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
        )
        self.assertNotIn("rescueSocketPathFingerprint", executable)

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

    def test_peer_challenge_nonce_is_bound_to_egress_listener_epoch(self):
        self.assertIn("pendingPeerChallengeIngress", self.source)
        self.assertIn("registerPendingPeerChallengeIngress(nonce, socket)", self.source)
        self.assertEqual(
            self.source.count("pendingPeerChallenges.put("),
            self.source.count("registerPendingPeerChallengeIngress(nonce, socket)"),
        )
        self.assertIn("lease.socket == socket && lanChatListenerRuntime.isCurrent(lease)", self.source)
        self.assertIn("challengeIngress.matches(ackIngressPath, socket, ackIngressGeneration)", self.source)
        self.assertIn("pendingPeerChallengeIngress.clear()", self.source)
        clear = self._method(
            "private void clearPendingPeerChallenge(",
            "private void sendPeerHello(",
        )
        self.assertIn("pendingPeerChallengeIngress.remove(nonce)", clear)
        peer_ack = self._method(
            'if ("peer.ack".equals(type))',
            'if ("chat.ack".equals(type))',
        )
        self.assertLess(
            peer_ack.index("challengeIngress.matches(ackIngressPath, socket, ackIngressGeneration)"),
            peer_ack.index("store.upsertPeer("),
        )
        self.assertIn(
            "pendingPeerChallengeIngress.remove(nonce, challengeIngress)", peer_ack
        )

    def test_peer_wide_cache_cannot_suppress_path_scoped_discovery(self):
        helper = self._method(
            "private boolean hasFreshLanChatPeerRoute(",
            "private boolean isCurrentLanChatSendBinding(",
        )
        self.assertIn("LanAdaptiveRouteSelector.selectPrimaryBinding(", helper)
        self.assertIn("lanChatPathRegistry.isCurrentBinding(binding)", helper)

        hello = self._method(
            'if ("peer.hello".equals(type))',
            'if ("peer.ack".equals(type))',
        )
        self.assertIn("findCurrentPeerRouteBinding(", hello)
        self.assertIn("helloIngressPath, socket, helloIngressGeneration", hello)
        self.assertIn("lanChatPathRegistry.listenerCount() <= 1", hello)

        self.assertIn("hasFreshLanChatPeerRoute(target.peerId)", self.source)
        self.assertIn("hasFreshLanChatPeerRoute(challengePeerId)", self.source)
        self.assertNotIn("verifiedChatPeers.containsKey(target.peerId)", self.source)
        self.assertNotIn("verifiedChatPeers.containsKey(challengePeerId)", self.source)

    def test_peer_ack_route_admission_is_atomic_with_listener_epoch(self):
        registry = (
            ROOT / "app/src/main/java/com/example/devicefinder/LanChatPathRegistry.java"
        ).read_text(encoding="utf-8")
        self.assertIn(
            "DatagramSocket expectedSocket, long expectedGeneration", registry
        )
        self.assertIn("listener.socket != expectedSocket", registry)
        self.assertIn("listener.generation != expectedGeneration", registry)
        self.assertIn(
            "Ingress listener epoch changed before peer route admission", registry
        )

        peer_ack = self._method(
            'if ("peer.ack".equals(type))',
            'if ("chat.ack".equals(type))',
        )
        self.assertIn(
            "verifiedAt, verifiedAt + 120_000L,\n"
            "                                            socket, verifiedListenerGeneration)",
            peer_ack,
        )
        self.assertIn("verified route registry admission rejected:", peer_ack)
        self.assertIn("verifiedListenerGeneration)) return;", peer_ack)
        self.assertLess(
            peer_ack.index("verified route registry admission rejected:"),
            peer_ack.index("verifiedChatPeers.put(senderId,"),
        )

    def test_duplicate_chat_message_collects_per_ingress_ack_targets(self):
        source = self.source
        self.assertIn("pendingLanChatV2Receives.putIfAbsent(", source)
        self.assertIn("pendingReceive.ackTargets.add(ackTarget)", source)
        self.assertIn("pendingReceive.completed && pendingReceive.stored", source)
        self.assertIn("new java.util.ArrayList<>(pendingReceive.ackTargets)", source)
        self.assertIn("pendingLanChatV2Receives.remove(receiveKey, pendingReceive)", source)

        receive = self._method(
            'if ("chat.message".equals(type))',
            "private boolean sendLanChatReceiveAck(",
        )
        self.assertEqual(receive.count("persistIncomingLanChat(senderId, messageId, body"), 1)
        self.assertIn("sendLanChatReceiveAck(\n                            ackTarget, senderId, messageId)", receive)
        ack = self._method(
            "private boolean sendLanChatReceiveAck(",
            "private boolean isCurrentLanChatIngress(",
        )
        self.assertIn("isCurrentLanChatIngress(target.pathFingerprint, target.socket,", ack)
        self.assertIn("target.generation)) return false;", ack)
        self.assertIn("sendAuthenticatedChatAck(target.socket, target.address, target.port,", ack)

    def test_secondary_listener_releases_socket_before_owner_handoff(self):
        stop = self._method(
            "private void stopSecondaryLanChatListener(",
            "private boolean isCurrentSecondaryLanChatListener(",
        )
        self.assertLess(
            stop.index("socket.close()"),
            stop.index("lanChatListenerRuntime.removeInstance(instance)"),
        )
        receive = self._method(
            "private void startSecondaryLanChatListener(",
            "private boolean isSecondaryPathStillPlanned(",
        )
        self.assertLess(
            receive.index("socket.close()"),
            receive.index("lanChatListenerRuntime.removeInstance(instance)", receive.index("} finally {")),
        )
        self.assertIn("Secondary ingress is intentionally authenticated P2P only", receive)
        receive_code = "\n".join(
            line for line in receive.splitlines()
            if not line.lstrip().startswith("//")
        )
        self.assertNotIn("FIND_ME_", receive_code)
        self.assertNotIn("lanUdpCommandDeduplicator.shouldAccept(", receive_code)

        primary_ingress = self.source.index(
            "if (handleLanRealtimePacket(rescueSocket, packet,"
        )
        primary_legacy = self.source.index(
            'if (message.startsWith("FIND_ME_ALARM:")', primary_ingress
        )
        primary_dedup = self.source.index(
            "lanUdpCommandDeduplicator.shouldAccept(", primary_ingress
        )
        self.assertLess(primary_ingress, primary_legacy)
        self.assertLess(primary_legacy, primary_dedup)

    def test_inbound_chat_ack_is_sent_on_the_socket_that_received_the_message(self):
        method = self._method(
            "private boolean handleAuthenticatedLanPacket(",
            "private boolean isCurrentLanChatIngress(",
        )
        self.assertIn("new PendingLanChatAckTarget(", method)
        self.assertIn("packet.getAddress(), packet.getPort(),", method)
        ack_helper = self._method(
            "private boolean sendLanChatReceiveAck(",
            "private boolean isCurrentLanChatIngress(",
        )
        self.assertIn(
            "sendAuthenticatedChatAck(target.socket, target.address, target.port,", ack_helper
        )
        self.assertIn(
            "processAuthenticatedLanChatFragment(\n"
            "                            socket, ackAddress, ackPort, senderId, messageId, targetId,",
            method,
        )


if __name__ == "__main__":
    unittest.main()
