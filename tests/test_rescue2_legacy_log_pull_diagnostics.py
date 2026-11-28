"""Regression tests for the preserved Rescue2 legacy multi-device log pull path."""
import ast
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "rescue2.py"


class Rescue2LegacyLogPullCompatibilityTests(unittest.TestCase):
    @staticmethod
    def _pull_logs_multi_source():
        source = SOURCE.read_text(encoding="utf-8")
        tree = ast.parse(source, filename=str(SOURCE))
        function = next(
            node for node in tree.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name == "pull_logs_multi"
        )
        return source, ast.get_source_segment(source, function)

    def test_function_source_is_bounded_to_pull_entrypoint(self):
        source, body = self._pull_logs_multi_source()
        self.assertIsNotNone(body)
        self.assertIn("def pull_logs_multi(", body)
        self.assertNotIn("def menu_e_adb_manager(", body)
        self.assertNotIn("# ===== ADB 核心模块", body)
        self.assertGreater(len(source), len(body))

    def test_legacy_pull_entrypoint_and_protocol_remain_available(self):
        _, body = self._pull_logs_multi_source()
        self.assertIn("FIND_ME_UPLOAD_LOG:{RESCUE_SECRET}:{temp_url}", body)
        self.assertIn("send_udp(cmd, ip, quiet=True, no_retry=True)", body)
        self.assertIn("deadline = time.time() + 30", body)
        self.assertIn('info.get("last_log", 0) >= send_time', body)
        self.assertIn("missing = [ip for ip in target_ips if ip not in received_ips]", body)

    def test_legacy_pull_path_is_not_coupled_to_diagnostic_logger(self):
        _, body = self._pull_logs_multi_source()
        self.assertNotIn("_diag_event(", body)
        self.assertNotIn("_diag_exception(", body)
        self.assertIn("if not target_ips or target_ips == [\"255.255.255.255\"]:", body)
        self.assertIn("for ip in target_ips:", body)
        self.assertIn("time.sleep(0.1)", body)
        self.assertIn("time.sleep(0.5)", body)
        self.assertIn("if len(received_ips) >= len(target_ips): break", body)
        self.assertIn("if missing: print", body)

    def test_optional_diagnostic_logger_filters_sensitive_field_names(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn('blocked = ("secret", "token", "password", "body", "content", "payload", "raw", "auth")', source)
        self.assertIn("def _diag_event(event, level=logging.INFO, **fields):", source)


if __name__ == "__main__":
    unittest.main()
