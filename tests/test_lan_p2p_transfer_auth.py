import socket
import threading
import unittest
from lan_p2p_transfer_auth import authenticate_tcp_client, authenticate_tcp_server
from lan_p2p_transfer import TransferValidationError

class TransferAuthenticationTests(unittest.TestCase):
    SECRET = "lan-group-secret-for-auth-tests"
    def _run_pair(self, client_secret=None, server_secret=None, allowed=True, expected_server="receiver"):
        left,right=socket.socketpair(); left.settimeout(1); right.settimeout(1)
        result,errors={},[]
        def server():
            try:
                result["server"]=authenticate_tcp_server(right,"receiver",server_secret or self.SECRET,
                    authorize_peer=lambda peer: allowed and peer=="sender")
            except Exception as exc: errors.append(exc)
            finally: right.close()
        worker=threading.Thread(target=server); worker.start()
        try:
            result["client"]=authenticate_tcp_client(left,"sender",expected_server,client_secret or self.SECRET)
        except Exception as exc: errors.append(exc)
        finally: left.close()
        worker.join(timeout=2)
        self.assertFalse(worker.is_alive(),"server authentication thread leaked")
        return result,errors
    def test_mutual_authentication_derives_identical_session_key(self):
        result,errors=self._run_pair()
        self.assertEqual([],errors)
        self.assertEqual("receiver", result["client"][0])
        self.assertEqual("sender", result["server"][0])
        self.assertEqual(result["client"][1], result["server"][1])
        self.assertEqual(32,len(result["client"][1]))
    def test_unknown_peer_is_rejected_by_trust_registry(self):
        result,errors=self._run_pair(allowed=False)
        self.assertNotIn("server",result); self.assertTrue(errors)
    def test_wrong_group_secret_is_rejected(self):
        result,errors=self._run_pair(client_secret="wrong-secret")
        self.assertTrue(errors); self.assertNotIn("server",result)
    def test_unexpected_server_identity_is_rejected(self):
        result,errors=self._run_pair(expected_server="other-device")
        self.assertTrue(errors); self.assertNotIn("client",result)


    def test_per_peer_secret_provider_matches_android_credential_model(self):
        client_secret = "base64-encoded-peer-secret-for-sender"
        other_secret = "different-secret-for-another-peer"
        left, right = socket.socketpair()
        left.settimeout(1)
        right.settimeout(1)
        result, errors = {}, []

        def server():
            try:
                result["server"] = authenticate_tcp_server(
                    right, "receiver", None,
                    authorize_peer=lambda peer: peer == "sender",
                    secret_provider=lambda peer: {
                        "sender": client_secret,
                        "other": other_secret,
                    }.get(peer),
                )
            except Exception as exc:
                errors.append(exc)
            finally:
                right.close()

        worker = threading.Thread(target=server)
        worker.start()
        try:
            result["client"] = authenticate_tcp_client(
                left, "sender", "receiver", client_secret
            )
        except Exception as exc:
            errors.append(exc)
        finally:
            left.close()
        worker.join(timeout=2)
        self.assertFalse(worker.is_alive(), "per-peer auth thread leaked")
        self.assertEqual([], errors)
        self.assertEqual(result["client"][1], result["server"][1])

    def test_per_peer_secret_provider_rejects_missing_peer_credential(self):
        left, right = socket.socketpair()
        left.settimeout(1)
        right.settimeout(1)
        errors = []

        def server():
            try:
                authenticate_tcp_server(
                    right, "receiver", None,
                    authorize_peer=lambda peer: peer == "sender",
                    secret_provider=lambda peer: None,
                )
            except Exception as exc:
                errors.append(exc)
            finally:
                right.close()

        worker = threading.Thread(target=server)
        worker.start()
        try:
            with self.assertRaises(TransferValidationError):
                authenticate_tcp_client(left, "sender", "receiver", self.SECRET)
        finally:
            left.close()
        worker.join(timeout=2)
        self.assertFalse(worker.is_alive(), "missing-credential auth thread leaked")
        self.assertTrue(errors)

    def test_replayed_server_proof_is_rejected_for_fresh_client_nonce(self):
        import hashlib
        import hmac
        import struct
        key = hashlib.sha256(b"lan-p2p-v2\\x00" + self.SECRET.encode()).digest()
        old_client_nonce = b"o" * 32
        server_nonce = b"s" * 32
        client_nonce = b"n" * 32
        client_id, server_id = b"sender", b"receiver"
        transcript = (struct.pack("!H", len(client_id)) + client_id
                      + struct.pack("!H", len(server_id)) + server_id
                      + old_client_nonce + server_nonce)
        replayed_proof = hmac.new(
            key, b"lan-transfer-v1/server\\x00" + transcript, hashlib.sha256
        ).digest()
        left, right = socket.socketpair()
        left.settimeout(1)
        def replay_server():
            try:
                data = bytearray()
                while len(data) < 4 + 2 + len(client_id) + 32:
                    part = right.recv(4 + 2 + len(client_id) + 32 - len(data))
                    if not part:
                        return
                    data.extend(part)
                right.sendall(struct.pack("!H", len(server_id)) + server_id
                              + server_nonce + replayed_proof)
            finally:
                right.close()
        worker = threading.Thread(target=replay_server)
        worker.start()
        try:
            with self.assertRaises(TransferValidationError):
                authenticate_tcp_client(left, "sender", "receiver", self.SECRET,
                                         nonce_factory=lambda size: client_nonce)
        finally:
            left.close()
        worker.join(timeout=2)
        self.assertFalse(worker.is_alive())

    def test_invalid_nonce_source_is_rejected(self):
        left,right=socket.socketpair()
        with self.assertRaises(TransferValidationError):
            authenticate_tcp_client(left,"sender","receiver",self.SECRET,nonce_factory=lambda size:b"short")
        left.close(); right.close()

if __name__=="__main__": unittest.main()
