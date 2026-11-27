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

    def test_only_validated_path_plan_entries_become_secondary(self):
        reconcile = self._method(
            "private void reconcileLanChatSecondaryListeners(",
            "private void stopSecondaryLanChatListener(",
        )
        self.assertIn("desired.put(path.diagnosticFingerprint(), path)", reconcile)
        self.assertIn("instance.generation != generation", reconcile)
        self.assertIn("LanChatListenerPathPlan.from(paths, preferredWifiNetwork)", self.source)
        self.assertIn("startSecondaryLanChatListener(path, generation)", reconcile)

    def test_secondary_listener_binds_exact_address_and_uses_path_snapshot(self):
        start = self._method(
            "private void startSecondaryLanChatListener(",
            "private boolean isSecondaryPathStillPlanned(",
        )
        self.assertIn("LanSocketBinding.bindDatagramListenerSocket(path, socket, 8888)", start)
        self.assertIn("lanChatListenerRuntime.register(fingerprint, socket, generation)", start)
        self.assertIn("handleLanRealtimePacket(socket, packet, receivedAt, fingerprint, generation)", start)
        self.assertIn("generation != lanSocketGeneration.get()", start)
        current = self._method(
            "private boolean isCurrentSecondaryLanChatListener(",
            "private void startSecondaryLanChatListener(",
        )
        self.assertIn("instance.generation == lanSocketGeneration.get()", current)
        self.assertNotIn("new InetSocketAddress(8888)", start)
        self.assertNotIn("socket.bind(new InetSocketAddress(8888))", start)
        self.assertNotIn("setSoTimeout", start)
        self.assertNotIn("new String(packet.getData()", start)
        self.assertNotIn('message.startsWith("FIND_ME_', start)

    def test_ap_only_primary_uses_exact_bind_and_rebinds_on_address_change(self):
        start = self._method(
            "private void startRescueListener()",
            "private void parseResponse(",
        )
        self.assertIn("primaryIsLocalCandidate", start)
        self.assertIn("LanSocketBinding.bindDatagramListenerSocket(", start)
        self.assertIn("plannedPrimaryPath, rescueSocket, 8888", start)
        self.assertIn("if (!rescueSocket.isBound() && !primaryIsLocalCandidate)", start)
        self.assertIn("needsLocalPrimaryListenerRebind(plan)", self.source)
        self.assertIn("requestRescueListenerRestart(\"local-primary-path-change:", self.source)

    def test_secondary_hello_uses_candidate_broadcast_and_bounded_nonce(self):
        hello = self._method(
            "private void sendSecondaryPathPeerHello(",
            "private void refreshWifiLanTransport(",
        )
        self.assertIn("path.broadcastAddress", hello)
        self.assertIn("pendingPeerChallenges.put(nonce, latch)", hello)
        self.assertIn("LAN_PEER_CHALLENGE_TIMEOUT_MS", hello)
        self.assertIn('"secondary-peer-discovery-timeout-" + nonce', hello)
        self.assertIn("SECONDARY_PEER_HELLO_", hello)

    def test_path_reconciliation_and_teardown_retire_secondary_owners(self):
        self.assertIn("reconcileLanChatSecondaryListeners(plan)", self.source)
        self.assertIn("reconcileLanChatSecondaryListeners(null)", self.source)
        self.assertIn("stopSecondaryLanChatListener(instance)", self.source)
        self.assertIn("lanChatListenerRuntime.removeInstance(instance)", self.source)
        self.assertIn("instance.requestStop()", self.source)
        self.assertIn("socket.close()", self.source)
        self.assertIn("worker.interrupt()", self.source)


    def test_secondary_to_primary_role_promotion_retires_old_owner_before_primary_claim(self):
        reconcile = self._method(
            "private void reconcileLanChatSecondaryListeners(",
            "private void stopSecondaryLanChatListener(",
        )
        self.assertLess(
            reconcile.index("stopSecondaryLanChatListener(instance)"),
            reconcile.index("startSecondaryLanChatListener(path, generation)"),
        )
        self.assertIn("!desired.containsKey(instance.pathFingerprint)", reconcile)

        primary = self._method(
            "private void startRescueListener()",
            "private void parseResponse(",
        )
        self.assertIn("lanChatListenerRuntime.installInstance(listenerInstance)", primary)
        self.assertIn("同一路径已有 receive-loop owner", primary)
        self.assertIn("listenerGeneration != lanSocketGeneration.get()", primary)

        stop = self._method(
            "private void stopSecondaryLanChatListener(",
            "private boolean isCurrentSecondaryLanChatListener(",
        )
        self.assertLess(
            stop.index("socket.close()"),
            stop.index("lanChatListenerRuntime.removeInstance(instance)"),
        )
        self.assertIn("secondaryListenerRetryAttempts.remove(instance.pathFingerprint)", stop)

    def test_service_destroy_closes_ingress_and_retires_secondary_start_races(self):
        destroy = self._method(
            "public void onDestroy()",
            "// ★ 补丁3：无痕极速复活",
        )
        self.assertLess(
            destroy.index("serviceDestroyed = true;"),
            destroy.index("closeLanChatIngressSocket();"),
        )

        close = self._method(
            "private void closeLanChatIngressSocket()",
            "private void closeLanChatStoreAfterExecutors(",
        )
        self.assertIn("lanChatListenerRuntime.instanceSnapshot()", close)
        self.assertIn("stopSecondaryLanChatListener(instance)", close)

        secondary = self._method(
            "private void startSecondaryLanChatListener(",
            "private boolean isSecondaryPathStillPlanned(",
        )
        self.assertIn("instance.attachThread(worker)", secondary)
        self.assertIn("if (serviceDestroyed || generation != lanSocketGeneration.get()", secondary)
        self.assertLess(
            secondary.index("if (serviceDestroyed || generation != lanSocketGeneration.get()"),
            secondary.index("worker.start()"),
        )
        self.assertIn("instance.isStopRequested()", secondary)

    def test_primary_listener_rechecks_destroy_state_before_thread_start_and_socket_bind(self):
        primary = self._method(
            "private void startRescueListener()",
            "private void releaseLanMulticastLock()",
        )
        self.assertIn("if (serviceDestroyed) return;", primary)
        self.assertIn("if (serviceDestroyed || Thread.currentThread().isInterrupted()) return;", primary)
        self.assertIn("if (serviceDestroyed) {", primary)
        self.assertIn("if (serviceDestroyed || listenerInstance.isStopRequested()", primary)
        self.assertLess(
            primary.index("if (serviceDestroyed) {", primary.index("listenerThread = new Thread")),
            primary.index("listenerThread.start()"),
        )


    def test_former_primary_path_is_reconciled_after_its_socket_and_owner_are_released(self):
        primary = self._method(
            "private void startRescueListener()",
            "private void releaseLanMulticastLock()",
        )
        finally_block = primary[primary.index("} finally {"):]
        close_index = finally_block.index("rescueSocket.close()")
        owner_index = finally_block.index("lanChatListenerRuntime.removeInstance(listenerInstance)")
        reconcile_index = finally_block.index(
            "reconcileLanChatSecondaryListeners(lanChatListenerPathPlan)"
        )
        self.assertLess(close_index, owner_index)
        self.assertLess(owner_index, reconcile_index)
        self.assertIn("if (!serviceDestroyed)", finally_block)
        self.assertIn("former primary path may now be a desired secondary path", finally_block)
        self.assertNotIn("postDelayed", finally_block[reconcile_index:reconcile_index + 300])


    def test_secondary_listener_unexpected_failure_uses_path_scoped_bounded_backoff(self):
        secondary = self._method(
            "private void startSecondaryLanChatListener(",
            "private boolean isSecondaryPathStillPlanned(",
        )
        self.assertIn("secondaryListenerRetryAttempts.remove(fingerprint)", secondary)
        self.assertIn("!instance.isStopRequested() && !serviceDestroyed", secondary)
        self.assertIn("generation == lanSocketGeneration.get()", secondary)
        self.assertIn("isSecondaryPathStillPlanned(fingerprint)", secondary)
        self.assertIn("attempts.incrementAndGet()", secondary)
        self.assertIn("Math.min(300_000L, 5_000L * Math.pow(3", secondary)
        self.assertIn('"secondary-listener-retry-" + fingerprint', secondary)
        self.assertIn("lanChatListenerRuntime.getInstance(fingerprint) == null", secondary)
        self.assertIn("startSecondaryLanChatListener(path, generation)", secondary)
        self.assertNotIn("postDelayed", secondary)

        source = self.source
        self.assertIn("stable single-listener fast path never allocates retry tasks", source)


if __name__ == "__main__":
    unittest.main()
