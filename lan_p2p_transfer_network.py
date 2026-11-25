"""Bounded TCP listener/client lifecycle for authenticated LAN file transfers."""
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import socket
import threading
from typing import Callable
from lan_p2p_transfer import TransferValidationError
from lan_p2p_transfer_files import TransferFilesystem
from lan_p2p_transfer_session import serve_transfer_session
from lan_p2p_transfer_store import TransferStore

PeerAuthenticator = Callable[[socket.socket, tuple], tuple[str, bytes]]

def _validated_peer(value: object) -> tuple[str, bytes]:
    if (not isinstance(value, tuple) or len(value) != 2
            or not isinstance(value[0], str) or not value[0]
            or not isinstance(value[1], bytes) or len(value[1]) < 16):
        raise TransferValidationError("peer authenticator returned invalid identity/session secret")
    return value[0], value[1]

@dataclass
class AuthenticatedTransferConnection:
    sock: socket.socket
    peer_id: str
    session_secret: bytes
    def close(self) -> None:
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()
    def __enter__(self):
        return self
    def __exit__(self, exc_type, exc, traceback):
        self.close()

def connect_transfer_peer(host: str, port: int,
        authenticate_peer: Callable[[socket.socket], tuple[str, bytes]], *,
        timeout_seconds: float = 10.0) -> AuthenticatedTransferConnection:
    """Connect and run caller-supplied trusted peer challenge; never trust IP as identity."""
    if not isinstance(host, str) or not host:
        raise TransferValidationError("remote host is required")
    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        raise TransferValidationError("remote TCP port is invalid")
    if (isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float))
            or timeout_seconds <= 0):
        raise TransferValidationError("connection timeout must be positive")
    if not callable(authenticate_peer):
        raise TransferValidationError("peer authentication callback is required")
    sock = socket.create_connection((host, port), timeout=float(timeout_seconds))
    try:
        sock.settimeout(float(timeout_seconds))
        peer_id, secret = _validated_peer(authenticate_peer(sock))
        return AuthenticatedTransferConnection(sock, peer_id, secret)
    except Exception:
        sock.close()
        raise

class TransferTcpServer:
    """Bounded TCP acceptor. Auth callback must complete trusted peer challenge."""
    def __init__(self, store: TransferStore, filesystem: TransferFilesystem, *,
            local_peer_id: str, authenticate_peer: PeerAuthenticator,
            now_ms: Callable[[], int], host: str = "0.0.0.0", port: int = 0,
            backlog: int = 16, max_connections: int = 2,
            socket_timeout_seconds: float = 15.0, max_frames: int = 64,
            max_payload_bytes: int = 1024 * 1024,
            on_error: Callable[[Exception], None] | None = None):
        if not isinstance(store, TransferStore) or not isinstance(filesystem, TransferFilesystem):
            raise TransferValidationError("transfer store and filesystem are required")
        if not isinstance(local_peer_id, str) or not local_peer_id:
            raise TransferValidationError("local peer identity is required")
        if not callable(authenticate_peer) or not callable(now_ms):
            raise TransferValidationError("authentication and clock callbacks are required")
        if not isinstance(host, str) or not host:
            raise TransferValidationError("bind host is required")
        for name, value, low, high in (
            ("port", port, 0, 65535), ("backlog", backlog, 1, 128),
            ("max_connections", max_connections, 1, 32),
            ("max_frames", max_frames, 1, 256),
            ("max_payload_bytes", max_payload_bytes, 1, 1024 * 1024)):
            if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
                raise TransferValidationError(f"invalid {name}")
        if (isinstance(socket_timeout_seconds, bool)
                or not isinstance(socket_timeout_seconds, (int, float))
                or socket_timeout_seconds <= 0):
            raise TransferValidationError("socket timeout must be positive")
        if on_error is not None and not callable(on_error):
            raise TransferValidationError("on_error must be callable")
        self.store, self.filesystem = store, filesystem
        self.local_peer_id, self.authenticate_peer, self.now_ms = local_peer_id, authenticate_peer, now_ms
        self.host, self.port, self.backlog = host, port, backlog
        self.max_connections, self.socket_timeout_seconds = max_connections, float(socket_timeout_seconds)
        self.max_frames, self.max_payload_bytes, self.on_error = max_frames, max_payload_bytes, on_error
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._slots = threading.BoundedSemaphore(max_connections)
        self._active: set[socket.socket] = set()
        self._executor = ThreadPoolExecutor(max_workers=max_connections, thread_name_prefix="lan-transfer")
        self._listener = None
        self._accept_thread = None
        self._started = False

    @property
    def address(self) -> tuple[str, int]:
        if self._listener is None:
            raise RuntimeError("transfer TCP server is not started")
        host, port = self._listener.getsockname()[:2]
        return str(host), int(port)

    def start(self):
        with self._lock:
            if self._started:
                raise RuntimeError("transfer TCP server already started")
            listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                listener.bind((self.host, self.port))
                listener.listen(self.backlog)
                listener.settimeout(0.25)
            except Exception:
                listener.close()
                raise
            self._listener, self._started = listener, True
            self._accept_thread = threading.Thread(target=self._accept_loop,
                name="lan-transfer-accept", daemon=True)
            self._accept_thread.start()
        return self

    def _report_error(self, exc):
        if self.on_error is not None:
            try:
                self.on_error(exc)
            except Exception:
                pass

    def _accept_loop(self):
        while not self._stop.is_set():
            listener = self._listener
            if listener is None:
                return
            try:
                conn, address = listener.accept()
            except socket.timeout:
                continue
            except OSError:
                if not self._stop.is_set():
                    self._report_error(RuntimeError("transfer TCP listener failed"))
                return
            if self._stop.is_set() or not self._slots.acquire(blocking=False):
                conn.close()
                continue
            with self._lock:
                if self._stop.is_set():
                    conn.close()
                    self._slots.release()
                    continue
                self._active.add(conn)
            try:
                self._executor.submit(self._serve_connection, conn, address)
            except RuntimeError as exc:
                with self._lock:
                    self._active.discard(conn)
                conn.close()
                self._slots.release()
                self._report_error(exc)
                return

    def _serve_connection(self, conn, address):
        try:
            conn.settimeout(self.socket_timeout_seconds)
            peer_id, secret = _validated_peer(self.authenticate_peer(conn, address))
            serve_transfer_session(conn, self.store, self.filesystem,
                local_peer_id=self.local_peer_id, authenticated_peer_id=peer_id,
                session_secret=secret, now_ms=self.now_ms, max_frames=self.max_frames,
                max_payload_bytes=self.max_payload_bytes)
        except Exception as exc:
            if not self._stop.is_set():
                self._report_error(exc)
        finally:
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            conn.close()
            with self._lock:
                self._active.discard(conn)
            self._slots.release()

    def shutdown(self, wait: bool = True):
        self._stop.set()
        listener = self._listener
        if listener is not None:
            try:
                listener.close()
            except OSError:
                pass
        with self._lock:
            active = tuple(self._active)
        for conn in active:
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                conn.close()
            except OSError:
                pass
        if self._accept_thread is not None and wait:
            self._accept_thread.join(timeout=self.socket_timeout_seconds + 1.0)
        self._executor.shutdown(wait=wait, cancel_futures=True)
        with self._lock:
            self._listener = None
            self._started = False

    close = shutdown
    def __enter__(self):
        return self.start()
    def __exit__(self, exc_type, exc, traceback):
        self.shutdown()
