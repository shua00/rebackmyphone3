import os
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from lan_p2p_core import load_stable_peer_id, record_peer


import re

def extract_java_method(source, signature):
    """Extract one Java method using balanced braces, ignoring comments and literals."""
    start = source.find(signature)
    if start < 0:
        raise AssertionError("Java method not found: " + signature)
    opening = source.find("{", start + len(signature))
    if opening < 0:
        raise AssertionError("Java method body not found: " + signature)

    depth = 0
    state = "code"
    escaped = False
    i = opening
    while i < len(source):
        ch = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ""
        if state == "line_comment":
            if ch == "\n":
                state = "code"
        elif state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "code"
                i += 1
        elif state == "string":
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                state = "code"
        elif state == "char":
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == "'":
                state = "code"
        else:
            if ch == "/" and nxt == "/":
                state = "line_comment"
                i += 1
            elif ch == "/" and nxt == "*":
                state = "block_comment"
                i += 1
            elif ch == '"':
                state = "string"
            elif ch == "'":
                state = "char"
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return source[start:i + 1]
        i += 1
    raise AssertionError("Unbalanced Java method body: " + signature)


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


    def test_unknown_recipient_ack_is_only_allowed_inside_open_discovery_window(self):
        from lan_p2p_core import (
            persist_outgoing_message, is_outgoing_discovery_pending,
            acknowledge_outgoing_message,
        )
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-open-discovery", "hello", []))
            self.assertTrue(is_outgoing_discovery_pending(
                self.db_path, "server2", "m-open-discovery"))
        with patch("lan_p2p_core.time.time", return_value=1006.0):
            self.assertFalse(is_outgoing_discovery_pending(
                self.db_path, "server2", "m-open-discovery"))
            self.assertFalse(acknowledge_outgoing_message(
                self.db_path, "server2", "m-open-discovery", "android-late"))

    def test_fixed_target_message_does_not_open_discovery_ack_bypass(self):
        from lan_p2p_core import persist_outgoing_message, is_outgoing_discovery_pending
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-fixed-ack", "hello", ["android-a"]))
        self.assertFalse(is_outgoing_discovery_pending(
            self.db_path, "server2", "m-fixed-ack"))

    def test_ack_route_requires_active_peer_and_rejects_changed_suspect_endpoint(self):
        from lan_p2p_core import record_peer, peer_endpoint_matches
        self.assertTrue(record_peer(
            self.db_path, "server2", "android-a", "android", "android-main",
            "192.168.1.10", 8888, "suspect"))
        self.assertFalse(peer_endpoint_matches(
            self.db_path, "server2", "android-a", "192.168.1.10", 8888))
        self.assertTrue(record_peer(
            self.db_path, "server2", "android-a", "android", "android-main",
            "192.168.1.10", 8888, "active"))
        self.assertTrue(peer_endpoint_matches(
            self.db_path, "server2", "android-a", "192.168.1.10", 8888))
        self.assertTrue(record_peer(
            self.db_path, "server2", "android-a", "android", "android-main",
            "192.168.1.11", 8888, "suspect"))
        self.assertFalse(peer_endpoint_matches(
            self.db_path, "server2", "android-a", "192.168.1.11", 8888))
        self.assertFalse(peer_endpoint_matches(
            self.db_path, "server2", "android-a", "192.168.1.10", 8888))

    def test_peer_endpoint_match_fails_closed_on_database_open_error(self):
        from lan_p2p_core import peer_endpoint_matches
        directory_path = os.path.join(self.temp_dir.name, "not-a-database")
        os.mkdir(directory_path)
        self.assertFalse(peer_endpoint_matches(
            directory_path, "server2", "android-a", "192.168.1.10", 8888))

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

    def test_one_broadcast_challenge_can_collect_multiple_distinct_peers(self):
        from lan_p2p_core import remember_peer_challenge, complete_peer_challenge, list_known_peers
        self.assertTrue(remember_peer_challenge(self.db_path, "server2", "broadcast-nonce"))
        self.assertTrue(complete_peer_challenge(
            self.db_path, "server2", "broadcast-nonce", "android-a",
            "192.168.1.10", 8888, "android", "android-main"))
        self.assertTrue(complete_peer_challenge(
            self.db_path, "server2", "broadcast-nonce", "android-b",
            "192.168.1.11", 8888, "android", "android-main"))
        peers = list_known_peers(self.db_path, "server2")
        self.assertEqual({peer["peer_id"] for peer in peers}, {"android-a", "android-b"})

    def test_challenge_lookup_fails_closed_when_database_cannot_be_opened(self):
        from lan_p2p_core import complete_peer_challenge
        directory_path = os.path.join(self.temp_dir.name, "not-a-challenge-database")
        os.mkdir(directory_path)
        self.assertFalse(complete_peer_challenge(
            directory_path, "server2", "nonce", "android-a",
            "192.168.1.10", 8888, "android", "android-main"))

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

    def test_active_target_sender_retries_only_until_ack(self):
        from lan_p2p_core import (
            record_peer, persist_outgoing_message,
            send_outbox_to_active_peers, acknowledge_outgoing_message,
        )

        class FakeSocket:
            def __init__(self, on_send=None):
                self.sent = []
                self.on_send = on_send
            def sendto(self, payload, destination):
                self.sent.append((payload, destination))
                if self.on_send:
                    self.on_send(payload, destination)

        record_peer(self.db_path, "rescue2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        packet = {
            "type": "chat.message", "sender_peer_id": "rescue2-id",
            "sender_peer_type": "rescue2", "service_id": "rescue2-id",
            "target_peer_id": "*", "message_id": "retry-m1",
            "created_at_ms": 123, "body": "hello",
        }
        self.assertTrue(persist_outgoing_message(
            self.db_path, "rescue2", "rescue2-id", "retry-m1", "hello", ["android-a"]))
        socket = FakeSocket()
        with patch("lan_p2p_core.time.sleep", return_value=None):
            sent = send_outbox_to_active_peers(
                socket, self.db_path, "rescue2", "retry-m1", packet,
                "test-secret", attempts=3, retry_delay_seconds=0.25)
        self.assertEqual(sent, 3)
        self.assertEqual(len(socket.sent), 3)
        self.assertTrue(all(addr == ("192.168.1.10", 8888) for _, addr in socket.sent))

        self.assertTrue(persist_outgoing_message(
            self.db_path, "rescue2", "rescue2-id", "retry-m2", "hello", ["android-a"]))
        packet["message_id"] = "retry-m2"
        def ack_first_send(_payload, _destination):
            acknowledge_outgoing_message(
                self.db_path, "rescue2", "retry-m2", "android-a")
        ack_socket = FakeSocket(on_send=ack_first_send)
        with patch("lan_p2p_core.time.sleep", return_value=None):
            acked_sends = send_outbox_to_active_peers(
                ack_socket, self.db_path, "rescue2", "retry-m2", packet,
                "test-secret", attempts=3, retry_delay_seconds=0.25)
        self.assertEqual(acked_sends, 1)


    def test_partial_ack_retries_only_the_remaining_fixed_peer(self):
        from lan_p2p_core import (
            record_peer, persist_outgoing_message,
            send_outbox_to_active_peers, acknowledge_outgoing_message,
        )

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, payload, destination):
                self.sent.append((payload, destination))
                if destination[0] == "192.168.1.10":
                    acknowledge_outgoing_message(
                        self.db_path, "server2", "group-retry", "android-a")

        record_peer(self.db_path, "server2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        record_peer(self.db_path, "server2", "android-b", "android", "android-main",
                    "192.168.1.11", 8888, "active")
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "group-retry", "hello",
            ["android-a", "android-b"]))
        socket = FakeSocket()
        socket.db_path = self.db_path
        packet = {
            "type": "chat.message", "sender_peer_id": "server2-id",
            "sender_peer_type": "server2", "service_id": "server2-id",
            "target_peer_id": "*", "message_id": "group-retry",
            "created_at_ms": 123, "body": "hello",
        }
        with patch("lan_p2p_core.time.sleep", return_value=None):
            sent = send_outbox_to_active_peers(
                socket, self.db_path, "server2", "group-retry", packet,
                "test-secret", attempts=3, retry_delay_seconds=0.25)
        self.assertEqual(sent, 4)
        ips = [destination[0] for _, destination in socket.sent]
        self.assertEqual(ips.count("192.168.1.10"), 1)
        self.assertEqual(ips.count("192.168.1.11"), 3)
        conn = sqlite3.connect(self.db_path)
        try:
            rows = dict(conn.execute(
                "SELECT peer_id,status FROM lan_outbox_targets WHERE message_id='group-retry'"
            ).fetchall())
        finally:
            conn.close()
        self.assertEqual(rows["android-a"], "acked")
        self.assertEqual(rows["android-b"], "sent")

    def test_broadcast_retries_are_bounded_and_keep_identical_payload(self):
        from lan_p2p_core import send_broadcast_packet_with_retries

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, payload, destination):
                self.sent.append((payload, destination))

        socket = FakeSocket()
        payload = b"same-message-id-packet"
        destinations = [("192.168.1.255", 8888), ("255.255.255.255", 8888)]
        with patch("lan_p2p_core.time.sleep", return_value=None):
            sent = send_broadcast_packet_with_retries(
                socket, payload, destinations, attempts=3, retry_delay_seconds=0.25)
        self.assertEqual(sent, 6)
        self.assertEqual(len(socket.sent), 6)
        self.assertTrue(all(packet == payload for packet, _ in socket.sent))

    def test_incoming_message_claim_rejects_same_id_with_different_body(self):
        from lan_p2p_core import validate_incoming_message_claim
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute("""CREATE TABLE received_chat_messages (
                receiver_scope TEXT NOT NULL, sender TEXT NOT NULL, message_key TEXT NOT NULL,
                content TEXT NOT NULL, PRIMARY KEY(receiver_scope,sender,message_key))""")
            conn.execute("BEGIN IMMEDIATE")
            first = conn.execute(
                "INSERT OR IGNORE INTO received_chat_messages VALUES(?,?,?,?)",
                ("server2", "android-a", "message-1", "original body"))
            self.assertTrue(validate_incoming_message_claim(
                conn, first.rowcount, "server2", "android-a", "message-1", "original body"))
            conn.commit()

            conn.execute("BEGIN IMMEDIATE")
            duplicate = conn.execute(
                "INSERT OR IGNORE INTO received_chat_messages VALUES(?,?,?,?)",
                ("server2", "android-a", "message-1", "original body"))
            self.assertFalse(validate_incoming_message_claim(
                conn, duplicate.rowcount, "server2", "android-a", "message-1", "original body"))
            conn.commit()

            conn.execute("BEGIN IMMEDIATE")
            conflict = conn.execute(
                "INSERT OR IGNORE INTO received_chat_messages VALUES(?,?,?,?)",
                ("server2", "android-a", "message-1", "tampered body"))
            self.assertIsNone(validate_incoming_message_claim(
                conn, conflict.rowcount, "server2", "android-a", "message-1", "tampered body"))
            conn.rollback()
            stored = conn.execute(
                "SELECT content FROM received_chat_messages WHERE receiver_scope=? AND sender=? AND message_key=?",
                ("server2", "android-a", "message-1")).fetchone()[0]
            self.assertEqual(stored, "original body")
        finally:
            conn.close()

    def test_all_authenticated_v2_service_receivers_persist_before_stored_ack(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("server2.py", "rescue2.py", "chat.py"):
            with self.subTest(service=filename):
                source = (repo_root / filename).read_text(encoding="utf-8")
                self.assertIn(
                    "claim_result = _claim_chat_message(sender, message_id, body)",
                    source,
                )
                self.assertNotIn(
                    'if sender in ("Web_Console", "PC_Terminal", "Guest_")',
                    source,
                )
                self.assertIn("validate_incoming_message_claim(", source)
                self.assertIn("is_outgoing_discovery_pending(", source)

    def test_peer_challenge_rejects_unregistered_udp_service_ports(self):
        from lan_p2p_core import remember_peer_challenge, complete_peer_challenge
        self.assertTrue(remember_peer_challenge(
            self.db_path, "server2", "nonce-bad-port", "android-a",
            "192.168.1.10", 9999))
        self.assertFalse(complete_peer_challenge(
            self.db_path, "server2", "nonce-bad-port", "android-a",
            "192.168.1.10", 9999, "android", "android-main"))

    def test_suspect_peer_is_not_an_active_unicast_route(self):
        from lan_p2p_core import record_peer, list_known_peers, persist_outgoing_message, load_pending_outgoing_for_peer
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        self.assertEqual(list_known_peers(self.db_path, "server2"), [])
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-suspect-route", "hello", ["android-a"]))
        self.assertEqual(load_pending_outgoing_for_peer(
            self.db_path, "server2", "android-a"), [])

    def test_android_discovery_ack_never_grants_future_route_trust(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index('if ("chat.ack".equals(type))')
        end = source.index('if ("chat.message".equals(type))', start)
        ack_handler = source[start:end]
        self.assertIn("boolean routeMatches", ack_handler)
        self.assertIn("boolean discoveryAck", ack_handler)
        self.assertIn("if (routeMatches) verifiedChatPeerSeen.put", ack_handler)
        self.assertNotIn("verifiedChatPeers.put(", ack_handler)

    def test_android_inbox_conflicting_payload_is_rejected_and_duplicate_refreshes_ui(self):
        repo_root = Path(__file__).resolve().parents[1]
        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        tracking_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        enqueue_start = store_source.index("public boolean enqueue(")
        enqueue_end = store_source.index("\n    public ", enqueue_start + 12)
        enqueue_method = store_source[enqueue_start:enqueue_end]
        self.assertIn("SELECT sender_peer_id,body FROM lan_outbox WHERE message_id=?", enqueue_method)
        self.assertIn("senderPeerId.equals(exists.getString(0))", enqueue_method)
        self.assertIn("body.equals(exists.getString(1))", enqueue_method)

        inbox_start = store_source.index("public boolean storeIncoming(")
        inbox_end = store_source.index("\n    public ", inbox_start + 12)
        inbox_method = store_source[inbox_start:inbox_end]
        self.assertIn("SELECT body FROM lan_inbox", inbox_method)
        self.assertIn("body.equals(existing.getString(0))", inbox_method)
        self.assertIn("if (!identical) return false", inbox_method)

        receive_start = tracking_source.index(
            "private boolean persistIncomingLanChat(String senderId, String messageId, String msgContent,"
        )
        receive_end = tracking_source.index("\n    private void startRescueListener", receive_start)
        receive_method = tracking_source[receive_start:receive_end]
        self.assertIn("if (alreadyInLegacyIds)", receive_method)
        self.assertIn("if (store.isIncomingDisplayed(receiverScope, senderId, messageId))", receive_method)
        self.assertGreaterEqual(receive_method.count("scheduleLanChatUiRefresh();"), 3)

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


    def test_android_retry_recovery_reads_durable_sqlite_outbox(self):
        repo_root = Path(__file__).resolve().parents[1]
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = tracking.index("private void retryPendingLanChatMessages()")
        end = tracking.index("private void sendLocalUdpMessage", start)
        recovery = tracking[start:end]

        # SharedPreferences remains a compatibility import only. Retry enumeration
        # must come from SQLite so a missing/stale mirror cannot strand durable rows.
        self.assertIn("store.loadPending(50)", recovery)
        self.assertIn("List<LanChatStore.PendingMessage> pending", recovery)
        self.assertIn("item.messageId", recovery)
        self.assertIn("item.body", recovery)
        self.assertIn("store.allTargetsAcked(id)", recovery)
        self.assertIn("removePendingLanChat(id)", recovery)

    def test_android_sqlite_pending_read_fails_closed_on_query_error(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        start = store.index("public List<PendingMessage> loadPending(int limit)")
        end = store.index("\n    public List<Target> loadTargets", start)
        method = store[start:end]

        # A database error must escape to retryPendingLanChatMessages' catch/log path.
        # It must never be converted into an empty queue, which looks like success.
        self.assertNotIn("catch (Exception ignored)", method)
        self.assertIn("return result;", method)
        self.assertIn("finally", method)


    def test_android_outbox_retry_attempt_ledger_is_persistent_and_migrated(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        self.assertIn("DB_VERSION = 5", store)
        self.assertIn("retry_attempt_count INTEGER NOT NULL DEFAULT 0", store)
        self.assertIn("state='retry_paused'", store)
        self.assertIn("'retry_paused' ELSE state", store)
        self.assertIn('if (oldVersion < 4)', store)
        self.assertIn('ALTER TABLE lan_outbox ADD COLUMN retry_attempt_count', store)
        self.assertIn("public int recordOutboxAttempt(String messageId)", store)
        self.assertIn("update.bindLong(1, now)", store)
        self.assertIn("update.bindLong(2, MAX_AUTOMATIC_RETRY_ATTEMPTS)", store)
        self.assertIn("update.bindString(3, messageId)", store)
        self.assertIn("update.bindLong(4, MAX_AUTOMATIC_RETRY_ATTEMPTS)", store)
        self.assertIn("retry_attempt_count=retry_attempt_count+1", store)
        self.assertIn("c.getString(4), c.getInt(5)", store)

    def test_android_retry_wakeups_are_bounded_and_cover_later_pending_rows(self):
        repo_root = Path(__file__).resolve().parents[1]
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = tracking.index("private void retryPendingLanChatMessages()")
        end = tracking.index("private void sendLocalUdpMessage", start)
        recovery = tracking[start:end]
        self.assertIn("final int maxBatchesPerWake = 3", recovery)
        self.assertIn("int startIndex = batchNo * batchSize", recovery)
        self.assertIn("scheduleLanChatRetryFromOutbox()", recovery)
        self.assertIn("lanChatRetryWakeScheduled", tracking)
        self.assertIn("item.retryAttempts > 0", tracking)
        self.assertIn("MAX_AUTOMATIC_RETRY_ATTEMPTS", tracking)
        self.assertIn("retryPendingLanChatMessages(true)", tracking)
        self.assertNotIn("item.retryAttempts < 3", tracking)
        self.assertIn("resumeExhaustedRetriesForRecoveryEvent", tracking)
        self.assertIn("lanChatRecoveryEventPending", tracking)
        self.assertIn("boolean attemptRecorded = false", tracking)
        self.assertIn("if (attemptRecorded)", tracking)
        self.assertIn("scheduleLanChatRetryFromOutbox()", tracking)
        self.assertIn("minimumAttempts == 2 ? 4_000L", tracking)
        self.assertIn("Math.min(60_000L, 10_000L << Math.min(3, minimumAttempts - 3))", tracking)
        self.assertIn("finite per-epoch attempt budget", tracking)
        self.assertIn("waiting_peers", tracking)
        self.assertIn("waiting_peers", tracking)
        self.assertIn("handler.postDelayed", tracking)
        self.assertIn("lanChatRetryInFlight.set(false);", recovery)
        self.assertIn("if (serviceDestroyed || !lanChatRetryInFlight.compareAndSet(false, true)) return", recovery)


    def test_android_exhausted_retry_budget_is_preserved_and_only_rearmed_by_cooldown_event(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        load_start = store.index("public List<PendingMessage> loadPending(int limit)")
        load_end = store.index("public int recordOutboxAttempt(String messageId)", load_start)
        load = store[load_start:load_end]
        self.assertIn("retry_attempt_count<?", load)
        self.assertIn("MAX_AUTOMATIC_RETRY_ATTEMPTS", load)
        resume_start = store.index("public int resumeExhaustedRetriesForRecoveryEvent(long now)")
        resume_end = store.index("public int recordOutboxAttempt(String messageId)", resume_start)
        resume = store[resume_start:resume_end]
        self.assertIn("last_event_retry_at_ms<=?", resume)
        self.assertIn("RETRY_RECOVERY_EVENT_COOLDOWN_MS", resume)
        self.assertIn("t.status!='acked'", resume)
        self.assertIn("retry_attempt_count=0", resume)
        self.assertIn("state='pending'", resume)
        attempt_start = store.index("public int recordOutboxAttempt(String messageId)")
        attempt_end = store.index("public List<Target> loadTargets", attempt_start)
        attempt = store[attempt_start:attempt_end]
        self.assertIn("retry_attempt_count<?", attempt)
        self.assertIn("MAX_AUTOMATIC_RETRY_ATTEMPTS", attempt)
        self.assertNotIn("DELETE FROM lan_outbox", resume)


    def test_android_peer_and_target_reads_do_not_collapse_database_errors_to_empty(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        for method_name, next_method in (
            ("public List<Target> loadKnownPeers()", "public List<PendingMessage> loadPending"),
            ("public List<Target> loadTargets(String messageId)", "public boolean markAttempt"),
        ):
            start = store.index(method_name)
            end = store.index(next_method, start)
            method = store[start:end]
            if method_name.startswith("public List<Target> loadKnownPeers"):
                self.assertNotIn("catch (Exception", method)
            else:
                # The only permitted local catch is IP:port parsing; SQL query failures
                # must escape instead of being converted into an empty target list.
                self.assertEqual(method.count("catch (Exception ignored)"), 1)
                self.assertLess(method.index("Integer.parseInt"), method.index("catch (Exception ignored)"))
                self.assertNotIn("catch (Exception e)", method)
            self.assertIn("finally", method)

        tx_start = tracking.index("final List<LanChatStore.Target> savedTargets")
        tx_end = tracking.index("java.util.Set<String> pendingTargetIds", tx_start)
        tx = tracking[tx_start:tx_end]
        self.assertIn("store.loadTargets(messageId)", tx)
        self.assertIn("拒绝发送以免误投", tx)
        self.assertIn("return;", tx)


    def test_android_expired_empty_discovery_window_never_broadcasts_old_message_to_late_peers(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        start = store.index("public boolean isDiscoveryPending(String messageId)")
        end = store.index("\n    /** Add a newly discovered peer", start)
        discovery = store[start:end]
        self.assertIn("delivery_deadline_ms>=?", discovery)
        self.assertIn("System.currentTimeMillis()", discovery)

        tx_start = tracking.index("final int totalPeerCount = pendingTargetIds.size()")
        tx_end = tracking.index("java.util.concurrent.CountDownLatch ack", tx_start)
        send_guard = tracking[tx_start:tx_end]
        self.assertIn("firstDiscoveryWindowOpen = store.isDiscoveryPending(messageId)", send_guard)
        self.assertIn("totalPeerCount == 0 && !firstDiscoveryWindowOpen", send_guard)
        self.assertIn("禁止向晚加入设备广播旧消息", send_guard)


    def test_python_chat_senders_respect_fixed_recipient_snapshots(self):
        repo_root = Path(__file__).resolve().parents[1]

        rescue = (repo_root / "rescue2.py").read_text(encoding="utf-8")
        rescue_start = rescue.index("def send_chat_v2(")
        rescue_end = rescue.index("\ndef udp_listener", rescue_start)
        rescue_sender = rescue[rescue_start:rescue_end]
        self.assertIn("elif target_ids:", rescue_sender)
        self.assertIn("保留原消息等待 peer 重新上线", rescue_sender)
        self.assertIn("broadcast_all and is_outgoing_discovery_pending", rescue_sender)

        chat = (repo_root / "chat.py").read_text(encoding="utf-8")
        chat_start = chat.index("def send_chat_v2(")
        chat_end = chat.index("\n\n# UDP 监听线程", chat_start)
        chat_sender = chat[chat_start:chat_end]
        self.assertIn("elif target_ids:", chat_sender)
        self.assertIn("message remains pending", chat_sender)
        self.assertIn("elif is_outgoing_discovery_pending", chat_sender)

        server = (repo_root / "server2.py").read_text(encoding="utf-8")
        server_start = server.index("def send_server2_v2_chat_from_outbox(")
        server_end = server.index("\ndef udp_listener", server_start)
        server_sender = server[server_start:server_end]
        self.assertIn("if fixed_target_ids:", server_sender)
        self.assertIn("send_outbox_to_active_peers(", server_sender)
        self.assertIn("if not is_outgoing_discovery_pending(", server_sender)
        self.assertIn("send_broadcast_packet_with_retries(", server_sender)
        self.assertIn("[(ip, 8888)", server_sender)

        action_start = server.index('elif action == "chat_msg":')
        action_end = server.index('elif action == "change_server":', action_start)
        web_chat = server[action_start:action_end]
        self.assertIn("send_server2_v2_chat_from_outbox(cmd_prefix, target_devs)", web_chat)
        self.assertNotIn("smart_send(cmd_prefix)", web_chat)


    def test_android_service_first_startup_work_runs_off_main_looper(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index("public void onCreate()")
        end = source.index("private void startWatchdog()", start)
        startup = source[start:end]

        # Foreground promotion remains synchronous to satisfy Android's FGS deadline.
        self.assertIn("startForegroundNotification();", startup)
        worker_start = startup.index('new Thread(() ->')
        self.assertGreater(worker_start, startup.index("startForegroundNotification();"))
        worker = startup[worker_start:]
        for operation in (
            "logStartupSelfCheck();",
            "handleIconVisibility(true);",
            "triggerRescueListener();",
            "requestSingleLocationAndReport();",
            "registerNetworkCallback();",
            "startLocationUpdates();",
            "registerBatteryMonitor();",
            "KeepAliveJobService.scheduleJob(this);",
        ):
            self.assertIn(operation, worker)
        self.assertNotIn("handler.post(() -> {\\n            FileLogger.log(this, \"SERVICE\", \"🚀 追踪服务已启动", startup)

    def test_android_location_callbacks_stay_on_main_looper_after_worker_registration(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index("private void startLocationUpdates()")
        end = source.index("public void onLocationChanged(Location location)", start)
        method = source[start:end]
        self.assertIn("LocationManager.GPS_PROVIDER, minTime, 0f, this, Looper.getMainLooper()", method)
        self.assertIn("LocationManager.FUSED_PROVIDER, minTime, 0f, this, Looper.getMainLooper()", method)
        self.assertIn("LocationManager.NETWORK_PROVIDER, minTime, 0f, this, Looper.getMainLooper()", method)

    def test_android_permission_finish_does_not_change_launcher_component_on_ui_thread(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/MainActivity.java"
        ).read_text(encoding="utf-8")
        start = source.index("private void finishWithToast(String msg, int duration)")
        end = source.index("\n}", start)
        finish = source[start:end]
        self.assertIn("startTrackingServiceOnly();", finish)
        self.assertIn('new Thread(() -> {', finish)
        self.assertIn("LauncherIconUtils.setLauncherIconVisible(appContext, false)", finish)
        self.assertNotIn("hideLauncherIcon();", finish)

    def test_android_system_wake_self_heal_is_dispatched_to_maintenance_executor(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index("public int onStartCommand(Intent intent, int flags, int startId)")
        end = source.index("private void logWakeSource(Intent intent)", start)
        method = source[start:end]
        self.assertIn('scheduleSelfHeal("job");', method)
        self.assertIn('scheduleSelfHeal("accessibility");', method)
        self.assertIn('scheduleSelfHeal("component");', method)
        self.assertIn('executeMaintenance("keep-alive"', method)
        self.assertNotIn('selfHeal();\\n            } else if ("ACTION_JOB_WAKE_UP"', method)


    def test_android_network_refresh_and_offline_flush_do_not_run_on_main_handler(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        start = source.index("private final Runnable networkRefreshRunnable")
        end = source.index("private JSONObject buildNetworkSnapshot()", start)
        runnable = source[start:end]
        self.assertIn('executeMaintenance("network-refresh"', runnable)
        self.assertIn("buildNetworkSnapshot()", runnable)
        self.assertIn("flushPendingReports()", runnable)
        self.assertIn("retryPendingLanChatMessages(true)", runnable)

        callback_start = source.index("public void onAvailable(Network network)")
        callback_end = source.index("public void onLost(Network network)", callback_start)
        callback = source[callback_start:callback_end]
        self.assertIn("handler.postDelayed(networkRefreshRunnable, 1200L)", callback)
        self.assertIn('executeMaintenance("network-available-flush"', callback)
        self.assertNotIn("flushPendingReports();", callback)


    def test_android_network_callbacks_only_enqueue_network_refresh_work(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        default_start = source.index("networkCallback = new ConnectivityManager.NetworkCallback()")
        default_end = source.index("cm.registerDefaultNetworkCallback(networkCallback)", default_start)
        default_callback = source[default_start:default_end]
        self.assertEqual(default_callback.count('executeMaintenance("peer-route-refresh"'), 4)
        # Refresh calls belong inside queued Runnable lambdas, not on callback threads.
        self.assertEqual(len(re.findall(
            r'executeMaintenance\(\s*"peer-route-refresh"\s*,\s*\(\s*\)\s*->\s*refreshPeerRoutesForNetworkChange\s*\(',
            default_callback)), 4)

        wifi_start = source.index("wifiLanNetworkCallback = new ConnectivityManager.NetworkCallback()")
        wifi_end = source.index("cm.registerNetworkCallback(wifiRequest, wifiLanNetworkCallback)", wifi_start)
        wifi_callback = source[wifi_start:wifi_end]
        self.assertEqual(wifi_callback.count('executeMaintenance("wifi-lan-refresh"'), 4)
        self.assertEqual(len(re.findall(
            r'executeMaintenance\(\s*"wifi-lan-refresh"\s*,\s*\(\s*\)\s*->\s*refreshWifiLanTransport\s*\(',
            wifi_callback)), 4)



    def test_android_lan_chat_receive_and_ack_use_dedicated_io_executor(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index("private boolean handleAuthenticatedLanPacket(")
        end = source.index("private void sendAuthenticatedChatAck(", start)
        handler = source[start:end]
        self.assertEqual(handler.count("lanChatIoExecutor.execute(() -> {"), 4)
        self.assertNotIn("reportExecutor.execute(", handler)
        self.assertIn('new Thread(runnable, "LanChat-IO")', source)

    def test_android_peer_challenge_nonce_outlives_initial_discovery_wait(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("LAN_PEER_CHALLENGE_TIMEOUT_MS = 2_500L", source)
        start = source.index("private void transmitLocalUdpMessageInternal(")
        end = source.index("private void sendLanUdpPacket(", start)
        method = source[start:end]
        self.assertIn("LAN_PEER_CHALLENGE_TIMEOUT_MS", method)
        self.assertNotIn("Thread.sleep(500L)", method)
        self.assertIn("Discovery is deliberately non-blocking", method)
        self.assertNotIn("clearPendingPeerChallenge(nonce);", method.split("finally {", 1)[-1])

    def test_android_known_offline_targets_are_rechallenged_at_saved_endpoint(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index("private void transmitLocalUdpMessageInternal(")
        end = source.index("private void sendLanUdpPacket(", start)
        method = source[start:end]
        self.assertIn("store.loadTargets(messageId)", method)
        self.assertIn("target.endpointIp", method)
        self.assertIn("target.endpointPort", method)
        self.assertIn("sendDirectedPeerChallenge(socket, target.peerId", method)

    def test_android_receive_latency_is_measured_from_udp_arrival(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("handleLanRealtimePacket(rescueSocket, packet, message, udpReceiveElapsedMs)", source)
        self.assertIn("P2P 接收耗时 message=", source)
        self.assertIn("queueMs=", source)
        self.assertIn("durableTotalMs=", source)

    def test_android_fresh_chat_send_queue_wait_is_measured(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("P2P 排队耗时 ", source)
        self.assertIn("P2P 发送串行锁等待 message=", source)

    def test_android_chat_send_uses_short_ack_wait_and_durable_retry(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        self.assertIn("attempt <= 1 && ack.getCount() > 0", send)
        self.assertIn("ack.await(250, TimeUnit.MILLISECONDS)", send)
        self.assertIn("scheduleLanChatRetryFromOutbox()", source)
        self.assertIn("activeLanChatTransmissions.add(messageId)", source)
        self.assertIn("synchronized (lanChatTransmitLock)", source)


    def test_android_p2p_send_log_distinguishes_fixed_targets_from_reachable_routes(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        method = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        # Check the meaning and counters, not one exact concatenation layout.
        self.assertIn("固定目标", method)
        self.assertIn("totalPeerCount", method)
        self.assertIn("当前可达路由", method)
        self.assertIn("reachablePeerCount", method)
        # Historical terminology in comments is harmless; the emitted log above
        # is what defines the operator-facing distinction.



    def test_android_p2p_retry_and_peer_deadlines_do_not_depend_on_main_handler(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn('new Thread(runnable, "LanChat-Scheduler")', source)

        retry_start = source.index("private void scheduleLanChatRetryFromOutbox()")
        retry_end = source.index("private void transmitLocalUdpMessageInternal(", retry_start)
        retry_method = source[retry_start:retry_end]
        self.assertIn('scheduleLanChatTask("outbox-retry"', retry_method)
        self.assertNotIn("handler.postDelayed(", retry_method)

        self.assertIn('scheduleLanChatTask("peer-verified-retry"', source)
        self.assertIn('scheduleLanChatTask("listener-outbox-recovery"', source)
        self.assertIn('scheduleLanChatTask("peer-discovery-timeout"', source)
        self.assertIn('scheduleLanChatTask("peer-challenge-timeout"', source)



    def test_android_first_discovery_window_starts_when_network_send_begins(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        enqueue = extract_java_method(store, "public boolean enqueue(")
        self.assertIn('v.put("delivery_deadline_ms", 0L)', enqueue)
        self.assertNotIn("+ FIRST_DISCOVERY_WINDOW_MS", enqueue)

        begin_start = store.index("public boolean beginDiscoveryWindow(")
        begin_end = store.index("public boolean isDiscoveryPending(", begin_start)
        begin = store[begin_start:begin_end]
        self.assertIn("FIRST_DISCOVERY_WINDOW_MS", begin)
        self.assertIn('if (deadline > 0L)', begin)

        self.assertIn("FIRST_DISCOVERY_WINDOW_MS = 10_000L", store)
        self.assertIn("store.beginDiscoveryWindow(messageId)", service)
        self.assertIn("首次发现窗口开始", service)



    def test_android_expired_empty_discovery_becomes_waiting_peers_and_stops_retrying(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        pending_start = store.index("public List<PendingMessage> loadPending(")
        pending_end = store.index("public int recordOutboxAttempt(", pending_start)
        self.assertIn("state NOT IN ('delivered','expired','waiting_peers')",
                      store[pending_start:pending_end])
        finalize_start = store.index("private boolean finalizeDiscoveryMessage(")
        finalize_end = store.index("public boolean storeIncoming(", finalize_start)
        self.assertIn('"waiting_peers"', store[finalize_start:finalize_end])
        self.assertIn("public boolean finalizeDiscoveryWindow(", store)
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("store.finalizeDiscoveryWindow(messageId)", tracking)


    def test_android_waiting_peers_keeps_sqlite_record_but_releases_legacy_queue_mirror(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("public boolean isWaitingForPeers(", store)
        remove_start = service.index("private void removePendingLanChat(")
        remove_end = service.index("private void retryPendingLanChatMessages(", remove_start)
        remove = service[remove_start:remove_end]
        self.assertIn("store.isWaitingForPeers(messageId)", remove)
        self.assertIn("SQLite 记录保留，停止自动重试", remove)
        self.assertIn("removePendingLanChat(messageId);", service[
            service.index('scheduleLanChatTask("discovery-window-close"'):
            service.index("private void sendLanUdpPacket(", service.index('scheduleLanChatTask("discovery-window-close"'))
        ])


    def test_android_expired_empty_discovery_rows_are_finalized_even_after_pending_window_closes(self):
        repo_root = Path(__file__).resolve().parents[1]
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = service.index("private void transmitLocalUdpMessageInternal(")
        end = service.index("private void sendLanUdpPacket(", start)
        send = service[start:end]
        self.assertIn("if (pendingTargetIds.isEmpty()) {", send)
        self.assertIn("boolean firstDispatch = store.beginDiscoveryWindow(messageId)", send)
        self.assertNotIn(
            "pendingTargetIds.isEmpty() && store.isDiscoveryPending(messageId)", send
        )
        self.assertIn("store.isWaitingForPeers(messageId)", send)


    def test_chat_send_restores_editor_focus_for_continuous_typing(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/ChatActivity.java"
        ).read_text(encoding="utf-8")
        start = source.index("private void sendMessage()")
        end = source.index("\n    private void refreshHistory(", start)
        send = source[start:end]
        self.assertIn('inputMsg.setText("");', send)
        self.assertIn("inputMsg.requestFocus();", send)
        self.assertLess(send.index('inputMsg.setText("");'), send.index("inputMsg.requestFocus();"))


if __name__ == "__main__":
    unittest.main()
