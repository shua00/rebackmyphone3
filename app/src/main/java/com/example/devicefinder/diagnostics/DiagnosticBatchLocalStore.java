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
    static final int MAX_PENDING_OUTBOUND = 128;
    static final int MAX_INBOX_EVENTS = 512;
    static final long RETENTION_MS = 14L * 24L * 60L * 60L * 1000L;

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
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            pruneExpired(db, System.currentTimeMillis());
            if (exists(db, envelope.eventId)) {
                if (rejectDuplicate)
                    throw new IllegalStateException("Diagnostic event id already queued");
                db.setTransactionSuccessful();
                return false;
            }
            if ("out".equals(direction)) {
                long pending = count(db, "direction='out' AND delivered=0");
                if (pending >= MAX_PENDING_OUTBOUND)
                    throw new IllegalStateException("Diagnostic outbound queue is full ("
                            + MAX_PENDING_OUTBOUND + "); pending report was not discarded");
            } else {
                long inbox = count(db, "direction='in'");
                if (inbox >= MAX_INBOX_EVENTS) {
                    long excess = inbox - MAX_INBOX_EVENTS + 1;
                    db.execSQL("DELETE FROM " + TABLE + " WHERE event_id IN (SELECT event_id FROM "
                            + TABLE + " WHERE direction='in' ORDER BY created_at_ms ASC, "
                            + "sequence_no ASC LIMIT " + excess + ")");
                }
            }
            ContentValues values = new ContentValues();
            values.put("event_id", envelope.eventId);
            values.put("batch_id", envelope.batchId);
            values.put("participant_id", envelope.participantId);
            values.put("sequence_no", envelope.sequence);
            values.put("created_at_ms", envelope.createdAtEpochMs);
            values.put("envelope_json", envelope.toJson());
            values.put("direction", direction);
            values.put("delivered", 0);
            long result = db.insertWithOnConflict(TABLE, null, values,
                    ignoreDuplicate ? SQLiteDatabase.CONFLICT_IGNORE : SQLiteDatabase.CONFLICT_ABORT);
            if (rejectDuplicate && result == -1)
                throw new IllegalStateException("Diagnostic event id already queued");
            db.setTransactionSuccessful();
            return result != -1;
        } finally {
            db.endTransaction();
        }
    }

    /** Removes only expired inbox rows and already-delivered outbound rows. */
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
        long cutoff = now - RETENTION_MS;
        int inbox = db.delete(TABLE, "direction='in' AND created_at_ms<?",
                new String[]{String.valueOf(cutoff)});
        int delivered = db.delete(TABLE, "direction='out' AND delivered=1 AND created_at_ms<?",
                new String[]{String.valueOf(cutoff)});
        return inbox + delivered;
    }

    private boolean exists(SQLiteDatabase db, String eventId) {
        try (Cursor cursor = db.query(TABLE, new String[]{"event_id"}, "event_id=?",
                new String[]{eventId}, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    private long count(SQLiteDatabase db, String selection) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE " + selection,
                null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
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
