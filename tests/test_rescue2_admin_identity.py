import hashlib
import tempfile
import unittest
from pathlib import Path

from rescue2_admin_identity import Rescue2AdminIdentity, AdminIdentityError


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

            self.assertEqual(hashlib.sha256(public_key).hexdigest(), fingerprint)
            self.assertTrue(Rescue2AdminIdentity.verify_task_signature(public_key, payload, signature))
            self.assertFalse(Rescue2AdminIdentity.verify_task_signature(public_key, payload + b" ", signature))
            self.assertTrue(path.read_bytes().startswith(b"R2ADMINKEY1\0"))
            self.assertNotIn(b"BEGIN PRIVATE KEY", path.read_bytes())

            restored = Rescue2AdminIdentity(path, TestProtector()).load_or_create()
            self.assertEqual(fingerprint, restored.fingerprint)
            self.assertTrue(Rescue2AdminIdentity.verify_task_signature(
                restored.public_key_der, payload, restored.sign_task_payload(payload)))

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
