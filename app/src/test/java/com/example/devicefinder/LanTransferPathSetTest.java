package com.example.devicefinder;

import static org.junit.Assert.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetwork;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LanTransferPathSetTest {
    private static Inet4Address ip(String value) throws Exception {
        return (Inet4Address) InetAddress.getByName(value);
    }

    private static LanNetworkPath wifi(String iface, String address, int id) throws Exception {
        return LanNetworkPath.fromAndroidNetwork(ShadowNetwork.newInstance(id), iface,
                ip(address), 24, ip("192.168.1.255"));
    }

    private static LanNetworkPath ap(String iface, String address) throws Exception {
        Inet4Address local = ip(address);
        byte[] raw = local.getAddress();
        raw[3] = (byte) 255;
        return LanNetworkPath.localInterfaceCandidate(iface, local, 24,
                (Inet4Address) InetAddress.getByAddress(raw));
    }

    @Test public void retainsStaAndOneUnrepresentedHotspotApPath() throws Exception {
        LanNetworkPath sta = wifi("wlan_sta", "192.168.1.20", 1);
        LanNetworkPath hotspot = ap("vendor_ap", "192.168.43.1");
        LanTransferPathSet paths = LanTransferPathSet.select(
                Collections.singletonList(sta), Collections.singletonList(hotspot),
                Collections.<String>emptySet());
        assertEquals(2, paths.size());
        assertTrue(paths.containsFingerprint(sta.diagnosticFingerprint()));
        assertTrue(paths.containsFingerprint(hotspot.diagnosticFingerprint()));
    }

    @Test public void excludesCandidatesAlreadyRepresentedByAndroidNetwork() throws Exception {
        LanNetworkPath sta = wifi("wlan0", "192.168.1.20", 1);
        LanNetworkPath duplicate = ap("wlan0", "192.168.1.21");
        LanTransferPathSet paths = LanTransferPathSet.select(
                Collections.singletonList(sta), Collections.singletonList(duplicate),
                Collections.<String>emptySet());
        assertEquals(1, paths.size());
    }

    @Test public void excludesCellularAndVpnInterfaces() throws Exception {
        LanNetworkPath sta = wifi("wlan0", "192.168.1.20", 1);
        LanTransferPathSet paths = LanTransferPathSet.select(
                Collections.singletonList(sta),
                Arrays.asList(ap("rmnet_data0", "10.0.0.2"), ap("tun0", "10.9.0.2")),
                new HashSet<>(Arrays.asList("rmnet_data0", "tun0")));
        assertEquals(1, paths.size());
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsMultipleUnrepresentedCandidates() throws Exception {
        LanTransferPathSet.select(Collections.singletonList(wifi("wlan0", "192.168.1.20", 1)),
                Arrays.asList(ap("ap0", "192.168.43.1"), ap("eth0", "192.168.44.1")),
                Collections.<String>emptySet());
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsEmptyPathSet() throws Exception {
        LanTransferPathSet.select(Collections.<LanNetworkPath>emptyList(),
                Collections.<LanNetworkPath>emptyList(), Collections.<String>emptySet());
    }

    @Test public void deduplicatesRepeatedAndroidPathSnapshot() throws Exception {
        LanNetworkPath path = wifi("wlan0", "192.168.1.20", 1);
        LanTransferPathSet paths = LanTransferPathSet.select(
                Arrays.asList(path, path), Collections.<LanNetworkPath>emptyList(),
                Collections.<String>emptySet());
        assertEquals(1, paths.size());
    }

    @Test public void fingerprintChangesWhenOnePathChanges() throws Exception {
        LanTransferPathSet first = LanTransferPathSet.select(
                Collections.singletonList(wifi("wlan0", "192.168.1.20", 1)),
                Collections.singletonList(ap("ap0", "192.168.43.1")),
                Collections.<String>emptySet());
        LanTransferPathSet second = LanTransferPathSet.select(
                Collections.singletonList(wifi("wlan0", "192.168.1.20", 1)),
                Collections.singletonList(ap("ap0", "192.168.44.1")),
                Collections.<String>emptySet());
        assertNotEquals(first.diagnosticFingerprint(), second.diagnosticFingerprint());
    }
}
