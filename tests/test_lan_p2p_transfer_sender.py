import hashlib
import socket
import tempfile
import threading
import unittest
from pathlib import Path

from lan_p2p_transfer import TransferLimits, TransferOffer, TransferState
from lan_p2p_transfer_store import TransferStore
from lan_p2p_transfer_files import TransferFilesystem
from lan_p2p_transfer_session import serve_transfer_session
from lan_p2p_transfer_sender import send_file_over_session


class TransferSenderIntegrationTests(unittest.TestCase):
    SECRET = b"sender-integration-secret-32-bytes"

    def test_sender_drives_receiver_session_to_verified_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b"a" * 4096 + b"tail"
            source = root / "source.bin"
            source.write_bytes(payload)
            offer = TransferOffer("sender-roundtrip", "sender", "receiver",
                "source.bin", len(payload), hashlib.sha256(payload).hexdigest(), 1)
            store = TransferStore(str(root / "ledger.db"), TransferLimits(chunk_size_bytes=4096))
            files = TransferFilesystem(store, str(root / "stage"), str(root / "received"))
            store.create_offer(offer, 1)
            store.transition(offer.transfer_id, TransferState.ACCEPTED, 2)
            store.transition(offer.transfer_id, TransferState.TRANSFERRING, 3)
            server, client = socket.socketpair()
            errors = []
            def serve():
                try:
                    serve_transfer_session(server, store, files, local_peer_id="receiver",
                        authenticated_peer_id="sender", session_secret=self.SECRET,
                        now_ms=lambda: 10, max_frames=4)
                except Exception as exc:
                    errors.append(exc)
            worker = threading.Thread(target=serve)
            worker.start()
            try:
                remaining = send_file_over_session(client, str(source), offer,
                    local_peer_id="sender", remote_peer_id="receiver",
                    session_secret=self.SECRET, chunk_size_bytes=4096)
                self.assertEqual((), remaining)
            finally:
                client.close()
                server.close()
                worker.join(timeout=3)
            self.assertFalse(worker.is_alive())
            self.assertEqual([], errors)
            self.assertEqual(TransferState.COMPLETED, store.get_transfer(offer.transfer_id).state)
            published = next((root / "received").iterdir())
            self.assertEqual(payload, published.read_bytes())

    def test_sender_resumes_large_missing_set_across_bounded_sessions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b"a" * 4096 + b"b" * 4096 + b"tail"
            source = root / "large.bin"
            source.write_bytes(payload)
            offer = TransferOffer("sender-batched", "sender", "receiver",
                "large.bin", len(payload), hashlib.sha256(payload).hexdigest(), 1)
            store = TransferStore(str(root / "ledger.db"), TransferLimits(chunk_size_bytes=4096))
            files = TransferFilesystem(store, str(root / "stage"), str(root / "received"))
            store.create_offer(offer, 1)
            store.transition(offer.transfer_id, TransferState.ACCEPTED, 2)
            store.transition(offer.transfer_id, TransferState.TRANSFERRING, 3)

            remaining = (0, 1, 2)
            for expected_remaining in ((1, 2), (2,), ()):
                server, client = socket.socketpair()
                errors = []
                def serve():
                    try:
                        serve_transfer_session(server, store, files, local_peer_id="receiver",
                            authenticated_peer_id="sender", session_secret=self.SECRET,
                            now_ms=lambda: 10, max_frames=3)
                    except Exception as exc:
                        errors.append(exc)
                worker = threading.Thread(target=serve)
                worker.start()
                try:
                    remaining = send_file_over_session(client, str(source), offer,
                        local_peer_id="sender", remote_peer_id="receiver",
                        session_secret=self.SECRET, chunk_size_bytes=4096,
                        max_chunks_per_session=1)
                finally:
                    client.close()
                    server.close()
                    worker.join(timeout=3)
                self.assertFalse(worker.is_alive())
                self.assertEqual([], errors)
                self.assertEqual(expected_remaining, remaining)
            self.assertEqual(TransferState.COMPLETED, store.get_transfer(offer.transfer_id).state)
            published = next((root / "received").iterdir())
            self.assertEqual(payload, published.read_bytes())

    def test_sender_rejects_wrong_authenticated_participant_before_network_io(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.bin"
            payload = b"data"
            source.write_bytes(payload)
            offer = TransferOffer("wrong-peer", "sender", "receiver", "x.bin", 4,
                                  hashlib.sha256(payload).hexdigest(), 1)
            left, right = socket.socketpair()
            try:
                with self.assertRaises(ValueError):
                    send_file_over_session(left, str(source), offer,
                        local_peer_id="sender", remote_peer_id="other",
                        session_secret=self.SECRET, chunk_size_bytes=4096)
            finally:
                left.close()
                right.close()


if __name__ == "__main__":
    unittest.main()
