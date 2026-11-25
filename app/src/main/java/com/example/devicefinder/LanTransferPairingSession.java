package com.example.devicefinder;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bounded, transport-independent state machine for the two-sided file-transfer pairing ceremony.
 *
 * The transport must authenticate and deliver the remote confirmation proof. This class never
 * persists credentials and deliberately has no dependency on chat discovery, chat ACKs, or the
 * file-data TCP framing. The caller may merge/persist the peer secret only in READY_TO_COMMIT.
 */
public final class LanTransferPairingSession {
    public enum Role { INITIATOR, RESPONDER }
    public enum State {
        WAITING_LOCAL_CONFIRMATION,
        WAITING_REMOTE_CONFIRMATION,
        READY_TO_COMMIT,
        REJECTED,
        CANCELLED,
        EXPIRED
    }

    private static final byte[] CONFIRM_DOMAIN =
            "rebackmyphone3/lan-transfer-pairing/v1/confirmation\0".getBytes(StandardCharsets.UTF_8);

    private final String localPeerId;
    private final String remotePeerId;
    private final Role localRole;
    private final long expiresAtElapsedMs;
    private byte[] peerSecret;
    private byte[] transcriptHash;
    private String expectedSas;
    private boolean localConfirmed;
    private boolean remoteConfirmed;
    private State terminalState;

    public LanTransferPairingSession(String localPeerId, String remotePeerId, Role localRole,
            LanTransferPairingCrypto.Result material, long nowElapsedMs, long timeoutMs) {
        if (!validPeer(localPeerId) || !validPeer(remotePeerId)
                || localPeerId.equals(remotePeerId) || localRole == null || material == null
                || material.peerSecret == null || material.peerSecret.length != 32
                || material.transcriptHash == null || material.transcriptHash.length != 32
                || material.shortAuthenticationCode == null
                || !material.shortAuthenticationCode.matches("[0-9]{6}")
                || timeoutMs < 1_000L || timeoutMs > 10 * 60_000L
                || nowElapsedMs < 0L || nowElapsedMs > Long.MAX_VALUE - timeoutMs) {
            throw new IllegalArgumentException("Invalid pairing session configuration");
        }
        this.localPeerId = localPeerId;
        this.remotePeerId = remotePeerId;
        this.localRole = localRole;
        this.peerSecret = material.peerSecret.clone();
        this.transcriptHash = material.transcriptHash.clone();
        this.expectedSas = material.shortAuthenticationCode;
        this.expiresAtElapsedMs = nowElapsedMs + timeoutMs;
    }

    public synchronized State state(long nowElapsedMs) {
        expireIfNeeded(nowElapsedMs);
        if (terminalState != null) return terminalState;
        if (localConfirmed && remoteConfirmed) return State.READY_TO_COMMIT;
        if (localConfirmed) return State.WAITING_REMOTE_CONFIRMATION;
        return State.WAITING_LOCAL_CONFIRMATION;
    }

    /** A wrong SAS is terminal; callers must not offer a retry on the same transcript. */
    public synchronized void confirmLocal(String displayedSas, long nowElapsedMs) {
        requireActive(nowElapsedMs);
        if (localConfirmed) throw new IllegalStateException("Local confirmation already recorded");
        byte[] supplied = displayedSas == null ? new byte[0]
                : displayedSas.getBytes(StandardCharsets.US_ASCII);
        byte[] expected = expectedSas.getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, supplied)) {
            terminate(State.REJECTED);
            return;
        }
        localConfirmed = true;
    }

    /** Returns a transcript-bound proof for the local user's explicit confirmation. */
    public synchronized byte[] createLocalConfirmationProof(long nowElapsedMs)
            throws GeneralSecurityException {
        requireActive(nowElapsedMs);
        if (!localConfirmed) throw new IllegalStateException("Local user has not confirmed SAS");
        return confirmationProof(localRole, true);
    }

    /**
     * Applies the remote user's decision. The proof is checked against the negotiated peer secret,
     * transcript hash, remote role, and decision; unauthenticated or altered confirmations fail closed.
     */
    public synchronized void applyRemoteConfirmation(boolean accepted, byte[] proof,
            long nowElapsedMs) throws GeneralSecurityException {
        requireActive(nowElapsedMs);
        if (remoteConfirmed) throw new IllegalStateException("Remote confirmation already recorded");
        byte[] expected = confirmationProof(opposite(localRole), accepted);
        if (proof == null || !MessageDigest.isEqual(expected, proof)) {
            terminate(State.REJECTED);
            return;
        }
        if (!accepted) {
            terminate(State.REJECTED);
            return;
        }
        remoteConfirmed = true;
    }

    public synchronized void cancel(long nowElapsedMs) {
        expireIfNeeded(nowElapsedMs);
        if (terminalState == null) terminate(State.CANCELLED);
    }

    /** Called when the transport reports a disconnect; it never leaves a half-paired credential. */
    public synchronized void disconnect(long nowElapsedMs) {
        cancel(nowElapsedMs);
    }

    public synchronized boolean canCommit(long nowElapsedMs) {
        return state(nowElapsedMs) == State.READY_TO_COMMIT;
    }

    /** Returns a defensive copy only after both users have confirmed. */
    public synchronized byte[] peerSecretForCommit(long nowElapsedMs) {
        if (!canCommit(nowElapsedMs) || peerSecret == null) {
            throw new IllegalStateException("Both pairing confirmations are required before commit");
        }
        return peerSecret.clone();
    }

    public String localPeerId() { return localPeerId; }
    public String remotePeerId() { return remotePeerId; }
    public Role localRole() { return localRole; }

    private byte[] confirmationProof(Role role, boolean accepted) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(peerSecret, "HmacSHA256"));
        mac.update(CONFIRM_DOMAIN);
        mac.update(transcriptHash);
        mac.update(role.name().getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) (accepted ? 1 : 0));
        return mac.doFinal();
    }

    private void requireActive(long nowElapsedMs) {
        expireIfNeeded(nowElapsedMs);
        if (terminalState != null) throw new IllegalStateException("Pairing session is terminal");
        if (localConfirmed && remoteConfirmed) throw new IllegalStateException("Pairing is ready to commit");
    }

    private void expireIfNeeded(long nowElapsedMs) {
        if (terminalState == null && nowElapsedMs >= expiresAtElapsedMs) terminate(State.EXPIRED);
    }

    private void terminate(State state) {
        terminalState = state;
        if (peerSecret != null) Arrays.fill(peerSecret, (byte) 0);
        if (transcriptHash != null) Arrays.fill(transcriptHash, (byte) 0);
        expectedSas = null;
        peerSecret = null;
        transcriptHash = null;
    }

    private static Role opposite(Role role) {
        return role == Role.INITIATOR ? Role.RESPONDER : Role.INITIATOR;
    }

    private static boolean validPeer(String peer) {
        return peer != null && peer.matches("[A-Za-z0-9._:-]{1,128}");
    }
}
