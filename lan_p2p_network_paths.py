"""Platform-neutral IPv4 interface snapshots for offline LAN discovery.

Interface enumeration is local-only: it never opens an Internet socket or guesses a /24.
psutil is optional; when unavailable the caller receives no directed-broadcast candidates
and may use its separately bounded limited-broadcast fallback.
"""
from __future__ import annotations

from dataclasses import dataclass
import ipaddress
from typing import Iterable


@dataclass(frozen=True)
class IPv4InterfacePath:
    interface_name: str
    address: str
    prefix_length: int
    broadcast_address: str | None


def enumerate_ipv4_paths(psutil_module=None) -> tuple[IPv4InterfacePath, ...]:
    """Return active usable IPv4 interfaces, deterministically ordered.

    The psutil dependency is optional and injected in tests. Missing interface-inspection
    support fails closed (empty tuple); it never guesses a subnet from an address suffix.
    """
    if psutil_module is None:
        try:
            import psutil as psutil_module  # type: ignore[no-redef]
        except ImportError:
            return ()

    try:
        stats = psutil_module.net_if_stats()
        addresses = psutil_module.net_if_addrs()
    except Exception:
        return ()

    result: list[IPv4InterfacePath] = []
    for name in sorted(addresses):
        stat = stats.get(name)
        if stat is None or not getattr(stat, "isup", False):
            continue
        for item in addresses.get(name, ()):
            if getattr(item, "family", None) not in (2, getattr(__import__("socket"), "AF_INET")):
                continue
            raw_address = getattr(item, "address", None)
            raw_netmask = getattr(item, "netmask", None)
            if not raw_address or not raw_netmask:
                continue
            try:
                address = ipaddress.IPv4Address(raw_address.split("%", 1)[0])
                interface = ipaddress.IPv4Interface(f"{address}/{raw_netmask}")
            except (ipaddress.AddressValueError, ipaddress.NetmaskValueError, ValueError):
                continue
            if (address.is_unspecified or address.is_loopback or address.is_link_local
                    or address.is_multicast or address.is_reserved):
                continue
            prefix = interface.network.prefixlen
            if prefix < 1 or prefix > 32:
                continue
            raw_broadcast = getattr(item, "broadcast", None)
            try:
                broadcast = str(ipaddress.IPv4Address(raw_broadcast)) if raw_broadcast else None
            except ipaddress.AddressValueError:
                broadcast = None
            result.append(IPv4InterfacePath(name, str(address), prefix, broadcast))
    return tuple(result)


def directed_broadcast_addresses(paths: Iterable[IPv4InterfacePath]) -> tuple[str, ...]:
    """Unique explicit interface broadcast addresses; no inferred broadcast values."""
    return tuple(sorted({p.broadcast_address for p in paths if p.broadcast_address}))
