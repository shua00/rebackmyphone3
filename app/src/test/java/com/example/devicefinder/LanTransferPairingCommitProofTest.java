package com.example.devicefinder;

import org.junit.Test;
import java.net.InetAddress;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LanTransferPairingCommitProofTest {
    private static final String SESSION = "5d6e3b20-41f4-4db1-8d34-ae75b77e8a10";

    @Test public void commitProofIsBoundToSessionPeersAndOperation() throws Exception {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) (i + 1);
        byte[] proof = LanTransferPairingCommitProof.create(
                secret, SESSION, "initiator", "responder", "COMMIT");
        assertEquals(32, proof.length);
        assertTrue(LanTransferPairingCommitProof.verify(
                secret, SESSION, "initiator", "responder", "COMMIT", proof));
        assertFalse(LanTransferPairingCommitProof.verify(
                secret, SESSION, "responder", "initiator", "COMMIT", proof));
        assertFalse(LanTransferPairingCommitProof.verify(
                secret, SESSION, "initiator", "responder", "COMMIT_ACK", proof));
        assertFalse(LanTransferPairingCommitProof.verify(
                secret, "c1c72d7a-16b3-42d6-95a4-12ccac0d4b5e",
                "initiator", "responder", "COMMIT", proof));
        proof[0] ^= 1;
        assertFalse(LanTransferPairingCommitProof.verify(
                secret, SESSION, "initiator", "responder", "COMMIT", proof));
        Arrays.fill(secret, (byte) 0);
        Arrays.fill(proof, (byte) 0);
    }

    @Test public void commitProofRejectsInvalidInputs() throws Exception {
        try {
            LanTransferPairingCommitProof.create(new byte[31], SESSION,
                    "initiator", "responder", "COMMIT");
            fail("short secret rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            LanTransferPairingCommitProof.create(new byte[32], "not-a-session",
                    "initiator", "responder", "COMMIT");
            fail("invalid session rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            LanTransferPairingCommitProof.create(new byte[32], SESSION,
                    "initiator", "initiator", "COMMIT");
            fail("same peer rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            LanTransferPairingCommitProof.create(new byte[32], SESSION,
                    "initiator", "responder", "UNKNOWN");
            fail("unknown operation rejected");
        } catch (IllegalArgumentException expected) { }
        assertFalse(LanTransferPairingCommitProof.verify(new byte[32], SESSION,
                "initiator", "responder", "COMMIT", new byte[31]));
    }

    @Test public void commitAndCommitAckMessagesRoundTrip() throws Exception {
        for (LanTransferPairingControlProtocol.Type type : new LanTransferPairingControlProtocol.Type[]{
                LanTransferPairingControlProtocol.Type.COMMIT,
                LanTransferPairingControlProtocol.Type.COMMIT_ACK}) {
            LanTransferPairingControlProtocol.Message source =
                    new LanTransferPairingControlProtocol.Message(type, SESSION,
                            "initiator", "responder", 9L, new byte[32]);
            LanTransferPairingControlProtocol.Message decoded =
                    LanTransferPairingControlProtocol.decode(
                            LanTransferPairingControlProtocol.encode(source));
            assertEquals(type, decoded.type);
            assertArrayEquals(source.payload, decoded.payload);
        }
    }

    @Test public void commitMessagesRequireExactlyOneSha256Proof() {
        for (LanTransferPairingControlProtocol.Type type : new LanTransferPairingControlProtocol.Type[]{
                LanTransferPairingControlProtocol.Type.COMMIT,
                LanTransferPairingControlProtocol.Type.COMMIT_ACK}) {
            try {
                new LanTransferPairingControlProtocol.Message(type, SESSION,
                        "initiator", "responder", 9L, new byte[31]);
                fail("short commit proof rejected for " + type);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void commitTransportAuthenticatesAcknowledgement() throws Exception {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x4a);
        LanTransferPairingControlTransport.Server server =
                new LanTransferPairingControlTransport.Server("responder", request -> {
                    assertEquals(LanTransferPairingControlProtocol.Type.COMMIT, request.type);
                    assertTrue(LanTransferPairingCommitProof.verify(secret, SESSION,
                            "initiator", "responder", "COMMIT", request.payload));
                    byte[] ack = LanTransferPairingCommitProof.create(secret, SESSION,
                            "responder", "initiator", "COMMIT_ACK");
                    try {
                        return new LanTransferPairingControlProtocol.Message(
                                LanTransferPairingControlProtocol.Type.COMMIT_ACK,
                                SESSION, "responder", "initiator", request.sequence, ack);
                    } finally { Arrays.fill(ack, (byte) 0); }
                }, 60_000L);
        try {
            server.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingCommitProof.sendCommit(InetAddress.getLoopbackAddress(),
                    server.getBoundPort(), "initiator", "responder", SESSION,
                    secret, 1L, 2000, 3000);
        } finally {
            server.close();
            Arrays.fill(secret, (byte) 0);
        }
    }
}
