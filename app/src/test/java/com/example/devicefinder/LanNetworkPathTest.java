package com.example.devicefinder;

import static org.junit.Assert.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import android.net.NetworkCapabilities;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetwork;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LanNetworkPathTest {
    private static Inet4Address ipv4(String value) throws Exception {
        return (Inet4Address) InetAddress.getByName(value);
    }

    @Test public void vpnTransportIsNeverSelectedAsWifiLanEvenWithWifiUnderlyingTransport() {
        NetworkCapabilities wifi = new NetworkCapabilities();
        wifi.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        assertTrue(LanNetworkPath.isWifiLanNetwork(wifi));

        NetworkCapabilities vpnOverWifi = new NetworkCapabilities();
        vpnOverWifi.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        vpnOverWifi.addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        assertFalse(LanNetworkPath.isWifiLanNetwork(vpnOverWifi));

        NetworkCapabilities vpnOnly = new NetworkCapabilities();
        vpnOnly.addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        assertFalse(LanNetworkPath.isWifiLanNetwork(vpnOnly));
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

    @Test public void hotspotCandidateDatagramSenderPinsItsSourceAddress() throws Exception {
        LanNetworkPath candidate = LanNetworkPath.localInterfaceCandidate(
                "vendor_ap42", ipv4("192.168.43.1"), 24, ipv4("192.168.43.255"));
        RecordingDatagramSocket socket = new RecordingDatagramSocket();
        try {
            LanSocketBinding.bindDatagramSocket(candidate, socket);
            assertEquals(new java.net.InetSocketAddress("192.168.43.1", 0), socket.bound);
        } finally {
            socket.close();
        }
    }

    @Test public void hotspotCandidateStreamClientPinsItsSourceAddress() throws Exception {
        LanNetworkPath candidate = LanNetworkPath.localInterfaceCandidate(
                "vendor_ap42", ipv4("192.168.43.1"), 24, ipv4("192.168.43.255"));
        RecordingSocket socket = new RecordingSocket();
        LanSocketBinding.bindStreamSocket(candidate, socket);
        assertEquals(new java.net.InetSocketAddress("192.168.43.1", 0), socket.bound);
    }

    @Test public void serverListenerCanBindExactHotspotCandidateAddress() throws Exception {
        LanNetworkPath candidate = LanNetworkPath.localInterfaceCandidate(
                "vendor_ap42", ipv4("192.168.43.1"), 24, ipv4("192.168.43.255"));
        LanSocketBinding.validateServerSocketAddress(candidate, ipv4("192.168.43.1"));
        try {
            LanSocketBinding.validateServerSocketAddress(candidate, ipv4("192.168.43.2"));
            fail("listener must bind only to the selected AP address");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("does not match"));
        }
    }

    @Test public void hotspotCandidateCanConstructIndependentDiscovery() throws Exception {
        LanNetworkPath candidate = LanNetworkPath.localInterfaceCandidate(
                "vendor_ap42", ipv4("192.168.43.1"), 24, ipv4("192.168.43.255"));
        LanTransferDiscovery discovery = new LanTransferDiscovery(
                org.robolectric.RuntimeEnvironment.getApplication(), candidate, "local-peer",
                candidate.localAddress, candidate.broadcastAddress, "ap-fingerprint", () -> 38471);
        assertNotNull(discovery);
        discovery.close();
    }

    private static final class RecordingSocket extends java.net.Socket {
        java.net.SocketAddress bound;
        @Override public void bind(java.net.SocketAddress address) { bound = address; }
    }

    private static final class RecordingDatagramSocket extends java.net.DatagramSocket {
        java.net.SocketAddress bound;
        RecordingDatagramSocket() throws java.net.SocketException { super((java.net.SocketAddress) null); }
        @Override public void bind(java.net.SocketAddress address) { bound = address; }
    }

    @Test public void hotspotDiscoveryAcceptsOnlyTheSelectedSubnet() throws Exception {
        LanNetworkPath candidate = LanNetworkPath.localInterfaceCandidate(
                "vendor_ap42", ipv4("192.168.43.1"), 24, ipv4("192.168.43.255"));
        assertTrue(LanTransferDiscovery.isOnSelectedSubnet(candidate, ipv4("192.168.43.100")));
        assertFalse(LanTransferDiscovery.isOnSelectedSubnet(candidate, ipv4("192.168.44.100")));
        assertFalse(LanTransferDiscovery.isOnSelectedSubnet(candidate, ipv4("127.0.0.1")));
    }

    @Test public void hotspotAddressOrPrefixChangeProducesDifferentPathIdentity() throws Exception {
        LanNetworkPath original = LanNetworkPath.localInterfaceCandidate(
                "vendor_ap42", ipv4("192.168.43.1"), 24, ipv4("192.168.43.255"));
        LanNetworkPath changedAddress = LanNetworkPath.localInterfaceCandidate(
                "vendor_ap42", ipv4("192.168.44.1"), 24, ipv4("192.168.44.255"));
        LanNetworkPath changedPrefix = LanNetworkPath.localInterfaceCandidate(
                "vendor_ap42", ipv4("192.168.43.1"), 25, ipv4("192.168.43.127"));
        assertNotEquals(original.diagnosticFingerprint(), changedAddress.diagnosticFingerprint());
        assertNotEquals(original.diagnosticFingerprint(), changedPrefix.diagnosticFingerprint());
    }

    @Test public void serverListenerAddressMustMatchSelectedPath() throws Exception {
        LanNetworkPath path = LanNetworkPath.fromAndroidNetwork(ShadowNetwork.newInstance(42),
                "wlan0", ipv4("192.168.1.2"), 24, ipv4("192.168.1.255"));
        try {
            LanSocketBinding.validateServerSocketAddress(path, ipv4("192.168.1.3"));
            fail("listener address must be from the selected path snapshot");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("does not match"));
        }
        LanSocketBinding.validateServerSocketAddress(path, ipv4("192.168.1.2"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void androidNetworkPathCannotBeCreatedWithoutNetwork() throws Exception {
        LanNetworkPath.fromAndroidNetwork(null, "wlan0", ipv4("192.168.1.2"),
                24, ipv4("192.168.1.255"));
    }
}
