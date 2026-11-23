import ast
import os
import sqlite3
import threading
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from lan_p2p_core import (LanP2PStorageError, load_stable_peer_id, record_peer,
                          load_pending_outgoing_for_peer, add_discovery_targets_for_peer,
                          load_active_pending_targets, mark_outgoing_attempt)


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

    def test_known_peer_queries_raise_storage_error_instead_of_appearing_empty(self):
        from lan_p2p_core import list_known_peers, list_known_peer_snapshot, LanP2PStorageError
        directory_path = os.path.join(self.temp_dir.name, "not-a-peer-database")
        os.mkdir(directory_path)
        with self.assertRaises(LanP2PStorageError):
            list_known_peers(directory_path, "server2")
        with self.assertRaises(LanP2PStorageError):
            list_known_peer_snapshot(directory_path, "server2")

    def test_network_callback_registration_is_atomic_and_idempotent(self):
        source = Path(__file__).resolve().parents[1] / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        java = source.read_text(encoding="utf-8")
        method = extract_java_method(java, "private void registerNetworkCallback()")
        self.assertIn("if (networkCallback != null && wifiLanNetworkCallback != null) return;", method)
        self.assertIn("cm.unregisterNetworkCallback(networkCallback)", method)
        self.assertIn("cm.unregisterNetworkCallback(wifiLanNetworkCallback)", method)
        self.assertIn("networkCallback = null;", method)
        self.assertIn("wifiLanNetworkCallback = null;", method)
        self.assertIn("if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;", method)
        self.assertIn("Build.VERSION.SDK_INT >= Build.VERSION_CODES.N", method)
        self.assertIn("cm.registerNetworkCallback(defaultNetworkRequest, networkCallback)", method)
        # API 21-23 use registerNetworkCallback and must also unregister both
        # observers on service teardown; gating cleanup at API 24 leaks callbacks.
        self.assertIn("if (cm != null && networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)", java)
        self.assertIn("if (cm != null && wifiLanNetworkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)", java)
        self.assertIn("cm.registerDefaultNetworkCallback(networkCallback)", method)
        self.assertIn("NET_CAPABILITY_INTERNET", method)
        self.assertIn("cm.registerNetworkCallback(defaultNetworkRequest, networkCallback)", method)
        self.assertLess(method.index("cm.registerDefaultNetworkCallback(networkCallback)"),
                        method.index("cm.registerNetworkCallback(wifiRequest, wifiLanNetworkCallback)"))

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



    def test_unverified_same_endpoint_hello_cannot_extend_verified_peer_lease(self):
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                        "android", "android-main", "192.168.1.10", 8888, "active"))
        with patch("lan_p2p_core.time.time", return_value=1110.0):
            self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                        "android", "android-main", "192.168.1.10", 8888, "suspect"))
        with sqlite3.connect(self.db_path) as conn:
            row = conn.execute(
                "SELECT last_seen_at,last_verified_at,lease_expires_at,status FROM lan_peers "
                "WHERE receiver_scope='server2' AND peer_id='android-a'"
            ).fetchone()
        self.assertEqual(row[0], 1110.0)
        self.assertEqual(row[1], 1000.0)
        self.assertEqual(row[2], 1120.0)
        self.assertEqual(row[3], "active")

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


    def test_ack_route_database_read_failure_fails_closed(self):
        from lan_p2p_core import peer_endpoint_matches
        with tempfile.TemporaryDirectory() as invalid_db_path:
            self.assertFalse(peer_endpoint_matches(
                invalid_db_path, "server2", "android-a", "192.168.1.10", 8888))


    def test_unknown_recipient_ack_is_only_allowed_inside_open_discovery_window(self):
        from lan_p2p_core import (
            persist_outgoing_message, is_outgoing_discovery_pending,
            acknowledge_outgoing_message, begin_outgoing_discovery_window,
        )
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-open-discovery", "hello", []))
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-open-discovery"))
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

    def test_outgoing_ack_fails_closed_when_database_cannot_be_opened(self):
        from lan_p2p_core import acknowledge_outgoing_message
        directory_path = os.path.join(self.temp_dir.name, "not-an-ack-database")
        os.mkdir(directory_path)
        self.assertFalse(acknowledge_outgoing_message(
            directory_path, "server2", "message-a", "android-a"))

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
        # A valid ledger includes the peer table even when this target is unknown.
        self.assertTrue(record_peer(
            self.db_path, "server2", "known-peer", "android", "android-main",
            "192.168.1.20", 8888, "active"))
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
        self.assertEqual(row[0], "waiting_peers")
        self.assertEqual(count, 0)

    def test_expired_empty_discovery_is_finalized_by_broadcast_gate_and_rejects_late_peer(self):
        from lan_p2p_core import (
            persist_outgoing_message, is_outgoing_discovery_pending,
            add_discovery_targets_for_peer,
        )
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-empty-final", "hello", []))
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "UPDATE lan_outbox SET delivery_deadline_ms=1 WHERE message_id='m-empty-final'")
        self.assertFalse(is_outgoing_discovery_pending(
            self.db_path, "server2", "m-empty-final"))
        self.assertEqual(add_discovery_targets_for_peer(
            self.db_path, "server2", "late-android"), 0)
        with sqlite3.connect(self.db_path) as conn:
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id='m-empty-final'").fetchone()[0]
            targets = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id='m-empty-final'").fetchone()[0]
        self.assertEqual(state, "waiting_peers")
        self.assertEqual(targets, 0)


    def test_initial_discovery_window_stays_unstarted_until_udp_dispatch(self):
        from lan_p2p_core import (
            persist_outgoing_message, is_outgoing_discovery_pending,
            begin_outgoing_discovery_window,
        )
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-window-start", "hello", []))
            self.assertTrue(is_outgoing_discovery_pending(
                self.db_path, "server2", "m-window-start"))
        with sqlite3.connect(self.db_path) as conn:
            state, deadline = conn.execute(
                "SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id='m-window-start'"
            ).fetchone()
        self.assertEqual(state, "discovery_pending")
        self.assertEqual(deadline, 0)

        # A delayed listener starts the bounded window at the actual first dispatch.
        with patch("lan_p2p_core.time.time", return_value=1010.0):
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-window-start"))
        with sqlite3.connect(self.db_path) as conn:
            deadline = conn.execute(
                "SELECT delivery_deadline_ms FROM lan_outbox WHERE message_id='m-window-start'"
            ).fetchone()[0]
        self.assertEqual(deadline, 1015000)

    def test_discovery_window_begin_is_idempotent_and_never_extends_deadline(self):
        from lan_p2p_core import persist_outgoing_message, begin_outgoing_discovery_window
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-window-idempotent", "hello", []))
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-window-idempotent"))
        with patch("lan_p2p_core.time.time", return_value=1002.0):
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-window-idempotent"))
        with sqlite3.connect(self.db_path) as conn:
            deadline = conn.execute(
                "SELECT delivery_deadline_ms FROM lan_outbox WHERE message_id='m-window-idempotent'"
            ).fetchone()[0]
        self.assertEqual(deadline, 1005000)


    def test_discovery_deadline_is_closed_at_exact_boundary_on_python_and_android(self):
        from lan_p2p_core import (
            persist_outgoing_message, begin_outgoing_discovery_window,
            add_discovery_target, is_outgoing_discovery_pending,
            acknowledge_outgoing_message,
        )
        cases = (
            ("m-boundary-bulk", "scope-bulk"),
            ("m-boundary-single", "scope-single"),
            ("m-boundary-begin", "scope-begin"),
            ("m-boundary-ack", "scope-ack"),
            ("m-boundary-query", "scope-query"),
        )
        for message_id, scope in cases:
            with patch("lan_p2p_core.time.time", return_value=1000.0):
                self.assertTrue(persist_outgoing_message(
                    self.db_path, scope, "sender", message_id, "hello", []))
                self.assertTrue(begin_outgoing_discovery_window(
                    self.db_path, scope, message_id))

        # Each operation is isolated by sender scope so one expiry sweep cannot mask another.
        with patch("lan_p2p_core.time.time", return_value=1005.0):
            self.assertFalse(add_discovery_target(
                self.db_path, "scope-single", "m-boundary-single", "android-late"))
            self.assertFalse(begin_outgoing_discovery_window(
                self.db_path, "scope-begin", "m-boundary-begin"))
            self.assertFalse(acknowledge_outgoing_message(
                self.db_path, "scope-ack", "m-boundary-ack", "android-late"))
            self.assertFalse(is_outgoing_discovery_pending(
                self.db_path, "scope-query", "m-boundary-query"))
            self.assertEqual(add_discovery_targets_for_peer(
                self.db_path, "scope-bulk", "android-late"), 0)

        with sqlite3.connect(self.db_path) as conn:
            states = dict(conn.execute(
                "SELECT message_id,state FROM lan_outbox WHERE message_id LIKE 'm-boundary-%'"
            ).fetchall())
            targets = conn.execute(
                "SELECT COUNT(*) FROM lan_outbox_targets WHERE message_id LIKE 'm-boundary-%'"
            ).fetchone()[0]
        self.assertEqual(states["m-boundary-begin"], "waiting_peers")
        self.assertEqual(states["m-boundary-query"], "waiting_peers")
        self.assertEqual(states["m-boundary-bulk"], "waiting_peers")
        self.assertEqual(targets, 0)

        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        ack = extract_java_method(store, "public boolean acknowledge(")
        begin = extract_java_method(store, "public boolean beginDiscoveryWindow(")
        pending = extract_java_method(store, "public boolean isDiscoveryPending(")
        add_peer = extract_java_method(store, "public boolean addDiscoveryPeerToOpenMessages(")
        self.assertIn("now >= deadline", ack)
        self.assertIn("now < deadline", ack)
        self.assertIn("now >= deadline", begin)
        self.assertIn("delivery_deadline_ms>?", pending)
        self.assertIn("now >= deadline", add_peer)

    def test_first_peer_discovered_after_initial_window_is_not_added(self):
        from lan_p2p_core import (
            persist_outgoing_message, add_discovery_targets_for_peer,
            acknowledge_outgoing_message, begin_outgoing_discovery_window,
        )
        with patch("lan_p2p_core.time.time", return_value=1000.0):
            self.assertTrue(persist_outgoing_message(
                self.db_path, "server2", "server2-id", "m-too-late", "hello", []))
            self.assertTrue(begin_outgoing_discovery_window(
                self.db_path, "server2", "m-too-late"))
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
        # The expired empty snapshot is terminal and must not remain retryable.
        self.assertEqual(row[0], "waiting_peers")
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
        with sqlite3.connect(self.db_path) as conn:
            attempt_count = conn.execute(
                "SELECT attempt_count FROM lan_outbox_targets WHERE message_id=? AND peer_id=?",
                ("retry-m1", "android-a")
            ).fetchone()[0]
        self.assertEqual(attempt_count, 3)
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


    def test_active_target_sender_fails_closed_when_attempt_ledger_fails(self):
        from lan_p2p_core import record_peer, persist_outgoing_message, send_outbox_to_active_peers

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, payload, destination):
                self.sent.append((payload, destination))

        record_peer(self.db_path, "rescue2", "android-a", "android", "android-main",
                    "192.168.1.10", 8888, "active")
        self.assertTrue(persist_outgoing_message(
            self.db_path, "rescue2", "rescue2-id", "ledger-fail", "hello", ["android-a"]))
        packet = {
            "type": "chat.message", "sender_peer_id": "rescue2-id",
            "sender_peer_type": "rescue2", "service_id": "rescue2-id",
            "target_peer_id": "*", "message_id": "ledger-fail",
            "created_at_ms": 123, "body": "hello",
        }
        socket = FakeSocket()
        with patch("lan_p2p_core.mark_outgoing_attempt", return_value=False):
            sent = send_outbox_to_active_peers(
                socket, self.db_path, "rescue2", "ledger-fail", packet,
                "test-secret", attempts=3, retry_delay_seconds=0)
        self.assertEqual(sent, 0)
        self.assertEqual(socket.sent, [])


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

    def test_broadcast_retry_stops_when_discovery_window_closes(self):
        from lan_p2p_core import send_broadcast_packet_with_retries

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, payload, destination):
                self.sent.append((payload, destination))

        socket = FakeSocket()
        allowed = {"open": True}
        destinations = [("192.168.1.255", 8888), ("255.255.255.255", 8888)]
        def close_window(_delay):
            allowed["open"] = False

        with patch("lan_p2p_core.time.sleep", side_effect=close_window):
            sent = send_broadcast_packet_with_retries(
                socket, b"same-message-id", destinations, attempts=3,
                retry_delay_seconds=0.25,
                should_continue=lambda: allowed["open"])

        self.assertEqual(sent, 2)
        self.assertEqual(len(socket.sent), 2)
        self.assertTrue(all(payload == b"same-message-id" for payload, _ in socket.sent))

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

    def test_directed_peer_challenge_nonce_is_consumed_atomically_under_replay(self):
        from concurrent.futures import ThreadPoolExecutor
        from lan_p2p_core import remember_peer_challenge, complete_peer_challenge

        self.assertTrue(remember_peer_challenge(
            self.db_path, "server2", "nonce-race", "android-a",
            "192.168.1.10", 8888))
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(
                lambda _: complete_peer_challenge(
                    self.db_path, "server2", "nonce-race", "android-a",
                    "192.168.1.10", 8888, "android", "android-main"),
                range(2)))
        self.assertEqual(sum(1 for result in results if result), 1)

    def test_python_chat_ack_requires_verified_peer_route_in_all_services(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            with self.subTest(service=filename):
                source = (repo_root / filename).read_text(encoding="utf-8")
                start = source.index('if packet_type == "chat.ack":')
                end = source.index('if packet_type != "chat.message":', start)
                handler = source[start:end]
                self.assertIn("active_route = peer_endpoint_matches(", handler)
                self.assertIn("and sender and active_route)", handler)
                self.assertNotIn("discovery_ack", handler)
                self.assertNotIn("is_outgoing_discovery_pending(", handler)

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
        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        self.assertIn("boolean routeMatches", ack_handler)
        self.assertIn("boolean discoveryAck", ack_handler)
        self.assertIn("if (!routeMatches && !persistedEndpointMatch)", ack_handler)
        self.assertNotIn("&& !discoveryAck)", ack_handler)
        self.assertIn("Discovery is a recipient-collection window, not an identity bypass", ack_handler)
        self.assertIn("if (routeMatches) {", ack_handler)
        self.assertIn("verifiedChatPeerRouteVersions.put(senderId", ack_handler)
        self.assertIn("verifiedChatPeerSeen.put(senderId, System.currentTimeMillis())", ack_handler)
        self.assertIn("synchronized (verifiedChatPeerRouteVersions)", ack_handler)
        self.assertIn("if (store.allTargetsAcked(messageId))", ack_handler)
        self.assertIn("removePendingLanChat(messageId)", ack_handler)
        self.assertIn("P2P 最终 ACK 已落账并回收兼容队列", ack_handler)
        self.assertNotIn("verifiedChatPeers.put(", ack_handler)
        self.assertIn("store.matchesExpectedAckEndpoint(messageId, senderId", ack_handler)
        self.assertIn("persistedEndpointMatch", ack_handler)
        self.assertIn("lanChatIoExecutor.execute(() -> {", ack_handler)
        self.assertIn("reason=unverified_endpoint", ack_handler)
        endpoint_start = store_source.index("public boolean matchesExpectedAckEndpoint(")
        endpoint_method = store_source[endpoint_start:endpoint_start + 1200]
        self.assertIn("last_endpoint=?", endpoint_method)
        self.assertIn("endpointIp + \":\" + endpointPort", endpoint_method)

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
        self.assertNotIn("receivedChatMessageIds", tracking_source)
        self.assertIn("P2P RX durable message=", tracking_source)
        self.assertIn("persisted=true ackSent=", tracking_source)
        self.assertIn("persisted=false ackSent=false", tracking_source)
        ack_start = tracking_source.index("private boolean sendAuthenticatedChatAck(")
        ack_end = tracking_source.index("private boolean handleLanRealtimePacket(", ack_start)
        ack_method = tracking_source[ack_start:ack_end]
        self.assertIn("return !socket.isClosed()", ack_method)
        self.assertIn("return false", ack_method)


    def test_android_receive_ack_requires_sqlite_history_and_presentation_commit(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        receive = extract_java_method(source, "private boolean persistIncomingLanChat(String senderId, String messageId, String msgContent,")
        sqlite_commit = receive.index("store.storeIncoming(")
        history_commit = receive.index("editor.commit()")
        presentation_commit = receive.rindex("store.markIncomingDisplayed(")
        self.assertLess(sqlite_commit, history_commit)
        self.assertLess(history_commit, presentation_commit)
        self.assertIn("if (!editor.commit())", receive)
        self.assertIn("if (!store.markIncomingDisplayed", receive)
        self.assertIn("return false", receive[presentation_commit:])

        packet_start = source.index('if ("chat.message".equals(type))')
        packet_end = source.index("\n        } catch (Exception e)", packet_start)
        packet_handler = source[packet_start:packet_end]
        persist_call = packet_handler.index("persistIncomingLanChat(")
        ack_call = packet_handler.index("sendAuthenticatedChatAck(")
        self.assertLess(persist_call, ack_call)
        self.assertIn("if (stored)", packet_handler)
        self.assertIn('persisted=false ackSent=false', packet_handler)
        self.assertIn("else {", packet_handler)

        ack = extract_java_method(source, "private boolean sendAuthenticatedChatAck(")
        actual_send = ack.index("sendLanUdpPacket(socket, response, \"CHAT_ACK_V2\")")
        success_return = ack.index("return !socket.isClosed()")
        failure_catch = ack.index("catch (Exception e)")
        self.assertLess(actual_send, success_return)
        self.assertLess(success_return, failure_catch)
        self.assertIn("return false", ack[failure_catch:])


    def test_android_inflight_duplicate_receive_is_coalesced_and_rejection_is_retryable(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index('if ("chat.message".equals(type))')
        end = source.index("\n        } catch (Exception e)", start)
        handler = source[start:end]

        duplicate_guard = handler.index("if (!pendingLanChatReceives.add(receiveKey))")
        claim = handler.index("pendingLanChatReceives.add(receiveKey)", duplicate_guard)
        dispatch = handler.index("lanChatIoExecutor.execute", duplicate_guard)
        persist = handler.index("persistIncomingLanChat(", dispatch)
        ack = handler.index("sendAuthenticatedChatAck(", persist)
        cleanup = handler.index("pendingLanChatReceives.remove(receiveKey)", persist)
        rejected = handler.index("catch (java.util.concurrent.RejectedExecutionException", cleanup)
        rejected_cleanup = handler.index("pendingLanChatReceives.remove(receiveKey)", rejected)

        self.assertLess(duplicate_guard, claim)
        self.assertLess(claim, dispatch)
        self.assertLess(dispatch, persist)
        self.assertLess(persist, ack)
        self.assertLess(ack, cleanup)
        self.assertLess(rejected, rejected_cleanup)
        self.assertIn("return true", handler[duplicate_guard:dispatch])
        self.assertIn("if (stored)", handler[persist:cleanup])


    def test_android_expired_outbox_rejects_late_ack_without_mutating_targets(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        start = source.index("public boolean acknowledge(String messageId, String peerId)")
        end = source.index("public boolean beginDiscoveryWindow(", start)
        acknowledge = source[start:end]
        terminal_guard = acknowledge.index('if (state.isEmpty() || "expired".equals(state)) return false;')
        target_mutation = acknowledge.index('db.update("lan_outbox_targets", ackValues')
        self.assertLess(terminal_guard, target_mutation)
        self.assertIn('"SELECT state,delivery_deadline_ms FROM lan_outbox WHERE message_id=?"', acknowledge)
        self.assertNotIn('next.put("state", total > 0 && total == acked ? "delivered"', acknowledge[:terminal_guard])

    def test_android_all_targets_acked_never_resurrects_expired_outbox(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        start = source.index("public boolean allTargetsAcked(")
        end = source.index("public List<Target> loadKnownPeers(", start)
        method = source[start:end]
        expired_guard = method.index('if ("expired".equals(state)) return false;')
        delivered_mutation = method.index('next.put("state", "delivered")')
        self.assertLess(expired_guard, delivered_mutation)
        self.assertIn('state = c.getString(0);', method)
        self.assertIn('if (discoveryOpen || total == 0) return false;', method)


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

    def test_python_expired_outbox_rejects_late_ack_without_mutating_targets(self):
        from lan_p2p_core import persist_outgoing_message, acknowledge_outgoing_message
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-expired-late-ack",
            "hello", ["android-a"]))
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute(
                "UPDATE lan_outbox SET state='expired' WHERE message_id=?",
                ("m-expired-late-ack",))
            conn.execute(
                "UPDATE lan_outbox_targets SET status='sent',attempt_count=3 "
                "WHERE message_id=? AND peer_id=?",
                ("m-expired-late-ack", "android-a"))
            conn.commit()
        finally:
            conn.close()

        self.assertFalse(acknowledge_outgoing_message(
            self.db_path, "server2", "m-expired-late-ack", "android-a"))
        conn = sqlite3.connect(self.db_path)
        try:
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id=?",
                ("m-expired-late-ack",)).fetchone()[0]
            target = conn.execute(
                "SELECT status,attempt_count,last_ack_at FROM lan_outbox_targets "
                "WHERE message_id=? AND peer_id=?",
                ("m-expired-late-ack", "android-a")).fetchone()
        finally:
            conn.close()
        self.assertEqual(state, "expired")
        self.assertEqual(target, ("sent", 3, 0))


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


    def test_startup_peer_beacon_is_withheld_when_challenge_nonce_cannot_be_persisted(self):
        from lan_p2p_core import send_startup_peer_beacon

        class FakeSocket:
            def __init__(self):
                self.sent = []
            def sendto(self, data, target):
                self.sent.append((data, target))

        sock = FakeSocket()
        with patch("lan_p2p_core.remember_peer_challenge", return_value=False) as persist:
            sent = send_startup_peer_beacon(
                sock, "server2-stable", "server2", "server2-stable",
                "test-secret", 8887, [8888, 8889], ["255.255.255.255"],
                db_path=self.db_path, receiver_scope="server2",
            )
        self.assertEqual(sent, 0)
        self.assertEqual(sock.sent, [])
        persist.assert_called_once()


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


    def test_android_recovery_event_is_latched_across_an_inflight_retry_worker(self):
        repo_root = Path(__file__).resolve().parents[1]
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = tracking.index("private void retryPendingLanChatMessages(boolean recoveryEvent)")
        end = tracking.index("private void transmitLocalUdpMessage(", start)
        retry = tracking[start:end]
        self.assertLess(
            retry.index("if (recoveryEvent) lanChatRecoveryEventPending.set(true)"),
            retry.index("lanChatRetryInFlight.compareAndSet(false, true)"),
        )
        self.assertIn("lanChatRecoveryEventPending.getAndSet(false)", retry)
        self.assertIn("resumeExhaustedRetriesForRecoveryEvent(System.currentTimeMillis())", retry)

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
        # Recovery may reopen only unfinished messages with an unacked fixed target.
        self.assertIn("state NOT IN ('delivered','expired','waiting_peers')", resume)
        self.assertIn("EXISTS (SELECT 1 FROM lan_outbox_targets t", resume)
        self.assertIn("t.message_id=lan_outbox.message_id AND t.status!='acked'", resume)
        self.assertIn("last_event_retry_at_ms<=?", resume)
        self.assertIn("retry_attempt_count=0", resume)
        self.assertIn("state=CASE WHEN state='discovery_pending' THEN 'discovery_pending' ELSE 'pending' END", resume)
        self.assertIn("ELSE 'pending' END", resume)
        attempt_start = store.index("public int recordOutboxAttempt(String messageId)")
        attempt_end = store.index("public List<Target> loadTargets", attempt_start)
        attempt = store[attempt_start:attempt_end]
        self.assertIn("retry_attempt_count<?", attempt)
        self.assertIn("MAX_AUTOMATIC_RETRY_ATTEMPTS", attempt)
        self.assertIn("state!='discovery_pending'", attempt)
        self.assertIn("ELSE state END", attempt)
        migration_start = store.index("if (oldVersion < 5)")
        migration_end = store.index("public boolean enqueue(", migration_start)
        migration = store[migration_start:migration_end]
        self.assertIn("'discovery_pending'", migration)
        self.assertIn("'waiting_peers'", migration)
        self.assertNotIn("DELETE FROM lan_outbox", resume)
        self.assertIn("'retry_paused'", load)
        ack_start = store.index("public boolean acknowledge(String messageId, String peerId)")
        ack_end = store.index("public boolean beginDiscoveryWindow(String messageId)", ack_start)
        ack = store[ack_start:ack_end]
        self.assertIn('"retry_paused".equals(state) ? "retry_paused" : "pending"', ack)


    def test_android_udp_endpoint_is_persisted_only_after_successful_send(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        tracking = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = store.index("public boolean markAttempt(")
        end = store.index("public void recordTargetSendError(", start)
        attempt_method = store[start:end]
        self.assertIn("attempt_count=attempt_count+1", attempt_method)
        self.assertIn("last_error=?", attempt_method)
        self.assertNotIn("last_endpoint", attempt_method)

        endpoint_start = store.index("public boolean recordSuccessfulSendEndpoint(")
        endpoint_method = store[endpoint_start:endpoint_start + 1100]
        self.assertIn('values.put("last_endpoint", endpoint)', endpoint_method)
        self.assertIn("status!='acked'", endpoint_method)

        send_start = tracking.index("private void transmitLocalUdpMessageInternal(")
        send_end = tracking.index("private boolean sendAuthenticatedChatAck(", send_start)
        send_method = tracking[send_start:send_end]
        attempt_pos = send_method.index("!store.markAttempt(messageId, peerId, \"\")")
        skip_pos = send_method.index("continue;", attempt_pos)
        udp_pos = send_method.index('sendLanUdpPacket(socket, p, "CHAT_UNICAST")')
        endpoint_pos = send_method.index("store.recordSuccessfulSendEndpoint(messageId, peerId")
        self.assertLess(attempt_pos, skip_pos)
        self.assertLess(skip_pos, udp_pos)
        self.assertLess(udp_pos, endpoint_pos)
        self.assertIn("recordTargetSendError(", send_method)
        udp_helper_start = tracking.index("private void sendLanUdpPacket(")
        udp_helper = tracking[udp_helper_start:udp_helper_start + 1500]
        self.assertIn("throw new java.net.SocketException", udp_helper)
        self.assertNotIn("if (socket.isClosed()) return", udp_helper)



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
        self.assertIn("delivery_deadline_ms>?", discovery)
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
        self.assertIn("broadcast_all and begin_outgoing_discovery_window", rescue_sender)

        chat = (repo_root / "chat.py").read_text(encoding="utf-8")
        chat_start = chat.index("def send_chat_v2(")
        chat_end = chat.index("\n\n# UDP 监听线程", chat_start)
        chat_sender = chat[chat_start:chat_end]
        self.assertIn("elif target_ids:", chat_sender)
        self.assertIn("message remains pending", chat_sender)
        self.assertIn("elif begin_outgoing_discovery_window", chat_sender)

        server = (repo_root / "server2.py").read_text(encoding="utf-8")
        server_start = server.index("def send_server2_v2_chat_from_outbox(")
        server_end = server.index("\ndef udp_listener", server_start)
        server_sender = server[server_start:server_end]
        self.assertIn("if fixed_target_ids:", server_sender)
        self.assertIn("send_outbox_to_active_peers(", server_sender)
        self.assertIn("if not begin_outgoing_discovery_window(", server_sender)
        self.assertIn("send_broadcast_packet_with_retries(", server_sender)
        self.assertIn("[(ip, 8888)", server_sender)

        action_start = server.index('elif action == "chat_msg":')
        action_end = server.index('elif action == "change_server":', action_start)
        web_chat = server[action_start:action_end]
        self.assertIn("send_server2_v2_chat_from_outbox(cmd_prefix, target_devs)", web_chat)
        self.assertNotIn("smart_send(cmd_prefix)", web_chat)


    def test_peer_reconnect_replay_records_attempt_before_udp_send(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            start = source.index("def _resume_pending_v2_for_peer(")
            end = source.index("\ndef _handle_authenticated_v2_packet", start)
            replay = source[start:end]
            record = replay.index("if not mark_outgoing_attempt(")
            send = replay.index("sock.sendto(raw, (pending[\"endpoint_ip\"], target_port))")
            self.assertLess(record, send, filename)
            self.assertIn("continue", replay[record:send], filename)


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



    def test_android_network_refresh_tasks_coalesce_callback_bursts_without_losing_final_state(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("coalescedMaintenanceStates", source)
        self.assertIn("state.compareAndSet(1, 2)", source)
        self.assertIn("completedState == 2 && !serviceDestroyed", source)
        dispatch_start = source.index("private void executeMaintenance(")
        dispatch = source[dispatch_start:dispatch_start + 1600]
        for task_name in ("peer-route-refresh", "wifi-lan-refresh", "network-refresh"):
            self.assertIn('"'+task_name+'".equals(taskName)', dispatch)
            self.assertIn("executeCoalescedMaintenance(taskName, task)", dispatch)


    def test_android_lan_chat_receive_and_ack_use_dedicated_io_executor(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index("private boolean handleAuthenticatedLanPacket(")
        end = source.index("private boolean sendAuthenticatedChatAck(", start)
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
        self.assertNotIn("ack.await(", send)
        self.assertIn('scheduleLanChatTask("chat-ack-grace-" + messageId', send)
        self.assertIn("ackCleanupDeferred", send)
        self.assertIn("completeLanChatAckWindow(messageId", source)
        self.assertIn("lanChatAckGraceInFlight.add(messageId)", send)
        self.assertLess(
            send.index("lanChatAckGraceInFlight.add(messageId)"),
            send.index('scheduleLanChatTask("chat-ack-grace-" + messageId'),
        )
        self.assertIn("lanChatAckGraceInFlight.contains(messageId)", source)
        self.assertIn("lanChatAckGraceInFlight.remove(messageId)", source)
        self.assertIn("activeLanChatTransmissions.remove(messageId)", source)
        self.assertIn("pendingChatAcks.remove(messageId)", source)
        self.assertIn("scheduleLanChatRetryFromOutbox()", source)
        self.assertIn("activeLanChatTransmissions.add(messageId)", source)
        self.assertIn("synchronized (lanChatTransmitLock)", source)

    def test_android_ack_grace_cleanup_cannot_remove_a_newly_verified_peer_route(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        send = extract_java_method(source, "private void transmitLocalUdpMessageInternal(")
        cleanup = extract_java_method(source, "private void completeLanChatAckWindow(")
        self.assertIn("awaitingRouteVersionSnapshot", send)
        self.assertIn("completionRouteVersionSnapshot", send)
        self.assertIn("routeVersionSnapshot.get(peerId)", cleanup)
        self.assertIn("sentVersion.equals(currentVersion)", cleanup)
        self.assertIn("verifiedChatPeerRouteVersions.remove(peerId)", cleanup)
        self.assertIn("verifiedChatPeers.remove(peerId)", cleanup)
        self.assertIn("synchronized (verifiedChatPeerRouteVersions)", cleanup)
        self.assertIn("synchronized (verifiedChatPeerRouteVersions)", send)
        self.assertIn("verifiedChatPeerRouteVersionCounter.incrementAndGet()", source)


    def test_android_duplicate_ack_cannot_consume_another_peer_wait_slot(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = source.index('if ("chat.ack".equals(type))')
        end = source.index('if ("chat.message".equals(type))', start)
        handler = source[start:end]
        persist_ack = handler.index("store.acknowledge(messageId, senderId)")
        remove_waiter = handler.index("expected.remove(senderId)")
        self.assertLess(persist_ack, remove_waiter)
        self.assertIn("only release the in-memory", handler)
        self.assertIn("discoveryAck && expected != null", handler)
        self.assertIn("expected.isEmpty() && latch != null && latch.getCount() > 0", handler)
        self.assertIn("wasExpected || discoveryBroadcastAck", handler)
        self.assertNotIn("wasExpected || discoveryAck)", handler)


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


    def test_android_discovery_finalization_respects_exhausted_retry_budget(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        start = store.index("private boolean finalizeDiscoveryMessage(")
        end = store.index("public boolean storeIncoming(", start)
        finalize = store[start:end]
        self.assertIn("SELECT retry_attempt_count FROM lan_outbox", finalize)
        self.assertIn("retryAttempts >= MAX_AUTOMATIC_RETRY_ATTEMPTS ? \"retry_paused\" : \"pending\"", finalize)
        self.assertIn("total == 0 ? \"waiting_peers\"", finalize)
        self.assertIn("total == acked ? \"delivered\"", finalize)


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

        store_source = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        finalize = extract_java_method(store_source, "public int finalizeExpiredDiscoveryWindows(")
        self.assertIn("delivery_deadline_ms<=?", finalize)
        self.assertIn("finalizeDiscoveryMessage(db, messageId)", finalize)

        retry = extract_java_method(service, "private void retryPendingLanChatMessages(boolean recoveryEvent)")
        self.assertLess(
            retry.index("store.finalizeExpiredDiscoveryWindows("),
            retry.index("store.loadPending(50)"),
        )
        self.assertIn("if (store.isWaitingForPeers(id))", retry)
        self.assertIn("removePendingLanChat(id);", retry)


    def test_android_peer_ack_is_durable_before_route_activation_and_discovery_snapshot(self):
        repo_root = Path(__file__).resolve().parents[1]
        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        start = service.index('if ("peer.ack".equals(type))')
        end = service.index('if ("chat.ack".equals(type))', start)
        handler = service[start:end]

        # A peer must be durably recorded and added to every still-open first-discovery
        # snapshot before it becomes an active unicast route or releases discovery waiters.
        persist = handler.index("store.upsertPeer(")
        snapshot = handler.index("store.addDiscoveryPeerToOpenMessages(senderId)")
        route = handler.index("verifiedChatPeers.put(senderId")
        release = handler.index("latch.countDown()")
        self.assertLess(persist, snapshot)
        self.assertLess(snapshot, route)
        self.assertLess(route, release)
        self.assertIn("if (!persisted)", handler)
        self.assertIn("if (!store.addDiscoveryPeerToOpenMessages(senderId))", handler)
        self.assertIn("return;", handler[persist:route])
        self.assertIn("lanChatIoExecutor.execute", handler)


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


    def test_android_wifi_lan_change_rebinds_listener_before_outbox_recovery(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")

        refresh_start = source.index("private void refreshWifiLanTransport(")
        refresh_end = source.index("private void registerNetworkCallback(", refresh_start)
        refresh = source[refresh_start:refresh_end]
        self.assertIn("previous.equals(current)", refresh)
        self.assertIn("invalidateVerifiedChatPeers(", refresh)
        self.assertIn("oldSocket.close()", refresh)
        self.assertIn("triggerRescueListener();", refresh)
        self.assertIn("else {", refresh)

        listener_start = source.index("private void startRescueListener()")
        listener_end = source.index("private void parseResponse(", listener_start)
        listener = source[listener_start:listener_end]
        bind = listener.index("bindSocketToWifi(rescueSocket)")
        socket_ready = listener.index("rescueSocketRef = rescueSocket")
        recovery = listener.index('scheduleLanChatTask("listener-outbox-recovery"')
        self.assertLess(bind, socket_ready)
        self.assertLess(socket_ready, recovery)
        self.assertIn("rescueSocketRef = null", listener)
        self.assertIn("isListeningForRescue = false", listener)
        self.assertIn("triggerRescueListener()", listener)


    def test_android_udp_listener_guard_matches_process_wide_listener_state(self):
        source = (
            Path(__file__).resolve().parents[1]
            / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("private static volatile boolean isListeningForRescue = false;", source)
        self.assertIn("private static final Object rescueLock = new Object();", source)
        self.assertNotIn("private final Object rescueLock = new Object();", source)


    def test_android_exhausted_retry_epoch_has_persisted_recovery_cooldown(self):
        repo_root = Path(__file__).resolve().parents[1]
        store = (
            repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java"
        ).read_text(encoding="utf-8")
        start = store.index("public int resumeExhaustedRetriesForRecoveryEvent(")
        end = store.index("public int recordOutboxAttempt(", start)
        method = store[start:end]
        self.assertIn("last_event_retry_at_ms<=?", method)
        self.assertIn("now - RETRY_RECOVERY_EVENT_COOLDOWN_MS", method)
        self.assertIn("retry_attempt_count=0", method)
        self.assertIn("state='discovery_pending'", method)
        self.assertIn("state NOT IN ('delivered','expired','waiting_peers')", method)
        self.assertIn("t.status!='acked'", method)

        service = (
            repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java"
        ).read_text(encoding="utf-8")
        retry_start = service.index("private void retryPendingLanChatMessages(boolean recoveryEvent)")
        retry_end = service.index("private void sendLocalUdpMessage(final String message, final String messageId)", retry_start)
        retry = service[retry_start:retry_end]
        self.assertIn("lanChatRecoveryEventPending.set(true)", retry)
        self.assertIn("lanChatRetryInFlight.compareAndSet(false, true)", retry)
        self.assertIn("lanChatRecoveryEventPending.getAndSet(false)", retry)
        self.assertIn("if (lanChatRecoveryEventPending.get())", retry)
        self.assertIn("retryPendingLanChatMessages(true)", retry)


    def test_server2_udp_ack_requires_verified_route_and_durable_outbox_ack_before_fallback_cleanup(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index('if packet_type == "chat.ack":')
        end = source.index('if packet_type != "chat.message":', start)
        handler = source[start:end]
        route = handler.index("peer_endpoint_matches(")
        persist = handler.index("acknowledge_outgoing_message(")
        cleanup = handler.index("_remove_chat_fallback_for_message(")
        self.assertLess(route, persist)
        self.assertLess(persist, cleanup)
        self.assertIn("and active_route", handler)
        self.assertIn('envelope.get("status") == "stored"', handler)


    def test_server2_http_chat_fallback_shares_identity_and_is_queued_before_udp_dispatch(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index('elif action == "chat_msg":')
        end = source.index('elif action == "upload_log":', start)
        handler = source[start:end]
        encode = handler.index("encode_chat_http_fallback_payload(")
        queue = handler.index('enqueue_command(dev_id, "chat_msg", fallback_payload)')
        udp = handler.index("send_server2_v2_chat_from_outbox(")
        self.assertLess(encode, queue)
        self.assertLess(queue, udp)
        self.assertIn("chat_message_id", handler)
        self.assertIn("message_id=chat_message_id", handler)
        self.assertIn("fallback_queued_count = 0", handler)
        self.assertIn("queued_count = fallback_queued_count", handler)


    def test_server2_executed_chat_id_persists_outbox_ack_before_http_queue_removal(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def process_executed_commands(")
        end = source.index("def process_command_feedback(", start)
        handler = source[start:end]
        ack = handler.index("acknowledge_outgoing_message(")
        remove = handler.index("PENDING_COMMANDS[dev_id] = new_queue")
        persist = handler.index("_persist_command_queue_locked(dev_id)")
        self.assertLess(ack, remove)
        self.assertLess(remove, persist)
        self.assertIn("if not removable_ids:", handler)
        self.assertIn("fallback retained", handler)
        self.assertIn("PENDING_COMMANDS[dev_id] = old_queue", handler)


    def test_server2_accepted_chat_feedback_cannot_retire_fallback_when_outbox_ack_fails(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def process_command_feedback(")
        end = source.index("def get_recent_command_feedback(", start)
        handler = source[start:end]
        ack = handler.index("acknowledge_outgoing_message(")
        reconciled = handler.index("reconciled_chat_command_ids.add(command_id)")
        terminal = handler.index("terminal_ids = {")
        self.assertLess(ack, reconciled)
        self.assertLess(reconciled, terminal)
        self.assertIn("row[0] not in reconciled_chat_command_ids", handler)
        self.assertIn("fallback retained", handler)


    def test_server2_command_queue_never_evicts_unacked_chat_fallbacks(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def enqueue_command(")
        end = source.index("def process_executed_commands(", start)
        handler = source[start:end]
        self.assertIn('item.get("type") != "chat_msg"', handler)
        self.assertIn("if evictable is None:", handler)
        self.assertIn("reject new", handler)
        self.assertNotIn("queue.pop(0)", handler)
        self.assertIn("HTTP queue enqueue rolled back", handler)


    def test_server2_chat_fallback_cleanup_rolls_back_memory_on_database_failure(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def _remove_chat_fallback_for_message(")
        end = source.index("def _load_pending_commands(", start)
        handler = source[start:end]
        persist = handler.index("_persist_command_queue_locked(dev_id)")
        except_block = handler.index("except Exception as exc:", persist)
        restore = handler.index("PENDING_COMMANDS[dev_id] = old_queue", except_block)
        failed = handler.index("return False", restore)
        self.assertLess(persist, except_block)
        self.assertLess(except_block, restore)
        self.assertLess(restore, failed)


    def test_server2_terminal_feedback_cleanup_rolls_back_queue_on_persistence_failure(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def process_command_feedback(")
        end = source.index("def get_recent_command_feedback(", start)
        handler = source[start:end]
        persist = handler.index("_persist_command_queue_locked(dev_id)")
        restore = handler.index("PENDING_COMMANDS[dev_id] = old_queue", persist)
        self.assertLess(persist, restore)
        self.assertIn("terminal command cleanup rolled back", handler)
        self.assertNotIn('_remove_chat_fallback_for_message(dev_id, envelope["message_id"])', handler)


    def test_server2_feedback_recovers_missing_action_from_pending_command_type(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def process_command_feedback(")
        end = source.index("def get_recent_command_feedback(", start)
        handler = source[start:end]
        lookup = handler.index("pending_actions = {")
        fallback = handler.index("pending_action = pending_actions.get(command_id)")
        reconcile = handler.index("chat_fallback_candidates.append((command_id, envelope))")
        self.assertLess(lookup, fallback)
        self.assertLess(fallback, reconcile)
        self.assertIn("action = pending_action or supplied_action", handler)
        self.assertIn('if action == "chat_msg" and status in ("accepted", "succeeded")', handler)
        self.assertIn('row[2] == "chat_msg" and row[0] not in reconciled_chat_command_ids', handler)


    def test_server2_command_ids_are_unique_across_process_restarts(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index("def enqueue_command(")
        end = source.index("def process_executed_commands(", start)
        enqueue = source[start:end]
        self.assertIn('cmd_id = f"c_{uuid.uuid4().hex}"', enqueue)
        self.assertNotIn("CMD_SEQ", enqueue)


    def test_server2_http_queue_rejection_is_counted_not_reported_as_queued(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index('elif "POST /api/control" in header_text:')
        end = source.index('# ===== GET /api/history =====', start)
        handler = source[start:end]
        self.assertIn("queue_rejected_count += 1", handler)
        self.assertIn("queued_count += 1", handler)
        self.assertIn("{queue_rejected_count}/{len(target_devs)}台设备", handler)
        self.assertNotIn("queued_count = len(target_devs) if action in HTTP_SUPPORTED_ACTIONS", handler)


    def test_server2_chat_outbox_is_successful_when_durable_but_peers_are_offline(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        start = source.index('elif action == "chat_msg":')
        end = source.index('elif action == "change_server":', start)
        handler = source[start:end]
        self.assertIn("if chat_packets:", handler)
        self.assertIn("success = True", handler)
        self.assertIn("LAN消息已持久化", handler)
        self.assertIn("未建立持久化投递路径", handler)


    def test_android_unverified_peer_hello_cannot_extend_verified_route_lease(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/LanChatStore.java").read_text(encoding="utf-8")
        start = source.index("public boolean observePeer(")
        end = source.index("public boolean isIncomingDisplayed(", start)
        observe = source[start:end]
        self.assertIn("last_verified_at,status,lease_expires_at", observe)
        self.assertIn("oldLeaseExpiresAt = c.getLong(4)", observe)
        self.assertIn("v.put(\"lease_expires_at\", oldLeaseExpiresAt)", observe)
        self.assertNotIn("v.put(\"lease_expires_at\", leaseExpiresAt)", observe)
        verified = source[source.index("public boolean upsertPeer("):source.index("public void closeStore(")]
        self.assertIn("v.put(\"lease_expires_at\", leaseExpiresAt)", verified)


    def test_android_recovery_event_is_not_lost_during_inflight_retry_batch(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        start = source.index("private void retryPendingLanChatMessages(boolean recoveryEvent)")
        end = source.index("// 仅由 lanChatSendExecutor 调用", start)
        retry = source[start:end]
        self.assertIn("if (recoveryEvent) lanChatRecoveryEventPending.set(true)", retry)
        self.assertIn("lanChatRecoveryEventPending.getAndSet(false)", retry)
        self.assertIn("if (lanChatRecoveryEventPending.get())", retry)
        self.assertIn("retryPendingLanChatMessages(true)", retry)
        self.assertIn("store.resumeExhaustedRetriesForRecoveryEvent", retry)


    def test_android_ordinary_retry_timer_does_not_reopen_exhausted_retry_epoch(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        start = source.index("private void retryPendingLanChatMessages()")
        end = source.index("private void retryPendingLanChatMessages(boolean recoveryEvent)", start)
        wrapper = source[start:end]
        self.assertIn("retryPendingLanChatMessages(false)", wrapper)
        retry_start = end
        retry_end = source.index("// 仅由 lanChatSendExecutor 调用", retry_start)
        retry = source[retry_start:retry_end]
        self.assertIn("if (lanChatRecoveryEventPending.getAndSet(false))", retry)
        self.assertNotIn("resumeExhaustedRetriesForRecoveryEvent", wrapper)


    def test_android_default_network_switch_does_not_revoke_unchanged_wifi_lan_peers(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        start = source.index("private void refreshPeerRoutesForNetworkChange(")
        end = source.index("/** 网络路径变化立即撤销旧 LAN endpoint", start)
        refresh = source[start:end]
        self.assertIn("默认网络路径变化但保留 LAN peer", refresh)
        self.assertNotIn("invalidateVerifiedChatPeers(", refresh)
        self.assertNotIn("broadcastLanPeerHello", refresh)
        wifi_start = source.index("private void refreshWifiLanTransport(")
        wifi_end = source.index("private void registerNetworkCallback()", wifi_start)
        wifi_refresh = source[wifi_start:wifi_end]
        self.assertIn("invalidateVerifiedChatPeers(\"Wi-Fi LAN path changed", wifi_refresh)
        self.assertIn("oldSocket.close()", wifi_refresh)
        # Transient platform inspection failures must not be mistaken for a real
        # Wi-Fi LAN topology change, otherwise valid peer routes are needlessly revoked.
        guard = wifi_refresh.index('if ("ERROR".equals(current) || "UNAVAILABLE".equals(current))')
        invalidate = wifi_refresh.index('invalidateVerifiedChatPeers("Wi-Fi LAN path changed')
        self.assertLess(guard, invalidate)
        self.assertIn("retaining verified peer routes", wifi_refresh)
        fingerprint_start = source.index("private String buildWifiLanFingerprint")
        fingerprint_end = source.index("private void refreshWifiLanTransport", fingerprint_start)
        fingerprint = source[fingerprint_start:fingerprint_end]
        self.assertIn('if (caps == null) return "UNAVAILABLE";', fingerprint)
        self.assertIn('if (links == null || links.getInterfaceName() == null', fingerprint)
        self.assertIn('|| links.getLinkAddresses().isEmpty())', fingerprint)
        self.assertIn('return "UNAVAILABLE";', fingerprint)
        # getAllNetworks() ordering is unspecified; canonical sorting prevents
        # false topology changes when Android enumerates the same networks differently.
        sort_at = fingerprint.index("java.util.Collections.sort(wifiEntries)")
        append_at = fingerprint.index("for (String entry : wifiEntries)")
        self.assertLess(sort_at, append_at)
        self.assertIn("wifiEntries.add(network.toString()", fingerprint)
        self.assertIn('if (wifiEntries.isEmpty()) return "NO_WIFI";', fingerprint)

    def test_outbox_database_read_failures_raise_instead_of_looking_empty(self):
        operations = (
            lambda: load_pending_outgoing_for_peer(self.db_path, "server2", "peer-a"),
            lambda: add_discovery_targets_for_peer(self.db_path, "server2", "peer-a"),
            lambda: load_active_pending_targets(self.db_path, "server2", "message-a"),
        )
        for operation in operations:
            with self.subTest(operation=operation):
                with patch("lan_p2p_core.sqlite3.connect",
                           side_effect=sqlite3.OperationalError("database is locked")):
                    with self.assertRaises(LanP2PStorageError):
                        operation()

    def test_mark_outgoing_attempt_storage_failure_is_not_reported_as_a_normal_skip(self):
        with patch("lan_p2p_core.sqlite3.connect",
                   side_effect=sqlite3.OperationalError("database is locked")):
            with self.assertRaises(LanP2PStorageError):
                mark_outgoing_attempt(self.db_path, "message-a", "peer-a",
                                      "192.168.1.20", 8888)

    def test_android_wifi_socket_binding_failure_does_not_fall_back_to_default_route(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        method = extract_java_method(source, "private void bindSocketToWifi(")
        self.assertIn("throws java.io.IOException", method)
        self.assertIn("throw new java.io.IOException", method)
        self.assertNotIn("将使用系统默认出口", method)
        self.assertIn("selected.bindSocket(socket)", method)
        self.assertIn("incompleteNetworkSnapshot", method)
        self.assertIn("Network capabilities incomplete; defer UDP socket binding", method)
        self.assertIn("Network active = cm.getActiveNetwork()", method)
        self.assertIn("wifiNetworks.contains(active)", method)
        self.assertIn("wifiNetworks.size() == 1", method)
        self.assertIn("Multiple non-default Wi-Fi networks; defer ambiguous UDP socket binding", method)
        # Selection must be based on the complete candidate set, not enumeration order.
        self.assertLess(method.index("for (Network network : cm.getAllNetworks())"),
                        method.index("Network active = cm.getActiveNetwork()"))
        self.assertLess(method.index("Network active = cm.getActiveNetwork()"),
                        method.index("selected.bindSocket(socket)"))

    def test_outbox_queries_still_return_empty_for_a_valid_empty_ledger(self):
        conn = sqlite3.connect(self.db_path)
        try:
            conn.executescript("""
                CREATE TABLE lan_outbox (
                    message_id TEXT PRIMARY KEY, sender_scope TEXT NOT NULL,
                    sender_peer_id TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '',
                    created_at_ms INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL,
                    delivery_deadline_ms INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE lan_outbox_targets (
                    message_id TEXT NOT NULL, peer_id TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending');
                CREATE TABLE lan_peers (
                    receiver_scope TEXT NOT NULL, peer_id TEXT NOT NULL,
                    endpoint_ip TEXT NOT NULL DEFAULT '', endpoint_port INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'suspect', lease_expires_at REAL NOT NULL DEFAULT 0);
            """)
            conn.commit()
        finally:
            conn.close()
        self.assertEqual([], load_pending_outgoing_for_peer(self.db_path, "server2", "peer-a"))
        self.assertEqual(0, add_discovery_targets_for_peer(self.db_path, "server2", "peer-a"))
        self.assertEqual([], load_active_pending_targets(self.db_path, "server2", "message-a"))

    def test_all_python_services_defer_peer_recovery_on_ledger_read_failure(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            start = source.index("def _resume_pending_v2_for_peer(")
            end = source.index("\ndef _handle_authenticated_v2_packet", start)
            recovery = source[start:end]
            self.assertIn("try:", recovery, filename)
            self.assertIn("except Exception as e:", recovery, filename)
            self.assertIn("durable ledger read failed", recovery, filename)
            self.assertIn("load_pending_outgoing_for_peer(", recovery, filename)

    def test_all_python_services_stop_peer_replay_on_outbox_storage_write_failure(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            self.assertIn("from lan_p2p_core import LanP2PStorageError", source, filename)
            start = source.index("def _resume_pending_v2_for_peer(")
            end = source.index("\ndef _handle_authenticated_v2_packet", start)
            recovery = source[start:end]
            storage_catch = recovery.index("except LanP2PStorageError as e:")
            generic_catch = recovery.index("except Exception as e:", storage_catch)
            self.assertLess(storage_catch, generic_catch, filename)
            storage_handler = recovery[storage_catch:generic_catch]
            self.assertIn("durable ledger write failed", storage_handler, filename)
            self.assertIn("return", storage_handler, filename)


    def test_outbox_storage_error_is_not_masked_by_rollback_or_close_failure(self):
        class BrokenCleanupConnection:
            def execute(self, *args, **kwargs):
                raise sqlite3.OperationalError("primary write failure")
            def rollback(self):
                raise RuntimeError("rollback cleanup failure")
            def close(self):
                raise RuntimeError("close cleanup failure")

        with patch("lan_p2p_core.sqlite3.connect", return_value=BrokenCleanupConnection()):
            with self.assertRaises(LanP2PStorageError) as caught:
                mark_outgoing_attempt(self.db_path, "message-a", "peer-a",
                                      "192.168.1.20", 8888)
        self.assertIsInstance(caught.exception.__cause__, sqlite3.OperationalError)
        self.assertEqual("primary write failure", str(caught.exception.__cause__))





    def test_android_network_callback_pair_self_heals_on_api_21_and_above(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "app/src/main/java/com/example/devicefinder/TrackingService.java").read_text(encoding="utf-8")
        method = extract_java_method(source, "private void selfHeal()")
        self.assertIn("(networkCallback == null || wifiLanNetworkCallback == null)", method)
        self.assertIn("Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP", method)
        register = extract_java_method(source, "private void registerNetworkCallback()")
        self.assertIn("Build.VERSION_CODES.N", register)
        self.assertIn("registerNetworkCallback(defaultNetworkRequest, networkCallback)", register)
        self.assertIn("registerNetworkCallback(wifiRequest, wifiLanNetworkCallback)", register)




    def test_all_python_services_retry_deferred_peer_recovery_without_new_handshake(self):
        repo_root = Path(__file__).resolve().parents[1]
        for filename in ("rescue2.py", "chat.py", "server2.py"):
            source = (repo_root / filename).read_text(encoding="utf-8")
            self.assertIn("_V2_RECOVERY_RETRY_PEERS = set()", source, filename)
            self.assertIn("_V2_RECOVERY_INFLIGHT = set()", source, filename)
            self.assertIn("def _retry_deferred_v2_peer_recoveries():", source, filename)
            self.assertIn("time.sleep(10)", source, filename)
            self.assertIn("list_known_peers(", source, filename)
            self.assertIn("def _start_v2_recovery_retry_worker():", source, filename)
            self.assertIn("if _V2_RECOVERY_WORKER_STARTED:", source, filename)
            start = source.index("def _resume_pending_v2_for_peer(")
            end = source.index("\ndef _retry_deferred_v2_peer_recoveries", start)
            recovery = source[start:end]
            self.assertIn("_V2_RECOVERY_RETRY_PEERS.add(peer_id)", recovery)
            self.assertIn("_V2_RECOVERY_INFLIGHT", recovery)
            self.assertIn("_V2_RECOVERY_RETRY_PEERS.discard(peer_id)", recovery)
            self.assertIn("if completed:", recovery)




    def test_outbox_ack_commit_failure_rolls_back_and_preserves_pending_recipient(self):
        from lan_p2p_core import persist_outgoing_message, acknowledge_outgoing_message
        self.assertTrue(persist_outgoing_message(
            self.db_path, "server2", "server2-id", "m-ack-commit-fail",
            "hello", ["android-a"]))
        real_connect = sqlite3.connect

        class CommitFailConnection:
            def __init__(self, connection):
                self.connection = connection
            def __getattr__(self, name):
                return getattr(self.connection, name)
            def commit(self):
                raise sqlite3.OperationalError("injected ACK commit failure")

        with patch("lan_p2p_core.sqlite3.connect",
                   side_effect=lambda *args, **kwargs: CommitFailConnection(
                       real_connect(*args, **kwargs))):
            self.assertFalse(acknowledge_outgoing_message(
                self.db_path, "server2", "m-ack-commit-fail", "android-a"))

        conn = real_connect(self.db_path)
        try:
            target = conn.execute(
                "SELECT status FROM lan_outbox_targets WHERE message_id=? AND peer_id=?",
                ("m-ack-commit-fail", "android-a")).fetchone()
            state = conn.execute(
                "SELECT state FROM lan_outbox WHERE message_id=?",
                ("m-ack-commit-fail",)).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(target[0], "pending")
        self.assertEqual(state, "pending")

    def test_server2_executed_chat_ack_and_queue_cleanup_failure_replay_is_idempotent(self):
        repo_root = Path(__file__).resolve().parents[1]
        source = (repo_root / "server2.py").read_text(encoding="utf-8")
        module = ast.parse(source, filename="server2.py")
        function = next(node for node in module.body
                        if isinstance(node, ast.FunctionDef)
                        and node.name == "process_executed_commands")
        isolated = ast.Module(body=[function], type_ignores=[])
        namespace = {
            "CHAT_DEDUP_DB": "CHAT_DEDUP_DB",
            "PENDING_COMMANDS": {
                "android-a": [{
                    "id": "cmd-chat-1",
                    "type": "chat_msg",
                    "payload": {"message_id": "shared-message-1"},
                }]
            },
            "_cmd_lock": threading.Lock(),
            "print_lock": threading.Lock(),
            "decode_chat_http_fallback_payload": lambda payload: payload,
        }
        ack_results = iter([False, True, True])
        ack_calls = []
        def acknowledge(db_path, scope, message_id, peer_id):
            ack_calls.append((db_path, scope, message_id, peer_id))
            return next(ack_results)
        persist_calls = []
        def persist(dev_id):
            persist_calls.append(dev_id)
            if len(persist_calls) == 1:
                raise sqlite3.OperationalError("injected command queue commit failure")
        namespace["acknowledge_outgoing_message"] = acknowledge
        namespace["_persist_command_queue_locked"] = persist
        exec(compile(isolated, "server2.py", "exec"), namespace)
        process = namespace["process_executed_commands"]

        # HTTP execution without a durable shared-ID outbox ACK must retain fallback.
        process("android-a", ["cmd-chat-1"])
        self.assertEqual(len(namespace["PENDING_COMMANDS"]["android-a"]), 1)
        self.assertEqual(persist_calls, [])

        # ACK is durable, but queue persistence fails: memory restores the fallback.
        process("android-a", ["cmd-chat-1"])
        self.assertEqual(len(namespace["PENDING_COMMANDS"]["android-a"]), 1)
        self.assertEqual(persist_calls, ["android-a"])

        # The same executed receipt is replayed; duplicate ACK is accepted and cleanup commits.
        process("android-a", ["cmd-chat-1"])
        self.assertNotIn("android-a", namespace["PENDING_COMMANDS"])
        self.assertEqual(persist_calls, ["android-a", "android-a"])
        self.assertEqual(ack_calls, [
            ("CHAT_DEDUP_DB", "server2", "shared-message-1", "android-a"),
            ("CHAT_DEDUP_DB", "server2", "shared-message-1", "android-a"),
            ("CHAT_DEDUP_DB", "server2", "shared-message-1", "android-a"),
        ])
        process("android-a", ["cmd-chat-1"])
        self.assertEqual(len(ack_calls), 3)




if __name__ == "__main__":
    unittest.main()
