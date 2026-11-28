package com.example.devicefinder;

import org.junit.Test;
import java.net.Inet4Address;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class LanChatCachedRoutePolicyTest {
    private static Inet4Address ip(String value) throws Exception {
        return (Inet4Address) InetAddress.getByName(value);
    }

    @Test public void allowsOnlyCurrentSingleListenerLeaseOnSameSubnet() throws Exception {
        assertTrue(LanChatCachedRoutePolicy.isEligible(true, true,
                ip("192.168.1.20"), 24, ip("192.168.1.55")));
    }

    @Test public void rejectsDifferentSubnetEvenWhenLeaseAndListenerMatch() throws Exception {
        assertFalse(LanChatCachedRoutePolicy.isEligible(true, true,
                ip("192.168.1.20"), 24, ip("192.168.2.55")));
    }

    @Test public void rejectsMultipleListeners() throws Exception {
        assertFalse(LanChatCachedRoutePolicy.isEligible(false, true,
                ip("192.168.1.20"), 24, ip("192.168.1.55")));
    }

    @Test public void rejectsExpiredOrWrongGenerationDurableLease() throws Exception {
        assertFalse(LanChatCachedRoutePolicy.isEligible(true, false,
                ip("192.168.1.20"), 24, ip("192.168.1.55")));
    }

    @Test public void rejectsInvalidPrefixAndNonLanAddresses() throws Exception {
        assertFalse(LanChatCachedRoutePolicy.isEligible(true, true,
                ip("192.168.1.20"), 0, ip("192.168.1.55")));
        assertFalse(LanChatCachedRoutePolicy.isEligible(true, true,
                ip("192.168.1.20"), 24, ip("8.8.8.8")));
        assertFalse(LanChatCachedRoutePolicy.isEligible(true, true,
                ip("192.168.1.20"), 24, ip("192.168.1.20")));
    }

    @Test public void parsesOnlyNumericIpv4Literals() throws Exception {
        assertEquals(ip("192.168.1.55"),
                LanChatCachedRoutePolicy.parseIpv4Literal("192.168.1.55"));
        assertNull(LanChatCachedRoutePolicy.parseIpv4Literal("peer.local"));
        assertNull(LanChatCachedRoutePolicy.parseIpv4Literal("192.168.1.999"));
        assertNull(LanChatCachedRoutePolicy.parseIpv4Literal("+1.2.3.4"));
    }
}
