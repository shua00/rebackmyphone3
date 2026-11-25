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
        self.assertIn("Unauthenticated LAN candidate discovery", source)

    def test_endpoint_lookup_is_current_network_scoped(self):
        source = self.read("LanTransferPeerDirectory.java")
        self.assertIn("activateNetwork", source)
        self.assertIn("findCurrent", source)
        self.assertIn("loadCurrent", source)
        self.assertIn("item.networkFingerprint.equals(network)", source)
        self.assertIn("now-item.lastSeenAtMs<=ENDPOINT_TTL_MS", source)

    def test_tracking_service_owns_discovery_lifecycle_and_feature_gate(self):
        service = self.read("TrackingService.java")
        self.assertIn("refreshLanTransferDiscovery(reason);", service)
        self.assertIn("private void refreshLanTransferDiscovery(String reason)", service)
        self.assertIn("LanTransferDiscovery discovery = new LanTransferDiscovery", service)
        self.assertIn("closeLanTransferDiscoveryLocked();", service)
        self.assertIn("LanTransferPeerDirectory.activateNetwork(this, fingerprint);", service)
        self.assertIn("private void closeLanTransferResources()", service)


if __name__ == "__main__":
    unittest.main()
