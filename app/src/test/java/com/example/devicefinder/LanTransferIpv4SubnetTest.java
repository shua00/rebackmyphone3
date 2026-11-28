package com.example.devicefinder;

import org.junit.Test;
import java.net.Inet4Address;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class LanTransferIpv4SubnetTest {
    private static Inet4Address ipv4(String value) throws Exception {
        return (Inet4Address) InetAddress.getByName(value);
    }

    @Test public void calculatesCommonAndNonOctetBroadcastBoundaries() throws Exception {
        assertEquals("192.168.1.255",
                LanTransferIpv4Subnet.directedBroadcast(ipv4("192.168.1.23"), 24).getHostAddress());
        assertEquals("192.168.15.255",
                LanTransferIpv4Subnet.directedBroadcast(ipv4("192.168.7.23"), 20).getHostAddress());
        assertEquals("10.127.255.255",
                LanTransferIpv4Subnet.directedBroadcast(ipv4("10.64.1.2"), 10).getHostAddress());
    }

    @Test public void pointToPointAndHostRoutesHaveNoDirectedBroadcast() throws Exception {
        assertNull(LanTransferIpv4Subnet.directedBroadcast(ipv4("192.0.2.10"), 31));
        assertNull(LanTransferIpv4Subnet.directedBroadcast(ipv4("192.0.2.10"), 32));
    }

    @Test public void rejectsInvalidPrefixLengths() throws Exception {
        assertNull(LanTransferIpv4Subnet.directedBroadcast(ipv4("192.0.2.10"), 0));
        assertNull(LanTransferIpv4Subnet.directedBroadcast(ipv4("192.0.2.10"), 33));
        assertNull(LanTransferIpv4Subnet.directedBroadcast(null, 24));
    }
    @Test public void validatesRemoteEndpointAgainstSelectedIpv4Prefix() throws Exception {
        assertTrue(LanTransferIpv4Subnet.contains(ipv4("192.168.40.2"), 24,
                ipv4("192.168.40.99")));
        assertFalse(LanTransferIpv4Subnet.contains(ipv4("192.168.40.2"), 24,
                ipv4("192.168.41.99")));
        assertTrue(LanTransferIpv4Subnet.contains(ipv4("10.42.0.1"), 16,
                ipv4("10.42.255.254")));
        assertFalse(LanTransferIpv4Subnet.contains(ipv4("192.0.2.1"), 0,
                ipv4("192.0.2.2")));
    }

}
