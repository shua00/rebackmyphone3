"""Resumable sender for an already accepted LAN transfer session.

Offer delivery/acceptance and TCP connection admission are intentionally owned
by higher layers. Reconnect and call this function again to query missing chunks.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
import socket
from typing import Callable

from lan_p2p_transfer import TransferOffer, TransferValidationError, validate_offer
from lan_p2p_transfer_tcp import TransferFrame, recv_frame, send_frame


def _hash_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        while True:
            block = source.read(1024 * 1024)
            if not block:
                break
            size += len(block)
            digest.update(block)
    return size, digest.hexdigest()


def send_file_over_session(
    sock: socket.socket,
    source_path: str,
    offer: TransferOffer,
    *,
    local_peer_id: str,
    remote_peer_id: str,
    session_secret: bytes,
    chunk_size_bytes: int,
    max_payload_bytes: int = 1024 * 1024,
    max_chunks_per_session: int = 256,
    progress: Callable[[int, int], None] | None = None,
) -> tuple[int, ...]:
    """Send currently missing chunks and request final verification.

    A reconnect repeats the missing query, so ACK loss is harmless. The caller
    supplies an accepted Offer and authenticated socket; this function never
    creates a listener, changes recipient snapshots, or touches chat queues.
    """
    validate_offer(offer)
    if offer.sender_peer_id != local_peer_id or offer.target_peer_id != remote_peer_id:
        raise TransferValidationError("offer participants do not match authenticated session")
    if isinstance(chunk_size_bytes, bool) or not isinstance(chunk_size_bytes, int) or not 4096 <= chunk_size_bytes <= 1024 * 1024:
        raise TransferValidationError("invalid negotiated chunk size")
    if isinstance(max_chunks_per_session, bool) or not isinstance(max_chunks_per_session, int) or not 1 <= max_chunks_per_session <= 4096:
        raise TransferValidationError("invalid per-session chunk budget")
    path = Path(source_path)
    if not path.is_file() or path.is_symlink():
        raise TransferValidationError("source must be a regular non-symlink file")
    before = path.stat()
    size, digest = _hash_file(path)
    if size != offer.size_bytes or digest != offer.sha256:
        raise TransferValidationError("source file does not match accepted offer")
    total = (size + chunk_size_bytes - 1) // chunk_size_bytes
    send_frame(sock, TransferFrame("missing", offer.transfer_id, local_peer_id,
                                   remote_peer_id), session_secret, max_payload_bytes)
    reply = recv_frame(sock, session_secret, max_payload_bytes,
                       expected_sender_peer_id=remote_peer_id,
                       expected_target_peer_id=local_peer_id)
    if reply.kind != "missing" or reply.transfer_id != offer.transfer_id:
        raise TransferValidationError("receiver did not return a missing-chunk list")
    indexes = reply.metadata.get("indexes") if isinstance(reply.metadata, dict) else None
    if not isinstance(indexes, list) or len(indexes) > total:
        raise TransferValidationError("receiver returned an invalid missing-chunk list")
    missing = []
    previous = -1
    for index in indexes:
        if isinstance(index, bool) or not isinstance(index, int) or not previous < index < total:
            raise TransferValidationError("receiver returned unordered or invalid chunk indexes")
        missing.append(index)
        previous = index
    # A large transfer may have more missing chunks than one connection is
    # allowed to carry. Send a bounded prefix; the completion response returns
    # the authoritative remainder for the next session.
    batch = missing[:max_chunks_per_session]
    sent = 0
    with path.open("rb") as source:
        for index in batch:
            source.seek(index * chunk_size_bytes)
            payload = source.read(min(chunk_size_bytes, size - index * chunk_size_bytes))
            if len(payload) != min(chunk_size_bytes, size - index * chunk_size_bytes):
                raise TransferValidationError("source changed while reading chunks")
            frame = TransferFrame("chunk", offer.transfer_id, local_peer_id,
                                  remote_peer_id, index=index, payload=payload)
            send_frame(sock, frame, session_secret, max_payload_bytes)
            ack = recv_frame(sock, session_secret, max_payload_bytes,
                             expected_sender_peer_id=remote_peer_id,
                             expected_target_peer_id=local_peer_id)
            if (ack.kind != "chunk_ack" or ack.transfer_id != offer.transfer_id
                    or not isinstance(ack.metadata, dict)
                    or ack.metadata.get("index") != index):
                raise TransferValidationError("receiver chunk acknowledgement mismatch")
            sent += 1
            if progress is not None:
                progress(sent, len(batch))
    after = path.stat()
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        raise TransferValidationError("source file changed during transfer")
    send_frame(sock, TransferFrame("complete", offer.transfer_id, local_peer_id,
                                   remote_peer_id), session_secret, max_payload_bytes)
    complete = recv_frame(sock, session_secret, max_payload_bytes,
                          expected_sender_peer_id=remote_peer_id,
                          expected_target_peer_id=local_peer_id)
    if complete.transfer_id != offer.transfer_id:
        raise TransferValidationError("completion response belongs to another transfer")
    if complete.kind == "missing":
        remaining = complete.metadata.get("indexes") if isinstance(complete.metadata, dict) else None
        if not isinstance(remaining, list):
            raise TransferValidationError("receiver returned malformed remaining chunks")
        return tuple(remaining)
    if complete.kind != "complete" or not isinstance(complete.metadata, dict) or complete.metadata.get("state") != "completed":
        raise TransferValidationError("receiver did not confirm verified completion")
    return ()
