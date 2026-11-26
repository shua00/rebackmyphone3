import hashlib
import tempfile
import unittest
from pathlib import Path

from rescue2_admin_identity import Rescue2AdminIdentity, AdminIdentityError
from diagnostic_task_protocol import DiagnosticTaskEnvelope


class TestProtector:
    def protect(self, data):
        return b"test-protected:" + data

    def unprotect(self, data):
        prefix = b"test-protected:"
        if not data.startswith(prefix):
            raise AdminIdentityError("bad test container")
        return data[len(prefix):]


class Rescue2AdminIdentityTests(unittest.TestCase):
    def test_persists_separate_identity_and_signs_canonical_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "admin.key.dpapi"
            first = Rescue2AdminIdentity(path, TestProtector()).load_or_create()
            fingerprint = first.fingerprint
            public_key = first.public_key_der
            payload = b'{"schemaVersion":1,"taskId":"t-1"}'
            signature = first.sign_task_payload(payload)
            exported = __import__("json").loads(first.export_public_identity())

            self.assertEqual(hashlib.sha256(public_key).hexdigest(), fingerprint)
            self.assertEqual(exported["fingerprint"], fingerprint)
            self.assertEqual(exported["keyType"], "EC-P256")
            self.assertEqual(exported["schemaVersion"], 1)
            self.assertTrue(Rescue2AdminIdentity.verify_task_signature(public_key, payload, signature))
            self.assertFalse(Rescue2AdminIdentity.verify_task_signature(public_key, payload + b" ", signature))
            self.assertTrue(path.read_bytes().startswith(b"R2ADMINKEY1\0"))
            self.assertNotIn(b"BEGIN PRIVATE KEY", path.read_bytes())

            restored = Rescue2AdminIdentity(path, TestProtector()).load_or_create()
            self.assertEqual(fingerprint, restored.fingerprint)
            self.assertTrue(Rescue2AdminIdentity.verify_task_signature(
                restored.public_key_der, payload, restored.sign_task_payload(payload)))
            envelope = DiagnosticTaskEnvelope(
                task_id="task-01", admin_key_fingerprint=restored.fingerprint,
                target_participant_ids=("phone-a",), probe_ids=("android.runtime",),
                created_at_epoch_ms=1_800_000_000_000,
                expires_at_epoch_ms=1_800_000_600_000, nonce="A" * 22)
            canonical, envelope_signature = restored.sign_task_envelope(envelope)
            self.assertEqual(canonical, envelope.to_json().encode("utf-8"))
            self.assertTrue(Rescue2AdminIdentity.verify_task_signature(
                restored.public_key_der, canonical, envelope_signature))
            wrong_fingerprint = DiagnosticTaskEnvelope(
                task_id="task-02", admin_key_fingerprint="b" * 64,
                target_participant_ids=("phone-a",), probe_ids=("android.runtime",),
                created_at_epoch_ms=1_800_000_000_000,
                expires_at_epoch_ms=1_800_000_600_000, nonce="B" * 22)
            with self.assertRaises(ValueError):
                restored.sign_task_envelope(wrong_fingerprint)

    def test_requires_explicit_load_and_rejects_bad_container(self):
        with tempfile.TemporaryDirectory() as directory:
            identity = Rescue2AdminIdentity(Path(directory) / "admin.key", TestProtector())
            with self.assertRaises(AdminIdentityError):
                _ = identity.fingerprint
            path = Path(directory) / "broken.key"
            path.write_bytes(b"not-an-admin-key")
            with self.assertRaises(AdminIdentityError):
                Rescue2AdminIdentity(path, TestProtector()).load_or_create()


if __name__ == "__main__":
    unittest.main()
