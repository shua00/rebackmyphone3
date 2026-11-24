"""SQLite metadata and verified-chunk ledger for isolated LAN transfers.

The caller must write and fsync chunk bytes to an app-owned temporary file before
record_verified_chunk. After restart, revalidate those files against this ledger.
"""
from __future__ import annotations
from dataclasses import dataclass
import re
import sqlite3
from typing import Optional
from lan_p2p_transfer import (
    ChunkDescriptor, TransferLimits, TransferOffer, TransferState,
    TransferTransitionError, TransferValidationError, chunk_count,
    missing_chunk_indexes, transition_state, validate_chunk, validate_offer,
)

@dataclass(frozen=True)
class TransferRecord:
    offer: TransferOffer
    state: TransferState
    chunk_size_bytes: int
    total_chunks: int
    updated_at_ms: int

class TransferStore:
    def __init__(self, database_path: str, limits: TransferLimits = TransferLimits()):
        if not isinstance(database_path, str) or not database_path:
            raise TransferValidationError("database_path is required")
        self.database_path, self.limits = database_path, limits
        self._initialize()

    def _connect(self):
        conn = sqlite3.connect(self.database_path, timeout=10.0, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=10000")
        return conn

    def _initialize(self):
        conn = self._connect()
        try:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS lan_transfer_tasks (
                    transfer_id TEXT PRIMARY KEY, sender_peer_id TEXT NOT NULL,
                    target_peer_id TEXT NOT NULL, filename TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
                    whole_sha256 TEXT NOT NULL, created_at_ms INTEGER NOT NULL CHECK(created_at_ms >= 0),
                    mime_type TEXT NOT NULL, state TEXT NOT NULL,
                    chunk_size_bytes INTEGER NOT NULL CHECK(chunk_size_bytes > 0),
                    total_chunks INTEGER NOT NULL CHECK(total_chunks >= 0),
                    updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms >= 0));
                CREATE TABLE IF NOT EXISTS lan_transfer_chunks (
                    transfer_id TEXT NOT NULL, chunk_index INTEGER NOT NULL CHECK(chunk_index >= 0),
                    payload_size INTEGER NOT NULL CHECK(payload_size >= 0),
                    chunk_sha256 TEXT NOT NULL, recorded_at_ms INTEGER NOT NULL CHECK(recorded_at_ms >= 0),
                    PRIMARY KEY(transfer_id, chunk_index),
                    FOREIGN KEY(transfer_id) REFERENCES lan_transfer_tasks(transfer_id) ON DELETE CASCADE);
                CREATE INDEX IF NOT EXISTS idx_lan_transfer_tasks_state
                    ON lan_transfer_tasks(state, updated_at_ms);
            """)
        finally:
            conn.close()

    @staticmethod
    def _offer_values(o):
        return (o.transfer_id,o.sender_peer_id,o.target_peer_id,o.filename,o.size_bytes,
                o.sha256,o.created_at_ms,o.mime_type)

    @staticmethod
    def _record(row):
        offer=TransferOffer(row["transfer_id"],row["sender_peer_id"],row["target_peer_id"],
            row["filename"],row["size_bytes"],row["whole_sha256"],row["created_at_ms"],row["mime_type"])
        return TransferRecord(offer,TransferState(row["state"]),row["chunk_size_bytes"],
                              row["total_chunks"],row["updated_at_ms"])

    @staticmethod
    def _check_time(value):
        if isinstance(value,bool) or not isinstance(value,int) or value<0:
            raise TransferValidationError("timestamp must be a non-negative integer")

    def create_offer(self, offer: TransferOffer, now_ms: int) -> bool:
        validate_offer(offer,self.limits)
        self._check_time(now_ms)
        size=self.limits.chunk_size_bytes
        total=chunk_count(offer.size_bytes,size)
        conn=self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            row=conn.execute("SELECT * FROM lan_transfer_tasks WHERE transfer_id=?",
                             (offer.transfer_id,)).fetchone()
            if row is not None:
                if self._offer_values(self._record(row).offer)!=self._offer_values(offer):
                    raise TransferValidationError("transfer_id reused with different metadata")
                conn.commit()
                return False
            active=conn.execute("SELECT COUNT(*) FROM lan_transfer_tasks WHERE state NOT IN "
                "('completed','rejected','cancelled','failed','expired')").fetchone()[0]
            if active>=self.limits.max_active_transfers:
                raise TransferValidationError("active transfer limit reached")
            conn.execute("""INSERT INTO lan_transfer_tasks
                (transfer_id,sender_peer_id,target_peer_id,filename,size_bytes,whole_sha256,
                 created_at_ms,mime_type,state,chunk_size_bytes,total_chunks,updated_at_ms)
                 VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                self._offer_values(offer)+(TransferState.OFFERED.value,size,total,now_ms))
            conn.commit()
            return True
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def get_transfer(self, transfer_id: str) -> Optional[TransferRecord]:
        conn=self._connect()
        try:
            row=conn.execute("SELECT * FROM lan_transfer_tasks WHERE transfer_id=?",
                             (transfer_id,)).fetchone()
            return self._record(row) if row is not None else None
        finally:
            conn.close()

    def transition(self, transfer_id: str, target: TransferState, now_ms: int):
        self._check_time(now_ms)
        conn=self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            row=conn.execute("SELECT * FROM lan_transfer_tasks WHERE transfer_id=?",
                             (transfer_id,)).fetchone()
            if row is None:
                raise TransferValidationError("unknown transfer_id")
            next_state=transition_state(TransferState(row["state"]),target)
            if next_state==TransferState.VERIFYING:
                count=conn.execute("SELECT COUNT(*) FROM lan_transfer_chunks WHERE transfer_id=?",
                                   (transfer_id,)).fetchone()[0]
                if count!=row["total_chunks"]:
                    raise TransferTransitionError("cannot verify before every chunk is recorded")
            conn.execute("UPDATE lan_transfer_tasks SET state=?,updated_at_ms=? WHERE transfer_id=?",
                         (next_state.value,now_ms,transfer_id))
            conn.commit()
            return next_state
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def record_verified_chunk(self, descriptor: ChunkDescriptor, now_ms: int) -> bool:
        """Record only after caller has durably written and fsynced corresponding bytes."""
        self._check_time(now_ms)
        if not isinstance(descriptor,ChunkDescriptor):
            raise TransferValidationError("invalid chunk descriptor")
        conn=self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            row=conn.execute("SELECT * FROM lan_transfer_tasks WHERE transfer_id=?",
                             (descriptor.transfer_id,)).fetchone()
            if row is None:
                raise TransferValidationError("unknown transfer_id")
            record=self._record(row)
            if record.state!=TransferState.TRANSFERRING:
                raise TransferTransitionError("chunks accepted only while transferring")
            limits=TransferLimits(max_file_bytes=self.limits.max_file_bytes,
                chunk_size_bytes=record.chunk_size_bytes,
                max_parallel_chunks=self.limits.max_parallel_chunks,
                max_active_transfers=self.limits.max_active_transfers,
                max_filename_utf8_bytes=self.limits.max_filename_utf8_bytes)
            validate_chunk(record.offer,descriptor,limits)
            old=conn.execute("SELECT payload_size,chunk_sha256 FROM lan_transfer_chunks "
                "WHERE transfer_id=? AND chunk_index=?",
                (descriptor.transfer_id,descriptor.index)).fetchone()
            size=len(descriptor.payload)
            if old is not None:
                if (old["payload_size"],old["chunk_sha256"])!=(size,descriptor.sha256):
                    raise TransferValidationError("chunk index replayed with different content")
                conn.commit()
                return False
            conn.execute("""INSERT INTO lan_transfer_chunks
                (transfer_id,chunk_index,payload_size,chunk_sha256,recorded_at_ms)
                VALUES (?,?,?,?,?)""",
                (descriptor.transfer_id,descriptor.index,size,descriptor.sha256,now_ms))
            conn.execute("UPDATE lan_transfer_tasks SET updated_at_ms=? WHERE transfer_id=?",
                         (now_ms,descriptor.transfer_id))
            conn.commit()
            return True
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def received_chunk_indexes(self, transfer_id: str) -> tuple[int,...]:
        conn=self._connect()
        try:
            row=conn.execute("SELECT total_chunks FROM lan_transfer_tasks WHERE transfer_id=?",
                             (transfer_id,)).fetchone()
            if row is None:
                raise TransferValidationError("unknown transfer_id")
            rows=conn.execute("SELECT chunk_index FROM lan_transfer_chunks WHERE transfer_id=? "
                              "ORDER BY chunk_index",(transfer_id,)).fetchall()
            return tuple(x["chunk_index"] for x in rows)
        finally:
            conn.close()

    def missing_chunks(self, transfer_id: str) -> tuple[int,...]:
        record=self.get_transfer(transfer_id)
        if record is None:
            raise TransferValidationError("unknown transfer_id")
        return missing_chunk_indexes(record.total_chunks,self.received_chunk_indexes(transfer_id))


    def remove_chunk_receipt(self, transfer_id: str, chunk_index: int, now_ms: int) -> bool:
        """Invalidate a receipt when restart reconciliation finds its file missing/corrupt."""
        self._check_time(now_ms)
        if isinstance(chunk_index, bool) or not isinstance(chunk_index, int) or chunk_index < 0:
            raise TransferValidationError("chunk index must be a non-negative integer")
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT state FROM lan_transfer_tasks WHERE transfer_id=?",
                               (transfer_id,)).fetchone()
            if row is None:
                raise TransferValidationError("unknown transfer_id")
            state = TransferState(row["state"])
            if state in (TransferState.COMPLETED, TransferState.REJECTED,
                         TransferState.CANCELLED, TransferState.FAILED, TransferState.EXPIRED):
                raise TransferTransitionError("cannot invalidate chunks for a terminal transfer")
            deleted = conn.execute(
                "DELETE FROM lan_transfer_chunks WHERE transfer_id=? AND chunk_index=?",
                (transfer_id, chunk_index)).rowcount
            if deleted and state == TransferState.VERIFYING:
                conn.execute("UPDATE lan_transfer_tasks SET state=?,updated_at_ms=? WHERE transfer_id=?",
                             (TransferState.TRANSFERRING.value, now_ms, transfer_id))
            else:
                conn.execute("UPDATE lan_transfer_tasks SET updated_at_ms=? WHERE transfer_id=?",
                             (now_ms, transfer_id))
            conn.commit()
            return deleted > 0
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def get_chunk_receipt(self, transfer_id: str, chunk_index: int):
        conn = self._connect()
        try:
            row = conn.execute(
                "SELECT payload_size,chunk_sha256 FROM lan_transfer_chunks "
                "WHERE transfer_id=? AND chunk_index=?", (transfer_id, chunk_index)
            ).fetchone()
            return dict(row) if row is not None else None
        finally:
            conn.close()

    def complete_after_verification(self, transfer_id: str, verified_whole_sha256: str, now_ms: int):
        """Complete only after caller hashes the assembled temporary file."""
        self._check_time(now_ms)
        if not isinstance(verified_whole_sha256,str) or not re.fullmatch(r"[0-9a-f]{64}",verified_whole_sha256):
            raise TransferValidationError("verified whole-file SHA-256 is malformed")
        conn=self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            row=conn.execute("SELECT * FROM lan_transfer_tasks WHERE transfer_id=?",
                             (transfer_id,)).fetchone()
            if row is None:
                raise TransferValidationError("unknown transfer_id")
            record=self._record(row)
            if record.state!=TransferState.VERIFYING:
                raise TransferTransitionError("transfer is not verifying")
            if verified_whole_sha256!=record.offer.sha256:
                raise TransferValidationError("whole-file SHA-256 mismatch")
            state=transition_state(record.state,TransferState.COMPLETED)
            conn.execute("UPDATE lan_transfer_tasks SET state=?,updated_at_ms=? WHERE transfer_id=?",
                         (state.value,now_ms,transfer_id))
            conn.commit()
            return state
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()
