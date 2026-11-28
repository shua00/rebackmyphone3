package com.example.devicefinder;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class LanTransferPairingHandshakeTest {
    @Test public void initiatorAndResponderDeriveIdenticalProvisionalMaterial() throws Exception {
        LanTransferPairingHandshake.InitiatorStart start =
                LanTransferPairingHandshake.begin("peer-a", "peer-b",
                        "5d6e3b20-41f4-4db1-8d34-ae75b77e8a10", 7L);
        LanTransferPairingHandshake.ResponderResult responder =
                LanTransferPairingHandshake.respond("peer-b", start.request, 1000L);
        LanTransferPairingCrypto.Result initiator =
                LanTransferPairingHandshake.completeInitiator(start, responder.response);
        assertEquals(initiator.shortAuthenticationCode, responder.material.shortAuthenticationCode);
        assertArrayEquals(initiator.peerSecret, responder.material.peerSecret);
        assertArrayEquals(initiator.transcriptHash, responder.material.transcriptHash);
        assertEquals(LanTransferPairingControlProtocol.Type.REQUEST, start.request.type);
        assertEquals(LanTransferPairingControlProtocol.Type.RESPONSE, responder.response.type);
    }

    @Test public void rejectsWrongResponderIdentitySessionAndSequence() throws Exception {
        LanTransferPairingHandshake.InitiatorStart start =
                LanTransferPairingHandshake.begin("peer-a", "peer-b", 1L);
        LanTransferPairingHandshake.ResponderResult responder =
                LanTransferPairingHandshake.respond("peer-b", start.request, 1000L);
        LanTransferPairingControlProtocol.Message wrongPeer =
                new LanTransferPairingControlProtocol.Message(
                        LanTransferPairingControlProtocol.Type.RESPONSE,
                        responder.response.sessionId, "peer-c", "peer-a", 1,
                        responder.response.payload);
        assertRejected(start, wrongPeer);
        LanTransferPairingControlProtocol.Message wrongSession =
                new LanTransferPairingControlProtocol.Message(
                        LanTransferPairingControlProtocol.Type.RESPONSE,
                        "8d6e3b20-41f4-4db1-8d34-ae75b77e8a10", "peer-b", "peer-a", 1,
                        responder.response.payload);
        assertRejected(start, wrongSession);
        LanTransferPairingControlProtocol.Message wrongSequence =
                new LanTransferPairingControlProtocol.Message(
                        LanTransferPairingControlProtocol.Type.RESPONSE,
                        responder.response.sessionId, "peer-b", "peer-a", 2,
                        responder.response.payload);
        assertRejected(start, wrongSequence);
    }

    @Test public void responderRejectsWrongTargetAndMessageType() throws Exception {
        LanTransferPairingHandshake.InitiatorStart start =
                LanTransferPairingHandshake.begin("peer-a", "peer-b", 1L);
        try {
            LanTransferPairingHandshake.respond("peer-c", start.request, 1000L);
            fail("wrong target must be rejected");
        } catch (IOException expected) { }
        LanTransferPairingControlProtocol.Message cancel =
                new LanTransferPairingControlProtocol.Message(
                        LanTransferPairingControlProtocol.Type.CANCEL,
                        start.request.sessionId, "peer-a", "peer-b", 2L, new byte[0]);
        try {
            LanTransferPairingHandshake.respond("peer-b", cancel, 1000L);
            fail("non-request must be rejected");
        } catch (IOException expected) { }
    }

    private static void assertRejected(LanTransferPairingHandshake.InitiatorStart start,
            LanTransferPairingControlProtocol.Message response) throws Exception {
        try {
            LanTransferPairingHandshake.completeInitiator(start, response);
            fail("mismatched response must be rejected");
        } catch (IOException expected) { }
    }
}
