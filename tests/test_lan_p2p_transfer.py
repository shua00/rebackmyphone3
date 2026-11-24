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


if __name__ == "__main__":
    unittest.main()
