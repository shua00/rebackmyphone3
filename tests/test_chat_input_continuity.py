from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/devicefinder/ChatActivity.java"
LAYOUT = ROOT / "app/src/main/res/layout/activity_chat.xml"


class ChatInputContinuityTests(unittest.TestCase):
    def test_send_restores_editor_focus_and_keeps_ime_visible(self):
        source = JAVA.read_text(encoding="utf-8")
        self.assertIn("import android.view.inputmethod.InputMethodManager;", source)
        self.assertIn("restoreChatInputFocus(keepKeyboardVisible);", source)
        self.assertIn("boolean keepKeyboardVisible = isComposerImeVisible();", source)
        self.assertIn("inputMsg.requestFocus();", source)
        self.assertIn("inputMsg.setSelection(inputMsg.length());", source)
        self.assertIn("imm.showSoftInput(inputMsg, InputMethodManager.SHOW_IMPLICIT);", source)
        self.assertIn("inputMsg.postDelayed(", source)
        self.assertIn("pendingComposerImeRestore = true;", source)
        self.assertIn("if (hasFocus && pendingComposerImeRestore)", source)
        self.assertIn("COMPOSER_IME_RESTORE_DELAY_MS", source)

    def test_send_preserves_only_the_keyboard_visibility_present_before_send(self):
        source = JAVA.read_text(encoding="utf-8")
        send = source.split("private void sendMessage()", 1)[1].split("private boolean isComposerImeVisible()", 1)[0]
        self.assertIn("boolean keepKeyboardVisible = isComposerImeVisible();", send)
        self.assertLess(send.index("isComposerImeVisible();"), send.index('inputMsg.setText("");'))
        self.assertIn("restoreChatInputFocus(keepKeyboardVisible);", send)
        self.assertNotIn("restoreChatInputFocus(true);", send)

    def test_keyboard_visibility_is_not_inferred_from_editor_focus(self):
        source = JAVA.read_text(encoding="utf-8")
        helper = source.split("private boolean isComposerImeVisible()", 1)[1].split("/**", 1)[0]
        self.assertIn("WindowInsets.Type.ime()", helper)
        self.assertIn("if (insets != null)", helper)
        self.assertIn("getWindowVisibleDisplayFrame", helper)
        self.assertIn("150f * density", helper)

    def test_button_and_ime_send_share_the_same_send_path(self):
        source = JAVA.read_text(encoding="utf-8")
        self.assertIn("btnSend.setOnClickListener(v -> sendMessage());", source)
        self.assertIn("sendMessage();", source)
        self.assertIn("EditorInfo.IME_ACTION_SEND", source)
        self.assertIn("restoreChatInputFocus(keepKeyboardVisible);", source)
        self.assertIn("isComposerImeVisible();", source)

    def test_lifecycle_focus_restore_does_not_force_open_keyboard(self):
        source = JAVA.read_text(encoding="utf-8")
        self.assertIn("restoreChatInputFocus(false);", source)
        self.assertIn("if (!keepKeyboardVisible) return;", source)
        self.assertIn("pendingComposerImeRestore = false;", source)

    def test_keyboard_resize_does_not_mark_history_reader_as_away_from_bottom(self):
        source = JAVA.read_text(encoding="utf-8")
        self.assertIn('android:windowSoftInputMode="adjustResize"', (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"))
        self.assertIn("lastScrollViewportHeight", source)
        self.assertIn("CHAT_BOTTOM_THRESHOLD_PX", source)
        self.assertIn("viewportHeight != lastScrollViewportHeight", source)
        self.assertIn("Do not mistake that geometry change for the user's scrolling away", source)

    def test_incoming_messages_preserve_history_position_and_offer_new_message_entry(self):
        source = JAVA.read_text(encoding="utf-8")
        refresh = source.split("private void refreshHistory(boolean isNewMessage)", 1)[1].split("@Override", 1)[0]
        self.assertIn("if (!isNewMessage || isAtBottom)", refresh)
        self.assertIn("tvNewMsg.setVisibility(View.VISIBLE);", refresh)
        resume = source.split("protected void onResume()", 1)[1].split("public void onWindowFocusChanged", 1)[0]
        self.assertIn("refreshHistory(true);", resume)
        self.assertNotIn("refreshHistory(false);", resume)

    def test_sending_from_history_returns_to_own_latest_message(self):
        source = JAVA.read_text(encoding="utf-8")
        send = source.split("private void sendMessage()", 1)[1].split("private void restoreChatInputFocus", 1)[0]
        self.assertIn("refreshHistory(false);", send)

    def test_send_button_does_not_steal_editor_focus(self):
        layout = LAYOUT.read_text(encoding="utf-8")
        button = layout.split('android:id="@+id/btnSend"', 1)[1].split("/>", 1)[0]
        self.assertIn('android:focusable="false"', button)
        self.assertIn('android:focusableInTouchMode="false"', button)


if __name__ == "__main__":
    unittest.main()
