package com.example.devicefinder;

import org.junit.Test;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class LanSocketBindingTest {
    @Test public void datagramListenerBindsExactLocalAddressAndRequestedPort() throws Exception {
        Inet4Address loopback = (Inet4Address) InetAddress.getByName("127.0.0.1");
        LanNetworkPath path = LanNetworkPath.localInterfaceCandidate("lo", loopback, 8, null);
        DatagramSocket socket = new DatagramSocket(null);
        try {
            LanSocketBinding.bindDatagramListenerSocket(path, socket, 0x6A31);
            assertEquals(loopback, socket.getLocalAddress());
            assertEquals(0x6A31, socket.getLocalPort());
            assertTrue(socket.getReuseAddress());
            assertTrue(socket.getBroadcast());
        } finally {
            socket.close();
        }
    }

    @Test public void datagramListenerRejectsInvalidPortAndAlreadyBoundSocket() throws Exception {
        Inet4Address loopback = (Inet4Address) InetAddress.getByName("127.0.0.1");
        LanNetworkPath path = LanNetworkPath.localInterfaceCandidate("lo", loopback, 8, null);
        DatagramSocket socket = new DatagramSocket(null);
        try {
            try {
                LanSocketBinding.bindDatagramListenerSocket(path, socket, 0);
                fail("port zero must be rejected for listener ownership");
            } catch (IllegalArgumentException expected) { }
            socket.bind(new java.net.InetSocketAddress(loopback, 0));
            try {
                LanSocketBinding.bindDatagramListenerSocket(path, socket, 0x6A31);
                fail("already-bound socket must be rejected");
            } catch (java.io.IOException expected) { }
        } finally {
            socket.close();
        }
    }
}
