from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/devicefinder"
RES = ROOT / "app/src/main/res"


class LanChatDeliveryUiTests(unittest.TestCase):
    def test_chat_ui_reads_the_existing_durable_outbox_ledger(self):
        activity = (JAVA / "ChatActivity.java").read_text(encoding="utf-8")
        store = (JAVA / "LanChatStore.java").read_text(encoding="utf-8")
        layout = (RES / "layout/activity_chat.xml").read_text(encoding="utf-8")

        self.assertIn("loadRecentDeliverySummaries(3)", activity)
        self.assertIn("deliveryStatusExecutor.execute", activity)
        self.assertIn("deliveryStatusRefreshQueued", activity)
        self.assertIn("tvDeliveryStatus", activity)
        self.assertIn("loadRecentDeliverySummaries(int limit)", store)
        self.assertIn("LEFT JOIN lan_outbox_targets", store)
        self.assertIn('android:id="@+id/tvDeliveryStatus"', layout)
        self.assertIn("managePendingOutboxMessages", activity)
        self.assertIn("确认取消", activity)
        self.assertIn("cancelPendingMessage", activity)
        self.assertIn("clearTerminalOutboxRecords", activity)
        self.assertIn("清理已结束记录", activity)
        self.assertIn("expireOutboxMessages", activity)

    def test_long_messages_are_validated_with_fragment_framing_before_outbox_admission(self):
        service = (JAVA / "TrackingService.java").read_text(encoding="utf-8")
        start = service.index("private void sendLocalUdpMessage(final String message) {")
        end = service.index("private void notifyLanChatSendRejected", start)
        admission = service[start:end]
        self.assertIn("buildAuthenticatedLanChatFragmentPackets", admission)
        self.assertNotIn("buildAuthenticatedLanChatPacket(", admission)

    def test_chat_composer_has_stable_height_and_compact_actions(self):
        layout = (RES / "layout/activity_chat.xml").read_text(encoding="utf-8")
        self.assertIn('android:layout_height="72dp"', layout)
        self.assertIn('android:layout_height="56dp"', layout)
        self.assertIn('android:maxLines="5"', layout)
        self.assertIn('android:layout_width="48dp"', layout)
        self.assertIn('android:layout_width="56dp"', layout)

    def test_chat_ui_distinguishes_delivery_states_without_read_receipts(self):
        activity = (JAVA / "ChatActivity.java").read_text(encoding="utf-8")
        for label in ("全部送达", "部分送达", "等待设备确认", "正在发现收件设备",
                      "重试已暂停，等待恢复", "未发现收件设备"):
            self.assertIn(label, activity)
        self.assertNotIn("已读", activity)
        self.assertIn("DELIVERY_STATUS_REFRESH_MS", activity)
        self.assertIn("A stale query must not cancel", activity)
        self.assertIn("Start watching for this message before refreshHistory", activity)

    def test_chat_exposes_opt_in_file_entry_without_embedding_transfer_transport(self):
        activity = (JAVA / "ChatActivity.java").read_text(encoding="utf-8")
        layout = (RES / "layout/activity_chat.xml").read_text(encoding="utf-8")
        self.assertIn("LanTransferActivity", activity)
        self.assertIn("LanTransferFeature", activity)
        for transport_symbol in ("LanTransferStore", "LanTransferTcpClient",
                                 "LanTransferTcpListener", "LanTransferCredentials"):
            self.assertNotIn(transport_symbol, activity)
        self.assertIn('android:id="@+id/btnTransfer"', layout)
        self.assertIn('android:id="@+id/btnChatSettings"', layout)


if __name__ == "__main__":
    unittest.main()
