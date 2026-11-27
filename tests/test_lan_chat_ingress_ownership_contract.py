"""Static regression contract for multi-listener ACK/peer ingress ownership.

These checks guard source-level ownership handoff. Android unit tests and real-device
STA/AP delivery tests remain separate acceptance layers.
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/example/devicefinder/TrackingService.java"


class LanChatIngressOwnershipContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SERVICE.read_text(encoding="utf-8")

    def _method(self, signature, next_signature):
        start = self.source.index(signature)
        end = self.source.index(next_signature, start)
        return self.source[start:end]

    def test_secondary_receive_loop_passes_immutable_path_and_generation(self):
        method = self._method(
            "private void startSecondaryLanChatListener(",
            "private boolean isSecondaryPathStillPlanned(",
        )
        self.assertIn(
            "handleLanRealtimePacket(socket, packet, receivedAt, fingerprint, generation)",
            method,
        )
        self.assertIn("isCurrentSecondaryLanChatListener(instance, socket, lease)", method)

    def test_ack_ingress_lease_must_match_exact_socket_path_and_generation(self):
        method = self._method(
            "private boolean handleAuthenticatedLanPacket(",
            "private boolean isCurrentLanChatIngress(",
        )
        self.assertIn("lanChatPathRegistry.getListener(listenerPathFingerprint)", method)
        self.assertIn("ingressListener.socket == socket", method)
        self.assertIn("ingressListener.generation == listenerGeneration", method)
        self.assertIn("ingressListener.pathFingerprint.equals(listenerPathFingerprint)", method)
        self.assertIn("ingressListener.isUsable()", method)
        executable = "\n".join(
            line for line in method.splitlines()
            if not line.lstrip().startswith("//")
        )\n        self.assertNotIn("rescueSocketPathFingerprint", executable)

        self.assertIn("if (ackIngressPath == null || ackIngressGeneration < 0L) return true;", method)
        self.assertIn("isCurrentLanChatIngress(verifiedPathFingerprint, socket,", method)
        self.assertIn("verifiedListenerGeneration)) return;", method)

    def test_async_chat_and_fragment_ack_keep_ingress_socket_and_epoch(self):
        method = self._method(
            "private boolean handleAuthenticatedLanPacket(",
            "private boolean isCurrentLanChatIngress(",
        )
        self.assertIn(
            "processAuthenticatedLanChatAck(\n"
            "                            messageId, senderId, sourceAddress, sourcePort, store,\n"
            "                            ackIngressPath, socket, ackIngressGeneration,",
            method,
        )
        self.assertIn(
            "processAuthenticatedLanChatFragmentAck(\n"
            "                            messageId, senderId, sourceAddress, sourcePort, fragmentIndex,\n"
            "                            store, ackIngressPath, socket, ackIngressGeneration,",
            method,
        )

    def test_ack_commit_requires_current_path_and_peer_route_or_single_path_legacy(self):
        ack = self._method(
            "private void processAuthenticatedLanChatAck(",
            "private boolean sendAuthenticatedChatAck(",
        )
        self.assertIn(
            "isCurrentLanChatIngress(ingressPath, ingressSocket, ingressGeneration)", ack
        )
        self.assertIn(
            "findCurrentPeerRouteBinding(senderId, ingressPath,\n"
            "                        ingressSocket, ingressGeneration,",
            ack,
        )
        self.assertIn("lanChatPathRegistry.listenerCount() == 1", ack)
        self.assertIn("allowLegacyEndpointFallback", ack)

        fragment = self._method(
            "private void processAuthenticatedLanChatFragmentAck(",
            "private void processAuthenticatedLanChatAck(",
        )
        self.assertIn(
            "isCurrentLanChatIngress(ingressPath, ingressSocket, ingressGeneration)",
            fragment,
        )
        self.assertIn("findCurrentPeerRouteBinding(senderId, ingressPath,", fragment)
        self.assertIn("lanChatPathRegistry.listenerCount() == 1", fragment)

    def test_inbound_chat_ack_is_sent_on_the_socket_that_received_the_message(self):
        method = self._method(
            "private boolean handleAuthenticatedLanPacket(",
            "private boolean isCurrentLanChatIngress(",
        )
        self.assertIn(
            "sendAuthenticatedChatAck(\n"
            "                                        socket, ackAddress, ackPort, senderId, messageId)",
            method,
        )
        self.assertIn(
            "processAuthenticatedLanChatFragment(\n"
            "                            socket, ackAddress, ackPort, senderId, messageId, targetId,",
            method,
        )


if __name__ == "__main__":
    unittest.main()
