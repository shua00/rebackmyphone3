package com.example.devicefinder.diagnostics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded runner for registered read-only diagnostic probes. */
public final class DiagnosticExecutor {
    public static final long DEFAULT_TIMEOUT_MS = 5000L;
    public interface Probe {
        boolean isSupported() throws Exception;
        Map<String, Long> execute() throws Exception;
    }
    public interface Listener {
        void onCaseCompleted(DiagnosticRun.CaseResult result, int completed, int total);
    }
    public interface Cancellation { boolean isCancelled(); }
    public static final class TestCase {
        public final String id;
        public final long timeoutMs;
        private final Probe probe;
        public TestCase(String id, Probe probe) { this(id, probe, DEFAULT_TIMEOUT_MS); }
        public TestCase(String id, Probe probe, long timeoutMs) {
            if (id == null || id.trim().isEmpty() || probe == null
                    || timeoutMs < 1 || timeoutMs > 120000L)
                throw new IllegalArgumentException("Invalid diagnostic test case");
            this.id = id.trim();
            this.probe = probe;
            this.timeoutMs = timeoutMs;
        }
    }
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private DiagnosticExecutor() {}

    public static DiagnosticRun execute(DiagnosticRun.Metadata metadata, List<TestCase> tests,
            int expectedCaseCount, Cancellation cancellation, Listener listener) {
        if (metadata == null || tests == null || expectedCaseCount < 1 || expectedCaseCount > 128
                || tests.size() > expectedCaseCount || tests.size() > 128)
            throw new IllegalArgumentException("Invalid diagnostic execution plan");
        HashSet<String> ids = new HashSet<>();
        for (TestCase test : tests) {
            if (test == null || !ids.add(test.id))
                throw new IllegalArgumentException("Null or duplicate diagnostic test");
        }
        List<DiagnosticRun.CaseResult> results = new ArrayList<>();
        for (TestCase test : tests) {
            long started = System.nanoTime();
            DiagnosticRun.CaseResult result = runOne(test, cancellation, started);
            results.add(result);
            if (listener != null) {
                try { listener.onCaseCompleted(result, results.size(), expectedCaseCount); }
                catch (RuntimeException ignored) { /* Observers cannot abort a run. */ }
            }
        }
        return new DiagnosticRun(metadata, results, expectedCaseCount);
    }

    private static DiagnosticRun.CaseResult runOne(TestCase test, Cancellation cancellation,
            long started) {
        if (cancelled(cancellation)) return outcome(test.id, DiagnosticRun.Status.CANCELLED,
                elapsedMs(started), "CANCELLED");
        FutureTask<Map<String, Long>> task = new FutureTask<>(() -> {
            if (!test.probe.isSupported()) return null;
            return test.probe.execute();
        });
        Thread worker = new Thread(task, "DiagnosticProbe-" + THREAD_IDS.incrementAndGet());
        worker.setDaemon(true);
        worker.start();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(test.timeoutMs);
        try {
            while (true) {
                if (cancelled(cancellation)) {
                    task.cancel(true);
                    return outcome(test.id, DiagnosticRun.Status.CANCELLED,
                            elapsedMs(started), "CANCELLED");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    task.cancel(true);
                    return outcome(test.id, DiagnosticRun.Status.TIMEOUT,
                            elapsedMs(started), "TIMEOUT");
                }
                try {
                    Map<String, Long> metrics = task.get(
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                            TimeUnit.NANOSECONDS);
                    if (metrics == null)
                        return outcome(test.id, DiagnosticRun.Status.SKIPPED,
                                elapsedMs(started), "NOT_APPLICABLE");
                    return new DiagnosticRun.CaseResult(test.id, DiagnosticRun.Status.PASS,
                            elapsedMs(started), "", metrics);
                } catch (TimeoutException polling) {
                    // Re-check cancellation and the absolute deadline.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return outcome(test.id, DiagnosticRun.Status.CANCELLED,
                    elapsedMs(started), "INTERRUPTED");
        } catch (ExecutionException wrapped) {
            Throwable failure = wrapped.getCause();
            String code = stableFailureCode(failure);
            DiagnosticRun.Status status = "TIMEOUT".equals(code)
                    ? DiagnosticRun.Status.TIMEOUT : DiagnosticRun.Status.FAIL;
            return outcome(test.id, status, elapsedMs(started), code);
        }
    }

    private static boolean cancelled(Cancellation cancellation) {
        return cancellation != null && cancellation.isCancelled();
    }
    private static DiagnosticRun.CaseResult outcome(String id, DiagnosticRun.Status status,
            long duration, String code) {
        return new DiagnosticRun.CaseResult(id, status, duration, code, null);
    }
    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
    private static String stableFailureCode(Throwable failure) {
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
