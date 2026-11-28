"""Mutual PSK challenge for LAN transfer TCP sessions."""
from __future__ import annotations
import hashlib
import hmac
import secrets
import socket
import struct
from typing import Callable
from lan_p2p_transfer import TransferValidationError

_MAGIC = b"LTF1"
_NONCE_BYTES = 32
_PROOF_BYTES = 32
_MAX_PEER_ID_BYTES = 128
_DOMAIN_SERVER = b"lan-transfer-v1/server\x00"
_DOMAIN_CLIENT = b"lan-transfer-v1/client\x00"
_DOMAIN_SESSION = b"lan-transfer-v1/session\x00"

def _peer_bytes(peer_id: str) -> bytes:
    if not isinstance(peer_id, str) or not peer_id:
        raise TransferValidationError("peer identity is required")
    try:
        raw = peer_id.encode("utf-8", errors="strict")
    except UnicodeError as exc:
        raise TransferValidationError("peer identity is not valid UTF-8") from exc
    if len(raw) > _MAX_PEER_ID_BYTES or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in peer_id):
        raise TransferValidationError("peer identity is invalid")
    return raw

def _key(secret: str | bytes) -> bytes:
    if isinstance(secret, str):
        raw = secret.encode("utf-8")
    elif isinstance(secret, bytes):
        raw = secret
    else:
        raise TransferValidationError("LAN transfer shared secret is required")
    if not raw:
        raise TransferValidationError("LAN transfer shared secret is required")
    return hashlib.sha256(b"lan-p2p-v2\x00" + raw).digest()

def _transcript(client_id: bytes, server_id: bytes, client_nonce: bytes, server_nonce: bytes) -> bytes:
    return (struct.pack("!H", len(client_id)) + client_id
            + struct.pack("!H", len(server_id)) + server_id + client_nonce + server_nonce)

def _read_exact(sock: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        part = sock.recv(size - len(chunks))
        if not part:
            raise EOFError("TCP peer closed during authentication")
        chunks.extend(part)
    return bytes(chunks)

def _read_peer_id(sock: socket.socket) -> bytes:
    size = struct.unpack("!H", _read_exact(sock, 2))[0]
    if not 1 <= size <= _MAX_PEER_ID_BYTES:
        raise TransferValidationError("invalid authenticated peer ID length")
    raw = _read_exact(sock, size)
    try:
        _peer_bytes(raw.decode("utf-8", errors="strict"))
    except UnicodeError as exc:
        raise TransferValidationError("invalid authenticated peer ID encoding") from exc
    return raw

def authenticate_tcp_client(sock: socket.socket, local_peer_id: str,
        expected_remote_peer_id: str, shared_secret: str | bytes, *,
        nonce_factory: Callable[[int], bytes] = secrets.token_bytes) -> tuple[str, bytes]:
    """Authenticate server identity and prove client possession of the same PSK."""
    if sock is None:
        raise TransferValidationError("TCP socket is required")
    client_id, expected_server = _peer_bytes(local_peer_id), _peer_bytes(expected_remote_peer_id)
    key = _key(shared_secret)
    client_nonce = nonce_factory(_NONCE_BYTES)
    if not isinstance(client_nonce, bytes) or len(client_nonce) != _NONCE_BYTES:
        raise TransferValidationError("nonce source must return 32 bytes")
    sock.sendall(_MAGIC + struct.pack("!H", len(client_id)) + client_id + client_nonce)
    server_id = _read_peer_id(sock)
    if not hmac.compare_digest(server_id, expected_server):
        raise TransferValidationError("unexpected TCP server identity")
    server_nonce = _read_exact(sock, _NONCE_BYTES)
    transcript = _transcript(client_id, server_id, client_nonce, server_nonce)
    server_proof = _read_exact(sock, _PROOF_BYTES)
    expected = hmac.new(key, _DOMAIN_SERVER + transcript, hashlib.sha256).digest()
    if not hmac.compare_digest(server_proof, expected):
        raise TransferValidationError("TCP server proof is invalid")
    sock.sendall(hmac.new(key, _DOMAIN_CLIENT + transcript, hashlib.sha256).digest())
    if _read_exact(sock, 1) != b"\x01":
        raise TransferValidationError("TCP server rejected client authentication")
    session_key = hmac.new(key, _DOMAIN_SESSION + transcript, hashlib.sha256).digest()
    return server_id.decode("utf-8"), session_key

def authenticate_tcp_server(sock: socket.socket, local_peer_id: str,
        shared_secret: str | bytes | None, authorize_peer: Callable[[str], bool], *,
        secret_provider: Callable[[str], str | bytes | None] | None = None,
        nonce_factory: Callable[[int], bytes] = secrets.token_bytes) -> tuple[str, bytes]:
    """Authenticate a trusted peer using its own PSK, with legacy group-PSK support.

    New production callers should provide secret_provider backed by the committed
    per-peer trust store. shared_secret remains for backward-compatible tests and
    explicitly provisioned legacy deployments; it must not be used to infer trust.
    """
    if sock is None or not callable(authorize_peer):
        raise TransferValidationError("TCP socket and trusted-peer authorization callback are required")
    if secret_provider is not None and not callable(secret_provider):
        raise TransferValidationError("per-peer secret provider must be callable")
    server_id = _peer_bytes(local_peer_id)
    if _read_exact(sock, len(_MAGIC)) != _MAGIC:
        raise TransferValidationError("invalid LAN transfer authentication preamble")
    client_id = _read_peer_id(sock)
    client_name = client_id.decode("utf-8")
    client_nonce = _read_exact(sock, _NONCE_BYTES)
    if not authorize_peer(client_name):
        raise TransferValidationError("TCP peer is not in the trusted peer registry")
    peer_secret = secret_provider(client_name) if secret_provider is not None else shared_secret
    key = _key(peer_secret)
    server_nonce = nonce_factory(_NONCE_BYTES)
    if not isinstance(server_nonce, bytes) or len(server_nonce) != _NONCE_BYTES:
        raise TransferValidationError("nonce source must return 32 bytes")
    transcript = _transcript(client_id, server_id, client_nonce, server_nonce)
    proof = hmac.new(key, _DOMAIN_SERVER + transcript, hashlib.sha256).digest()
    sock.sendall(struct.pack("!H", len(server_id)) + server_id + server_nonce + proof)
    supplied = _read_exact(sock, _PROOF_BYTES)
    expected = hmac.new(key, _DOMAIN_CLIENT + transcript, hashlib.sha256).digest()
    if not hmac.compare_digest(supplied, expected):
        raise TransferValidationError("TCP client proof is invalid")
    sock.sendall(b"\x01")
    session_key = hmac.new(key, _DOMAIN_SESSION + transcript, hashlib.sha256).digest()
    return client_name, session_key
