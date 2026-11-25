package com.example.devicefinder.diagnostics;

import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagnosticRunHistoryStoreTest {
    private Context context;
    private DiagnosticRunHistoryStore store;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("diagnostic_run_history.db");
        store = new DiagnosticRunHistoryStore(context);
    }

    @After public void tearDown() {
        if (store != null) store.close();
        context.deleteDatabase("diagnostic_run_history.db");
    }

    private DiagnosticRun run(String runId, long startedAt, String feature) {
        DiagnosticRun.Metadata metadata = new DiagnosticRun.Metadata(feature, runId, startedAt,
                "1.0", "abc123", "TestDevice", 34, "wifi", "SECURE", "sender");
        return new DiagnosticRun(metadata, Collections.singletonList(
                new DiagnosticRun.CaseResult("probe", DiagnosticRun.Status.PASS,
                        12, "", Collections.singletonMap("rttMs", 8L))), 1);
    }

    @Test public void saveIsIdempotentAndListReturnsNewestFirst() {
        DiagnosticRun older = run("run-older", System.currentTimeMillis() - 2000L, "chat");
        DiagnosticRun newer = run("run-newer", System.currentTimeMillis() - 1000L, "file_transfer");
        store.save(older);
        store.save(older);
        store.save(newer);
        assertEquals(2, store.list(10).size());
        assertEquals("run-newer", store.list(10).get(0).metadata.runId);
        assertEquals("file_transfer", store.get("run-newer").metadata.featureId);
    }

    @Test public void conflictingRunIdCannotOverwriteExistingReport() {
        store.save(run("run-same", System.currentTimeMillis() - 1000L, "chat"));
        try {
            store.save(run("run-same", System.currentTimeMillis() - 1000L, "different"));
            fail("conflicting run id must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("different content"));
        }
        assertEquals("chat", store.get("run-same").metadata.featureId);
    }

    @Test public void deleteAndClearOnlyAffectHistory() {
        store.save(run("run-a", System.currentTimeMillis() - 2000L, "chat"));
        store.save(run("run-b", System.currentTimeMillis() - 1000L, "chat"));
        assertTrue(store.delete("run-a"));
        assertFalse(store.delete("run-a"));
        assertEquals(1, store.list(10).size());
        assertEquals(1, store.clear());
        assertTrue(store.list(10).isEmpty());
    }

    @Test public void retentionRemovesExpiredHistory() {
        long expired = System.currentTimeMillis() - DiagnosticRunHistoryStore.RETENTION_MS - 1000;
        store.save(run("run-expired", expired, "chat"));
        assertEquals(1, store.pruneExpiredReports());
        assertNull(store.get("run-expired"));
    }

    @Test public void jsonRoundTripPreservesRunAndMetrics() throws Exception {
        DiagnosticRun original = run("run-roundtrip", 1700000000000L, "network");
        DiagnosticRun decoded = DiagnosticJsonReport.fromJson(DiagnosticJsonReport.toJson(original));
        assertEquals(original.metadata.runId, decoded.metadata.runId);
        assertEquals(original.metadata.featureId, decoded.metadata.featureId);
        assertEquals(original.cases.get(0).metrics, decoded.cases.get(0).metrics);
        assertTrue(decoded.isCompleteAndPassing());
    }
}
