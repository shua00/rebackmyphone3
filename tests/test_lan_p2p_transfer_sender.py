import hashlib
import socket
import tempfile
import threading
import unittest
from pathlib import Path

from lan_p2p_transfer import ChunkDescriptor, TransferLimits, TransferOffer, TransferState
from lan_p2p_transfer_store import TransferStore
from lan_p2p_transfer_files import TransferFilesystem
from lan_p2p_transfer_session import serve_transfer_session
from lan_p2p_transfer_sender import send_file_over_session, send_offer_over_session
from lan_p2p_transfer_tcp import TransferFrame, send_frame, recv_frame


class TransferSenderIntegrationTests(unittest.TestCase):
    SECRET = b"sender-integration-secret-32-bytes"


    def test_sender_sends_offer_and_receiver_keeps_it_pending(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b"offer-handshake"
            source = root / "source.txt"
            source.write_bytes(payload)
            offer = TransferOffer("sender-offer", "sender", "receiver", "source.txt",
                len(payload), hashlib.sha256(payload).hexdigest(), 1000, "text/plain")
            store = TransferStore(str(root / "ledger.db"), TransferLimits(chunk_size_bytes=4096))
            files = TransferFilesystem(store, str(root / "stage"), str(root / "received"))
            server, client = socket.socketpair()
            errors = []
            def serve():
                try:
                    serve_transfer_session(server, store, files, local_peer_id="receiver",
                        authenticated_peer_id="sender", session_secret=self.SECRET,
                        now_ms=lambda: 1000, max_frames=1)
                except Exception as exc:
                    errors.append(exc)
                finally:
                    server.close()
            worker = threading.Thread(target=serve)
            worker.start()
            try:
                ack = send_offer_over_session(client, str(source), offer,
                    local_peer_id="sender", remote_peer_id="receiver",
                    session_secret=self.SECRET, chunk_size_bytes=4096)
                self.assertEqual("offered", ack["state"])
                self.assertTrue(ack["new_offer"])
                self.assertEqual(TransferState.OFFERED, store.get_transfer(offer.transfer_id).state)
            finally:
                client.close()
                worker.join(timeout=2)
            self.assertFalse(worker.is_alive())
            self.assertEqual([], errors)

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
            for expected_remaining in ((1,), (2,), ()):
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

    def test_missing_index_control_frames_are_paged_for_large_transfers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b"x" * (4096 * 140)
            source = root / "many-chunks.bin"
            source.write_bytes(payload)
            offer = TransferOffer("sender-many-pages", "sender", "receiver",
                "many-chunks.bin", len(payload), hashlib.sha256(payload).hexdigest(), 1)
            store = TransferStore(str(root / "ledger.db"), TransferLimits(chunk_size_bytes=4096))
            files = TransferFilesystem(store, str(root / "stage"), str(root / "received"))
            store.create_offer(offer, 1)
            store.transition(offer.transfer_id, TransferState.ACCEPTED, 2)
            store.transition(offer.transfer_id, TransferState.TRANSFERRING, 3)

            for expected_remaining in (tuple(range(62, 124)), tuple(range(124, 140)), ()):
                server, client = socket.socketpair()
                errors = []
                def serve():
                    try:
                        serve_transfer_session(server, store, files, local_peer_id="receiver",
                            authenticated_peer_id="sender", session_secret=self.SECRET,
                            now_ms=lambda: 10, max_frames=64)
                    except Exception as exc:
                        errors.append(exc)
                worker = threading.Thread(target=serve)
                worker.start()
                try:
                    remaining = send_file_over_session(client, str(source), offer,
                        local_peer_id="sender", remote_peer_id="receiver",
                        session_secret=self.SECRET, chunk_size_bytes=4096,
                        )
                finally:
                    client.close()
                    server.close()
                    worker.join(timeout=5)
                self.assertFalse(worker.is_alive())
                self.assertEqual([], errors)
                self.assertEqual(expected_remaining, remaining)
            self.assertEqual(TransferState.COMPLETED, store.get_transfer(offer.transfer_id).state)
            published = next((root / "received").iterdir())
            self.assertEqual(payload, published.read_bytes())

    def test_complete_request_recovers_receiver_left_in_verifying_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b"recover-after-restart"
            offer = TransferOffer("sender-verifying-recovery", "sender", "receiver",
                "recover.txt", len(payload), hashlib.sha256(payload).hexdigest(), 1)
            store = TransferStore(str(root / "ledger.db"), TransferLimits(chunk_size_bytes=4096))
            files = TransferFilesystem(store, str(root / "stage"), str(root / "received"))
            store.create_offer(offer, 1)
            store.transition(offer.transfer_id, TransferState.ACCEPTED, 2)
            store.transition(offer.transfer_id, TransferState.TRANSFERRING, 3)
            files.write_chunk(ChunkDescriptor(offer.transfer_id, 0, payload,
                hashlib.sha256(payload).hexdigest()), 4)
            store.transition(offer.transfer_id, TransferState.VERIFYING, 5)

            server, client = socket.socketpair()
            errors = []
            def serve():
                try:
                    serve_transfer_session(server, store, files, local_peer_id="receiver",
                        authenticated_peer_id="sender", session_secret=self.SECRET,
                        now_ms=lambda: 10, max_frames=1)
                except Exception as exc:
                    errors.append(exc)
                finally:
                    server.close()
            worker = threading.Thread(target=serve)
            worker.start()
            try:
                send_frame(client, TransferFrame("complete", offer.transfer_id,
                    "sender", "receiver"), self.SECRET)
                response = recv_frame(client, self.SECRET,
                    expected_sender_peer_id="receiver", expected_target_peer_id="sender")
                self.assertEqual("complete", response.kind)
                self.assertEqual({"state": "completed"}, response.metadata)
            finally:
                client.close()
                worker.join(timeout=3)
            self.assertFalse(worker.is_alive())
            self.assertEqual([], errors)
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
