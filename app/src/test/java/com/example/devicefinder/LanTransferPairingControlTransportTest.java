package com.example.devicefinder;

import org.junit.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class LanTransferPairingControlTransportTest {
    private static LanTransferPairingControlProtocol.Message message(
            LanTransferPairingControlProtocol.Type type, String session,
            String sender, String target, long sequence, byte[] payload) {
        return new LanTransferPairingControlProtocol.Message(type, session, sender, target, sequence, payload);
    }

    @Test public void dedicatedServerExchangesOneBoundedEnvelope() throws Exception {
        final String session = UUID.randomUUID().toString();
        final byte[] requestPayload = new byte[64];
        final byte[] responsePayload = new byte[64];
        final CountDownLatch handled = new CountDownLatch(1);
        LanTransferPairingControlTransport.Server server =
                new LanTransferPairingControlTransport.Server("receiver", request -> {
                    assertEquals(LanTransferPairingControlProtocol.Type.REQUEST, request.type);
                    assertArrayEquals(requestPayload, request.payload);
                    handled.countDown();
                    return message(LanTransferPairingControlProtocol.Type.RESPONSE, session,
                            "receiver", "sender", 1L, responsePayload);
                }, 60_000L);
        try {
            server.start(InetAddress.getLoopbackAddress(), 0);
            assertTrue(server.isRunning());
            LanTransferPairingControlProtocol.Message response =
                    LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                            server.getBoundPort(),
                            message(LanTransferPairingControlProtocol.Type.REQUEST, session,
                                    "sender", "receiver", 1L, requestPayload), 2000, 3000);
            assertTrue(handled.await(1, TimeUnit.SECONDS));
            assertEquals(LanTransferPairingControlProtocol.Type.RESPONSE, response.type);
            assertArrayEquals(responsePayload, response.payload);
        } finally {
            server.close();
        }
        assertFalse(server.isRunning());
    }

    @Test public void serverRejectsMismatchedResponseIdentity() throws Exception {
        final String session = UUID.randomUUID().toString();
        LanTransferPairingControlTransport.Server server =
                new LanTransferPairingControlTransport.Server("receiver", request ->
                        message(LanTransferPairingControlProtocol.Type.RESPONSE, session,
                                "impostor", "sender", 1L, new byte[64]), 60_000L);
        try {
            server.start(InetAddress.getLoopbackAddress(), 0);
            try {
                LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                        server.getBoundPort(),
                        message(LanTransferPairingControlProtocol.Type.REQUEST, session,
                                "sender", "receiver", 1L, new byte[64]), 2000, 3000);
                fail("mismatched response must be rejected");
            } catch (IOException expected) {
                // The server closes the connection rather than emitting a misrouted response.
            }
        } finally {
            server.close();
        }
    }

    @Test public void clientRejectsWrongResponseType() throws Exception {
        final String session = UUID.randomUUID().toString();
        LanTransferPairingControlTransport.Server server =
                new LanTransferPairingControlTransport.Server("receiver", request ->
                        message(LanTransferPairingControlProtocol.Type.CONFIRM, session,
                                "receiver", "sender", 1L, new byte[32]), 60_000L);
        try {
            server.start(InetAddress.getLoopbackAddress(), 0);
            try {
                LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                        server.getBoundPort(),
                        message(LanTransferPairingControlProtocol.Type.REQUEST, session,
                                "sender", "receiver", 1L, new byte[64]), 2000, 3000);
                fail("REQUEST must receive RESPONSE");
            } catch (IOException expected) {
                // Wrong response type is rejected at the client boundary.
            }
        } finally {
            server.close();
        }
    }

    @Test public void clientRejectsWrongResponseSequence() throws Exception {
        final String session = UUID.randomUUID().toString();
        LanTransferPairingControlTransport.Server server =
                new LanTransferPairingControlTransport.Server("receiver", request ->
                        message(LanTransferPairingControlProtocol.Type.RESPONSE, session,
                                "receiver", "sender", 2L, new byte[64]), 60_000L);
        try {
            server.start(InetAddress.getLoopbackAddress(), 0);
            try {
                LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                        server.getBoundPort(),
                        message(LanTransferPairingControlProtocol.Type.REQUEST, session,
                                "sender", "receiver", 1L, new byte[64]), 2000, 3000);
                fail("mismatched response sequence must be rejected");
            } catch (IOException expected) {
                // Wrong sequence is rejected at the client boundary.
            }
        } finally {
            server.close();
        }
    }

    @Test public void rejectsInvalidRemotePortBeforeConnecting() throws Exception {
        try {
            LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(), 0,
                    message(LanTransferPairingControlProtocol.Type.REQUEST, UUID.randomUUID().toString(),
                            "sender", "receiver", 1L, new byte[64]), 100, 100);
            fail("port zero is not a remote endpoint");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
