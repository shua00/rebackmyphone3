import os
import sqlite3
import tempfile
import unittest

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

    def test_peer_lease_keeps_verified_status_after_hello_refresh(self):
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "suspect"))
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.10", 8888, "active"))
        self.assertTrue(record_peer(self.db_path, "server2", "android-a",
                                    "android", "android-main", "192.168.1.11", 8888, "suspect"))
        conn = sqlite3.connect(self.db_path)
        try:
            row = conn.execute(
                "SELECT endpoint_ip,last_verified_at,status FROM lan_peers "
                "WHERE receiver_scope='server2' AND peer_id='android-a'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(row[0], "192.168.1.11")
        self.assertGreater(row[1], 0)
        self.assertEqual(row[2], "active")

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


if __name__ == "__main__":
    unittest.main()


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
