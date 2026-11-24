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
    ChunkDescriptor, TransferState, TransferTransitionError, TransferValidationError,
)
from lan_p2p_transfer_files import TransferFilesystem
from lan_p2p_transfer_store import TransferStore
from lan_p2p_transfer_tcp import TransferFrame, recv_frame, send_frame


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
    while handled < max_frames:
        try:
            frame = recv_frame(
                sock, session_secret, max_payload_bytes,
                expected_sender_peer_id=authenticated_peer_id,
                expected_target_peer_id=local_peer_id,
            )
        except EOFError:
            return handled
        record = store.get_transfer(frame.transfer_id)
        if record is None:
            raise TransferValidationError("unknown transfer task")
        if (record.offer.sender_peer_id != authenticated_peer_id
                or record.offer.target_peer_id != local_peer_id
                or frame.sender_peer_id != record.offer.sender_peer_id
                or frame.target_peer_id != record.offer.target_peer_id):
            raise TransferValidationError("frame identity does not match fixed transfer participants")

        response_kind = frame.kind
        response_index = None
        response_payload = b""
        response_metadata = None
        timestamp = now_ms()

        if frame.kind == "chunk":
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
            response_kind = "missing"
            response_metadata = {"indexes": list(filesystem.reconcile(frame.transfer_id, timestamp))}
        elif frame.kind == "complete":
            if frame.payload or frame.index is not None:
                raise TransferValidationError("invalid completion request")
            missing = filesystem.reconcile(frame.transfer_id, timestamp)
            if missing:
                response_kind = "missing"
                response_metadata = {"indexes": list(missing)}
            else:
                filesystem.assemble_and_verify(frame.transfer_id, timestamp)
                response_kind = "complete"
                response_metadata = {"state": TransferState.COMPLETED.value}
        elif frame.kind == "cancel":
            if frame.payload or frame.index is not None:
                raise TransferValidationError("invalid cancellation request")
            if record.state not in (TransferState.COMPLETED, TransferState.REJECTED,
                                    TransferState.CANCELLED, TransferState.FAILED,
                                    TransferState.EXPIRED):
                store.transition(frame.transfer_id, TransferState.CANCELLED, timestamp)
            response_kind = "cancel"
            response_metadata = {"state": TransferState.CANCELLED.value}
        else:
            raise TransferValidationError("unexpected client frame kind")

        send_frame(sock, TransferFrame(
            response_kind, frame.transfer_id, local_peer_id,
            authenticated_peer_id, index=response_index,
            payload=response_payload, metadata=response_metadata,
        ), session_secret, max_payload_bytes)
        handled += 1
    return handled
