"""Foreground entrypoint for the opt-in Python LAN transfer receiver.

Run explicitly with: python -m lan_p2p_transfer_host ...
No chat or device-management process imports or starts this host implicitly.
"""
from __future__ import annotations

import argparse
import json
import logging
import signal
import threading

from lan_p2p_transfer_provision import load_or_create_peer_id
from lan_p2p_transfer_service import LanTransferService


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Run an explicitly enabled LAN P2P file receiver")
    parser.add_argument("--credentials", required=True, help="0600 JSON file created by transfer provisioning")
    parser.add_argument("--data-dir", required=True, help="private transfer database and file directory")
    parser.add_argument("--identity-file", required=True, help="persistent local Peer ID file")
    parser.add_argument("--interface", help="required when multiple LAN interfaces are active")
    parser.add_argument("--address", help="explicit local IPv4 address selector")
    parser.add_argument("--port", type=int, default=0, help="TCP port (0 selects an ephemeral port)")
    parser.add_argument("--log-level", choices=("DEBUG", "INFO", "WARNING", "ERROR"), default="INFO")
    args = parser.parse_args(argv)
    logging.basicConfig(level=getattr(logging, args.log_level),
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    stopped = threading.Event()

    def stop(_signum, _frame):
        stopped.set()

    signal.signal(signal.SIGINT, stop)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, stop)

    peer_id = load_or_create_peer_id(args.identity_file)
    service = LanTransferService(
        local_peer_id=peer_id,
        data_dir=args.data_dir,
        credentials_path=args.credentials,
        interface_name=args.interface,
        address=args.address,
        port=args.port,
    )
    try:
        address = service.start()
        print(json.dumps({"peer_id": peer_id, "listen_address": address[0],
                          "listen_port": address[1]}, separators=(",", ":")), flush=True)
        while not stopped.wait(1.0):
            pass
    finally:
        service.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
