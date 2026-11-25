"""Regression coverage for multi-device group-style delivery semantics.

These tests exercise the existing fixed-recipient fan-out ledger. They do not
claim that an Android multi-device group-chat UI or a live three-phone session
already exists; those remain separate integration/physical-device gates.
"""
import sqlite3
import tempfile
import unittest
from pathlib import Path

from lan_p2p_core import (
    acknowledge_outgoing_message,
    load_stable_peer_id,
    persist_outgoing_message,
)


class MultiPeerDeliveryAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.db_path = str(Path(self.temp_dir.name) / "multi-peer.sqlite3")
        load_stable_peer_id(self.db_path, "server2")

    def _state_and_targets(self, message_id):
        with sqlite3.connect(self.db_path) as connection:
            state = connection.execute(
                "SELECT state FROM lan_outbox WHERE message_id=?", (message_id,)
            ).fetchone()[0]
            targets = connection.execute(
                "SELECT peer_id, acked FROM lan_outbox_targets "
                "WHERE message_id=? ORDER BY peer_id", (message_id,)
            ).fetchall()
        return state, targets

    def test_three_peer_fanout_requires_each_fixed_recipient_ack(self):
        peers = ["android-a", "android-b", "android-c"]
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "group-3-peer",
            "group test", peers,
        ))

        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "group-3-peer", "android-a"))
        state, targets = self._state_and_targets("group-3-peer")
        self.assertEqual("partial", state)
        self.assertEqual(
            [("android-a", 1), ("android-b", 0), ("android-c", 0)], targets)

        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "group-3-peer", "android-b"))
        self.assertEqual("partial", self._state_and_targets("group-3-peer")[0])

        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "group-3-peer", "android-c"))
        state, targets = self._state_and_targets("group-3-peer")
        self.assertEqual("delivered", state)
        self.assertTrue(all(acked == 1 for _, acked in targets))

    def test_late_peer_cannot_join_already_sent_recipient_snapshot(self):
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "fixed-snapshot",
            "do not expand old message", ["android-a", "android-b"],
        ))
        self.assertFalse(acknowledge_outgoing_message(
            self.db_path, "server2", "fixed-snapshot", "android-c"))
        state, targets = self._state_and_targets("fixed-snapshot")
        self.assertEqual("pending", state)
        self.assertEqual([("android-a", 0), ("android-b", 0)], targets)

    def test_duplicate_ack_is_idempotent_and_unknown_ack_never_completes(self):
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "idempotent-ack",
            "dedupe", ["android-a", "android-b", "android-c"],
        ))
        self.assertFalse(acknowledge_outgoing_message(
            self.db_path, "server2", "idempotent-ack", "unknown-peer"))
        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "idempotent-ack", "android-a"))
        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "idempotent-ack", "android-a"))
        state, targets = self._state_and_targets("idempotent-ack")
        self.assertEqual("partial", state)
        self.assertEqual(
            [("android-a", 1), ("android-b", 0), ("android-c", 0)], targets)


if __name__ == "__main__":
    unittest.main()
