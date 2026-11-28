package com.example.devicefinder.diagnostics;

import org.junit.Test;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DiagnosticRunRecorderTest {
    private DiagnosticRun.Metadata metadata() {
        return DiagnosticRun.Metadata.create(1000, "1.0", "abc123",
                "TestDevice", 34, "wifi", "SECURE", "sender");
    }

    @Test public void recordsFailAndSkippedResultsNotOnlyPassingRuns() throws Exception {
        AtomicInteger saves = new AtomicInteger();
        DiagnosticRunRecorder recorder = new DiagnosticRunRecorder(run -> {
            saves.incrementAndGet();
            assertEquals(1, run.count(DiagnosticRun.Status.FAIL));
            assertEquals(1, run.count(DiagnosticRun.Status.SKIPPED));
        });
        DiagnosticExecutor.TestCase failed = new DiagnosticExecutor.TestCase("failed",
                new DiagnosticExecutor.Probe() {
                    public boolean isSupported() { return true; }
                    public java.util.Map<String, Long> execute() { throw new IllegalStateException("private detail"); }
                });
        DiagnosticExecutor.TestCase skipped = new DiagnosticExecutor.TestCase("skipped",
                new DiagnosticExecutor.Probe() {
                    public boolean isSupported() { return false; }
                    public java.util.Map<String, Long> execute() { fail("must not execute"); return null; }
                });
        DiagnosticRun run = recorder.executeAndRecord(metadata(),
                java.util.Arrays.asList(failed, skipped), 2, null, null);
        assertEquals(1, saves.get());
        assertFalse(run.isCompleteAndPassing());
    }

    @Test public void persistenceFailureIsPropagatedAndReported() {
        AtomicInteger failedCallback = new AtomicInteger();
        DiagnosticRunRecorder recorder = new DiagnosticRunRecorder(run -> {
            throw new IllegalStateException("disk unavailable");
        });
        try {
            recorder.executeAndRecord(metadata(), Collections.emptyList(), 1, null, null,
                    new DiagnosticRunRecorder.Completion() {
                        public void onRecorded(DiagnosticRun run) { fail("must not report success"); }
                        public void onRecordingFailed(DiagnosticRun run, Exception failure) {
                            assertTrue(failure.getMessage().contains("disk unavailable"));
                            failedCallback.incrementAndGet();
                        }
                    });
            fail("persistence failure must reach caller");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("disk unavailable"));
        }
        assertEquals(1, failedCallback.get());
    }

    @Test public void emptyPlanStillPersistsAnExplicitIncompleteRun() throws Exception {
        AtomicInteger saves = new AtomicInteger();
        DiagnosticRunRecorder recorder = new DiagnosticRunRecorder(run -> {
            saves.incrementAndGet();
            assertEquals(0, run.cases.size());
            assertEquals(1, run.expectedCaseCount);
        });
        DiagnosticRun run = recorder.executeAndRecord(metadata(),
                Collections.emptyList(), 1, null, null);
        assertEquals(1, saves.get());
        assertFalse(run.isCompleteAndPassing());
    }
}
