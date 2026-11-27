package com.example.devicefinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Event-driven LAN route selection. The stable single-listener case deliberately takes
 * one O(1) peer+path lookup and never constructs a route list or fallback plan.
 *
 * Multi-path selection is activated only when more than one usable listener is registered.
 * In the absence of measured path-quality history, fresh authenticated routes are ordered by
 * verification recency; an explicitly preferred path wins when it has a fresh binding.
 * Fallback candidates are materialized only after the caller has decided a retry is needed.
 */
public final class LanAdaptiveRouteSelector {
    public static final int MAX_FALLBACK_ROUTES = 2;

    private LanAdaptiveRouteSelector() { }

    public static LanChatPathRegistry.RouteBinding selectPrimaryBinding(
            LanChatPathRegistry registry, String peerId, String preferredPath, long nowElapsedMs) {
        if (registry == null || peerId == null || peerId.trim().isEmpty()) return null;

        // Keep the common ordinary-router path allocation-free beyond the returned binding.
        if (registry.listenerCount() <= 1) {
            return registry.getFreshPeerRouteBinding(peerId, preferredPath, nowElapsedMs);
        }

        List<LanChatPathRegistry.RouteBinding> bindings =
                registry.getFreshPeerRouteBindings(peerId, nowElapsedMs);
        if (bindings.isEmpty()) return null;
        if (preferredPath != null && !preferredPath.isEmpty()) {
            for (LanChatPathRegistry.RouteBinding binding : bindings) {
                if (preferredPath.equals(binding.route.pathFingerprint)
                        && registry.isCurrentBinding(binding)) return binding;
            }
        }
        for (LanChatPathRegistry.RouteBinding binding : bindings) {
            if (registry.isCurrentBinding(binding)) return binding;
        }
        return null;
    }

    /**
     * Called only after a primary-path failure/timeout decision. This method is intentionally
     * absent from the single-listener fast path and returns at most the requested bounded count.
     */
    public static List<LanChatPathRegistry.RouteBinding> selectFallbackBindings(
            LanChatPathRegistry registry, String peerId,
            LanChatPathRegistry.RouteBinding primary, String preferredPath,
            long nowElapsedMs, int maxFallbacks) {
        if (registry == null || peerId == null || peerId.trim().isEmpty()
                || maxFallbacks <= 0 || registry.listenerCount() <= 1) {
            return Collections.emptyList();
        }
        int limit = Math.min(maxFallbacks, MAX_FALLBACK_ROUTES);
        List<LanChatPathRegistry.RouteBinding> available =
                registry.getFreshPeerRouteBindings(peerId, nowElapsedMs);
        if (available.isEmpty()) return Collections.emptyList();

        List<LanChatPathRegistry.RouteBinding> result = new ArrayList<>(limit);
        // If the normal preferred path was not the primary, try it first as a fallback.
        if (preferredPath != null && !preferredPath.isEmpty()
                && (primary == null || !preferredPath.equals(primary.route.pathFingerprint))) {
            for (LanChatPathRegistry.RouteBinding binding : available) {
                if (preferredPath.equals(binding.route.pathFingerprint)
                        && registry.isCurrentBinding(binding)) {
                    result.add(binding);
                    break;
                }
            }
        }
        for (LanChatPathRegistry.RouteBinding binding : available) {
            if (result.size() >= limit) break;
            if (primary != null && primary.route.pathFingerprint.equals(binding.route.pathFingerprint))
                continue;
            boolean duplicate = false;
            for (LanChatPathRegistry.RouteBinding selected : result) {
                if (selected.route.pathFingerprint.equals(binding.route.pathFingerprint)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate && registry.isCurrentBinding(binding)) result.add(binding);
        }
        return result.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(result);
    }
}
