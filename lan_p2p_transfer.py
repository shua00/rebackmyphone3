"""Pure domain contract for LAN file transfers.

This module deliberately does not perform network or filesystem I/O. It defines
validation and state transitions shared by future Android/Python transports.
Chat messages remain on the existing small-datagram path.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import re
from typing import Iterable


_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_TRANSFER_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


class TransferValidationError(ValueError):
    """An offer or chunk violates the transfer contract."""


class TransferTransitionError(ValueError):
    """A transfer state transition is not allowed."""


class TransferState(str, Enum):
    OFFERED = "offered"
    ACCEPTED = "accepted"
    TRANSFERRING = "transferring"
    PAUSED = "paused"
    VERIFYING = "verifying"
    COMPLETED = "completed"
    REJECTED = "rejected"
    CANCELLED = "cancelled"
    FAILED = "failed"
    EXPIRED = "expired"


_TERMINAL_STATES = frozenset({
    TransferState.COMPLETED,
    TransferState.REJECTED,
    TransferState.CANCELLED,
    TransferState.FAILED,
    TransferState.EXPIRED,
})

_ALLOWED_TRANSITIONS = {
    TransferState.OFFERED: frozenset({
        TransferState.ACCEPTED, TransferState.REJECTED,
        TransferState.CANCELLED, TransferState.EXPIRED, TransferState.FAILED,
    }),
    TransferState.ACCEPTED: frozenset({
        TransferState.TRANSFERRING, TransferState.CANCELLED,
        TransferState.FAILED,
    }),
    TransferState.TRANSFERRING: frozenset({
        TransferState.PAUSED, TransferState.VERIFYING,
        TransferState.CANCELLED, TransferState.FAILED,
    }),
    TransferState.PAUSED: frozenset({
        TransferState.TRANSFERRING, TransferState.CANCELLED,
        TransferState.FAILED, TransferState.EXPIRED,
    }),
    TransferState.VERIFYING: frozenset({
        TransferState.TRANSFERRING, TransferState.COMPLETED,
        TransferState.CANCELLED, TransferState.FAILED,
    }),
}


@dataclass(frozen=True)
class TransferLimits:
    max_file_bytes: int = 2 * 1024 * 1024 * 1024
    chunk_size_bytes: int = 256 * 1024
    max_parallel_chunks: int = 4
    max_active_transfers: int = 4
    max_filename_utf8_bytes: int = 255

    def __post_init__(self) -> None:
        if self.max_file_bytes < 0:
            raise TransferValidationError("max_file_bytes must be non-negative")
        if not 4 * 1024 <= self.chunk_size_bytes <= 1024 * 1024:
            raise TransferValidationError("chunk size must be between 4 KiB and 1 MiB")
        if not 1 <= self.max_parallel_chunks <= 16:
            raise TransferValidationError("parallel chunk limit must be between 1 and 16")
        if not 1 <= self.max_active_transfers <= 32:
            raise TransferValidationError("active transfer limit must be between 1 and 32")
        if not 1 <= self.max_filename_utf8_bytes <= 1024:
            raise TransferValidationError("filename byte limit is invalid")


@dataclass(frozen=True)
class TransferOffer:
    transfer_id: str
    sender_peer_id: str
    target_peer_id: str
    filename: str
    size_bytes: int
    sha256: str
    created_at_ms: int
    mime_type: str = "application/octet-stream"


@dataclass(frozen=True)
class ChunkDescriptor:
    transfer_id: str
    index: int
    payload: bytes
    sha256: str


def validate_offer(offer: TransferOffer, limits: TransferLimits = TransferLimits()) -> None:
    """Validate metadata only; filename is display metadata, never a path."""
    if not isinstance(offer, TransferOffer):
        raise TransferValidationError("offer has an invalid type")
    if not isinstance(offer.transfer_id, str) or not _TRANSFER_ID_RE.fullmatch(offer.transfer_id):
        raise TransferValidationError("transfer_id is invalid")
    if not isinstance(offer.sender_peer_id, str) or not isinstance(offer.target_peer_id, str):
        raise TransferValidationError("sender and target peer IDs must be strings")
    if not offer.sender_peer_id or not offer.target_peer_id:
        raise TransferValidationError("sender and target peer IDs are required")
    if offer.sender_peer_id == offer.target_peer_id:
        raise TransferValidationError("sender and target must differ")
    if not isinstance(offer.filename, str) or not offer.filename:
        raise TransferValidationError("filename is required")
    if offer.filename in {".", ".."} or "/" in offer.filename or "\\" in offer.filename:
        raise TransferValidationError("filename must be a display name, not a path")
    if any(ord(char) < 32 or ord(char) == 127 for char in offer.filename):
        raise TransferValidationError("filename contains control characters")
    try:
        filename_bytes = offer.filename.encode("utf-8", "strict")
    except UnicodeError as exc:
        raise TransferValidationError("filename is not valid Unicode") from exc
    if len(filename_bytes) > limits.max_filename_utf8_bytes:
        raise TransferValidationError("filename is too long")
    if isinstance(offer.size_bytes, bool) or not isinstance(offer.size_bytes, int):
        raise TransferValidationError("size_bytes must be an integer")
    if not 0 <= offer.size_bytes <= limits.max_file_bytes:
        raise TransferValidationError("file size is outside configured limits")
    if not isinstance(offer.sha256, str) or not _SHA256_RE.fullmatch(offer.sha256):
        raise TransferValidationError("whole-file SHA-256 must be 64 lowercase hex characters")
    if isinstance(offer.created_at_ms, bool) or not isinstance(offer.created_at_ms, int) or offer.created_at_ms < 0:
        raise TransferValidationError("created_at_ms must be a non-negative integer")
    if not isinstance(offer.mime_type, str) or not offer.mime_type or len(offer.mime_type) > 127:
        raise TransferValidationError("mime_type is invalid")
    if any(ord(char) < 32 or ord(char) == 127 for char in offer.mime_type):
        raise TransferValidationError("mime_type contains control characters")


def chunk_count(size_bytes: int, chunk_size_bytes: int) -> int:
    if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes < 0:
        raise TransferValidationError("size_bytes must be a non-negative integer")
    if isinstance(chunk_size_bytes, bool) or not isinstance(chunk_size_bytes, int) or chunk_size_bytes <= 0:
        raise TransferValidationError("chunk_size_bytes must be a positive integer")
    return (size_bytes + chunk_size_bytes - 1) // chunk_size_bytes


def expected_chunk_size(size_bytes: int, chunk_size_bytes: int, index: int) -> int:
    count = chunk_count(size_bytes, chunk_size_bytes)
    if isinstance(index, bool) or not isinstance(index, int) or not 0 <= index < count:
        raise TransferValidationError("chunk index is outside the transfer")
    if index < count - 1:
        return chunk_size_bytes
    return size_bytes - index * chunk_size_bytes


def validate_chunk(
    offer: TransferOffer,
    descriptor: ChunkDescriptor,
    limits: TransferLimits = TransferLimits(),
) -> None:
    validate_offer(offer, limits)
    if not isinstance(descriptor, ChunkDescriptor) or descriptor.transfer_id != offer.transfer_id:
        raise TransferValidationError("chunk belongs to a different transfer")
    required_size = expected_chunk_size(
        offer.size_bytes, limits.chunk_size_bytes, descriptor.index
    )
    if not isinstance(descriptor.payload, bytes) or len(descriptor.payload) != required_size:
        raise TransferValidationError("chunk payload length is incorrect")
    if not isinstance(descriptor.sha256, str) or not _SHA256_RE.fullmatch(descriptor.sha256):
        raise TransferValidationError("chunk SHA-256 is malformed")
    actual = hashlib.sha256(descriptor.payload).hexdigest()
    if actual != descriptor.sha256:
        raise TransferValidationError("chunk SHA-256 mismatch")


def missing_chunk_indexes(total_chunks: int, received_indexes: Iterable[int]) -> tuple[int, ...]:
    if isinstance(total_chunks, bool) or not isinstance(total_chunks, int) or total_chunks < 0:
        raise TransferValidationError("total_chunks must be a non-negative integer")
    received: set[int] = set()
    for index in received_indexes:
        if isinstance(index, bool) or not isinstance(index, int) or not 0 <= index < total_chunks:
            raise TransferValidationError("received chunk index is outside the transfer")
        received.add(index)
    return tuple(index for index in range(total_chunks) if index not in received)


def transition_state(current: TransferState, target: TransferState) -> TransferState:
    """Return the next state; repeated same-state events are idempotent."""
    if not isinstance(current, TransferState) or not isinstance(target, TransferState):
        raise TransferTransitionError("unknown transfer state")
    if current == target:
        return current
    if current in _TERMINAL_STATES or target not in _ALLOWED_TRANSITIONS.get(current, ()):
        raise TransferTransitionError(f"invalid transfer transition: {current.value} -> {target.value}")
    return target


def is_terminal_state(state: TransferState) -> bool:
    if not isinstance(state, TransferState):
        raise TransferTransitionError("unknown transfer state")
    return state in _TERMINAL_STATES
