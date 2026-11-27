package com.example.devicefinder;

import java.io.IOException;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;

/** Selects one immutable LAN path snapshot; ambiguous local interfaces fail closed. */
public final class LanTransferPathSelector {
    private LanTransferPathSelector() { }

    public static LanNetworkPath select(LanNetworkPath preferredNetworkPath,
            List<LanNetworkPath> localCandidates) throws IOException {
        if (preferredNetworkPath != null) {
            if (preferredNetworkPath.kind != LanNetworkPath.Kind.ANDROID_NETWORK
                    || preferredNetworkPath.network == null
                    || !isComplete(preferredNetworkPath, false)) {
                throw new IOException("Preferred Android LAN path is incomplete");
            }
            return preferredNetworkPath;
        }
        List<LanNetworkPath> eligible = new ArrayList<>();
        if (localCandidates != null) {
            for (LanNetworkPath candidate : localCandidates) {
                if (candidate != null
                        && candidate.kind == LanNetworkPath.Kind.LOCAL_INTERFACE_CANDIDATE
                        && candidate.network == null && isComplete(candidate, true)) {
                    eligible.add(candidate);
                }
            }
        }
        if (eligible.isEmpty()) throw new IOException("No unambiguous local LAN interface candidate");
        if (eligible.size() != 1)
            throw new IOException("Multiple local LAN interface candidates; refusing ambiguous path");
        return eligible.get(0);
    }

    private static boolean isComplete(LanNetworkPath path, boolean requireBroadcast) {
        if (path.interfaceName == null || path.interfaceName.isEmpty()
                || !LanNetworkPath.isUsableLanIpv4(path.localAddress)
                || path.prefixLength < 1 || path.prefixLength > (requireBroadcast ? 30 : 32))
            return false;
        if (!requireBroadcast) return true;
        Inet4Address expected = broadcast(path.localAddress, path.prefixLength);
        return expected != null && expected.equals(path.broadcastAddress);
    }

    private static Inet4Address broadcast(Inet4Address address, int prefix) {
        try {
            byte[] raw = address.getAddress();
            int value = ((raw[0] & 255) << 24) | ((raw[1] & 255) << 16)
                    | ((raw[2] & 255) << 8) | (raw[3] & 255);
            int mask = (int) (0xffffffffL << (32 - prefix));
            int result = (value & mask) | (~mask);
            return (Inet4Address) java.net.InetAddress.getByAddress(new byte[] {
                    (byte) (result >>> 24), (byte) (result >>> 16),
                    (byte) (result >>> 8), (byte) result });
        } catch (Exception invalid) {
            return null;
        }
    }
}
