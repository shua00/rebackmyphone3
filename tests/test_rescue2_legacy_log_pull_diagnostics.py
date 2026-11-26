"""Regression tests for the preserved Rescue2 legacy multi-device log pull path."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "rescue2.py"


class Rescue2LegacyLogPullDiagnosticsTests(unittest.TestCase):
    def test_legacy_pull_entrypoint_and_protocol_remain_available(self):
        source = SOURCE.read_text(encoding="utf-8")
        body = source.split("def pull_logs_multi(target_ips, temp_url, clear_after):", 1)[1]
        body = body.split("\n# ===== ADB 核心模块", 1)[0]
        self.assertIn("FIND_ME_UPLOAD_LOG:{RESCUE_SECRET}:{temp_url}", body)
        self.assertIn('send_udp(cmd, ip, quiet=True, no_retry=True)', body)
        self.assertIn("deadline = time.time() + 30", body)
        self.assertIn("info.get(\"last_log\", 0) >= send_time", body)
        self.assertIn("missing = [ip for ip in target_ips if ip not in received_ips]", body)

    def test_legacy_log_pull_emits_bounded_summary_events(self):
        source = SOURCE.read_text(encoding="utf-8")
        body = source.split("def pull_logs_multi(target_ips, temp_url, clear_after):", 1)[1]
        body = body.split("\n# ===== ADB 核心模块", 1)[0]
        for event in ("legacy_log_pull_rejected", "legacy_log_pull_started",
                      "legacy_log_pull_dispatched", "legacy_log_pull_completed"):
            self.assertIn(event, body)
        self.assertIn("target_count=len(target_ips)", body)
        self.assertIn("received_count=len(received_ips)", body)
        self.assertIn("missing_count=len(missing)", body)
        self.assertIn("duration_ms=duration_ms", body)

    def test_diagnostic_events_do_not_log_device_addresses_or_log_contents(self):
        source = SOURCE.read_text(encoding="utf-8")
        body = source.split("def pull_logs_multi(target_ips, temp_url, clear_after):", 1)[1]
        body = body.split("\n# ===== ADB 核心模块", 1)[0]
        self.assertNotIn("_diag_event(\"legacy_log_pull", body.replace(
            "target_count=len(target_ips)", "").replace(
            "dispatched_count=dispatched", "").replace(
            "received_count=len(received_ips)", "").replace(
            "missing_count=len(missing)", "").replace(
            "duration_ms=duration_ms", "").replace(
            "clear_after=bool(clear_after)", "").replace(
            "timeout_seconds=30", "").replace(
            'reason="invalid_or_broadcast_target"', "").replace(
            'outcome="complete" if not missing else ("partial" if received_ips else "timeout")', ""))


if __(!__name__) == "__main__":
    unittest.main()
