import unittest

from diagnostic_task_protocol import (
    ALLOWED_PROBES, DiagnosticTaskEnvelope, MAX_TARGETS, MAX_TASK_TTL_MS,
)


class DiagnosticTaskProtocolTests(unittest.TestCase):
    def task(self, **changes):
        values = dict(
            task_id="task-01", admin_key_fingerprint="a" * 64,
            target_participant_ids=("phone-a", "phone-b"),
            probe_ids=("android.runtime", "lan.udp_bind"),
            created_at_epoch_ms=1_800_000_000_000,
            expires_at_epoch_ms=1_800_000_600_000,
            nonce="A" * 22,
        )
        values.update(changes)
        return DiagnosticTaskEnvelope(**values)

    def test_python_android_schema_round_trip_and_stable_field_names(self):
        task = self.task()
        decoded = DiagnosticTaskEnvelope.from_json(task.to_json())
        self.assertEqual(task, decoded)
        self.assertIn('"targetParticipantIds"', task.to_json())
        self.assertIn('"adminKeyFingerprint"', task.to_json())

    def test_rejects_non_allowlisted_probe_and_shell_like_action(self):
        for probe in ("shell.exec", "adb.shell", "FIND_ME_CLEAR_HISTORY"):
            with self.subTest(probe=probe), self.assertRaises(ValueError):
                self.task(probe_ids=(probe,))

    def test_rejects_empty_duplicate_and_oversized_targets(self):
        for targets in ((), ("phone-a", "phone-a"), tuple(f"p{i}" for i in range(MAX_TARGETS + 1))):
            with self.subTest(targets=len(targets)), self.assertRaises(ValueError):
                self.task(target_participant_ids=targets)

    def test_rejects_invalid_fingerprint_nonce_and_lifetime(self):
        with self.assertRaises(ValueError):
            self.task(admin_key_fingerprint="not-a-fingerprint")
        with self.assertRaises(ValueError):
            self.task(nonce="short")
        with self.assertRaises(ValueError):
            self.task(expires_at_epoch_ms=1_800_000_000_999)
        with self.assertRaises(ValueError):
            self.task(expires_at_epoch_ms=1_800_000_000_000 + MAX_TASK_TTL_MS + 1)

    def test_rejects_unknown_or_missing_fields_and_boolean_timestamps(self):
        encoded = self.task().to_json()
        with self.assertRaises(ValueError):
            DiagnosticTaskEnvelope.from_json(encoded.replace('"nonce":', '"extra":1,"nonce":'))
        with self.assertRaises(ValueError):
            self.task(created_at_epoch_ms=True)

    def test_task_expiry_window_is_half_open(self):
        task = self.task()
        self.assertFalse(task.is_valid_at(task.created_at_epoch_ms - 1))
        self.assertTrue(task.is_valid_at(task.created_at_epoch_ms))
        self.assertFalse(task.is_valid_at(task.expires_at_epoch_ms))


if __name__ == "__main__":
    unittest.main()
