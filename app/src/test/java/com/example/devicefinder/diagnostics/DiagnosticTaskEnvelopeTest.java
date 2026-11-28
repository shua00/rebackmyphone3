package com.example.devicefinder.diagnostics;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagnosticTaskEnvelopeTest {
    private DiagnosticTaskEnvelope task() {
        return new DiagnosticTaskEnvelope("task-01", repeat('a', 64),
                Arrays.asList("phone-a", "phone-b"),
                Arrays.asList("android.runtime", "lan.udp_bind"),
                1800000000000L, 1800000600000L, repeat('A', 22));
    }
    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test public void pythonCompatibleJsonRoundTrips() throws Exception {
        DiagnosticTaskEnvelope original = task();
        String encoded = original.toJson();
        assertNotNull(encoded);
        DiagnosticTaskEnvelope decoded = DiagnosticTaskEnvelope.fromJson(encoded);
        assertEquals(original.taskId, decoded.taskId);
        assertEquals(original.adminKeyFingerprint, decoded.adminKeyFingerprint);
        assertEquals(original.targetParticipantIds, decoded.targetParticipantIds);
        assertEquals(original.probeIds, decoded.probeIds);
        assertEquals(original.nonce, decoded.nonce);
        assertEquals(original.createdAtEpochMs, decoded.createdAtEpochMs);
        assertEquals(original.expiresAtEpochMs, decoded.expiresAtEpochMs);
    }

    @Test public void rejectsShellAndUnregisteredProbeNames() {
        try {
            new DiagnosticTaskEnvelope("task-01", repeat('a', 64),
                    Arrays.asList("phone-a"), Arrays.asList("shell.exec"),
                    1800000000000L, 1800000600000L, repeat('A', 22));
            fail("unregistered probe accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsDuplicateTargetsAndOverlongTasks() {
        try {
            new DiagnosticTaskEnvelope("task-01", repeat('a', 64),
                    Arrays.asList("phone-a", "phone-a"), Arrays.asList("android.runtime"),
                    1800000000000L, 1800000600000L, repeat('A', 22));
            fail("duplicate target accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            new DiagnosticTaskEnvelope("task-01", repeat('a', 64),
                    Arrays.asList("phone-a"), Arrays.asList("android.runtime"),
                    1800000000000L, 1800002400001L, repeat('A', 22));
            fail("overlong task accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsUnknownAndMissingJsonFields() throws Exception {
        String encoded = task().toJson();
        String withUnknown = encoded.replace("\"nonce\":", "\"extra\":true,\"nonce\":");
        try {
            DiagnosticTaskEnvelope.fromJson(withUnknown);
            fail("unknown field accepted");
        } catch (IllegalArgumentException expected) { }
        String withoutNonce = encoded.replace(",\"nonce\":\"" + repeat('A', 22) + "\"}", "}");
        try {
            DiagnosticTaskEnvelope.fromJson(withoutNonce);
            fail("missing field accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsWrongJsonPrimitiveTypesLikePythonProtocol() throws Exception {
        String encoded = task().toJson();
        JSONObject wrongTimestamp = new JSONObject(encoded);
        wrongTimestamp.put("createdAtEpochMs", "1800000000000");
        try {
            DiagnosticTaskEnvelope.fromJson(wrongTimestamp.toString());
            fail("string timestamp accepted");
        } catch (IllegalArgumentException expected) { }

        JSONObject wrongTargets = new JSONObject(encoded);
        JSONArray targets = new JSONArray().put(1).put("phone-b");
        wrongTargets.put("targetParticipantIds", targets);
        try {
            DiagnosticTaskEnvelope.fromJson(wrongTargets.toString());
            fail("numeric target accepted");
        } catch (IllegalArgumentException expected) { }

        JSONObject wrongSchema = new JSONObject(encoded);
        wrongSchema.put("schemaVersion", true);
        try {
            DiagnosticTaskEnvelope.fromJson(wrongSchema.toString());
            fail("boolean schema accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void expiryIsHalfOpen() {
        DiagnosticTaskEnvelope task = task();
        assertFalse(task.isValidAt(task.createdAtEpochMs - 1));
        assertTrue(task.isValidAt(task.createdAtEpochMs));
        assertFalse(task.isValidAt(task.expiresAtEpochMs));
    }
}
