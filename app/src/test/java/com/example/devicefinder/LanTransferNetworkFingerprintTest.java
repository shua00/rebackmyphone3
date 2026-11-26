package com.example.devicefinder;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LanTransferNetworkFingerprintTest {
    @Test public void snapshotOrderingDoesNotChangeFingerprint() {
        String first = LanTransferNetworkFingerprint.create("Network{42}", "wlan0",
                Arrays.asList("192.168.1.20/24", "fe80::1/64"),
                Arrays.asList("0.0.0.0/0 -> 192.168.1.1", "192.168.1.0/24"));
        String reordered = LanTransferNetworkFingerprint.create("Network{42}", "wlan0",
                Arrays.asList("fe80::1/64", "192.168.1.20/24"),
                Arrays.asList("192.168.1.0/24", "0.0.0.0/0 -> 192.168.1.1"));
        assertEquals(first, reordered);
        assertTrue(first.startsWith("wifi:sha256:"));
        assertEquals(76, first.length());
    }

    @Test public void gatewayOrRouteChangeInvalidatesFingerprintEvenOnSameSubnet() {
        String before = LanTransferNetworkFingerprint.create("Network{42}", "wlan0",
                Arrays.asList("192.168.1.20/24"),
                Arrays.asList("0.0.0.0/0 -> 192.168.1.1", "192.168.1.0/24"));
        String after = LanTransferNetworkFingerprint.create("Network{42}", "wlan0",
                Arrays.asList("192.168.1.20/24"),
                Arrays.asList("0.0.0.0/0 -> 192.168.1.254", "192.168.1.0/24"));
        assertNotEquals(before, after);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncompleteNetworkSnapshot() {
        LanTransferNetworkFingerprint.create("Network{42}", "wlan0",
                Arrays.asList("192.168.1.20/24"), null);
    }
}
