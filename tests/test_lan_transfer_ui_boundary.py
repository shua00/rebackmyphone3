from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/devicefinder"
RES = ROOT / "app/src/main/res"


class LanTransferUiBoundaryTests(unittest.TestCase):
    def test_chat_activity_remains_transfer_agnostic(self):
        chat = (JAVA / "ChatActivity.java").read_text(encoding="utf-8")
        layout = (RES / "layout/activity_chat.xml").read_text(encoding="utf-8")
        self.assertNotIn("LanTransfer", chat)
        self.assertNotIn("lanTransfer", chat)
        self.assertNotIn("btnTransfer", layout)
        self.assertNotIn("tvTransferStatus", layout)

    def test_transfer_controls_live_in_dedicated_internal_activity(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        activity = (JAVA / "LanTransferActivity.java").read_text(encoding="utf-8")
        layout = (RES / "layout/activity_lan_transfer.xml").read_text(encoding="utf-8")
        self.assertIn('android:name=".LanTransferActivity"', manifest)
        self.assertIn('android:exported="false"', manifest)
        self.assertIn("LanTransferCredentials.provision", activity)
        self.assertIn("loadIncomingOffers", activity)
        self.assertIn("ACTION_LAN_TRANSFER_ACCEPT", activity)
        self.assertIn("ACTION_LAN_TRANSFER_REJECT", activity)
        self.assertIn("lanTransferOfferList", layout)

    def test_foreground_notification_provides_independent_entry(self):
        service = (JAVA / "TrackingService.java").read_text(encoding="utf-8")
        self.assertIn("new Intent(this, LanTransferActivity.class)", service)
        self.assertIn('addAction(android.R.drawable.ic_menu_upload, "文件传输"', service)


if __name__ == "__main__":
    unittest.main()
