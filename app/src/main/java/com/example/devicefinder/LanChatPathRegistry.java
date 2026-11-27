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
        private PeerRoute(String peer, String path, InetSocketAddress endpoint,
                long verifiedAt, long expiresAt) {
            this.peerId = peer; this.pathFingerprint = path; this.endpoint = endpoint;
            this.verifiedAtMs = verifiedAt; this.expiresAtMs = expiresAt;
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
        if (previous != null && previous.socket != socket) {
            // A re-created listener has a new receive/send ownership epoch. Do not
            // carry peer routes verified against the previous socket into that epoch.
            removeRoutesForPath(path);
        }
        Listener value = new Listener(path, socket, generation);
        listeners.put(path, value);
        return value;
    }

    /** Compare-and-remove: a delayed old thread cannot tear down a replacement listener. */
    public synchronized boolean removeListener(String path, DatagramSocket expectedSocket) {
        Listener current = listeners.get(path);
        if (current == null || current.socket != expectedSocket) return false;
        listeners.remove(path);
        removeRoutesForPath(path);
        return true;
    }

    public synchronized Listener getListener(String path) { return listeners.get(path); }

    /** Call only after authenticated challenge/response and durable peer-route commit. */
    public synchronized PeerRoute recordVerifiedPeer(String peer, String path,
            InetSocketAddress endpoint, long verifiedAt, long expiresAt) {
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
        Map<String, PeerRoute> peerRoutes = routes.get(peer);
        if (peerRoutes == null) { peerRoutes = new HashMap<>(); routes.put(peer, peerRoutes); }
        PeerRoute route = new PeerRoute(peer, path, endpoint, verifiedAt, expiresAt);
        peerRoutes.put(path, route);
        return route;
    }

    /** Fresh routes are returned newest-first, but only while their own listener is active. */
    public synchronized List<PeerRoute> getFreshPeerRoutes(String peer, long now) {
        Map<String, PeerRoute> peerRoutes = routes.get(peer);
        if (peerRoutes == null) return Collections.emptyList();
        List<PeerRoute> result = new ArrayList<>();
        Iterator<PeerRoute> iterator = peerRoutes.values().iterator();
        while (iterator.hasNext()) {
            PeerRoute route = iterator.next();
            if (now >= route.expiresAtMs) iterator.remove();
            else {
                Listener listener = listeners.get(route.pathFingerprint);
                if (listener != null && listener.isUsable()) result.add(route);
            }
        }
        if (peerRoutes.isEmpty()) routes.remove(peer);
        Collections.sort(result, new Comparator<PeerRoute>() {
            @Override public int compare(PeerRoute a, PeerRoute b) {
                int newest = Long.compare(b.verifiedAtMs, a.verifiedAtMs);
                return newest != 0 ? newest : a.pathFingerprint.compareTo(b.pathFingerprint);
            }
        });
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
