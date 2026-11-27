"""Static contract for path-scoped secondary LAN chat ingress.

The contract guards architecture boundaries; it cannot prove OEM AP bindability,
broadcast delivery, NAT behavior, or end-to-end multi-device reachability.
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/example/devicefinder/TrackingService.java"


class LanSecondaryListenerContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SERVICE.read_text(encoding="utf-8")

    def _method(self, signature, next_signature):
        start = self.source.index(signature)
        end = self.source.index(next_signature, start)
        return self.source[start:end]

    def test_only_validated_local_interface_candidates_become_secondary(self):
        reconcile = self._method(
            "private void reconcileLanChatSecondaryListeners(",
            "private void stopSecondaryLanChatListener(",
        )
        self.assertIn("LanNetworkPath.Kind.LOCAL_INTERFACE_CANDIDATE", reconcile)
        self.assertIn("instance.generation != generation", reconcile)
        self.assertIn("startSecondaryLanChatListener(path, generation)", reconcile)

    def test_secondary_listener_binds_exact_address_and_uses_path_snapshot(self):
        start = self._method(
            "private void startSecondaryLanChatListener(",
            "private boolean isSecondaryPathStillPlanned(",
        )
        self.assertIn("LanSocketBinding.bindDatagramListenerSocket(path, socket, 8888)", start)
        self.assertIn("lanChatListenerRuntime.register(fingerprint, socket, generation)", start)
        self.assertIn("handleLanRealtimePacket(socket, packet, receivedAt, fingerprint, generation)", start)
        self.assertIn("instance.generation == lanSocketGeneration.get()", start)
        self.assertNotIn("new InetSocketAddress(8888)", start)
        self.assertNotIn("socket.bind(new InetSocketAddress(8888))", start)
        self.assertNotIn("new String(packet.getData()", start)
        self.assertNotIn('message.startsWith("FIND_ME_', start)

    def test_secondary_hello_uses_candidate_broadcast_and_bounded_nonce(self):
        hello = self._method(
            "private void sendSecondaryPathPeerHello(",
            "private void refreshWifiLanTransport(",
        )
        self.assertIn("path.broadcastAddress", hello)
        self.assertIn("pendingPeerChallenges.put(nonce, latch)", hello)
        self.assertIn("LAN_PEER_CHALLENGE_TIMEOUT_MS", hello)
        self.assertIn("SECONDARY_PEER_HELLO_", hello)

    def test_path_reconciliation_and_teardown_retire_secondary_owners(self):
        self.assertIn("reconcileLanChatSecondaryListeners(plan)", self.source)
        self.assertIn("reconcileLanChatSecondaryListeners(null)", self.source)
        self.assertIn("stopSecondaryLanChatListener(instance)", self.source)
        self.assertIn("lanChatListenerRuntime.removeInstance(instance)", self.source)
        self.assertIn("instance.requestStop()", self.source)
        self.assertIn("socket.close()", self.source)
        self.assertIn("worker.interrupt()", self.source)


if __name__ == "__main__":
    unittest.main()
