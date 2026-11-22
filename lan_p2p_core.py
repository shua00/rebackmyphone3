"""Shared, dependency-free persistence primitives for LAN P2P services."""
import sqlite3
import time


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
                        ELSE lan_peers.last_verified_at END,
                    lease_expires_at=excluded.lease_expires_at,
                    status=CASE
                        WHEN lan_peers.status='active' AND excluded.status='suspect'
                        THEN lan_peers.status ELSE excluded.status END""",
                (receiver_scope, str(peer_id), peer_type or "unknown", service_id or "",
                 endpoint_ip or "", int(endpoint_port or 0), now,
                 now if status == "active" else 0.0, now + 120.0, status))
            conn.commit()
            return True
        finally:
            conn.close()
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
        conn.rollback()
        raise
    finally:
        conn.close()


def _canonical_envelope_bytes(fields):
    import json
    return json.dumps(fields, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def _envelope_key(secret):
    import hashlib
    if not isinstance(secret, str) or not secret:
        raise ValueError("shared secret is required")
    return hashlib.sha256(b"lan-p2p-v2\x00" + secret.encode("utf-8")).digest()


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
    if not isinstance(packet.get("sender_peer_id"), str) or not packet["sender_peer_id"]:
        raise ValueError("missing sender peer identity")
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


def persist_outgoing_message(db_path, sender_scope, sender_peer_id, message_id,
                             body, target_peer_ids, created_at_ms=None):
    """Atomically persist a Python-originated chat message and its fixed recipient snapshot."""
    if not sender_scope or not sender_peer_id or not message_id or body is None:
        return False
    now_ms = int(created_at_ms if created_at_ms is not None else time.time() * 1000)
    targets = sorted(set(str(peer) for peer in (target_peer_ids or []) if peer))
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
            PRIMARY KEY(message_id, peer_id)
        )""")
        conn.execute("BEGIN IMMEDIATE")
        conn.execute("""INSERT OR IGNORE INTO lan_outbox
            (message_id,sender_scope,sender_peer_id,message_type,body,created_at_ms,
             protocol_version,state,delivery_deadline_ms)
            VALUES(?,?,?,'chat',?,?,2,?,0)""",
            (message_id, sender_scope, sender_peer_id, str(body), now_ms,
             "pending" if targets else "discovery_pending"))
        for peer_id in targets:
            conn.execute("""INSERT OR IGNORE INTO lan_outbox_targets
                (message_id,peer_id,status) VALUES(?,?,'pending')""", (message_id, peer_id))
        if targets:
            conn.execute("UPDATE lan_outbox SET state='pending' WHERE message_id=? AND state='discovery_pending'",
                         (message_id,))
        conn.commit()
        return True
    except Exception:
        conn.rollback()
        return False
    finally:
        conn.close()


def acknowledge_outgoing_message(db_path, sender_scope, message_id, peer_id):
    """Persist exactly one recipient ACK; unknown/non-target ACKs never complete an outbox row."""
    if not sender_scope or not message_id or not peer_id:
        return False
    conn = sqlite3.connect(db_path, timeout=10)
    try:
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT 1 FROM lan_outbox WHERE sender_scope=? AND message_id=?",
                           (sender_scope, message_id)).fetchone()
        if row is None:
            conn.rollback()
            return False
        now_ms = int(time.time() * 1000)
        cur = conn.execute("""UPDATE lan_outbox_targets
            SET status='acked',last_ack_at=?
            WHERE message_id=? AND peer_id=? AND status!='acked'""",
            (now_ms, message_id, peer_id))
        if cur.rowcount == 0:
            # With no known members at send time, authenticated ACKs populate the first bounded
            # discovery snapshot. Once the window closes, non-target ACKs are rejected.
            row = conn.execute("SELECT state,delivery_deadline_ms FROM lan_outbox WHERE sender_scope=? AND message_id=?",
                               (sender_scope, message_id)).fetchone()
            if row is None or row[0] != "discovery_pending":
                conn.rollback()
                return False
            deadline = int(row[1] or 0)
            if deadline and now_ms > deadline:
                conn.rollback()
                return False
            if not deadline:
                deadline = now_ms + 5000
                conn.execute("UPDATE lan_outbox SET delivery_deadline_ms=? WHERE message_id=?",
                             (deadline, message_id))
            conn.execute("""INSERT OR IGNORE INTO lan_outbox_targets
                (message_id,peer_id,status,last_ack_at) VALUES(?,?,'acked',?)""",
                (message_id, peer_id, now_ms))
        total, acked = conn.execute("""SELECT COUNT(*),
            SUM(CASE WHEN status='acked' THEN 1 ELSE 0 END)
            FROM lan_outbox_targets WHERE message_id=?""", (message_id,)).fetchone()
        row = conn.execute("SELECT state,delivery_deadline_ms FROM lan_outbox WHERE sender_scope=? AND message_id=?",
                           (sender_scope, message_id)).fetchone()
        discovery_open = bool(row and row[0] == "discovery_pending"
                              and row[1] and now_ms <= int(row[1]))
        if discovery_open:
            pass
        elif total > 0 and total == (acked or 0):
            conn.execute("UPDATE lan_outbox SET state='delivered' WHERE message_id=?", (message_id,))
        else:
            conn.execute("UPDATE lan_outbox SET state='partial' WHERE message_id=?", (message_id,))
        conn.commit()
        return True
    except Exception:
        conn.rollback()
        return False
    finally:
        conn.close()


def load_pending_outgoing_for_peer(db_path, sender_scope, peer_id, limit=10):
    """Load only unacked messages addressed to a peer whose current LAN lease is valid."""
    conn = sqlite3.connect(db_path, timeout=10)
    try:
        conn.execute("PRAGMA busy_timeout=10000")
        now = time.time()
        now_ms = int(now * 1000)
        # Close expired discovery snapshots: pending targets remain retryable; fully ACKed sets finish.
        conn.execute("""UPDATE lan_outbox SET state='delivered'
            WHERE sender_scope=? AND state='discovery_pending' AND delivery_deadline_ms>0
              AND delivery_deadline_ms<=?
              AND EXISTS (SELECT 1 FROM lan_outbox_targets t WHERE t.message_id=lan_outbox.message_id)
              AND NOT EXISTS (SELECT 1 FROM lan_outbox_targets t
                              WHERE t.message_id=lan_outbox.message_id AND t.status!='acked')""",
            (sender_scope, now_ms))
        conn.execute("""UPDATE lan_outbox SET state='pending'
            WHERE sender_scope=? AND state='discovery_pending' AND delivery_deadline_ms>0
              AND delivery_deadline_ms<=?
              AND EXISTS (SELECT 1 FROM lan_outbox_targets t WHERE t.message_id=lan_outbox.message_id)
              AND EXISTS (SELECT 1 FROM lan_outbox_targets t
                          WHERE t.message_id=lan_outbox.message_id AND t.status!='acked')""",
            (sender_scope, now_ms))
        conn.commit()
        rows = conn.execute("""SELECT o.message_id,o.sender_peer_id,o.body,o.created_at_ms,
                    p.endpoint_ip,p.endpoint_port
            FROM lan_outbox o
            JOIN lan_outbox_targets t ON t.message_id=o.message_id
            JOIN lan_peers p ON p.receiver_scope=? AND p.peer_id=t.peer_id
            WHERE o.sender_scope=? AND t.peer_id=? AND t.status!='acked'
              AND o.state NOT IN ('delivered','expired') AND p.lease_expires_at>?
            ORDER BY o.created_at_ms LIMIT ?""",
            (sender_scope, sender_scope, peer_id, now, max(1, min(50, int(limit))))).fetchall()
        return [{"message_id": r[0], "sender_peer_id": r[1], "body": r[2],
                 "created_at_ms": r[3], "endpoint_ip": r[4], "endpoint_port": r[5]}
                for r in rows]
    except Exception:
        return []
    finally:
        conn.close()


def mark_outgoing_attempt(db_path, message_id, peer_id, endpoint_ip, endpoint_port):
    conn = sqlite3.connect(db_path, timeout=10)
    try:
        conn.execute("PRAGMA busy_timeout=10000")
        cur = conn.execute("""UPDATE lan_outbox_targets SET status='sent',
            attempt_count=attempt_count+1,last_attempt_at=?,last_endpoint=?,last_error=''
            WHERE message_id=? AND peer_id=? AND status!='acked'""",
            (int(time.time() * 1000), str(endpoint_ip) + ":" + str(endpoint_port),
             message_id, peer_id))
        conn.commit()
        return cur.rowcount > 0
    except Exception:
        conn.rollback()
        return False
    finally:
        conn.close()


def list_known_peers(db_path, receiver_scope, limit=100):
    """Return currently leased peers for a service scope; endpoint is a temporary route."""
    conn = sqlite3.connect(db_path, timeout=10)
    try:
        conn.execute("PRAGMA busy_timeout=10000")
        rows = conn.execute("""SELECT peer_id,peer_type,service_id,endpoint_ip,endpoint_port,
                    last_seen_at,lease_expires_at,status
            FROM lan_peers WHERE receiver_scope=? AND lease_expires_at>?
              AND status IN ('active','suspect')
            ORDER BY last_seen_at DESC LIMIT ?""",
            (receiver_scope, time.time(), max(1, min(500, int(limit))))).fetchall()
        return [{"peer_id": r[0], "peer_type": r[1], "service_id": r[2],
                 "endpoint_ip": r[3], "endpoint_port": r[4],
                 "last_seen_at": r[5], "lease_expires_at": r[6], "status": r[7]}
                for r in rows]
    except Exception:
        return []
    finally:
        conn.close()


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
            conn.rollback()
            return False
        now_ms = int(time.time() * 1000)
        deadline = int(row[1] or 0)
        if deadline and now_ms > deadline:
            conn.rollback()
            return False
        if not deadline:
            deadline = now_ms + 5000
            conn.execute("UPDATE lan_outbox SET delivery_deadline_ms=? WHERE message_id=?",
                         (deadline, message_id))
        conn.execute("""INSERT OR IGNORE INTO lan_outbox_targets
            (message_id,peer_id,status) VALUES(?,?,'pending')""", (message_id, peer_id))
        conn.commit()
        return True
    except Exception:
        conn.rollback()
        return False
    finally:
        conn.close()


def add_discovery_targets_for_peer(db_path, sender_scope, peer_id):
    """Attach a newly discovered peer to every still-open unknown-recipient message."""
    conn = sqlite3.connect(db_path, timeout=10)
    try:
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("BEGIN IMMEDIATE")
        now_ms = int(time.time() * 1000)
        rows = conn.execute("""SELECT message_id,delivery_deadline_ms FROM lan_outbox
            WHERE sender_scope=? AND state='discovery_pending'""", (sender_scope,)).fetchall()
        added = 0
        for message_id, deadline_value in rows:
            deadline = int(deadline_value or 0)
            if deadline and now_ms > deadline:
                continue
            if not deadline:
                deadline = now_ms + 5000
                conn.execute("UPDATE lan_outbox SET delivery_deadline_ms=? WHERE message_id=?",
                             (deadline, message_id))
            cur = conn.execute("""INSERT OR IGNORE INTO lan_outbox_targets
                (message_id,peer_id,status) VALUES(?,?,'pending')""", (message_id, peer_id))
            added += max(0, cur.rowcount)
        conn.commit()
        return added
    except Exception:
        conn.rollback()
        return 0
    finally:
        conn.close()
