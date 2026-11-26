"""Fail-closed IPv4 socket binding for LAN P2P interface snapshots.

The caller must pass a path produced by lan_p2p_network_paths. Binding is platform
specific and is deliberately strict: unsupported options, missing interface indexes,
and OS permission errors are surfaced to the caller. No default-route fallback exists.
"""
from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import socket
import struct
import sys
from typing import Any


@dataclass(frozen=True)
class SocketBinding:
    interface_name: str
    interface_index: int
    local_address: str
    platform: str


def validate_endpoint_on_path(path: Any, remote_address: str) -> None:
    """Reject endpoints outside the selected IPv4 interface prefix."""
    try:
        local = ipaddress.IPv4Address(path.address)
        remote = ipaddress.IPv4Address(remote_address)
        prefix = int(path.prefix_length)
        network = ipaddress.IPv4Network((local, prefix), strict=False)
    except (AttributeError, TypeError, ValueError) as exc:
        raise ValueError("invalid IPv4 LAN path or endpoint") from exc
    if not path.interface_name or remote not in network or remote == local:
        raise ValueError("endpoint does not belong to the selected LAN path")


def bind_socket_to_path(sock: socket.socket, path: Any, *,
                        remote_address: str | None = None,
                        platform: str | None = None,
                        socket_module: Any = socket,
                        bind_source_address: bool = True) -> SocketBinding:
    """Bind an unconnected IPv4 socket to a named interface and its source address.

    Linux uses SO_BINDTODEVICE, Windows uses IP_UNICAST_IF, and macOS uses
    IP_BOUND_IF. Source-address binding is additionally applied on all platforms.
    This function must be called before connect()/sendto(); it never reports success
    when only a source address could be selected.
    """
    if sock is None or path is None:
        raise ValueError("socket and interface path are required")
    if getattr(sock, "family", None) != socket_module.AF_INET:
        raise ValueError("only IPv4 sockets are supported by this adapter")

    if getattr(path, "prefix_length", 0) < 1 or getattr(path, "prefix_length", 0) > 32:
        raise ValueError("invalid interface prefix")
    try:
        local = str(ipaddress.IPv4Address(path.address))
    except (AttributeError, ipaddress.AddressValueError) as exc:
        raise ValueError("invalid local IPv4 address") from exc
    if not getattr(path, "interface_name", ""):
        raise ValueError("interface name is required")
    if remote_address is not None:
        validate_endpoint_on_path(path, remote_address)

    os_name = platform or sys.platform
    try:
        index = socket_module.if_nametoindex(path.interface_name)
    except (AttributeError, OSError) as exc:
        raise OSError("unable to resolve LAN interface index") from exc
    if not isinstance(index, int) or index <= 0:
        raise OSError("LAN interface index is unavailable")

    if os_name.startswith("linux"):
        option = getattr(socket_module, "SO_BINDTODEVICE", None)
        if option is None:
            raise OSError("SO_BINDTODEVICE is unavailable on this Python runtime")
        sock.setsockopt(socket_module.SOL_SOCKET, option,
                        path.interface_name.encode("utf-8") + bytes((0,)))
    elif os_name == "win32":
        option = getattr(socket_module, "IP_UNICAST_IF", 31)
        # Windows expects the interface index in network byte order.
        sock.setsockopt(socket_module.IPPROTO_IP, option,
                        struct.pack("@I", socket_module.htonl(index)))
    elif os_name == "darwin":
        option = getattr(socket_module, "IP_BOUND_IF", 25)
        sock.setsockopt(socket_module.IPPROTO_IP, option, index)
    else:
        raise OSError("interface-bound IPv4 sockets are unsupported on this platform")

    # Pin the source address for clients; listeners bind their configured port afterwards.
    if bind_source_address:
        sock.bind((local, 0))
    return SocketBinding(path.interface_name, index, local, os_name)
