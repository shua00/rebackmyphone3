import unittest

from diagnostic_batch_protocol import DiagnosticBatchEnvelope, MAX_REPORT_JSON_CHARS


class DiagnosticBatchProtocolTests(unittest.TestCase):
    def make_envelope(self, report='{"featureId":"lan","cases":[]}'):
        return DiagnosticBatchEnvelope(
            batch_id="batch-1", event_id="event-1", run_id="run-1",
            participant_id="participant-a", sequence=3,
            created_at_epoch_ms=1800000000000, report_json=report,
        )

    def test_android_compatible_json_round_trip(self):
        original = self.make_envelope()
        self.assertEqual(original, DiagnosticBatchEnvelope.from_json(original.to_json()))

    def test_rejects_oversized_report(self):
        with self.assertRaises(ValueError):
            self.make_envelope('"' + ("x" * MAX_REPORT_JSON_CHARS) + '"')

    def test_rejects_invalid_token_and_non_json_report(self):
        with self.assertRaises(ValueError):
            DiagnosticBatchEnvelope("batch/1", "event-1", "run-1",
                                    "participant-a", 1, 1000, "{}")
        with self.assertRaises(ValueError):
            self.make_envelope("not-json")

    def test_rejects_boolean_sequence_and_unknown_schema(self):
        with self.assertRaises(ValueError):
            DiagnosticBatchEnvelope("batch-1", "event-1", "run-1",
                                    "participant-a", True, 1000, "{}")
        encoded = self.make_envelope().to_json().replace('"schemaVersion":1', '"schemaVersion":2')
        with self.assertRaises(ValueError):
            DiagnosticBatchEnvelope.from_json(encoded)


if __name__ == "__main__":
    unittest.main()
