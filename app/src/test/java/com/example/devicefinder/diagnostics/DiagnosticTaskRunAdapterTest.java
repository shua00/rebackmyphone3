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
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagnosticTaskRunAdapterTest {
    private static DiagnosticExecutor.TestCase test(String id) {
        return new DiagnosticExecutor.TestCase(id, new DiagnosticExecutor.Probe() {
            @Override public boolean isSupported() { return true; }
            @Override public java.util.Map<String, Long> execute() {
                return Collections.singletonMap("ok", 1L);
            }
        });
    }

    @Test public void selectsOnlyExplicitRegisteredProbeIds() {
        java.util.List<DiagnosticExecutor.TestCase> selected =
                DiagnosticTaskRunAdapter.selectRequestedProbes(
                        Arrays.asList(test("android.runtime"), test("lan.udp_bind")),
                        Arrays.asList("lan.udp_bind"));
        assertEquals(1, selected.size());
        assertEquals("lan.udp_bind", selected.get(0).id);
        try {
            DiagnosticTaskRunAdapter.selectRequestedProbes(
                    Arrays.asList(test("android.runtime")), Arrays.asList("shell.exec"));
            fail("unregistered shell operation accepted");
        } catch (SecurityException expected) { }
    }

    @Test public void executionRequiresConsentAndPersistsDiagnosticHistory() throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Rescue2AdminTrustStore trust = new Rescue2AdminTrustStore(context);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String fingerprint = trust.trustP256PublicKey(pair.getPublic().getEncoded());
        DiagnosticTaskEnvelope envelope = new DiagnosticTaskEnvelope("task-remote-01", fingerprint,
                Arrays.asList("phone-a"), Arrays.asList("android.runtime"),
                1800000000000L, 1800000600000L, "AAAAAAAAAAAAAAAAAAAAAA");
        String json = envelope.toJson();
        byte[] domain = "rebackmyphone3/rescue2-admin-task/v1\0".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(domain);
        signer.update(json.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();
        DiagnosticTaskVerifier.Result verified = DiagnosticTaskVerifier.verify(
                json, signature, "phone-a", 1800000000100L, trust);
        assertTrue(verified.accepted);

        DiagnosticTaskConsentStore consent = new DiagnosticTaskConsentStore(context);
        assertTrue(consent.registerPending(envelope, 1800000000100L));
        DiagnosticRunHistoryStore history = new DiagnosticRunHistoryStore(context);
        try {
            DiagnosticTaskRunAdapter.execute(context, verified, consent, history,
                    1800000000200L, null, null);
            fail("task executed without explicit consent");
        } catch (SecurityException expected) { }
        assertTrue(consent.approve(fingerprint, envelope.taskId, 1800000000200L));

        DiagnosticRun run = DiagnosticTaskRunAdapter.execute(context, verified, consent, history,
                1800000000300L, null, null);
        assertEquals(1, run.cases.size());
        assertEquals(DiagnosticRun.Status.PASS, run.cases.get(0).status);
        assertNotNull(history.get(run.metadata.runId));
        assertEquals(DiagnosticTaskConsentStore.State.COMPLETED,
                consent.getState(fingerprint, envelope.taskId, 1800000000400L));
    }
}
