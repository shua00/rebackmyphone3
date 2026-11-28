package com.example.devicefinder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic core for the independent LAN-transfer pairing ceremony.
 *
 * The caller must exchange the encoded public key and nonce over the pairing transport,
 * display the returned SAS on BOTH devices, and persist the key only after BOTH local
 * confirmation decisions have been received. This class deliberately has no trust-store
 * or chat-discovery side effects.
 */
public final class LanTransferPairingCrypto {
    public static final int NONCE_BYTES = 32;
    public static final int SECRET_BYTES = 32;
    private static final String CURVE = "secp256r1";
    private static final byte[] TRANSCRIPT_DOMAIN =
            "rebackmyphone3/lan-transfer-pairing/v1/transcript\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_INFO =
            "rebackmyphone3/lan-transfer-pairing/v1/peer-secret\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SAS_DOMAIN =
            "rebackmyphone3/lan-transfer-pairing/v1/sas\0".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private LanTransferPairingCrypto() {}

    public static final class Ephemeral {
        private final KeyPair keyPair;
        private final byte[] nonce;

        private Ephemeral(KeyPair pair, byte[] nonce) {
            this.keyPair = pair;
            this.nonce = nonce.clone();
        }

        public byte[] publicKey() { return keyPair.getPublic().getEncoded().clone(); }
        public byte[] nonce() { return nonce.clone(); }
    }

    public static final class Result {
        public final byte[] peerSecret;
        public final String shortAuthenticationCode;
        public final byte[] transcriptHash;

        private Result(byte[] secret, String code, byte[] transcriptHash) {
            this.peerSecret = secret.clone();
            this.shortAuthenticationCode = code;
            this.transcriptHash = transcriptHash.clone();
        }
    }

    public static Ephemeral createEphemeral() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(CURVE), RANDOM);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return new Ephemeral(generator.generateKeyPair(), nonce);
    }

    /** Derive from the initiator's perspective; initiator fields are always transcript-first. */
    public static Result deriveAsInitiator(String initiatorPeerId, String responderPeerId,
            Ephemeral local, byte[] responderPublicKey, byte[] responderNonce)
            throws GeneralSecurityException {
        return derive(initiatorPeerId, responderPeerId, local,
                responderPublicKey, responderNonce, true);
    }

    /** Derive from the responder's perspective; arguments still identify both protocol roles. */
    public static Result deriveAsResponder(String initiatorPeerId, String responderPeerId,
            Ephemeral local, byte[] initiatorPublicKey, byte[] initiatorNonce)
            throws GeneralSecurityException {
        return derive(initiatorPeerId, responderPeerId, local,
                initiatorPublicKey, initiatorNonce, false);
    }

    private static Result derive(String initiatorId, String responderId, Ephemeral local,
            byte[] remotePublic, byte[] remoteNonce, boolean localIsInitiator)
            throws GeneralSecurityException {
        validatePeerId(initiatorId);
        validatePeerId(responderId);
        if (initiatorId.equals(responderId)) throw new IllegalArgumentException("Pairing identities must differ");
        if (local == null || remotePublic == null || remotePublic.length < 64
                || remotePublic.length > 512 || remoteNonce == null
                || remoteNonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("Invalid pairing exchange material");
        }
        byte[] initiatorPublic = localIsInitiator ? local.publicKey() : remotePublic.clone();
        byte[] responderPublic = localIsInitiator ? remotePublic.clone() : local.publicKey();
        byte[] initiatorNonce = localIsInitiator ? local.nonce() : remoteNonce.clone();
        byte[] responderNonce = localIsInitiator ? remoteNonce.clone() : local.nonce();
        byte[] transcript = transcript(initiatorId, responderId,
                initiatorPublic, responderPublic, initiatorNonce, responderNonce);

        KeyFactory factory = KeyFactory.getInstance("EC");
        java.security.PublicKey remote = factory.generatePublic(new X509EncodedKeySpec(remotePublic));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(local.keyPair.getPrivate());
        agreement.doPhase(remote, true);
        byte[] shared = agreement.generateSecret();
        byte[] transcriptHash = MessageDigest.getInstance("SHA-256").digest(transcript);
        byte[] salt = MessageDigest.getInstance("SHA-256").digest(concat(TRANSCRIPT_DOMAIN, transcript));
        byte[] prk = hmac(salt, shared);
        byte[] secret = hkdfExpand(prk, concat(HKDF_INFO, transcriptHash), SECRET_BYTES);
        byte[] sasBytes = hmac(secret, concat(SAS_DOMAIN, transcriptHash));
        long value = (((long)(sasBytes[0] & 0xff) << 24)
                | ((long)(sasBytes[1] & 0xff) << 16)
                | ((long)(sasBytes[2] & 0xff) << 8)
                | (long)(sasBytes[3] & 0xff)) % 1_000_000L;
        String sas = String.format(java.util.Locale.US, "%06d", value);
        java.util.Arrays.fill(shared, (byte) 0);
        java.util.Arrays.fill(prk, (byte) 0);
        return new Result(secret, sas, transcriptHash);
    }

    private static byte[] transcript(String initiatorId, String responderId,
            byte[] initiatorPublic, byte[] responderPublic,
            byte[] initiatorNonce, byte[] responderNonce) throws GeneralSecurityException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(TRANSCRIPT_DOMAIN);
            writeField(out, "P-256/ECDH+HKDF-SHA256");
            writeField(out, initiatorId);
            writeField(out, responderId);
            writeField(out, initiatorPublic);
            writeField(out, responderPublic);
            writeField(out, initiatorNonce);
            writeField(out, responderNonce);
            out.flush();
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new GeneralSecurityException("Unable to encode pairing transcript", impossible);
        }
    }

    private static void writeField(DataOutputStream out, String value) throws java.io.IOException {
        writeField(out, value.getBytes(StandardCharsets.UTF_8));
    }
    private static void writeField(DataOutputStream out, byte[] value) throws java.io.IOException {
        if (value == null || value.length > 4096) throw new IllegalArgumentException("Invalid transcript field");
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length)
            throws GeneralSecurityException {
        byte[] output = new byte[length], previous = new byte[0];
        int offset = 0;
        for (int counter = 1; offset < length; counter++) {
            previous = hmac(prk, concat(concat(previous, info), new byte[]{(byte) counter}));
            int count = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, output, offset, count);
            offset += count;
        }
        return output;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
    private static void validatePeerId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("Invalid pairing peer identity");
    }
}
