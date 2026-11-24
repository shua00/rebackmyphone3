"""Shared, dependency-free persistence primitives for LAN P2P services."""
import sqlite3
import time


# Must match Android LanChatStore.FIRST_DISCOVERY_WINDOW_MS.
FIRST_DISCOVERY_WINDOW_MS = 10_000
MAX_PEER_ID_BYTES = 128
MAX_OUTBOX_TARGET_ATTEMPTS = 8
OUTBOX_RETRY_RECOVERY_COOLDOWN_MS = 60_000


class LanP2PStorageError(RuntimeError):
    """A durable LAN P2P ledger operation failed; callers must not treat it as empty state."""


def _safe_rollback(conn):
    """Best-effort rollback that never masks the original storage failure."""
    if conn is not None:
        try:
            conn.rollback()
        except Exception:
            pass


def _safe_close(conn):
    """Best-effort close; a cleanup failure must not replace the operation result."""
    if conn is not None:
        try:
            conn.close()
        except Exception:
            pass


def record_peer(db_path, receiver_scope, peer_id, peer_type, service_id,
                endpoint_ip, endpoint_port, status="suspect"):
    """Persist a discovered peer without treating an unconfirmed HELLO as active."""
    if not peer_id or not receiver_scope:
        return False
    now = time.time()
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        try:
            conn.execute("PRAGMA busy_timeout=10000")
            conn.execute("""CREATE TABLE IF NOT EXISTS lan_peers (
                receiver_scope TEXT NOT NULL,
                peer_id TEXT NOT NULL,
                peer_type TEXT NOT NULL DEFAULT 'unknown',
                service_id TEXT NOT NULL DEFAULT '',
                protocol_version INTEGER NOT NULL DEFAULT 2,
                capabilities TEXT NOT NULL DEFAULT '',
                network_scope TEXT NOT NULL DEFAULT '',
                endpoint_ip TEXT NOT NULL DEFAULT '',
                endpoint_port INTEGER NOT NULL DEFAULT 0,
                last_seen_at REAL NOT NULL DEFAULT 0,
                last_verified_at REAL NOT NULL DEFAULT 0,
                lease_expires_at REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'suspect',
                PRIMARY KEY(receiver_scope, peer_id)
            )""")
            conn.execute("""INSERT INTO lan_peers
                (receiver_scope,peer_id,peer_type,service_id,protocol_version,
                 endpoint_ip,endpoint_port,last_seen_at,last_verified_at,lease_expires_at,status)
                VALUES(?,?,?,?,2,?,?,?,?,?,?)
                ON CONFLICT(receiver_scope,peer_id) DO UPDATE SET
                    peer_type=excluded.peer_type,
                    service_id=excluded.service_id,
                    endpoint_ip=excluded.endpoint_ip,
                    endpoint_port=excluded.endpoint_port,
                    last_seen_at=excluded.last_seen_at,
                    last_verified_at=CASE
                        WHEN excluded.status='active' THEN excluded.last_verified_at
                        WHEN excluded.status='suspect' AND
                             (lan_peers.endpoint_ip!=excluded.endpoint_ip OR
                              lan_peers.endpoint_port!=excluded.endpoint_port) THEN 0
                        ELSE lan_peers.last_verified_at END,
                    lease_expires_at=CASE
                        WHEN excluded.status='active' THEN excluded.lease_expires_at
                        ELSE lan_peers.lease_expires_at END,
                    status=CASE
                        WHEN excluded.status='suspect' AND
                             (lan_peers.endpoint_ip!=excluded.endpoint_ip OR
                              lan_peers.endpoint_port!=excluded.endpoint_port) THEN 'suspect'
                        WHEN lan_peers.status='active' AND excluded.status='suspect'
                        THEN 'active' ELSE excluded.status END""",
                (receiver_scope, str(peer_id), peer_type or "unknown", service_id or "",
                 endpoint_ip or "", int(endpoint_port or 0), now,
                 now if status == "active" else 0.0, now + 120.0, status))
            conn.commit()
            return True
        finally:
            _safe_close(conn)
    except Exception:
        return False


def load_stable_peer_id(db_path, service_name):
    """Return a durable logical service identity; never a process-session UUID."""
    conn = sqlite3.connect(db_path, timeout=10)
    try:
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("""CREATE TABLE IF NOT EXISTS lan_service_identity (
            service_name TEXT PRIMARY KEY,
            peer_id TEXT NOT NULL UNIQUE,
            created_at REAL NOT NULL
        )""")
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute(
            "SELECT peer_id FROM lan_service_identity WHERE service_name=?",
            (service_name,)
        ).fetchone()
        if row and row[0]:
            peer_id = str(row[0])
        else:
            import uuid
            peer_id = service_name + "-" + uuid.uuid4().hex
            conn.execute(
                "INSERT INTO lan_service_identity(service_name,peer_id,created_at) VALUES(?,?,?)",
                (service_name, peer_id, time.time())
            )
        conn.commit()
        return peer_id
    except Exception:
        _safe_rollback(conn)
        raise
    finally:
        _safe_close(conn)


def _canonical_envelope_bytes(fields):
    import json
    return json.dumps(fields, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def _envelope_key(secret):
    import hashlib
    if not isinstance(secret, str) or not secret:
        raise ValueError("shared secret is required")
    return hashlib.sha256(b"lan-p2p-v2\x00" + secret.encode("utf-8")).digest()


def max_peer_id_budget_probe():
    """Return a valid maximum-length ID with worst-case JSON quote escaping."""
    return '"' * MAX_PEER_ID_BYTES


def is_valid_peer_id(peer_id):
    """Shared identity predicate for packet codecs and durable recipient snapshots."""
    if not isinstance(peer_id, str) or not peer_id:
        return False
    try:
        if len(peer_id.encode("utf-8")) > MAX_PEER_ID_BYTES:
            return False
    except UnicodeError:
        return False
    return not any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in peer_id)


def _validate_peer_id_fields(packet):
    for field in ("sender_peer_id", "target_peer_id"):
        if field not in packet and field == "target_peer_id":
            continue
        if not is_valid_peer_id(packet.get(field)):
            raise ValueError("invalid " + field)


def encode_packet(fields, secret, max_bytes=1200):
    """Encode and authenticate a bounded v2 JSON datagram."""
    import hashlib
    import hmac
    if not isinstance(fields, dict):
        raise ValueError("packet fields must be a dict")
    packet = dict(fields)
    packet.pop("auth", None)
    packet["v"] = 2
    packet_type = packet.get("type")
    if packet_type not in ("peer.hello", "peer.ack", "chat.message", "chat.ack"):
        raise ValueError("unsupported LAN P2P packet type")
    _validate_peer_id_fields(packet)
    unsigned = _canonical_envelope_bytes(packet)
    packet["auth"] = hmac.new(_envelope_key(secret), unsigned, hashlib.sha256).hexdigest()
    raw = _canonical_envelope_bytes(packet)
    if len(raw) > int(max_bytes):
        raise ValueError("LAN P2P datagram exceeds size limit")
    return raw


def decode_packet(data, secret, max_bytes=1200):
    """Decode and authenticate a v2 datagram; raises ValueError on any invalid packet."""
    import hashlib
    import hmac
    import json
    if not isinstance(data, (bytes, bytearray)):
        raise ValueError("packet must be bytes")
    if len(data) == 0 or len(data) > int(max_bytes):
        raise ValueError("invalid LAN P2P datagram size")
    try:
        packet = json.loads(bytes(data).decode("utf-8", errors="strict"))
    except Exception as exc:
        raise ValueError("invalid LAN P2P JSON") from exc
    if not isinstance(packet, dict) or packet.get("v") != 2:
        raise ValueError("unsupported LAN P2P protocol version")
    if packet.get("type") not in ("peer.hello", "peer.ack", "chat.message", "chat.ack"):
        raise ValueError("unsupported LAN P2P packet type")
    _validate_peer_id_fields(packet)
    supplied = packet.get("auth")
    if not isinstance(supplied, str):
        raise ValueError("missing packet authentication")
    unsigned = dict(packet)
    unsigned.pop("auth", None)
    expected = hmac.new(_envelope_key(secret), _canonical_envelope_bytes(unsigned),
                        hashlib.sha256).hexdigest()
    if not hmac.compare_digest(supplied, expected):
        raise ValueError("invalid packet authentication")
    return packet


def validate_incoming_message_claim(conn, insert_rowcount, receiver_scope,
                                    sender_peer_id, message_id, body):
    """Return True for a new row, False for an identical duplicate, None for payload conflict."""
    if conn is None or not receiver_scope or not sender_peer_id or not message_id:
        return None
    if int(insert_rowcount or 0) == 1:
        return True
    row = conn.execute("""SELECT content FROM received_chat_messages
        WHERE receiver_scope=? AND sender=? AND message_key=?""",
        (str(receiver_scope), str(sender_peer_id), str(message_id))).fetchone()
    if row is None or str(row[0]) != str(body):
        return None
    return False


def persist_outgoing_message(db_path, sender_scope, sender_peer_id, message_id,
                             body, target_peer_ids, created_at_ms=None):
    """Atomically persist a Python-originated chat message and its fixed recipient snapshot."""
    if not sender_scope or not is_valid_peer_id(sender_peer_id) or not message_id or body is None:
        return False
    persisted_at_ms = int(time.time() * 1000)
    now_ms = int(created_at_ms if created_at_ms is not None else persisted_at_ms)
    targets = sorted(set(str(peer) for peer in (target_peer_ids or []) if peer))
    if any(not is_valid_peer_id(peer) for peer in targets):
        return False
    conn = sqlite3.connect(db_path, timeout=10)
    try:
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("""CREATE TABLE IF NOT EXISTS lan_outbox (
            message_id TEXT PRIMARY KEY NOT NULL,
            sender_scope TEXT NOT NULL,
            sender_peer_id TEXT NOT NULL,
            message_type TEXT NOT NULL DEFAULT 'chat',
            body TEXT NOT NULL,
            created_at_ms INTEGER NOT NULL,
            protocol_version INTEGER NOT NULL DEFAULT 2,
            state TEXT NOT NULL DEFAULT 'discovery_pending',
            delivery_deadline_ms INTEGER NOT NULL DEFAULT 0
        )""")
        conn.execute("""CREATE TABLE IF NOT EXISTS lan_outbox_targets (
            message_id TEXT NOT NULL,
            peer_id TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            attempt_count INTEGER NOT NULL DEFAULT 0,
            last_attempt_at INTEGER NOT NULL DEFAULT 0,
            last_ack_at INTEGER NOT NULL DEFAULT 0,
            last_endpoint TEXT NOT NULL DEFAULT '',
            last_error TEXT NOT NULL DEFAULT '',
            last_recovery_at_ms INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(message_id, peer_id)
        )""")
        target_columns = {row[1] for row in conn.execute("PRAGMA table_info(lan_outbox_targets)")}
        if "last_recovery_at_ms" not in target_columns:
            conn.execute("ALTER TABLE lan_outbox_targets ADD COLUMN last_recovery_at_ms INTEGER NOT NULL DEFAULT 0")
        conn.execute("BEGIN IMMEDIATE")
        existing = conn.execute(
            "SELECT sender_scope,sender_peer_id,body FROM lan_outbox WHERE message_id=?",
            (message_id,)
        ).fetchone()
        if existing is not None:
            # A retry may resume the original outbox row, but it must never mutate
            # the original payload or enlarge the frozen recipient snapshot.
            same_message = (existing[0] == sender_scope
                            and existing[1] == sender_peer_id
                            and existing[2] == str(body))
            if not same_message:
                _safe_rollback(conn)
                return False
            conn.commit()
            return True

        # Empty-recipient discovery starts only when a real UDP dispatch begins.
        # A queued message with no ready socket must not burn its discovery window.
        discovery_deadline_ms = 0
        conn.execute("""INSERT INTO lan_outbox
            (message_id,sender_scope,sender_peer_id,message_type,body,created_at_ms,
             protocol_version,state,delivery_deadline_ms)
            VALUES(?,?,?,'chat',?,?,2,?,?)""",
            (message_id, sender_scope, sender_peer_id, str(body), now_ms,
             "pending" if targets else "discovery_pending", discovery_deadline_ms))
        for peer_id in targets:
            conn.execute("""INSERT INTO lan_outbox_targets
                (message_id,peer_id,status) VALUES(?,?,'pending')""", (message_id, peer_id))
        conn.commit()
        return True
    except Exception:
        _safe_rollback(conn)
        return False
    finally:
        _safe_close(conn)


def ensure_outbox_retry_schema(db_path):
    """Create/migrate the durable per-target retry ledger before recovery workers start."""
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("""CREATE TABLE IF NOT EXISTS lan_outbox (
            message_id TEXT PRIMARY KEY NOT NULL, sender_scope TEXT NOT NULL,
            sender_peer_id TEXT NOT NULL, message_type TEXT NOT NULL DEFAULT 'chat',
            body TEXT NOT NULL, created_at_ms INTEGER NOT NULL,
            protocol_version INTEGER NOT NULL DEFAULT 2,
            state TEXT NOT NULL DEFAULT 'discovery_pending',
            delivery_deadline_ms INTEGER NOT NULL DEFAULT 0)""")
        conn.execute("""CREATE TABLE IF NOT EXISTS lan_outbox_targets (
            message_id TEXT NOT NULL, peer_id TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            attempt_count INTEGER NOT NULL DEFAULT 0,
            last_attempt_at INTEGER NOT NULL DEFAULT 0,
            last_ack_at INTEGER NOT NULL DEFAULT 0,
            last_endpoint TEXT NOT NULL DEFAULT '',
            last_error TEXT NOT NULL DEFAULT '',
            last_recovery_at_ms INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(message_id, peer_id))""")
        columns = {row[1] for row in conn.execute("PRAGMA table_info(lan_outbox_targets)")}
        migrations = {
            "status": "TEXT NOT NULL DEFAULT 'pending'",
            "attempt_count": "INTEGER NOT NULL DEFAULT 0",
            "last_attempt_at": "INTEGER NOT NULL DEFAULT 0",
            "last_ack_at": "INTEGER NOT NULL DEFAULT 0",
            "last_endpoint": "TEXT NOT NULL DEFAULT ''",
            "last_error": "TEXT NOT NULL DEFAULT ''",
            "last_recovery_at_ms": "INTEGER NOT NULL DEFAULT 0",
        }
        for column, declaration in migrations.items():
            if column not in columns:
                conn.execute("ALTER TABLE lan_outbox_targets ADD COLUMN " + column + " " + declaration)
        # The retry worker scans by recipient and due time once per second; keep it indexed.
        conn.execute("CREATE INDEX IF NOT EXISTS idx_lan_outbox_targets_retry "
                     "ON lan_outbox_targets(peer_id,status,attempt_count,last_attempt_at)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_lan_outbox_sender_state "
                     "ON lan_outbox(sender_scope,state)")
        conn.commit()
        return True
    except Exception as exc:
        if conn is not None:
            _safe_rollback(conn)
        raise LanP2PStorageError("failed to initialize LAN outbox retry schema") from exc
    finally:
        if conn is not None:
            _safe_close(conn)


def prepare_outgoing_peer_recovery(db_path, sender_scope, peer_id, now_ms=None):
    """Throttle verified-route recovery and reopen exhausted targets after cooldown."""
    if not sender_scope or not peer_id:
        return 0
    conn = None
    now = int(now_ms if now_ms is not None else time.time() * 1000)
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("BEGIN IMMEDIATE")
        cur = conn.execute("""UPDATE lan_outbox_targets
            SET attempt_count=CASE WHEN attempt_count>=? THEN 0 ELSE attempt_count END,
                last_attempt_at=CASE WHEN attempt_count>=? THEN 0 ELSE last_attempt_at END,
                last_error=CASE WHEN attempt_count>=? THEN '' ELSE last_error END,
                last_recovery_at_ms=?
            WHERE peer_id=? AND status!='acked' AND attempt_count>=? AND last_recovery_at_ms<=?
              AND EXISTS (SELECT 1 FROM lan_outbox o
                          WHERE o.message_id=lan_outbox_targets.message_id
                            AND o.sender_scope=?
                            AND o.state NOT IN ('delivered','expired','waiting_peers'))""",
            (MAX_OUTBOX_TARGET_ATTEMPTS, MAX_OUTBOX_TARGET_ATTEMPTS,
             MAX_OUTBOX_TARGET_ATTEMPTS, now, str(peer_id), MAX_OUTBOX_TARGET_ATTEMPTS,
             now - OUTBOX_RETRY_RECOVERY_COOLDOWN_MS, str(sender_scope)))
        conn.commit()
        return int(cur.rowcount or 0)
    except Exception as exc:
        if conn is not None:
            _safe_rollback(conn)
        raise LanP2PStorageError("failed to prepare LAN peer recovery retry epoch") from exc
    finally:
        if conn is not None:
            _safe_close(conn)


def list_due_outgoing_peers(db_path, sender_scope, limit=500, now_ms=None):
    """Find active peers with unacked targets whose persisted backoff elapsed."""
    conn = None
    now = int(now_ms if now_ms is not None else time.time() * 1000)
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        rows = conn.execute("""SELECT DISTINCT t.peer_id
            FROM lan_outbox o
            JOIN lan_outbox_targets t ON t.message_id=o.message_id
            JOIN lan_peers p ON p.receiver_scope=? AND p.peer_id=t.peer_id
            WHERE o.sender_scope=? AND o.state NOT IN ('delivered','expired','waiting_peers')
              AND t.status!='acked' AND t.attempt_count<?
              AND p.status='active' AND p.lease_expires_at>?
              AND (t.attempt_count=0
                   OR t.last_endpoint != (p.endpoint_ip || ':' || p.endpoint_port)
                   OR t.last_attempt_at +
                      CASE t.attempt_count WHEN 1 THEN 1500 WHEN 2 THEN 4000
                        WHEN 3 THEN 10000 WHEN 4 THEN 20000 WHEN 5 THEN 40000
                        ELSE 60000 END <= ?)
            ORDER BY t.peer_id LIMIT ?""",
            (sender_scope, sender_scope, MAX_OUTBOX_TARGET_ATTEMPTS,
             now / 1000.0, now, max(1, min(500, int(limit))))).fetchall()
        return [str(row[0]) for row in rows]
    except Exception as exc:
        if conn is not None:
            _safe_rollback(conn)
        raise LanP2PStorageError("failed to scan due LAN outbox peer retries") from exc
    finally:
        if conn is not None:
            _safe_close(conn)


def encode_chat_http_fallback_payload(message_id, sender_peer_id, display_name, body):
    """Serialize the HTTP fallback for a v2 LAN chat using the identical message identity."""
    import json
    if not message_id or not sender_peer_id or body is None:
        raise ValueError("chat fallback requires message_id, sender_peer_id and body")
    envelope = {
        "transport": "lan-p2p-v2",
        "message_id": str(message_id),
        "sender_peer_id": str(sender_peer_id),
        "display_name": str(display_name or sender_peer_id),
        "body": str(body),
    }
    return json.dumps(envelope, ensure_ascii=False, separators=(",", ":"))


def decode_chat_http_fallback_payload(payload):
    """Return a validated shared-ID HTTP fallback envelope, or None for legacy text commands."""
    import json
    if not isinstance(payload, str):
        return None
    try:
        envelope = json.loads(payload)
    except Exception:
        return None
    if not isinstance(envelope, dict) or envelope.get("transport") != "lan-p2p-v2":
        return None
    message_id = envelope.get("message_id")
    sender_peer_id = envelope.get("sender_peer_id")
    body = envelope.get("body")
    display_name = envelope.get("display_name") or sender_peer_id
    if not isinstance(message_id, str) or not message_id:
        return None
    if not isinstance(sender_peer_id, str) or not sender_peer_id:
        return None
    if not isinstance(body, str):
        return None
    if not isinstance(display_name, str) or not display_name:
        return None
    return {
        "transport": "lan-p2p-v2",
        "message_id": message_id,
        "sender_peer_id": sender_peer_id,
        "display_name": display_name,
        "body": body,
    }


def acknowledge_outgoing_message(db_path, sender_scope, message_id, peer_id):
    """Persist exactly one recipient ACK; unknown/non-target ACKs never complete an outbox row."""
    if not sender_scope or not message_id or not peer_id:
        return False
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT state FROM lan_outbox WHERE sender_scope=? AND message_id=?",
                           (sender_scope, message_id)).fetchone()
        # Expired is terminal. A delayed ACK must not mutate target rows or
        # resurrect the aggregate outbox state, matching the Android ledger.
        if row is None or row[0] == "expired":
            _safe_rollback(conn)
            return False
        now_ms = int(time.time() * 1000)
        cur = conn.execute("""UPDATE lan_outbox_targets
            SET status='acked',last_ack_at=?
            WHERE message_id=? AND peer_id=? AND status!='acked'""",
            (now_ms, message_id, peer_id))
        if cur.rowcount == 0:
            existing = conn.execute(
                "SELECT status FROM lan_outbox_targets WHERE message_id=? AND peer_id=?",
                (message_id, peer_id)
            ).fetchone()
            if existing and existing[0] == "acked":
                # Duplicate ACKs are idempotent, including after aggregate delivery.
                pass
            else:
                # With no known members at send time, authenticated ACKs populate the first bounded
                # discovery snapshot. Once the window closes, non-target ACKs are rejected.
                row = conn.execute("SELECT state,delivery_deadline_ms FROM lan_outbox WHERE sender_scope=? AND message_id=?",
                                   (sender_scope, message_id)).fetchone()
                if row is None or row[0] != "discovery_pending":
                    _safe_rollback(conn)
                    return False
                deadline = int(row[1] or 0)
                # Unknown ACKs may join only after the first actual UDP dispatch
                # opened the bounded discovery window; handshakes alone cannot start it.
                if not deadline or now_ms >= deadline:
                    _safe_rollback(conn)
                    return False
                conn.execute("""INSERT OR IGNORE INTO lan_outbox_targets
                    (message_id,peer_id,status,last_ack_at) VALUES(?,?,'acked',?)""",
                    (message_id, peer_id, now_ms))
        total, acked = conn.execute("""SELECT COUNT(*),
            SUM(CASE WHEN status='acked' THEN 1 ELSE 0 END)
            FROM lan_outbox_targets WHERE message_id=?""", (message_id,)).fetchone()
        row = conn.execute("SELECT state,delivery_deadline_ms FROM lan_outbox WHERE sender_scope=? AND message_id=?",
                           (sender_scope, message_id)).fetchone()
        discovery_open = bool(row and row[0] == "discovery_pending"
                              and row[1] and now_ms < int(row[1]))
        if discovery_open:
            pass
        elif total > 0 and total == (acked or 0):
            conn.execute("UPDATE lan_outbox SET state='delivered' WHERE message_id=?", (message_id,))
        else:
            conn.execute("UPDATE lan_outbox SET state='partial' WHERE message_id=?", (message_id,))
        conn.commit()
        return True
    except Exception:
        if conn is not None:
            try:
                _safe_rollback(conn)
            except Exception:
                pass
        return False
    finally:
        if conn is not None:
            try:
                _safe_close(conn)
            except Exception:
                pass


def _finalize_expired_discovery_windows(conn, sender_scope, now_ms):
    """Settle expired discovery snapshots exactly once, including empty snapshots."""
    conn.execute("""UPDATE lan_outbox SET state=CASE
            WHEN EXISTS (SELECT 1 FROM lan_outbox_targets t
                         WHERE t.message_id=lan_outbox.message_id)
            THEN CASE
                WHEN NOT EXISTS (SELECT 1 FROM lan_outbox_targets t
                                WHERE t.message_id=lan_outbox.message_id AND t.status!='acked')
                THEN 'delivered'
                ELSE 'pending'
            END
            ELSE 'waiting_peers'
        END
        WHERE sender_scope=? AND state='discovery_pending'
          AND delivery_deadline_ms>0 AND delivery_deadline_ms<=?""",
        (sender_scope, now_ms))


def load_pending_outgoing_for_peer(db_path, sender_scope, peer_id, limit=10, force=False):
    """Load due unacked targets; verified recovery may bypass backoff, never the attempt cap."""
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        now_ms = int(time.time() * 1000)
        _finalize_expired_discovery_windows(conn, sender_scope, now_ms)
        conn.commit()
        rows = conn.execute("""SELECT o.message_id,o.sender_peer_id,o.body,o.created_at_ms,
                    p.endpoint_ip,p.endpoint_port
            FROM lan_outbox o
            JOIN lan_outbox_targets t ON t.message_id=o.message_id
            JOIN lan_peers p ON p.receiver_scope=? AND p.peer_id=t.peer_id
            WHERE o.sender_scope=? AND t.peer_id=? AND t.status!='acked'
              AND o.state NOT IN ('delivered','expired','waiting_peers')
              AND t.attempt_count<?
              AND (?=1 OR t.attempt_count=0
                   OR t.last_endpoint != (p.endpoint_ip || ':' || p.endpoint_port)
                   OR t.last_attempt_at +
                      CASE t.attempt_count WHEN 1 THEN 1500 WHEN 2 THEN 4000
                        WHEN 3 THEN 10000 WHEN 4 THEN 20000 WHEN 5 THEN 40000
                        ELSE 60000 END <= ?)
            ORDER BY o.created_at_ms LIMIT ?""",
            (sender_scope, sender_scope, peer_id, MAX_OUTBOX_TARGET_ATTEMPTS,
             1 if force else 0, now_ms, max(1, min(50, int(limit))))).fetchall()
        return [{"message_id": r[0], "sender_peer_id": r[1], "body": r[2],
                 "created_at_ms": r[3], "endpoint_ip": r[4], "endpoint_port": r[5]}
                for r in rows]
    except Exception as exc:
        if conn is not None:
            _safe_rollback(conn)
        raise LanP2PStorageError("failed to load pending LAN outbox rows for peer") from exc
    finally:
        if conn is not None:
            _safe_close(conn)


def mark_outgoing_attempt(db_path, message_id, peer_id, endpoint_ip, endpoint_port,
                         allow_fast_retry=True):
    """Atomically account a send; recovery workers must revalidate the persisted backoff.

    The initial bounded burst may retry quickly. Background workers pass
    allow_fast_retry=False so a peer/message selected by an earlier due scan
    cannot be sent again after another dispatcher has already advanced its
    retry timestamp.
    """
    conn = None
    now_ms = int(time.time() * 1000)
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        endpoint = str(endpoint_ip) + ":" + str(endpoint_port)
        cur = conn.execute("""UPDATE lan_outbox_targets SET status='sent',
            attempt_count=attempt_count+1,last_attempt_at=?,last_endpoint=?,last_error=''
            WHERE message_id=? AND peer_id=? AND status!='acked' AND attempt_count<?
              AND (?=1 OR attempt_count=0 OR last_endpoint<>? OR last_attempt_at +
                   CASE attempt_count WHEN 1 THEN 1500 WHEN 2 THEN 4000
                     WHEN 3 THEN 10000 WHEN 4 THEN 20000 WHEN 5 THEN 40000
                     ELSE 60000 END <= ?)
              AND EXISTS (SELECT 1 FROM lan_outbox o
                          WHERE o.message_id=lan_outbox_targets.message_id
                            AND o.state NOT IN ('delivered','expired','waiting_peers'))""",
            (now_ms, endpoint, message_id, peer_id, MAX_OUTBOX_TARGET_ATTEMPTS,
             1 if allow_fast_retry else 0, endpoint, now_ms))
        conn.commit()
        return cur.rowcount > 0
    except Exception as exc:
        if conn is not None:
            _safe_rollback(conn)
        raise LanP2PStorageError("failed to persist LAN outbox send attempt") from exc
    finally:
        if conn is not None:
            _safe_close(conn)



def list_known_peers(db_path, receiver_scope, limit=100):
    """Return currently leased peers; storage failures must not masquerade as no peers."""
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        rows = conn.execute("""SELECT peer_id,peer_type,service_id,endpoint_ip,endpoint_port,
                    last_seen_at,lease_expires_at,status
            FROM lan_peers WHERE receiver_scope=? AND lease_expires_at>?
              AND status='active'
            ORDER BY last_seen_at DESC LIMIT ?""",
            (receiver_scope, time.time(), max(1, min(500, int(limit))))).fetchall()
        return [{"peer_id": r[0], "peer_type": r[1], "service_id": r[2],
                 "endpoint_ip": r[3], "endpoint_port": r[4],
                 "last_seen_at": r[5], "lease_expires_at": r[6], "status": r[7]}
                for r in rows]
    except Exception as exc:
        raise LanP2PStorageError("failed to load known LAN peers") from exc
    finally:
        if conn is not None:
            _safe_close(conn)

def list_known_peer_snapshot(db_path, receiver_scope, limit=500):
    """Return historical peer identities for immutable recipient snapshots."""
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        rows = conn.execute("""SELECT peer_id,peer_type,service_id,endpoint_ip,endpoint_port,
                    last_seen_at,lease_expires_at,status
            FROM lan_peers WHERE receiver_scope=?
            ORDER BY last_seen_at DESC LIMIT ?""",
            (receiver_scope, max(1, min(1000, int(limit))))).fetchall()
        return [{"peer_id": r[0], "peer_type": r[1], "service_id": r[2],
                 "endpoint_ip": r[3], "endpoint_port": r[4],
                 "last_seen_at": r[5], "lease_expires_at": r[6], "status": r[7]}
                for r in rows]
    except Exception as exc:
        raise LanP2PStorageError("failed to load LAN peer recipient snapshot") from exc
    finally:
        if conn is not None:
            _safe_close(conn)

def add_discovery_target(db_path, sender_scope, message_id, peer_id):
    """Add a peer only during the first bounded discovery window of an unknown-recipient message."""
    if not sender_scope or not message_id or not peer_id:
        return False
    conn = sqlite3.connect(db_path, timeout=10)
    try:
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT state,delivery_deadline_ms FROM lan_outbox "
                           "WHERE sender_scope=? AND message_id=?",
                           (sender_scope, message_id)).fetchone()
        if row is None or row[0] != "discovery_pending":
            _safe_rollback(conn)
            return False
        now_ms = int(time.time() * 1000)
        deadline = int(row[1] or 0)
        # Only the first actual UDP dispatch may open this window. A peer
        # handshake or helper call while the message is merely queued must not.
        if not deadline or now_ms >= deadline:
            _safe_rollback(conn)
            return False
        conn.execute("""INSERT OR IGNORE INTO lan_outbox_targets
            (message_id,peer_id,status) VALUES(?,?,'pending')""", (message_id, peer_id))
        conn.commit()
        return True
    except Exception:
        _safe_rollback(conn)
        return False
    finally:
        _safe_close(conn)


def add_discovery_targets_for_peer(db_path, sender_scope, peer_id):
    """Attach a newly discovered peer to every still-open unknown-recipient message."""
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("BEGIN IMMEDIATE")
        now_ms = int(time.time() * 1000)
        _finalize_expired_discovery_windows(conn, sender_scope, now_ms)
        rows = conn.execute("""SELECT message_id,delivery_deadline_ms FROM lan_outbox
            WHERE sender_scope=? AND state='discovery_pending'""", (sender_scope,)).fetchall()
        added = 0
        for message_id, deadline_value in rows:
            deadline = int(deadline_value or 0)
            # A peer handshake before the first message dispatch must not start
            # or consume this message's bounded discovery window.
            if not deadline or now_ms >= deadline:
                continue
            cur = conn.execute("""INSERT OR IGNORE INTO lan_outbox_targets
                (message_id,peer_id,status) VALUES(?,?,'pending')""", (message_id, peer_id))
            added += max(0, cur.rowcount)
        conn.commit()
        return added
    except Exception as exc:
        if conn is not None:
            _safe_rollback(conn)
        raise LanP2PStorageError("failed to attach peer to LAN discovery snapshots") from exc
    finally:
        if conn is not None:
            _safe_close(conn)

def remember_peer_challenge(db_path, receiver_scope, nonce, expected_peer_id="",
                            expected_ip="", expected_port=0, ttl_seconds=5):
    """Persist a short-lived nonce bound to an optional peer identity and UDP endpoint."""
    if not db_path or not receiver_scope or not nonce:
        return False
    try:
        now = time.time()
        conn = sqlite3.connect(db_path, timeout=10)
        try:
            conn.execute("PRAGMA busy_timeout=10000")
            conn.execute("""CREATE TABLE IF NOT EXISTS lan_peer_challenges (
                receiver_scope TEXT NOT NULL,
                nonce TEXT NOT NULL,
                expected_peer_id TEXT NOT NULL DEFAULT '',
                expected_ip TEXT NOT NULL DEFAULT '',
                expected_port INTEGER NOT NULL DEFAULT 0,
                expires_at REAL NOT NULL,
                PRIMARY KEY(receiver_scope, nonce)
            )""")
            conn.execute("DELETE FROM lan_peer_challenges WHERE expires_at<=?", (now,))
            conn.execute("""INSERT OR REPLACE INTO lan_peer_challenges
                (receiver_scope,nonce,expected_peer_id,expected_ip,expected_port,expires_at)
                VALUES(?,?,?,?,?,?)""",
                (receiver_scope, str(nonce), str(expected_peer_id or ""),
                 str(expected_ip or ""), int(expected_port or 0),
                 now + max(1.0, min(30.0, float(ttl_seconds)))))
            conn.commit()
            return True
        finally:
            _safe_close(conn)
    except Exception:
        return False


def complete_peer_challenge(db_path, receiver_scope, nonce, peer_id,
                            endpoint_ip, endpoint_port, peer_type="unknown",
                            service_id=""):
    """Atomically consume a directed nonce and activate only its bound peer route."""
    if (not db_path or not receiver_scope or not nonce or not peer_id
            or not endpoint_ip or int(endpoint_port or 0) not in (8887, 8888, 8889, 8890)):
        return False
    conn = None
    try:
        now = time.time()
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("BEGIN IMMEDIATE")
        conn.execute("""CREATE TABLE IF NOT EXISTS lan_peer_challenges (
            receiver_scope TEXT NOT NULL,
            nonce TEXT NOT NULL,
            expected_peer_id TEXT NOT NULL DEFAULT '',
            expected_ip TEXT NOT NULL DEFAULT '',
            expected_port INTEGER NOT NULL DEFAULT 0,
            expires_at REAL NOT NULL,
            PRIMARY KEY(receiver_scope, nonce)
        )""")
        conn.execute("""CREATE TABLE IF NOT EXISTS lan_peers (
            receiver_scope TEXT NOT NULL,
            peer_id TEXT NOT NULL,
            peer_type TEXT NOT NULL DEFAULT 'unknown',
            service_id TEXT NOT NULL DEFAULT '',
            protocol_version INTEGER NOT NULL DEFAULT 2,
            capabilities TEXT NOT NULL DEFAULT '',
            network_scope TEXT NOT NULL DEFAULT '',
            endpoint_ip TEXT NOT NULL DEFAULT '',
            endpoint_port INTEGER NOT NULL DEFAULT 0,
            last_seen_at REAL NOT NULL DEFAULT 0,
            last_verified_at REAL NOT NULL DEFAULT 0,
            lease_expires_at REAL NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'suspect',
            PRIMARY KEY(receiver_scope, peer_id)
        )""")
        row = conn.execute("""SELECT expected_peer_id,expected_ip,expected_port,expires_at
            FROM lan_peer_challenges WHERE receiver_scope=? AND nonce=?""",
            (receiver_scope, str(nonce))).fetchone()
        if row is None:
            _safe_rollback(conn)
            return False
        expected_peer_id, expected_ip, expected_port, expires_at = row
        if (float(expires_at) <= now
                or (expected_peer_id and str(expected_peer_id) != str(peer_id))
                or (expected_ip and str(expected_ip) != str(endpoint_ip))
                or (expected_port and int(expected_port) != int(endpoint_port))):
            _safe_rollback(conn)
            return False
        # Validation, route activation, and directed nonce consumption share one
        # write transaction. Concurrent replay cannot race between SELECT/DELETE.
        conn.execute("""INSERT INTO lan_peers
            (receiver_scope,peer_id,peer_type,service_id,protocol_version,
             endpoint_ip,endpoint_port,last_seen_at,last_verified_at,lease_expires_at,status)
            VALUES(?,?,?,?,2,?,?,?,?,?,'active')
            ON CONFLICT(receiver_scope,peer_id) DO UPDATE SET
                peer_type=excluded.peer_type,
                service_id=excluded.service_id,
                endpoint_ip=excluded.endpoint_ip,
                endpoint_port=excluded.endpoint_port,
                last_seen_at=excluded.last_seen_at,
                last_verified_at=excluded.last_verified_at,
                lease_expires_at=excluded.lease_expires_at,
                status='active'""",
            (receiver_scope, str(peer_id), peer_type or "unknown", service_id or "",
             str(endpoint_ip), int(endpoint_port), now, now, now + 120.0))
        if expected_peer_id:
            conn.execute("DELETE FROM lan_peer_challenges WHERE receiver_scope=? AND nonce=?",
                         (receiver_scope, str(nonce)))
        conn.commit()
        return True
    except Exception:
        if conn is not None:
            try:
                _safe_rollback(conn)
            except Exception:
                pass
        return False
    finally:
        if conn is not None:
            _safe_close(conn)


def send_directed_peer_challenge(sock, db_path, receiver_scope, sender_peer_id,
                                 sender_peer_type, service_id, secret, listen_port,
                                 target_peer_id, target_ip, target_port, ttl_seconds=5):
    """Challenge one observed route; only its matching reply can activate it."""
    import uuid
    if (sock is None or not target_peer_id or not target_ip
            or int(target_port or 0) not in (8887, 8888, 8889, 8890)):
        return False
    nonce = uuid.uuid4().hex
    if not remember_peer_challenge(db_path, receiver_scope, nonce,
                                   target_peer_id, target_ip, target_port, ttl_seconds):
        return False
    packet = {
        "type": "peer.hello",
        "sender_peer_id": str(sender_peer_id),
        "sender_peer_type": str(sender_peer_type or "unknown"),
        "service_id": str(service_id),
        "nonce": nonce,
        "listen_port": int(listen_port),
        "network_scope": "",
        "capabilities": ["chat.text", "chat.ack"],
        "challenge_only": True,
    }
    try:
        sock.sendto(encode_packet(packet, secret), (str(target_ip), int(target_port)))
        return True
    except Exception:
        return False


def send_startup_peer_beacon(sock, peer_id, peer_type, service_id, secret,
                             listen_port, target_ports, broadcast_addresses=None,
                             db_path=None, receiver_scope=None):
    """Send one authenticated HELLO burst after a service listener binds; no background scanner."""
    import uuid
    if sock is None or not peer_id or not service_id:
        return 0
    ports = sorted(set(int(port) for port in (target_ports or [])
                       if 1 <= int(port) <= 65535))
    addresses = list(dict.fromkeys(
        str(address) for address in (broadcast_addresses or ["255.255.255.255"]) if address
    ))
    if not ports or not addresses:
        return 0
    nonce = uuid.uuid4().hex
    packet = {
        "type": "peer.hello",
        "sender_peer_id": str(peer_id),
        "sender_peer_type": str(peer_type or "unknown"),
        "service_id": str(service_id),
        "nonce": nonce,
        "listen_port": int(listen_port),
        "network_scope": "",
        "capabilities": ["chat.text", "chat.ack"],
        "challenge_only": False,
    }
    try:
        raw = encode_packet(packet, secret)
    except Exception:
        return 0
    # A peer.ack can activate a route only if its nonce was durably recorded.
    # Never emit a challenge that this process cannot later authenticate.
    if db_path and receiver_scope:
        if not remember_peer_challenge(db_path, receiver_scope, nonce, ttl_seconds=5):
            return 0
    sent = 0
    for address in addresses:
        for port in ports:
            try:
                sock.sendto(raw, (address, port))
                sent += 1
            except OSError:
                continue
    return sent


def begin_outgoing_discovery_window(db_path, sender_scope, message_id,
                                    window_ms=FIRST_DISCOVERY_WINDOW_MS):
    """Start an empty-recipient discovery deadline on first actual UDP dispatch."""
    if not db_path or not sender_scope or not message_id:
        return False
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute(
            "SELECT state,delivery_deadline_ms FROM lan_outbox "
            "WHERE sender_scope=? AND message_id=?",
            (sender_scope, message_id),
        ).fetchone()
        if row is None or row[0] != "discovery_pending":
            _safe_rollback(conn)
            return False
        now_ms = int(time.time() * 1000)
        deadline = int(row[1] or 0)
        if deadline > 0:
            if now_ms >= deadline:
                _finalize_expired_discovery_windows(conn, sender_scope, now_ms)
                conn.commit()
                return False
            conn.commit()
            return True  # Already open; never extend the original window.
        bounded_window = max(1000, min(30000, int(window_ms)))
        conn.execute(
            "UPDATE lan_outbox SET delivery_deadline_ms=? "
            "WHERE sender_scope=? AND message_id=? AND state='discovery_pending' "
            "AND delivery_deadline_ms=0",
            (now_ms + bounded_window, sender_scope, message_id),
        )
        conn.commit()
        return True
    except Exception as exc:
        if conn is not None:
            _safe_rollback(conn)
        raise LanP2PStorageError("failed to start LAN discovery window") from exc
    finally:
        if conn is not None:
            _safe_close(conn)


def is_outgoing_discovery_pending(db_path, sender_scope, message_id):
    """Return true only while this message's bounded first-discovery ACK window is open."""
    if not db_path or not sender_scope or not message_id:
        return False
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        _finalize_expired_discovery_windows(conn, sender_scope, int(time.time() * 1000))
        conn.commit()
        row = conn.execute("""SELECT state,delivery_deadline_ms FROM lan_outbox
            WHERE sender_scope=? AND message_id=?""",
            (sender_scope, message_id)).fetchone()
        if row is None or row[0] != "discovery_pending":
            return False
        deadline = int(row[1] or 0)
        return deadline <= 0 or deadline > int(time.time() * 1000)
    except Exception:
        return False
    finally:
        if conn is not None:
            _safe_close(conn)


def load_active_pending_targets(db_path, sender_scope, message_id, limit=500):
    """Load the active, unacked fixed targets for one durable outbox message in one query."""
    if not db_path or not sender_scope or not message_id:
        return []
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        rows = conn.execute("""SELECT t.peer_id,p.endpoint_ip,p.endpoint_port
            FROM lan_outbox o
            JOIN lan_outbox_targets t ON t.message_id=o.message_id
            JOIN lan_peers p ON p.receiver_scope=? AND p.peer_id=t.peer_id
            WHERE o.sender_scope=? AND o.message_id=?
              AND o.state NOT IN ('delivered','expired','waiting_peers')
              AND t.status!='acked' AND t.attempt_count<?
              AND p.status='active' AND p.lease_expires_at>?
            ORDER BY t.peer_id LIMIT ?""",
            (sender_scope, sender_scope, message_id, MAX_OUTBOX_TARGET_ATTEMPTS,
             time.time(), max(1, min(1000, int(limit))))).fetchall()
        return [{"peer_id": row[0], "endpoint_ip": row[1],
                 "endpoint_port": row[2]} for row in rows]
    except Exception as exc:
        raise LanP2PStorageError("failed to load active LAN outbox targets") from exc
    finally:
        if conn is not None:
            _safe_close(conn)



def load_pending_fixed_targets(db_path, sender_scope, message_id, limit=500):
    """Load all immutable unacked recipients with persisted endpoints, regardless of lease state."""
    if not db_path or not sender_scope or not message_id:
        return []
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        rows = conn.execute("""SELECT t.peer_id,COALESCE(p.endpoint_ip,''),COALESCE(p.endpoint_port,0),
                    COALESCE(p.status,'unknown')
            FROM lan_outbox o
            JOIN lan_outbox_targets t ON t.message_id=o.message_id
            LEFT JOIN lan_peers p ON p.receiver_scope=? AND p.peer_id=t.peer_id
            WHERE o.sender_scope=? AND o.message_id=?
              AND o.state NOT IN ('delivered','expired','waiting_peers')
              AND t.status!='acked' AND t.attempt_count<?
            ORDER BY t.peer_id LIMIT ?""",
            (sender_scope, sender_scope, message_id, MAX_OUTBOX_TARGET_ATTEMPTS,
             max(1, min(1000, int(limit))))).fetchall()
        return [{"peer_id": row[0], "endpoint_ip": row[1],
                 "endpoint_port": row[2], "peer_status": row[3]} for row in rows]
    except Exception as exc:
        raise LanP2PStorageError("failed to load fixed LAN outbox targets") from exc
    finally:
        if conn is not None:
            _safe_close(conn)



def send_outbox_to_active_peers(sock, db_path, sender_scope, message_id,
                                packet_template, secret, send_lock=None,
                                attempts=3, retry_delay_seconds=0.25):
    """Send to all fixed targets with persisted endpoints, even if their lease is suspect or expired."""
    if sock is None or not db_path or not sender_scope or not message_id:
        return 0
    max_attempts = max(1, min(3, int(attempts)))
    delay = max(0.0, min(1.0, float(retry_delay_seconds)))
    sent_total = 0
    for attempt in range(max_attempts):
        targets = load_pending_fixed_targets(db_path, sender_scope, message_id)
        if not targets:
            break
        for target in targets:
            packet = dict(packet_template or {})
            packet["target_peer_id"] = target["peer_id"]
            destination = (target["endpoint_ip"], int(target["endpoint_port"]))
            try:
                raw = encode_packet(packet, secret)
                # Commit the retry attempt before network I/O. A ledger failure
                # must fail closed; a crash after send must not erase the attempt.
                if not mark_outgoing_attempt(db_path, message_id, target["peer_id"],
                                             destination[0], destination[1]):
                    continue
                if send_lock is None:
                    sock.sendto(raw, destination)
                else:
                    with send_lock:
                        sock.sendto(raw, destination)
                sent_total += 1
            except LanP2PStorageError:
                raise
            except Exception:
                continue
        if attempt + 1 < max_attempts and delay > 0:
            # Recheck once per round: do not sleep/retry when every target ACKed.
            if load_pending_fixed_targets(db_path, sender_scope, message_id):
                time.sleep(delay)
    return sent_total



def send_broadcast_packet_with_retries(sock, packet_bytes, destinations,
                                       send_lock=None, attempts=3,
                                       retry_delay_seconds=0.25,
                                       should_continue=None):
    """Bounded broadcast; an optional durable gate is rechecked before every send."""
    if sock is None or not packet_bytes:
        return 0
    max_attempts = max(1, min(3, int(attempts)))
    delay = max(0.0, min(1.0, float(retry_delay_seconds)))
    targets = []
    for destination in (destinations or []):
        if not isinstance(destination, (tuple, list)) or len(destination) != 2:
            continue
        ip, port = str(destination[0]), int(destination[1])
        if ip and 1 <= port <= 65535:
            targets.append((ip, port))
    targets = list(dict.fromkeys(targets))
    sent_total = 0
    for attempt in range(max_attempts):
        if should_continue is not None and not should_continue():
            break
        for destination in targets:
            # The first-discovery window may expire during a retry round. Recheck
            # before each destination so no later peer receives an out-of-window replay.
            if should_continue is not None and not should_continue():
                break
            try:
                if send_lock is None:
                    sock.sendto(packet_bytes, destination)
                else:
                    with send_lock:
                        sock.sendto(packet_bytes, destination)
                sent_total += 1
            except OSError:
                continue
        if attempt + 1 < max_attempts and delay > 0:
            if should_continue is not None and not should_continue():
                break
            time.sleep(delay)
    return sent_total


def peer_endpoint_matches(db_path, receiver_scope, peer_id, endpoint_ip, endpoint_port):
    """Return true only when an ACK comes from the currently recorded route for its target peer."""
    if not receiver_scope or not peer_id or not endpoint_ip or not endpoint_port:
        return False
    conn = None
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        conn.execute("PRAGMA busy_timeout=10000")
        row = conn.execute("""SELECT endpoint_ip,endpoint_port,lease_expires_at,status
            FROM lan_peers WHERE receiver_scope=? AND peer_id=?""",
            (receiver_scope, peer_id)).fetchone()
        return bool(row and str(row[0]) == str(endpoint_ip)
                    and int(row[1]) == int(endpoint_port)
                    and float(row[2]) > time.time()
                    and str(row[3]) == "active")
    except Exception:
        return False
    finally:
        if conn is not None:
            _safe_close(conn)


def outgoing_ack_endpoint_matches(db_path, sender_scope, message_id, peer_id,
                                 endpoint_ip, endpoint_port):
    """Match an ACK to the exact endpoint attempted for this fixed message target."""
    if not db_path or not sender_scope or not message_id or not peer_id or not endpoint_ip:
        return False
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        try:
            conn.execute("PRAGMA busy_timeout=10000")
            row = conn.execute("""SELECT 1 FROM lan_outbox o
                JOIN lan_outbox_targets t ON t.message_id=o.message_id
                WHERE o.sender_scope=? AND o.message_id=? AND t.peer_id=?
                  AND o.state NOT IN ('expired','waiting_peers')
                  AND t.status='sent' AND t.attempt_count>0
                  AND t.last_endpoint=? LIMIT 1""",
                (sender_scope, message_id, peer_id,
                 str(endpoint_ip) + ":" + str(int(endpoint_port)))).fetchone()
            return row is not None
        finally:
            _safe_close(conn)
    except Exception:
        return False
