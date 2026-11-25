package com.example.devicefinder;

import org.junit.Test;
import static org.junit.Assert.*;

public class LanTransferPairingCryptoTest {
    @Test public void bothRolesDeriveIdenticalPeerSecretAndSas() throws Exception {
        LanTransferPairingCrypto.Ephemeral a = LanTransferPairingCrypto.createEphemeral();
        LanTransferPairingCrypto.Ephemeral b = LanTransferPairingCrypto.createEphemeral();
        LanTransferPairingCrypto.Result left = LanTransferPairingCrypto.deriveAsInitiator(
                "peer-a", "peer-b", a, b.publicKey(), b.nonce());
        LanTransferPairingCrypto.Result right = LanTransferPairingCrypto.deriveAsResponder(
                "peer-a", "peer-b", b, a.publicKey(), a.nonce());
        assertArrayEquals(left.peerSecret, right.peerSecret);
        assertArrayEquals(left.transcriptHash, right.transcriptHash);
        assertEquals(left.shortAuthenticationCode, right.shortAuthenticationCode);
        assertEquals(32, left.peerSecret.length);
        assertEquals(6, left.shortAuthenticationCode.length());
    }

    @Test public void changedIdentityNonceOrEphemeralKeyChangesDerivedCredential() throws Exception {
        LanTransferPairingCrypto.Ephemeral a = LanTransferPairingCrypto.createEphemeral();
        LanTransferPairingCrypto.Ephemeral b = LanTransferPairingCrypto.createEphemeral();
        LanTransferPairingCrypto.Result baseline = LanTransferPairingCrypto.deriveAsInitiator(
                "peer-a", "peer-b", a, b.publicKey(), b.nonce());
        LanTransferPairingCrypto.Result changedIdentity = LanTransferPairingCrypto.deriveAsInitiator(
                "peer-x", "peer-b", a, b.publicKey(), b.nonce());
        byte[] changedNonce = b.nonce();
        changedNonce[0] ^= 1;
        LanTransferPairingCrypto.Result nonceMutation = LanTransferPairingCrypto.deriveAsInitiator(
                "peer-a", "peer-b", a, b.publicKey(), changedNonce);
        LanTransferPairingCrypto.Ephemeral other = LanTransferPairingCrypto.createEphemeral();
        LanTransferPairingCrypto.Result keyMutation = LanTransferPairingCrypto.deriveAsInitiator(
                "peer-a", "peer-b", a, other.publicKey(), b.nonce());
        assertFalse(java.util.Arrays.equals(baseline.peerSecret, changedIdentity.peerSecret));
        assertFalse(java.util.Arrays.equals(baseline.peerSecret, nonceMutation.peerSecret));
        assertFalse(java.util.Arrays.equals(baseline.peerSecret, keyMutation.peerSecret));
        assertNotEquals(baseline.shortAuthenticationCode, changedIdentity.shortAuthenticationCode);
    }

    @Test public void malformedExchangeAndSameIdentityAreRejected() throws Exception {
        LanTransferPairingCrypto.Ephemeral a = LanTransferPairingCrypto.createEphemeral();
        LanTransferPairingCrypto.Ephemeral b = LanTransferPairingCrypto.createEphemeral();
        try {
            LanTransferPairingCrypto.deriveAsInitiator("peer-a", "peer-a", a, b.publicKey(), b.nonce());
            fail("same peer identity must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            LanTransferPairingCrypto.deriveAsInitiator("peer-a", "peer-b", a, new byte[8], b.nonce());
            fail("malformed public key must be rejected");
        } catch (IllegalArgumentException expected) { }
    }
}
