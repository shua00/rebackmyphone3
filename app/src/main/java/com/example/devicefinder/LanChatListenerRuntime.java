package com.example.devicefinder;

import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Path-keyed ownership boundary for LAN chat UDP listeners.
 *
 * This class owns registration epochs, not threads or socket closing: callers create/bind
 * sockets and stop receive loops outside this monitor. A lease is immutable and identifies
 * exactly one path/socket/generation tuple. Removing an old lease can never remove a replacement.
 *
 * The stable single-path case has no worker, timer, polling, or background activity here.
 */
public final class LanChatListenerRuntime {
    public static final class Lease {
        public final String pathFingerprint;
        public final DatagramSocket socket;
        public final long generation;
        public final LanChatPathRegistry.Listener registryListener;

        private Lease(String path, DatagramSocket socket, long generation,
                LanChatPathRegistry.Listener registryListener) {
            this.pathFingerprint = path;
            this.socket = socket;
            this.generation = generation;
            this.registryListener = registryListener;
        }

        public boolean isSocketUsable() {
            return socket != null && !socket.isClosed() && socket.isBound();
        }
    }

    private final LanChatPathRegistry registry;
    private final Map<String, Lease> leases = new HashMap<>();

    public LanChatListenerRuntime(LanChatPathRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("Path registry is required");
        this.registry = registry;
    }

    /** Register only after the caller has successfully bound the socket to this exact path. */
    public synchronized Lease register(String pathFingerprint, DatagramSocket socket,
            long generation) {
        if (pathFingerprint == null || pathFingerprint.trim().isEmpty()
                || socket == null || socket.isClosed() || !socket.isBound() || generation < 0L) {
            throw new IllegalArgumentException("A bound socket, path and valid generation are required");
        }
        LanChatPathRegistry.Listener registered =
                registry.registerListener(pathFingerprint, socket, generation);
        Lease lease = new Lease(pathFingerprint, socket, generation, registered);
        leases.put(pathFingerprint, lease);
        return lease;
    }

    /**
     * Compare-and-remove the exact lease. Socket closing and receive-thread joining must happen
     * outside this monitor to avoid blocking path registration or other listener teardown.
     */
    public synchronized boolean remove(Lease expected) {
        if (expected == null || leases.get(expected.pathFingerprint) != expected) return false;
        boolean removed = registry.removeListener(expected.pathFingerprint,
                expected.socket, expected.generation);
        // Another lifecycle event may already have invalidated this exact lease in the
        // underlying registry. Retire our local owner record either way; never retain a
        // dead lease merely because path invalidation won the race.
        leases.remove(expected.pathFingerprint);
        return removed;
    }

    public synchronized boolean isCurrent(Lease lease) {
        return lease != null && leases.get(lease.pathFingerprint) == lease
                && lease.isSocketUsable()
                && registry.getListener(lease.pathFingerprint) == lease.registryListener;
    }

    public synchronized Lease get(String pathFingerprint) {
        return leases.get(pathFingerprint);
    }

    /** Snapshot is allocated only for lifecycle/diagnostic operations, never per packet. */
    public synchronized List<Lease> snapshot() {
        if (leases.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(leases.values()));
    }

    public synchronized int listenerCount() {
        return leases.size();
    }
}
