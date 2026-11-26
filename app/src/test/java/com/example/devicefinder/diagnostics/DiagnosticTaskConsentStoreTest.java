package com.example.devicefinder.diagnostics;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagnosticTaskConsentStoreTest {
    private DiagnosticTaskEnvelope task(String id, String nonce) {
        char[] chars = new char[64];
        Arrays.fill(chars, 'a');
        return new DiagnosticTaskEnvelope(id, new String(chars), Arrays.asList("phone-a"),
                Arrays.asList("android.runtime"), 1800000000000L, 1800000600000L, nonce);
    }

    @Test public void requiresExplicitApprovalAndEnforcesTerminalTransitions() {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        DiagnosticTaskConsentStore store = new DiagnosticTaskConsentStore(context);
        DiagnosticTaskVerifier.Result verified01 = verified(task("task-01", repeat('A', 22)), 1800000000100L);
        String fingerprint01 = verified01.envelope.adminKeyFingerprint;
        assertTrue(verified01.replayProtected);
        assertTrue(store.registerPending(verified01, 1800000000100L));
        assertEquals(DiagnosticTaskConsentStore.State.PENDING,
                store.getState(fingerprint01, "task-01", 1800000000200L));
        assertEquals(Arrays.asList("task-01"), store.pendingTaskIds(fingerprint01, 1800000000200L));
        assertTrue(store.reject(fingerprint01, "task-01", 1800000000200L));
        assertFalse(store.approve(fingerprint01, "task-01", 1800000000300L));
        assertEquals(DiagnosticTaskConsentStore.State.REJECTED,
                store.getState(fingerprint01, "task-01", 1800000000300L));

        DiagnosticTaskVerifier.Result verified02 = verified(task("task-02", repeat('B', 22)), 1800000000100L);
        String fingerprint02 = verified02.envelope.adminKeyFingerprint;
        assertTrue(store.registerPending(verified02, 1800000000100L));
        assertTrue(store.approve(fingerprint02, "task-02", 1800000000200L));
        assertTrue(store.isApproved(fingerprint02, "task-02", 1800000000300L));
        assertTrue(store.cancel(fingerprint02, "task-02", 1800000000300L));
        assertFalse(store.markCompleted(fingerprint02, "task-02", 1800000000400L));
        assertEquals(DiagnosticTaskConsentStore.State.CANCELLED,
                store.getState(fingerprint02, "task-02", 1800000000400L));
    }

    @Test public void expiresAndPrunesOldPendingTasks() throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        DiagnosticTaskConsentStore store = new DiagnosticTaskConsentStore(context);
        com.example.devicefinder.Rescue2AdminTrustStore trust =
                new com.example.devicefinder.Rescue2AdminTrustStore(context);
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair pair = generator.generateKeyPair();
        String fingerprint = trust.trustP256PublicKey(pair.getPublic().getEncoded());

        DiagnosticTaskEnvelope first = new DiagnosticTaskEnvelope("task-01", fingerprint,
                Arrays.asList("phone-a"), Arrays.asList("android.runtime"),
                1800000000000L, 1800000600000L, repeat('A', 22));
        DiagnosticTaskVerifier.Result firstVerified = verifyWith(first, pair, trust, context, 1800000000100L);
        assertTrue(store.registerPending(firstVerified, 1800000000100L));
        assertEquals(DiagnosticTaskConsentStore.State.EXPIRED,
                store.getState(fingerprint, "task-01", 1800000600000L));

        DiagnosticTaskEnvelope second = new DiagnosticTaskEnvelope("task-01", fingerprint,
                Arrays.asList("phone-a"), Arrays.asList("android.runtime"),
                1800000600001L, 1800001200001L, repeat('B', 22));
        DiagnosticTaskVerifier.Result secondVerified = verifyWith(second, pair, trust, context, 1800000600100L);
        assertTrue(store.registerPending(secondVerified, 1800000600100L));
        assertEquals(DiagnosticTaskConsentStore.State.PENDING,
                store.getState(fingerprint, "task-01", 1800000600200L));
    }

    private DiagnosticTaskVerifier.Result verifyWith(DiagnosticTaskEnvelope envelope,
            java.security.KeyPair pair, com.example.devicefinder.Rescue2AdminTrustStore trust,
            Context context, long now) throws Exception {
        byte[] domain = "rebackmyphone3/rescue2-admin-task/v1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(domain);
        signer.update(envelope.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return DiagnosticTaskVerifier.verifyAndConsume(envelope.toJson(), signer.sign(),
                "phone-a", now, trust, new DiagnosticTaskReplayStore(context));
    }

    private DiagnosticTaskVerifier.Result verified(DiagnosticTaskEnvelope envelope, long now) {
        // Build a test-only authenticated result through the real verifier/replay guard.
        try {
            android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
            com.example.devicefinder.Rescue2AdminTrustStore trust =
                    new com.example.devicefinder.Rescue2AdminTrustStore(context);
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.KeyPair pair = generator.generateKeyPair();
            String fingerprint = trust.trustP256PublicKey(pair.getPublic().getEncoded());
            DiagnosticTaskEnvelope signed = new DiagnosticTaskEnvelope(envelope.taskId, fingerprint,
                    envelope.targetParticipantIds, envelope.probeIds, envelope.createdAtEpochMs,
                    envelope.expiresAtEpochMs, envelope.nonce);
            byte[] domain = "rebackmyphone3/rescue2-admin-task/v1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
            signer.initSign(pair.getPrivate());
            signer.update(domain);
            signer.update(signed.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] signature = signer.sign();
            return DiagnosticTaskVerifier.verifyAndConsume(signed.toJson(), signature,
                    "phone-a", now, trust, new DiagnosticTaskReplayStore(context));
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static String repeat(char c, int count) {
        char[] value = new char[count];
        Arrays.fill(value, c);
        return new String(value);
    }
}
