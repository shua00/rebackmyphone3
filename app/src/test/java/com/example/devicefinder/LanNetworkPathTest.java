package com.example.devicefinder;

import static org.junit.Assert.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import org.junit.Test;

public class LanNetworkPathTest {
    private static Inet4Address ipv4(String value) throws Exception {
        return (Inet4Address) InetAddress.getByName(value);
    }

    @Test public void acceptsPrivateIpv4AndRejectsNonLanAddresses() throws Exception {
        assertTrue(LanNetworkPath.isUsableLanIpv4(ipv4("192.168.43.1")));
        assertTrue(LanNetworkPath.isUsableLanIpv4(ipv4("10.0.0.1")));
        assertTrue(LanNetworkPath.isUsableLanIpv4(ipv4("172.20.0.1")));
        assertFalse(LanNetworkPath.isUsableLanIpv4(ipv4("127.0.0.1")));
        assertFalse(LanNetworkPath.isUsableLanIpv4(ipv4("169.254.10.2")));
        assertFalse(LanNetworkPath.isUsableLanIpv4(ipv4("8.8.8.8")));
        assertFalse(LanNetworkPath.isUsableLanIpv4(ipv4("224.0.0.1")));
    }

    @Test public void hotspotCandidateDoesNotRequireHardCodedInterfaceName() throws Exception {
        LanNetworkPath path = LanNetworkPath.localInterfaceCandidate(
                "vendor_ap42", ipv4("192.168.43.1"), 24, ipv4("192.168.43.255"));
        assertEquals(LanNetworkPath.Kind.LOCAL_INTERFACE_CANDIDATE, path.kind);
        assertNull(path.network);
        assertEquals("vendor_ap42", path.interfaceName);
        assertEquals("192.168.43.1", path.localAddress.getHostAddress());
        assertEquals("192.168.43.255", path.broadcastAddress.getHostAddress());
        assertTrue(path.diagnosticFingerprint().contains("vendor_ap42"));
    }

    @Test public void noBroadcastIsRepresentableForPointToPointOrRestrictedInterfaces() throws Exception {
        LanNetworkPath path = LanNetworkPath.localInterfaceCandidate(
                "local0", ipv4("10.1.2.3"), 32, null);
        assertNull(path.broadcastAddress);
        assertTrue(path.toString().contains("broadcast=none"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void androidNetworkPathCannotBeCreatedWithoutNetwork() throws Exception {
        LanNetworkPath.fromAndroidNetwork(null, "wlan0", ipv4("192.168.1.2"),
                24, ipv4("192.168.1.255"));
    }
}
