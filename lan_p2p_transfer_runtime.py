"""Fail-closed lifecycle adapter for a Python LAN file-transfer listener.

The host application owns when this runtime is started. A listener is only
created from an enumerated IPv4 interface snapshot; ambiguous multi-NIC
selection requires an explicit interface/address selector. Refreshing the
interface snapshot closes the old listener before binding a changed path.
"""
from __future__ import annotations

import threading
from typing import Callable

from lan_p2p_network_paths import IPv4InterfacePath, enumerate_ipv4_paths
from lan_p2p_transfer import TransferValidationError
from lan_p2p_transfer_network import TransferTcpServer


def select_transfer_path(paths, *, interface_name=None, address=None):
    """Select exactly one usable path; never silently choose among multiple NICs."""
    candidates = tuple(
        path for path in paths
        if isinstance(path, IPv4InterfacePath)
        and (interface_name is None or path.interface_name == interface_name)
        and (address is None or path.address == address)
    )
    if not candidates:
        raise TransferValidationError("no matching active IPv4 LAN interface path")
    if len(candidates) != 1:
        raise TransferValidationError(
            "multiple LAN interface paths are available; select interface_name or address"
        )
    return candidates[0]


def _path_key(path):
    return (path.interface_name, path.address, path.prefix_length, path.broadcast_address)


class TransferListenerRuntime:
    """Own one path-bound TransferTcpServer and reconcile it on network changes.

    server_options must contain the normal TransferTcpServer dependencies
    (store, filesystem, local_peer_id, authenticate_peer and now_ms). The
    runtime supplies host and network_path from the selected immutable path.
    """

    def __init__(self, *, path_provider: Callable = enumerate_ipv4_paths,
                 server_factory: Callable = TransferTcpServer,
                 interface_name=None, address=None, **server_options):
        if not callable(path_provider) or not callable(server_factory):
            raise TransferValidationError("path provider and server factory are required")
        if "host" in server_options or "network_path" in server_options:
            raise TransferValidationError("host and network_path are selected by the runtime")
        self._path_provider = path_provider
        self._server_factory = server_factory
        self._interface_name = interface_name
        self._address = address
        self._server_options = dict(server_options)
        self._lock = threading.RLock()
        self._server = None
        self._path = None
        self._closed = False

    @property
    def path(self):
        with self._lock:
            return self._path

    @property
    def server(self):
        with self._lock:
            return self._server

    def _choose_path(self):
        try:
            paths = tuple(self._path_provider())
        except Exception as exc:
            raise TransferValidationError("LAN interface enumeration failed") from exc
        return select_transfer_path(
            paths, interface_name=self._interface_name, address=self._address
        )

    def _stop_locked(self):
        server, self._server = self._server, None
        self._path = None
        if server is not None:
            server.shutdown()

    def start(self):
        with self._lock:
            if self._closed:
                raise RuntimeError("transfer listener runtime is closed")
            if self._server is not None:
                raise RuntimeError("transfer listener runtime is already started")
            path = self._choose_path()
            server = self._server_factory(
                **self._server_options, host=path.address, network_path=path
            )
            try:
                server.start()
            except Exception:
                try:
                    server.shutdown()
                except Exception:
                    pass
                raise
            self._path, self._server = path, server
            return server

    def refresh(self):
        """Re-enumerate; retain an identical listener, otherwise stop then rebind."""
        with self._lock:
            if self._closed:
                raise RuntimeError("transfer listener runtime is closed")
            try:
                path = self._choose_path()
            except TransferValidationError:
                self._stop_locked()
                return None
            if self._server is not None and self._path is not None:
                if _path_key(path) == _path_key(self._path):
                    return self._server
                self._stop_locked()
            server = self._server_factory(
                **self._server_options, host=path.address, network_path=path
            )
            try:
                server.start()
            except Exception:
                try:
                    server.shutdown()
                except Exception:
                    pass
                self._path = None
                self._server = None
                raise
            self._path, self._server = path, server
            return server

    def close(self):
        with self._lock:
            if self._closed:
                return
            self._stop_locked()
            self._closed = True
