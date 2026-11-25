"""Authenticated, bounded TCP framing primitives for LAN file-transfer data.

This module is deliberately a transport codec, not a listener or scheduler.
The caller must bind the socket to an already authenticated peer/session,
apply connection timeouts and concurrency limits, and pass the expected peer IDs
to recv_frame. It never carries chat.message payloads.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
import socket
import struct
from dataclasses import dataclass
from typing import Any

_MAGIC = b"LNP2"
_VERSION = 1
_MAX_HEADER_BYTES = 4096
_DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024
MAX_MISSING_INDEXES_PER_FRAME = 128
_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
_KINDS = frozenset({"chunk", "chunk_ack", "missing", "complete", "cancel"})
_HEADER_KEYS = frozenset({
    "v", "kind", "transfer_id", "sender_peer_id", "target_peer_id",
    "index", "payload_size", "payload_sha256", "metadata", "auth",
})


class TransferFrameError(ValueError):
    """Malformed, unauthenticated, or out-of-policy transfer frame."""


@dataclass(frozen=True)
class TransferFrame:
    kind: str
    transfer_id: str
    sender_peer_id: str
    target_peer_id: str
    index: int | None = None
    payload: bytes = b""
    metadata: dict[str, Any] | None = None


def _canonical(value: dict[str, Any]) -> bytes:
    try:
        return json.dumps(value, ensure_ascii=False, sort_keys=True,
                          separators=(",", ":"), allow_nan=False).encode("utf-8", "strict")
    except (TypeError, ValueError, UnicodeError) as exc:
        raise TransferFrameError("frame header is not canonical JSON") from exc


def _validate_id(value: Any, field: str) -> None:
    if not isinstance(value, str) or not _ID_RE.fullmatch(value):
        raise TransferFrameError(f"invalid {field}")


def _validate_frame(frame: TransferFrame, secret: bytes, max_payload_bytes: int) -> None:
    if not isinstance(frame, TransferFrame):
        raise TransferFrameError("invalid frame type")
    if not isinstance(secret, bytes) or len(secret) < 16:
        raise TransferFrameError("session secret must contain at least 16 bytes")
    if isinstance(max_payload_bytes, bool) or not isinstance(max_payload_bytes, int) or max_payload_bytes < 1:
        raise TransferFrameError("invalid payload limit")
    if not isinstance(frame.kind, str) or frame.kind not in _KINDS:
        raise TransferFrameError("unsupported frame kind")
    _validate_id(frame.transfer_id, "transfer_id")
    _validate_id(frame.sender_peer_id, "sender_peer_id")
    _validate_id(frame.target_peer_id, "target_peer_id")
    if frame.sender_peer_id == frame.target_peer_id:
        raise TransferFrameError("sender and target must differ")
    if not isinstance(frame.payload, bytes) or len(frame.payload) > max_payload_bytes:
        raise TransferFrameError("payload exceeds configured limit")
    if frame.kind == "chunk":
        if isinstance(frame.index, bool) or not isinstance(frame.index, int) or frame.index < 0:
            raise TransferFrameError("chunk index is invalid")
        if not frame.payload:
            raise TransferFrameError("chunk payload cannot be empty")
    elif frame.index is not None or frame.payload:
        raise TransferFrameError("control frames cannot carry chunk payload or index")
    if frame.metadata is not None:
        if not isinstance(frame.metadata, dict):
            raise TransferFrameError("metadata must be an object")
        encoded = _canonical(frame.metadata)
        if len(encoded) > 2048:
            raise TransferFrameError("control metadata exceeds limit")
    if frame.kind == "chunk" and frame.metadata is not None:
        raise TransferFrameError("chunk frame cannot carry control metadata")


def encode_frame(frame: TransferFrame, secret: bytes,
                 max_payload_bytes: int = _DEFAULT_MAX_PAYLOAD_BYTES) -> bytes:
    _validate_frame(frame, secret, max_payload_bytes)
    payload_hash = hashlib.sha256(frame.payload).hexdigest() if frame.payload else ""
    unsigned = {
        "v": _VERSION, "kind": frame.kind, "transfer_id": frame.transfer_id,
        "sender_peer_id": frame.sender_peer_id, "target_peer_id": frame.target_peer_id,
        "index": frame.index, "payload_size": len(frame.payload),
        "payload_sha256": payload_hash, "metadata": frame.metadata,
    }
    auth = hmac.new(secret, _canonical(unsigned), hashlib.sha256).hexdigest()
    header = dict(unsigned)
    header["auth"] = auth
    encoded_header = _canonical(header)
    if not encoded_header or len(encoded_header) > _MAX_HEADER_BYTES:
        raise TransferFrameError("frame header exceeds limit")
    return _MAGIC + struct.pack("!I", len(encoded_header)) + encoded_header + frame.payload


def _recv_exact(sock: socket.socket, size: int) -> bytes:
    if size < 0:
        raise TransferFrameError("negative read length")
    chunks = bytearray()
    while len(chunks) < size:
        part = sock.recv(size - len(chunks))
        if not part:
            raise EOFError("peer closed transfer stream")
        chunks.extend(part)
    return bytes(chunks)


def recv_frame(sock: socket.socket, secret: bytes,
               max_payload_bytes: int = _DEFAULT_MAX_PAYLOAD_BYTES,
               expected_sender_peer_id: str | None = None,
               expected_target_peer_id: str | None = None) -> TransferFrame:
    if not isinstance(secret, bytes) or len(secret) < 16:
        raise TransferFrameError("session secret must contain at least 16 bytes")
    if isinstance(max_payload_bytes, bool) or not isinstance(max_payload_bytes, int) or max_payload_bytes < 1:
        raise TransferFrameError("invalid payload limit")
    prefix = _recv_exact(sock, 8)
    if prefix[:4] != _MAGIC:
        raise TransferFrameError("invalid transfer stream magic")
    header_size = struct.unpack("!I", prefix[4:])[0]
    if not 1 <= header_size <= _MAX_HEADER_BYTES:
        raise TransferFrameError("invalid transfer header length")
    raw_header = _recv_exact(sock, header_size)
    try:
        header = json.loads(raw_header.decode("utf-8", "strict"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise TransferFrameError("invalid transfer header JSON") from exc
    if not isinstance(header, dict) or set(header) != _HEADER_KEYS:
        raise TransferFrameError("transfer header fields do not match schema")
    if _canonical(header) != raw_header:
        raise TransferFrameError("transfer header is not canonical JSON")
    if header.get("v") != _VERSION or isinstance(header.get("v"), bool):
        raise TransferFrameError("unsupported transfer frame version")
    payload_size = header.get("payload_size")
    if isinstance(payload_size, bool) or not isinstance(payload_size, int) or not 0 <= payload_size <= max_payload_bytes:
        raise TransferFrameError("invalid transfer payload length")
    payload = _recv_exact(sock, payload_size) if payload_size else b""
    kind = header.get("kind")
    index = header.get("index")
    frame = TransferFrame(kind, header.get("transfer_id"),
        header.get("sender_peer_id"), header.get("target_peer_id"),
        index=index, payload=payload, metadata=header.get("metadata"))
    _validate_frame(frame, secret, max_payload_bytes)
    if len(payload) != payload_size:
        raise TransferFrameError("payload length mismatch")
    expected_hash = hashlib.sha256(payload).hexdigest() if payload else ""
    if not hmac.compare_digest(str(header.get("payload_sha256", "")), expected_hash):
        raise TransferFrameError("payload SHA-256 mismatch")
    if expected_sender_peer_id is not None and frame.sender_peer_id != expected_sender_peer_id:
        raise TransferFrameError("unexpected sender peer")
    if expected_target_peer_id is not None and frame.target_peer_id != expected_target_peer_id:
        raise TransferFrameError("unexpected target peer")
    unsigned = dict(header)
    supplied_auth = unsigned.pop("auth")
    expected_auth = hmac.new(secret, _canonical(unsigned), hashlib.sha256).hexdigest()
    if not isinstance(supplied_auth, str) or not hmac.compare_digest(supplied_auth, expected_auth):
        raise TransferFrameError("invalid transfer frame authentication")
    return frame


def send_frame(sock: socket.socket, frame: TransferFrame, secret: bytes,
               max_payload_bytes: int = _DEFAULT_MAX_PAYLOAD_BYTES) -> None:
    sock.sendall(encode_frame(frame, secret, max_payload_bytes))
