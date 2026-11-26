package com.example.devicefinder;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;

/**
 * Explicit trust registry for Rescue2 administrator public keys.
 *
 * This registry authenticates the Rescue2 application identity only. It is not an
 * application permission system and must never be used to restrict Rescue2 shell/ADB
 * capabilities. Trust enrollment must be initiated by an explicit device-owner action.
 */
public final class Rescue2AdminTrustStore {
    private static final String PREFS = "rescue2_admin_trust_v1";
    private static final String ACTIVE_PREFIX = "active.";
    private static final String REVOKED_PREFIX = "revoked.";
    private static final byte[] SIGNATURE_DOMAIN =
            "rebackmyphone3/rescue2-admin-task/v1\0".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_KEYS = 16;
    private static final int MAX_PUBLIC_KEY_BYTES = 512;
    private static final int MAX_SIGNATURE_BYTES = 128;

    private final SharedPreferences preferences;

    public Rescue2AdminTrustStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Stores a validated P-256 X.509 SubjectPublicKeyInfo after explicit user approval. */
    public synchronized String trustP256PublicKey(byte[] encodedPublicKey) throws Exception {
        PublicKey key = decodeP256(encodedPublicKey);
        String fingerprint = fingerprint(key.getEncoded());
        String activeKey = ACTIVE_PREFIX + fingerprint;
        String revokedKey = REVOKED_PREFIX + fingerprint;
        if (preferences.contains(activeKey)) return fingerprint;
        int activeCount = 0;
        for (String name : preferences.getAll().keySet())
            if (name.startsWith(ACTIVE_PREFIX)) activeCount++;
        if (activeCount >= MAX_KEYS) throw new IllegalStateException("Administrator trust limit reached");
        boolean saved = preferences.edit().remove(revokedKey)
                .putString(activeKey, Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP)).commit();
        if (!saved) throw new IllegalStateException("Unable to persist administrator trust");
        return fingerprint;
    }

    public synchronized boolean revoke(String fingerprint) {
        if (!isFingerprint(fingerprint)) return false;
        String activeKey = ACTIVE_PREFIX + fingerprint;
        if (!preferences.contains(activeKey)) return false;
        boolean saved = preferences.edit().remove(activeKey)
                .putBoolean(REVOKED_PREFIX + fingerprint, true).commit();
        if (!saved) throw new IllegalStateException("Unable to persist administrator revocation");
        return true;
    }

    public synchronized boolean isTrusted(String fingerprint) {
        return isFingerprint(fingerprint) && preferences.contains(ACTIVE_PREFIX + fingerprint)
                && !preferences.getBoolean(REVOKED_PREFIX + fingerprint, false);
    }

    /** Verify a detached signature over domain-separated canonical task JSON bytes. */
    public synchronized boolean verify(String fingerprint, byte[] canonicalTaskJson, byte[] signatureBytes) {
        if (!isTrusted(fingerprint) || canonicalTaskJson == null || canonicalTaskJson.length == 0
                || canonicalTaskJson.length > 16 * 1024 || signatureBytes == null
                || signatureBytes.length == 0 || signatureBytes.length > MAX_SIGNATURE_BYTES)
            return false;
        try {
            String encoded = preferences.getString(ACTIVE_PREFIX + fingerprint, null);
            if (encoded == null) return false;
            byte[] publicBytes = Base64.decode(encoded, Base64.NO_WRAP);
            PublicKey key = decodeP256(publicBytes);
            if (!fingerprint(publicBytes).equals(fingerprint)) return false;
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(SIGNATURE_DOMAIN);
            verifier.update(canonicalTaskJson);
            return verifier.verify(signatureBytes);
        } catch (Exception invalid) {
            return false;
        }
    }

    private static PublicKey decodeP256(byte[] encoded) throws Exception {
        if (encoded == null || encoded.length < 64 || encoded.length > MAX_PUBLIC_KEY_BYTES)
            throw new IllegalArgumentException("Invalid administrator public key size");
        final PublicKey key;
        try {
            key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (java.security.GeneralSecurityException invalidKey) {
            throw new IllegalArgumentException("Invalid administrator EC public key", invalidKey);
        }
        if (!"EC".equalsIgnoreCase(key.getAlgorithm())
                || !(key instanceof java.security.interfaces.ECPublicKey)
                || ((java.security.interfaces.ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != 256)
            throw new IllegalArgumentException("Administrator key must be P-256");
        return key;
    }

    private static String fingerprint(byte[] encoded) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
