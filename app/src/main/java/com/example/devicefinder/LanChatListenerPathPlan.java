package com.example.devicefinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic plan for chat ingress listeners derived from an already validated LAN path set.
 *
 * This class performs no interface enumeration, probing, socket creation, or thread creation.
 * Callers build a path set only at an explicit network/topology reconciliation boundary.
 * A LOCAL_INTERFACE_CANDIDATE is not assumed to be an AP merely from its interface name; it
 * remains an unrepresented local LAN candidate whose exact-address bind must succeed.
 */
public final class LanChatListenerPathPlan {
    private final LanNetworkPath primary;
    private final List<LanNetworkPath> secondary;
    private final String fingerprint;

    private LanChatListenerPathPlan(LanNetworkPath primary, List<LanNetworkPath> secondary) {
        this.primary = primary;
        this.secondary = Collections.unmodifiableList(new ArrayList<>(secondary));
        StringBuilder key = new StringBuilder(primary.diagnosticFingerprint());
        for (LanNetworkPath path : secondary) key.append("||").append(path.diagnosticFingerprint());
        this.fingerprint = key.toString();
    }

    public static LanChatListenerPathPlan from(LanTransferPathSet paths) {
        return from(paths, null);
    }

    /**
     * Prefer the same active Wi-Fi Network selected by the legacy primary socket binder.
     * This keeps the existing single-listener fast path and its path identity aligned when
     * Android exposes more than one Wi-Fi Network concurrently.
     */
    public static LanChatListenerPathPlan from(LanTransferPathSet paths,
            android.net.Network preferredNetwork) {
        if (paths == null || paths.size() == 0)
            throw new IllegalArgumentException("A non-empty validated LAN path set is required");
        LanNetworkPath primary = null;
        List<LanNetworkPath> secondary = new ArrayList<>();
        if (preferredNetwork != null) {
            for (LanNetworkPath path : paths.paths()) {
                if (path == null) throw new IllegalArgumentException("Path set contains a null path");
                if (path.kind == LanNetworkPath.Kind.ANDROID_NETWORK
                        && preferredNetwork.equals(path.network)) {
                    primary = path;
                    break;
                }
            }
            if (primary == null)
                throw new IllegalArgumentException("Preferred Wi-Fi Network is absent from validated path set");
        }
        // Without an explicit preferred Network, prefer a ConnectivityManager-backed path.
        if (primary == null) {
            for (LanNetworkPath path : paths.paths()) {
                if (path == null) throw new IllegalArgumentException("Path set contains a null path");
                if (primary == null && path.kind == LanNetworkPath.Kind.ANDROID_NETWORK) {
                    primary = path;
                }
            }
        }
        if (primary == null) primary = paths.paths().get(0);
        for (LanNetworkPath path : paths.paths()) {
            if (path != primary) secondary.add(path);
        }
        return new LanChatListenerPathPlan(primary, secondary);
    }

    public LanNetworkPath primary() { return primary; }
    public List<LanNetworkPath> secondary() { return secondary; }
    public int listenerCount() { return 1 + secondary.size(); }
    public boolean isMultiPath() { return !secondary.isEmpty(); }
    public String diagnosticFingerprint() { return fingerprint; }
}
