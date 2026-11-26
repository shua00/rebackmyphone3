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

    @Test public void explicitLocalRejectionProducesAuthenticatedProofAndDestroysPendingSession()
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

            byte[] rejectionProof = host.rejectLocalProof(values[0], values[1]);
            LanTransferPairingSession initiator = new LanTransferPairingSession(
                    "sender", "receiver", LanTransferPairingSession.Role.INITIATOR,
                    material, 2L, 120_000L);
            initiator.applyRemoteConfirmation(false, rejectionProof, 3L);
            assertEquals(LanTransferPairingSession.State.REJECTED, initiator.state(3L));
            assertTrue(host.pendingSessions().isEmpty());
            try {
                host.rejectLocalProof(values[0], values[1]);
                fail("a rejected session must not be reusable");
            } catch (IllegalArgumentException expected) { }
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

    @Test public void durableCommitRequiresResponderPersistenceAndSupportsAuthenticatedRetry()
            throws Exception {
        final CountDownLatch requested = new CountDownLatch(1);
        final CountDownLatch staged = new CountDownLatch(1);
        final CountDownLatch committed = new CountDownLatch(1);
        final CountDownLatch recovered = new CountDownLatch(1);
        final String[] values = new String[3];
        final byte[][] stagedSecret = new byte[1][];
        final byte[][] committedSecret = new byte[1][];
        final int[] commitCount = new int[1];
        LanTransferPairingControlHost.Listener listener =
                new LanTransferPairingControlHost.Listener() {
            @Override public void onPairingRequested(String sessionId, String peerId,
                    String sas, long expiresAt) {
                values[0] = sessionId; values[1] = peerId; values[2] = sas;
                requested.countDown();
            }
            @Override public boolean onPairingReady(String sessionId, String peerId,
                    byte[] secret, byte[] localProof, byte[] remoteProof, long expiresAt) {
                if (secret.length != 32 || localProof == null || localProof.length != 32
                        || remoteProof == null || remoteProof.length != 32) return false;
                stagedSecret[0] = secret.clone();
                staged.countDown();
                return true;
            }
            @Override public boolean onPairingCommitRequested(String sessionId, String peerId,
                    byte[] secret) {
                if (!java.util.Arrays.equals(stagedSecret[0], secret)) return false;
                committedSecret[0] = secret.clone();
                commitCount[0]++;
                committed.countDown();
                return true;
            }
            @Override public LanTransferPairingControlProtocol.Message onPairingRecoveryMessage(
                    LanTransferPairingControlProtocol.Message request) throws Exception {
                if (request.type != LanTransferPairingControlProtocol.Type.COMMIT
                        || committedSecret[0] == null
                        || !LanTransferPairingCommitProof.verify(committedSecret[0],
                                request.sessionId, request.senderPeerId, "receiver",
                                "COMMIT", request.payload)) return null;
                recovered.countDown();
                byte[] ack = LanTransferPairingCommitProof.create(committedSecret[0],
                        request.sessionId, "receiver", request.senderPeerId, "COMMIT_ACK");
                try {
                    return new LanTransferPairingControlProtocol.Message(
                            LanTransferPairingControlProtocol.Type.COMMIT_ACK,
                            request.sessionId, "receiver", request.senderPeerId,
                            request.sequence, ack);
                } finally { java.util.Arrays.fill(ack, (byte) 0); }
            }
        };
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver", listener);
        LanTransferPairingInitiator initiator = null;
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            initiator = LanTransferPairingInitiator.connect("sender", "receiver",
                    InetAddress.getLoopbackAddress(), host.getBoundPort(), 2000, 3000);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            assertEquals(LanTransferPairingSession.State.WAITING_REMOTE_CONFIRMATION,
                    initiator.confirmLocal(initiator.shortAuthenticationCode(), 2000, 3000));
            byte[] responderProof = host.confirmLocalProof(values[0], values[1], values[2]);
            java.util.Arrays.fill(responderProof, (byte) 0);
            assertEquals(LanTransferPairingSession.State.READY_TO_COMMIT,
                    initiator.retryConfirmation(2000, 3000));
            assertTrue(staged.await(1, TimeUnit.SECONDS));
            byte[] secret = initiator.copySecretForCommit();
            try {
                initiator.sendCommit(secret, 2000, 3000);
                assertTrue(committed.await(1, TimeUnit.SECONDS));
                assertArrayEquals(stagedSecret[0], secret);
                assertTrue(host.pendingSessions().isEmpty());
                // A fresh sequence after an ACK loss is answered from the durable receipt path.
                initiator.sendCommit(secret, 2000, 3000);
                assertTrue(recovered.await(1, TimeUnit.SECONDS));
                assertEquals(1, commitCount[0]);
            } finally { java.util.Arrays.fill(secret, (byte) 0); }
        } finally {
            if (initiator != null) initiator.close();
            host.close();
            if (stagedSecret[0] != null) java.util.Arrays.fill(stagedSecret[0], (byte) 0);
            if (committedSecret[0] != null) java.util.Arrays.fill(committedSecret[0], (byte) 0);
        }
    }
}
