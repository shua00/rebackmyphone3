from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "server2.py"
RESCUE2 = ROOT / "rescue2.py"


class Server2ActivePeerDiscoveryUiTests(unittest.TestCase):
    def test_talk_page_triggers_a_throttled_authenticated_lan_discovery_burst(self):
        source = SERVER.read_text(encoding="utf-8")
        self.assertIn("def request_active_peer_discovery()", source)
        self.assertIn("_ACTIVE_PEER_DISCOVERY_COOLDOWN_S = 5.0", source)
        self.assertIn("send_startup_peer_beacon(", source)
        self.assertIn('elif "POST /api/discovery/refresh" in header_text:', source)
        self.assertIn("request_data.get(\"token\") != CONTROL_TOKEN", source)
        self.assertIn("refreshLanPeerDiscovery(false);", source)
        self.assertIn("function refreshLanPeerDiscovery(showFeedback)", source)

    def test_chat_selector_makes_all_and_individual_targets_mutually_exclusive(self):
        source = SERVER.read_text(encoding="utf-8")
        self.assertIn("document.addEventListener('change'", source)
        self.assertIn("selectorTouched = {chat: false, companion: false}", source)
        self.assertIn("input.value === 'all' || other.value === 'all'", source)
        self.assertIn("function mediaTarget()", source)
        self.assertIn("请至少选择一个设备，或选择所有人群发", source)

    def test_rescue2_startup_discovery_uses_each_active_interface_broadcast(self):
        source = RESCUE2.read_text(encoding="utf-8")
        self.assertIn("from lan_p2p_network_paths import enumerate_ipv4_paths, directed_broadcast_addresses", source)
        self.assertIn("directed_broadcast_addresses(enumerate_ipv4_paths())", source)
        self.assertIn("subnet_broadcasts", source)
        self.assertIn('"255.255.255.255"', source)

    def test_batch_reports_inherit_current_app_version_metadata(self):
        source = SERVER.read_text(encoding="utf-8")
        self.assertIn('batch_build_version = batch_data.get("build_version")', source)
        self.assertIn('batch_protocol_version = batch_data.get("protocol_version")', source)
        self.assertIn('item["build_version"] = batch_build_version', source)
        self.assertIn('item["protocol_version"] = batch_protocol_version', source)
        self.assertIn('or prev_info.get("build_version") or "旧版"', source)
        self.assertIn('or prev_info.get("protocol_version") or 1', source)

    def test_chat_target_list_includes_fresh_authenticated_android_peers(self):
        source = SERVER.read_text(encoding="utf-8")
        self.assertIn('list_known_peer_snapshot(CHAT_DEDUP_DB, "server2", limit=500)', source)
        self.assertIn('peer.get("status") == "active"', source)
        self.assertIn('peer.get("peer_type") == "android"', source)
        self.assertIn('float(peer.get("lease_expires_at") or 0) > now', source)


if __name__ == "__main__":
    unittest.main()
