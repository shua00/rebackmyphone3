"""Independent Rescue2 administrator P-256 identity.

Private keys are persisted only as Windows DPAPI-protected PKCS#8 DER. The module
fails closed on non-Windows hosts unless a test/application protector is injected.
It never reads or writes the legacy RESCUE_SECRET.
"""
from __future__ import annotations

import base64
import ctypes
import json
import hashlib
import os
import tempfile
import threading
import time
import uuid
from pathlib import Path
from typing import Callable, Optional

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

_KEY_MAGIC = b"R2ADMINKEY1\0"
_SIGNATURE_DOMAIN = b"rebackmyphone3/rescue2-admin-task/v1\0"
_MAX_PRIVATE_BLOB = 16 * 1024


class AdminIdentityError(RuntimeError):
    pass


class WindowsDpapiProtector:
    """Current-Windows-user DPAPI wrapper; no passphrase or shared app secret."""
    @staticmethod
    def _transform(data: bytes, protect: bool) -> bytes:
        if os.name != "nt":
            raise AdminIdentityError("Rescue2 administrator key storage requires Windows DPAPI")
        class DATA_BLOB(ctypes.Structure):
            _fields_ = [("cbData", ctypes.c_uint32), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]
        source_buffer = ctypes.create_string_buffer(data)
        source = DATA_BLOB(len(data), ctypes.cast(source_buffer, ctypes.POINTER(ctypes.c_ubyte)))
        target = DATA_BLOB()
        crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        blob_ptr = ctypes.POINTER(DATA_BLOB)
        if protect:
            fn = crypt32.CryptProtectData
            fn.argtypes = [blob_ptr, ctypes.c_wchar_p, blob_ptr, ctypes.c_void_p,
                           ctypes.c_void_p, ctypes.c_uint32, blob_ptr]
            fn.restype = ctypes.c_int
            ok = fn(ctypes.byref(source), "Rescue2 administrator identity", None, None,
                    None, 0x1, ctypes.byref(target))
        else:
            fn = crypt32.CryptUnprotectData
            fn.argtypes = [blob_ptr, ctypes.POINTER(ctypes.c_wchar_p), blob_ptr,
                           ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32, blob_ptr]
            fn.restype = ctypes.c_int
            description = ctypes.c_wchar_p()
            ok = fn(ctypes.byref(source), ctypes.byref(description), None, None,
                    None, 0x1, ctypes.byref(target))
        kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        kernel32.LocalFree.restype = ctypes.c_void_p
        if not ok:
            raise AdminIdentityError("Windows DPAPI operation failed (error %d)" % ctypes.get_last_error())
        try:
            return ctypes.string_at(target.pbData, target.cbData)
        finally:
            kernel32.LocalFree(target.pbData)


class Rescue2AdminIdentity:
    """One independent administrator signing identity, separate from peer pairing."""
    @staticmethod
    def default_key_path() -> Path:
        if os.name != "nt":
            raise AdminIdentityError("Rescue2 administrator identity storage currently requires Windows")
        base = os.environ.get("LOCALAPPDATA") or os.path.join(
            os.path.expanduser("~"), "AppData", "Local")
        return Path(base) / "RebackMyPhone3" / "rescue2_admin.key.dpapi"

    def __init__(self, key_path: os.PathLike | str,
                 protector: Optional[object] = None):
        self.key_path = Path(key_path)
        self.protector = protector or WindowsDpapiProtector()
        self._key = None
        self._lock = threading.RLock()

    def load_or_create(self) -> "Rescue2AdminIdentity":
        with self._lock:
            if self._key is not None:
                return self
            self.key_path.parent.mkdir(parents=True, exist_ok=True)
            if self.key_path.exists():
                raw = self.key_path.read_bytes()
                if not raw.startswith(_KEY_MAGIC) or len(raw) > _MAX_PRIVATE_BLOB:
                    raise AdminIdentityError("Invalid administrator key container")
                private_der = self.protector.unprotect(raw[len(_KEY_MAGIC):])
                key = serialization.load_der_private_key(private_der, password=None)
                if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
                    raise AdminIdentityError("Stored administrator key is not P-256")
                self._key = key
                return self

            key = ec.generate_private_key(ec.SECP256R1())
            private_der = key.private_bytes(
                serialization.Encoding.DER,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
            protected = _KEY_MAGIC + self.protector.protect(private_der)
            if not self._write_container(protected):
                # Another Rescue2 process won the first-run race; always load its persisted key.
                raw = self.key_path.read_bytes()
                if not raw.startswith(_KEY_MAGIC) or len(raw) > _MAX_PRIVATE_BLOB:
                    raise AdminIdentityError("Invalid administrator key container")
                private_der = self.protector.unprotect(raw[len(_KEY_MAGIC):])
                key = serialization.load_der_private_key(private_der, password=None)
                if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
                    raise AdminIdentityError("Stored administrator key is not P-256")
            self._key = key
            return self

    def _write_container(self, payload: bytes) -> bool:
        lock_path = self.key_path.with_suffix(self.key_path.suffix + ".lock")
        deadline = time.monotonic() + 5.0
        lock_fd = None
        while lock_fd is None:
            try:
                lock_fd = os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                if self.key_path.exists():
                    return False
                if time.monotonic() >= deadline:
                    raise AdminIdentityError("Timed out waiting for administrator key creation")
                time.sleep(0.05)
        try:
            if self.key_path.exists():
                return False
            fd, temporary = tempfile.mkstemp(prefix=self.key_path.name + ".", dir=str(self.key_path.parent))
            try:
                with os.fdopen(fd, "wb") as stream:
                    stream.write(payload)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, self.key_path)
                try:
                    os.chmod(self.key_path, 0o600)
                except OSError:
                    pass
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
        finally:
            os.close(lock_fd)
            try:
                os.unlink(lock_path)
            except FileNotFoundError:
                pass
        return True

    def _require_key(self):
        if self._key is None:
            raise AdminIdentityError("Call load_or_create() before using the administrator identity")
        return self._key

    @property
    def public_key_der(self) -> bytes:
        key = self._require_key().public_key()
        return key.public_bytes(serialization.Encoding.DER,
                                 serialization.PublicFormat.SubjectPublicKeyInfo)

    @property
    def fingerprint(self) -> str:
        return hashlib.sha256(self.public_key_der).hexdigest()

    def export_public_identity(self) -> str:
        """Stable public-only enrollment document; it contains no private key material."""
        public_key = base64.urlsafe_b64encode(self.public_key_der).decode("ascii").rstrip("=")
        return json.dumps({
            "schemaVersion": 1,
            "keyType": "EC-P256",
            "fingerprint": self.fingerprint,
            "publicKeySpki": public_key,
        }, separators=(",", ":"), ensure_ascii=True)

    def sign_task_payload(self, canonical_payload: bytes) -> bytes:
        if not isinstance(canonical_payload, bytes) or not canonical_payload or len(canonical_payload) > 16 * 1024:
            raise ValueError("Task payload must be 1..16384 bytes")
        return self._require_key().sign(_SIGNATURE_DOMAIN + canonical_payload,
                                        ec.ECDSA(hashes.SHA256()))

    def export_public_identity_file(self, destination: os.PathLike | str) -> Path:
        """Write a public-only enrollment document without exposing private material."""
        target = Path(destination)
        target.parent.mkdir(parents=True, exist_ok=True)
        content = self.export_public_identity() + "\n"
        with target.open("x", encoding="utf-8") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        return target

    def sign_task_envelope(self, envelope) -> tuple[bytes, bytes]:
        """Return canonical UTF-8 JSON and detached signature for a matching task envelope."""
        if getattr(envelope, "admin_key_fingerprint", None) != self.fingerprint:
            raise ValueError("Task envelope fingerprint does not identify this Rescue2 administrator")
        canonical = envelope.to_json().encode("utf-8")
        return canonical, self.sign_task_payload(canonical)

    @staticmethod
    def verify_task_signature(public_key_der: bytes, canonical_payload: bytes,
                              signature_der: bytes) -> bool:
        try:
            if not (1 <= len(canonical_payload) <= 16 * 1024 and 1 <= len(signature_der) <= 128):
                return False
            key = serialization.load_der_public_key(public_key_der)
            if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
                return False
            key.verify(signature_der, _SIGNATURE_DOMAIN + canonical_payload,
                       ec.ECDSA(hashes.SHA256()))
            return True
        except Exception:
            return False
