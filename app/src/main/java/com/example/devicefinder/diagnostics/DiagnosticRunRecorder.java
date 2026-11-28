package com.example.devicefinder.diagnostics;

import java.util.List;

/**
 * Owns the execution-to-history boundary. Call from a worker thread.
 * Persistence is mandatory: a completed run is not reported as successfully recorded
 * unless the history store confirms the save.
 */
public final class DiagnosticRunRecorder {
    public interface Store {
        void save(DiagnosticRun run) throws Exception;
    }

    public interface Completion {
        void onRecorded(DiagnosticRun run);
        void onRecordingFailed(DiagnosticRun run, Exception failure);
    }

    private final Store store;

    public DiagnosticRunRecorder(Store store) {
        if (store == null) throw new IllegalArgumentException("Diagnostic history store is required");
        this.store = store;
    }

    /** Adapter for the app's independent SQLite history store. */
    public static Store sqlite(DiagnosticRunHistoryStore history) {
        if (history == null) throw new IllegalArgumentException("History database is required");
        return history::save;
    }

    /** Executes registered probes and persists PASS, FAIL, and SKIPPED results. */
    public DiagnosticRun executeAndRecord(DiagnosticRun.Metadata metadata,
            List<DiagnosticExecutor.TestCase> tests, int expectedCaseCount,
            DiagnosticExecutor.Cancellation cancellation,
            DiagnosticExecutor.Listener progress) throws Exception {
        DiagnosticRun run = DiagnosticExecutor.execute(metadata, tests, expectedCaseCount,
                cancellation, progress);
        store.save(run);
        return run;
    }

    /** Callback variant; callbacks run on the caller's worker thread. */
    public DiagnosticRun executeAndRecord(DiagnosticRun.Metadata metadata,
            List<DiagnosticExecutor.TestCase> tests, int expectedCaseCount,
            DiagnosticExecutor.Cancellation cancellation,
            DiagnosticExecutor.Listener progress, Completion completion) throws Exception {
        final DiagnosticRun run;
        try {
            run = executeAndRecord(metadata, tests, expectedCaseCount, cancellation, progress);
        } catch (Exception failure) {
            if (completion != null) completion.onRecordingFailed(null, failure);
            throw failure;
        }
        if (completion != null) completion.onRecorded(run);
        return run;
    }
}
