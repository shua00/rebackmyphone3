"""Regression tests for the preserved Rescue2 legacy multi-device log pull path."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "rescue2.py"


class Rescue2LegacyLogPullDiagnosticsTests(unittest.TestCase):
    def test_legacy_pull_entrypoint_and_protocol_remain_available(self):
        source = SOURCE.read_text(encoding="utf-8")
        body = source.split("def pull_logs_multi(target_ips, temp_url, clear_after):", 1)[1]
        body = body.split("\\n# ===== ADB 核心模块", 1)[0]
        self.assertIn("FIND_ME_UPLOAD_LOG:{RESCUE_SECRET}:{temp_url}", body)
        self.assertIn("send_udp(cmd, ip, quiet=True, no_retry=True)", body)
        self.assertIn("deadline = time.time() + 30", body)
        self.assertIn('info.get("last_log", 0) >= send_time', body)
        self.assertIn("missing = [ip for ip in target_ips if ip not in received_ips]", body)

    def test_legacy_log_pull_emits_bounded_summary_events(self):
        source = SOURCE.read_text(encoding="utf-8")
        body = source.split("def pull_logs_multi(target_ips, temp_url, clear_after):", 1)[1]
        body = body.split("\\n# ===== ADB 核心模块", 1)[0]
        for event in ("legacy_log_pull_rejected", "legacy_log_pull_started",
                      "legacy_log_pull_dispatched", "legacy_log_pull_completed"):
            self.assertIn(event, body)
        for field in ("target_count=len(target_ips)", "received_count=len(received_ips)",
                      "missing_count=len(missing)", "duration_ms=duration_ms"):
            self.assertIn(field, body)

    def test_diagnostic_logger_filters_sensitive_field_names(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn('blocked = ("secret", "token", "password", "body", "content", "payload", "raw", "auth")', source)
        self.assertIn("def _diag_event(event, level=logging.INFO, **fields):", source)


if __name__ == "__main__":
    unittest.main()
