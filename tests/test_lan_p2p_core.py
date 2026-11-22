import os
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from lan_p2p_core import load_stable_peer_id, record_peer


class LanP2PCoreTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.temp_dir.name, "peers.sqlite3")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_service_identity_survives_restart_and_is_scoped(self):
        server_id = load_stable_peer_id(self.db_path, "server2")
        self.assertEqual(server_id, load_stable_peer_id(self.db_path, "server2"))
        rescue_id = load_stable_peer_id(self.db_path, "rescue2")
        self.assertNotEqual(server_id, rescue_id)

    def test_same_endpoint_hello_keeps_verified_status_but_route_change_invalidates_it(self):
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "active"))
        # A HELLO refresh from the same endpoint does not erase a valid verification.
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        conn = sqlite3.connect(self.db_path)
        try:
            same_route = conn.execute(
                "SELECT endpoint_ip,last_verified_at,status FROM lan_peers "
                "WHERE receiver_scope='server2' AND peer_id='android-a'"
            ).fetchone()
        finally:
            conn.close()
        self.assertGreater(same_route[1], 0)
        self.assertEqual(same_route[2], "active")

        # A new IP/port is a new route and must not inherit the old route's verified state.
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.11", 8888, "suspect"))
        conn = sqlite3.connect(self.db_path)
        try:
            changed_route = conn.execute(
                "SELECT endpoint_ip,last_verified_at,status FROM lan_peers "
                "WHERE receiver_scope='server2' AND peer_id='android-a'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(changed_route[0], "192.168.1.11")
        self.assertEqual(changed_route[1], 0)
        self.assertEqual(changed_route[2], "suspect")

    def test_same_peer_has_independent_service_scopes(self):
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        self.assertTrue(record_peer(self.db_path, "rescue2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        conn = sqlite3.connect(self.db_path)
        try:
            count = conn.execute(
                "SELECT COUNT(*) FROM lan_peers WHERE peer_id='android-a'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(count, 2)


    def test_authenticated_v2_packet_round_trip_and_tamper_rejection(self):
        from lan_p2p_core import encode_packet, decode_packet
        packet = {
            "type": "chat.message",
            "sender_peer_id": "android-a",
            "sender_peer_type": "android",
            "service_id": "android-main",
            "message_id": "message-1",
            "target_peer_id": "android-b",
            "created_at_ms": 123456,
            "body": "老人好，今天记得吃药"
        }
        encoded = encode_packet(packet, "test-secret")
        decoded = decode_packet(encoded, "test-secret")
        self.assertEqual(decoded["body"], packet["body"])
        tampered = encoded.replace("老人好".encode("utf-8"), "老人坏".encode("utf-8"))
        with self.assertRaises(ValueError):
            decode_packet(tampered, "test-secret")

    def test_authenticated_v2_packet_rejects_oversized_datagrams(self):
        from lan_p2p_core import encode_packet
        packet = {
            "type": "chat.message",
            "sender_peer_id": "android-a",
            "body": "x" * 2000
        }
        with self.assertRaises(ValueError):
            encode_packet(packet, "test-secret")




    def test_python_outbox_requires_every_fixed_recipient_ack(self):
        from lan_p2p_core import persist_outgoing_message, acknowledge_outgoing_message
        self.assertTrue(persist_outgoing_message(self.db_path, "server2", "server2-id",
                                                 "m-1", "hello", ["android-a", "android-b"]))
        self.assertFalse(acknowledge_outgoing_message(self.db_path, "server2", "m-1", "unknown"))
        self.assertTrue(acknowledge_outgoing_message(self.db_path, "server2", "m-1", "android-a"))
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute("SELECT state FROM lan_outbox WHERE message_id='m-1'").fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(state, "partial")
        self.assertTrue(acknowledge_outgoing_message(self.db_path, "server2", "m-1", "android-b"))
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute("SELECT state FROM lan_outbox WHERE message_id='m-1'").fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(state, "delivered")


    def test_unknown_recipient_ack_window_collects_multiple_peers(self):
        from lan_p2p_core import persist_outgoing_message, acknowledge_outgoing_message, record_peer, load_pending_outgoing_for_peer
        self.assertTrue(persist_outgoing_message(self.db_path, "server2", "server2-id",
                                                 "m-discovery", "hello", []))
        self.assertTrue(acknowledge_outgoing_message(self.db_path, "server2", "m-discovery", "android-a"))
        self.assertTrue(acknowledge_outgoing_message(self.db_path, "server2", "m-discovery", "android-b"))
        conn = sqlite3.connect(self.db_path)
        try:
            row = conn.execute("SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id='m-discovery'").fetchone()
            count = conn.execute("SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-discovery'").fetchone()[0]
            conn.execute("UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id='m-discovery'")
            conn.commit()
        finally:
            conn.close()
        self.assertEqual(row[0], "discovery_pending")
        self.assertEqual(count, 2)
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        load_pending_outgoing_for_peer(self.db_path, "server2", "android-a")
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute("SELECT state FROM lan_outbox WHERE message_id='m-discovery'").fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(state, "delivered")

    def test_ack_endpoint_must_match_recorded_peer_route(self):
        from lan_p2p_core import peer_endpoint_matches
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        self.assertTrue(peer_endpoint_matches(self.db_path, "server2", "android-a",
                                              "192.168.1.10", 8888))
        self.assertFalse(peer_endpoint_matches(self.db_path, "server2", "android-a",
                                               "192.168.1.11", 8888))
        self.assertFalse(peer_endpoint_matches(self.db_path, "server2", "android-a",
                                               "192.168.1.10", 8890))
        self.assertFalse(peer_endpoint_matches(self.db_path, "rescue2", "android-a",
                                               "192.168.1.10", 8888))


    def test_retry_only_returns_unacked_fixed_targets(self):
        from lan_p2p_core import (
            persist_outgoing_message, record_peer, load_pending_outgoing_for_peer,
            acknowledge_outgoing_message, mark_outgoing_attempt,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-retry", "hello",
            ["android-a", "android-b"]))
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        record_peer(self.db_path, "server2", "android-b", "android", "android-main",
                    "192.168.1.11", 8888, "active")

        first = load_pending_outgoing_for_peer(self.db_path, "server2", "android-a")
        second = load_pending_outgoing_for_peer(self.db_path, "server2", "android-b")
        self.assertEqual([x["message_id"] for x in first], ["m-retry"])
        self.assertEqual([x["message_id"] for x in second], ["m-retry"])

        self.assertTrue(mark_outgoing_attempt(
            self.db_path, "m-retry", "android-a", "192.168.1.10", 8888))
        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "m-retry", "android-a"))
        self.assertEqual(load_pending_outgoing_for_peer(
            self.db_path, "server2", "android-a"), [])
        remaining = load_pending_outgoing_for_peer(
            self.db_path, "server2", "android-b")
        self.assertEqual([x["message_id"] for x in remaining], ["m-retry"])

        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "m-retry", "android-b"))
        self.assertEqual(load_pending_outgoing_for_peer(
            self.db_path, "server2", "android-b"), [])
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-retry'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(state, "delivered")

    def test_first_discovery_target_set_freezes_after_deadline(self):
        from lan_p2p_core import (
            persist_outgoing_message, record_peer, add_discovery_targets_for_peer,
            load_pending_outgoing_for_peer,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-freeze", "hello", []))
        self.assertEqual(add_discovery_targets_for_peer(
            self.db_path, "server2", "android-a"), 1)
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                "UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id='m-freeze'")
            conn.commit()
        finally:
            conn.close()
        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        load_pending_outgoing_for_peer(self.db_path, "server2", "android-a")
        self.assertEqual(add_discovery_targets_for_peer(
            self.db_path, "server2", "android-b"), 0)
        conn = sqlite3.connect(self.db_path)
        try:
            targets = conn.execute(
                "SELECT peer_id FROM lan_outbox_targets WHERE message_id='m-freeze' ORDER BY peer_id"
            ).fetchall()
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-freeze'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual([x[0] for x in targets], ["android-a"])
        self.assertEqual(state, "pending")

    def test_empty_discovery_window_never_becomes_delivered(self):
        from lan_p2p_core import persist_outgoing_message, load_pending_outgoing_for_peer
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-empty", "hello", []))
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                "UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id='m-empty'")
            conn.commit()
        finally:
            conn.close()
        load_pending_outgoing_for_peer(self.db_path, "server2", "nonexistent-peer")
        conn = sqlite3.connect(self.db_path)
        try:
            row = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-empty'"
            ).fetchone()
            count = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-empty'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(row[0], "discovery_pending")
        self.assertEqual(count, 0)

    def test_initial_discovery_deadline_is_anchored_to_outbox_creation(self):
        from lan_p2p_core import persist_outgoing_message
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-window-start", "hello", []))
        conn = sqlite3.connect(self.db_path)
        try:
            state, deadline = conn.execute(
                "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id='m-window-start'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(state, "discovery_pending")
        self.assertEqual(deadline, 1005000)

    def test_first_peer_discovered_after_initial_window_is_not_added(self):
        from lan_p2p_core import (
            persist_outgoing_message, add_discovery_targets_for_peer,
            acknowledge_outgoing_message,
        )
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-too-late", "hello", []))
        with patch("lan_p2p_core.time.time", return_value=1006.0):
            self.assertEqual(add_discovery_targets_for_peer(
                self.db_path, "server2", "android-late"), 0)
            self.assertFalse(acknowledge_outgoing_message(
                self.db_path, "server2", "m-too-late", "android-late"))
        conn = sqlite3.connect(self.db_path)
        try:
            row = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-too-late'"
            ).fetchone()
            count = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-too-late'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(row[0], "discovery_pending")
        self.assertEqual(count, 0)

    def test_replaying_same_message_id_cannot_expand_targets_or_change_body(self):
        from lan_p2p_core import persist_outgoing_message
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-immutable", "original", ["android-a"]))
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-immutable", "original",
            ["android-a", "android-b"]))
        self.assertFalse(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-immutable", "mutated", ["android-a"]))
        conn = sqlite3.connect(self.db_path)
        try:
            body = conn.execute(
                "SELECT body FROM lan_outbox WHERE message_id='m-immutable'"
            ).fetchone()[0]
            targets = conn.execute(
                "SELECT peer_id FROM lan_outbox_targets WHERE message_id='m-immutable' ORDER BY peer_id"
            ).fetchall()
        finally:
            conn.close()
        self.assertEqual(body, "original")
        self.assertEqual([x[0] for x in targets], ["android-a"])

    def test_packet_with_wrong_secret_is_rejected(self):
        from lan_p2p_core import encode_packet, decode_packet
        packet = encode_packet({
            "type": "chat.message",
            "sender_peer_id": "android-a",
            "target_peer_id": "android-b",
            "message_id": "m-secret",
            "body": "hello",
        }, "correct-secret")
        with self.assertRaises(ValueError):
            decode_packet(packet, "wrong-secret")


    def test_directed_peer_challenge_requires_matching_nonce_identity_and_endpoint(self):
        from lan_p2p_core import remember_peer_challenge, complete_peer_challenge, list_known_peers
        self.assertTrue(remember_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-a", "192.168.1.10", 8888))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "wrong-nonce", "android-a",
            "192.168.1.10", 8888, "android", "android-main"))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-b",
            "192.168.1.10", 8888, "android", "android-main"))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-a",
            "192.168.1.11", 8888, "android", "android-main"))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-a",
            "192.168.1.10", 8890, "android", "android-main"))
        self.assertTrue(complete_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-a",
            "192.168.1.10", 8888, "android", "android-main"))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "nonce-1", "android-a",
            "192.168.1.10", 8888, "android", "android-main"))
        peers = list_known_peers(self.db_path, "server2")
        self.assertEqual([peer["peer_id"] for peer in peers], ["android-a"])
        self.assertEqual(peers[0]["status"], "active")

    def test_expired_peer_challenge_cannot_activate_route(self):
        from lan_p2p_core import remember_peer_challenge, complete_peer_challenge
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(remember_peer_challenge(
                self.db_path, "server2", "nonce-expired", "android-a",
                "192.168.1.10", 8888))
        with patch("lan_p2p_core.time.time", return_value=1006.0):
            self.assertFalse(complete_peer_challenge(
                self.db_path, "server2", "nonce-expired", "android-a",
                "192.168.1.10", 8888, "android", "android-main"))

    def test_suspect_peer_is_not_an_active_unicast_route(self):
        from lan_p2p_core import record_peer, list_known_peers, persist_outgoing_message, load_pending_outgoing_for_peer
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        self.assertEqual(list_known_peers(self.db_path, "server2"), [])
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-suspect-route", "hello", ["android-a"]))
        self.assertEqual(load_pending_outgoing_for_peer(
            self.db_path, "server2", "android-a"), [])

    def test_record_peer_fails_closed_when_database_cannot_be_opened(self):
        from lan_p2p_core import record_peer
        directory_path = os.path.join(self.temp_dir.name, "not-a-database-file")
        os.mkdir(directory_path)
        self.assertFalse(record_peer(directory_path, "server2", "android-a",
                                     "android", "android-main", "192.168.1.10", 8888, "suspect"))


    def test_historical_peer_snapshot_includes_expired_offline_members(self):
        from lan_p2p_core import record_peer, list_known_peers, list_known_peer_snapshot
        self.assertTrue(record_peer(self.db_path, "rescue2", "android-offline",
                                    "android", "android-main", "192.168.1.20", 8888, "active"))
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                "UPDATE lan_peers SET lease_expires_at=1,status='suspect' "
                "WHERE receiver_scope='rescue2' AND peer_id='android-offline'")
            conn.commit()
        finally:
            conn.close()
        self.assertEqual(list_known_peers(self.db_path, "rescue2"), [])
        snapshot = list_known_peer_snapshot(self.db_path, "rescue2")
        self.assertEqual([peer["peer_id"] for peer in snapshot], ["android-offline"])
        self.assertEqual(snapshot[0]["endpoint_ip"], "192.168.1.20")

    def test_outgoing_ack_is_idempotent_after_delivery(self):
        from lan_p2p_core import persist_outgoing_message, acknowledge_outgoing_message
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-ack-idempotent",
            "hello", ["android-a"]))
        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "m-ack-idempotent", "android-a"))
        self.assertTrue(acknowledge_outgoing_message(
            self.db_path, "server2", "m-ack-idempotent", "android-a"))
        self.assertFalse(acknowledge_outgoing_message(
            self.db_path, "server2", "m-ack-idempotent", "android-unknown"))
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-ack-idempotent'"
            ).fetchone()[0]
            count = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-ack-idempotent'"
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(state, "delivered")
        self.assertEqual(count, 1)

    def test_http_fallback_envelope_preserves_v2_message_identity(self):
        from lan_p2p_core import (
            encode_chat_http_fallback_payload, decode_chat_http_fallback_payload,
        )
        payload = encode_chat_http_fallback_payload(
            "message-unique-1", "server2-stable-id", "Web_Console", "同一消息：中文|内容"
        )
        decoded = decode_chat_http_fallback_payload(payload)
        self.assertEqual(decoded["message_id"], "message-unique-1")
        self.assertEqual(decoded["sender_peer_id"], "server2-stable-id")
        self.assertEqual(decoded["body"], "同一消息：中文|内容")
        self.assertEqual(decoded["display_name"], "Web_Console")
        self.assertIsNone(decode_chat_http_fallback_payload("legacy plain chat text"))

    def test_startup_peer_beacon_sends_authenticated_one_shot_hello(self):
        from lan_p2p_core import send_startup_peer_beacon, decode_packet

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, data, target):
                self.sent.append((data, target))

        sock = FakeSocket()
        sent = send_startup_peer_beacon(
            sock, "server2-stable", "server2", "server2-stable",
            "test-secret", 8887, [8888, 8889],
            ["255.255.255.255", "192.168.1.255"],
        )
        self.assertEqual(sent, 4)
        self.assertEqual(len(sock.sent), 4)
        decoded = [decode_packet(data, "test-secret") for data, _ in sock.sent]
        self.assertTrue(all(packet["type"] == "peer.hello" for packet in decoded))
        self.assertTrue(all(packet["sender_peer_id"] == "server2-stable" for packet in decoded))
        self.assertTrue(all(packet["listen_port"] == 8887 for packet in decoded))
        self.assertTrue(all(packet["nonce"] == decoded[0]["nonce"] for packet in decoded))

if __name__ == "__main__":
    unittest.main()
