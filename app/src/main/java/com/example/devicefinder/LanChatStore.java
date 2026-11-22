package com.example.devicefinder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable transport ledger for LAN peer chat.
 *
 * This store deliberately knows nothing about floating messages or UI presentation.
 * A recipient ACK is only valid after storeIncoming() has committed successfully.
 */
public final class LanChatStore extends SQLiteOpenHelper {

    /** Bounded first-recipient collection window, started on the first actual discovery send. */
    public static final long FIRST_DISCOVERY_WINDOW_MS = 10_000L;
    private static final String DB_NAME = "lan_p2p_delivery.db";
    private static final int DB_VERSION = 5;
    public static final int MAX_PENDING_MESSAGES = 50;
    /** Maximum automatic sends in one retry epoch, including the original send. */
    public static final int MAX_AUTOMATIC_RETRY_ATTEMPTS = 8;
    /** A real network/peer recovery event may reopen an exhausted retry epoch once per minute. */
    public static final long RETRY_RECOVERY_EVENT_COOLDOWN_MS = 60_000L;

    public static final class PendingMessage {
        public final String messageId;
        public final String senderPeerId;
        public final String body;
        public final long createdAtMs;
        public final String state;
        public final int retryAttempts;

        PendingMessage(String id, String sender, String body, long created, String state, int retryAttempts) {
            this.messageId = id;
            this.senderPeerId = sender;
            this.body = body;
            this.createdAtMs = created;
            this.state = state;
            this.retryAttempts = retryAttempts;
        }
    }

    public static final class Target {
        public final String peerId;
        public final String status;
        public final int attemptCount;
        public final String endpointIp;
        public final int endpointPort;

        Target(String peerId, String status, int attempts, String ip, int port) {
            this.peerId = peerId;
            this.status = status;
            this.attemptCount = attempts;
            this.endpointIp = ip;
            this.endpointPort = port;
        }
    }

    public LanChatStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS lan_peers (" +
                "peer_id TEXT PRIMARY KEY NOT NULL," +
                "peer_type TEXT NOT NULL DEFAULT 'unknown'," +
                "service_id TEXT NOT NULL DEFAULT ''," +
                "protocol_version INTEGER NOT NULL DEFAULT 2," +
                "capabilities TEXT NOT NULL DEFAULT ''," +
                "network_scope TEXT NOT NULL DEFAULT ''," +
                "endpoint_ip TEXT NOT NULL DEFAULT ''," +
                "endpoint_port INTEGER NOT NULL DEFAULT 0," +
                "last_seen_at INTEGER NOT NULL DEFAULT 0," +
                "last_verified_at INTEGER NOT NULL DEFAULT 0," +
                "lease_expires_at INTEGER NOT NULL DEFAULT 0," +
                "status TEXT NOT NULL DEFAULT 'offline')");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_peers_lease ON lan_peers(status, lease_expires_at)");

        db.execSQL("CREATE TABLE IF NOT EXISTS lan_outbox (" +
                "message_id TEXT PRIMARY KEY NOT NULL," +
                "sender_peer_id TEXT NOT NULL," +
                "message_type TEXT NOT NULL DEFAULT 'chat'," +
                "body TEXT NOT NULL," +
                "created_at_ms INTEGER NOT NULL," +
                "protocol_version INTEGER NOT NULL DEFAULT 2," +
                "state TEXT NOT NULL DEFAULT 'discovery_pending'," +
                "delivery_deadline_ms INTEGER NOT NULL DEFAULT 0," +
                "retry_attempt_count INTEGER NOT NULL DEFAULT 0," +
                "last_retry_at_ms INTEGER NOT NULL DEFAULT 0," +
                "last_event_retry_at_ms INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_outbox_state ON lan_outbox(state, created_at_ms)");

        db.execSQL("CREATE TABLE IF NOT EXISTS lan_outbox_targets (" +
                "message_id TEXT NOT NULL," +
                "peer_id TEXT NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'pending'," +
                "attempt_count INTEGER NOT NULL DEFAULT 0," +
                "last_attempt_at INTEGER NOT NULL DEFAULT 0," +
                "last_ack_at INTEGER NOT NULL DEFAULT 0," +
                "last_endpoint TEXT NOT NULL DEFAULT ''," +
                "last_error TEXT NOT NULL DEFAULT ''," +
                "PRIMARY KEY(message_id, peer_id)," +
                "FOREIGN KEY(message_id) REFERENCES lan_outbox(message_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_targets_status ON lan_outbox_targets(message_id, status)");

        db.execSQL("CREATE TABLE IF NOT EXISTS lan_inbox (" +
                "receiver_scope TEXT NOT NULL," +
                "sender_peer_id TEXT NOT NULL," +
                "message_id TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "created_at_ms INTEGER NOT NULL DEFAULT 0," +
                "received_at_ms INTEGER NOT NULL," +
                "display_time TEXT NOT NULL DEFAULT ''," +
                "display_name TEXT NOT NULL DEFAULT ''," +
                "displayed INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(receiver_scope, sender_peer_id, message_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_inbox_received ON lan_inbox(receiver_scope, received_at_ms)");

        db.execSQL("CREATE TABLE IF NOT EXISTS lan_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
    }

    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            int nameIndex = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (nameIndex >= 0 && column.equalsIgnoreCase(c.getString(nameIndex))) return true;
            }
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    private boolean rowExists(SQLiteDatabase db, String table, String where, String[] args) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT 1 FROM " + table + " WHERE " + where + " LIMIT 1", args);
            return c.moveToFirst();
        } finally {
            if (c != null) c.close();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Additive migration only. Any unexpected error must abort the upgrade transaction,
        // leaving SQLiteOpenHelper at the previous version so migration can safely retry.
        if (oldVersion < 2 && !hasColumn(db, "lan_inbox", "display_name")) {
            db.execSQL("ALTER TABLE lan_inbox ADD COLUMN display_name TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 3) {
            if (!hasColumn(db, "lan_inbox", "displayed")) {
                db.execSQL("ALTER TABLE lan_inbox ADD COLUMN displayed INTEGER NOT NULL DEFAULT 0");
            }
            // Rows that existed before v3 already passed through the old history path.
            // Mark them as displayed so an upgrade never replays historical messages.
            db.execSQL("UPDATE lan_inbox SET displayed=1");
        }
        if (oldVersion < 4) {
            if (!hasColumn(db, "lan_outbox", "retry_attempt_count")) {
                db.execSQL("ALTER TABLE lan_outbox ADD COLUMN retry_attempt_count INTEGER NOT NULL DEFAULT 0");
            }
            if (!hasColumn(db, "lan_outbox", "last_retry_at_ms")) {
                db.execSQL("ALTER TABLE lan_outbox ADD COLUMN last_retry_at_ms INTEGER NOT NULL DEFAULT 0");
            }
        }
        if (oldVersion < 5 && !hasColumn(db, "lan_outbox", "last_event_retry_at_ms")) {
            db.execSQL("ALTER TABLE lan_outbox ADD COLUMN last_event_retry_at_ms INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 5) {
            db.execSQL("UPDATE lan_outbox SET state='retry_paused' " +
                    "WHERE retry_attempt_count>=8 AND state NOT IN ('delivered','expired','waiting_peers')");
        }
    }

    /** Persist an outgoing message before any network send. */
    public boolean enqueue(String messageId, String senderPeerId, String body, long createdAtMs) {
        if (messageId == null || messageId.length() == 0
                || senderPeerId == null || senderPeerId.length() == 0 || body == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM lan_outbox WHERE state NOT IN ('delivered','expired','waiting_peers')", null);
            int count = 0;
            try { if (c.moveToFirst()) count = c.getInt(0); } finally { c.close(); }

            Cursor exists = db.rawQuery(
                    "SELECT sender_peer_id,body FROM lan_outbox WHERE message_id=?",
                    new String[]{messageId});
            try {
                if (exists.moveToFirst()) {
                    boolean sameMessage = senderPeerId.equals(exists.getString(0))
                            && body.equals(exists.getString(1));
                    if (!sameMessage) return false;
                    db.setTransactionSuccessful();
                    return true;
                }
            } finally { exists.close(); }

            if (count >= MAX_PENDING_MESSAGES) return false;
            ContentValues v = new ContentValues();
            v.put("message_id", messageId);
            v.put("sender_peer_id", senderPeerId);
            v.put("message_type", "chat");
            v.put("body", body);
            v.put("created_at_ms", createdAtMs);
            v.put("protocol_version", 2);
            v.put("state", "discovery_pending");
            // A queued message may wait behind other sends before reaching the network.
            // Keep the deadline unset until the first actual discovery transmission begins.
            v.put("delivery_deadline_ms", 0L);
            long rowId = db.insertWithOnConflict("lan_outbox", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            if (rowId < 0 && !rowExists(db, "lan_outbox", "message_id=?", new String[]{messageId})) {
                return false;
            }
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /** Add the fixed recipient snapshot for a message. Empty snapshots remain pending, never delivered. */
    public boolean setTargets(String messageId, List<Target> targets) {
        if (messageId == null || targets == null || targets.isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor message = db.rawQuery(
                    "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
                    new String[]{messageId});
            String messageState = "";
            long discoveryDeadline = 0L;
            try {
                if (message.moveToFirst()) {
                    messageState = message.getString(0);
                    discoveryDeadline = message.getLong(1);
                }
            } finally { message.close(); }
            if (messageState.isEmpty()) return false;
            long now = System.currentTimeMillis();
            // A previously frozen or expired message must never acquire new recipients.
            // Treat this as an idempotent no-op rather than a retryable storage failure.
            if (!"discovery_pending".equals(messageState)
                    || (discoveryDeadline > 0 && now > discoveryDeadline)) {
                db.setTransactionSuccessful();
                return true;
            }
            if (discoveryDeadline == 0L) {
                discoveryDeadline = now + FIRST_DISCOVERY_WINDOW_MS;
                ContentValues window = new ContentValues();
                window.put("delivery_deadline_ms", discoveryDeadline);
                if (db.update("lan_outbox", window, "message_id=?",
                        new String[]{messageId}) != 1) return false;
            }
            boolean hadTargets = rowExists(db, "lan_outbox_targets", "message_id=?",
                    new String[]{messageId});
            int insertedOrExisting = 0;
            for (Target target : targets) {
                if (target == null || target.peerId == null || target.peerId.length() == 0) continue;
                ContentValues v = new ContentValues();
                v.put("message_id", messageId);
                v.put("peer_id", target.peerId);
                v.put("status", "pending");
                v.put("last_endpoint", target.endpointIp == null ? "" : target.endpointIp + ":" + target.endpointPort);
                long rowId = db.insertWithOnConflict(
                        "lan_outbox_targets", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                boolean exists = rowId >= 0 || rowExists(db, "lan_outbox_targets",
                        "message_id=? AND peer_id=?", new String[]{messageId, target.peerId});
                if (!exists) return false;
                insertedOrExisting++;
            }
            if (insertedOrExisting == 0) return false;
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id=?",
                    new String[]{messageId});
            int count = 0;
            try { if (c.moveToFirst()) count = c.getInt(0); } finally { c.close(); }
            if (count <= 0) return false;
            ContentValues state = new ContentValues();
            // If a peer already entered the open discovery window, keep collecting
            // until its original deadline. A direct known-member snapshot freezes now.
            state.put("state", hadTargets ? "discovery_pending" : "pending");
            if (db.update("lan_outbox", state, "message_id=?", new String[]{messageId}) != 1) return false;
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public boolean hasTargets(String messageId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM lan_outbox_targets WHERE message_id=? LIMIT 1",
                    new String[]{messageId});
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    public boolean allTargetsAcked(String messageId) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT o.state,o.delivery_deadline_ms,COUNT(t.peer_id), " +
                    "SUM(CASE WHEN t.status='acked' THEN 1 ELSE 0 END) " +
                    "FROM lan_outbox o LEFT JOIN lan_outbox_targets t ON t.message_id=o.message_id " +
                    "WHERE o.message_id=? GROUP BY o.message_id",
                    new String[]{messageId});
            if (!c.moveToFirst()) return false;
            String state = c.getString(0);
            long deadline = c.getLong(1);
            int total = c.getInt(2);
            int acked = c.isNull(3) ? 0 : c.getInt(3);
            long now = System.currentTimeMillis();
            boolean discoveryOpen = "discovery_pending".equals(state) && deadline > now;
            if (discoveryOpen || total == 0) return false;
            if (total != acked) {
                if ("discovery_pending".equals(state)) {
                    ContentValues next = new ContentValues();
                    next.put("state", "pending");
                    if (db.update("lan_outbox", next, "message_id=?", new String[]{messageId}) != 1) {
                        return false;
                    }
                }
                return false;
            }
            if (!"delivered".equals(state)) {
                ContentValues next = new ContentValues();
                next.put("state", "delivered");
                if (db.update("lan_outbox", next, "message_id=?", new String[]{messageId}) != 1) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Stable known-member snapshot, including currently offline peers; routes must be rediscovered before use. */
    public List<Target> loadKnownPeers() {
        ArrayList<Target> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT peer_id,endpoint_ip,endpoint_port FROM lan_peers ORDER BY last_seen_at DESC",
                    null);
            while (c.moveToNext()) result.add(new Target(c.getString(0), "pending", 0,
                    c.getString(1), c.getInt(2)));
            return result;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Load the durable retry source. Database/query failures must propagate to the
     * recovery owner; returning an empty list would falsely turn a storage failure
     * into "no pending messages" and could silently strand the outbox.
     */
    public List<PendingMessage> loadPending(int limit) {
        ArrayList<PendingMessage> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT message_id,sender_peer_id,body,created_at_ms,state,retry_attempt_count FROM lan_outbox " +
                    "WHERE state NOT IN ('delivered','expired','waiting_peers','retry_paused') " +
                    "AND retry_attempt_count<? ORDER BY retry_attempt_count,created_at_ms LIMIT ?",
                    new String[]{String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
                            String.valueOf(Math.max(1, Math.min(50, limit)))});
            while (c.moveToNext()) result.add(new PendingMessage(
                    c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
                    c.getString(4), c.getInt(5)));
            return result;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Reopen an exhausted retry epoch only after a real network/listener/peer recovery event.
     * The durable cooldown prevents repeated callbacks from resetting the finite retry budget.
     * ACKed targets remain ACKed; only messages with at least one unacked fixed target qualify.
     */
    public int resumeExhaustedRetriesForRecoveryEvent(long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            android.database.sqlite.SQLiteStatement update = db.compileStatement(
                    "UPDATE lan_outbox SET retry_attempt_count=0,last_event_retry_at_ms=?,state='pending' " +
                    "WHERE retry_attempt_count>=? " +
                    "AND state NOT IN ('delivered','expired','waiting_peers') " +
                    "AND last_event_retry_at_ms<=? " +
                    "AND EXISTS (SELECT 1 FROM lan_outbox_targets t " +
                    "WHERE t.message_id=lan_outbox.message_id AND t.status!='acked')");
            int changed;
            try {
                update.bindLong(1, now);
                update.bindLong(2, MAX_AUTOMATIC_RETRY_ATTEMPTS);
                update.bindLong(3, now - RETRY_RECOVERY_EVENT_COOLDOWN_MS);
                changed = update.executeUpdateDelete();
            } finally { update.close(); }
            db.setTransactionSuccessful();
            return changed;
        } catch (Exception e) {
            return 0;
        } finally {
            db.endTransaction();
        }
    }

    /** Persist one bounded delivery attempt for this message before network I/O. */
    public int recordOutboxAttempt(String messageId) {
        if (messageId == null || messageId.length() == 0) return -1;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor c = null;
        try {
            long now = System.currentTimeMillis();
            android.database.sqlite.SQLiteStatement update = db.compileStatement(
                    "UPDATE lan_outbox SET retry_attempt_count=retry_attempt_count+1,last_retry_at_ms=?," +
                    "state=CASE WHEN retry_attempt_count+1>=? THEN 'retry_paused' ELSE state END " +
                    "WHERE message_id=? AND state NOT IN ('delivered','expired','waiting_peers','retry_paused') " +
                    "AND retry_attempt_count<?");
            int changed;
            try {
                update.bindLong(1, now);
                update.bindLong(2, MAX_AUTOMATIC_RETRY_ATTEMPTS);
                update.bindString(3, messageId);
                update.bindLong(4, MAX_AUTOMATIC_RETRY_ATTEMPTS);
                changed = update.executeUpdateDelete();
            } finally {
                update.close();
            }
            if (changed != 1) return -1;
            c = db.rawQuery("SELECT retry_attempt_count FROM lan_outbox WHERE message_id=?",
                    new String[]{messageId});
            if (!c.moveToFirst()) return -1;
            int attempts = c.getInt(0);
            db.setTransactionSuccessful();
            return attempts;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.close();
            db.endTransaction();
        }
    }

    public List<Target> loadTargets(String messageId) {
        ArrayList<Target> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT peer_id,status,attempt_count,last_endpoint FROM lan_outbox_targets " +
                    "WHERE message_id=? ORDER BY peer_id", new String[]{messageId});
            while (c.moveToNext()) {
                String endpoint = c.getString(3);
                String ip = "";
                int port = 0;
                if (endpoint != null) {
                    int split = endpoint.lastIndexOf(':');
                    if (split > 0) {
                        ip = endpoint.substring(0, split);
                        try { port = Integer.parseInt(endpoint.substring(split + 1)); } catch (Exception ignored) {}
                    }
                }
                result.add(new Target(c.getString(0), c.getString(1), c.getInt(2), ip, port));
            }
            return result;
        } finally {
            if (c != null) c.close();
        }
    }

    public boolean markAttempt(String messageId, String peerId, String endpoint, String error) {
        if (messageId == null || peerId == null) return false;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("UPDATE lan_outbox_targets SET status='sent',attempt_count=attempt_count+1," +
                    "last_attempt_at=?,last_endpoint=?,last_error=? WHERE message_id=? AND peer_id=? AND status!='acked'",
                    new Object[]{System.currentTimeMillis(), endpoint == null ? "" : endpoint,
                            error == null ? "" : error, messageId, peerId});
            Cursor c = db.rawQuery(
                    "SELECT status FROM lan_outbox_targets WHERE message_id=? AND peer_id=?",
                    new String[]{messageId, peerId});
            try {
                return c.moveToFirst() && !"acked".equals(c.getString(0));
            } finally { c.close(); }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Accept a delayed ACK only from the endpoint durably recorded for this fixed recipient.
     * This remains valid after the short-lived in-memory route cache is evicted.
     */
    public boolean matchesExpectedAckEndpoint(String messageId, String peerId,
                                              String endpointIp, int endpointPort) {
        if (messageId == null || peerId == null || endpointIp == null || endpointIp.length() == 0
                || endpointPort <= 0 || endpointPort > 65535) return false;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM lan_outbox_targets WHERE message_id=? AND peer_id=? AND last_endpoint=? LIMIT 1",
                    new String[]{messageId, peerId, endpointIp + ":" + endpointPort});
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    public boolean acknowledge(String messageId, String peerId) {
        if (messageId == null || peerId == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            Cursor outbox = db.rawQuery(
                    "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
                    new String[]{messageId});
            String state = "";
            long deadline = 0L;
            try {
                if (outbox.moveToFirst()) {
                    state = outbox.getString(0);
                    deadline = outbox.getLong(1);
                }
            } finally { outbox.close(); }
            if (state.isEmpty()) return false;

            ContentValues ackValues = new ContentValues();
            ackValues.put("status", "acked");
            ackValues.put("last_ack_at", now);
            int changed = db.update("lan_outbox_targets", ackValues,
                    "message_id=? AND peer_id=?", new String[]{messageId, peerId});
            if (changed == 0) {
                Cursor target = db.rawQuery(
                        "SELECT status FROM lan_outbox_targets WHERE message_id=? AND peer_id=?",
                        new String[]{messageId, peerId});
                String existingStatus = null;
                try { if (target.moveToFirst()) existingStatus = target.getString(0); }
                finally { target.close(); }

                if ("acked".equals(existingStatus)) {
                    // A repeated ACK is idempotent; continue to reconcile the message state.
                } else {
                    if (existingStatus != null || !"discovery_pending".equals(state)
                            || (deadline > 0 && now > deadline)) return false;
                    if (deadline == 0L) {
                        deadline = now + FIRST_DISCOVERY_WINDOW_MS;
                        ContentValues window = new ContentValues();
                        window.put("delivery_deadline_ms", deadline);
                        if (db.update("lan_outbox", window, "message_id=?",
                                new String[]{messageId}) != 1) return false;
                    }
                    ContentValues targetValues = new ContentValues();
                    targetValues.put("message_id", messageId);
                    targetValues.put("peer_id", peerId);
                    targetValues.put("status", "acked");
                    targetValues.put("last_ack_at", now);
                    long targetRow = db.insertWithOnConflict(
                            "lan_outbox_targets", null, targetValues, SQLiteDatabase.CONFLICT_IGNORE);
                    if (targetRow < 0) {
                        Cursor verify = db.rawQuery(
                                "SELECT status FROM lan_outbox_targets WHERE message_id=? AND peer_id=?",
                                new String[]{messageId, peerId});
                        boolean persistedAck;
                        try { persistedAck = verify.moveToFirst() && "acked".equals(verify.getString(0)); }
                        finally { verify.close(); }
                        if (!persistedAck) return false;
                    }
                }
            }

            Cursor totals = db.rawQuery(
                    "SELECT COUNT(*),SUM(CASE WHEN status='acked' THEN 1 ELSE 0 END) " +
                    "FROM lan_outbox_targets WHERE message_id=?",
                    new String[]{messageId});
            int total = 0, acked = 0;
            try {
                if (totals.moveToFirst()) {
                    total = totals.getInt(0);
                    acked = totals.isNull(1) ? 0 : totals.getInt(1);
                }
            } finally { totals.close(); }

            boolean discoveryOpen = "discovery_pending".equals(state)
                    && deadline > 0 && now <= deadline;
            if (!discoveryOpen) {
                ContentValues next = new ContentValues();
                next.put("state", total > 0 && total == acked ? "delivered"
                        : ("retry_paused".equals(state) ? "retry_paused" : "pending"));
                if (db.update("lan_outbox", next, "message_id=?", new String[]{messageId}) != 1) return false;
            }
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }


    /**
     * Start the bounded first-recipient window when the first actual LAN discovery
     * send begins, not while a message is merely waiting in the sender executor.
     * Repeated calls never extend an already-started window.
     */
    public boolean beginDiscoveryWindow(String messageId) {
        if (messageId == null || messageId.length() == 0) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
                    new String[]{messageId});
            if (!c.moveToFirst()) return false;
            String state = c.getString(0);
            long deadline = c.getLong(1);
            c.close();
            c = null;
            if (!"discovery_pending".equals(state)) {
                db.setTransactionSuccessful();
                return false;
            }
            long now = System.currentTimeMillis();
            if (deadline > 0L) {
                if (now > deadline) {
                    // Preserve an empty expired snapshot for diagnostics without retrying forever.
                    finalizeDiscoveryMessage(db, messageId);
                    db.setTransactionSuccessful();
                    return false;
                }
                db.setTransactionSuccessful();
                return false; // Already started: open, but never extend or re-arm.
            }
            ContentValues window = new ContentValues();
            window.put("delivery_deadline_ms", now + FIRST_DISCOVERY_WINDOW_MS);
            if (db.update("lan_outbox", window, "message_id=?",
                    new String[]{messageId}) != 1) return false;
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
            db.endTransaction();
        }
    }


    /** Close an elapsed first-discovery window and settle its durable outbox state. */
    public boolean finalizeDiscoveryWindow(String messageId) {
        if (messageId == null || messageId.length() == 0) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
                    new String[]{messageId});
            if (!c.moveToFirst()) return false;
            String state = c.getString(0);
            long deadline = c.getLong(1);
            c.close();
            c = null;
            if (!"discovery_pending".equals(state) || deadline <= 0L
                    || System.currentTimeMillis() < deadline) {
                db.setTransactionSuccessful();
                return false;
            }
            if (!finalizeDiscoveryMessage(db, messageId)) return false;
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
            db.endTransaction();
        }
    }


    /** True when a bounded empty-recipient discovery has settled without inventing recipients. */
    public boolean isWaitingForPeers(String messageId) {
        if (messageId == null || messageId.length() == 0) return false;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM lan_outbox WHERE message_id=? AND state='waiting_peers'",
                    new String[]{messageId});
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    public boolean isDiscoveryPending(String messageId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM lan_outbox WHERE message_id=? AND state='discovery_pending' " +
                    "AND (delivery_deadline_ms<=0 OR delivery_deadline_ms>=?)",
                    new String[]{messageId, String.valueOf(System.currentTimeMillis())});
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Add a newly discovered peer to all open first-discovery snapshots. */
    public boolean addDiscoveryPeerToOpenMessages(String peerId) {
        if (peerId == null || peerId.length() == 0) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            Cursor c = db.rawQuery(
                    "SELECT message_id,delivery_deadline_ms FROM lan_outbox WHERE state='discovery_pending'",
                    null);
            try {
                while (c.moveToNext()) {
                    String messageId = c.getString(0);
                    long deadline = c.getLong(1);
                    if (deadline > 0 && now > deadline) {
                        if (!finalizeDiscoveryMessage(db, messageId)) return false;
                        continue;
                    }
                    if (deadline == 0L) {
                        ContentValues window = new ContentValues();
                        window.put("delivery_deadline_ms", now + FIRST_DISCOVERY_WINDOW_MS);
                        if (db.update("lan_outbox", window, "message_id=?",
                                new String[]{messageId}) != 1) return false;
                    }
                    ContentValues target = new ContentValues();
                    target.put("message_id", messageId);
                    target.put("peer_id", peerId);
                    target.put("status", "pending");
                    long rowId = db.insertWithOnConflict(
                            "lan_outbox_targets", null, target, SQLiteDatabase.CONFLICT_IGNORE);
                    if (rowId < 0 && !rowExists(db, "lan_outbox_targets",
                            "message_id=? AND peer_id=?", new String[]{messageId, peerId})) return false;
                }
            } finally { c.close(); }
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    private boolean finalizeDiscoveryMessage(SQLiteDatabase db, String messageId) {
        Cursor c = db.rawQuery(
                "SELECT COUNT(*),SUM(CASE WHEN status='acked' THEN 1 ELSE 0 END) " +
                "FROM lan_outbox_targets WHERE message_id=?",
                new String[]{messageId});
        int total = 0, acked = 0;
        try {
            if (c.moveToFirst()) {
                total = c.getInt(0);
                acked = c.isNull(1) ? 0 : c.getInt(1);
            }
        } finally { c.close(); }
        ContentValues next = new ContentValues();
        next.put("state", total > 0 && total == acked
                ? "delivered" : (total > 0 ? "pending" : "waiting_peers"));
        return db.update("lan_outbox", next, "message_id=?", new String[]{messageId}) == 1;
    }

    /** Returns true for both first storage and a durable identical duplicate; false means storage failed. */
    public boolean storeIncoming(String receiverScope, String senderPeerId, String messageId,
                                 String body, String displayName, long createdAtMs, String displayTime) {
        if (receiverScope == null || receiverScope.length() == 0
                || senderPeerId == null || senderPeerId.length() == 0
                || messageId == null || messageId.length() == 0 || body == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("receiver_scope", receiverScope);
            v.put("sender_peer_id", senderPeerId);
            v.put("message_id", messageId);
            v.put("body", body);
            v.put("created_at_ms", createdAtMs);
            v.put("received_at_ms", System.currentTimeMillis());
            v.put("display_time", displayTime == null ? "" : displayTime);
            v.put("display_name", displayName == null ? "" : displayName);
            long rowId = db.insertWithOnConflict("lan_inbox", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            if (rowId < 0) {
                Cursor existing = db.rawQuery(
                        "SELECT body FROM lan_inbox WHERE receiver_scope=? AND sender_peer_id=? AND message_id=?",
                        new String[]{receiverScope, senderPeerId, messageId});
                boolean identical;
                try { identical = existing.moveToFirst() && body.equals(existing.getString(0)); }
                finally { existing.close(); }
                if (!identical) return false;
            }
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Record an authenticated HELLO without treating it as a verified return route.
     * A changed IP/port clears the old verification; a same-route HELLO preserves it.
     */
    public boolean observePeer(String peerId, String peerType, String serviceId, String networkScope,
                               String ip, int port, long now, long leaseExpiresAt) {
        if (peerId == null || peerId.length() == 0 || ip == null || ip.length() == 0
                || port <= 0 || port > 65535) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String oldIp = null;
            int oldPort = -1;
            long oldVerifiedAt = 0L;
            String oldStatus = "suspect";
            Cursor c = db.rawQuery(
                    "SELECT endpoint_ip,endpoint_port,last_verified_at,status FROM lan_peers WHERE peer_id=?",
                    new String[]{peerId});
            try {
                if (c.moveToFirst()) {
                    oldIp = c.getString(0);
                    oldPort = c.getInt(1);
                    oldVerifiedAt = c.getLong(2);
                    oldStatus = c.getString(3);
                }
            } finally { c.close(); }

            boolean sameRoute = ip.equals(oldIp) && port == oldPort;
            ContentValues v = new ContentValues();
            v.put("peer_type", peerType == null ? "unknown" : peerType);
            v.put("service_id", serviceId == null ? "" : serviceId);
            v.put("protocol_version", 2);
            v.put("network_scope", networkScope == null ? "" : networkScope);
            v.put("endpoint_ip", ip);
            v.put("endpoint_port", port);
            v.put("last_seen_at", now);
            v.put("last_verified_at", sameRoute ? oldVerifiedAt : 0L);
            v.put("lease_expires_at", leaseExpiresAt);
            v.put("status", sameRoute && "active".equals(oldStatus) ? "active" : "suspect");
            if (oldIp == null) {
                v.put("peer_id", peerId);
                long rowId = db.insertWithOnConflict("lan_peers", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                if (rowId < 0 && !rowExists(db, "lan_peers", "peer_id=?", new String[]{peerId})) return false;
            } else {
                int updated = db.update("lan_peers", v, "peer_id=?", new String[]{peerId});
                if (updated <= 0 && !rowExists(db, "lan_peers", "peer_id=?", new String[]{peerId})) return false;
            }
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /** True once this durable inbox entry has been appended to the legacy chat history. */
    public boolean isIncomingDisplayed(String receiverScope, String senderPeerId, String messageId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT displayed FROM lan_inbox WHERE receiver_scope=? AND sender_peer_id=? AND message_id=?",
                    new String[]{receiverScope, senderPeerId, messageId});
            return c.moveToFirst() && c.getInt(0) != 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Mark presentation only after the history/ID SharedPreferences commit has succeeded. */
    public boolean markIncomingDisplayed(String receiverScope, String senderPeerId, String messageId) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("displayed", 1);
            return db.update("lan_inbox", values,
                    "receiver_scope=? AND sender_peer_id=? AND message_id=?",
                    new String[]{receiverScope, senderPeerId, messageId}) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean upsertPeer(String peerId, String peerType, String serviceId, String networkScope,
                              String ip, int port, long now, long leaseExpiresAt) {
        if (peerId == null || peerId.length() == 0 || ip == null || ip.length() == 0
                || port <= 0 || port > 65535) return false;
        ContentValues v = new ContentValues();
        v.put("peer_id", peerId);
        v.put("peer_type", peerType == null ? "unknown" : peerType);
        v.put("service_id", serviceId == null ? "" : serviceId);
        v.put("protocol_version", 2);
        v.put("network_scope", networkScope == null ? "" : networkScope);
        v.put("endpoint_ip", ip);
        v.put("endpoint_port", port);
        v.put("last_seen_at", now);
        v.put("last_verified_at", now);
        v.put("lease_expires_at", leaseExpiresAt);
        v.put("status", "active");
        try {
            SQLiteDatabase db = getWritableDatabase();
            long rowId = db.insertWithOnConflict("lan_peers", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            if (rowId >= 0) return true;
            Cursor c = db.rawQuery(
                    "SELECT 1 FROM lan_peers WHERE peer_id=? AND endpoint_ip=? AND endpoint_port=? " +
                    "AND status='active' AND last_verified_at=?",
                    new String[]{peerId, ip, String.valueOf(port), String.valueOf(now)});
            try { return c.moveToFirst(); } finally { c.close(); }
        } catch (Exception e) {
            return false;
        }
    }

    public void closeStore() {
        close();
    }
}
