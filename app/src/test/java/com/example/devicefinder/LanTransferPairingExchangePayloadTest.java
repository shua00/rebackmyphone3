package com.example.devicefinder;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LanTransferPairingExchangePayloadTest {
    @Test public void roundTripPreservesPublicKeyAndNonceAndDefensivelyCopies() throws Exception {
        byte[] key = new byte[91], nonce = new byte[LanTransferPairingCrypto.NONCE_BYTES];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (100 + i);
        LanTransferPairingExchangePayload.Material source =
                new LanTransferPairingExchangePayload.Material(key, nonce);
        key[0] ^= 1;
        nonce[0] ^= 1;
        assertEquals(1, source.publicKey()[0]);
        assertEquals(100, source.nonce()[0]);

        LanTransferPairingExchangePayload.Material decoded =
                LanTransferPairingExchangePayload.decode(LanTransferPairingExchangePayload.encode(source));
        assertArrayEquals(source.publicKey(), decoded.publicKey());
        assertArrayEquals(source.nonce(), decoded.nonce());
        byte[] exposedKey = decoded.publicKey();
        byte[] exposedNonce = decoded.nonce();
        exposedKey[0] ^= 1;
        exposedNonce[0] ^= 1;
        assertArrayEquals(source.publicKey(), decoded.publicKey());
        assertArrayEquals(source.nonce(), decoded.nonce());
        assertTrue(source.toString().contains("publicKeyBytes=91"));
    }

    @Test public void rejectsTruncatedAndTrailingPayloads() throws Exception {
        byte[] key = new byte[91], nonce = new byte[LanTransferPairingCrypto.NONCE_BYTES];
        byte[] valid = LanTransferPairingExchangePayload.encode(
                new LanTransferPairingExchangePayload.Material(key, nonce));
        for (byte[] malformed : new byte[][] {
                Arrays.copyOf(valid, valid.length - 1),
                Arrays.copyOf(valid, valid.length + 1),
                new byte[] { 2, 0, 91 }
        }) {
            try {
                LanTransferPairingExchangePayload.decode(malformed);
                fail("malformed exchange payload must be rejected");
            } catch (IOException expected) {
                // fail closed
            }
        }
    }

    @Test public void rejectsInvalidKeyAndNonceLengths() {
        try {
            new LanTransferPairingExchangePayload.Material(new byte[63], new byte[32]);
            fail("short public key must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            new LanTransferPairingExchangePayload.Material(new byte[91], new byte[31]);
            fail("short nonce must be rejected");
        } catch (IllegalArgumentException expected) { }
    }
}
