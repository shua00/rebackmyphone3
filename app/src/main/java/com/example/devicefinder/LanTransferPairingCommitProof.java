package com.example.devicefinder;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Transcript-session-bound authentication for the durable pairing COMMIT/COMMIT_ACK exchange. */
public final class LanTransferPairingCommitProof {
    private static final byte[] DOMAIN =
            "rebackmyphone3/lan-transfer-pairing/v1/durable-commit\0"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private LanTransferPairingCommitProof() {}

    public static byte[] create(byte[] peerSecret, String sessionId, String senderPeerId,
            String targetPeerId, String operation) throws GeneralSecurityException {
        validate(peerSecret, sessionId, senderPeerId, targetPeerId, operation);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(peerSecret, "HmacSHA256"));
        mac.update(DOMAIN);
        updateField(mac, sessionId);
        updateField(mac, senderPeerId);
        updateField(mac, targetPeerId);
        updateField(mac, operation);
        return mac.doFinal();
    }

    public static boolean verify(byte[] peerSecret, String sessionId, String senderPeerId,
            String targetPeerId, String operation, byte[] supplied)
            throws GeneralSecurityException {
        if (supplied == null || supplied.length != 32) return false;
        byte[] expected = create(peerSecret, sessionId, senderPeerId, targetPeerId, operation);
        try { return MessageDigest.isEqual(expected, supplied); }
        finally { java.util.Arrays.fill(expected, (byte) 0); }
    }

    /** Sends one idempotent commit request and authenticates the responder's acknowledgement. */
    public static void sendCommit(java.net.InetAddress address, int port, String localPeerId,
            String remotePeerId, String sessionId, byte[] peerSecret, long sequence,
            int connectTimeoutMs, int readTimeoutMs)
            throws GeneralSecurityException, java.io.IOException {
        byte[] proof = create(peerSecret, sessionId, localPeerId, remotePeerId, "COMMIT");
        try {
            LanTransferPairingControlProtocol.Message request =
                    new LanTransferPairingControlProtocol.Message(
                            LanTransferPairingControlProtocol.Type.COMMIT, sessionId,
                            localPeerId, remotePeerId, sequence, proof);
            LanTransferPairingControlProtocol.Message response =
                    LanTransferPairingControlTransport.exchange(address, port, request,
                            connectTimeoutMs, readTimeoutMs);
            if (!verify(peerSecret, sessionId, remotePeerId, localPeerId,
                    "COMMIT_ACK", response.payload))
                throw new java.io.IOException("Pairing commit acknowledgement authentication failed");
        } finally { java.util.Arrays.fill(proof, (byte) 0); }
    }

    private static void updateField(Mac mac, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mac.update((byte) (bytes.length >>> 24));
        mac.update((byte) (bytes.length >>> 16));
        mac.update((byte) (bytes.length >>> 8));
        mac.update((byte) bytes.length);
        mac.update(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
    }

    private static void validate(byte[] secret, String sessionId, String sender,
            String target, String operation) {
        if (secret == null || secret.length != 32 || sessionId == null
                || !UUID.fromString(sessionId).toString().equalsIgnoreCase(sessionId)
                || sender == null || !sender.matches("[A-Za-z0-9._:-]{1,128}")
                || target == null || !target.matches("[A-Za-z0-9._:-]{1,128}")
                || sender.equals(target)
                || !("COMMIT".equals(operation) || "COMMIT_ACK".equals(operation)))
            throw new IllegalArgumentException("Invalid pairing commit proof inputs");
    }
}
