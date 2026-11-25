from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/devicefinder"
RES = ROOT / "app/src/main/res"


class LanTransportIsolationTests(unittest.TestCase):
    def read(self, name):
        return (JAVA / name).read_text(encoding="utf-8")

    def test_chat_persistence_and_delivery_policy_have_no_file_transfer_dependency(self):
        for name in ("LanChatStore.java", "LanChatDeliveryState.java", "LanChatRetryPolicy.java"):
            source = self.read(name)
            self.assertNotIn("LanTransfer", source, name)
            self.assertNotIn("lan_transfer", source.lower(), name)

    def test_file_transfer_protocol_and_persistence_have_no_chat_delivery_dependency(self):
        for name in ("LanTransferStore.java", "LanTransferTcpSession.java",
                     "LanTransferTcpClient.java", "LanTransferSenderWorker.java",
                     "LanTransferCredentials.java", "LanTransferFilesystem.java"):
            source = self.read(name)
            self.assertNotIn("LanChat", source, name)
            self.assertNotIn("lan_outbox", source.lower(), name)
            self.assertNotIn("chat_ack", source.lower(), name)

    def test_feature_toggle_is_confined_to_file_transfer_lifecycle(self):
        feature = self.read("LanTransferFeature.java")
        sender = self.read("LanTransferSenderService.java")
        tracking = self.read("TrackingService.java")
        chat_store = self.read("LanChatStore.java")
        self.assertIn("lan_file_transfer_enabled", feature)
        self.assertIn("LanTransferFeature.isEnabled(this)", sender)
        self.assertIn("stopLanTransferTcpListener", tracking)
        self.assertNotIn("LanTransferFeature", chat_store)
        self.assertNotIn("lan_file_transfer_enabled", tracking)

    def test_sender_cancel_transitions_accepted_task_to_terminal_state(self):
        worker = self.read("LanTransferSenderWorker.java")
        cancel = worker.split("private void cancelLedger()", 1)[1].split("private void sleepBackoff", 1)[0]
        self.assertIn('"accepted".equals(state)', cancel)
        self.assertIn('store.transition(offer.transferId, "cancelled"', cancel)

    def test_chat_composer_keyboard_behavior_is_not_changed_by_file_entry(self):
        chat = self.read("ChatActivity.java")
        self.assertIn("boolean keepKeyboardVisible = isComposerImeVisible();", chat)
        self.assertIn("restoreChatInputFocus(keepKeyboardVisible);", chat)
        self.assertIn("if (!keepKeyboardVisible) return;", chat)
        self.assertIn("restoreChatInputFocus(false);", chat)
        self.assertIn('android:id="@+id/btnTransfer"', (RES / "layout/activity_chat.xml").read_text(encoding="utf-8"))

    def test_notification_and_boot_restore_are_gated_or_durable(self):
        sender = self.read("LanTransferSenderService.java")
        boot = self.read("BootReceiver.java")
        self.assertIn("if(!LanTransferFeature.isEnabled(context) || !hasPendingJob(context)) return;", sender)
        self.assertIn("LanTransferSenderService.resumeIfPending(context)", boot)
        self.assertIn("if (!LanTransferFeature.isEnabled(this))", sender)
        self.assertIn("worker.pause()", sender)


if __name__ == "__main__":
    unittest.main()
