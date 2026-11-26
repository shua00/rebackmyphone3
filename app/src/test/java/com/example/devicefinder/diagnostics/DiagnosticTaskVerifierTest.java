package com.example.devicefinder.diagnostics;

import android.content.Context;
import com.example.devicefinder.Rescue2AdminTrustStore;
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
public class DiagnosticTaskVerifierTest {
    @Test public void verifiesSignatureExpiryAndExactTargetBeforeAccepting() throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Rescue2AdminTrustStore store = new Rescue2AdminTrustStore(context);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String fingerprint = store.trustP256PublicKey(pair.getPublic().getEncoded());
        DiagnosticTaskEnvelope task = new DiagnosticTaskEnvelope("task-01", fingerprint,
                Arrays.asList("phone-a", "phone-b"), Arrays.asList("android.runtime"),
                1800000000000L, 1800000600000L, "AAAAAAAAAAAAAAAAAAAAAA");
        String json = task.toJson();
        byte[] domain = "rebackmyphone3/rescue2-admin-task/v1\0".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(domain);
        signer.update(json.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();

        assertTrue(DiagnosticTaskVerifier.verify(json, signature, "phone-a",
                1800000000100L, store).accepted);
        assertEquals("WRONG_TARGET", DiagnosticTaskVerifier.verify(json, signature,
                "phone-c", 1800000000100L, store).errorCode);
        assertEquals("TASK_EXPIRED", DiagnosticTaskVerifier.verify(json, signature,
                "phone-a", 1800000600000L, store).errorCode);
        assertEquals("INVALID_SIGNATURE", DiagnosticTaskVerifier.verify(json, new byte[]{1},
                "phone-a", 1800000000100L, store).errorCode);
        assertEquals("NON_CANONICAL_TASK", DiagnosticTaskVerifier.verify(json + " ",
                signature, "phone-a", 1800000000100L, store).errorCode);
        assertEquals("MALFORMED_TASK", DiagnosticTaskVerifier.verify("{", signature,
                "phone-a", 1800000000100L, store).errorCode);
        DiagnosticTaskReplayStore replay = new DiagnosticTaskReplayStore(context);
        assertTrue(DiagnosticTaskVerifier.verifyAndConsume(json, signature, "phone-a",
                1800000000100L, store, replay).accepted);
        assertEquals("REPLAYED_OR_UNAVAILABLE", DiagnosticTaskVerifier.verifyAndConsume(
                json, signature, "phone-a", 1800000000200L, store,
                new DiagnosticTaskReplayStore(context)).errorCode);
    }
}
