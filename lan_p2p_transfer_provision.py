"""Safe, explicit provisioning helpers for Python LAN transfer peers.

Secrets are entered interactively (never as command-line arguments), validated,
and atomically written with restrictive permissions. This does not pair peers:
the same per-peer PSK must come from an independently authenticated pairing
process on both devices.
"""
from __future__ import annotations

import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import re
import secrets
import sys
import uuid

from lan_p2p_transfer import TransferValidationError

_PEER_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


def validate_peer_secret(peer_id: str, encoded: str) -> None:
    if not isinstance(peer_id, str) or not _PEER_ID.fullmatch(peer_id):
        raise TransferValidationError("invalid peer ID")
    if not isinstance(encoded, str) or not 1 <= len(encoded) <= 128:
        raise TransferValidationError("invalid Base64 peer secret")
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise TransferValidationError("peer secret must be standard Base64") from exc
    if len(raw) != 32 or base64.b64encode(raw).decode("ascii") != encoded:
        raise TransferValidationError("peer secret must encode exactly 32 bytes")


def write_peer_credentials(path: str, peer_secrets: dict[str, str], *, overwrite: bool = False) -> None:
    if not isinstance(path, str) or not path:
        raise TransferValidationError("credential output path is required")
    if not isinstance(peer_secrets, dict) or not 1 <= len(peer_secrets) <= 256:
        raise TransferValidationError("one to 256 explicitly trusted peers are required")
    for peer_id, secret in peer_secrets.items():
        validate_peer_secret(peer_id, secret)
    target = Path(path).expanduser().absolute()
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and not overwrite:
        raise FileExistsError("credential file already exists; refusing to overwrite")
    if target.is_symlink():
        raise TransferValidationError("credential output cannot be a symlink")
    payload = json.dumps(
        {"version": 1, "peer_secrets": peer_secrets},
        ensure_ascii=True, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")
    temporary = target.with_name(target.name + ".tmp-" + secrets.token_hex(8))
    fd = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        if target.exists() and not overwrite:
            raise FileExistsError("credential file appeared during provisioning")
        os.replace(temporary, target)
        if os.name == "posix":
            os.chmod(target, 0o600)
    except Exception:
        try:
            temporary.unlink()
        except OSError:
            pass
        raise


def load_or_create_peer_id(path: str) -> str:
    """Persist a stable random node identity; never derive identity from IP/MAC."""
    if not isinstance(path, str) or not path:
        raise TransferValidationError("identity file path is required")
    target = Path(path).expanduser().absolute()
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_symlink():
        raise TransferValidationError("identity path cannot be a symlink")
    if target.exists():
        if os.name == "posix" and target.stat().st_mode & 0o077:
            raise TransferValidationError("identity file permissions must be 0600 or stricter")
        value = target.read_text(encoding="ascii").strip()
        if not _PEER_ID.fullmatch(value):
            raise TransferValidationError("identity file contains an invalid peer ID")
        return value
    value = "python-" + uuid.uuid4().hex
    fd = os.open(str(target), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="ascii") as output:
        output.write(value + "\n")
        output.flush()
        os.fsync(output.fileno())
    return value


def _provision_command(args) -> int:
    peers: dict[str, str] = {}
    print("Provisioning does not perform pairing. Enter only a PSK obtained from an independently authenticated pairing process.")
    while True:
        peer_id = input("Trusted peer ID (blank to finish): ").strip()
        if not peer_id:
            break
        if peer_id in peers:
            raise TransferValidationError("peer ID was entered more than once")
        encoded = getpass.getpass("32-byte peer PSK, standard Base64: ").strip()
        validate_peer_secret(peer_id, encoded)
        peers[peer_id] = encoded
    write_peer_credentials(args.output, peers, overwrite=args.overwrite)
    print("Wrote %d trusted peer credential(s) to %s" % (len(peers), args.output))
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Provision Python LAN P2P transfer credentials")
    sub = parser.add_subparsers(dest="command", required=True)
    provision = sub.add_parser("provision", help="write an explicit per-peer credential file")
    provision.add_argument("--output", required=True)
    provision.add_argument("--overwrite", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.command == "provision":
            return _provision_command(args)
    except (OSError, TransferValidationError) as exc:
        print("Provisioning failed: %s" % exc, file=sys.stderr)
        return 2
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
