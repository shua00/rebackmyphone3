import hashlib
import socket
import tempfile
import threading
import unittest
from pathlib import Path
from lan_p2p_transfer import TransferLimits, TransferOffer, TransferState
from lan_p2p_transfer_files import TransferFilesystem
from lan_p2p_transfer_network import TransferTcpServer, connect_transfer_peer
from lan_p2p_transfer_auth import authenticate_tcp_client, authenticate_tcp_server
from lan_p2p_transfer_sender import send_file_over_session
from lan_p2p_transfer_store import TransferStore

class TransferTcpNetworkTests(unittest.TestCase):
    SECRET = b"network-integration-secret-32-bytes"
    def _prepared(self, root, payload):
        source = root / "source.bin"
        source.write_bytes(payload)
        offer = TransferOffer("network-transfer", "sender", "receiver", "source.bin",
            len(payload), hashlib.sha256(payload).hexdigest(), 1)
        store = TransferStore(str(root / "ledger.db"), TransferLimits(chunk_size_bytes=4096))
        files = TransferFilesystem(store, str(root / "stage"), str(root / "received"))
        store.create_offer(offer, 1)
        store.transition(offer.transfer_id, TransferState.ACCEPTED, 2)
        store.transition(offer.transfer_id, TransferState.TRANSFERRING, 3)
        return source, offer, store, files

    def test_real_loopback_listener_client_and_verified_publish(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            payload = b"network-data" * 700
            source, offer, store, files = self._prepared(root, payload)
            errors = []
            server = TransferTcpServer(store, files, local_peer_id="receiver",
                authenticate_peer=lambda conn, addr: authenticate_tcp_server(conn, "receiver", self.SECRET, authorize_peer=lambda peer: peer == "sender"),
                now_ms=lambda: 10, host="127.0.0.1", port=0,
                socket_timeout_seconds=2, on_error=errors.append).start()
            try:
                with connect_transfer_peer(*server.address,
                    lambda sock: authenticate_tcp_client(sock, "sender", "receiver", self.SECRET), timeout_seconds=2) as connection:
                    remaining = send_file_over_session(connection.sock, str(source), offer,
                        local_peer_id="sender", remote_peer_id=connection.peer_id,
                        session_secret=connection.session_secret, chunk_size_bytes=4096)
                    self.assertEqual((), remaining)
                self.assertEqual(TransferState.COMPLETED, store.get_transfer(offer.transfer_id).state)
                self.assertEqual(payload, next((root / "received").iterdir()).read_bytes())
            finally:
                server.shutdown()
            self.assertEqual([], errors)

    def test_listener_rejects_over_admission_limit(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            _, _, store, files = self._prepared(root, b"x")
            entered, release = threading.Event(), threading.Event()
            def authenticate(conn, addr):
                entered.set()
                release.wait(2)
                return "sender", self.SECRET
            server = TransferTcpServer(store, files, local_peer_id="receiver",
                authenticate_peer=authenticate, now_ms=lambda: 10, host="127.0.0.1",
                port=0, max_connections=1, socket_timeout_seconds=2).start()
            first = second = None
            try:
                first = socket.create_connection(server.address, timeout=1)
                self.assertTrue(entered.wait(1))
                second = socket.create_connection(server.address, timeout=1)
                second.settimeout(1)
                self.assertEqual(b"", second.recv(1))
            finally:
                release.set()
                if first: first.close()
                if second: second.close()
                server.shutdown()

    def test_client_closes_socket_when_authentication_fails(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            accepted = []
            def accept_once():
                conn, _ = listener.accept()
                accepted.append(conn)
            worker = threading.Thread(target=accept_once)
            worker.start()
            host, port = listener.getsockname()
            with self.assertRaises(ValueError):
                connect_transfer_peer(host, port,
                    lambda sock: (_ for _ in ()).throw(ValueError("bad peer")),
                    timeout_seconds=1)
            worker.join(timeout=1)
            self.assertEqual(1, len(accepted))
            accepted[0].settimeout(1)
            self.assertEqual(b"", accepted[0].recv(1))
            accepted[0].close()

    def test_idle_socket_timeout_releases_worker(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            _, _, store, files = self._prepared(root, b"x")
            errors, closed = [], threading.Event()
            def on_error(exc):
                errors.append(exc)
                closed.set()
            server = TransferTcpServer(store, files, local_peer_id="receiver",
                authenticate_peer=lambda conn, addr: ("sender", self.SECRET),
                now_ms=lambda: 10, host="127.0.0.1", port=0,
                socket_timeout_seconds=0.15, on_error=on_error).start()
            client = None
            try:
                client = socket.create_connection(server.address, timeout=1)
                self.assertTrue(closed.wait(1))
                self.assertTrue(any(isinstance(exc, socket.timeout) for exc in errors))
            finally:
                if client: client.close()
                server.shutdown()
            self.assertEqual(0, len(server._active))
            with self.assertRaises(RuntimeError):
                server.start()

if __name__ == "__main__":
    unittest.main()
