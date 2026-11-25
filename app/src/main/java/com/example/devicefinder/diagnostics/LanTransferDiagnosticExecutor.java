package com.example.devicefinder.diagnostics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Small deterministic runner for diagnostic probes. It does not create sockets or mutate
 * production transfer state: concrete probes must call the real production interfaces.
 * Invoke from a dedicated background executor, never the Android main thread.
 */
public final class LanTransferDiagnosticExecutor {
    public interface Probe {
        /** False means the test is not applicable on this device/network and is recorded SKIPPED. */
        boolean isSupported() throws Exception;
        /** Execute one real probe and return bounded, non-sensitive integer metrics. */
        Map<String, Long> execute() throws Exception;
    }

    public interface Listener {
        void onCaseCompleted(LanTransferDiagnosticRun.CaseResult result, int completed, int total);
    }

    public interface Cancellation {
        boolean isCancelled();
    }

    public static final class TestCase {
        public final String id;
        private final Probe probe;
        public TestCase(String id, Probe probe) {
            if (id == null || id.trim().isEmpty() || probe == null)
                throw new IllegalArgumentException("Diagnostic test id and probe are required");
            this.id = id.trim();
            this.probe = probe;
        }
    }

    private LanTransferDiagnosticExecutor() {}

    public static LanTransferDiagnosticRun execute(LanTransferDiagnosticRun.Metadata metadata,
            List<TestCase> tests, int expectedCaseCount, Cancellation cancellation, Listener listener) {
        if (metadata == null || tests == null || expectedCaseCount < 1 || expectedCaseCount > 128
                || tests.size() > expectedCaseCount || tests.size() > 128)
            throw new IllegalArgumentException("Invalid diagnostic execution plan");
        HashSet<String> ids = new HashSet<>();
        for (TestCase test : tests) {
            if (test == null || !ids.add(test.id))
                throw new IllegalArgumentException("Null or duplicate diagnostic test");
        }

        List<LanTransferDiagnosticRun.CaseResult> results = new ArrayList<>();
        for (int i = 0; i < tests.size(); i++) {
            TestCase test = tests.get(i);
            long started = System.nanoTime();
            LanTransferDiagnosticRun.CaseResult result;
            if (cancellation != null && cancellation.isCancelled()) {
                result = skipped(test.id, "CANCELLED");
            } else {
                try {
                    if (!test.probe.isSupported()) {
                        result = skipped(test.id, "NOT_APPLICABLE");
                    } else {
                        Map<String, Long> metrics = test.probe.execute();
                        long duration = elapsedMs(started);
                        result = new LanTransferDiagnosticRun.CaseResult(test.id,
                                LanTransferDiagnosticRun.Status.PASS, duration, "", metrics);
                    }
                } catch (Exception failure) {
                    result = new LanTransferDiagnosticRun.CaseResult(test.id,
                            LanTransferDiagnosticRun.Status.FAIL, elapsedMs(started),
                            stableFailureCode(failure), null);
                }
            }
            results.add(result);
            if (listener != null) {
                try { listener.onCaseCompleted(result, results.size(), expectedCaseCount); }
                catch (RuntimeException ignored) { /* UI observers cannot abort diagnostics. */ }
            }
        }
        return new LanTransferDiagnosticRun(metadata, results, expectedCaseCount);
    }

    private static LanTransferDiagnosticRun.CaseResult skipped(String id, String code) {
        return new LanTransferDiagnosticRun.CaseResult(id,
                LanTransferDiagnosticRun.Status.SKIPPED, 0L, code, null);
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String stableFailureCode(Exception failure) {
        if (failure instanceof TimeoutException) return "TIMEOUT";
        if (failure instanceof SecurityException) return "SECURITY_REJECTED";
        if (failure instanceof IOException) return "IO_FAILURE";
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        }
        return "PROBE_FAILED";
    }
}
