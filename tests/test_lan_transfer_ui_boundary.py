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


    def test_saf_snapshot_and_sender_service_are_isolated_and_durable(self):
        activity = (JAVA / "LanTransferActivity.java").read_text(encoding="utf-8")
        snapshot = (JAVA / "LanTransferSnapshot.java").read_text(encoding="utf-8")
        service = (JAVA / "LanTransferSenderService.java").read_text(encoding="utf-8")
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        layout = (RES / "layout/activity_lan_transfer.xml").read_text(encoding="utf-8")
        self.assertIn("ACTION_OPEN_DOCUMENT", activity)
        self.assertIn("LanTransferSnapshot.create", activity)
        self.assertIn("trustedPeerIds.contains(peerId)", activity)
        self.assertIn("getFilesDir(),\"lan-transfer-outbox\"", snapshot)
        self.assertIn("SHA-256", snapshot)
        self.assertIn("MAX_FILE_BYTES", snapshot)
        self.assertIn("LanTransferSenderService.enqueue", activity)
        self.assertIn("startForeground", service)
        self.assertNotIn("isDestroyed()", service)
        self.assertIn("START_STICKY", service)
        self.assertIn("lan_transfer_sender_jobs", service)
        self.assertIn("postDelayed", service)
        self.assertIn("TRANSPORT_WIFI", service)
        self.assertIn('android:name=".LanTransferSenderService"', manifest)
        self.assertIn('android:foregroundServiceType="dataSync"', manifest)
        self.assertIn("lanTransferSendFile", layout)

    def test_sender_worker_is_bounded_resumable_and_wifi_only(self):
        worker = (JAVA / "LanTransferSenderWorker.java").read_text(encoding="utf-8")
        store = (JAVA / "LanTransferStore.java").read_text(encoding="utf-8")
        client = (JAVA / "LanTransferTcpClient.java").read_text(encoding="utf-8")
        self.assertIn("trustedPeerIds.contains(offer.targetPeerId)", worker)
        self.assertIn("currentWifiNetwork()", worker)
        self.assertIn("LanTransferTcpClient.sendOffer", worker)
        self.assertIn("LanTransferTcpClient.connectAndSend", worker)
        self.assertIn("DEFAULT_CHUNKS_PER_SESSION", worker)
        self.assertIn("offerWaitMs", worker)
        self.assertIn("maxSessions", worker)
        self.assertIn("store.transition(offer.transferId, \"paused\"", worker)
        self.assertIn("markSenderCompleted", worker)
        self.assertIn("failIfTransferring", worker)
        self.assertIn("failed", worker)
        self.assertIn("Only the sender may finalize its outgoing task", store)
        self.assertIn('new LanTransferFrameCodec.Frame("missing"', client)


if __name__ == "__main__":
    unittest.main()
