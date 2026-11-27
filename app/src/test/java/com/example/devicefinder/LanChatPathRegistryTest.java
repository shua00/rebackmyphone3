package com.example.devicefinder;

import org.junit.Test;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import static org.junit.Assert.*;

public class LanChatPathRegistryTest {
    private static final long NOW = 1_000_000L;
    private static DatagramSocket socket() throws Exception {
        return new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
    }
    private static InetSocketAddress endpoint(String ip) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(ip), 8888);
    }

    @Test public void samePeerCanHaveStaAndApRoutes() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket sta = socket(), ap = socket();
        try {
            String s = "sta|wlan0|192.168.1.20/24", a = "ap|ap0|192.168.43.1/24";
            registry.registerListener(s, sta, 1); registry.registerListener(a, ap, 2);
            registry.recordVerifiedPeer("peer", s, endpoint("192.168.1.31"), NOW, NOW + 120000);
            registry.recordVerifiedPeer("peer", a, endpoint("192.168.43.2"), NOW + 1, NOW + 120001);
            List<LanChatPathRegistry.PeerRoute> result = registry.getFreshPeerRoutes("peer", NOW + 2);
            assertEquals(2, result.size());
            assertNotEquals(result.get(0).pathFingerprint, result.get(1).pathFingerprint);
        } finally { sta.close(); ap.close(); }
    }

    @Test public void staleListenerCannotRemoveReplacementOrItsRoute() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket old = socket(), current = socket();
        try {
            String path = "sta|wlan0|192.168.1.20/24";
            registry.registerListener(path, old, 1);
            registry.registerListener(path, current, 2);
            registry.recordVerifiedPeer("peer", path, endpoint("192.168.1.31"), NOW, NOW + 100);
            assertFalse(registry.removeListener(path, old));
            assertSame(current, registry.getListener(path).socket);
            assertEquals(1, registry.getFreshPeerRoutes("peer", NOW + 1).size());
        } finally { old.close(); current.close(); }
    }

    @Test public void advancingGenerationOnSameSocketInvalidatesOldRoutes() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket listener = socket();
        try {
            String path = "sta|wlan0|192.168.1.20/24";
            registry.registerListener(path, listener, 1);
            registry.recordVerifiedPeer("peer", path, endpoint("192.168.1.31"), NOW, NOW + 100);
            registry.registerListener(path, listener, 2);
            assertTrue(registry.getFreshPeerRoutes("peer", NOW + 1).isEmpty());
            registry.recordVerifiedPeer("peer", path, endpoint("192.168.1.32"), NOW + 2, NOW + 102);
            List<LanChatPathRegistry.PeerRoute> routes =
                    registry.getFreshPeerRoutes("peer", NOW + 3);
            assertEquals(1, routes.size());
            assertEquals(2L, routes.get(0).listenerGeneration);
        } finally { listener.close(); }
    }

    @Test public void staleGenerationCannotRemoveSameSocketReplacement() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket listener = socket();
        try {
            String path = "sta|wlan0|192.168.1.20/24";
            registry.registerListener(path, listener, 10);
            registry.registerListener(path, listener, 11);
            assertFalse(registry.removeListener(path, listener, 10));
            assertSame(listener, registry.getListener(path).socket);
            assertEquals(11L, registry.getListener(path).generation);
            assertTrue(registry.removeListener(path, listener, 11));
            assertEquals(0, registry.listenerCount());
        } finally { listener.close(); }
    }

    @Test public void closedListenerRoutesArePrunedWhenQueried() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket listener = socket();
        String path = "sta|wlan0|192.168.1.20/24";
        registry.registerListener(path, listener, 20);
        registry.recordVerifiedPeer("peer", path, endpoint("192.168.1.31"), NOW, NOW + 100);
        listener.close();
        assertTrue(registry.getFreshPeerRoutes("peer", NOW + 1).isEmpty());
        assertTrue(registry.getFreshPeerRoutes("peer", NOW + 2).isEmpty());
    }

    @Test public void replacingListenerInvalidatesRoutesFromPreviousSocketEpoch() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket old = socket(), replacement = socket();
        try {
            String path = "sta|wlan0|192.168.1.20/24";
            registry.registerListener(path, old, 1);
            registry.recordVerifiedPeer("peer", path, endpoint("192.168.1.31"), NOW, NOW + 100);
            registry.registerListener(path, replacement, 2);
            assertTrue(registry.getFreshPeerRoutes("peer", NOW + 1).isEmpty());
        } finally { old.close(); replacement.close(); }
    }

    @Test public void advancingStaGenerationPreservesIndependentApRoute() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket sta = socket(), ap = socket();
        try {
            String s = "sta|wlan0|192.168.1.20/24", a = "ap|ap0|192.168.43.1/24";
            registry.registerListener(s, sta, 1);
            registry.registerListener(a, ap, 1);
            registry.recordVerifiedPeer("peer", s, endpoint("192.168.1.31"), NOW, NOW + 100);
            registry.recordVerifiedPeer("peer", a, endpoint("192.168.43.2"), NOW, NOW + 100);
            registry.registerListener(s, sta, 2);
            List<LanChatPathRegistry.PeerRoute> remaining =
                    registry.getFreshPeerRoutes("peer", NOW + 1);
            assertEquals(1, remaining.size());
            assertEquals(a, remaining.get(0).pathFingerprint);
            assertEquals(1L, remaining.get(0).listenerGeneration);
        } finally { sta.close(); ap.close(); }
    }

    @Test public void removingApPathPreservesStaRoute() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket sta = socket(), ap = socket();
        try {
            String s = "sta|wlan0|192.168.1.20/24", a = "ap|ap0|192.168.43.1/24";
            registry.registerListener(s, sta, 1); registry.registerListener(a, ap, 2);
            registry.recordVerifiedPeer("peer", s, endpoint("192.168.1.31"), NOW, NOW + 100);
            registry.recordVerifiedPeer("peer", a, endpoint("192.168.43.2"), NOW, NOW + 100);
            assertTrue(registry.removeListener(a, ap));
            List<LanChatPathRegistry.PeerRoute> result = registry.getFreshPeerRoutes("peer", NOW + 1);
            assertEquals(1, result.size()); assertEquals(s, result.get(0).pathFingerprint);
        } finally { sta.close(); ap.close(); }
    }

    @Test public void expiredOrLoopbackRoutesAreRejected() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket sta = socket();
        try {
            String path = "sta|wlan0|192.168.1.20/24";
            registry.registerListener(path, sta, 1);
            registry.recordVerifiedPeer("peer", path, endpoint("192.168.1.31"), NOW, NOW + 10);
            assertTrue(registry.getFreshPeerRoutes("peer", NOW + 10).isEmpty());
            try {
                registry.recordVerifiedPeer("peer", path, endpoint("127.0.0.1"), NOW, NOW + 100);
                fail("loopback is not a LAN endpoint");
            } catch (IllegalArgumentException expected) { }
        } finally { sta.close(); }
    }
    @Test public void verifiedRouteRequiresAnActivePathListener() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        String path = "sta|wlan0|192.168.1.20/24";
        try {
            registry.recordVerifiedPeer("peer", path, endpoint("192.168.1.31"), NOW, NOW + 100);
            fail("route admission must be tied to a live listener path");
        } catch (IllegalStateException expected) { }
        assertTrue(registry.getFreshPeerRoutes("peer", NOW + 1).isEmpty());
    }

    @Test public void listenerRejectsNegativeGeneration() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket socket = socket();
        try {
            try {
                registry.registerListener("sta|wlan0|192.168.1.20/24", socket, -1);
                fail("negative generations are invalid");
            } catch (IllegalArgumentException expected) { }
        } finally { socket.close(); }
    }


    @Test public void ordinaryRouterSupportsMultipleAndroidAndPythonPeersOnOnePath() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket routerLan = socket();
        try {
            // Platform labels do not establish trust; all peers use authenticated peer IDs.
            String path = "sta|wlan0|192.168.1.20/24";
            registry.registerListener(path, routerLan, 7);
            registry.recordVerifiedPeer("android-peer-a", path, endpoint("192.168.1.31"), NOW, NOW + 120000);
            registry.recordVerifiedPeer("python-peer-b", path, endpoint("192.168.1.32"), NOW + 1, NOW + 120001);
            registry.recordVerifiedPeer("android-peer-c", path, endpoint("192.168.1.33"), NOW + 2, NOW + 120002);
            assertEquals(1, registry.listenerCount());
            assertEquals(1, registry.getFreshPeerRoutes("android-peer-a", NOW + 3).size());
            assertEquals(1, registry.getFreshPeerRoutes("python-peer-b", NOW + 3).size());
            assertEquals(1, registry.getFreshPeerRoutes("android-peer-c", NOW + 3).size());
            assertTrue(registry.getFreshPeerRoutes("unknown-peer", NOW + 3).isEmpty());
        } finally { routerLan.close(); }
    }

    @Test public void routeUpdatesForOnePeerDoNotOverwriteAnotherPeerOnSamePath() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket routerLan = socket();
        try {
            String path = "sta|wlan0|192.168.1.20/24";
            registry.registerListener(path, routerLan, 8);
            registry.recordVerifiedPeer("peer-a", path, endpoint("192.168.1.31"), NOW, NOW + 120000);
            registry.recordVerifiedPeer("peer-b", path, endpoint("192.168.1.32"), NOW, NOW + 120000);
            registry.recordVerifiedPeer("peer-a", path, endpoint("192.168.1.41"), NOW + 5, NOW + 120005);
            assertEquals("192.168.1.41", registry.getFreshPeerRoutes("peer-a", NOW + 6)
                    .get(0).endpoint.getAddress().getHostAddress());
            assertEquals("192.168.1.32", registry.getFreshPeerRoutes("peer-b", NOW + 6)
                    .get(0).endpoint.getAddress().getHostAddress());
        } finally { routerLan.close(); }
    }

    @Test public void routeBindingCapturesExactPathListenerAndGeneration() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket sta = socket(), ap = socket();
        try {
            String s = "sta|wlan0|192.168.1.20/24", a = "ap|ap0|192.168.43.1/24";
            registry.registerListener(s, sta, 31);
            registry.registerListener(a, ap, 47);
            registry.recordVerifiedPeer("peer", s, endpoint("192.168.1.31"), NOW, NOW + 100);
            registry.recordVerifiedPeer("peer", a, endpoint("192.168.43.2"), NOW + 1, NOW + 101);

            List<LanChatPathRegistry.RouteBinding> bindings =
                    registry.getFreshPeerRouteBindings("peer", NOW + 2);
            assertEquals(2, bindings.size());
            for (LanChatPathRegistry.RouteBinding binding : bindings) {
                assertTrue(binding.isCurrentAndUsable());
                if (s.equals(binding.route.pathFingerprint)) {
                    assertSame(sta, binding.listener.socket);
                    assertEquals(31L, binding.listener.generation);
                } else {
                    assertEquals(a, binding.route.pathFingerprint);
                    assertSame(ap, binding.listener.socket);
                    assertEquals(47L, binding.listener.generation);
                }
                assertEquals(binding.route.listenerGeneration, binding.listener.generation);
            }
        } finally { sta.close(); ap.close(); }
    }

    @Test public void routeBindingIsInvalidatedWhenItsListenerEpochIsReplaced() throws Exception {
        LanChatPathRegistry registry = new LanChatPathRegistry();
        DatagramSocket old = socket(), replacement = socket();
        try {
            String path = "sta|wlan0|192.168.1.20/24";
            registry.registerListener(path, old, 51);
            registry.recordVerifiedPeer("peer", path, endpoint("192.168.1.31"), NOW, NOW + 100);
            List<LanChatPathRegistry.RouteBinding> before =
                    registry.getFreshPeerRouteBindings("peer", NOW + 1);
            assertEquals(1, before.size());
            assertSame(old, before.get(0).listener.socket);

            registry.registerListener(path, replacement, 52);
            assertTrue(registry.getFreshPeerRouteBindings("peer", NOW + 2).isEmpty());
            // A previously captured binding remains tied to the old epoch; it must never
            // silently become a binding to the replacement listener.
            assertSame(old, before.get(0).listener.socket);
            assertEquals(51L, before.get(0).listener.generation);
        } finally { old.close(); replacement.close(); }
    }

}
