package com.example.devicefinder.diagnostics;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** App-private durable spool for diagnostic events; completely separate from chat/file databases. */
public final class DiagnosticBatchLocalStore extends SQLiteOpenHelper
        implements DiagnosticBatchCoordinator.Store {
    private static final String DB_NAME = "diagnostic_batch_spool.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "diagnostic_events";

    public DiagnosticBatchLocalStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "event_id TEXT PRIMARY KEY NOT NULL,"
                + "batch_id TEXT NOT NULL,"
                + "participant_id TEXT NOT NULL,"
                + "sequence_no INTEGER NOT NULL,"
                + "created_at_ms INTEGER NOT NULL,"
                + "envelope_json TEXT NOT NULL,"
                + "direction TEXT NOT NULL CHECK(direction IN ('out','in')),"
                + "delivered INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_diag_outbox ON " + TABLE
                + "(direction,delivered,created_at_ms)");
        db.execSQL("CREATE INDEX idx_diag_batch_inbox ON " + TABLE
                + "(batch_id,direction,created_at_ms)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Schema changes must be additive and preserve queued reports.
    }

    @Override public synchronized void enqueue(DiagnosticBatchEnvelope envelope) {
        insert(envelope, "out", false, true);
    }

    @Override public synchronized List<DiagnosticBatchEnvelope> pending(int limit) {
        return query("direction='out' AND delivered=0", null, limit);
    }

    @Override public synchronized void markDelivered(String eventId) {
        ContentValues values = new ContentValues();
        values.put("delivered", 1);
        getWritableDatabase().update(TABLE, values, "event_id=? AND direction='out'",
                new String[]{eventId});
    }

    @Override public synchronized boolean acceptIncoming(DiagnosticBatchEnvelope envelope) {
        return insert(envelope, "in", true, false);
    }

    @Override public synchronized List<DiagnosticBatchEnvelope> received(String batchId, int limit) {
        return query("direction='in' AND batch_id=?", new String[]{batchId}, limit);
    }

    private boolean insert(DiagnosticBatchEnvelope envelope, String direction,
            boolean ignoreDuplicate, boolean rejectDuplicate) {
        if (envelope == null) throw new IllegalArgumentException("Envelope is required");
        ContentValues values = new ContentValues();
        values.put("event_id", envelope.eventId);
        values.put("batch_id", envelope.batchId);
        values.put("participant_id", envelope.participantId);
        values.put("sequence_no", envelope.sequence);
        values.put("created_at_ms", envelope.createdAtEpochMs);
        values.put("envelope_json", envelope.toJson());
        values.put("direction", direction);
        values.put("delivered", 0);
        long result = getWritableDatabase().insertWithOnConflict(TABLE, null, values,
                ignoreDuplicate ? SQLiteDatabase.CONFLICT_IGNORE : SQLiteDatabase.CONFLICT_ABORT);
        if (rejectDuplicate && result == -1)
            throw new IllegalStateException("Diagnostic event id already queued");
        return result != -1;
    }

    private List<DiagnosticBatchEnvelope> query(String selection, String[] args, int limit) {
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("Invalid query limit");
        ArrayList<DiagnosticBatchEnvelope> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"envelope_json"},
                selection, args, null, null, "created_at_ms ASC, sequence_no ASC", String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                try { result.add(DiagnosticBatchEnvelope.fromJson(cursor.getString(0))); }
                catch (Exception corrupt) {
                    // A corrupt row is isolated; it cannot block later diagnostic reports.
                }
            }
        }
        return Collections.unmodifiableList(result);
    }
}
