"""Crash-safe filesystem staging for LAN transfer chunks.

Use only beneath an app-private directory. Chunk bytes are fsynced before
SQLite receipts are committed; every resume revalidates the staged bytes.
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import stat
import tempfile
import threading
from pathlib import Path

from lan_p2p_transfer import (
    ChunkDescriptor, TransferState, TransferTransitionError,
    TransferValidationError, expected_chunk_size,
)
from lan_p2p_transfer_store import TransferStore


class TransferFilesystem:
    def __init__(self, store: TransferStore, staging_root: str,
                 received_root: str | None = None, max_staging_bytes: int = 4 * 1024**3):
        if not isinstance(store, TransferStore):
            raise TransferValidationError("a TransferStore is required")
        if isinstance(max_staging_bytes, bool) or not isinstance(max_staging_bytes, int) or max_staging_bytes < 1:
            raise TransferValidationError("max_staging_bytes must be a positive integer")
        self.store = store
        self.max_staging_bytes = max_staging_bytes
        self._lock = threading.RLock()
        self.staging_root = self._prepare_root(staging_root)
        self.received_root = self._prepare_root(received_root or str(self.staging_root / "received"))

    @staticmethod
    def _prepare_root(value: str) -> Path:
        if not isinstance(value, str) or not value:
            raise TransferValidationError("filesystem root is required")
        path = Path(value).expanduser().absolute()
        path.mkdir(parents=True, exist_ok=True, mode=0o700)
        if path.is_symlink() or not path.is_dir():
            raise TransferValidationError("filesystem root must be a real directory")
        try:
            os.chmod(path, 0o700)
        except OSError:
            pass
        return path.resolve(strict=True)

    @staticmethod
    def _key(transfer_id: str) -> str:
        if not isinstance(transfer_id, str) or not transfer_id:
            raise TransferValidationError("transfer_id is required")
        return hashlib.sha256(transfer_id.encode("utf-8")).hexdigest()[:24]

    def _task_dir(self, transfer_id: str) -> Path:
        path = self.staging_root / self._key(transfer_id)
        if path.exists() and (path.is_symlink() or not path.is_dir()):
            raise TransferValidationError("transfer staging path is not a real directory")
        path.mkdir(exist_ok=True, mode=0o700)
        if path.is_symlink():
            raise TransferValidationError("transfer staging path cannot be a symlink")
        return path

    @staticmethod
    def _chunk_path(task_dir: Path, index: int) -> Path:
        if isinstance(index, bool) or not isinstance(index, int) or index < 0:
            raise TransferValidationError("chunk index must be non-negative")
        return task_dir / f"chunk-{index:08d}.part"

    @staticmethod
    def _fsync_dir(path: Path) -> None:
        fd = os.open(str(path), os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(fd)
        finally:
            os.close(fd)

    @staticmethod
    def _read_regular(path: Path, expected_size: int) -> bytes | None:
        try:
            fd = os.open(str(path), os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        except FileNotFoundError:
            return None
        except OSError as exc:
            raise TransferValidationError("chunk path cannot be opened safely") from exc
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_size != expected_size:
                return None
            parts, remaining = [], expected_size
            while remaining:
                part = os.read(fd, min(1024 * 1024, remaining))
                if not part:
                    return None
                parts.append(part)
                remaining -= len(part)
            if os.read(fd, 1):
                return None
            return b"".join(parts)
        finally:
            os.close(fd)

    def _used_bytes(self) -> int:
        total = 0
        for root, dirs, files in os.walk(self.staging_root, followlinks=False):
            dirs[:] = [name for name in dirs
                        if not (Path(root) / name).is_symlink()
                        and (Path(root) / name).resolve() != self.received_root]
            for name in files:
                path = Path(root) / name
                try:
                    info = path.lstat()
                except FileNotFoundError:
                    continue
                if stat.S_ISREG(info.st_mode):
                    total += info.st_size
        return total

    def write_chunk(self, descriptor: ChunkDescriptor, now_ms: int) -> bool:
        if not isinstance(descriptor, ChunkDescriptor):
            raise TransferValidationError("invalid chunk descriptor")
        self.store._check_time(now_ms)
        record = self.store.get_transfer(descriptor.transfer_id)
        if record is None:
            raise TransferValidationError("unknown transfer_id")
        if record.state != TransferState.TRANSFERRING:
            raise TransferTransitionError("chunks accepted only while transferring")
        expected = expected_chunk_size(record.offer.size_bytes, record.chunk_size_bytes, descriptor.index)
        if not isinstance(descriptor.payload, bytes) or len(descriptor.payload) != expected:
            raise TransferValidationError("chunk payload length is incorrect")
        digest = hashlib.sha256(descriptor.payload).hexdigest()
        if not isinstance(descriptor.sha256, str) or digest != descriptor.sha256:
            raise TransferValidationError("chunk SHA-256 mismatch")
        with self._lock:
            task_dir = self._task_dir(descriptor.transfer_id)
            final = self._chunk_path(task_dir, descriptor.index)
            if final.exists() or final.is_symlink():
                existing = self._read_regular(final, expected)
                if existing is None or hashlib.sha256(existing).hexdigest() != digest:
                    raise TransferValidationError("existing staged chunk conflicts with incoming content")
                return self.store.record_verified_chunk(descriptor, now_ms)
            if self._used_bytes() + len(descriptor.payload) > self.max_staging_bytes:
                raise TransferValidationError("staging disk quota exceeded")
            fd, temp_name = tempfile.mkstemp(prefix=".chunk-", suffix=".tmp", dir=str(task_dir))
            temp = Path(temp_name)
            try:
                with os.fdopen(fd, "wb") as output:
                    output.write(descriptor.payload)
                    output.flush()
                    os.fsync(output.fileno())
                try:
                    os.link(temp, final)
                except FileExistsError:
                    existing = self._read_regular(final, expected)
                    if existing is None or hashlib.sha256(existing).hexdigest() != digest:
                        raise TransferValidationError("concurrent chunk write conflicts")
                self._fsync_dir(task_dir)
            finally:
                try:
                    temp.unlink()
                except FileNotFoundError:
                    pass
            return self.store.record_verified_chunk(descriptor, now_ms)

    def reconcile_page(self, transfer_id: str, now_ms: int,
                       limit: int = 128) -> tuple[tuple[int, ...], bool]:
        """Revalidate receipts and return a bounded missing page.

        Memory use is O(limit), even for the 2 GiB / 4 KiB configuration
        (524,288 chunks). Re-querying from index zero after each sent batch is
        intentional: durable receipts make lost ACKs and reconnects idempotent.
        """
        self.store._check_time(now_ms)
        if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 256:
            raise TransferValidationError("missing page limit must be between 1 and 256")
        record = self.store.get_transfer(transfer_id)
        if record is None:
            raise TransferValidationError("unknown transfer_id")
        with self._lock:
            task_dir = self._task_dir(transfer_id)
            total = record.total_chunks
            missing: list[int] = []
            candidate = 0
            receipt_start = 0
            receipts = self.store.load_chunk_receipts_page(transfer_id, receipt_start, 128)
            offset = 0
            while candidate < total:
                if offset >= len(receipts):
                    if len(receipts) == 128:
                        receipt_start = receipts[-1]["chunk_index"] + 1
                        receipts = self.store.load_chunk_receipts_page(
                            transfer_id, receipt_start, 128)
                        offset = 0
                    else:
                        while candidate < total and len(missing) < limit:
                            missing.append(candidate)
                            candidate += 1
                        return tuple(missing), candidate < total
                    if not receipts:
                        while candidate < total and len(missing) < limit:
                            missing.append(candidate)
                            candidate += 1
                        return tuple(missing), candidate < total

                receipt = receipts[offset]
                index = receipt["chunk_index"]
                if index > candidate:
                    missing.append(candidate)
                    candidate += 1
                    if len(missing) >= limit:
                        return tuple(missing), candidate < total
                    continue
                if index < candidate:
                    raise TransferValidationError("chunk receipt page is not strictly ordered")
                expected = expected_chunk_size(record.offer.size_bytes,
                                               record.chunk_size_bytes, index)
                try:
                    payload = self._read_regular(self._chunk_path(task_dir, index), expected)
                except TransferValidationError:
                    payload = None
                valid = (payload is not None
                         and receipt["payload_size"] == len(payload)
                         and receipt["chunk_sha256"] == hashlib.sha256(payload).hexdigest())
                if not valid:
                    # A missing receipt alone is insufficient: write_chunk refuses to
                    # overwrite an existing conflicting staged entry. Remove only this
                    # deterministic entry (unlink never follows a symlink), then durably
                    # record the receipt invalidation so a retransmitted chunk can repair it.
                    chunk_path = self._chunk_path(task_dir, index)
                    try:
                        chunk_path.unlink()
                    except FileNotFoundError:
                        pass
                    else:
                        self._fsync_dir(task_dir)
                    self.store.remove_chunk_receipt(transfer_id, index, now_ms)
                    missing.append(index)
                candidate += 1
                offset += 1
                if len(missing) >= limit:
                    return tuple(missing), candidate < total
            return tuple(missing), False

    def reconcile(self, transfer_id: str, now_ms: int) -> tuple[int, ...]:
        record = self.store.get_transfer(transfer_id)
        if record is None:
            raise TransferValidationError("unknown transfer_id")
        with self._lock:
            task_dir = self._task_dir(transfer_id)
            for index in self.store.received_chunk_indexes(transfer_id):
                expected = expected_chunk_size(record.offer.size_bytes, record.chunk_size_bytes, index)
                try:
                    payload = self._read_regular(self._chunk_path(task_dir, index), expected)
                except TransferValidationError:
                    payload = None
                receipt = self.store.get_chunk_receipt(transfer_id, index)
                if (payload is None or receipt is None or
                    receipt["payload_size"] != len(payload) or
                    receipt["chunk_sha256"] != hashlib.sha256(payload).hexdigest()):
                    self.store.remove_chunk_receipt(transfer_id, index, now_ms)
            return self.store.missing_chunks(transfer_id)

    def assemble_and_verify(self, transfer_id: str, now_ms: int) -> Path:
        with self._lock:
            if self.reconcile(transfer_id, now_ms):
                raise TransferTransitionError("cannot assemble while chunks are missing")
            record = self.store.get_transfer(transfer_id)
            if record.state == TransferState.TRANSFERRING:
                self.store.transition(transfer_id, TransferState.VERIFYING, now_ms)
            elif record.state != TransferState.VERIFYING:
                raise TransferTransitionError("transfer is not ready for verification")
            task_dir = self._task_dir(transfer_id)
            digest = hashlib.sha256()
            fd, temp_name = tempfile.mkstemp(prefix=".assembled-", suffix=".tmp",
                                               dir=str(self.received_root))
            temp = Path(temp_name)
            try:
                with os.fdopen(fd, "wb") as output:
                    for index in range(record.total_chunks):
                        expected = expected_chunk_size(record.offer.size_bytes,
                                                        record.chunk_size_bytes, index)
                        payload = self._read_regular(self._chunk_path(task_dir, index), expected)
                        if payload is None:
                            raise TransferValidationError("chunk disappeared during assembly")
                        digest.update(payload)
                        output.write(payload)
                    output.flush()
                    os.fsync(output.fileno())
                actual = digest.hexdigest()
                if actual != record.offer.sha256:
                    self.store.transition(transfer_id, TransferState.TRANSFERRING, now_ms)
                    raise TransferValidationError("assembled whole-file SHA-256 mismatch")
                safe_name = re.sub(r"[^A-Za-z0-9._-]+", "_", record.offer.filename).strip("._")
                safe_name = (safe_name or "received-file")[:120]
                destination = self.received_root / f"{self._key(transfer_id)}-{safe_name}"
                os.replace(temp, destination)
                self._fsync_dir(self.received_root)
                self.store.complete_after_verification(transfer_id, actual, now_ms)
                return destination
            except Exception:
                try:
                    temp.unlink()
                except FileNotFoundError:
                    pass
                raise

    def cleanup(self, transfer_id: str) -> None:
        record = self.store.get_transfer(transfer_id)
        if record is None:
            return
        if record.state not in (TransferState.CANCELLED, TransferState.REJECTED,
                                TransferState.FAILED, TransferState.EXPIRED,
                                TransferState.COMPLETED):
            raise TransferTransitionError("cannot clean up an active transfer")
        with self._lock:
            task_dir = self.staging_root / self._key(transfer_id)
            if task_dir.is_symlink():
                raise TransferValidationError("refusing to follow a symlink during cleanup")
            if task_dir.exists():
                shutil.rmtree(task_dir)
                self._fsync_dir(self.staging_root)
