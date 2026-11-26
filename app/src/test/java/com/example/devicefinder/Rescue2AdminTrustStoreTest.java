package com.example.devicefinder;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class Rescue2AdminTrustStoreTest {
    @Test public void verifiesCanonicalSignedTaskAndRejectsTamperingAfterRevocation() throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Rescue2AdminTrustStore store = new Rescue2AdminTrustStore(context);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String fingerprint = store.trustP256PublicKey(pair.getPublic().getEncoded());
        DiagnosticTaskEnvelope task = new DiagnosticTaskEnvelope("task-01", fingerprint,
                Arrays.asList("phone-a"), Arrays.asList("android.runtime"),
                1800000000000L, 1800000600000L, "AAAAAAAAAAAAAAAAAAAAAA");
        byte[] canonical = task.toJson().getBytes(StandardCharsets.UTF_8);
        byte[] domain = "rebackmyphone3/rescue2-admin-task/v1\0".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(domain);
        signer.update(canonical);
        byte[] signature = signer.sign();

        assertTrue(store.isTrusted(fingerprint));
        assertTrue(store.verify(fingerprint, canonical, signature));
        assertFalse(store.verify(fingerprint,
                (task.toJson() + " ").getBytes(StandardCharsets.UTF_8), signature));
        assertFalse(store.verify(fingerprint, canonical, new byte[]{1, 2, 3}));
        assertTrue(store.revoke(fingerprint));
        assertFalse(store.isTrusted(fingerprint));
        assertFalse(store.verify(fingerprint, canonical, signature));
        assertFalse(store.revoke(fingerprint));
    }

    @Test public void importsOnlyMatchingPublicIdentityDocument() throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Rescue2AdminTrustStore store = new Rescue2AdminTrustStore(context);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] publicKey = generator.generateKeyPair().getPublic().getEncoded();
        String fingerprint = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(publicKey));
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey);
        String document = "{\\"schemaVersion\\":1,\\"keyType\\":\\"EC-P256\\","
                + "\\"fingerprint\\":\\"" + fingerprint + "\\","
                + "\\"publicKeySpki\\":\\"" + encoded + "\\"}";
        assertEquals(fingerprint, store.trustExportedIdentity(document));
        assertTrue(store.isTrusted(fingerprint));
        try {
            store.trustExportedIdentity(document.replace(fingerprint, repeatZeroFingerprint()));
            fail("mismatched fingerprint accepted");
        } catch (SecurityException expected) { }
    }

    private static String repeatZeroFingerprint() {
        char[] value = new char[64];
        java.util.Arrays.fill(value, '0');
        return new String(value);
    }

    @Test public void rejectsNonP256Key() throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Rescue2AdminTrustStore store = new Rescue2AdminTrustStore(context);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        try {
            store.trustP256PublicKey(generator.generateKeyPair().getPublic().getEncoded());
            fail("RSA key accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
