package com.example.devicefinder.diagnostics;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DiagnosticExecutorTest {
    private DiagnosticRun.Metadata metadata() {
        return DiagnosticRun.Metadata.create(1000, "1.0", "abc123",
                "TestDevice", 34, "wifi", "SECURE", "sender");
    }

    @Test public void executesSupportedProbeAndCollectsProgressAndMetrics() {
        AtomicInteger progress = new AtomicInteger();
        DiagnosticExecutor.TestCase probe = new DiagnosticExecutor.TestCase(
                "round_trip", new DiagnosticExecutor.Probe() {
            public boolean isSupported() { return true; }
            public Map<String, Long> execute() {
                LinkedHashMap<String, Long> metrics = new LinkedHashMap<>();
                metrics.put("rttMs", 15L);
                return metrics;
            }
        });
        DiagnosticRun run = DiagnosticExecutor.execute(metadata(),
                Collections.singletonList(probe), 1, () -> false,
                (result, completed, total) -> progress.set(completed));
        assertTrue(run.isCompleteAndPassing());
        assertEquals(1, progress.get());
        assertEquals(15L, (long) run.cases.get(0).metrics.get("rttMs"));
    }

    @Test public void unsupportedAndCancelledProbesAreSkippedNotPassed() {
        DiagnosticExecutor.TestCase unsupported = new DiagnosticExecutor.TestCase(
                "network", new DiagnosticExecutor.Probe() {
            public boolean isSupported() { return false; }
            public Map<String, Long> execute() { fail("unsupported probe must not run"); return null; }
        });
        DiagnosticRun skipped = DiagnosticExecutor.execute(metadata(),
                Collections.singletonList(unsupported), 1, null, null);
        assertEquals(DiagnosticRun.Status.SKIPPED, skipped.cases.get(0).status);
        assertFalse(skipped.isCompleteAndPassing());

        DiagnosticRun cancelled = DiagnosticExecutor.execute(metadata(),
                Collections.singletonList(unsupported), 1, () -> true, null);
        assertEquals("CANCELLED", cancelled.cases.get(0).failureCode);
    }

    @Test public void exceptionsBecomeStableCodesWithoutLeakingExceptionMessages() {
        DiagnosticExecutor.TestCase timeout = new DiagnosticExecutor.TestCase(
                "socket", new DiagnosticExecutor.Probe() {
            public boolean isSupported() { return true; }
            public Map<String, Long> execute() throws Exception {
                throw new TimeoutException("contains private address 10.0.0.1");
            }
        });
        DiagnosticRun run = DiagnosticExecutor.execute(metadata(),
                Collections.singletonList(timeout), 1, null, null);
        assertEquals("TIMEOUT", run.cases.get(0).failureCode);
        assertFalse(DiagnosticJsonReport.toJson(run).contains("10.0.0.1"));
    }

    @Test public void ioAndSecurityFailuresHaveDifferentStableCodes() {
        for (Exception failure : Arrays.asList(new IOException("socket"), new SecurityException("auth"))) {
            DiagnosticExecutor.TestCase probe = new DiagnosticExecutor.TestCase(
                    "case_" + failure.getClass().getSimpleName(),
                    new DiagnosticExecutor.Probe() {
                public boolean isSupported() { return true; }
                public Map<String, Long> execute() throws Exception { throw failure; }
            });
            DiagnosticRun run = DiagnosticExecutor.execute(metadata(),
                    Collections.singletonList(probe), 1, null, null);
            assertEquals(failure instanceof IOException ? "IO_FAILURE" : "SECURITY_REJECTED",
                    run.cases.get(0).failureCode);
        }
    }
}
