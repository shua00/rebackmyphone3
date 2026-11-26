package com.example.devicefinder;

import org.junit.Test;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class LanTransferPairingInitiatorTest {
    @Test public void initiatorWaitsForLocalResponderDecisionThenCompletesWithFreshSequence()
            throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        String[] values = new String[3];
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> {
                    values[0] = sessionId; values[1] = peerId; values[2] = sas;
                    requested.countDown();
                });
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingInitiator initiator = LanTransferPairingInitiator.connect(
                    "sender", "receiver", InetAddress.getLoopbackAddress(),
                    host.getBoundPort(), 2000, 3000);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            assertEquals(values[0], initiator.sessionId());
            assertEquals(values[1], initiator.remotePeerId());
            assertEquals(values[2], initiator.shortAuthenticationCode());
            assertEquals(LanTransferPairingSession.State.WAITING_REMOTE_CONFIRMATION,
                    initiator.confirmLocal(initiator.shortAuthenticationCode(), 2000, 3000));
            assertEquals(LanTransferPairingSession.State.WAITING_LOCAL_CONFIRMATION,
                    host.pendingSessions().get(0).state);
            host.confirmLocalProof(values[0], values[1], values[2]);
            assertEquals(LanTransferPairingSession.State.READY_TO_COMMIT,
                    initiator.retryConfirmation(2000, 3000));
            initiator.close();
        } finally { host.close(); }
    }

    @Test public void initiatorLearnsAuthenticatedResponderRejectionOnNextConfirmationRetry()
            throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        String[] values = new String[3];
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> {
                    values[0] = sessionId; values[1] = peerId; values[2] = sas;
                    requested.countDown();
                });
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingInitiator initiator = LanTransferPairingInitiator.connect(
                    "sender", "receiver", InetAddress.getLoopbackAddress(),
                    host.getBoundPort(), 2000, 3000);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            host.rejectLocalForRemote(values[0], values[1]);
            assertEquals(LanTransferPairingSession.State.REJECTED,
                    initiator.confirmLocal(initiator.shortAuthenticationCode(), 2000, 3000));
            assertTrue(host.pendingSessions().isEmpty());
            initiator.close();
        } finally { host.close(); }
    }

    @Test public void initiatorLearnsResponderCancellationOnNextConfirmationRetry()
            throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        String[] values = new String[2];
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> {
                    values[0] = sessionId; values[1] = peerId; requested.countDown();
                });
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingInitiator initiator = LanTransferPairingInitiator.connect(
                    "sender", "receiver", InetAddress.getLoopbackAddress(),
                    host.getBoundPort(), 2000, 3000);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            host.cancelPendingForRemote(values[0], values[1]);
            assertEquals(LanTransferPairingSession.State.CANCELLED,
                    initiator.confirmLocal(initiator.shortAuthenticationCode(), 2000, 3000));
            assertTrue(host.pendingSessions().isEmpty());
            initiator.close();
        } finally { host.close(); }
    }

    @Test public void initiatorRejectsLocalSasMismatchBeforeSendingConfirmation() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> requested.countDown());
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingInitiator initiator = LanTransferPairingInitiator.connect(
                    "sender", "receiver", InetAddress.getLoopbackAddress(),
                    host.getBoundPort(), 2000, 3000);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            String wrong = initiator.shortAuthenticationCode().equals("000000") ? "000001" : "000000";
            try { initiator.confirmLocal(wrong, 2000, 3000); fail("SAS mismatch must fail closed"); }
            catch (SecurityException expected) { }
            assertEquals(LanTransferPairingSession.State.WAITING_LOCAL_CONFIRMATION,
                    host.pendingSessions().get(0).state);
            initiator.cancel(2000, 3000);
            assertTrue(host.pendingSessions().isEmpty());
        } finally { host.close(); }
    }

    @Test public void initiatorRejectSendsTranscriptBoundNegativeDecision() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> requested.countDown());
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingInitiator initiator = LanTransferPairingInitiator.connect(
                    "sender", "receiver", InetAddress.getLoopbackAddress(),
                    host.getBoundPort(), 2000, 3000);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            initiator.rejectLocal(2000, 3000);
            assertTrue(host.pendingSessions().isEmpty());
        } finally { host.close(); }
    }

    @Test public void initiatorCancelNotifiesResponderAndDestroysLocalSession() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        LanTransferPairingControlHost host = new LanTransferPairingControlHost("receiver",
                (sessionId, peerId, sas, expiresAt) -> requested.countDown());
        try {
            host.start(InetAddress.getLoopbackAddress(), 0);
            LanTransferPairingInitiator initiator = LanTransferPairingInitiator.connect(
                    "sender", "receiver", InetAddress.getLoopbackAddress(),
                    host.getBoundPort(), 2000, 3000);
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            initiator.cancel(2000, 3000);
            assertTrue(host.pendingSessions().isEmpty());
        } finally { host.close(); }
    }
}
