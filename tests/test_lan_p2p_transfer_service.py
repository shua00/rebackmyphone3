import base64
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path

from lan_p2p_network_paths import IPv4InterfacePath
from lan_p2p_transfer import (ChunkDescriptor, TransferOffer, TransferState,
                              TransferValidationError)
from lan_p2p_transfer_service import LanTransferService, PeerCredentialStore


def write_credentials(directory, peers=None):
    path = Path(directory) / "trusted-peers.json"
    peers = peers if peers is not None else {
        "android-peer-1": base64.b64encode(b"x" * 32).decode("ascii")
    }
    path.write_text(json.dumps({"version": 1, "peer_secrets": peers}), encoding="utf-8")
    os.chmod(path, 0o600)
    return str(path)


class FakeServer:
    def __init__(self, **kwargs):
        self.kwargs = kwargs
        self.started = False
        self.stopped = False
        self.address = (kwargs["host"], kwargs["port"] or 49152)

    def start(self):
        self.started = True
        return self

    def shutdown(self):
        self.stopped = True


class TransferServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.credentials_path = write_credentials(self.temp.name)

    def test_credential_store_exposes_only_explicit_peer_secrets(self):
        store = PeerCredentialStore(self.credentials_path)
        expected = base64.b64encode(b"x" * 32).decode("ascii")
        self.assertEqual(store.trusted_peer_ids, ("android-peer-1",))
        self.assertTrue(store.is_trusted("android-peer-1"))
        self.assertFalse(store.is_trusted("discovered-only-peer"))
        self.assertEqual(store.secret_for("android-peer-1"), expected)
        self.assertIsNone(store.secret_for("discovered-only-peer"))

    def test_credential_store_rejects_invalid_configuration(self):
        valid_secret = base64.b64encode(b"x" * 32).decode("ascii")
        documents = (
            {"version": 1, "peer_secrets": {}},
            {"version": 2, "peer_secrets": {"peer": valid_secret}},
            {"version": 1, "peer_secrets": {"peer": "not-base64"}},
            {"version": 1, "peer_secrets": {"peer": base64.b64encode(b"short").decode("ascii")}},
            {"version": 1, "peer_secrets": {"bad peer id": valid_secret}},
        )
        for index, document in enumerate(documents):
            with self.subTest(index=index):
                path = Path(self.temp.name) / ("invalid-%d.json" % index)
                path.write_text(json.dumps(document), encoding="utf-8")
                os.chmod(path, 0o600)
                with self.assertRaises(TransferValidationError):
                    PeerCredentialStore(str(path))

    def test_failed_credential_reload_revokes_cached_trust(self):
        store = PeerCredentialStore(self.credentials_path)
        self.assertTrue(store.is_trusted("android-peer-1"))
        Path(self.credentials_path).write_text('{"version":1,"peer_secrets":{}}', encoding="utf-8")
        os.chmod(self.credentials_path, 0o600)
        with self.assertRaises(TransferValidationError):
            store.reload()
        self.assertFalse(store.is_trusted("android-peer-1"))
        self.assertIsNone(store.secret_for("android-peer-1"))

    @unittest.skipUnless(os.name == "posix", "POSIX credential-file mode policy")
    def test_credential_store_rejects_group_or_world_readable_file(self):
        os.chmod(self.credentials_path, 0o644)
        with self.assertRaisesRegex(TransferValidationError, "0600"):
            PeerCredentialStore(self.credentials_path)

    def test_service_explicit_lifecycle_and_network_refresh(self):
        paths = [IPv4InterfacePath("wlan-test", "192.168.50.2", 24, "192.168.50.255")]
        servers = []

        def factory(**kwargs):
            server = FakeServer(**kwargs)
            servers.append(server)
            return server

        service = LanTransferService(
            local_peer_id="python-node-1",
            data_dir=str(Path(self.temp.name) / "service-data"),
            credentials_path=self.credentials_path,
            path_provider=lambda: tuple(paths),
            server_factory=factory,
        )
        self.assertFalse(service.is_running)
        self.assertEqual(service.start(), ("192.168.50.2", 49152))
        self.assertTrue(service.is_running)
        self.assertEqual(service.refresh(), ("192.168.50.2", 49152))
        self.assertEqual(len(servers), 1)

        paths[:] = [IPv4InterfacePath("wlan-test", "192.168.60.2", 24, "192.168.60.255")]
        self.assertEqual(service.refresh(), ("192.168.60.2", 49152))
        self.assertTrue(servers[0].stopped)
        self.assertEqual(len(servers), 2)

        paths.clear()
        self.assertIsNone(service.refresh())
        self.assertTrue(servers[1].stopped)
        self.assertFalse(service.is_running)
        service.close()
        service.close()
        with self.assertRaisesRegex(RuntimeError, "closed"):
            service.start()

    def test_service_process_restart_preserves_partial_transfer_and_staging(self):
        data_dir = Path(self.temp.name) / "restartable-service"
        paths = (IPv4InterfacePath("wlan-test", "192.168.50.2", 24, "192.168.50.255"),)
        payload = b"r" * (256 * 1024)
        offer = TransferOffer(
            "service-restart-001", "android-peer-1", "python-node-1",
            "restart.bin", len(payload) + 1,
            hashlib.sha256(payload + b"x").hexdigest(), 100,
        )

        first = LanTransferService(
            local_peer_id="python-node-1", data_dir=str(data_dir),
            credentials_path=self.credentials_path,
            path_provider=lambda: paths, server_factory=lambda **kwargs: FakeServer(**kwargs),
        )
        first.start()
        first.store.create_offer(offer, 101)
        first.store.transition(offer.transfer_id, TransferState.ACCEPTED, 102)
        first.store.transition(offer.transfer_id, TransferState.TRANSFERRING, 103)
        first.filesystem.write_chunk(
            ChunkDescriptor(offer.transfer_id, 0, payload, hashlib.sha256(payload).hexdigest()),
            104,
        )
        first.close()

        restarted = LanTransferService(
            local_peer_id="python-node-1", data_dir=str(data_dir),
            credentials_path=self.credentials_path,
            path_provider=lambda: paths, server_factory=lambda **kwargs: FakeServer(**kwargs),
        )
        try:
            record = restarted.store.get_transfer(offer.transfer_id)
            self.assertEqual(TransferState.TRANSFERRING, record.state)
            self.assertEqual((1,), restarted.filesystem.reconcile(offer.transfer_id, 105))
            self.assertEqual((1,), restarted.store.missing_chunks(offer.transfer_id))
            self.assertEqual(("android-peer-1",), restarted.credentials.trusted_peer_ids)
            restarted.start()
            self.assertTrue(restarted.is_running)
        finally:
            restarted.close()

    def test_service_rebinds_after_path_disappears_and_returns_on_new_subnet(self):
        paths = [IPv4InterfacePath("wlan-test", "192.168.50.2", 24, "192.168.50.255")]
        servers = []

        def factory(**kwargs):
            server = FakeServer(**kwargs)
            servers.append(server)
            return server

        service = LanTransferService(
            local_peer_id="python-node-stable",
            data_dir=str(Path(self.temp.name) / "path-recovery"),
            credentials_path=self.credentials_path,
            path_provider=lambda: tuple(paths),
            server_factory=factory,
        )
        try:
            self.assertEqual(("192.168.50.2", 49152), service.start())
            paths.clear()
            self.assertIsNone(service.refresh())
            self.assertFalse(service.is_running)
            self.assertTrue(servers[0].stopped)

            paths.append(IPv4InterfacePath(
                "vendor_ap42", "192.168.43.1", 24, "192.168.43.255"))
            self.assertEqual(("192.168.43.1", 49152), service.refresh())
            self.assertTrue(service.is_running)
            self.assertEqual(2, len(servers))
            self.assertTrue(servers[1].started)
            self.assertEqual("python-node-stable", service.local_peer_id)
        finally:
            service.close()

    def test_restart_chunk_replay_is_idempotent_and_keeps_other_chunks_missing(self):
        data_dir = Path(self.temp.name) / "restart-replay"
        paths = (IPv4InterfacePath("wlan-test", "192.168.50.2", 24, "192.168.50.255"),)
        first_chunk = b"a" * (256 * 1024)
        descriptor = ChunkDescriptor(
            "restart-replay-001", 0, first_chunk, hashlib.sha256(first_chunk).hexdigest())
        offer = TransferOffer(
            "restart-replay-001", "android-peer-1", "python-node-1",
            "replay.bin", len(first_chunk) + 1,
            hashlib.sha256(first_chunk + b"b").hexdigest(), 100,
        )
        first = LanTransferService(
            local_peer_id="python-node-1", data_dir=str(data_dir),
            credentials_path=self.credentials_path, path_provider=lambda: paths,
            server_factory=lambda **kwargs: FakeServer(**kwargs),
        )
        first.start()
        first.store.create_offer(offer, 101)
        first.store.transition(offer.transfer_id, TransferState.ACCEPTED, 102)
        first.store.transition(offer.transfer_id, TransferState.TRANSFERRING, 103)
        first.filesystem.write_chunk(descriptor, 104)
        first.close()

        restarted = LanTransferService(
            local_peer_id="python-node-1", data_dir=str(data_dir),
            credentials_path=self.credentials_path, path_provider=lambda: paths,
            server_factory=lambda **kwargs: FakeServer(**kwargs),
        )
        try:
            replay_result = restarted.filesystem.write_chunk(descriptor, 105)
            self.assertFalse(replay_result)
            self.assertEqual((1,), restarted.filesystem.reconcile(offer.transfer_id, 106))
            self.assertEqual((1,), restarted.store.missing_chunks(offer.transfer_id))
            self.assertEqual(1, len(list((data_dir / "staging").glob("*.part"))))
        finally:
            restarted.close()

    def test_service_requires_stable_identity_and_unambiguous_path(self):
        with self.assertRaisesRegex(TransferValidationError, "stable"):
            LanTransferService(
                local_peer_id="",
                data_dir=str(Path(self.temp.name) / "bad-data"),
                credentials_path=self.credentials_path,
            )

        service = LanTransferService(
            local_peer_id="python-node-1",
            data_dir=str(Path(self.temp.name) / "ambiguous-data"),
            credentials_path=self.credentials_path,
            path_provider=lambda: (
                IPv4InterfacePath("wlan0", "192.168.1.2", 24, "192.168.1.255"),
                IPv4InterfacePath("eth0", "10.0.0.2", 24, "10.0.0.255"),
            ),
            server_factory=lambda **kwargs: FakeServer(**kwargs),
        )
        with self.assertRaisesRegex(TransferValidationError, "multiple"):
            service.start()
        self.assertFalse(service.is_running)
        service.close()


if __name__ == "__main__":
    unittest.main()
