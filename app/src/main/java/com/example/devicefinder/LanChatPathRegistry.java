package com.example.devicefinder;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe ownership table for independent STA/AP chat listeners and authenticated peer
 * endpoints. Fingerprints isolate paths; they are diagnostic keys, never authentication.
 * Listener and route removal is path-scoped. A stale listener can only remove itself.
 */
public final class LanChatPathRegistry {
    public static final class Listener {
        public final String pathFingerprint;
        public final DatagramSocket socket;
        public final long generation;
        private Listener(String path, DatagramSocket socket, long generation) {
            this.pathFingerprint = path; this.socket = socket; this.generation = generation;
        }
        public boolean isUsable() {
            return socket != null && !socket.isClosed() && socket.isBound();
        }
    }

    public static final class PeerRoute {
        public final String peerId;
        public final String pathFingerprint;
        public final InetSocketAddress endpoint;
        public final long verifiedAtMs;
        public final long expiresAtMs;
        public final long listenerGeneration;
        private PeerRoute(String peer, String path, InetSocketAddress endpoint,
                long verifiedAt, long expiresAt, long listenerGeneration) {
            this.peerId = peer; this.pathFingerprint = path; this.endpoint = endpoint;
            this.verifiedAtMs = verifiedAt; this.expiresAtMs = expiresAt;
            this.listenerGeneration = listenerGeneration;
        }
    }

    /** Immutable route + exact listener epoch captured under the registry lock. */
    public static final class RouteBinding {
        public final PeerRoute route;
        public final Listener listener;
        private RouteBinding(PeerRoute route, Listener listener) {
            this.route = route;
            this.listener = listener;
        }
        public boolean isCurrentAndUsable() {
            return listener != null && listener.isUsable()
                    && route != null
                    && route.pathFingerprint.equals(listener.pathFingerprint)
                    && route.listenerGeneration == listener.generation;
        }
    }

    private final Map<String, Listener> listeners = new HashMap<>();
    private final Map<String, Map<String, PeerRoute>> routes = new HashMap<>();

    private static void requireKey(String value, String label) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(label + " is required");
    }

    public synchronized Listener registerListener(String path, DatagramSocket socket,
            long generation) {
        requireKey(path, "path fingerprint");
        if (socket == null || socket.isClosed() || !socket.isBound() || generation < 0L)
            throw new IllegalArgumentException("Listener must be open, bound, and have a valid generation");
        Listener previous = listeners.get(path);
        if (previous != null && (previous.socket != socket || previous.generation != generation)) {
            // A re-created listener has a new receive/send ownership epoch. Do not
            // carry peer routes verified against the previous socket into that epoch.
            removeRoutesForPath(path);
        }
        Listener value = new Listener(path, socket, generation);
        listeners.put(path, value);
        return value;
    }

    /** Compatibility overload; lifecycle owners should also supply their generation. */
    public synchronized boolean removeListener(String path, DatagramSocket expectedSocket) {
        Listener current = listeners.get(path);
        return current != null && removeListener(path, expectedSocket, current.generation);
    }

    /** Compare-and-remove by socket AND epoch: stale receive loops cannot revoke a newer owner. */
    public synchronized boolean removeListener(String path, DatagramSocket expectedSocket,
            long expectedGeneration) {
        Listener current = listeners.get(path);
        if (current == null || current.socket != expectedSocket
                || current.generation != expectedGeneration) return false;
        listeners.remove(path);
        removeRoutesForPath(path);
        return true;
    }

    public synchronized Listener getListener(String path) { return listeners.get(path); }

    /** Call only after authenticated challenge/response and durable peer-route commit. */
    public synchronized PeerRoute recordVerifiedPeer(String peer, String path,
            InetSocketAddress endpoint, long verifiedAt, long expiresAt) {
        return recordVerifiedPeerLocked(peer, path, endpoint, verifiedAt, expiresAt, null, -1L);
    }

    /**
     * Exact-ingress admission: socket identity and generation are checked atomically with
     * route insertion under the same monitor used by listener registration/replacement.
     * A response received by an old socket can therefore never be stamped with a new epoch.
     */
    public synchronized PeerRoute recordVerifiedPeer(String peer, String path,
            InetSocketAddress endpoint, long verifiedAt, long expiresAt,
            DatagramSocket expectedSocket, long expectedGeneration) {
        if (expectedSocket == null || expectedGeneration < 0L)
            throw new IllegalArgumentException("Expected listener socket and generation are required");
        return recordVerifiedPeerLocked(peer, path, endpoint, verifiedAt, expiresAt,
                expectedSocket, expectedGeneration);
    }

    private PeerRoute recordVerifiedPeerLocked(String peer, String path,
            InetSocketAddress endpoint, long verifiedAt, long expiresAt,
            DatagramSocket expectedSocket, long expectedGeneration) {
        requireKey(peer, "peer id"); requireKey(path, "path fingerprint");
        if (endpoint == null || endpoint.isUnresolved()
                || !(endpoint.getAddress() instanceof Inet4Address)
                || !endpoint.getAddress().isSiteLocalAddress()
                || endpoint.getAddress().isLoopbackAddress()
                || endpoint.getAddress().isAnyLocalAddress()
                || endpoint.getPort() < 1 || endpoint.getPort() > 65535
                || expiresAt <= verifiedAt)
            throw new IllegalArgumentException("Invalid verified LAN endpoint or lease");
        Listener listener = listeners.get(path);
        if (listener == null || !listener.isUsable())
            throw new IllegalStateException("Cannot verify a peer route without its active path listener");
        if (expectedSocket != null && (listener.socket != expectedSocket
                || listener.generation != expectedGeneration))
            throw new IllegalStateException("Ingress listener epoch changed before peer route admission");
        Map<String, PeerRoute> peerRoutes = routes.get(peer);
        if (peerRoutes == null) { peerRoutes = new HashMap<>(); routes.put(peer, peerRoutes); }
        PeerRoute route = new PeerRoute(peer, path, endpoint, verifiedAt, expiresAt,
                listener.generation);
        peerRoutes.put(path, route);
        return route;
    }

    /**
     * Capture each fresh authenticated route together with the exact listener/socket epoch.
     * This is the preferred send-side API: callers must not re-resolve a listener by path
     * after obtaining a route, because a listener replacement could otherwise cross epochs.
     */
    public synchronized List<RouteBinding> getFreshPeerRouteBindings(String peer, long now) {
        Map<String, PeerRoute> peerRoutes = routes.get(peer);
        if (peerRoutes == null) return Collections.emptyList();
        List<RouteBinding> result = new ArrayList<>();
        Iterator<PeerRoute> iterator = peerRoutes.values().iterator();
        while (iterator.hasNext()) {
            PeerRoute route = iterator.next();
            if (now >= route.expiresAtMs) {
                iterator.remove();
                continue;
            }
            Listener listener = listeners.get(route.pathFingerprint);
            if (listener == null || !listener.isUsable()
                    || listener.generation != route.listenerGeneration) {
                iterator.remove();
            } else {
                result.add(new RouteBinding(route, listener));
            }
        }
        if (peerRoutes.isEmpty()) routes.remove(peer);
        Collections.sort(result, new Comparator<RouteBinding>() {
            @Override public int compare(RouteBinding a, RouteBinding b) {
                int newest = Long.compare(b.route.verifiedAtMs, a.route.verifiedAtMs);
                return newest != 0 ? newest
                        : a.route.pathFingerprint.compareTo(b.route.pathFingerprint);
            }
        });
        return Collections.unmodifiableList(result);
    }

    /**
     * O(1) preferred-path lookup for the common single-path fast path. Unlike
     * getFreshPeerRouteBindings(), this does not allocate or enumerate a route list.
     */
    public synchronized RouteBinding getFreshPeerRouteBinding(String peer, String path, long now) {
        if (peer == null || peer.trim().isEmpty() || path == null || path.trim().isEmpty())
            return null;
        Listener listener = listeners.get(path);
        if (listener == null || !listener.isUsable()) return null;
        Map<String, PeerRoute> peerRoutes = routes.get(peer);
        if (peerRoutes == null) return null;
        PeerRoute route = peerRoutes.get(path);
        if (route == null) return null;
        if (now >= route.expiresAtMs || route.listenerGeneration != listener.generation) {
            peerRoutes.remove(path);
            if (peerRoutes.isEmpty()) routes.remove(peer);
            return null;
        }
        return new RouteBinding(route, listener);
    }

    /**
     * Resolve an authenticated ACK/source endpoint only within the exact ingress path epoch.
     * Direct path lookup keeps the single-path ACK fast path O(1); it never searches other
     * interfaces or borrows another path's authentication.
     */
    public synchronized RouteBinding findCurrentPeerRouteBinding(String peer, String path,
            DatagramSocket expectedSocket, long expectedGeneration,
            InetSocketAddress expectedEndpoint, long now) {
        if (peer == null || peer.trim().isEmpty() || path == null || path.trim().isEmpty()
                || expectedSocket == null || expectedEndpoint == null
                || expectedEndpoint.isUnresolved()) return null;
        Listener listener = listeners.get(path);
        if (listener == null || listener.socket != expectedSocket
                || listener.generation != expectedGeneration || !listener.isUsable()) return null;
        Map<String, PeerRoute> peerRoutes = routes.get(peer);
        if (peerRoutes == null) return null;
        PeerRoute route = peerRoutes.get(path);
        if (route == null) return null;
        if (now >= route.expiresAtMs || route.listenerGeneration != expectedGeneration
                || !route.endpoint.equals(expectedEndpoint)) {
            if (now >= route.expiresAtMs || route.listenerGeneration != expectedGeneration) {
                peerRoutes.remove(path);
                if (peerRoutes.isEmpty()) routes.remove(peer);
            }
            return null;
        }
        return new RouteBinding(route, listener);
    }

    /**
     * Revalidate a previously captured binding immediately before dispatch. This does not
     * hold the registry lock across socket I/O; callers must still check socket/generation
     * ownership at the send boundary and treat a failed check as a stale-path retry.
     */
    public synchronized boolean isCurrentBinding(RouteBinding binding) {
        if (binding == null || binding.route == null || binding.listener == null
                || !binding.listener.isUsable()) return false;
        Listener current = listeners.get(binding.route.pathFingerprint);
        if (current != binding.listener
                || current.generation != binding.route.listenerGeneration) return false;
        Map<String, PeerRoute> peerRoutes = routes.get(binding.route.peerId);
        return peerRoutes != null
                && peerRoutes.get(binding.route.pathFingerprint) == binding.route;
    }

    /** Compatibility view for callers that only need endpoint metadata. */
    public synchronized List<PeerRoute> getFreshPeerRoutes(String peer, long now) {
        List<RouteBinding> bindings = getFreshPeerRouteBindings(peer, now);
        if (bindings.isEmpty()) return Collections.emptyList();
        List<PeerRoute> result = new ArrayList<>(bindings.size());
        for (RouteBinding binding : bindings) result.add(binding.route);
        return Collections.unmodifiableList(result);
    }

    public synchronized void invalidatePath(String path) {
        listeners.remove(path);
        removeRoutesForPath(path);
    }

    private void removeRoutesForPath(String path) {
        Iterator<Map.Entry<String, Map<String, PeerRoute>>> iterator = routes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map<String, PeerRoute> peerRoutes = iterator.next().getValue();
            peerRoutes.remove(path);
            if (peerRoutes.isEmpty()) iterator.remove();
        }
    }

    public synchronized int listenerCount() { return listeners.size(); }
}
