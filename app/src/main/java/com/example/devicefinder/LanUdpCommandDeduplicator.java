package com.example.devicefinder;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Service-scoped duplicate suppression for legacy UDP commands.
 *
 * Every ingress listener must share one instance. A per-receive-loop map is insufficient once
 * STA/AP listeners coexist because the same broadcast can arrive on both paths. This helper
 * is event-driven, synchronized only for the tiny in-memory admission operation, and creates
 * no worker, timer, socket, or periodic activity.
 */
public final class LanUdpCommandDeduplicator {
    private static final long DUPLICATE_WINDOW_MS = 3000L;
    private static final long RETENTION_WINDOW_MS = 30000L;
    private static final int MAX_ENTRIES = 50;

    private final Map<String, Long> acceptedAt = new HashMap<>();

    /**
     * Returns true for the first occurrence and false for repeats inside the suppression window.
     * Callers should pass a monotonic clock such as SystemClock.elapsedRealtime().
     */
    public synchronized boolean shouldAccept(String key, long nowElapsedMs) {
        if (key == null || key.trim().isEmpty() || nowElapsedMs < 0L) return false;
        Long previous = acceptedAt.get(key);
        if (previous != null && nowElapsedMs >= previous
                && nowElapsedMs - previous < DUPLICATE_WINDOW_MS) {
            return false;
        }
        acceptedAt.put(key, nowElapsedMs);
        if (acceptedAt.size() > MAX_ENTRIES) {
            long cutoff = nowElapsedMs - RETENTION_WINDOW_MS;
            Iterator<Map.Entry<String, Long>> iterator = acceptedAt.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue() < cutoff) iterator.remove();
            }
            while (acceptedAt.size() > MAX_ENTRIES) {
                String oldestKey = null;
                long oldestAt = Long.MAX_VALUE;
                for (Map.Entry<String, Long> entry : acceptedAt.entrySet()) {
                    if (entry.getValue() < oldestAt) {
                        oldestAt = entry.getValue();
                        oldestKey = entry.getKey();
                    }
                }
                if (oldestKey == null) break;
                acceptedAt.remove(oldestKey);
            }
        }
        return true;
    }

    public synchronized int size() {
        return acceptedAt.size();
    }
}
