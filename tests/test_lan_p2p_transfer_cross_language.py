"""Golden vectors shared with Android LanTransferFrameCodecTest and authenticator tests."""
import hashlib
import hmac
import json
from pathlib import Path
import socket
import unittest

from lan_p2p_transfer_auth import (
    _DOMAIN_CLIENT, _DOMAIN_SERVER, _DOMAIN_SESSION, _key, _transcript,
)
from lan_p2p_transfer_tcp import TransferFrame, encode_frame, recv_frame


FRAME_KEY = b"0123456789abcdef-session-key"
JAVA_PYTHON_GOLDEN_FRAME = (
    "4c4e5032000000f47b2261757468223a223334333865386135353663616362353133373564666236613530333138653332"
    "3839333962646162343939316165323639663365653535393165373337666363222c22696e646578223a6e756c6c2c"
    "226b696e64223a2263616e63656c222c226d65746164617461223a6e756c6c2c227061796c6f61645f736861323536"
    "223a22222c227061796c6f61645f73697a65223a302c2273656e6465725f706565725f6964223a2273656e64657222"
    "2c227461726765745f706565725f6964223a227265636569766572222c227472616e736665725f6964223a22747261"
    "6e736665722d31222c2276223a317d"
)


class CrossLanguageTransferVectors(unittest.TestCase):
    def test_python_encoder_matches_android_golden_wire_bytes(self):
        frame = TransferFrame("cancel", "transfer-1", "sender", "receiver")
        self.assertEqual(bytes.fromhex(JAVA_PYTHON_GOLDEN_FRAME),
                         encode_frame(frame, FRAME_KEY, 1024))

    def test_python_decoder_accepts_android_golden_wire_bytes(self):
        import socket
        left, right = socket.socketpair()
        try:
            left.sendall(bytes.fromhex(JAVA_PYTHON_GOLDEN_FRAME))
            frame = recv_frame(right, FRAME_KEY, 1024, "sender", "receiver")
            self.assertEqual(("cancel", "transfer-1", "sender", "receiver"),
                             (frame.kind, frame.transfer_id, frame.sender_peer_id, frame.target_peer_id))
        finally:
            left.close()
            right.close()

    def test_all_control_and_chunk_frames_match_shared_android_fixture(self):
        fixture_path = (Path(__file__).resolve().parents[1]
                        / "app" / "src" / "test" / "resources"
                        / "lan_p2p_frame_vectors.json")
        fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
        key = bytes.fromhex(fixture["session_key_hex"])
        for vector in fixture["frames"]:
            with self.subTest(kind=vector["kind"]):
                payload = bytes.fromhex(vector["payload_hex"])
                frame = TransferFrame(
                    vector["kind"], vector["transfer_id"],
                    vector["sender_peer_id"], vector["target_peer_id"],
                    index=vector["index"], payload=payload,
                    metadata=vector["metadata"],
                )
                expected = bytes.fromhex(vector["wire_hex"])
                self.assertEqual(expected, encode_frame(frame, key, fixture["max_payload_bytes"]))
                left, right = socket.socketpair()
                try:
                    left.sendall(expected)
                    decoded = recv_frame(right, key, fixture["max_payload_bytes"],
                                          vector["sender_peer_id"], vector["target_peer_id"])
                    self.assertEqual(vector["kind"], decoded.kind)
                    self.assertEqual(payload, decoded.payload)
                    self.assertEqual(vector["metadata"], decoded.metadata)
                finally:
                    left.close()
                    right.close()

    def test_android_python_authentication_derivation_golden_vector(self):
        secret = "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg="
        client_id, server_id = b"android-A", b"python-B"
        client_nonce, server_nonce = bytes(range(32)), bytes(range(32, 64))
        key = _key(secret)
        transcript = _transcript(client_id, server_id, client_nonce, server_nonce)
        self.assertEqual("80d0e9e112555fb1910dc6a60c103f1a0b1e3639ac1060517fcaf215263c5af7", key.hex())
        self.assertEqual("51d864d2fc5cef2474309c7b0188f743789a05227f33258213ac197e716cd23e",
                         hmac.new(key, _DOMAIN_SERVER + transcript, hashlib.sha256).hexdigest())
        self.assertEqual("585ca4ddbad267eab82efd459a0893b3b3cb95d8ae013683d19a4a1ecc999f0d",
                         hmac.new(key, _DOMAIN_CLIENT + transcript, hashlib.sha256).hexdigest())
        self.assertEqual("6c962607aa55ed1c48952647b0c759a7efc179c6921b30fad4e204cce704ef8a",
                         hmac.new(key, _DOMAIN_SESSION + transcript, hashlib.sha256).hexdigest())


if __name__ == "__main__":
    unittest.main()
