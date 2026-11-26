"""Golden vectors shared with Android LanTransferFrameCodecTest and authenticator tests."""
import hashlib
import hmac
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

    def test_android_python_authentication_derivation_golden_vector(self):
        secret = "base64-encoded-peer-secret-for-sender"
        client_id, server_id = b"android-A", b"python-B"
        client_nonce, server_nonce = bytes(range(32)), bytes(range(32, 64))
        key = _key(secret)
        transcript = _transcript(client_id, server_id, client_nonce, server_nonce)
        self.assertEqual("a69a4ac48fa3fa1d8454c4732275bedc71b973d349b767515d05ac46c62ff330", key.hex())
        self.assertEqual("bed8741fd9deae227eeae2cf44eae2cb12399ec6ca3c7e1920a65a76fc7dac57",
                         hmac.new(key, _DOMAIN_SERVER + transcript, hashlib.sha256).hexdigest())
        self.assertEqual("29230d3e9d9fda979422daf6f64214b520aa17cceee2cad7a1ea74c62ad39a48",
                         hmac.new(key, _DOMAIN_CLIENT + transcript, hashlib.sha256).hexdigest())
        self.assertEqual("955200ba44ad7dd482ef90b9541247a0d27d59970cfdf1c9f17126be67d72be1",
                         hmac.new(key, _DOMAIN_SESSION + transcript, hashlib.sha256).hexdigest())


if __name__ == "__main__":
    unittest.main()
