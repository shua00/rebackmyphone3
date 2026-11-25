package com.example.devicefinder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import static org.junit.Assert.*;

public class LanTransferPeerAllowListTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test public void addingPeerPreservesExistingTrustedPeers() {
        Set<String> merged = LanTransferCredentials.mergeTrustedPeerIds(
                Set.of("peer-a", "peer-b"), SECRET, SECRET, "peer-c");
        assertEquals(Set.of("peer-a", "peer-b", "peer-c"), merged);
    }

    @Test public void addingExistingPeerIsIdempotent() {
        Set<String> merged = LanTransferCredentials.mergeTrustedPeerIds(
                Set.of("peer-a", "peer-b"), SECRET, SECRET, "peer-a");
        assertEquals(Set.of("peer-a", "peer-b"), merged);
    }

    @Test public void differentGroupSecretCannotSilentlyRebindExistingPeers() {
        try {
            LanTransferCredentials.mergeTrustedPeerIds(
                    Set.of("peer-a"), SECRET, "fedcba9876543210fedcba9876543210", "peer-b");
            fail("Expected mismatched group secret to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("different group secret")
                    || expected.getMessage().contains("cannot be rebound"));
        }
    }

    @Test public void emptyAllowListCanStartFirstPairing() {
        Set<String> merged = LanTransferCredentials.mergeTrustedPeerIds(
                Collections.emptySet(), null, SECRET, "peer-a");
        assertEquals(Collections.singleton("peer-a"), merged);
    }
}
