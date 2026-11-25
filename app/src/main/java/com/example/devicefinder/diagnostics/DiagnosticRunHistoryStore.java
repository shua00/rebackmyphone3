package com.example.devicefinder.diagnostics;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded local history for completed diagnostic runs.
 *
 * This database is independent from chat, file-transfer, and batch-delivery storage.
 * Reports contain only the already-sanitized DiagnosticRun schema.
 */
public final class DiagnosticRunHistoryStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "diagnostic_run_history.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "diagnostic_runs";
    public static final int MAX_HISTORY = 100;
    public static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    public DiagnosticRunHistoryStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "run_id TEXT PRIMARY KEY NOT NULL,"
                + "feature_id TEXT NOT NULL,"
                + "started_at_ms INTEGER NOT NULL,"
                + "saved_at_ms INTEGER NOT NULL,"
                + "acceptance_passed INTEGER NOT NULL,"
                + "report_json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_diagnostic_runs_started ON " + TABLE
                + "(started_at_ms DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future schema changes must preserve report history.
    }

    /** Saves a report idempotently; a conflicting reuse of runId is rejected. */
    public synchronized void save(DiagnosticRun run) {
        if (run == null) throw new IllegalArgumentException("Diagnostic run is required");
        String json = DiagnosticJsonReport.toJson(run);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            pruneExpired(db, System.currentTimeMillis());
            try (Cursor existing = db.query(TABLE, new String[]{"report_json"},
                    "run_id=?", new String[]{run.metadata.runId}, null, null, null, "1")) {
                if (existing.moveToFirst()) {
                    if (!json.equals(existing.getString(0)))
                        throw new IllegalStateException("Diagnostic run id already exists with different content");
                    db.setTransactionSuccessful();
                    return;
                }
            }
            ContentValues values = new ContentValues();
            values.put("run_id", run.metadata.runId);
            values.put("feature_id", run.metadata.featureId);
            values.put("started_at_ms", run.metadata.startedAtEpochMs);
            values.put("saved_at_ms", System.currentTimeMillis());
            values.put("acceptance_passed", run.isCompleteAndPassing() ? 1 : 0);
            values.put("report_json", json);
            db.insertOrThrow(TABLE, null, values);
            db.execSQL("DELETE FROM " + TABLE + " WHERE run_id IN (SELECT run_id FROM "
                    + TABLE + " ORDER BY started_at_ms DESC, saved_at_ms DESC LIMIT -1 OFFSET "
                    + MAX_HISTORY + ")");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Returns newest reports first; malformed records are isolated from the remaining history. */
    public synchronized List<DiagnosticRun> list(int limit) {
        if (limit < 1 || limit > MAX_HISTORY)
            throw new IllegalArgumentException("Invalid diagnostic history limit");
        ArrayList<DiagnosticRun> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"report_json"},
                null, null, null, null, "started_at_ms DESC, saved_at_ms DESC",
                String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                try { result.add(DiagnosticJsonReport.fromJson(cursor.getString(0))); }
                catch (Exception malformed) {
                    // A corrupt row is skipped; it must not hide valid neighboring reports.
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized DiagnosticRun get(String runId) {
        if (runId == null || runId.trim().isEmpty())
            throw new IllegalArgumentException("Run id is required");
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"report_json"},
                "run_id=?", new String[]{runId}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            try { return DiagnosticJsonReport.fromJson(cursor.getString(0)); }
            catch (org.json.JSONException malformed) {
                throw new IllegalStateException("Stored diagnostic report is malformed", malformed);
            }
        }
    }

    public synchronized boolean delete(String runId) {
        if (runId == null || runId.trim().isEmpty())
            throw new IllegalArgumentException("Run id is required");
        return getWritableDatabase().delete(TABLE, "run_id=?", new String[]{runId}) > 0;
    }

    public synchronized int clear() {
        return getWritableDatabase().delete(TABLE, null, null);
    }

    public synchronized int pruneExpiredReports() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int removed = pruneExpired(db, System.currentTimeMillis());
            db.setTransactionSuccessful();
            return removed;
        } finally {
            db.endTransaction();
        }
    }

    private int pruneExpired(SQLiteDatabase db, long now) {
        return db.delete(TABLE, "started_at_ms<?",
                new String[]{String.valueOf(now - RETENTION_MS)});
    }
}
