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
import java.util.Arrays;
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
        assertTrue(store.verifyTaskEnvelope(task, canonical, signature));
        assertFalse(store.verifyTaskEnvelope(task,
                (task.toJson() + " ").getBytes(StandardCharsets.UTF_8), signature));
        assertFalse(store.verifyTaskEnvelope(task, canonical, new byte[]{1, 2, 3}));
        assertTrue(store.revoke(fingerprint));
        assertFalse(store.isTrusted(fingerprint));
        assertFalse(store.verifyTaskEnvelope(task, canonical, signature));
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
