import base64
import os
import tempfile
import unittest
from pathlib import Path

from lan_p2p_transfer import TransferValidationError
from lan_p2p_transfer_provision import (
    load_or_create_peer_id, validate_peer_secret, write_peer_credentials,
)


class TransferProvisionTests(unittest.TestCase):
    def test_writes_private_explicit_peer_credential_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "trusted.json")
            encoded = base64.b64encode(b"k" * 32).decode("ascii")
            write_peer_credentials(path, {"android-peer": encoded})
            self.assertEqual(0o600, Path(path).stat().st_mode & 0o777)
            with open(path, encoding="utf-8") as source:
                value = source.read()
            self.assertIn("android-peer", value)
            self.assertIn(encoded, value)

    def test_refuses_overwrite_and_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "trusted.json")
            encoded = base64.b64encode(b"k" * 32).decode("ascii")
            write_peer_credentials(path, {"peer": encoded})
            with self.assertRaises(FileExistsError):
                write_peer_credentials(path, {"peer": encoded})
            link = str(Path(directory) / "link.json")
            os.symlink(path, link)
            with self.assertRaises(TransferValidationError):
                write_peer_credentials(link, {"peer": encoded}, overwrite=True)

    def test_rejects_invalid_secret_and_peer_id(self):
        with self.assertRaises(TransferValidationError):
            validate_peer_secret("bad peer", base64.b64encode(b"k" * 32).decode("ascii"))
        with self.assertRaises(TransferValidationError):
            validate_peer_secret("peer", base64.b64encode(b"short").decode("ascii"))

    def test_identity_is_stable_and_private(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "peer.id")
            first = load_or_create_peer_id(path)
            second = load_or_create_peer_id(path)
            self.assertEqual(first, second)
            self.assertTrue(first.startswith("python-"))
            self.assertEqual(0o600, Path(path).stat().st_mode & 0o777)

    @unittest.skipUnless(os.name == "posix", "POSIX identity-file mode policy")
    def test_identity_rejects_broad_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "peer.id")
            load_or_create_peer_id(path)
            os.chmod(path, 0o644)
            with self.assertRaisesRegex(TransferValidationError, "0600"):
                load_or_create_peer_id(path)


if __name__ == "__main__":
    unittest.main()
