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
    private static final String DB_NAME = "lan_p2p_delivery.db";
    private static final int DB_VERSION = 2;
    public static final int MAX_PENDING_MESSAGES = 50;

    public static final class PendingMessage {
        public final String messageId;
        public final String senderPeerId;
        public final String body;
        public final long createdAtMs;
        public final String state;

        PendingMessage(String id, String sender, String body, long created, String state) {
            this.messageId = id;
            this.senderPeerId = sender;
            this.body = body;
            this.createdAtMs = created;
            this.state = state;
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
                "delivery_deadline_ms INTEGER NOT NULL DEFAULT 0)");
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
                "PRIMARY KEY(receiver_scope, sender_peer_id, message_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lan_inbox_received ON lan_inbox(receiver_scope, received_at_ms)");

        db.execSQL("CREATE TABLE IF NOT EXISTS lan_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Schema changes are additive and versioned. Never drop existing chat or floating-message data.
        if (oldVersion < 1) onCreate(db);
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE lan_inbox ADD COLUMN display_name TEXT NOT NULL DEFAULT ''"); }
            catch (Exception ignored) { /* Column may already exist after an interrupted additive migration. */ }
        }
    }

    /** Persist an outgoing message before any network send. */
    public boolean enqueue(String messageId, String senderPeerId, String body, long createdAtMs) {
        if (messageId == null || messageId.length() == 0 ||
                senderPeerId == null || senderPeerId.length() == 0 || body == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM lan_outbox WHERE state NOT IN ('delivered','expired')", null);
            int count = 0;
            try { if (c.moveToFirst()) count = c.getInt(0); } finally { c.close(); }
            Cursor exists = db.rawQuery("SELECT 1 FROM lan_outbox WHERE message_id=?", new String[]{messageId});
            boolean alreadyExists;
            try { alreadyExists = exists.moveToFirst(); } finally { exists.close(); }
            if (!alreadyExists && count >= MAX_PENDING_MESSAGES) return false;

            ContentValues v = new ContentValues();
            v.put("message_id", messageId);
            v.put("sender_peer_id", senderPeerId);
            v.put("message_type", "chat");
            v.put("body", body);
            v.put("created_at_ms", createdAtMs);
            v.put("protocol_version", 2);
            v.put("state", "discovery_pending");
            v.put("delivery_deadline_ms", 0L);
            db.insertWithOnConflict("lan_outbox", null, v, SQLiteDatabase.CONFLICT_IGNORE);
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
            for (Target target : targets) {
                if (target == null || target.peerId == null || target.peerId.length() == 0) continue;
                ContentValues v = new ContentValues();
                v.put("message_id", messageId);
                v.put("peer_id", target.peerId);
                v.put("status", "pending");
                v.put("last_endpoint", target.endpointIp == null ? "" : target.endpointIp + ":" + target.endpointPort);
                db.insertWithOnConflict("lan_outbox_targets", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id=?", new String[]{messageId});
            int count = 0;
            try { if (c.moveToFirst()) count = c.getInt(0); } finally { c.close(); }
            ContentValues state = new ContentValues();
            state.put("state", count > 0 ? "pending" : "discovery_pending");
            db.update("lan_outbox", state, "message_id=?", new String[]{messageId});
            db.setTransactionSuccessful();
            return count > 0;
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
            if ("discovery_pending".equals(state) && deadline > System.currentTimeMillis()) return false;
            if ("discovery_pending".equals(state) && deadline > 0) {
                ContentValues next = new ContentValues();
                next.put("state", total > 0 && total == acked ? "delivered" : (total > 0 ? "pending" : "discovery_pending"));
                db.update("lan_outbox", next, "message_id=?", new String[]{messageId});
            }
            return total > 0 && total == acked && deadline <= System.currentTimeMillis();
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
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

    public List<PendingMessage> loadPending(int limit) {
        ArrayList<PendingMessage> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT message_id,sender_peer_id,body,created_at_ms,state FROM lan_outbox " +
                    "WHERE state NOT IN ('delivered','expired') ORDER BY created_at_ms LIMIT ?",
                    new String[]{String.valueOf(Math.max(1, Math.min(50, limit)))});
            while (c.moveToNext()) result.add(new PendingMessage(
                    c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4)));
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return result;
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
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

    public boolean markAttempt(String messageId, String peerId, String endpoint, String error) {
        ContentValues v = new ContentValues();
        v.put("status", "sent");
        v.put("attempt_count", "attempt_count + 1");
        v.put("last_attempt_at", System.currentTimeMillis());
        v.put("last_endpoint", endpoint == null ? "" : endpoint);
        v.put("last_error", error == null ? "" : error);
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("UPDATE lan_outbox_targets SET status='sent',attempt_count=attempt_count+1," +
                    "last_attempt_at=?,last_endpoint=?,last_error=? WHERE message_id=? AND peer_id=? AND status!='acked'",
                    new Object[]{System.currentTimeMillis(), endpoint == null ? "" : endpoint,
                            error == null ? "" : error, messageId, peerId});
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean acknowledge(String messageId, String peerId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            Cursor outbox = db.rawQuery("SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id=?",
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

            ContentValues v = new ContentValues();
            v.put("status", "acked");
            v.put("last_ack_at", now);
            int changed = db.update("lan_outbox_targets", v, "message_id=? AND peer_id=?",
                    new String[]{messageId, peerId});
            if (changed == 0) {
                if (!"discovery_pending".equals(state) || (deadline > 0 && now > deadline)) return false;
                if (deadline == 0L) {
                    deadline = now + 5_000L;
                    ContentValues window = new ContentValues();
                    window.put("delivery_deadline_ms", deadline);
                    db.update("lan_outbox", window, "message_id=?", new String[]{messageId});
                }
                ContentValues target = new ContentValues();
                target.put("message_id", messageId);
                target.put("peer_id", peerId);
                target.put("status", "acked");
                target.put("last_ack_at", now);
                db.insertWithOnConflict("lan_outbox_targets", null, target, SQLiteDatabase.CONFLICT_IGNORE);
            }
            Cursor c = db.rawQuery("SELECT COUNT(*), SUM(CASE WHEN status='acked' THEN 1 ELSE 0 END) " +
                    "FROM lan_outbox_targets WHERE message_id=?", new String[]{messageId});
            int total = 0, acked = 0;
            try {
                if (c.moveToFirst()) {
                    total = c.getInt(0);
                    acked = c.isNull(1) ? 0 : c.getInt(1);
                }
            } finally { c.close(); }
            boolean discoveryOpen = "discovery_pending".equals(state) && deadline > 0 && now <= deadline;
            if (!discoveryOpen) {
                ContentValues next = new ContentValues();
                next.put("state", total > 0 && total == acked ? "delivered" : "pending");
                db.update("lan_outbox", next, "message_id=?", new String[]{messageId});
            }
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public boolean isDiscoveryPending(String messageId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM lan_outbox WHERE message_id=? AND state='discovery_pending'",
                    new String[]{messageId});
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Add a newly discovered peer to all open first-discovery snapshots. */
    public void addDiscoveryPeerToOpenMessages(String peerId) {
        if (peerId == null || peerId.length() == 0) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            Cursor c = db.rawQuery("SELECT message_id,delivery_deadline_ms FROM lan_outbox " +
                    "WHERE state='discovery_pending'", null);
            try {
                while (c.moveToNext()) {
                    String messageId = c.getString(0);
                    long deadline = c.getLong(1);
                    if (deadline > 0 && now > deadline) {
                        finalizeDiscoveryMessage(db, messageId);
                        continue;
                    }
                    if (deadline == 0L) {
                        ContentValues window = new ContentValues();
                        window.put("delivery_deadline_ms", now + 5_000L);
                        db.update("lan_outbox", window, "message_id=?", new String[]{messageId});
                    }
                    ContentValues target = new ContentValues();
                    target.put("message_id", messageId);
                    target.put("peer_id", peerId);
                    target.put("status", "pending");
                    db.insertWithOnConflict("lan_outbox_targets", null, target, SQLiteDatabase.CONFLICT_IGNORE);
                }
            } finally { c.close(); }
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
        } finally {
            db.endTransaction();
        }
    }

    private void finalizeDiscoveryMessage(SQLiteDatabase db, String messageId) {
        Cursor c = db.rawQuery("SELECT COUNT(*), SUM(CASE WHEN status='acked' THEN 1 ELSE 0 END) " +
                "FROM lan_outbox_targets WHERE message_id=?", new String[]{messageId});
        int total = 0, acked = 0;
        try {
            if (c.moveToFirst()) {
                total = c.getInt(0);
                acked = c.isNull(1) ? 0 : c.getInt(1);
            }
        } finally { c.close(); }
        ContentValues next = new ContentValues();
        next.put("state", total > 0 && total == acked ? "delivered" : (total > 0 ? "pending" : "discovery_pending"));
        db.update("lan_outbox", next, "message_id=?", new String[]{messageId});
    }

    /** Returns true for both first storage and a durable duplicate; false means storage failed. */
    public boolean storeIncoming(String receiverScope, String senderPeerId, String messageId,
                                 String body, String displayName, long createdAtMs, String displayTime) {
        if (receiverScope == null || senderPeerId == null || messageId == null || body == null) return false;
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
            db.insertWithOnConflict("lan_inbox", null, v, SQLiteDatabase.CONFLICT_IGNORE);
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
    public void observePeer(String peerId, String peerType, String serviceId, String networkScope,
                            String ip, int port, long now, long leaseExpiresAt) {
        if (peerId == null || peerId.length() == 0 || ip == null || ip.length() == 0
                || port <= 0 || port > 65535) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String oldIp = null;
            int oldPort = -1;
            long oldVerifiedAt = 0L;
            String oldStatus = "suspect";
            Cursor c = db.rawQuery("SELECT endpoint_ip,endpoint_port,last_verified_at,status "
                    + "FROM lan_peers WHERE peer_id=?", new String[]{peerId});
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
                db.insertWithOnConflict("lan_peers", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            } else {
                db.update("lan_peers", v, "peer_id=?", new String[]{peerId});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void upsertPeer(String peerId, String peerType, String serviceId, String networkScope,
                           String ip, int port, long now, long leaseExpiresAt) {
        if (peerId == null || peerId.length() == 0) return;
        ContentValues v = new ContentValues();
        v.put("peer_id", peerId);
        v.put("peer_type", peerType == null ? "unknown" : peerType);
        v.put("service_id", serviceId == null ? "" : serviceId);
        v.put("protocol_version", 2);
        v.put("network_scope", networkScope == null ? "" : networkScope);
        v.put("endpoint_ip", ip == null ? "" : ip);
        v.put("endpoint_port", port);
        v.put("last_seen_at", now);
        v.put("last_verified_at", now);
        v.put("lease_expires_at", leaseExpiresAt);
        v.put("status", "active");
        getWritableDatabase().insertWithOnConflict("lan_peers", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void closeStore() {
        close();
    }
}
