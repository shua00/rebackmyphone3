package com.example.devicefinder;

import android.net.Network;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Immutable description of one local IPv4 LAN path.
 *
 * A path may be backed by an Android Network (normal Wi-Fi client path), or may be only a
 * local-interface candidate (for example, an OEM hotspot AP interface not surfaced as a
 * TRANSPORT_WIFI Network). A candidate is diagnostic/discovery input only; it is NOT proof
 * that sockets can be safely bound to that interface or that peers are reachable.
 */
public final class LanNetworkPath {
    public enum Kind {
        ANDROID_NETWORK,
        LOCAL_INTERFACE_CANDIDATE
    }

    public final Kind kind;
    public final Network network; // null for interfaces not represented by ConnectivityManager
    public final String interfaceName;
    public final Inet4Address localAddress;
    public final int prefixLength;
    public final Inet4Address broadcastAddress;

    private LanNetworkPath(Kind kind, Network network, String interfaceName,
            Inet4Address localAddress, int prefixLength, Inet4Address broadcastAddress) {
        if (kind == null || interfaceName == null || interfaceName.isEmpty()
                || localAddress == null || prefixLength < 1 || prefixLength > 32) {
            throw new IllegalArgumentException("Invalid LAN network path");
        }
        this.kind = kind;
        this.network = network;
        this.interfaceName = interfaceName;
        this.localAddress = localAddress;
        this.prefixLength = prefixLength;
        this.broadcastAddress = broadcastAddress;
    }

    public static LanNetworkPath fromAndroidNetwork(Network network, String interfaceName,
            Inet4Address localAddress, int prefixLength, Inet4Address broadcastAddress) {
        if (network == null) throw new IllegalArgumentException("Android Network is required");
        return new LanNetworkPath(Kind.ANDROID_NETWORK, network, interfaceName,
                localAddress, prefixLength, broadcastAddress);
    }

    static LanNetworkPath localInterfaceCandidate(String interfaceName,
            Inet4Address localAddress, int prefixLength, Inet4Address broadcastAddress) {
        return new LanNetworkPath(Kind.LOCAL_INTERFACE_CANDIDATE, null, interfaceName,
                localAddress, prefixLength, broadcastAddress);
    }

    /**
     * Enumerate all active, non-loopback, site-local IPv4 interfaces. Do not guess AP interface
     * names: OEMs use different names, and the same name can mean different roles across devices.
     * Results are candidates only; caller must correlate them with routes/Network snapshots.
     */
    public static List<LanNetworkPath> enumerateLocalInterfaceCandidates() throws SocketException {
        List<LanNetworkPath> result = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return Collections.emptyList();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (networkInterface == null) continue;
            try {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
            } catch (SocketException unavailable) {
                continue;
            }
            for (InterfaceAddress entry : networkInterface.getInterfaceAddresses()) {
                if (entry == null || !(entry.getAddress() instanceof Inet4Address)) continue;
                Inet4Address address = (Inet4Address) entry.getAddress();
                if (!isUsableLanIpv4(address)) continue;
                int prefix = entry.getNetworkPrefixLength();
                if (prefix < 1 || prefix > 32) continue;
                InetAddress rawBroadcast = entry.getBroadcast();
                Inet4Address broadcast = rawBroadcast instanceof Inet4Address
                        ? (Inet4Address) rawBroadcast : null;
                result.add(localInterfaceCandidate(networkInterface.getName(),
                        address, prefix, broadcast));
            }
        }
        return Collections.unmodifiableList(result);
    }

    static boolean isUsableLanIpv4(Inet4Address address) {
        return address != null && address.isSiteLocalAddress()
                && !address.isAnyLocalAddress() && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress() && !address.isMulticastAddress();
    }

    /** Stable path identity for cache isolation; not an authentication credential. */
    public String diagnosticFingerprint() {
        return kind.name() + "|" + interfaceName + "|" + localAddress.getHostAddress()
                + "/" + prefixLength;
    }

    @Override public String toString() {
        return "LanNetworkPath{" + kind + ", interface=" + interfaceName
                + ", address=" + localAddress.getHostAddress() + "/" + prefixLength
                + ", broadcast=" + (broadcastAddress == null ? "none"
                        : broadcastAddress.getHostAddress()) + "}";
    }
}
