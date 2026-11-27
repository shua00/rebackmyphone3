"""Bounded TCP transfer session adapter joining framing, ledger and disk staging.

The caller owns socket creation, binding, timeout, peer challenge/session-key
establishment, admission control and lifecycle. This adapter only handles an
already authenticated peer connection and never touches the chat UDP path.
"""
from __future__ import annotations

import hashlib
import socket
from typing import Callable

from lan_p2p_transfer import (
    ChunkDescriptor, TransferOffer, TransferState, TransferTransitionError, TransferValidationError,
)
from lan_p2p_transfer_files import TransferFilesystem
from lan_p2p_transfer_store import TransferStore
from lan_p2p_transfer_tcp import (
    MAX_MISSING_INDEXES_PER_FRAME, TransferFrame, recv_frame, send_frame,
)


def serve_transfer_session(
    sock: socket.socket,
    store: TransferStore,
    filesystem: TransferFilesystem,
    *,
    local_peer_id: str,
    authenticated_peer_id: str,
    session_secret: bytes,
    now_ms: Callable[[], int],
    max_frames: int = 64,
    max_payload_bytes: int = 1024 * 1024,
) -> int:
    """Serve at most max_frames requests; socket timeout/admission are caller-owned."""
    if not isinstance(store, TransferStore) or not isinstance(filesystem, TransferFilesystem):
        raise TransferValidationError("transfer store and filesystem are required")
    if not callable(now_ms):
        raise TransferValidationError("now_ms must be a clock callback")
    if isinstance(max_frames, bool) or not isinstance(max_frames, int) or not 1 <= max_frames <= 256:
        raise TransferValidationError("max_frames must be between 1 and 256")
    if not isinstance(local_peer_id, str) or not local_peer_id:
        raise TransferValidationError("local peer identity is required")
    if not isinstance(authenticated_peer_id, str) or not authenticated_peer_id:
        raise TransferValidationError("authenticated peer identity is required")
    handled = 0
    missing_page_limit = min(64, MAX_MISSING_INDEXES_PER_FRAME)
    while handled < max_frames:
        try:
            frame = recv_frame(
                sock, session_secret, max_payload_bytes,
                expected_sender_peer_id=authenticated_peer_id,
                expected_target_peer_id=local_peer_id,
            )
        except EOFError:
            return handled
        response_kind = frame.kind
        response_index = None
        response_payload = b""
        response_metadata = None
        timestamp = now_ms()
        record = store.get_transfer(frame.transfer_id)

        if frame.kind == "offer":
            if frame.index is not None or frame.payload or not isinstance(frame.metadata, dict):
                raise TransferValidationError("malformed transfer Offer frame")
            if (frame.sender_peer_id != authenticated_peer_id
                    or frame.target_peer_id != local_peer_id):
                raise TransferValidationError("Offer participants do not match authenticated session")
            expected = {"filename", "size_bytes", "sha256", "created_at_ms",
                        "mime_type", "chunk_size_bytes"}
            if set(frame.metadata) != expected:
                raise TransferValidationError("invalid transfer Offer metadata")
            size = frame.metadata["size_bytes"]
            created = frame.metadata["created_at_ms"]
            chunk_size = frame.metadata["chunk_size_bytes"]
            if (isinstance(size, bool) or not isinstance(size, int)
                    or isinstance(created, bool) or not isinstance(created, int)
                    or isinstance(chunk_size, bool) or not isinstance(chunk_size, int)):
                raise TransferValidationError("Offer size, timestamp and chunk size must be integers")
            if chunk_size != store.limits.chunk_size_bytes:
                raise TransferValidationError("Offer chunk size does not match receiver configuration")
            if created > timestamp + 300_000 or created < timestamp - 7 * 24 * 60 * 60 * 1000:
                raise TransferValidationError("Offer is outside the accepted age window")
            offer = TransferOffer(
                frame.transfer_id, authenticated_peer_id, local_peer_id,
                frame.metadata["filename"], size, frame.metadata["sha256"],
                created, frame.metadata["mime_type"],
            )
            inserted = store.create_offer(offer, timestamp)
            record = store.get_transfer(frame.transfer_id)
            if record is None:
                raise TransferValidationError("Offer was not durably stored")
            response_kind = "offer_ack"
            response_metadata = {
                "state": record.state.value,
                "chunk_size_bytes": record.chunk_size_bytes,
                "total_chunks": record.total_chunks,
                "new_offer": bool(inserted),
            }
        else:
            if record is None:
                raise TransferValidationError("unknown transfer task")
            if (record.offer.sender_peer_id != authenticated_peer_id
                    or record.offer.target_peer_id != local_peer_id
                    or frame.sender_peer_id != record.offer.sender_peer_id
                    or frame.target_peer_id != record.offer.target_peer_id):
                raise TransferValidationError("frame identity does not match fixed transfer participants")

        if frame.kind == "offer":
            # The durable state remains OFFERED until the receiver explicitly accepts.
            pass
        elif frame.kind == "chunk":
            accepted = filesystem.write_chunk(
                ChunkDescriptor(frame.transfer_id, frame.index, frame.payload,
                                hashlib.sha256(frame.payload).hexdigest()),
                timestamp,
            )
            response_kind = "chunk_ack"
            response_metadata = {"index": frame.index, "new_receipt": bool(accepted)}
        elif frame.kind == "missing":
            if frame.payload or frame.index is not None:
                raise TransferValidationError("invalid missing request")
            if frame.metadata is None:
                page_limit = min(64, MAX_MISSING_INDEXES_PER_FRAME)
            elif (isinstance(frame.metadata, dict) and set(frame.metadata) == {"limit"}
                  and isinstance(frame.metadata.get("limit"), int)
                  and not isinstance(frame.metadata.get("limit"), bool)
                  and 1 <= frame.metadata["limit"] <= MAX_MISSING_INDEXES_PER_FRAME):
                page_limit = frame.metadata["limit"]
            else:
                raise TransferValidationError("invalid missing-page request")
            missing_page_limit = page_limit
            page, more = filesystem.reconcile_page(frame.transfer_id, timestamp, page_limit)
            response_kind = "missing"
            response_metadata = {"indexes": list(page), "more": more}
        elif frame.kind == "complete":
            if frame.payload or frame.index is not None:
                raise TransferValidationError("invalid completion request")
            if record.state == TransferState.COMPLETED:
                response_kind = "complete"
                response_metadata = {"state": TransferState.COMPLETED.value}
            elif record.state in (TransferState.TRANSFERRING, TransferState.VERIFYING):
                missing, more = filesystem.reconcile_page(
                    frame.transfer_id, timestamp, missing_page_limit)
                if missing or more:
                    response_kind = "missing"
                    response_metadata = {"indexes": list(missing), "more": more}
                else:
                    filesystem.assemble_and_verify(frame.transfer_id, timestamp)
                    response_kind = "complete"
                    response_metadata = {"state": TransferState.COMPLETED.value}
            else:
                raise TransferTransitionError("transfer is not ready to complete")
        elif frame.kind == "cancel":
            if frame.payload or frame.index is not None:
                raise TransferValidationError("invalid cancellation request")
            terminal = (TransferState.COMPLETED, TransferState.REJECTED,
                        TransferState.CANCELLED, TransferState.FAILED,
                        TransferState.EXPIRED)
            if record.state not in terminal:
                store.transition(frame.transfer_id, TransferState.CANCELLED, timestamp)
                response_state = TransferState.CANCELLED
            else:
                response_state = record.state
            # Match Android: cancellation ACK follows durable state change and
            # private staging cleanup; replayed cancellation remains idempotent.
            if response_state == TransferState.CANCELLED:
                filesystem.cleanup(frame.transfer_id)
            response_kind = "cancel"
            response_metadata = {"state": response_state.value}
        else:
            raise TransferValidationError("unexpected client frame kind")

        send_frame(sock, TransferFrame(
            response_kind, frame.transfer_id, local_peer_id,
            authenticated_peer_id, index=response_index,
            payload=response_payload, metadata=response_metadata,
        ), session_secret, max_payload_bytes)
        handled += 1
    return handled
