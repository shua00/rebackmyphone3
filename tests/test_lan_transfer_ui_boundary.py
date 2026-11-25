from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/devicefinder"
RES = ROOT / "app/src/main/res"


class LanTransferUiBoundaryTests(unittest.TestCase):
    def test_chat_entry_is_only_a_navigation_and_feature_gate_boundary(self):
        chat = (JAVA / "ChatActivity.java").read_text(encoding="utf-8")
        layout = (RES / "layout/activity_chat.xml").read_text(encoding="utf-8")
        self.assertIn("LanTransferActivity", chat)
        self.assertIn("LanTransferFeature.isEnabled(this)", chat)
        self.assertNotIn("LanTransferStore", chat)
        self.assertNotIn("LanTransferTcpClient", chat)
        self.assertNotIn("LanTransferTcpListener", chat)
        self.assertNotIn("lanTransferOfferList", chat)
        self.assertIn('android:id="@+id/btnTransfer"', layout)
        self.assertIn('android:visibility="gone"', layout)

    def test_file_transfer_is_default_off_and_backend_fail_closed(self):
        gate = (JAVA / "LanTransferFeature.java").read_text(encoding="utf-8")
        service = (JAVA / "TrackingService.java").read_text(encoding="utf-8")
        sender = (JAVA / "LanTransferSenderService.java").read_text(encoding="utf-8")
        activity = (JAVA / "LanTransferActivity.java").read_text(encoding="utf-8")
        self.assertIn('.getBoolean(KEY_ENABLED, false)', gate)
        self.assertIn('putBoolean(KEY_ENABLED, enabled).commit()', gate)
        self.assertIn('if (!LanTransferFeature.isEnabled(this))', service)
        self.assertIn('file-transfer feature disabled', service)
        self.assertIn('if (!LanTransferFeature.isEnabled(context))', sender)
        self.assertIn('if (!LanTransferFeature.isEnabled(this))', activity)
        self.assertIn('ACTION_LAN_TRANSFER_FEATURE_TOGGLED', service)
        self.assertIn('onFeatureToggled(app, enabled)', gate)
        self.assertIn('ACTION_FEATURE_TOGGLED', sender)
        self.assertIn('worker.pause()', sender)
        self.assertIn('retry && LanTransferFeature.isEnabled(this)', sender)
        self.assertIn('if (!hasPendingJob(context)) return;', sender)


    def test_pairing_adds_a_peer_without_replacing_existing_trust(self):
        activity = (JAVA / "LanTransferActivity.java").read_text(encoding="utf-8")
        self.assertIn("LanTransferCredentials.Value existing = LanTransferCredentials.load(this);", activity)
        self.assertIn("LanTransferCredentials.mergeTrustedPeerIds(", activity)
        self.assertIn("LanTransferCredentials.provision(this, value, trustedPeerIds);", activity)
        credentials = (JAVA / "LanTransferCredentials.java").read_text(encoding="utf-8")
        self.assertIn("merged.addAll(existingPeerIds);", credentials)
        self.assertIn("Existing trusted peers cannot be rebound to a different group secret", credentials)
        self.assertNotIn("Collections.singleton(peerId)", activity)
        self.assertIn('setNeutralButton("清除全部配对"', activity)
        self.assertIn("LanTransferSenderService.hasPendingJob(this)", activity)
        self.assertIn("LanTransferCredentials.clear(this);", activity)
        self.assertIn("不会删除已收发文件", activity)

    def test_transfer_controls_live_in_dedicated_internal_activity(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        activity = (JAVA / "LanTransferActivity.java").read_text(encoding="utf-8")
        layout = (RES / "layout/activity_lan_transfer.xml").read_text(encoding="utf-8")
        self.assertIn('android:name=".LanTransferActivity"', manifest)
        self.assertIn('android:exported="false"', manifest)
        self.assertIn("LanTransferCredentials.provision", activity)
        self.assertIn("ACTION_LAN_TRANSFER_ACCEPT", activity)
        self.assertIn("ACTION_LAN_TRANSFER_REJECT", activity)
        self.assertIn("lanTransferOfferList", layout)
        self.assertIn("loadRecentTransfers", activity)
        self.assertIn("REQUEST_SAVE_RECEIVED_FILE", activity)
        self.assertIn("ACTION_CREATE_DOCUMENT", activity)
        self.assertIn("copyCompletedFileToUserLocation", activity)
        self.assertIn("publishedPath", activity)
        self.assertIn("MessageDigest.getInstance(\"SHA-256\")", activity)
        self.assertIn("transfer.offer.sha256.equals(actualHash.toString())", activity)
        transfer_store = (JAVA / "LanTransferStore.java").read_text(encoding="utf-8")
        self.assertIn("loadRecentTransfers", transfer_store)
        self.assertIn("loadIncomingOffers", transfer_store)
        self.assertIn("published_path", transfer_store)

    def test_foreground_notification_provides_independent_entry(self):
        service = (JAVA / "TrackingService.java").read_text(encoding="utf-8")
        self.assertIn("new Intent(this, LanTransferActivity.class)", service)
        self.assertIn('if (LanTransferFeature.isEnabled(this))', service)
        self.assertIn('addAction(android.R.drawable.ic_menu_upload, "文件传输"', service)


    def test_saf_snapshot_and_sender_service_are_isolated_and_durable(self):
        activity = (JAVA / "LanTransferActivity.java").read_text(encoding="utf-8")
        snapshot = (JAVA / "LanTransferSnapshot.java").read_text(encoding="utf-8")
        service = (JAVA / "LanTransferSenderService.java").read_text(encoding="utf-8")
        worker = (JAVA / "LanTransferSenderWorker.java").read_text(encoding="utf-8")
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        layout = (RES / "layout/activity_lan_transfer.xml").read_text(encoding="utf-8")
        self.assertIn("ACTION_OPEN_DOCUMENT", activity)
        self.assertIn("LanTransferSnapshot.create", activity)
        self.assertIn("trustedPeerIds.contains(peerId)", activity)
        self.assertIn("getFilesDir(),\"lan-transfer-outbox\"", snapshot)
        self.assertIn("SHA-256", snapshot)
        self.assertIn("MAX_FILE_BYTES", snapshot)
        self.assertIn("LanTransferSenderService.enqueue", activity)
        self.assertIn("LanTransferSenderService.resumeIfPending(this)", activity)
        self.assertIn("startForeground", service)
        self.assertIn("cancelPersistedLocalJob", service)
        self.assertIn("worker.cancel()", service)
        self.assertIn("worker.pause()", service)
        self.assertIn("pauseRequested", worker)
        self.assertIn("notifyPausedAtBoundary()", worker)
        self.assertIn("if (cancelled.get()) { cancelLedger(); return; }", worker)
        self.assertIn('notifyState("paused")', worker)
        self.assertIn("resumeIfPending", service)
        self.assertIn("cancelLocal", activity)
        self.assertIn("lanTransferCancelSend", layout)
        boot = (JAVA / "BootReceiver.java").read_text(encoding="utf-8")
        self.assertIn("LanTransferSenderService.resumeIfPending", boot)
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
        self.assertIn('"paused".equals(b)', store)
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
        self.assertIn('"expired".equals(remoteState) || "failed".equals(remoteState)', worker)
        self.assertIn("Only the sender may finalize its outgoing task", store)
        self.assertIn('new LanTransferFrameCodec.Frame("missing"', client)


if __name__ == "__main__":
    unittest.main()
