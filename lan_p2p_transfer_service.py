"""Explicitly provisioned Python LAN file-transfer receiving service.

The service is opt-in and owns the SQLite store, private staging directories,
trusted-peer credentials, and one path-bound TCP listener. It does not start
itself from chat/server processes.
"""
from __future__ import annotations

import base64
import json
import logging
import os
from pathlib import Path
import re
import threading
import time
from typing import Callable

from lan_p2p_transfer import TransferValidationError
from lan_p2p_transfer_auth import authenticate_tcp_server
from lan_p2p_transfer_files import TransferFilesystem
from lan_p2p_transfer_runtime import TransferListenerRuntime
from lan_p2p_transfer_store import TransferStore

_PEER_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
_MAX_CREDENTIAL_FILE = 64 * 1024
_LOG = logging.getLogger(__name__)


class PeerCredentialStore:
    """Read-only, explicit per-peer PSKs exported by an authenticated pairing flow.

    Values remain the Android-compatible Base64 text: the TCP authenticator
    hashes its UTF-8 bytes, matching LanTransferAuthenticator. The decoded
    value is checked to be exactly 32 bytes before it is accepted.
    """

    def __init__(self, path: str):
        if not isinstance(path, str) or not path:
            raise TransferValidationError("credential file path is required")
        self.path = Path(path).expanduser().absolute()
        self._lock = threading.RLock()
        self._secrets: dict[str, str] = {}
        self.reload()

    def reload(self) -> None:
        with self._lock:
            try:
                info = self.path.stat()
                if not self.path.is_file() or self.path.is_symlink():
                    raise TransferValidationError("credential path must be a regular file")
                if os.name == "posix" and info.st_mode & 0o077:
                    raise TransferValidationError(
                        "credential file permissions must be 0600 or stricter"
                    )
                if info.st_size < 1 or info.st_size > _MAX_CREDENTIAL_FILE:
                    raise TransferValidationError("credential file size is outside limits")
                raw = self.path.read_text(encoding="utf-8")
                document = json.loads(raw)
            except TransferValidationError:
                raise
            except (OSError, UnicodeError, json.JSONDecodeError) as exc:
                raise TransferValidationError("unable to read trusted-peer credential file") from exc
            if not isinstance(document, dict) or set(document) != {"version", "peer_secrets"}:
                raise TransferValidationError("credential file schema is invalid")
            if document["version"] != 1 or isinstance(document["version"], bool):
                raise TransferValidationError("unsupported credential file version")
            peers = document["peer_secrets"]
            if not isinstance(peers, dict) or not 1 <= len(peers) <= 256:
                raise TransferValidationError("an explicit trusted-peer map is required")
            validated: dict[str, str] = {}
            for peer_id, encoded in peers.items():
                if not isinstance(peer_id, str) or not _PEER_ID.fullmatch(peer_id):
                    raise TransferValidationError("credential file contains an invalid peer ID")
                if not isinstance(encoded, str) or not encoded or len(encoded) > 128:
                    raise TransferValidationError("credential file contains an invalid peer secret")
                try:
                    decoded = base64.b64decode(encoded, validate=True)
                except (ValueError, base64.binascii.Error) as exc:
                    raise TransferValidationError("peer secret must be standard Base64") from exc
                if len(decoded) != 32 or base64.b64encode(decoded).decode("ascii") != encoded:
                    raise TransferValidationError("peer secret must encode exactly 32 bytes")
                validated[peer_id] = encoded
            self._secrets = validated

    def is_trusted(self, peer_id: str) -> bool:
        with self._lock:
            return peer_id in self._secrets

    def secret_for(self, peer_id: str) -> str | None:
        with self._lock:
            return self._secrets.get(peer_id)

    @property
    def trusted_peer_ids(self) -> tuple[str, ...]:
        with self._lock:
            return tuple(sorted(self._secrets))


class LanTransferService:
    """Opt-in receiving node; caller explicitly controls start/refresh/close."""

    def __init__(self, *, local_peer_id: str, data_dir: str,
                 credentials_path: str, interface_name: str | None = None,
                 address: str | None = None, port: int = 0,
                 path_provider: Callable | None = None,
                 server_factory: Callable | None = None,
                 on_error: Callable[[Exception], None] | None = None,
                 now_ms: Callable[[], int] | None = None):
        if not isinstance(local_peer_id, str) or not _PEER_ID.fullmatch(local_peer_id):
            raise TransferValidationError("a stable, provisioned local peer ID is required")
        if not isinstance(data_dir, str) or not data_dir:
            raise TransferValidationError("service data directory is required")
        if isinstance(port, bool) or not isinstance(port, int) or not 0 <= port <= 65535:
            raise TransferValidationError("TCP port must be between 0 and 65535")
        if on_error is not None and not callable(on_error):
            raise TransferValidationError("on_error must be callable")
        self.local_peer_id = local_peer_id
        self.data_dir = Path(data_dir).expanduser().absolute()
        self.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.data_dir.is_symlink() or not self.data_dir.is_dir():
            raise TransferValidationError("service data directory must be a real directory")
        try:
            os.chmod(self.data_dir, 0o700)
        except OSError:
            pass
        self.credentials = PeerCredentialStore(credentials_path)
        self.store = TransferStore(str(self.data_dir / "transfers.sqlite3"))
        self.filesystem = TransferFilesystem(
            self.store, str(self.data_dir / "staging"),
            str(self.data_dir / "received"),
        )
        self._port = port
        self._on_error = on_error
        self._now_ms = now_ms or (lambda: int(time.time() * 1000))
        self._runtime_options = {
            "local_peer_id": local_peer_id,
            "store": self.store,
            "filesystem": self.filesystem,
            "authenticate_peer": self._authenticate_peer,
            "now_ms": self._now_ms,
            "port": port,
            "on_error": self._report_error,
        }
        if path_provider is not None:
            self._runtime_options["path_provider"] = path_provider
        if server_factory is not None:
            self._runtime_options["server_factory"] = server_factory
        self._interface_name, self._address = interface_name, address
        self._lock = threading.RLock()
        self._runtime = None
        self._closed = False
        self._started = False

    def _authenticate_peer(self, sock, _remote_address):
        return authenticate_tcp_server(
            sock, self.local_peer_id, None,
            self.credentials.is_trusted,
            secret_provider=self.credentials.secret_for,
        )

    def _report_error(self, exc: Exception) -> None:
        _LOG.warning("LAN transfer service error: %s", exc)
        if self._on_error is not None:
            try:
                self._on_error(exc)
            except Exception:
                _LOG.exception("LAN transfer error callback failed")

    @property
    def address(self) -> tuple[str, int] | None:
        with self._lock:
            if self._runtime is None or self._runtime.server is None:
                return None
            return self._runtime.server.address

    @property
    def is_running(self) -> bool:
        return self.address is not None

    def start(self):
        with self._lock:
            if self._closed:
                raise RuntimeError("LAN transfer service is closed")
            if self._started:
                raise RuntimeError("LAN transfer service is already started")
            self.credentials.reload()
            self._runtime = TransferListenerRuntime(
                interface_name=self._interface_name,
                address=self._address,
                **self._runtime_options,
            )
            self._runtime.start()
            self._started = True
            _LOG.info("LAN transfer listener started at %s", self.address)
            return self.address

    def refresh(self):
        with self._lock:
            if self._closed:
                raise RuntimeError("LAN transfer service is closed")
            if not self._started or self._runtime is None:
                raise RuntimeError("LAN transfer service is not started")
            self.credentials.reload()
            result = self._runtime.refresh()
            if result is None:
                _LOG.warning("LAN transfer listener stopped: no unambiguous LAN path")
                return None
            _LOG.info("LAN transfer listener refreshed at %s", result.address)
            return result.address

    def close(self):
        with self._lock:
            if self._closed:
                return
            if self._runtime is not None:
                self._runtime.close()
            self._runtime = None
            self._started = False
            self._closed = True
            _LOG.info("LAN transfer service stopped")

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc, traceback):
        self.close()
