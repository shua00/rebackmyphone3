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
        self.assertEqual(("receiver",result["client"][1]),result["server"])
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
    def test_invalid_nonce_source_is_rejected(self):
        left,right=socket.socketpair()
        with self.assertRaises(TransferValidationError):
            authenticate_tcp_client(left,"sender","receiver",self.SECRET,nonce_factory=lambda size:b"short")
        left.close(); right.close()

if __name__=="__main__": unittest.main()
