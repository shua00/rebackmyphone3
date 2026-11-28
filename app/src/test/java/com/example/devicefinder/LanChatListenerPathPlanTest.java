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
public class LanChatListenerPathPlanTest {
    private static Inet4Address ip(String value) throws Exception {
        return (Inet4Address) InetAddress.getByName(value);
    }

    private static LanNetworkPath wifi(String iface, String address, int id) throws Exception {
        return LanNetworkPath.fromAndroidNetwork(ShadowNetwork.newInstance(id), iface,
                ip(address), 24, ip("192.168.1.255"));
    }

    private static LanNetworkPath candidate(String iface, String address, String broadcast)
            throws Exception {
        return LanNetworkPath.localInterfaceCandidate(iface, ip(address), 24, ip(broadcast));
    }

    @Test public void singleWifiPathProducesExactlyOneListenerAndNoSecondaryWork() throws Exception {
        LanNetworkPath sta = wifi("wlan0", "192.168.1.20", 1);
        LanTransferPathSet set = LanTransferPathSet.select(
                Collections.singletonList(sta), Collections.<LanNetworkPath>emptyList(),
                Collections.<String>emptySet());
        LanChatListenerPathPlan plan = LanChatListenerPathPlan.from(set);
        assertSame(sta, plan.primary());
        assertTrue(plan.secondary().isEmpty());
        assertEquals(1, plan.listenerCount());
        assertFalse(plan.isMultiPath());
    }

    @Test public void wifiStaRemainsPrimaryAndHotspotCandidateBecomesSecondary() throws Exception {
        LanNetworkPath sta = wifi("wlan0", "192.168.1.20", 1);
        LanNetworkPath ap = candidate("vendor_iface", "192.168.43.1", "192.168.43.255");
        LanTransferPathSet set = LanTransferPathSet.select(
                Collections.singletonList(sta), Collections.singletonList(ap),
                Collections.<String>emptySet());
        LanChatListenerPathPlan plan = LanChatListenerPathPlan.from(set);
        assertSame(sta, plan.primary());
        assertEquals(1, plan.secondary().size());
        assertSame(ap, plan.secondary().get(0));
        assertEquals(2, plan.listenerCount());
        assertTrue(plan.isMultiPath());
    }

    @Test public void selectedActiveWifiNetworkRemainsPrimaryWhenSeveralWifiNetworksExist()
            throws Exception {
        LanNetworkPath first = wifi("wlan0", "192.168.1.20", 1);
        LanNetworkPath active = wifi("wlan1", "10.0.0.20", 2);
        LanTransferPathSet set = LanTransferPathSet.select(
                Arrays.asList(first, active), Collections.<LanNetworkPath>emptyList(),
                Collections.<String>emptySet());
        LanChatListenerPathPlan plan = LanChatListenerPathPlan.from(set, active.network);
        assertSame(active, plan.primary());
        assertEquals(1, plan.secondary().size());
        assertSame(first, plan.secondary().get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPreferredWifiNetworkMissingFromValidatedPathSet() throws Exception {
        LanNetworkPath available = wifi("wlan0", "192.168.1.20", 1);
        LanNetworkPath missing = wifi("wlan1", "10.0.0.20", 2);
        LanTransferPathSet set = LanTransferPathSet.select(
                Collections.singletonList(available), Collections.<LanNetworkPath>emptyList(),
                Collections.<String>emptySet());
        LanChatListenerPathPlan.from(set, missing.network);
    }

    @Test public void localCandidateCanBePrimaryWhenNoAndroidWifiNetworkExists() throws Exception {
        LanNetworkPath ap = candidate("vendor_iface", "192.168.43.1", "192.168.43.255");
        LanTransferPathSet set = LanTransferPathSet.select(
                Collections.<LanNetworkPath>emptyList(), Collections.singletonList(ap),
                Collections.<String>emptySet());
        LanChatListenerPathPlan plan = LanChatListenerPathPlan.from(set);
        assertSame(ap, plan.primary());
        assertEquals(1, plan.listenerCount());
    }

    @Test public void primarySelectionIsIndependentOfInputEnumerationOrder() throws Exception {
        LanNetworkPath ap = candidate("vendor_iface", "192.168.43.1", "192.168.43.255");
        LanNetworkPath sta = wifi("wlan0", "192.168.1.20", 1);
        LanTransferPathSet first = LanTransferPathSet.select(
                Collections.singletonList(sta), Collections.singletonList(ap),
                Collections.<String>emptySet());
        LanTransferPathSet second = LanTransferPathSet.select(
                Collections.singletonList(sta), Collections.singletonList(ap),
                Collections.<String>emptySet());
        LanChatListenerPathPlan a = LanChatListenerPathPlan.from(first);
        LanChatListenerPathPlan b = LanChatListenerPathPlan.from(second);
        assertEquals(a.diagnosticFingerprint(), b.diagnosticFingerprint());
        assertSame(sta, a.primary());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullPathSet() {
        LanChatListenerPathPlan.from(null);
    }
}
