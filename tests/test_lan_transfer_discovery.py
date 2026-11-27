from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/devicefinder"


class LanTransferDiscoveryIsolationTests(unittest.TestCase):
    def read(self, name):
        return (JAVA / name).read_text(encoding="utf-8")

    def test_directory_is_owned_by_file_transfer_and_never_authenticates(self):
        source = self.read("LanTransferPeerDirectory.java")
        self.assertIn('PREFS = "lan_transfer_peer_directory_v1"', source)
        self.assertNotIn("LanChat", source)
        self.assertNotIn("LanTransferAuthenticator", source)
        self.assertIn("isAuthenticated() { return false; }", source)
        self.assertIn("ENDPOINT_TTL_MS = 120_000L", source)

    def test_chat_discovery_is_optional_hint_only(self):
        source = self.read("LanTransferPeerDirectory.java")
        self.assertIn("recordChatDiscoveryHint", source)
        self.assertIn("recordFileDiscoveryCandidate", source)
        self.assertIn("isAuthenticated() { return false; }", source)

    def test_discovery_uses_dedicated_udp_port_and_never_chat_socket(self):
        source = self.read("LanTransferDiscovery.java")
        self.assertIn("UDP_PORT = 38472", source)
        self.assertNotIn("LanChat", source)
        self.assertNotIn("8888", source)
        self.assertIn("recordFileDiscoveryCandidate", source)
        self.assertIn("unauthenticated LAN candidate discovery", source)

    def test_endpoint_lookup_is_current_network_scoped(self):
        source = self.read("LanTransferPeerDirectory.java")
        self.assertIn("activateNetwork", source)
        self.assertIn("findCurrent", source)
        self.assertIn("loadCurrent", source)
        self.assertIn("item.networkFingerprint.equals(network)", source)
        self.assertIn("now-item.lastSeenAtMs<=ENDPOINT_TTL_MS", source)

    def test_chat_path_listener_binding_is_exact_and_separate_from_ephemeral_sender_binding(self):
        source = self.read("LanSocketBinding.java")
        self.assertIn("bindDatagramListenerSocket", source)
        self.assertIn("new java.net.InetSocketAddress(path.localAddress, port)", source)
        self.assertIn("requireAndroidNetwork(path)", source)
        self.assertIn("Listener socket must be open and unbound", source)
        self.assertIn("socket.getLocalPort() != port", source)

    def test_pairing_control_is_independent_per_path_and_uses_all_fresh_endpoints(self):
        service = self.read("TrackingService.java")
        directory = self.read("LanTransferPeerDirectory.java")
        self.assertIn("lanTransferPairingControlHosts", service)
        self.assertIn("createLanTransferPairingControlHost()", service)
        self.assertIn("findLanTransferPairingHost(sessionId, peerId)", service)
        self.assertIn("closeLanTransferPairingInitiatorsForMissingPaths(desired)", service)
        self.assertIn("LanTransferPeerDirectory.findCurrentAll", service)
        self.assertIn("pathsByFingerprint.get(candidate.networkFingerprint)", service)
        self.assertIn("public static List<Candidate> findCurrentAll", directory)
        self.assertIn("private void closeLanTransferPairingControlHostLocked()", service)
        self.assertNotIn("lanTransferPairingControlFingerprint", service)

    def test_file_sender_uses_all_fresh_endpoints_on_their_exact_active_paths(self):
        service = self.read("LanTransferSenderService.java")
        worker = self.read("LanTransferSenderWorker.java")
        self.assertIn("LanTransferPeerDirectory.findCurrentAll", service)
        self.assertIn("candidate.networkFingerprint", service)
        self.assertIn("currentLanNetworkPaths()", service)
        self.assertIn("LanTransferPathSet.select(wifi,localCandidates,excludedInterfaces)", service)
        self.assertIn("TRANSPORT_CELLULAR", service)
        self.assertIn("TRANSPORT_VPN", service)
        self.assertIn("currentLanNetworkPaths()", worker)
        self.assertIn("endpoint.networkFingerprint.equals(path.diagnosticFingerprint())", worker)
        self.assertIn("sendOfferAcrossCurrentPaths()", worker)
        self.assertIn("connectAndSendAcrossCurrentPaths()", worker)
        self.assertIn("bounded.size() < 4", worker)
        self.assertIn("No active endpoint/path pair", worker)

    def test_tracking_service_owns_discovery_lifecycle_and_feature_gate(self):
        service = self.read("TrackingService.java")
        self.assertIn("refreshLanTransferDiscovery(reason);", service)
        self.assertIn("private void refreshLanTransferDiscovery(String reason)", service)
        self.assertIn("LanTransferDiscovery discovery = new LanTransferDiscovery", service)
        self.assertIn("closeLanTransferDiscoveryLocked();", service)
        self.assertIn("LanTransferPeerDirectory.activatePath(this, fingerprint);", service)
        self.assertIn("currentLanTransferPathSet(cm)", service)
        self.assertIn("lanTransferDiscoveries", service)
        self.assertIn("lanTransferTcpListeners", service)
        self.assertIn("lanTransferPairingControlHosts", service)
        self.assertIn("createLanTransferPairingControlHost()", service)
        self.assertIn("findLanTransferPairingHost(sessionId, peerId)", service)
        self.assertIn("private void closeLanTransferResources()", service)


if __name__ == "__main__":
    unittest.main()
