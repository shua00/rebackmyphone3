package com.example.devicefinder.diagnostics;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import static org.junit.Assert.*;

public class LanTransferDiagnosticRunTest {
    private LanTransferDiagnosticRun.Metadata metadata() {
        return LanTransferDiagnosticRun.Metadata.create(1000, "1.0", "abc123",
                "TestDevice", 34, "wifi", "SECURE", "sender");
    }

    @Test public void reportSummarizesPassFailAndSkippedWithoutCallingPartialRunAccepted() {
        LanTransferDiagnosticRun run = new LanTransferDiagnosticRun(metadata(), Arrays.asList(
                new LanTransferDiagnosticRun.CaseResult("discovery", LanTransferDiagnosticRun.Status.PASS,
                        10, "", Collections.emptyMap()),
                new LanTransferDiagnosticRun.CaseResult("transfer", LanTransferDiagnosticRun.Status.FAIL,
                        20, "PEER_TIMEOUT", Collections.emptyMap()),
                new LanTransferDiagnosticRun.CaseResult("recovery", LanTransferDiagnosticRun.Status.SKIPPED,
                        0, "", Collections.emptyMap())), 3);
        assertEquals(1, run.count(LanTransferDiagnosticRun.Status.PASS));
        assertEquals(1, run.count(LanTransferDiagnosticRun.Status.FAIL));
        assertEquals(1, run.count(LanTransferDiagnosticRun.Status.SKIPPED));
        assertFalse(run.isCompleteAndPassing());
        String json = LanTransferDiagnosticReport.toJson(run);
        assertTrue(json.contains("\"failureCode\":\"PEER_TIMEOUT\""));
        assertTrue(json.contains("\"acceptancePassed\":false"));
    }

    @Test public void completePassingRunIsAcceptedAndMetricsAreExported() {
        LinkedHashMap<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("roundTripMs", 42L);
        metrics.put("bytesPerSecond", 8192L);
        LanTransferDiagnosticRun run = new LanTransferDiagnosticRun(metadata(), Collections.singletonList(
                new LanTransferDiagnosticRun.CaseResult("message_round_trip",
                        LanTransferDiagnosticRun.Status.PASS, 42, "", metrics)), 1);
        assertTrue(run.isCompleteAndPassing());
        assertTrue(LanTransferDiagnosticReport.toJson(run).contains("\"roundTripMs\":42"));
    }

    @Test public void incompleteSelectedSubsetCannotBeReportedAsFullAcceptance() {
        LanTransferDiagnosticRun run = new LanTransferDiagnosticRun(metadata(), Collections.singletonList(
                new LanTransferDiagnosticRun.CaseResult("discovery",
                        LanTransferDiagnosticRun.Status.PASS, 10, "", Collections.emptyMap())), 8);
        assertFalse(run.isCompleteAndPassing());
        assertTrue(LanTransferDiagnosticReport.toJson(run).contains("\"expectedCaseCount\":8"));
    }

    @Test public void reportEscapesMetadataAndNeverAddsUnprovidedSensitiveFields() {
        LanTransferDiagnosticRun.Metadata safe = LanTransferDiagnosticRun.Metadata.create(
                1000, "v\"1", "abc", "Device\\Model", 34, "wifi", "SECURE", "receiver");
        String json = LanTransferDiagnosticReport.toJson(
                new LanTransferDiagnosticRun(safe, Collections.emptyList(), 1));
        assertTrue(json.contains("v\\\"1"));
        assertFalse(json.contains("peerSecret"));
        assertFalse(json.contains("privateKey"));
        assertFalse(json.contains("fileContents"));
        assertFalse(json.contains("ipAddress"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void failedCaseRequiresStableFailureCode() {
        new LanTransferDiagnosticRun.CaseResult("socket", LanTransferDiagnosticRun.Status.FAIL,
                1, "", Collections.emptyMap());
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateCaseIdsAreRejected() {
        LanTransferDiagnosticRun.CaseResult result = new LanTransferDiagnosticRun.CaseResult(
                "discovery", LanTransferDiagnosticRun.Status.PASS, 1, "", Collections.emptyMap());
        new LanTransferDiagnosticRun(metadata(), Arrays.asList(result, result), 2);
    }
}
