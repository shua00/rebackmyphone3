package com.example.devicefinder;

import org.junit.Test;
import static org.junit.Assert.*;

public class LanTransferPairingSessionTest {
    private static final long NOW = 10_000L;
    private static final long TTL = 60_000L;

    private static LanTransferPairingCrypto.Result[] materialPair() throws Exception {
        LanTransferPairingCrypto.Ephemeral a = LanTransferPairingCrypto.createEphemeral();
        LanTransferPairingCrypto.Ephemeral b = LanTransferPairingCrypto.createEphemeral();
        return new LanTransferPairingCrypto.Result[] {
            LanTransferPairingCrypto.deriveAsInitiator("peer-a", "peer-b", a, b.publicKey(), b.nonce()),
            LanTransferPairingCrypto.deriveAsResponder("peer-a", "peer-b", b, a.publicKey(), a.nonce())
        };
    }

    @Test public void neitherSideCanCommitUntilBothUsersConfirm() throws Exception {
        LanTransferPairingCrypto.Result[] pair = materialPair();
        LanTransferPairingSession a = new LanTransferPairingSession("peer-a", "peer-b",
                LanTransferPairingSession.Role.INITIATOR, pair[0], NOW, TTL);
        LanTransferPairingSession b = new LanTransferPairingSession("peer-b", "peer-a",
                LanTransferPairingSession.Role.RESPONDER, pair[1], NOW, TTL);

        a.confirmLocal(pair[0].shortAuthenticationCode, NOW + 1);
        assertEquals(LanTransferPairingSession.State.WAITING_REMOTE_CONFIRMATION, a.state(NOW + 1));
        try { a.peerSecretForCommit(NOW + 1); fail("one-sided confirmation must not commit"); }
        catch (IllegalStateException expected) { }

        b.confirmLocal(pair[1].shortAuthenticationCode, NOW + 2);
        byte[] proofB = b.createLocalConfirmationProof(NOW + 2);
        byte[] proofA = a.createLocalConfirmationProof(NOW + 2);
        a.applyRemoteConfirmation(true, proofB, NOW + 3);
        assertTrue(a.canCommit(NOW + 3));
        assertFalse(b.canCommit(NOW + 3));
        b.applyRemoteConfirmation(true, proofA, NOW + 4);
        assertTrue(b.canCommit(NOW + 4));
        assertArrayEquals(a.peerSecretForCommit(NOW + 4), b.peerSecretForCommit(NOW + 4));
    }

    @Test public void identicalRemoteConfirmationRetryIsIdempotentAfterReady() throws Exception {
        LanTransferPairingCrypto.Result[] pair = materialPair();
        LanTransferPairingSession a = new LanTransferPairingSession("peer-a", "peer-b",
                LanTransferPairingSession.Role.INITIATOR, pair[0], NOW, TTL);
        LanTransferPairingSession b = new LanTransferPairingSession("peer-b", "peer-a",
                LanTransferPairingSession.Role.RESPONDER, pair[1], NOW, TTL);
        a.confirmLocal(pair[0].shortAuthenticationCode, NOW + 1);
        b.confirmLocal(pair[1].shortAuthenticationCode, NOW + 1);
        byte[] proofB = b.createLocalConfirmationProof(NOW + 2);
        a.applyRemoteConfirmation(true, proofB, NOW + 3);
        assertEquals(LanTransferPairingSession.State.READY_TO_COMMIT, a.state(NOW + 3));
        a.applyRemoteConfirmation(true, proofB.clone(), NOW + 4);
        assertEquals(LanTransferPairingSession.State.READY_TO_COMMIT, a.state(NOW + 4));
        assertEquals(32, a.peerSecretForCommit(NOW + 4).length);
    }

    @Test public void conflictingRemoteConfirmationRetryRejectsReadySession() throws Exception {
        LanTransferPairingCrypto.Result[] pair = materialPair();
        LanTransferPairingSession a = new LanTransferPairingSession("peer-a", "peer-b",
                LanTransferPairingSession.Role.INITIATOR, pair[0], NOW, TTL);
        LanTransferPairingSession b = new LanTransferPairingSession("peer-b", "peer-a",
                LanTransferPairingSession.Role.RESPONDER, pair[1], NOW, TTL);
        a.confirmLocal(pair[0].shortAuthenticationCode, NOW + 1);
        b.confirmLocal(pair[1].shortAuthenticationCode, NOW + 1);
        byte[] proofB = b.createLocalConfirmationProof(NOW + 2);
        a.applyRemoteConfirmation(true, proofB, NOW + 3);
        proofB[0] ^= 1;
        try {
            a.applyRemoteConfirmation(true, proofB, NOW + 4);
            fail("conflicting replay must be rejected");
        } catch (SecurityException expected) { }
        assertEquals(LanTransferPairingSession.State.REJECTED, a.state(NOW + 4));
        try { a.peerSecretForCommit(NOW + 4); fail("rejected session must not commit"); }
        catch (IllegalStateException expected) { }
    }

    @Test public void wrongSasRejectsAndDestroysSessionMaterial() throws Exception {
        LanTransferPairingCrypto.Result[] pair = materialPair();
        LanTransferPairingSession session = new LanTransferPairingSession("peer-a", "peer-b",
                LanTransferPairingSession.Role.INITIATOR, pair[0], NOW, TTL);
        session.confirmLocal("000000", NOW + 1);
        assertEquals(LanTransferPairingSession.State.REJECTED, session.state(NOW + 1));
        try { session.peerSecretForCommit(NOW + 1); fail("rejected session must not expose secret"); }
        catch (IllegalStateException expected) { }
    }

    @Test public void tamperedRemoteProofIsRejected() throws Exception {
        LanTransferPairingCrypto.Result[] pair = materialPair();
        LanTransferPairingSession a = new LanTransferPairingSession("peer-a", "peer-b",
                LanTransferPairingSession.Role.INITIATOR, pair[0], NOW, TTL);
        LanTransferPairingSession b = new LanTransferPairingSession("peer-b", "peer-a",
                LanTransferPairingSession.Role.RESPONDER, pair[1], NOW, TTL);
        a.confirmLocal(pair[0].shortAuthenticationCode, NOW + 1);
        b.confirmLocal(pair[1].shortAuthenticationCode, NOW + 1);
        byte[] proof = b.createLocalConfirmationProof(NOW + 2);
        proof[0] ^= 1;
        a.applyRemoteConfirmation(true, proof, NOW + 2);
        assertEquals(LanTransferPairingSession.State.REJECTED, a.state(NOW + 2));
    }

    @Test public void rejectionCancellationDisconnectAndTimeoutNeverCommit() throws Exception {
        LanTransferPairingCrypto.Result[] pair = materialPair();
        LanTransferPairingSession rejected = new LanTransferPairingSession("peer-a", "peer-b",
                LanTransferPairingSession.Role.INITIATOR, pair[0], NOW, TTL);
        rejected.confirmLocal(pair[0].shortAuthenticationCode, NOW + 1);
        LanTransferPairingSession responder = new LanTransferPairingSession("peer-b", "peer-a",
                LanTransferPairingSession.Role.RESPONDER, pair[1], NOW, TTL);
        responder.confirmLocal(pair[1].shortAuthenticationCode, NOW + 1);
        rejected.applyRemoteConfirmation(false, responder.createLocalConfirmationProof(NOW + 2), NOW + 2);
        assertEquals(LanTransferPairingSession.State.REJECTED, rejected.state(NOW + 2));

        LanTransferPairingSession cancelled = new LanTransferPairingSession("peer-a", "peer-b",
                LanTransferPairingSession.Role.INITIATOR, pair[0], NOW, TTL);
        cancelled.disconnect(NOW + 3);
        assertEquals(LanTransferPairingSession.State.CANCELLED, cancelled.state(NOW + 3));

        LanTransferPairingSession expired = new LanTransferPairingSession("peer-a", "peer-b",
                LanTransferPairingSession.Role.INITIATOR, pair[0], NOW, TTL);
        assertEquals(LanTransferPairingSession.State.EXPIRED, expired.state(NOW + TTL));
        assertFalse(expired.canCommit(NOW + TTL));
    }
}
