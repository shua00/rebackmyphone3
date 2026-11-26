package com.example.devicefinder;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
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

    /** Validates a public-only enrollment document without changing the trust registry. */
    public synchronized String previewExportedIdentity(String encodedJson) throws Exception {
        return fingerprint(parseExportedPublicKey(encodedJson).getEncoded());
    }

    /** Imports the public-only JSON exported by Rescue2 after the UI obtains explicit approval. */
    public synchronized String trustExportedIdentity(String encodedJson) throws Exception {
        return trustP256PublicKey(parseExportedPublicKey(encodedJson).getEncoded());
    }

    private static PublicKey parseExportedPublicKey(String encodedJson) throws Exception {
        if (encodedJson == null || encodedJson.length() > 8 * 1024)
            throw new IllegalArgumentException("Invalid Rescue2 identity document size");
        JSONObject document = new JSONObject(encodedJson);
        if (document.length() != 4 || !document.has("schemaVersion")
                || !document.has("keyType") || !document.has("fingerprint")
                || !document.has("publicKeySpki"))
            throw new IllegalArgumentException("Invalid Rescue2 identity document fields");
        Object schema = document.get("schemaVersion");
        Object keyType = document.get("keyType");
        Object fingerprintValue = document.get("fingerprint");
        Object publicKeyValue = document.get("publicKeySpki");
        if ((!(schema instanceof Integer) && !(schema instanceof Long))
                || ((Number) schema).longValue() != 1L || !(keyType instanceof String)
                || !"EC-P256".equals(keyType) || !(fingerprintValue instanceof String)
                || !(publicKeyValue instanceof String))
            throw new IllegalArgumentException("Unsupported Rescue2 identity document");
        String expectedFingerprint = (String) fingerprintValue;
        if (!isFingerprint(expectedFingerprint))
            throw new IllegalArgumentException("Invalid Rescue2 identity fingerprint");
        String encodedKey = (String) publicKeyValue;
        if (encodedKey.length() > 1024)
            throw new IllegalArgumentException("Public key is too large");
        byte[] publicKey = Base64.decode(encodedKey,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        PublicKey validated = decodeP256(publicKey);
        if (!expectedFingerprint.equals(fingerprint(validated.getEncoded())))
            throw new SecurityException("Rescue2 identity fingerprint does not match its public key");
        return validated;
    }

    /** Lists only active administrator fingerprints; no public key bytes or secrets are exposed. */
    public synchronized java.util.List<String> listTrustedFingerprints() {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String name : preferences.getAll().keySet()) {
            if (!name.startsWith(ACTIVE_PREFIX)) continue;
            String fingerprint = name.substring(ACTIVE_PREFIX.length());
            if (isTrusted(fingerprint)) result.add(fingerprint);
        }
        java.util.Collections.sort(result);
        return java.util.Collections.unmodifiableList(result);
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
                || !(key instanceof java.security.interfaces.ECPublicKey))
            throw new IllegalArgumentException("Administrator key must be P-256");
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
        ECParameterSpec actual = ((java.security.interfaces.ECPublicKey) key).getParams();
        if (!actual.getCurve().equals(expected.getCurve())
                || !actual.getGenerator().equals(expected.getGenerator())
                || !actual.getOrder().equals(expected.getOrder())
                || actual.getCofactor() != expected.getCofactor())
            throw new IllegalArgumentException("Administrator key must use secp256r1/P-256");
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
