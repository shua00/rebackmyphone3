"""Transport-neutral, bounded Rescue2 diagnostic task protocol.

This is a schema/validation layer only. It does NOT authenticate an administrator,
authorize a device, or provide a secure transport. Callers must verify the
administrator signature and device-side task consent before execution.
"""
from __future__ import annotations

from dataclasses import dataclass
import json
import re
from typing import Any, Iterable

SCHEMA_VERSION = 1
MAX_TARGETS = 64
MAX_PROBES = 16
MIN_TASK_TTL_MS = 1_000
MAX_TASK_TTL_MS = 30 * 60 * 1000
_TOKEN = re.compile(r"^[A-Za-z0-9_.-]{1,80}$")
_FINGERPRINT = re.compile(r"^[0-9a-f]{64}$")
ALLOWED_PROBES = frozenset({
    "android.runtime",
    "network.connectivity",
    "app.install",
    "lan.ipv4_interface",
    "lan.udp_bind",
    "lan.transfer_runtime",
})


def _token(value: Any, field: str) -> str:
    if not isinstance(value, str) or not _TOKEN.fullmatch(value):
        raise ValueError(f"invalid {field}")
    return value


def _unique_tokens(values: Any, field: str, maximum: int) -> tuple[str, ...]:
    if not isinstance(values, list) or not values or len(values) > maximum:
        raise ValueError(f"invalid {field} count")
    result = tuple(_token(item, field) for item in values)
    if len(set(result)) != len(result):
        raise ValueError(f"duplicate {field}")
    return result


@dataclass(frozen=True)
class DiagnosticTaskEnvelope:
    task_id: str
    admin_key_fingerprint: str
    target_participant_ids: tuple[str, ...]
    probe_ids: tuple[str, ...]
    created_at_epoch_ms: int
    expires_at_epoch_ms: int
    nonce: str
    schema_version: int = SCHEMA_VERSION

    def __post_init__(self) -> None:
        _token(self.task_id, "task_id")
        if not isinstance(self.admin_key_fingerprint, str) or not _FINGERPRINT.fullmatch(self.admin_key_fingerprint):
            raise ValueError("invalid admin_key_fingerprint")
        targets = _unique_tokens(list(self.target_participant_ids), "target_participant_id", MAX_TARGETS)
        probes = _unique_tokens(list(self.probe_ids), "probe_id", MAX_PROBES)
        if any(probe not in ALLOWED_PROBES for probe in probes):
            raise ValueError("probe is not in the read-only allowlist")
        if self.schema_version != SCHEMA_VERSION:
            raise ValueError("unsupported diagnostic task schema")
        for value, field in ((self.created_at_epoch_ms, "created_at_epoch_ms"),
                             (self.expires_at_epoch_ms, "expires_at_epoch_ms")):
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                raise ValueError(f"invalid {field}")
        ttl = self.expires_at_epoch_ms - self.created_at_epoch_ms
        if ttl < MIN_TASK_TTL_MS or ttl > MAX_TASK_TTL_MS:
            raise ValueError("diagnostic task lifetime is outside policy")
        if not isinstance(self.nonce, str) or not re.fullmatch(r"[A-Za-z0-9_-]{22,64}", self.nonce):
            raise ValueError("invalid task nonce")

    def to_json(self) -> str:
        return json.dumps({
            "schemaVersion": self.schema_version,
            "taskId": self.task_id,
            "adminKeyFingerprint": self.admin_key_fingerprint,
            "targetParticipantIds": list(self.target_participant_ids),
            "probeIds": list(self.probe_ids),
            "createdAtEpochMs": self.created_at_epoch_ms,
            "expiresAtEpochMs": self.expires_at_epoch_ms,
            "nonce": self.nonce,
        }, ensure_ascii=False, separators=(",", ":"))

    @classmethod
    def from_json(cls, encoded: str) -> "DiagnosticTaskEnvelope":
        if not isinstance(encoded, str) or len(encoded) > 16 * 1024:
            raise ValueError("diagnostic task exceeds size limit")
        data = json.loads(encoded)
        required = {"schemaVersion", "taskId", "adminKeyFingerprint",
                    "targetParticipantIds", "probeIds", "createdAtEpochMs",
                    "expiresAtEpochMs", "nonce"}
        if not isinstance(data, dict) or set(data) != required:
            raise ValueError("invalid diagnostic task fields")
        return cls(
            task_id=data["taskId"],
            admin_key_fingerprint=data["adminKeyFingerprint"],
            target_participant_ids=tuple(data["targetParticipantIds"]),
            probe_ids=tuple(data["probeIds"]),
            created_at_epoch_ms=data["createdAtEpochMs"],
            expires_at_epoch_ms=data["expiresAtEpochMs"],
            nonce=data["nonce"],
            schema_version=data["schemaVersion"],
        )

    def is_valid_at(self, now_epoch_ms: int) -> bool:
        return (isinstance(now_epoch_ms, int) and not isinstance(now_epoch_ms, bool)
                and self.created_at_epoch_ms <= now_epoch_ms < self.expires_at_epoch_ms)
