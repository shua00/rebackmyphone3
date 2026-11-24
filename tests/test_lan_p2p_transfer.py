import hashlib
import unittest

from lan_p2p_transfer import (
    ChunkDescriptor,
    TransferLimits,
    TransferOffer,
    TransferState,
    TransferTransitionError,
    TransferValidationError,
    chunk_count,
    expected_chunk_size,
    is_terminal_state,
    missing_chunk_indexes,
    transition_state,
    validate_chunk,
    validate_offer,
)
from lan_p2p_transfer_store import TransferStore
from lan_p2p_transfer_files import TransferFilesystem
import tempfile
from pathlib import Path
import subprocess
import sys


def make_offer(size=9, filename="photo.jpg", transfer_id="transfer-001"):
    return TransferOffer(
        transfer_id=transfer_id,
        sender_peer_id="android-a",
        target_peer_id="desktop-b",
        filename=filename,
        size_bytes=size,
        sha256=hashlib.sha256(b"whole-file").hexdigest(),
        created_at_ms=123456,
    )


class TransferOfferValidationTests(unittest.TestCase):
    def test_valid_offer_and_zero_byte_file_are_supported(self):
        validate_offer(make_offer(size=0))
        validate_offer(make_offer(filename="家庭照片📷.jpg"))

    def test_rejects_path_traversal_and_control_characters(self):
        for filename in ("../secret.txt", "folder\\secret.txt", ".", "..", "bad\nname"):
            with self.subTest(filename=filename):
                with self.assertRaises(TransferValidationError):
                    validate_offer(make_offer(filename=filename))

    def test_rejects_invalid_hash_size_and_identity(self):
        bad_offers = (
            TransferOffer("x", "a", "b", "x", 1, "A" * 64, 1),
            TransferOffer("x", "a", "a", "x", 1, "a" * 64, 1),
            TransferOffer("x", "a", "b", "x", -1, "a" * 64, 1),
            TransferOffer("x", "a", "b", "x", 1, "a" * 63, 1),
            TransferOffer("x", "a", "b", "x", True, "a" * 64, 1),
            TransferOffer(None, "a", "b", "x", 1, "a" * 64, 1),
            TransferOffer("x", None, "b", "x", 1, "a" * 64, 1),
        )
        for offer in bad_offers:
            with self.subTest(offer=offer):
                with self.assertRaises(TransferValidationError):
                    validate_offer(offer)

    def test_configured_file_and_filename_limits_are_enforced(self):
        limits = TransferLimits(max_file_bytes=10, max_filename_utf8_bytes=8)
        with self.assertRaises(TransferValidationError):
            validate_offer(make_offer(size=11), limits)
        with self.assertRaises(TransferValidationError):
            validate_offer(make_offer(filename="long-name.txt"), limits)


class TransferChunkContractTests(unittest.TestCase):
    def test_chunk_geometry_handles_empty_exact_and_partial_final_chunks(self):
        self.assertEqual(0, chunk_count(0, 4096))
        self.assertEqual(2, chunk_count(8192, 4096))
        self.assertEqual(3, chunk_count(8193, 4096))
        self.assertEqual(4096, expected_chunk_size(8193, 4096, 1))
        self.assertEqual(1, expected_chunk_size(8193, 4096, 2))

    def test_validates_transfer_id_index_exact_length_and_digest(self):
        limits = TransferLimits(chunk_size_bytes=4096)
        offer = make_offer(size=4097)
        first = b"a" * 4096
        validate_chunk(
            offer,
            ChunkDescriptor(offer.transfer_id, 0, first, hashlib.sha256(first).hexdigest()),
            limits,
        )
        last = b"b"
        validate_chunk(
            offer,
            ChunkDescriptor(offer.transfer_id, 1, last, hashlib.sha256(last).hexdigest()),
            limits,
        )
        invalid = (
            ChunkDescriptor("other", 0, first, hashlib.sha256(first).hexdigest()),
            ChunkDescriptor(offer.transfer_id, 2, b"x", hashlib.sha256(b"x").hexdigest()),
            ChunkDescriptor(offer.transfer_id, 0, first[:-1], hashlib.sha256(first[:-1]).hexdigest()),
            ChunkDescriptor(offer.transfer_id, 0, first, "0" * 64),
        )
        for chunk in invalid:
            with self.subTest(chunk=chunk):
                with self.assertRaises(TransferValidationError):
                    validate_chunk(offer, chunk, limits)

    def test_resume_returns_only_missing_chunks_in_order(self):
        self.assertEqual((1, 3), missing_chunk_indexes(5, [4, 2, 0, 2]))
        self.assertEqual((), missing_chunk_indexes(0, []))
        with self.assertRaises(TransferValidationError):
            missing_chunk_indexes(3, [3])


class TransferStateMachineTests(unittest.TestCase):
    def test_happy_path_and_pause_resume(self):
        state = transition_state(TransferState.OFFERED, TransferState.ACCEPTED)
        state = transition_state(state, TransferState.TRANSFERRING)
        state = transition_state(state, TransferState.PAUSED)
        state = transition_state(state, TransferState.TRANSFERRING)
        state = transition_state(state, TransferState.VERIFYING)
        state = transition_state(state, TransferState.COMPLETED)
        self.assertTrue(is_terminal_state(state))

    def test_repeated_events_are_idempotent_but_terminal_states_are_final(self):
        self.assertEqual(
            TransferState.ACCEPTED,
            transition_state(TransferState.ACCEPTED, TransferState.ACCEPTED),
        )
        for terminal in (
            TransferState.COMPLETED, TransferState.REJECTED,
            TransferState.CANCELLED, TransferState.FAILED, TransferState.EXPIRED,
        ):
            with self.subTest(terminal=terminal):
                with self.assertRaises(TransferTransitionError):
                    transition_state(terminal, TransferState.TRANSFERRING)

    def test_illegal_shortcuts_and_unknown_states_are_rejected(self):
        with self.assertRaises(TransferTransitionError):
            transition_state(TransferState.OFFERED, TransferState.COMPLETED)
        with self.assertRaises(TransferTransitionError):
            transition_state("offered", TransferState.ACCEPTED)



class TransferStorePersistenceTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.db_path=str(Path(self.temp_dir.name)/"transfers.sqlite3")
        self.limits=TransferLimits(chunk_size_bytes=4096,max_active_transfers=2)
        self.store=TransferStore(self.db_path,self.limits)
        self.offer=make_offer(size=4097,transfer_id="persist-001")

    def _start_transfer(self):
        self.store.create_offer(self.offer,100)
        self.store.transition(self.offer.transfer_id,TransferState.ACCEPTED,101)
        self.store.transition(self.offer.transfer_id,TransferState.TRANSFERRING,102)

    def test_offer_and_partial_chunk_ledger_survive_store_reopen(self):
        self._start_transfer()
        payload=b"a"*4096
        chunk=ChunkDescriptor(self.offer.transfer_id,0,payload,hashlib.sha256(payload).hexdigest())
        self.assertTrue(self.store.record_verified_chunk(chunk,103))
        self.assertFalse(self.store.record_verified_chunk(chunk,104))
        self.store=TransferStore(self.db_path,self.limits)
        record=self.store.get_transfer(self.offer.transfer_id)
        self.assertEqual(TransferState.TRANSFERRING,record.state)
        self.assertEqual((0,),self.store.received_chunk_indexes(self.offer.transfer_id))
        self.assertEqual((1,),self.store.missing_chunks(self.offer.transfer_id))

    def test_conflicting_chunk_replay_is_rejected_without_overwriting_ledger(self):
        self._start_transfer()
        payload=b"a"*4096
        first=ChunkDescriptor(self.offer.transfer_id,0,payload,hashlib.sha256(payload).hexdigest())
        self.store.record_verified_chunk(first,103)
        changed=b"b"*4096
        replay=ChunkDescriptor(self.offer.transfer_id,0,changed,hashlib.sha256(changed).hexdigest())
        with self.assertRaises(TransferValidationError):
            self.store.record_verified_chunk(replay,104)
        self.assertEqual((0,),self.store.received_chunk_indexes(self.offer.transfer_id))

    def test_verification_requires_all_chunks_and_matching_whole_file_hash(self):
        first,last=b"a"*4096,b"b"
        whole=hashlib.sha256(first+last).hexdigest()
        self.offer=TransferOffer(self.offer.transfer_id,self.offer.sender_peer_id,
            self.offer.target_peer_id,self.offer.filename,len(first+last),whole,
            self.offer.created_at_ms,self.offer.mime_type)
        self._start_transfer()
        with self.assertRaises(TransferTransitionError):
            self.store.transition(self.offer.transfer_id,TransferState.VERIFYING,103)
        for index,payload in enumerate((first,last)):
            self.store.record_verified_chunk(ChunkDescriptor(self.offer.transfer_id,index,
                payload,hashlib.sha256(payload).hexdigest()),104+index)
        self.store.transition(self.offer.transfer_id,TransferState.VERIFYING,106)
        with self.assertRaises(TransferValidationError):
            self.store.complete_after_verification(self.offer.transfer_id,"0"*64,107)
        self.assertEqual(TransferState.VERIFYING,self.store.get_transfer(self.offer.transfer_id).state)
        self.assertEqual(TransferState.COMPLETED,
            self.store.complete_after_verification(self.offer.transfer_id,whole,108))

    def test_transfer_id_reuse_with_different_offer_is_rejected(self):
        self.store.create_offer(self.offer,100)
        altered=TransferOffer(self.offer.transfer_id,self.offer.sender_peer_id,
            self.offer.target_peer_id,"different.jpg",self.offer.size_bytes,
            self.offer.sha256,self.offer.created_at_ms,self.offer.mime_type)
        with self.assertRaises(TransferValidationError):
            self.store.create_offer(altered,101)

    def test_terminal_task_does_not_reopen(self):
        self.store.create_offer(self.offer,100)
        self.store.transition(self.offer.transfer_id,TransferState.REJECTED,101)
        with self.assertRaises(TransferTransitionError):
            self.store.transition(self.offer.transfer_id,TransferState.ACCEPTED,102)

    def test_chunk_ledger_survives_a_real_python_process_restart(self):
        code = """
import hashlib,sys
from lan_p2p_transfer import ChunkDescriptor,TransferLimits,TransferOffer,TransferState
from lan_p2p_transfer_store import TransferStore
path=sys.argv[1]
limits=TransferLimits(chunk_size_bytes=4096)
payload=b'z'*4096
offer=TransferOffer('process-restart','sender','receiver','payload.bin',
    len(payload),hashlib.sha256(b'whole').hexdigest(),10)
store=TransferStore(path,limits)
assert store.create_offer(offer,11)
store.transition(offer.transfer_id,TransferState.ACCEPTED,12)
store.transition(offer.transfer_id,TransferState.TRANSFERRING,13)
chunk=ChunkDescriptor(offer.transfer_id,0,payload,hashlib.sha256(payload).hexdigest())
assert store.record_verified_chunk(chunk,14)
"""
        result=subprocess.run([sys.executable,"-c",code,self.db_path],
            cwd=str(Path(__file__).resolve().parents[1]),
            capture_output=True,text=True,timeout=20)
        self.assertEqual(0,result.returncode,result.stderr)
        reopened=TransferStore(self.db_path,self.limits)
        record=reopened.get_transfer("process-restart")
        self.assertEqual(TransferState.TRANSFERRING,record.state)
        self.assertEqual((0,),reopened.received_chunk_indexes("process-restart"))
        self.assertEqual((),reopened.missing_chunks("process-restart"))



class TransferFilesystemTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        root = Path(self.temp_dir.name)
        self.db = str(root / "ledger.sqlite3")
        self.staging = str(root / "staging")
        self.received = str(root / "received")
        self.limits = TransferLimits(chunk_size_bytes=4096, max_active_transfers=2)
        self.store = TransferStore(self.db, self.limits)
        self.files = TransferFilesystem(self.store, self.staging, self.received,
                                        max_staging_bytes=16 * 1024)
        self.payload = b"a" * 4096 + b"tail"
        self.offer = TransferOffer("fs-stage-01", "sender", "receiver", "photo.jpg",
            len(self.payload), hashlib.sha256(self.payload).hexdigest(), 100)
        self.store.create_offer(self.offer, 100)
        self.store.transition(self.offer.transfer_id, TransferState.ACCEPTED, 101)
        self.store.transition(self.offer.transfer_id, TransferState.TRANSFERRING, 102)

    def _chunk(self, index):
        payload = self.payload[index * 4096:(index + 1) * 4096]
        return ChunkDescriptor(self.offer.transfer_id, index, payload,
                               hashlib.sha256(payload).hexdigest())

    def test_chunk_is_fsynced_before_receipt_and_assembled_atomically(self):
        for index in range(2):
            self.assertTrue(self.files.write_chunk(self._chunk(index), 103 + index))
        result = self.files.assemble_and_verify(self.offer.transfer_id, 106)
        self.assertEqual(self.payload, result.read_bytes())
        self.assertEqual(TransferState.COMPLETED,
                         self.store.get_transfer(self.offer.transfer_id).state)
        self.assertEqual((), self.store.missing_chunks(self.offer.transfer_id))

    def test_resume_revalidates_and_invalidates_missing_or_corrupt_chunk(self):
        self.files.write_chunk(self._chunk(0), 103)
        self.files.write_chunk(self._chunk(1), 104)
        chunk_path = Path(self.staging) / self.files._key(self.offer.transfer_id) / "chunk-00000000.part"
        chunk_path.write_bytes(b"x" * 4096)
        self.assertEqual((0,), self.files.reconcile(self.offer.transfer_id, 105))
        self.assertEqual((1,), self.store.received_chunk_indexes(self.offer.transfer_id))

    def test_quota_rejects_before_persisting_receipt(self):
        constrained = TransferFilesystem(self.store, self.staging, self.received,
                                         max_staging_bytes=4095)
        with self.assertRaises(TransferValidationError):
            constrained.write_chunk(self._chunk(0), 103)
        self.assertEqual((), self.store.received_chunk_indexes(self.offer.transfer_id))

    def test_symlink_chunk_is_never_followed_or_accepted(self):
        task = Path(self.staging) / self.files._key(self.offer.transfer_id)
        task.mkdir(mode=0o700)
        target = Path(self.temp_dir.name) / "outside"
        target.write_bytes(self._chunk(0).payload)
        (task / "chunk-00000000.part").symlink_to(target)
        with self.assertRaises(TransferValidationError):
            self.files.write_chunk(self._chunk(0), 103)
        self.assertEqual((), self.store.received_chunk_indexes(self.offer.transfer_id))

    def test_empty_file_is_verified_and_published(self):
        offer = TransferOffer("fs-empty", "sender", "receiver", "empty.bin", 0,
                              hashlib.sha256(b"").hexdigest(), 200)
        self.store.create_offer(offer, 200)
        self.store.transition(offer.transfer_id, TransferState.ACCEPTED, 201)
        self.store.transition(offer.transfer_id, TransferState.TRANSFERRING, 202)
        result = self.files.assemble_and_verify(offer.transfer_id, 203)
        self.assertEqual(b"", result.read_bytes())
        self.assertEqual(TransferState.COMPLETED,
                         self.store.get_transfer(offer.transfer_id).state)

    def test_durable_orphan_chunk_after_crash_can_be_reconciled_on_retry(self):
        chunk = self._chunk(0)
        task = Path(self.staging) / self.files._key(self.offer.transfer_id)
        task.mkdir(mode=0o700)
        (task / "chunk-00000000.part").write_bytes(chunk.payload)
        self.assertEqual((0, 1), self.store.missing_chunks(self.offer.transfer_id))
        self.assertTrue(self.files.write_chunk(chunk, 103))
        self.assertEqual((1,), self.store.missing_chunks(self.offer.transfer_id))



    def test_resume_treats_symlink_as_corrupt_and_revokes_receipt(self):
        self.files.write_chunk(self._chunk(0), 103)
        task = Path(self.staging) / self.files._key(self.offer.transfer_id)
        path = task / "chunk-00000000.part"
        path.unlink()
        target = Path(self.temp_dir.name) / "outside-corrupt"
        target.write_bytes(self._chunk(0).payload)
        path.symlink_to(target)
        self.assertEqual((0, 1), self.files.reconcile(self.offer.transfer_id, 104))
        self.assertEqual((), self.store.received_chunk_indexes(self.offer.transfer_id))

    def test_terminal_cleanup_removes_only_owned_staging_tree(self):
        self.files.write_chunk(self._chunk(0), 103)
        task = Path(self.staging) / self.files._key(self.offer.transfer_id)
        self.store.transition(self.offer.transfer_id, TransferState.CANCELLED, 104)
        self.files.cleanup(self.offer.transfer_id)
        self.assertFalse(task.exists())



class TransferTcpFrameTests(unittest.TestCase):
    SECRET = b"test-transfer-session-secret-32"

    def _round_trip(self, frame, *, expected_sender="sender", expected_target="receiver"):
        import socket
        from lan_p2p_transfer_tcp import send_frame, recv_frame
        left, right = socket.socketpair()
        try:
            send_frame(left, frame, self.SECRET)
            return recv_frame(right, self.SECRET,
                              expected_sender_peer_id=expected_sender,
                              expected_target_peer_id=expected_target)
        finally:
            left.close()
            right.close()

    def test_chunk_frame_round_trip_and_peer_binding(self):
        from lan_p2p_transfer_tcp import TransferFrame
        payload = b"chunk-payload"
        frame = TransferFrame("chunk", "transfer-tcp-1", "sender", "receiver",
                              index=3, payload=payload)
        decoded = self._round_trip(frame)
        self.assertEqual(frame, decoded)

    def test_control_frame_round_trip_with_missing_indexes(self):
        from lan_p2p_transfer_tcp import TransferFrame
        frame = TransferFrame("missing", "transfer-tcp-1", "sender", "receiver",
                              metadata={"indexes": [1, 4, 8]})
        self.assertEqual(frame, self._round_trip(frame))

    def test_fragmented_tcp_reads_are_reassembled(self):
        import socket
        import threading
        from lan_p2p_transfer_tcp import TransferFrame, encode_frame, recv_frame
        left, right = socket.socketpair()
        frame = TransferFrame("chunk", "transfer-tcp-2", "sender", "receiver",
                              index=0, payload=b"x" * 8192)
        encoded = encode_frame(frame, self.SECRET)
        def fragmented_write():
            for offset in range(0, len(encoded), 37):
                left.sendall(encoded[offset:offset + 37])
            left.shutdown(socket.SHUT_WR)
        worker = threading.Thread(target=fragmented_write)
        try:
            worker.start()
            decoded = recv_frame(right, self.SECRET)
            self.assertEqual(frame, decoded)
            worker.join(timeout=2)
            self.assertFalse(worker.is_alive())
        finally:
            left.close()
            right.close()

    def test_rejects_wrong_secret_and_peer_identity(self):
        import socket
        from lan_p2p_transfer_tcp import TransferFrame, encode_frame, recv_frame, TransferFrameError
        frame = TransferFrame("chunk", "transfer-tcp-3", "sender", "receiver",
                              index=0, payload=b"abc")
        wire = encode_frame(frame, self.SECRET)
        left, right = socket.socketpair()
        try:
            left.sendall(wire)
            with self.assertRaises(TransferFrameError):
                recv_frame(right, b"wrong-secret-material-32")
        finally:
            left.close()
            right.close()
        left, right = socket.socketpair()
        try:
            left.sendall(wire)
            with self.assertRaises(TransferFrameError):
                recv_frame(right, self.SECRET, expected_sender_peer_id="other-peer")
        finally:
            left.close()
            right.close()

    def test_rejects_oversized_payload_before_reading_body(self):
        import socket
        import json
        import struct
        from lan_p2p_transfer_tcp import _MAGIC, TransferFrameError, recv_frame
        header = {
            "v": 1, "kind": "chunk", "transfer_id": "t", "sender_peer_id": "sender",
            "target_peer_id": "receiver", "index": 0, "payload_size": 999999,
            "payload_sha256": "0" * 64, "metadata": None, "auth": "0" * 64
        }
        raw = json.dumps(header, separators=(",", ":"), sort_keys=True).encode()
        left, right = socket.socketpair()
        try:
            left.sendall(_MAGIC + struct.pack("!I", len(raw)) + raw)
            with self.assertRaises(TransferFrameError):
                recv_frame(right, self.SECRET, max_payload_bytes=1024)
        finally:
            left.close()
            right.close()

    def test_rejects_truncated_stream(self):
        import socket
        from lan_p2p_transfer_tcp import TransferFrameError, recv_frame
        left, right = socket.socketpair()
        try:
            left.sendall(b"LNP2\x00")
            left.shutdown(socket.SHUT_WR)
            with self.assertRaises(EOFError):
                recv_frame(right, self.SECRET)
        finally:
            left.close()
            right.close()

    def test_control_frames_cannot_smuggle_payload(self):
        from lan_p2p_transfer_tcp import TransferFrame, TransferFrameError, encode_frame
        with self.assertRaises(TransferFrameError):
            encode_frame(TransferFrame("complete", "t", "sender", "receiver",
                                       payload=b"unexpected"), self.SECRET)



class TransferTcpSessionTests(unittest.TestCase):
    SECRET = b"session-integration-secret-32-bytes"

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        root = Path(self.temp_dir.name)
        self.store = TransferStore(str(root / "session.sqlite3"),
                                   TransferLimits(chunk_size_bytes=4096))
        self.files = TransferFilesystem(self.store, str(root / "stage"),
                                        str(root / "received"))
        self.payload = b"b" * 4096 + b"tail"
        self.offer = TransferOffer("session-transfer-1", "sender", "receiver",
            "clip.bin", len(self.payload), hashlib.sha256(self.payload).hexdigest(), 10)
        self.store.create_offer(self.offer, 10)
        self.store.transition(self.offer.transfer_id, TransferState.ACCEPTED, 11)
        self.store.transition(self.offer.transfer_id, TransferState.TRANSFERRING, 12)

    def _frame(self, kind, index=None, payload=b"", metadata=None):
        from lan_p2p_transfer_tcp import TransferFrame
        return TransferFrame(kind, self.offer.transfer_id, "sender", "receiver",
                             index=index, payload=payload, metadata=metadata)

    def test_tcp_session_writes_chunks_negotiates_missing_and_completes(self):
        import socket
        import threading
        from lan_p2p_transfer_tcp import send_frame, recv_frame
        from lan_p2p_transfer_session import serve_transfer_session
        server, client = socket.socketpair()
        errors = []
        def serve():
            try:
                serve_transfer_session(server, self.store, self.files,
                    local_peer_id="receiver", authenticated_peer_id="sender",
                    session_secret=self.SECRET, now_ms=lambda: 20, max_frames=4)
            except Exception as exc:
                errors.append(exc)
        worker = threading.Thread(target=serve)
        worker.start()
        try:
            first = self.payload[:4096]
            send_frame(client, self._frame("chunk", 0, first), self.SECRET)
            ack = recv_frame(client, self.SECRET, expected_sender_peer_id="receiver",
                             expected_target_peer_id="sender")
            self.assertEqual("chunk_ack", ack.kind)
            self.assertEqual({"index": 0, "new_receipt": True}, ack.metadata)

            send_frame(client, self._frame("missing"), self.SECRET)
            missing = recv_frame(client, self.SECRET, expected_sender_peer_id="receiver",
                                 expected_target_peer_id="sender")
            self.assertEqual("missing", missing.kind)
            self.assertEqual([1], missing.metadata["indexes"])

            last = self.payload[4096:]
            send_frame(client, self._frame("chunk", 1, last), self.SECRET)
            ack = recv_frame(client, self.SECRET, expected_sender_peer_id="receiver",
                             expected_target_peer_id="sender")
            self.assertEqual("chunk_ack", ack.kind)

            send_frame(client, self._frame("complete"), self.SECRET)
            complete = recv_frame(client, self.SECRET, expected_sender_peer_id="receiver",
                                  expected_target_peer_id="sender")
            self.assertEqual("complete", complete.kind)
            self.assertEqual("completed", complete.metadata["state"])
            self.assertEqual(TransferState.COMPLETED,
                             self.store.get_transfer(self.offer.transfer_id).state)
        finally:
            client.close()
            server.close()
            worker.join(timeout=3)
        self.assertFalse(worker.is_alive())
        self.assertEqual([], errors)



    def test_terminal_cancel_reports_actual_state_and_duplicate_complete_is_idempotent(self):
        import socket
        import threading
        from lan_p2p_transfer_tcp import send_frame, recv_frame
        from lan_p2p_transfer_session import serve_transfer_session
        for index in range(2):
            payload = self.payload[index * 4096:(index + 1) * 4096]
            self.files.write_chunk(
                ChunkDescriptor(self.offer.transfer_id, index, payload,
                                hashlib.sha256(payload).hexdigest()), 50 + index)
        self.files.assemble_and_verify(self.offer.transfer_id, 52)
        server, client = socket.socketpair()
        errors = []
        def serve():
            try:
                serve_transfer_session(server, self.store, self.files,
                    local_peer_id="receiver", authenticated_peer_id="sender",
                    session_secret=self.SECRET, now_ms=lambda: 60, max_frames=2)
            except Exception as exc:
                errors.append(exc)
        worker = threading.Thread(target=serve)
        worker.start()
        try:
            send_frame(client, self._frame("complete"), self.SECRET)
            done = recv_frame(client, self.SECRET)
            self.assertEqual({"state": "completed"}, done.metadata)
            send_frame(client, self._frame("cancel"), self.SECRET)
            cancelled = recv_frame(client, self.SECRET)
            self.assertEqual({"state": "completed"}, cancelled.metadata)
        finally:
            client.close()
            server.close()
            worker.join(timeout=3)
        self.assertFalse(worker.is_alive())
        self.assertEqual([], errors)

    def test_tcp_session_rejects_wrong_fixed_recipient_without_writing(self):
        import socket
        import threading
        from lan_p2p_transfer_tcp import send_frame
        from lan_p2p_transfer_session import serve_transfer_session
        server, client = socket.socketpair()
        other = TransferOffer("wrong-target-transfer", "sender", "other-peer",
            "clip.bin", len(self.payload), hashlib.sha256(self.payload).hexdigest(), 30)
        self.store.create_offer(other, 30)
        self.store.transition(other.transfer_id, TransferState.ACCEPTED, 31)
        self.store.transition(other.transfer_id, TransferState.TRANSFERRING, 32)
        errors = []
        def serve():
            try:
                serve_transfer_session(server, self.store, self.files,
                    local_peer_id="receiver", authenticated_peer_id="sender",
                    session_secret=self.SECRET, now_ms=lambda: 40, max_frames=1)
            except Exception as exc:
                errors.append(exc)
        worker = threading.Thread(target=serve)
        worker.start()
        try:
            from lan_p2p_transfer_tcp import TransferFrame
            send_frame(client, TransferFrame("chunk", other.transfer_id, "sender",
                "receiver", index=0, payload=self.payload[:4096]), self.SECRET)
            client.shutdown(socket.SHUT_WR)
        finally:
            client.close()
            worker.join(timeout=3)
            server.close()
        self.assertFalse(worker.is_alive())
        self.assertEqual(1, len(errors))
        self.assertIsInstance(errors[0], TransferValidationError)
        self.assertEqual((), self.store.received_chunk_indexes(other.transfer_id))


if __name__ == "__main__":
    unittest.main()
