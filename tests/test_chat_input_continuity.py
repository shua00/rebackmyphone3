from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/devicefinder/ChatActivity.java"
LAYOUT = ROOT / "app/src/main/res/layout/activity_chat.xml"


class ChatInputContinuityTests(unittest.TestCase):
    def test_send_restores_editor_focus_and_keeps_ime_visible(self):
        source = JAVA.read_text(encoding="utf-8")
        self.assertIn("import android.view.inputmethod.InputMethodManager;", source)
        self.assertIn("restoreChatInputFocus(true);", source)
        self.assertIn("inputMsg.requestFocus();", source)
        self.assertIn("inputMsg.setSelection(inputMsg.length());", source)
        self.assertIn("imm.showSoftInput(inputMsg, InputMethodManager.SHOW_IMPLICIT);", source)
        self.assertIn("inputMsg.postDelayed(", source)
        self.assertIn("pendingComposerImeRestore = true;", source)
        self.assertIn("if (hasFocus && pendingComposerImeRestore)", source)
        self.assertIn("COMPOSER_IME_RESTORE_DELAY_MS", source)

    def test_button_and_ime_send_share_the_same_send_path(self):
        source = JAVA.read_text(encoding="utf-8")
        self.assertIn("btnSend.setOnClickListener(v -> sendMessage());", source)
        self.assertIn("sendMessage();", source)
        self.assertIn("EditorInfo.IME_ACTION_SEND", source)
        self.assertIn("restoreChatInputFocus(true);", source)

    def test_lifecycle_focus_restore_does_not_force_open_keyboard(self):
        source = JAVA.read_text(encoding="utf-8")
        self.assertIn("restoreChatInputFocus(false);", source)
        self.assertIn("if (!keepKeyboardVisible) return;", source)
        self.assertIn("pendingComposerImeRestore = false;", source)

    def test_send_button_does_not_steal_editor_focus(self):
        layout = LAYOUT.read_text(encoding="utf-8")
        button = layout.split('android:id="@+id/btnSend"', 1)[1].split("/>", 1)[0]
        self.assertIn('android:focusable="false"', button)
        self.assertIn('android:focusableInTouchMode="false"', button)


if __name__ == "__main__":
    unittest.main()
