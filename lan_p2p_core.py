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
