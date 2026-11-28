package com.example.devicefinder;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Narrow compatibility gate for the legacy cached-endpoint chat fallback.
 *
 * A peer-wide endpoint is never sufficient by itself. The caller must prove that the durable
 * authentication lease belongs to the exact current listener epoch, that this is the only
 * active chat listener, and that the endpoint remains on the current listener's IPv4 subnet.
 */
public final class LanChatCachedRoutePolicy {
    private LanChatCachedRoutePolicy() {}

    public static boolean isEligible(boolean singleListener,
            boolean durableLeaseMatchesCurrentListener,
            Inet4Address localAddress, int prefixLength, Inet4Address peerAddress) {
        if (!singleListener || !durableLeaseMatchesCurrentListener
                || localAddress == null || peerAddress == null
                || prefixLength < 1 || prefixLength > 32
                || !LanNetworkPath.isUsableLanIpv4(localAddress)
                || !LanNetworkPath.isUsableLanIpv4(peerAddress)) return false;
        byte[] local = localAddress.getAddress();
        byte[] peer = peerAddress.getAddress();
        int remaining = prefixLength;
        for (int i = 0; i < 4; i++) {
            int bits = Math.min(8, remaining);
            int mask = bits == 0 ? 0 : (0xff << (8 - bits)) & 0xff;
            if (((local[i] & 0xff) & mask) != ((peer[i] & 0xff) & mask)) return false;
            remaining -= bits;
        }
        return true;
    }

    /** Parse only dotted-decimal IPv4 literals; never perform DNS on an Outbox value. */
    public static Inet4Address parseIpv4Literal(String value) {
        if (value == null || value.isEmpty()) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) return null;
                int octet = Integer.parseInt(parts[i]);
                if (octet < 0 || octet > 255) return null;
                bytes[i] = (byte) octet;
            }
            return (Inet4Address) InetAddress.getByAddress(bytes);
        } catch (Exception invalid) {
            return null;
        }
    }
}
