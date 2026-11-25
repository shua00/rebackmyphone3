package com.example.devicefinder.diagnostics;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import static org.junit.Assert.*;

public class DiagnosticRunTest {
    private DiagnosticRun.Metadata metadata() {
        return DiagnosticRun.Metadata.create(1000, "1.0", "abc123",
                "TestDevice", 34, "wifi", "SECURE", "sender");
    }

    @Test public void reportSummarizesPassFailAndSkippedWithoutCallingPartialRunAccepted() {
        DiagnosticRun run = new DiagnosticRun(metadata(), Arrays.asList(
                new DiagnosticRun.CaseResult("discovery", DiagnosticRun.Status.PASS,
                        10, "", Collections.emptyMap()),
                new DiagnosticRun.CaseResult("transfer", DiagnosticRun.Status.FAIL,
                        20, "PEER_TIMEOUT", Collections.emptyMap()),
                new DiagnosticRun.CaseResult("recovery", DiagnosticRun.Status.SKIPPED,
                        0, "", Collections.emptyMap())), 3);
        assertEquals(1, run.count(DiagnosticRun.Status.PASS));
        assertEquals(1, run.count(DiagnosticRun.Status.FAIL));
        assertEquals(1, run.count(DiagnosticRun.Status.SKIPPED));
        assertFalse(run.isCompleteAndPassing());
        String json = DiagnosticJsonReport.toJson(run);
        assertTrue(json.contains("\"failureCode\":\"PEER_TIMEOUT\""));
        assertTrue(json.contains("\"acceptancePassed\":false"));
    }

    @Test public void completePassingRunIsAcceptedAndMetricsAreExported() {
        LinkedHashMap<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("roundTripMs", 42L);
        metrics.put("bytesPerSecond", 8192L);
        DiagnosticRun run = new DiagnosticRun(metadata(), Collections.singletonList(
                new DiagnosticRun.CaseResult("message_round_trip",
                        DiagnosticRun.Status.PASS, 42, "", metrics)), 1);
        assertTrue(run.isCompleteAndPassing());
        assertTrue(DiagnosticJsonReport.toJson(run).contains("\"roundTripMs\":42"));
    }

    @Test public void incompleteSelectedSubsetCannotBeReportedAsFullAcceptance() {
        DiagnosticRun run = new DiagnosticRun(metadata(), Collections.singletonList(
                new DiagnosticRun.CaseResult("discovery",
                        DiagnosticRun.Status.PASS, 10, "", Collections.emptyMap())), 8);
        assertFalse(run.isCompleteAndPassing());
        assertTrue(DiagnosticJsonReport.toJson(run).contains("\"expectedCaseCount\":8"));
    }

    @Test public void reportEscapesMetadataAndNeverAddsUnprovidedSensitiveFields() {
        DiagnosticRun.Metadata safe = DiagnosticRun.Metadata.create(
                1000, "v\"1", "abc", "Device\\Model", 34, "wifi", "SECURE", "receiver");
        String json = DiagnosticJsonReport.toJson(
                new DiagnosticRun(safe, Collections.emptyList(), 1));
        assertTrue(json.contains("v\\\"1"));
        assertFalse(json.contains("peerSecret"));
        assertFalse(json.contains("privateKey"));
        assertFalse(json.contains("fileContents"));
        assertFalse(json.contains("ipAddress"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void failedCaseRequiresStableFailureCode() {
        new DiagnosticRun.CaseResult("socket", DiagnosticRun.Status.FAIL,
                1, "", Collections.emptyMap());
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateCaseIdsAreRejected() {
        DiagnosticRun.CaseResult result = new DiagnosticRun.CaseResult(
                "discovery", DiagnosticRun.Status.PASS, 1, "", Collections.emptyMap());
        new DiagnosticRun(metadata(), Arrays.asList(result, result), 2);
    }
}
