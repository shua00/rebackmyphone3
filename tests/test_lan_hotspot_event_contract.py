"""Static contract for event-driven hotspot topology awareness.

This locks down the low-overhead boundary only. It does not prove that an OEM
emits either broadcast, that a candidate is an AP, or that a second listener
can bind/reach peers; those require Android device validation.
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/example/devicefinder/TrackingService.java"


class LanHotspotEventContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SERVICE.read_text(encoding="utf-8")

    def test_startup_seeds_initial_plan_and_registers_event_observer(self):
        self.assertIn("registerLanHotspotStateReceiver();", self.source)
        self.assertIn('refreshLanChatListenerPathPlan(lanCm, "service startup")', self.source)

    def test_receiver_observes_platform_events_without_polling(self):
        start = self.source.index("private void registerLanHotspotStateReceiver()")
        end = self.source.index("private void registerNetworkCallback()", start)
        method = self.source[start:end]
        self.assertIn('"android.net.wifi.WIFI_AP_STATE_CHANGED"', method)
        self.assertIn('"android.net.conn.TETHER_STATE_CHANGED"', method)
        self.assertIn("Context.RECEIVER_NOT_EXPORTED", method)
        self.assertIn("registerReceiver(receiver, filter);", method)
        self.assertIn("refreshLanChatListenerPathPlan(cm,", method)
        self.assertNotIn("postDelayed", method)
        self.assertNotIn("new Thread(", method)
        self.assertNotIn("startRescueListener(", method)
        self.assertNotIn("new DatagramSocket", method)

    def test_registration_race_is_closed_and_receiver_is_unregistered(self):
        self.assertIn("if (serviceDestroyed) {", self.source)
        self.assertIn("unregisterReceiver(receiver)", self.source)
        self.assertIn("unregisterReceiver(lanHotspotStateReceiver)", self.source)


if __name__ == "__main__":
    unittest.main()
