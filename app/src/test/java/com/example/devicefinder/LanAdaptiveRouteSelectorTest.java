package com.example.devicefinder;

import org.junit.Test;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import static org.junit.Assert.*;

public class LanAdaptiveRouteSelectorTest {
    private static final long NOW = 2_000_000L;
    private static DatagramSocket socket() throws Exception {
        return new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
    }
    private static InetSocketAddress endpoint(String ip) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(ip), 8888);
    }

    @Test public void singleListenerUsesOnlyPreferredPathAndHasNoFallbackPlan() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket sta = socket();
        try {
            String path = "sta|wlan0|192.168.1.20/24";
            registry.registerListener(path, sta, 1);
            registry.recordVerifiedPeer("peer", path, endpoint("192.168.1.31"), NOW, NOW + 1000);

            LanChatPathRegistry.RouteBinding selected =
                    LanAdaptiveRouteSelector.selectPrimaryBinding(registry, "peer", path, NOW + 1);
            assertNotNull(selected);
            assertSame(sta, selected.listener.socket);
            assertTrue(registry.isCurrentBinding(selected));
            assertNull(LanAdaptiveRouteSelector.selectPrimaryBinding(
                    registry, "peer", "unrelated-path", NOW + 1));
            assertTrue(LanAdaptiveRouteSelector.selectFallbackBindings(
                    registry, "peer", selected, path, NOW + 1, 2).isEmpty());
        } finally { sta.close(); }
    }

    @Test public void multiPathPrefersRequestedFreshPathAndBuildsFallbackOnlyOnDemand()
            throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket sta = socket(), ap = socket(), usb = socket();
        try {
            String s = "sta|wlan0|192.168.1.20/24";
            String a = "ap|ap0|192.168.43.1/24";
            String u = "usb|rndis0|192.168.42.1/24";
            registry.registerListener(s, sta, 11);
            registry.registerListener(a, ap, 12);
            registry.registerListener(u, usb, 13);
            registry.recordVerifiedPeer("peer", s, endpoint("192.168.1.31"), NOW + 30, NOW + 1000);
            registry.recordVerifiedPeer("peer", a, endpoint("192.168.43.2"), NOW + 10, NOW + 1000);
            registry.recordVerifiedPeer("peer", u, endpoint("192.168.42.2"), NOW + 20, NOW + 1000);

            LanChatPathRegistry.RouteBinding primary =
                    LanAdaptiveRouteSelector.selectPrimaryBinding(registry, "peer", a, NOW + 40);
            assertNotNull(primary);
            assertEquals(a, primary.route.pathFingerprint);
            assertSame(ap, primary.listener.socket);

            List<LanChatPathRegistry.RouteBinding> fallback =
                    LanAdaptiveRouteSelector.selectFallbackBindings(
                            registry, "peer", primary, s, NOW + 40, 9);
            assertEquals(2, fallback.size());
            assertEquals(s, fallback.get(0).route.pathFingerprint);
            assertEquals(u, fallback.get(1).route.pathFingerprint);
            assertTrue(registry.isCurrentBinding(fallback.get(0)));
            assertTrue(registry.isCurrentBinding(fallback.get(1)));
        } finally { sta.close(); ap.close(); usb.close(); }
    }

    @Test public void multiPathWithoutPreferredPathUsesNewestAuthenticatedRoute()
            throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket sta = socket(), ap = socket();
        try {
            String s = "sta|wlan0|192.168.1.20/24", a = "ap|ap0|192.168.43.1/24";
            registry.registerListener(s, sta, 21);
            registry.registerListener(a, ap, 22);
            registry.recordVerifiedPeer("peer", s, endpoint("192.168.1.31"), NOW + 10, NOW + 1000);
            registry.recordVerifiedPeer("peer", a, endpoint("192.168.43.2"), NOW + 20, NOW + 1000);

            LanChatPathRegistry.RouteBinding primary =
                    LanAdaptiveRouteSelector.selectPrimaryBinding(registry, "peer", null, NOW + 30);
            assertNotNull(primary);
            assertEquals(a, primary.route.pathFingerprint);
        } finally { sta.close(); ap.close(); }
    }
}
