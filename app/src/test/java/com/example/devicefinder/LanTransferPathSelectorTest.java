package com.example.devicefinder;

import static org.junit.Assert.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetwork;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LanTransferPathSelectorTest {
    private static Inet4Address ip(String value) throws Exception {
        return (Inet4Address) InetAddress.getByName(value);
    }
    private static LanNetworkPath candidate(String name, String address) throws Exception {
        return LanNetworkPath.localInterfaceCandidate(name, ip(address), 24, ip("192.168.43.255"));
    }
    @Test public void androidNetworkAlwaysWinsOverLocalCandidates() throws Exception {
        LanNetworkPath network = LanNetworkPath.fromAndroidNetwork(ShadowNetwork.newInstance(7),
                "wlan", ip("192.168.1.2"), 24, null);
        assertSame(network, LanTransferPathSelector.select(network,
                Collections.singletonList(candidate("ap", "192.168.43.1"))));
    }
    @Test public void uniqueCompleteCandidateIsSelected() throws Exception {
        LanNetworkPath only = candidate("vendor_ap", "192.168.43.1");
        assertSame(only, LanTransferPathSelector.select(null, Collections.singletonList(only)));
    }
    @Test(expected = java.io.IOException.class)
    public void multipleCandidatesAreRejected() throws Exception {
        LanTransferPathSelector.select(null, Arrays.asList(
                candidate("ap", "192.168.43.1"), candidate("eth", "192.168.43.2")));
    }
    @Test(expected = java.io.IOException.class)
    public void noCandidatesAreRejected() throws Exception {
        LanTransferPathSelector.select(null, Collections.emptyList());
    }
    @Test(expected = java.io.IOException.class)
    public void candidateWithoutBroadcastIsRejected() throws Exception {
        LanTransferPathSelector.select(null, Collections.singletonList(
                LanNetworkPath.localInterfaceCandidate("ap", ip("192.168.43.1"), 24, null)));
    }
    @Test(expected = java.io.IOException.class)
    public void incorrectBroadcastIsRejected() throws Exception {
        LanTransferPathSelector.select(null, Collections.singletonList(
                LanNetworkPath.localInterfaceCandidate("ap", ip("192.168.43.1"), 24, ip("192.168.43.1"))));
    }
}
