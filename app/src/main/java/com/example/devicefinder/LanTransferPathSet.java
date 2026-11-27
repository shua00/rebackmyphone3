package com.example.devicefinder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of independently usable LAN paths.
 *
 * A device may have more than one LAN attachment at once (for example Wi-Fi STA plus a
 * hotspot AP interface). Paths are transport endpoints, not peer identities. This class
 * deliberately does not claim that different subnets can route to one another.
 */
public final class LanTransferPathSet {
    private final List<LanNetworkPath> paths;
    private final String fingerprint;

    private LanTransferPathSet(List<LanNetworkPath> paths) {
        this.paths = Collections.unmodifiableList(new ArrayList<>(paths));
        StringBuilder value = new StringBuilder();
        for (LanNetworkPath path : paths) {
            if (value.length() > 0) value.append("||");
            value.append(path.diagnosticFingerprint());
        }
        this.fingerprint = value.toString();
    }

    /**
     * Build a path set from all verified Android Wi-Fi Network paths and at most one
     * unrepresented local-interface candidate (normally an OEM hotspot AP).
     *
     * Interfaces already represented by Android Networks, or explicitly classified as
     * cellular/VPN by the caller, are never admitted as local-interface candidates.
     * Multiple unrepresented candidates remain ambiguous and fail closed.
     */
    public static LanTransferPathSet select(List<LanNetworkPath> androidNetworkPaths,
            List<LanNetworkPath> localCandidates, Set<String> excludedInterfaces)
            throws IOException {
        Map<String, LanNetworkPath> selected = new LinkedHashMap<>();
        Set<String> representedInterfaces = new HashSet<>();
        Set<String> representedAddresses = new HashSet<>();

        if (androidNetworkPaths != null) {
            for (LanNetworkPath path : androidNetworkPaths) {
                if (path == null) continue;
                LanTransferPathSelector.select(path, Collections.<LanNetworkPath>emptyList());
                String key = path.diagnosticFingerprint();
                LanNetworkPath previous = selected.put(key, path);
                if (previous != null && !samePath(previous, path))
                    throw new IOException("Conflicting Android LAN paths share one fingerprint");
                representedInterfaces.add(path.interfaceName);
                representedAddresses.add(path.localAddress.getHostAddress());
            }
        }

        List<LanNetworkPath> eligibleCandidates = new ArrayList<>();
        if (localCandidates != null) {
            for (LanNetworkPath candidate : localCandidates) {
                if (candidate == null
                        || candidate.kind != LanNetworkPath.Kind.LOCAL_INTERFACE_CANDIDATE
                        || candidate.network != null) continue;
                if (excludedInterfaces != null && excludedInterfaces.contains(candidate.interfaceName))
                    continue;
                if (representedInterfaces.contains(candidate.interfaceName)
                        || representedAddresses.contains(candidate.localAddress.getHostAddress()))
                    continue;
                // Reuse the existing strict subnet/broadcast validation for AP candidates.
                LanTransferPathSelector.select(null, Collections.singletonList(candidate));
                eligibleCandidates.add(candidate);
            }
        }

        if (eligibleCandidates.size() > 1)
            throw new IOException("Multiple unrepresented LAN candidates; refusing ambiguous AP path");
        if (eligibleCandidates.size() == 1) {
            LanNetworkPath candidate = eligibleCandidates.get(0);
            selected.put(candidate.diagnosticFingerprint(), candidate);
        }
        if (selected.isEmpty()) throw new IOException("No usable LAN paths");
        return new LanTransferPathSet(new ArrayList<>(selected.values()));
    }

    private static boolean samePath(LanNetworkPath left, LanNetworkPath right) {
        return left.kind == right.kind && left.network == right.network
                && left.interfaceName.equals(right.interfaceName)
                && left.localAddress.equals(right.localAddress)
                && left.prefixLength == right.prefixLength
                && (left.broadcastAddress == null
                    ? right.broadcastAddress == null
                    : left.broadcastAddress.equals(right.broadcastAddress));
    }

    public List<LanNetworkPath> paths() { return paths; }
    public int size() { return paths.size(); }
    public String diagnosticFingerprint() { return fingerprint; }

    public boolean containsFingerprint(String value) {
        if (value == null) return false;
        for (LanNetworkPath path : paths)
            if (value.equals(path.diagnosticFingerprint())) return true;
        return false;
    }
}
