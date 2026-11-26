package com.example.devicefinder;

import org.junit.Test;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class LanTransferPairingControlHostTest {
    @Test public void serviceHostCreatesPendingResponderSessionWithoutPersistingCredentials()
            throws Exception {
        final String local = "receiver";
        final String remote = "sender";
        final CountDownLatch requested = new CountDownLatch(1);
        final String[] callback = new String[3];
        LanTransferPairingControlHost host = new LanTransferPairingControlHost(local,
                (sessionId, peerId, sas, expiresAt) -> {
                    callback[0] = sessionId;
                    callback[1] = peerId;
                    callback[2] = sas;
                    requested.countDown();
                });
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingHandshake.InitiatorStart start =
                    LanTransferPairingHandshake.begin(remote, local, 1L);
            LanTransferPairingControlProtocol.Message response =
                    LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                            host.getBoundPort(), start.request, 2000, 3000);
            LanTransferPairingCrypto.Result initiatorMaterial =
                    LanTransferPairingHandshake.completeInitiator(start, response);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            assertEquals(start.request.sessionId, callback[0]);
            assertEquals(remote, callback[1]);
            assertEquals(initiatorMaterial.shortAuthenticationCode, callback[2]);
            assertEquals(1, host.pendingSessions().size());
            LanTransferPairingControlHost.Pending pending = host.pendingSessions().get(0);
            assertEquals(LanTransferPairingSession.State.WAITING_LOCAL_CONFIRMATION, pending.state);
            assertEquals(callback[0], pending.sessionId);
            assertEquals(callback[1], pending.remotePeerId);
            assertEquals(callback[2], pending.sas);
        } finally {
            host.close();
        }
        assertFalse(host.isRunning());
        assertTrue(host.pendingSessions().isEmpty());
    }

    @Test public void secretHandoffRequiresBothConfirmedSidesAndIsOneShot() throws Exception {
        final CountDownLatch requested = new CountDownLatch(1);
        final String[] values = new String[3];
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> {
                    values[0] = sessionId; values[1] = peerId; values[2] = sas;
                    requested.countDown();
                });
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingHandshake.InitiatorStart start =
                    LanTransferPairingHandshake.begin("sender", "receiver", UUID.randomUUID().toString(), 1L);
            LanTransferPairingControlProtocol.Message response =
                    LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                            host.getBoundPort(), start.request, 2000, 3000);
            LanTransferPairingCrypto.Result initiator =
                    LanTransferPairingHandshake.completeInitiator(start, response);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            try {
                host.takeSecretForCommit(values[0], values[1]);
                fail("one-sided session must not expose a commit secret");
            } catch (IllegalStateException expected) { }

            byte[] responderProof = host.confirmLocalProof(values[0], values[1], values[2]);
            LanTransferPairingSession.State state = host.applyRemoteConfirmation(
                    values[0], values[1], true, initiatorConfirmationProofForTest(start, response));
            assertEquals(LanTransferPairingSession.State.READY_TO_COMMIT, state);
            byte[] secret = host.takeSecretForCommit(values[0], values[1]);
            assertEquals(32, secret.length);
            assertArrayEquals(initiator.peerSecret, secret);
            java.util.Arrays.fill(secret, (byte) 0);
            try {
                host.takeSecretForCommit(values[0], values[1]);
                fail("commit handoff must be one-shot");
            } catch (IllegalArgumentException expected) { }
        } finally {
            host.close();
        }
    }


    @Test public void controlTcpAcceptsAuthenticatedRemoteConfirmationAndAcknowledgesIt()
            throws Exception {
        final CountDownLatch requested = new CountDownLatch(1);
        final String[] values = new String[3];
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> {
                    values[0] = sessionId; values[1] = peerId; values[2] = sas;
                    requested.countDown();
                });
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingHandshake.InitiatorStart start =
                    LanTransferPairingHandshake.begin("sender", "receiver",
                            UUID.randomUUID().toString(), 1L);
            LanTransferPairingControlProtocol.Message response =
                    LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                            host.getBoundPort(), start.request, 2000, 3000);
            LanTransferPairingCrypto.Result material =
                    LanTransferPairingHandshake.completeInitiator(start, response);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            byte[] responderProof = host.confirmLocalProof(values[0], values[1], values[2]);
            LanTransferPairingSession initiatorSession = new LanTransferPairingSession(
                    "sender", "receiver", LanTransferPairingSession.Role.INITIATOR,
                    material, 2L, 120_000L);
            initiatorSession.confirmLocal(material.shortAuthenticationCode, 3L);
            byte[] initiatorProof = initiatorSession.createLocalConfirmationProof(3L);
            LanTransferPairingControlProtocol.Message confirm =
                    new LanTransferPairingControlProtocol.Message(
                            LanTransferPairingControlProtocol.Type.CONFIRM, values[0],
                            "sender", "receiver", 2L, initiatorProof);
            LanTransferPairingControlProtocol.Message ack =
                    LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                            host.getBoundPort(), confirm, 2000, 3000);
            assertEquals(LanTransferPairingControlProtocol.Type.ACK, ack.type);
            assertEquals(33, ack.payload.length);
            assertEquals(1, ack.payload[0]);
            initiatorSession.applyRemoteConfirmation(true,
                    java.util.Arrays.copyOfRange(ack.payload, 1, ack.payload.length), 4L);
            assertEquals(LanTransferPairingSession.State.READY_TO_COMMIT,
                    initiatorSession.state(4L));
            assertEquals(LanTransferPairingSession.State.READY_TO_COMMIT,
                    host.pendingSessions().get(0).state);
            byte[] committedSecret = host.takeSecretForCommit(values[0], values[1]);
            assertArrayEquals(material.peerSecret, committedSecret);
            java.util.Arrays.fill(committedSecret, (byte) 0);
            assertEquals(32, responderProof.length);
        } finally {
            host.close();
        }
    }

    private static byte[] initiatorConfirmationProofForTest(
            LanTransferPairingHandshake.InitiatorStart start,
            LanTransferPairingControlProtocol.Message response) throws Exception {
        LanTransferPairingCrypto.Result material =
                LanTransferPairingHandshake.completeInitiator(start, response);
        LanTransferPairingSession session = new LanTransferPairingSession("sender", "receiver",
                LanTransferPairingSession.Role.INITIATOR, material, 1L, 120_000L);
        session.confirmLocal(material.shortAuthenticationCode, 2L);
        return session.createLocalConfirmationProof(2L);
    }

    @Test public void responderCanConfirmLocallyAfterRemoteConfirmationArrivesFirst()
            throws Exception {
        final CountDownLatch requested = new CountDownLatch(1);
        final String[] values = new String[3];
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> {
                    values[0] = sessionId; values[1] = peerId; values[2] = sas;
                    requested.countDown();
                });
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingHandshake.InitiatorStart start =
                    LanTransferPairingHandshake.begin("sender", "receiver",
                            UUID.randomUUID().toString(), 1L);
            LanTransferPairingControlProtocol.Message response =
                    LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                            host.getBoundPort(), start.request, 2000, 3000);
            LanTransferPairingCrypto.Result initiator =
                    LanTransferPairingHandshake.completeInitiator(start, response);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            byte[] initiatorProof = initiatorConfirmationProofForTest(start, response);
            assertEquals(LanTransferPairingSession.State.WAITING_LOCAL_CONFIRMATION,
                    host.applyRemoteConfirmation(values[0], values[1], true, initiatorProof));
            byte[] responderProof = host.confirmLocalProof(values[0], values[1], values[2]);
            assertEquals(32, responderProof.length);
            assertEquals(LanTransferPairingSession.State.READY_TO_COMMIT,
                    host.pendingSessions().get(0).state);
        } finally {
            host.close();
        }
    }

    @Test public void localSasMismatchRejectsAndRemovesPendingSession() throws Exception {
        final CountDownLatch requested = new CountDownLatch(1);
        final String[] sessionAndPeer = new String[3];
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> {
                    sessionAndPeer[0] = sessionId;
                    sessionAndPeer[1] = peerId;
                    sessionAndPeer[2] = sas;
                    requested.countDown();
                });
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingHandshake.InitiatorStart start =
                    LanTransferPairingHandshake.begin("sender", "receiver", UUID.randomUUID().toString(), 1L);
            LanTransferPairingControlTransport.exchange(InetAddress.getLoopbackAddress(),
                    host.getBoundPort(), start.request, 2000, 3000);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            assertEquals(LanTransferPairingSession.State.REJECTED,
                    host.confirmLocal(sessionAndPeer[0], sessionAndPeer[1],
                            sessionAndPeer[2].equals("000000") ? "000001" : "000000"));
            assertTrue(host.pendingSessions().isEmpty());
        } finally {
            host.close();
        }
    }
}
