package com.example.devicefinder.diagnostics;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class Rescue2AdminTrustStoreTest {
    @Test public void trustVerifyAndRevokeAreIndependentOfPeerCredentials() throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Rescue2AdminTrustStore store = new Rescue2AdminTrustStore(context);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String fingerprint = store.trustP256PublicKey(pair.getPublic().getEncoded());
        byte[] payload = "canonical-task-envelope".getBytes(StandardCharsets.UTF_8);
        byte[] domain = "rebackmyphone3/rescue2-admin-task/v1\0".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(domain);
        signer.update(payload);
        byte[] signature = signer.sign();

        assertTrue(store.isTrusted(fingerprint));
        assertTrue(store.verify(fingerprint, payload, signature));
        assertFalse(store.verify(fingerprint, "tampered".getBytes(StandardCharsets.UTF_8), signature));
        assertTrue(store.revoke(fingerprint));
        assertFalse(store.isTrusted(fingerprint));
        assertFalse(store.verify(fingerprint, payload, signature));
        assertFalse(store.revoke(fingerprint));
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
