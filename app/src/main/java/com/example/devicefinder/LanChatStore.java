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

    /** Compatibility aliases; policy values are owned by LanChatRetryPolicy. */
    public static final long FIRST_DISCOVERY_WINDOW_MS = LanChatRetryPolicy.FIRST_DISCOVERY_WINDOW_MS;
    private static final String DB_NAME = "lan_p2p_delivery.db";
    // v9 records attempted endpoints; v10 adds durable receive fragments; v11 adds sender ACK bitmaps.
    private static final int DB_VERSION = 11;
    // Durable partial long-chat receive cache: strict per-message, session and global bounds.
    public static final long FRAGMENT_SESSION_TTL_MS = 30L * 60L * 1000L;
    public static final int MAX_FRAGMENT_SESSIONS = 32;
    public static final int MAX_FRAGMENT_CACHE_CHARS = 2 * 1024 * 1024;
    public static final int MAX_PENDING_MESSAGES = LanChatRetryPolicy.MAX_PENDING_MESSAGES;
    /** Maximum automatic sends in one retry epoch, including the original send. */
    public static final int MAX_AUTOMATIC_RETRY_ATTEMPTS = LanChatRetryPolicy.MAX_AUTOMATIC_RETRY_ATTEMPTS;
    /** A real network/peer recovery event may reopen an exhausted retry epoch once per minute. */
    public static final long RETRY_RECOVERY_EVENT_COOLDOWN_MS =
            LanChatRetryPolicy.RETRY_RECOVERY_EVENT_COOLDOWN_MS;

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

    /** One durable piece of an authenticated long-chat receive session. */
    public static final class IncomingFragment {
        public final String receiverScope;
        public final String senderPeerId;
        public final String messageId;
        public final int fragmentIndex;
        public final int fragmentCount;
        public final int totalBytes;
        public final String sha256;
        public final long createdAtMs;
        public final String base64Data;

        IncomingFragment(String receiverScope, String senderPeerId, String messageId,
                int fragmentIndex, int fragmentCount, int totalBytes, String sha256,
                long createdAtMs, String base64Data) {
            this.receiverScope = receiverScope;
            this.senderPeerId = senderPeerId;
            this.messageId = messageId;
            this.fragmentIndex = fragmentIndex;
            this.fragmentCount = fragmentCount;
            this.totalBytes = totalBytes;
            this.sha256 = sha256;
            this.createdAtMs = createdAtMs;
            this.base64Data = base64Data;
        }
    }

    public static final class IncomingMessage {
        public final String senderPeerId;
        public final String messageId;
        public final String body;
        public final String displayName;
        public final String displayTime;

        IncomingMessage(String senderPeerId, String messageId, String body,
                String displayName, String displayTime) {
            this.senderPeerId = senderPeerId;
            this.messageId = messageId;
            this.body = body;
            this.displayName = displayName;
            this.displayTime = displayTime;
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

    /** Read-only snapshot of durable per-recipient ACK progress for truthful diagnostics. */
    public static final class AckProgress {
        public final int totalTargets;
        public final int ackedTargets;

        AckProgress(int total, int acked) {
            this.totalTargets = total;
            this.ackedTargets = acked;
        }
    }

    /** Read-only per-message delivery summary for the chat window. */
    public static final class DeliverySummary {
        public final String messageId;
        public final String body;
        public final long createdAtMs;
        public final String state;
        public final int totalTargets;
        public final int ackedTargets;

        DeliverySummary(String id, String body, long createdAtMs, String state,
                        int totalTargets, int ackedTargets) {
            this.messageId = id;
            this.body = body;
            this.createdAtMs = createdAtMs;
            this.state = state;
            this.totalTargets = totalTargets;
            this.ackedTargets = ackedTargets;
        }
    }

    public LanChatStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // Enforce the declared outbox-target relationship on every connection.
        // This keeps future outbox cleanup/migrations from leaving orphan targets.
        db.setForeignKeyConstraintsEnabled(true);
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
                "last_sent_endpoint TEXT NOT NULL DEFAULT ''," +
                "last_attempt_endpoint TEXT NOT NULL DEFAULT ''," +
                "last_error TEXT NOT NULL DEFAULT ''," +
                "last_recovery_at_ms INTEGER NOT NULL DEFAULT 0," +
                "fragment_count INTEGER NOT NULL DEFAULT 0," +
                // Per-recipient selective ACK state survives process death; empty count means legacy/short message.
                "fragment_ack_bitmap TEXT NOT NULL DEFAULT ''," +
                "PRIMARY KEY(message_id, peer_id)," +
                "FOREIGN KEY(message_id) REFERENCES lan_outbox(message_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_targets_status ON lan_outbox_targets(message_id, status)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_targets_retry ON lan_outbox_targets(message_id, status, attempt_count, last_attempt_at)");

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

        db.execSQL("CREATE TABLE IF NOT EXISTS lan_chat_fragment_sessions (" +
                "receiver_scope TEXT NOT NULL," +
                "sender_peer_id TEXT NOT NULL," +
                "message_id TEXT NOT NULL," +
                "fragment_count INTEGER NOT NULL," +
                "total_bytes INTEGER NOT NULL," +
                "sha256 TEXT NOT NULL," +
                "created_at_ms INTEGER NOT NULL," +
                "first_seen_at_ms INTEGER NOT NULL," +
                "expires_at_ms INTEGER NOT NULL," +
                "PRIMARY KEY(receiver_scope,sender_peer_id,message_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_fragment_expiry " +
                "ON lan_chat_fragment_sessions(expires_at_ms)");
        db.execSQL("CREATE TABLE IF NOT EXISTS lan_chat_fragments (" +
                "receiver_scope TEXT NOT NULL," +
                "sender_peer_id TEXT NOT NULL," +
                "message_id TEXT NOT NULL," +
                "fragment_index INTEGER NOT NULL," +
                "fragment_data TEXT NOT NULL," +
                "PRIMARY KEY(receiver_scope,sender_peer_id,message_id,fragment_index)," +
                "FOREIGN KEY(receiver_scope,sender_peer_id,message_id) " +
                "REFERENCES lan_chat_fragment_sessions(receiver_scope,sender_peer_id,message_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_fragments_session " +
                "ON lan_chat_fragments(receiver_scope,sender_peer_id,message_id)");
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
                    "WHERE retry_attempt_count>=8 AND state NOT IN ('delivered','expired','waiting_peers','discovery_pending')");
        }
        if (oldVersion < 6 && !hasColumn(db, "lan_outbox_targets", "last_recovery_at_ms")) {
            db.execSQL("ALTER TABLE lan_outbox_targets ADD COLUMN last_recovery_at_ms INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 7) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_targets_retry " +
                    "ON lan_outbox_targets(message_id, status, attempt_count, last_attempt_at)");
        }
        if (oldVersion < 8 && !hasColumn(db, "lan_outbox_targets", "last_sent_endpoint")) {
            // Older builds used last_endpoint both as a challenge candidate and as ACK evidence.
            // Keep old route candidates, but require an actual successful send for durable ACK fallback.
            db.execSQL("ALTER TABLE lan_outbox_targets ADD COLUMN last_sent_endpoint TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 9 && !hasColumn(db, "lan_outbox_targets", "last_attempt_endpoint")) {
            db.execSQL("ALTER TABLE lan_outbox_targets ADD COLUMN last_attempt_endpoint TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 11) {
            if (!hasColumn(db, "lan_outbox_targets", "fragment_count")) {
                db.execSQL("ALTER TABLE lan_outbox_targets ADD COLUMN fragment_count INTEGER NOT NULL DEFAULT 0");
            }
            if (!hasColumn(db, "lan_outbox_targets", "fragment_ack_bitmap")) {
                db.execSQL("ALTER TABLE lan_outbox_targets ADD COLUMN fragment_ack_bitmap TEXT NOT NULL DEFAULT ''");
            }
        }
        if (oldVersion < 10) {
            db.execSQL("CREATE TABLE IF NOT EXISTS lan_chat_fragment_sessions (" +
                    "receiver_scope TEXT NOT NULL,sender_peer_id TEXT NOT NULL,message_id TEXT NOT NULL," +
                    "fragment_count INTEGER NOT NULL,total_bytes INTEGER NOT NULL,sha256 TEXT NOT NULL," +
                    "created_at_ms INTEGER NOT NULL,first_seen_at_ms INTEGER NOT NULL,expires_at_ms INTEGER NOT NULL," +
                    "PRIMARY KEY(receiver_scope,sender_peer_id,message_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_fragment_expiry " +
                    "ON lan_chat_fragment_sessions(expires_at_ms)");
            db.execSQL("CREATE TABLE IF NOT EXISTS lan_chat_fragments (" +
                    "receiver_scope TEXT NOT NULL,sender_peer_id TEXT NOT NULL,message_id TEXT NOT NULL," +
                    "fragment_index INTEGER NOT NULL,fragment_data TEXT NOT NULL," +
                    "PRIMARY KEY(receiver_scope,sender_peer_id,message_id,fragment_index)," +
                    "FOREIGN KEY(receiver_scope,sender_peer_id,message_id) " +
                    "REFERENCES lan_chat_fragment_sessions(receiver_scope,sender_peer_id,message_id) ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_fragments_session " +
                    "ON lan_chat_fragments(receiver_scope,sender_peer_id,message_id)");
        }
        if (oldVersion < 6) {
            // Fixed-recipient rows now use target-local counters. Discard the old aggregate
            // retry counter so it cannot distort the new per-target scheduling delay.
            db.execSQL("UPDATE lan_outbox SET retry_attempt_count=0,last_retry_at_ms=0 " +
                    "WHERE EXISTS (SELECT 1 FROM lan_outbox_targets t WHERE t.message_id=lan_outbox.message_id)");
            // Existing message-level pauses must not strand targets that still have
            // independent retry budget under the new per-recipient ledger.
            db.execSQL("UPDATE lan_outbox SET state='pending' WHERE state='retry_paused' " +
                    "AND EXISTS (SELECT 1 FROM lan_outbox_targets t WHERE t.message_id=lan_outbox.message_id " +
                    "AND t.status!='acked' AND t.attempt_count<8)");
        }
    }

    /** Persist an outgoing message before any network send. */
    /**
     * Atomically persist a new message and freeze its known recipient snapshot.
     *
     * The outbox row and fixed target rows share one SQLite transaction. This replaces
     * the old SharedPreferences mirror + outbox transaction + target-snapshot transaction
     * on the interactive send path, while legacy SharedPreferences rows remain migratable.
     * An empty snapshot intentionally remains discovery_pending for the bounded first
     * discovery window; later verified peers may join only that still-open window.
     */
    public boolean enqueueWithRecipientSnapshot(String messageId, String senderPeerId,
            String body, long createdAtMs,
            java.util.Map<String, java.net.InetSocketAddress> verifiedRoutes) {
        if (messageId == null || messageId.isEmpty()
                || !LanP2pProtocol.isValidPeerId(senderPeerId) || body == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT sender_peer_id,body,state,delivery_deadline_ms "
                    + "FROM lan_outbox WHERE message_id=?", new String[]{messageId});
            boolean exists = cursor.moveToFirst();
            String state = exists ? cursor.getString(2) : "";
            long deadline = exists ? cursor.getLong(3) : 0L;
            if (exists && (!senderPeerId.equals(cursor.getString(0))
                    || !body.equals(cursor.getString(1)))) return false;
            cursor.close();
            cursor = null;

            if (!exists) {
                cursor = db.rawQuery("SELECT COUNT(*) FROM lan_outbox "
                        + "WHERE state NOT IN ('delivered','expired','waiting_peers')", null);
                int count = 0;
                try { if (cursor.moveToFirst()) count = cursor.getInt(0); }
                finally { cursor.close(); cursor = null; }
                if (count >= MAX_PENDING_MESSAGES) return false;
                ContentValues outbox = new ContentValues();
                outbox.put("message_id", messageId);
                outbox.put("sender_peer_id", senderPeerId);
                outbox.put("message_type", "chat");
                outbox.put("body", body);
                outbox.put("created_at_ms", createdAtMs);
                outbox.put("protocol_version", 2);
                outbox.put("state", "discovery_pending");
                outbox.put("delivery_deadline_ms", 0L);
                if (db.insertWithOnConflict("lan_outbox", null, outbox,
                        SQLiteDatabase.CONFLICT_IGNORE) < 0
                        && !rowExists(db, "lan_outbox", "message_id=?", new String[]{messageId})) {
                    return false;
                }
                state = "discovery_pending";
                deadline = 0L;
            }

            // Idempotent re-entry must never alter an already frozen recipient set.
            if (rowExists(db, "lan_outbox_targets", "message_id=?", new String[]{messageId})
                    || !"discovery_pending".equals(state)
                    || (deadline > 0L && System.currentTimeMillis() >= deadline)) {
                db.setTransactionSuccessful();
                return true;
            }

            java.util.LinkedHashMap<String, Target> recipients = new java.util.LinkedHashMap<>();
            cursor = db.rawQuery("SELECT peer_id,endpoint_ip,endpoint_port FROM lan_peers "
                    + "ORDER BY last_seen_at DESC", null);
            while (cursor.moveToNext()) {
                String peerId = cursor.getString(0);
                if (peerId == null || peerId.isEmpty() || senderPeerId.equals(peerId)) continue;
                if (!LanP2pProtocol.isValidPeerId(peerId)) return false;
                recipients.put(peerId, new Target(peerId, "pending", 0,
                        cursor.getString(1), cursor.getInt(2)));
            }
            cursor.close();
            cursor = null;
            if (verifiedRoutes != null) {
                for (java.util.Map.Entry<String, java.net.InetSocketAddress> entry : verifiedRoutes.entrySet()) {
                    String peerId = entry.getKey();
                    java.net.InetSocketAddress endpoint = entry.getValue();
                    if (peerId == null || peerId.isEmpty() || senderPeerId.equals(peerId)
                            || endpoint == null || endpoint.getAddress() == null) continue;
                    if (!LanP2pProtocol.isValidPeerId(peerId)) return false;
                    int port = endpoint.getPort();
                    if (!(port == 8888 || port == 8887 || port == 8889 || port == 8890)) continue;
                    recipients.put(peerId, new Target(peerId, "pending", 0,
                            endpoint.getAddress().getHostAddress(), port));
                }
            }

            int inserted = 0;
            for (Target target : recipients.values()) {
                // Freeze the peer identity independently from current route availability.
                // An offline known peer without an endpoint remains a target and must be
                // rediscovered/authenticated before a future unicast attempt.
                String endpoint = "";
                if (target.endpointIp != null && !target.endpointIp.isEmpty()
                        && (target.endpointPort == 8888 || target.endpointPort == 8887
                            || target.endpointPort == 8889 || target.endpointPort == 8890)) {
                    endpoint = target.endpointIp + ":" + target.endpointPort;
                }
                ContentValues row = new ContentValues();
                row.put("message_id", messageId);
                row.put("peer_id", target.peerId);
                row.put("status", "pending");
                row.put("last_endpoint", endpoint);
                long rowId = db.insertWithOnConflict("lan_outbox_targets", null, row,
                        SQLiteDatabase.CONFLICT_IGNORE);
                if (rowId < 0 && !rowExists(db, "lan_outbox_targets",
                        "message_id=? AND peer_id=?", new String[]{messageId, target.peerId})) return false;
                inserted++;
            }
            if (inserted > 0) {
                ContentValues next = new ContentValues();
                next.put("state", "pending");
                if (db.update("lan_outbox", next, "message_id=?",
                        new String[]{messageId}) != 1) return false;
            }
            // No known endpoint: keep discovery_pending with no deadline until the first
            // actual broadcast is dispatched by the transport path.
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
            db.endTransaction();
        }
    }

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
        for (Target target : targets) {
            if (target != null && !LanP2pProtocol.isValidPeerId(target.peerId)) return false;
        }
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
                    || (discoveryDeadline > 0 && now >= discoveryDeadline)) {
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

    /** Counts only durable target rows; it does not infer delivery from a transient ACK latch. */
    public AckProgress getTargetAckProgress(String messageId) {
        if (messageId == null || messageId.length() == 0) return null;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*),SUM(CASE WHEN status='acked' THEN 1 ELSE 0 END) " +
                    "FROM lan_outbox_targets WHERE message_id=?",
                    new String[]{messageId});
            if (!c.moveToFirst()) return new AckProgress(0, 0);
            int total = c.getInt(0);
            int acked = c.isNull(1) ? 0 : c.getInt(1);
            return new AckProgress(total, acked);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Read recent outgoing messages with their durable per-recipient ACK totals.
     * This is a presentation projection only; the SQLite outbox remains authoritative.
     * Query failures propagate so the UI never mistakes a storage error for an empty queue.
     */
    public List<DeliverySummary> loadRecentDeliverySummaries(int limit) {
        ArrayList<DeliverySummary> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT o.message_id,o.body,o.created_at_ms,o.state,COUNT(t.peer_id)," +
                    "COALESCE(SUM(CASE WHEN t.status='acked' THEN 1 ELSE 0 END),0) " +
                    "FROM lan_outbox o LEFT JOIN lan_outbox_targets t ON t.message_id=o.message_id " +
                    "GROUP BY o.message_id,o.body,o.created_at_ms,o.state " +
                    "ORDER BY o.created_at_ms DESC LIMIT ?",
                    new String[]{String.valueOf(Math.max(1, Math.min(20, limit)))});
            while (c.moveToNext()) {
                result.add(new DeliverySummary(c.getString(0), c.getString(1), c.getLong(2),
                        c.getString(3), c.getInt(4), c.getInt(5)));
            }
            return result;
        } finally {
            if (c != null) c.close();
        }
    }

    public boolean allTargetsAcked(String messageId) {
        if (messageId == null || messageId.length() == 0) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
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
            // Expired is terminal: reconciliation must never resurrect it as delivered.
            if (LanChatDeliveryState.EXPIRED.equals(state)) return false;
            long deadline = c.getLong(1);
            int total = c.getInt(2);
            int acked = c.isNull(3) ? 0 : c.getInt(3);
            long now = System.currentTimeMillis();
            boolean discoveryOpen = LanChatDeliveryState.DISCOVERY_PENDING.equals(state)
                    && deadline > now;
            if (discoveryOpen || total == 0) return false;

            if (total != acked) {
                if (LanChatDeliveryState.DISCOVERY_PENDING.equals(state)) {
                    boolean hasRetryBudget = rowExists(db, "lan_outbox_targets",
                            "message_id=? AND status!='acked' AND attempt_count<?",
                            new String[]{messageId, String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS)});
                    ContentValues next = new ContentValues();
                    next.put("state", LanChatDeliveryState.settleAfterAck(total, acked, hasRetryBudget));
                    if (db.update("lan_outbox", next, "message_id=?",
                            new String[]{messageId}) != 1) return false;
                    db.setTransactionSuccessful();
                }
                return false;
            }

            if (!LanChatDeliveryState.DELIVERED.equals(state)) {
                ContentValues next = new ContentValues();
                next.put("state", LanChatDeliveryState.DELIVERED);
                if (db.update("lan_outbox", next, "message_id=?",
                        new String[]{messageId}) != 1) return false;
            }
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
            db.endTransaction();
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

    /**
     * Settle expired discovery windows during listener recovery as well as normal sends.
     * A service restart cancels scheduled close tasks, so relying on those callbacks alone
     * can strand an exhausted empty-recipient row in discovery_pending forever.
     */
    /** True when a fixed recipient has never had an actual UDP send attempt. */
    public boolean hasUnattemptedTargets(String messageId) {
        if (messageId == null || messageId.length() == 0) return false;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM lan_outbox_targets t INNER JOIN lan_outbox o " +
                    "ON o.message_id=t.message_id WHERE t.message_id=? AND t.status!='acked' " +
                    "AND t.attempt_count=0 AND o.state NOT IN " +
                    "('delivered','expired','waiting_peers','retry_paused') LIMIT 1",
                    new String[]{messageId});
            return c.moveToFirst();
        } finally {
            if (c != null) c.close();
        }
    }

    public int finalizeExpiredDiscoveryWindows(long now) {
        return finalizeExpiredDiscoveryWindowIds(now).size();
    }

    /**
     * Atomically settle all elapsed discovery windows and return only the message IDs
     * transitioned by this transaction, allowing callers to reconcile compatibility
     * mirrors without rescanning or waking the entire Outbox.
     */
    public List<String> finalizeExpiredDiscoveryWindowIds(long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT message_id FROM lan_outbox WHERE state='discovery_pending' " +
                    "AND delivery_deadline_ms>0 AND delivery_deadline_ms<=?",
                    new String[]{String.valueOf(now)});
            ArrayList<String> expired = new ArrayList<>();
            while (c.moveToNext()) expired.add(c.getString(0));
            c.close();
            c = null;
            ArrayList<String> finalized = new ArrayList<>();
            for (String messageId : expired) {
                if (!finalizeDiscoveryMessage(db, messageId)) {
                    throw new IllegalStateException("Unable to settle expired discovery row");
                }
                finalized.add(messageId);
            }
            db.setTransactionSuccessful();
            return finalized;
        } finally {
            if (c != null) c.close();
            db.endTransaction();
        }
    }


    /** Earliest active discovery deadline, or -1 when no window is currently open. */
    public long getNextDiscoveryDeadlineMs() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT MIN(delivery_deadline_ms) FROM lan_outbox " +
                    "WHERE state='discovery_pending' AND delivery_deadline_ms>0", null);
            if (!c.moveToFirst() || c.isNull(0)) return -1L;
            return c.getLong(0);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read next LAN discovery deadline", e);
        } finally {
            if (c != null) c.close();
        }
    }

    public List<PendingMessage> loadPending(int limit) {
        ArrayList<PendingMessage> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT o.message_id,o.sender_peer_id,o.body,o.created_at_ms,o.state," +
                    "COALESCE((SELECT MIN(t.attempt_count) FROM lan_outbox_targets t " +
                    "WHERE t.message_id=o.message_id AND t.status!='acked' " +
                    "AND t.attempt_count>0 AND t.attempt_count<?),o.retry_attempt_count) " +
                    "FROM lan_outbox o WHERE o.state NOT IN ('delivered','expired','waiting_peers','retry_paused') " +
                    "AND ((EXISTS (SELECT 1 FROM lan_outbox_targets t WHERE t.message_id=o.message_id " +
                    "AND t.status!='acked' AND t.attempt_count<?)) " +
                    "OR (NOT EXISTS (SELECT 1 FROM lan_outbox_targets t WHERE t.message_id=o.message_id) " +
                    "AND o.retry_attempt_count<?)) " +
                    "ORDER BY 6,o.created_at_ms LIMIT ?",
                    new String[]{String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
                            String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
                            String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
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
            int resetTargets = db.update("lan_outbox_targets",
                    valuesForRecoveryReset(now),
                    "status!='acked' AND attempt_count>=? AND last_recovery_at_ms<=? " +
                    "AND EXISTS (SELECT 1 FROM lan_outbox o WHERE o.message_id=lan_outbox_targets.message_id " +
                    "AND o.state NOT IN ('delivered','expired','waiting_peers'))",
                    new String[]{String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
                            String.valueOf(now - RETRY_RECOVERY_EVENT_COOLDOWN_MS)});
            ContentValues reopen = new ContentValues();
            reopen.put("state", "pending");
            reopen.put("retry_attempt_count", 0);
            reopen.put("last_event_retry_at_ms", now);
            int reopenedTargets = db.update("lan_outbox", reopen,
                    "state='retry_paused' AND EXISTS (SELECT 1 FROM lan_outbox_targets t " +
                    "WHERE t.message_id=lan_outbox.message_id AND t.status!='acked' " +
                    "AND t.attempt_count<?)",
                    new String[]{String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS)});
            ContentValues reopenDiscovery = new ContentValues();
            reopenDiscovery.put("state", "pending");
            reopenDiscovery.put("retry_attempt_count", 0);
            reopenDiscovery.put("last_event_retry_at_ms", now);
            int reopenedDiscovery = db.update("lan_outbox", reopenDiscovery,
                    "retry_attempt_count>=? AND state NOT IN ('delivered','expired','waiting_peers','discovery_pending') " +
                    "AND last_event_retry_at_ms<=? AND NOT EXISTS (SELECT 1 FROM lan_outbox_targets t " +
                    "WHERE t.message_id=lan_outbox.message_id)",
                    new String[]{String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
                            String.valueOf(now - RETRY_RECOVERY_EVENT_COOLDOWN_MS)});
            db.setTransactionSuccessful();
            return resetTargets + reopenedTargets + reopenedDiscovery;
        } catch (Exception e) {
            // Zero means a successful transaction that found nothing eligible. Storage failure
            // must remain distinguishable so the caller can retain the recovery wake and retry.
            throw new IllegalStateException("Failed to resume LAN Outbox retry epoch", e);
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Reopen only exhausted targets belonging to one authenticated, recovered peer.
     * This deliberately does not reopen discovery-only messages or any other peer's budget.
     */
    public int resumeExhaustedRetriesForPeerRecoveryEvent(String peerId, long now) {
        if (peerId == null || peerId.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int resetTargets = db.update("lan_outbox_targets",
                    valuesForRecoveryReset(now),
                    "peer_id=? AND status!='acked' AND attempt_count>=? AND last_recovery_at_ms<=? " +
                    "AND EXISTS (SELECT 1 FROM lan_outbox o WHERE o.message_id=lan_outbox_targets.message_id " +
                    "AND o.state NOT IN ('delivered','expired','waiting_peers'))",
                    new String[]{peerId, String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
                            String.valueOf(now - RETRY_RECOVERY_EVENT_COOLDOWN_MS)});
            ContentValues reopen = new ContentValues();
            reopen.put("state", "pending");
            reopen.put("retry_attempt_count", 0);
            reopen.put("last_event_retry_at_ms", now);
            int reopenedMessages = db.update("lan_outbox", reopen,
                    "state='retry_paused' AND EXISTS (SELECT 1 FROM lan_outbox_targets t " +
                    "WHERE t.message_id=lan_outbox.message_id AND t.peer_id=? " +
                    "AND t.status!='acked' AND t.attempt_count<?)",
                    new String[]{peerId, String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS)});
            db.setTransactionSuccessful();
            return resetTargets + reopenedMessages;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resume peer LAN Outbox retry epoch", e);
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Return the next cooldown deadline for exhausted targets of one peer only.
     * Long.MAX_VALUE means no peer-scoped recovery is currently deferred.
     */
    public long nextPeerRecoveryEventEligibleAtMs(String peerId, long now) {
        if (peerId == null || peerId.isEmpty()) return Long.MAX_VALUE;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT MIN(t.last_recovery_at_ms + ?) FROM lan_outbox_targets t " +
                    "JOIN lan_outbox o ON o.message_id=t.message_id " +
                    "WHERE t.peer_id=? AND t.status!='acked' AND t.attempt_count>=? " +
                    "AND t.last_recovery_at_ms>? " +
                    "AND o.state NOT IN ('delivered','expired','waiting_peers')",
                    new String[]{String.valueOf(RETRY_RECOVERY_EVENT_COOLDOWN_MS), peerId,
                            String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
                            String.valueOf(now - RETRY_RECOVERY_EVENT_COOLDOWN_MS)});
            if (!c.moveToFirst() || c.isNull(0)) return Long.MAX_VALUE;
            return c.getLong(0);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read peer LAN recovery cooldown deadline", e);
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Return the earliest persisted cooldown deadline for exhausted, deliverable rows.
     * Long.MAX_VALUE means no deferred recovery event is currently needed.
     */
    public long nextRecoveryEventEligibleAtMs(long now) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT MIN(eligible_at_ms) FROM (" +
                    "SELECT t.last_recovery_at_ms + ? AS eligible_at_ms " +
                    "FROM lan_outbox_targets t JOIN lan_outbox o ON o.message_id=t.message_id " +
                    "WHERE t.status!='acked' AND t.attempt_count>=? AND t.last_recovery_at_ms>? " +
                    "AND o.state NOT IN ('delivered','expired','waiting_peers') " +
                    "UNION ALL " +
                    "SELECT o.last_event_retry_at_ms + ? AS eligible_at_ms FROM lan_outbox o " +
                    "WHERE o.retry_attempt_count>=? AND o.last_event_retry_at_ms>? " +
                    "AND o.state NOT IN ('delivered','expired','waiting_peers','discovery_pending') " +
                    "AND NOT EXISTS (SELECT 1 FROM lan_outbox_targets t WHERE t.message_id=o.message_id)" +
                    ")",
                    new String[]{String.valueOf(RETRY_RECOVERY_EVENT_COOLDOWN_MS),
                            String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
                            String.valueOf(now - RETRY_RECOVERY_EVENT_COOLDOWN_MS),
                            String.valueOf(RETRY_RECOVERY_EVENT_COOLDOWN_MS),
                            String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
                            String.valueOf(now - RETRY_RECOVERY_EVENT_COOLDOWN_MS)});
            if (!c.moveToFirst() || c.isNull(0)) return Long.MAX_VALUE;
            return c.getLong(0);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read next LAN recovery cooldown deadline", e);
        } finally {
            if (c != null) c.close();
        }
    }

    private ContentValues valuesForRecoveryReset(long now) {
        ContentValues values = new ContentValues();
        values.put("attempt_count", 0);
        values.put("last_attempt_at", 0L);
        values.put("last_error", "");
        values.put("last_recovery_at_ms", now);
        return values;
    }

    /** Persist the discovery-only attempt before UDP; fixed recipients use independent target budgets. */
    /**
     * Returns the current retry epoch for fixed recipients without taking SQLite's writer
     * transaction. Only legacy/open-discovery rows with no recipient snapshot need the
     * atomic outbox counter update below.
     */
    private int[] readOutboxTargetAttemptProgress(SQLiteDatabase db, String messageId) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*),SUM(CASE WHEN status!='acked' AND attempt_count<? " +
                    "THEN 1 ELSE 0 END),MIN(CASE WHEN status!='acked' AND attempt_count<? " +
                    "THEN attempt_count END) FROM lan_outbox_targets WHERE message_id=?",
                    new String[]{String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS),
                            String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS), messageId});
            if (!c.moveToFirst()) return new int[]{0, 0, 0};
            return new int[]{c.getInt(0), c.isNull(1) ? 0 : c.getInt(1),
                    c.isNull(2) ? 0 : c.getInt(2)};
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Read-only admission check for an already-frozen recipient snapshot.
     * Fixed-target sends have no message-level retry counter to mutate; their durable
     * attempt budget lives on each target and is rechecked transactionally by markAttempt().
     * Keeping this aggregate read outside the global UDP sender lock avoids a SQLite query
     * delaying the next interactive message.
     */
    public int readFixedTargetAttemptProgress(String messageId) {
        if (messageId == null || messageId.length() == 0) return -1;
        int[] progress = readOutboxTargetAttemptProgress(getReadableDatabase(), messageId);
        if (progress[0] <= 0 || progress[1] <= 0) return -1;
        return progress[2];
    }

    public int recordOutboxAttempt(String messageId) {
        if (messageId == null || messageId.length() == 0) return -1;

        // A recipient snapshot may have been committed after the caller's pre-lock
        // empty-snapshot read. Recheck before charging the message-level discovery counter.
        int[] fastProgress = readOutboxTargetAttemptProgress(getReadableDatabase(), messageId);
        if (fastProgress[0] > 0) {
            return fastProgress[1] == 0 ? -1 : fastProgress[2];
        }

        // No target snapshot exists yet. Recheck under the write transaction so a concurrent
        // discovery ACK/peer admission cannot race the legacy outbox retry-counter update.
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor c = null;
        try {
            int[] lockedProgress = readOutboxTargetAttemptProgress(db, messageId);
            int targetCount = lockedProgress[0];
            int remainingTargets = lockedProgress[1];
            int minimumAttempts = lockedProgress[2];
            if (targetCount > 0) {
                db.setTransactionSuccessful();
                return remainingTargets == 0 ? -1 : minimumAttempts;
            }

            long now = System.currentTimeMillis();
            android.database.sqlite.SQLiteStatement update = db.compileStatement(
                    "UPDATE lan_outbox SET retry_attempt_count=retry_attempt_count+1,last_retry_at_ms=?," +
                    "state=CASE WHEN retry_attempt_count+1>=? AND state!='discovery_pending' THEN 'retry_paused' ELSE state END " +
                    "WHERE message_id=? AND state NOT IN ('delivered','expired','waiting_peers','retry_paused') " +
                    "AND retry_attempt_count<? AND NOT EXISTS (SELECT 1 FROM lan_outbox_targets t " +
                    "WHERE t.message_id=lan_outbox.message_id)");
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

    /** Atomically enforce the per-recipient attempt cap and persisted exponential backoff. */
    public boolean markAttempt(String messageId, String peerId, String endpoint, String error) {
        if (messageId == null || peerId == null || endpoint == null || endpoint.length() == 0) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            // The first three LAN dispatches form a short reliability burst:
            // attempts 2 and 3 may follow after 250ms; later attempts use durable backoff.
            android.database.sqlite.SQLiteStatement update = db.compileStatement(
                    "UPDATE lan_outbox_targets SET status='" + LanChatDeliveryState.TARGET_ATTEMPT_RESERVED + "',attempt_count=attempt_count+1," +
                    "last_attempt_endpoint=?,last_attempt_at=?,last_error=? WHERE message_id=? AND peer_id=? AND status!='acked' " +
                    "AND attempt_count<? AND (attempt_count=0 OR last_attempt_endpoint<>? " +
                    "OR last_attempt_at + " + LanChatRetryPolicy.TARGET_RETRY_DELAY_CASE_SQL + " <= ?) " +
                    "AND EXISTS (SELECT 1 FROM lan_outbox o WHERE o.message_id=lan_outbox_targets.message_id " +
                    "AND o.state NOT IN ('delivered','expired','waiting_peers'))");
            int changed;
            try {
                update.bindString(1, endpoint);
                update.bindLong(2, now);
                update.bindString(3, error == null ? "" : error);
                update.bindString(4, messageId);
                update.bindString(5, peerId);
                update.bindLong(6, MAX_AUTOMATIC_RETRY_ATTEMPTS);
                update.bindString(7, endpoint);
                update.bindLong(8, now);
                changed = update.executeUpdateDelete();
            } finally { update.close(); }
            if (changed != 1) return false;
            ContentValues pause = new ContentValues();
            pause.put("state", "retry_paused");
            db.update("lan_outbox", pause,
                    "message_id=? AND state NOT IN ('delivered','expired','waiting_peers','discovery_pending') " +
                    "AND EXISTS (SELECT 1 FROM lan_outbox_targets t WHERE t.message_id=lan_outbox.message_id " +
                    "AND t.status!='acked') " +
                    "AND NOT EXISTS (SELECT 1 FROM lan_outbox_targets t WHERE t.message_id=lan_outbox.message_id " +
                    "AND t.status!='acked' AND t.attempt_count<?)",
                    new String[]{messageId, String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS)});
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /** Read the number of discovery-broadcast dispatch epochs already charged to this message. */
    public int getOutboxRetryAttemptCount(String messageId) {
        if (messageId == null || messageId.isEmpty()) return -1;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT retry_attempt_count FROM lan_outbox WHERE message_id=?",
                    new String[]{messageId});
            return c.moveToFirst() ? c.getInt(0) : -1;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read LAN outbox retry attempt count", e);
        } finally {
            if (c != null) c.close();
        }
    }

    /** Record a failed attempt without incrementing the already-recorded attempt counter. */
    public void recordTargetSendError(String messageId, String peerId, String error) {
        if (messageId == null || peerId == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put("last_error", error == null ? "" : error);
            getWritableDatabase().update("lan_outbox_targets", values,
                    "message_id=? AND peer_id=? AND status!='acked'",
                    new String[]{messageId, peerId});
        } catch (Exception ignored) {
        }
    }

    /** Persist the endpoint only after DatagramSocket.send() returned successfully. */
    public boolean recordSuccessfulSendEndpoint(String messageId, String peerId, String endpoint) {
        if (messageId == null || peerId == null || endpoint == null || endpoint.length() == 0) return false;
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("last_endpoint", endpoint);
            values.put("last_sent_endpoint", endpoint);
            values.put("last_error", "");
            // The send may have succeeded and its authenticated ACK may already have
            // committed status='acked' on the control executor. Preserve the real endpoint
            // evidence even in that race; this method is called only after socket.send returns.
            return db.update("lan_outbox_targets", values,
                    "message_id=? AND peer_id=?",
                    new String[]{messageId, peerId}) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Accept a delayed ACK only from the endpoint durably recorded after this target's UDP send succeeded.
     * Historical last_endpoint remains a route-challenge candidate, never ACK evidence by itself.
     * This remains valid after the short-lived in-memory route cache is evicted.
     */
    public boolean matchesExpectedAckEndpoint(String messageId, String peerId,
                                              String endpointIp, int endpointPort) {
        if (messageId == null || peerId == null || endpointIp == null || endpointIp.length() == 0
                || endpointPort <= 0 || endpointPort > 65535) return false;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM lan_outbox_targets WHERE message_id=? AND peer_id=? " +
                            "AND last_sent_endpoint=? LIMIT 1",
                    new String[]{messageId, peerId, endpointIp + ":" + endpointPort});
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Detect an endpoint that has only been reserved for an in-progress UDP attempt.
     * This is never ACK evidence; it only permits one delayed recheck for last_sent_endpoint
     * after socket.send() and its success bookkeeping have had a chance to commit.
     */
    public boolean matchesAttemptedAckEndpoint(String messageId, String peerId,
                                                String endpointIp, int endpointPort) {
        if (messageId == null || messageId.length() == 0 || peerId == null || peerId.length() == 0
                || endpointIp == null || endpointIp.length() == 0
                || endpointPort <= 0 || endpointPort > 65535) return false;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM lan_outbox_targets WHERE message_id=? AND peer_id=? " +
                            "AND last_attempt_endpoint=? AND last_sent_endpoint!=? LIMIT 1",
                    new String[]{messageId, peerId, endpointIp + ":" + endpointPort,
                            endpointIp + ":" + endpointPort});
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Freeze the fragment count for one already-snapshotted recipient and initialize its
     * durable selective-ACK bitmap. Re-entry is idempotent; a different count is rejected.
     */
    /** Stable creation timestamp shared by every retry of one logical message. */
    public long getMessageCreatedAtMs(String messageId) {
        if (messageId == null || messageId.isEmpty()) return -1L;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT created_at_ms FROM lan_outbox WHERE message_id=?",
                    new String[]{messageId});
            return c.moveToFirst() ? c.getLong(0) : -1L;
        } catch (Exception failure) {
            return -1L;
        } finally {
            if (c != null) c.close();
        }
    }

    public boolean initializeTargetFragmentProgress(String messageId, String peerId, int fragmentCount) {
        if (messageId == null || messageId.isEmpty() || peerId == null || peerId.isEmpty()
                || fragmentCount < 1 || fragmentCount > 1024) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery("SELECT fragment_count,fragment_ack_bitmap,status " +
                    "FROM lan_outbox_targets WHERE message_id=? AND peer_id=?",
                    new String[]{messageId, peerId});
            int existingCount;
            String bitmap;
            String status;
            try {
                if (!c.moveToFirst()) return false;
                existingCount = c.getInt(0);
                bitmap = c.getString(1);
                status = c.getString(2);
            } finally { c.close(); }
            if ("acked".equals(status)) {
                db.setTransactionSuccessful();
                return true;
            }
            if (existingCount != 0 && existingCount != fragmentCount) return false;
            String initial = bitmap == null ? "" : bitmap;
            if (existingCount == 0) {
                initial = repeatZeroes(fragmentCount);
            } else if (initial.length() != fragmentCount) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put("fragment_count", fragmentCount);
            values.put("fragment_ack_bitmap", initial);
            boolean updated = db.update("lan_outbox_targets", values,
                    "message_id=? AND peer_id=? AND status!='acked'",
                    new String[]{messageId, peerId}) == 1;
            if (!updated) return false;
            db.setTransactionSuccessful();
            return true;
        } catch (Exception failure) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /** Persist one authenticated fragment ACK; duplicate ACKs are idempotent. */
    public boolean acknowledgeTargetFragment(String messageId, String peerId, int fragmentIndex) {
        if (messageId == null || messageId.isEmpty() || peerId == null || peerId.isEmpty()
                || fragmentIndex < 0 || fragmentIndex >= 1024) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery("SELECT fragment_count,fragment_ack_bitmap FROM lan_outbox_targets " +
                    "WHERE message_id=? AND peer_id=? AND status!='acked'",
                    new String[]{messageId, peerId});
            int count;
            String bitmap;
            try {
                if (!c.moveToFirst()) return false;
                count = c.getInt(0);
                bitmap = c.getString(1);
            } finally { c.close(); }
            if (count < 1 || count > 1024 || fragmentIndex >= count
                    || bitmap == null || bitmap.length() != count) return false;
            if (bitmap.charAt(fragmentIndex) == '1') {
                db.setTransactionSuccessful();
                return true;
            }
            if (bitmap.charAt(fragmentIndex) != '0') return false;
            String updatedBitmap = bitmap.substring(0, fragmentIndex) + "1"
                    + bitmap.substring(fragmentIndex + 1);
            ContentValues values = new ContentValues();
            values.put("fragment_ack_bitmap", updatedBitmap);
            if (db.update("lan_outbox_targets", values,
                    "message_id=? AND peer_id=? AND status!='acked'",
                    new String[]{messageId, peerId}) != 1) return false;
            db.setTransactionSuccessful();
            return true;
        } catch (Exception failure) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /** Return the durable per-target ACK bitmap, or null if no fragment session exists. */
    public String loadTargetFragmentAckBitmap(String messageId, String peerId) {
        if (messageId == null || messageId.isEmpty() || peerId == null || peerId.isEmpty()) return null;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT fragment_count,fragment_ack_bitmap " +
                    "FROM lan_outbox_targets WHERE message_id=? AND peer_id=?",
                    new String[]{messageId, peerId});
            if (!c.moveToFirst()) return null;
            int count = c.getInt(0);
            String bitmap = c.getString(1);
            return count >= 1 && count <= 1024 && bitmap != null && bitmap.length() == count
                    ? bitmap : null;
        } catch (Exception failure) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    private static String repeatZeroes(int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, '0');
        return new String(values);
    }

    public boolean acknowledge(String messageId, String peerId) {
        // Empty identities must never become synthetic recipients during discovery ACK admission.
        if (messageId == null || messageId.length() == 0
                || peerId == null || peerId.length() == 0) return false;
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
            if (state.isEmpty() || "expired".equals(state)) return false;

            ContentValues ackValues = new ContentValues();
            ackValues.put("status", LanChatDeliveryState.TARGET_ACKED);
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
                            || deadline <= 0L || now >= deadline) return false;
                    // An authenticated ACK can join only a window opened by actual UDP dispatch.
                    ContentValues targetValues = new ContentValues();
                    targetValues.put("message_id", messageId);
                    targetValues.put("peer_id", peerId);
                    targetValues.put("status", LanChatDeliveryState.TARGET_ACKED);
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
                    && deadline > 0 && now < deadline;
            if (!discoveryOpen) {
                ContentValues next = new ContentValues();
                boolean hasRetryBudget = rowExists(db, "lan_outbox_targets",
                        "message_id=? AND status!='acked' AND attempt_count<?",
                        new String[]{messageId, String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS)});
                next.put("state", total > 0 && total == acked ? "delivered"
                        : (hasRetryBudget ? "pending" : "retry_paused"));
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
    /**
     * Atomically starts or observes the first-recipient window.
     * Returns ERROR on storage failure/missing rows, CLOSED for a non-discovery terminal state,
     * OPEN for an existing live window, STARTED for the first dispatch, and WAITING when the
     * empty discovery snapshot has been durably settled. One transaction owns the state decision,
     * so callers do not need a second query that can race an ACK or deadline transition.
     */
    public static final int DISCOVERY_WINDOW_ERROR = -1;
    public static final int DISCOVERY_WINDOW_CLOSED = 0;
    public static final int DISCOVERY_WINDOW_OPEN = 1;
    public static final int DISCOVERY_WINDOW_STARTED = 2;
    public static final int DISCOVERY_WINDOW_WAITING = 3;

    public int beginOrReadDiscoveryWindow(String messageId) {
        if (messageId == null || messageId.length() == 0) return DISCOVERY_WINDOW_ERROR;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
                    new String[]{messageId});
            if (!c.moveToFirst()) return DISCOVERY_WINDOW_ERROR;
            String state = c.getString(0);
            long deadline = c.getLong(1);
            c.close();
            c = null;

            if ("waiting_peers".equals(state)) {
                db.setTransactionSuccessful();
                return DISCOVERY_WINDOW_WAITING;
            }
            if (!"discovery_pending".equals(state)) {
                db.setTransactionSuccessful();
                return DISCOVERY_WINDOW_CLOSED;
            }

            long now = System.currentTimeMillis();
            if (deadline > 0L) {
                if (now < deadline) {
                    db.setTransactionSuccessful();
                    return DISCOVERY_WINDOW_OPEN;
                }
                // Settle an expired empty snapshot in this same transaction. The state
                // read below is intentionally in-transaction, not a second caller query.
                if (!finalizeDiscoveryMessage(db, messageId)) return DISCOVERY_WINDOW_ERROR;
                c = db.rawQuery("SELECT state FROM lan_outbox WHERE message_id=?",
                        new String[]{messageId});
                String settledState = null;
                if (c.moveToFirst()) settledState = c.getString(0);
                c.close();
                c = null;
                db.setTransactionSuccessful();
                return "waiting_peers".equals(settledState)
                        ? DISCOVERY_WINDOW_WAITING : DISCOVERY_WINDOW_CLOSED;
            }

            ContentValues window = new ContentValues();
            window.put("delivery_deadline_ms", now + FIRST_DISCOVERY_WINDOW_MS);
            int changed = db.update("lan_outbox", window,
                    "message_id=? AND state='discovery_pending' AND delivery_deadline_ms<=0",
                    new String[]{messageId});
            if (changed != 1) return DISCOVERY_WINDOW_ERROR;
            db.setTransactionSuccessful();
            return DISCOVERY_WINDOW_STARTED;
        } catch (Exception e) {
            return DISCOVERY_WINDOW_ERROR;
        } finally {
            if (c != null) c.close();
            db.endTransaction();
        }
    }

    /** Compatibility wrapper for callers that only need to know whether this call opened it. */
    public boolean beginDiscoveryWindow(String messageId) {
        return beginOrReadDiscoveryWindow(messageId) == DISCOVERY_WINDOW_STARTED;
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

    /** True when SQLite has a terminal delivery/discovery result and the legacy mirror can be reclaimed. */
    public boolean isTerminalForLegacyMirror(String messageId) {
        if (messageId == null || messageId.length() == 0) return false;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM lan_outbox WHERE message_id=? " +
                            "AND state IN ('delivered','waiting_peers')",
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
                    "AND (delivery_deadline_ms<=0 OR delivery_deadline_ms>?)",
                    new String[]{messageId, String.valueOf(System.currentTimeMillis())});
            return c.moveToFirst();
        } catch (Exception e) {
            // A storage failure is not evidence that the discovery window is closed.
            // Propagate it so callers can stop the operation without widening recipients.
            throw new IllegalStateException("Failed to read LAN discovery state", e);
        } finally {
            if (c != null) c.close();
        }
    }

    /** Add a newly discovered peer to all open first-discovery snapshots. */
    /**
     * Add a newly verified peer to every still-open first-discovery snapshot.
     * The caller may supply an existing transaction so route activation and target
     * admission become one atomic commit instead of two competing SQLite writer turns.
     */
    private boolean addDiscoveryPeerToOpenMessages(SQLiteDatabase db, String peerId, long now) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT message_id,delivery_deadline_ms FROM lan_outbox WHERE state='discovery_pending'",
                    null);
            while (c.moveToNext()) {
                String messageId = c.getString(0);
                long deadline = c.getLong(1);
                if (deadline <= 0L) {
                    // A verified peer may appear while the message is still queued.
                    // Discovery membership starts only after the first actual UDP dispatch.
                    continue;
                }
                if (now >= deadline) {
                    if (!finalizeDiscoveryMessage(db, messageId)) return false;
                    continue;
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
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    public boolean addDiscoveryPeerToOpenMessages(String peerId) {
        if (peerId == null || peerId.length() == 0) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!addDiscoveryPeerToOpenMessages(db, peerId, System.currentTimeMillis())) return false;
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
        boolean hasRetryBudget = rowExists(db, "lan_outbox_targets",
                "message_id=? AND status!='acked' AND attempt_count<?",
                new String[]{messageId, String.valueOf(MAX_AUTOMATIC_RETRY_ATTEMPTS)});
        next.put("state", LanChatDeliveryState.settleAfterDiscovery(total, acked, hasRetryBudget));
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
     * Persist one already-authenticated fragment. The return value is deliberately
     * transport-local: stored, duplicate, complete, or rejected. It is NOT a chat.ack.
     * Callers must still reassemble, verify the whole-message digest and commit inbox
     * before sending the final stored ACK.
     */
    public String storeIncomingFragment(String receiverScope, String senderPeerId,
            String messageId, int fragmentIndex, int fragmentCount, int totalBytes,
            String sha256, long createdAtMs, String base64Data, long nowMs) {
        if (receiverScope == null || receiverScope.isEmpty()
                || !LanP2pProtocol.isValidPeerId(senderPeerId)
                || messageId == null || messageId.isEmpty()
                || messageId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128
                || fragmentCount < 1 || fragmentCount > LanP2pFragments.MAX_FRAGMENTS
                || totalBytes < 1 || totalBytes > LanP2pFragments.MAX_MESSAGE_BYTES
                || fragmentCount != (totalBytes + LanP2pFragments.RAW_FRAGMENT_BYTES - 1)
                        / LanP2pFragments.RAW_FRAGMENT_BYTES
                || fragmentIndex < 0 || fragmentIndex >= fragmentCount
                || createdAtMs < 0 || nowMs < 0
                || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                || base64Data == null || base64Data.length() > 400) return "rejected";
        final byte[] raw;
        try {
            raw = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP);
            if (!android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP).equals(base64Data)) {
                return "rejected";
            }
            int expected = fragmentIndex < fragmentCount - 1
                    ? LanP2pFragments.RAW_FRAGMENT_BYTES
                    : totalBytes - LanP2pFragments.RAW_FRAGMENT_BYTES * (fragmentCount - 1);
            if (raw.length != expected) return "rejected";
        } catch (IllegalArgumentException malformed) {
            return "rejected";
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor cursor = null;
        try {
            db.delete("lan_chat_fragment_sessions", "expires_at_ms<=?",
                    new String[]{String.valueOf(nowMs)});
            cursor = db.rawQuery("SELECT fragment_count,total_bytes,sha256,created_at_ms " +
                    "FROM lan_chat_fragment_sessions WHERE receiver_scope=? AND sender_peer_id=? AND message_id=?",
                    new String[]{receiverScope, senderPeerId, messageId});
            boolean exists = cursor.moveToFirst();
            if (exists && (cursor.getInt(0) != fragmentCount || cursor.getInt(1) != totalBytes
                    || !sha256.equals(cursor.getString(2)) || cursor.getLong(3) != createdAtMs)) {
                return "rejected";
            }
            cursor.close();
            cursor = null;

            if (!exists) {
                cursor = db.rawQuery("SELECT COUNT(*) FROM lan_chat_fragment_sessions", null);
                int sessions = 0;
                try { if (cursor.moveToFirst()) sessions = cursor.getInt(0); }
                finally { cursor.close(); cursor = null; }
                if (sessions >= MAX_FRAGMENT_SESSIONS) return "rejected";
                ContentValues session = new ContentValues();
                session.put("receiver_scope", receiverScope);
                session.put("sender_peer_id", senderPeerId);
                session.put("message_id", messageId);
                session.put("fragment_count", fragmentCount);
                session.put("total_bytes", totalBytes);
                session.put("sha256", sha256);
                session.put("created_at_ms", createdAtMs);
                session.put("first_seen_at_ms", nowMs);
                session.put("expires_at_ms", nowMs + FRAGMENT_SESSION_TTL_MS);
                if (db.insertOrThrow("lan_chat_fragment_sessions", null, session) < 0) return "rejected";
            }

            cursor = db.rawQuery("SELECT fragment_data FROM lan_chat_fragments " +
                    "WHERE receiver_scope=? AND sender_peer_id=? AND message_id=? AND fragment_index=?",
                    new String[]{receiverScope, senderPeerId, messageId, String.valueOf(fragmentIndex)});
            if (cursor.moveToFirst()) {
                boolean identical = base64Data.equals(cursor.getString(0));
                cursor.close();
                cursor = null;
                if (!identical) return "rejected";
                db.setTransactionSuccessful();
                return "duplicate";
            }
            cursor.close();
            cursor = null;

            cursor = db.rawQuery("SELECT COALESCE(SUM(LENGTH(fragment_data)),0) " +
                    "FROM lan_chat_fragments", null);
            int cachedChars = 0;
            try { if (cursor.moveToFirst()) cachedChars = cursor.getInt(0); }
            finally { cursor.close(); cursor = null; }
            if (cachedChars + base64Data.length() > MAX_FRAGMENT_CACHE_CHARS) return "rejected";

            ContentValues part = new ContentValues();
            part.put("receiver_scope", receiverScope);
            part.put("sender_peer_id", senderPeerId);
            part.put("message_id", messageId);
            part.put("fragment_index", fragmentIndex);
            part.put("fragment_data", base64Data);
            db.insertOrThrow("lan_chat_fragments", null, part);

            cursor = db.rawQuery("SELECT COUNT(*) FROM lan_chat_fragments " +
                    "WHERE receiver_scope=? AND sender_peer_id=? AND message_id=?",
                    new String[]{receiverScope, senderPeerId, messageId});
            int received = 0;
            try { if (cursor.moveToFirst()) received = cursor.getInt(0); }
            finally { cursor.close(); cursor = null; }
            db.setTransactionSuccessful();
            return received == fragmentCount ? "complete" : "stored";
        } catch (Exception failure) {
            return "rejected";
        } finally {
            if (cursor != null) cursor.close();
            db.endTransaction();
        }
    }

    /** Load one partial session in index order after process restart. */
    public List<IncomingFragment> loadIncomingFragments(String receiverScope,
            String senderPeerId, String messageId, long nowMs) {
        ArrayList<IncomingFragment> result = new ArrayList<>();
        if (receiverScope == null || senderPeerId == null || messageId == null || nowMs < 0) return result;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor cursor = null;
        try {
            db.delete("lan_chat_fragment_sessions", "expires_at_ms<=?",
                    new String[]{String.valueOf(nowMs)});
            cursor = db.rawQuery("SELECT s.fragment_count,s.total_bytes,s.sha256,s.created_at_ms," +
                    "f.fragment_index,f.fragment_data FROM lan_chat_fragment_sessions s " +
                    "JOIN lan_chat_fragments f ON f.receiver_scope=s.receiver_scope " +
                    "AND f.sender_peer_id=s.sender_peer_id AND f.message_id=s.message_id " +
                    "WHERE s.receiver_scope=? AND s.sender_peer_id=? AND s.message_id=? " +
                    "AND s.expires_at_ms>? ORDER BY f.fragment_index",
                    new String[]{receiverScope, senderPeerId, messageId, String.valueOf(nowMs)});
            while (cursor.moveToNext()) {
                result.add(new IncomingFragment(receiverScope, senderPeerId, messageId,
                        cursor.getInt(4), cursor.getInt(0), cursor.getInt(1),
                        cursor.getString(2), cursor.getLong(3), cursor.getString(5)));
            }
            db.setTransactionSuccessful();
        } catch (Exception failure) {
            result.clear();
        } finally {
            if (cursor != null) cursor.close();
            db.endTransaction();
        }
        return result;
    }

    /** Remove partial state only after the caller has durably committed the full inbox row. */
    public boolean deleteIncomingFragmentSession(String receiverScope, String senderPeerId,
            String messageId) {
        if (receiverScope == null || senderPeerId == null || messageId == null) return false;
        try {
            SQLiteDatabase db = getWritableDatabase();
            return db.delete("lan_chat_fragment_sessions",
                    "receiver_scope=? AND sender_peer_id=? AND message_id=?",
                    new String[]{receiverScope, senderPeerId, messageId}) > 0;
        } catch (Exception failure) {
            return false;
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
            long oldLeaseExpiresAt = 0L;
            String oldStatus = "suspect";
            Cursor c = db.rawQuery(
                    "SELECT endpoint_ip,endpoint_port,last_verified_at,status,lease_expires_at FROM lan_peers WHERE peer_id=?",
                    new String[]{peerId});
            try {
                if (c.moveToFirst()) {
                    oldIp = c.getString(0);
                    oldPort = c.getInt(1);
                    oldVerifiedAt = c.getLong(2);
                    oldStatus = c.getString(3);
                    oldLeaseExpiresAt = c.getLong(4);
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
            // HELLO is discovery evidence, not a route challenge. It must never
            // renew a verified lease; only upsertPeer after nonce-bound validation may do so.
            v.put("lease_expires_at", oldLeaseExpiresAt);
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

    /**
     * Read durable inbox rows that still need projection into the legacy UI history.
     * ACK state is independent: an accepted row remains recoverable across process death.
     */
    public List<IncomingMessage> loadUndisplayedIncoming(String receiverScope, int limit) {
        if (receiverScope == null || receiverScope.isEmpty()) return new ArrayList<>();
        ArrayList<IncomingMessage> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT sender_peer_id,message_id,body,display_name,display_time "
                            + "FROM lan_inbox WHERE receiver_scope=? AND displayed=0 "
                            + "ORDER BY received_at_ms,created_at_ms LIMIT ?",
                    new String[]{receiverScope, String.valueOf(Math.max(1, Math.min(200, limit)))});
            while (c.moveToNext()) {
                result.add(new IncomingMessage(c.getString(0), c.getString(1),
                        c.getString(2), c.getString(3), c.getString(4)));
            }
            return result;
        } finally {
            if (c != null) c.close();
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
            // A failed marker read is not evidence that the message has never been displayed.
            // Propagate the storage failure so the receive path withholds ACK instead of
            // appending the same message to legacy history a second time.
            throw new IllegalStateException("Failed to read LAN inbox presentation state", e);
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
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
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
            long rowId = db.insertWithOnConflict("lan_peers", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            if (rowId < 0) {
                Cursor c = db.rawQuery(
                        "SELECT 1 FROM lan_peers WHERE peer_id=? AND endpoint_ip=? AND endpoint_port=? " +
                        "AND status='active' AND last_verified_at=?",
                        new String[]{peerId, ip, String.valueOf(port), String.valueOf(now)});
                boolean persisted;
                try { persisted = c.moveToFirst(); } finally { c.close(); }
                if (!persisted) return false;
            }

            // Refresh only the route candidate for outstanding messages addressed to this
            // stable peer identity. Never rewrite last_attempt_endpoint/last_sent_endpoint:
            // those fields remain message-specific ACK evidence, not reusable route state.
            ContentValues targetRoute = new ContentValues();
            targetRoute.put("last_endpoint", ip + ":" + port);
            db.update("lan_outbox_targets", targetRoute,
                    "peer_id=? AND status!='acked' AND EXISTS (" +
                    "SELECT 1 FROM lan_outbox o WHERE o.message_id=lan_outbox_targets.message_id " +
                    "AND o.state NOT IN ('delivered','expired','waiting_peers'))",
                    new String[]{peerId});
            // Persist the verified route and admit it to open discovery snapshots in the
            // same SQLite writer transaction. This removes a second writer-lock round trip
            // from the peer.ack critical path and prevents an intermediate half-committed state.
            if (!addDiscoveryPeerToOpenMessages(db, peerId, now)) return false;
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public void closeStore() {
        close();
    }
}
