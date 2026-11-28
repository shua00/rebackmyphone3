"""Cross-platform diagnostic batch envelope (serialization only).

No sockets, peer authorization, or cryptographic transport are implemented here.
Android and Python transports must authenticate and protect this envelope before
accepting it from another device.
"""
from __future__ import annotations

from dataclasses import dataclass
import json
import re
from typing import Any

SCHEMA_VERSION = 1
MAX_REPORT_JSON_CHARS = 128 * 1024
_TOKEN = re.compile(r"^[A-Za-z0-9_.-]{1,80}$")


def _validate_token(value: Any, field: str) -> str:
    if not isinstance(value, str) or not _TOKEN.fullmatch(value):
        raise ValueError(f"invalid {field}")
    return value


@dataclass(frozen=True)
class DiagnosticBatchEnvelope:
    batch_id: str
    event_id: str
    run_id: str
    participant_id: str
    sequence: int
    created_at_epoch_ms: int
    report_json: str
    schema_version: int = SCHEMA_VERSION

    def __post_init__(self) -> None:
        for field in ("batch_id", "event_id", "run_id", "participant_id"):
            _validate_token(getattr(self, field), field)
        if self.schema_version != SCHEMA_VERSION:
            raise ValueError("unsupported diagnostic envelope schema")
        if isinstance(self.sequence, bool) or not isinstance(self.sequence, int) or self.sequence < 0:
            raise ValueError("invalid sequence")
        if (isinstance(self.created_at_epoch_ms, bool)
                or not isinstance(self.created_at_epoch_ms, int)
                or self.created_at_epoch_ms <= 0):
            raise ValueError("invalid created_at_epoch_ms")
        if not isinstance(self.report_json, str) or not self.report_json:
            raise ValueError("report_json is required")
        if len(self.report_json) > MAX_REPORT_JSON_CHARS:
            raise ValueError("diagnostic report exceeds size limit")
        json.loads(self.report_json)

    def to_json(self) -> str:
        return json.dumps({
            "schemaVersion": self.schema_version,
            "batchId": self.batch_id,
            "eventId": self.event_id,
            "runId": self.run_id,
            "participantId": self.participant_id,
            "sequence": self.sequence,
            "createdAtEpochMs": self.created_at_epoch_ms,
            "reportJson": self.report_json,
        }, ensure_ascii=False, separators=(",", ":"))

    @classmethod
    def from_json(cls, encoded: str) -> "DiagnosticBatchEnvelope":
        if not isinstance(encoded, str) or len(encoded) > MAX_REPORT_JSON_CHARS + 2048:
            raise ValueError("diagnostic envelope exceeds size limit")
        data = json.loads(encoded)
        if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA_VERSION:
            raise ValueError("unsupported diagnostic envelope schema")
        required = ("batchId", "eventId", "runId", "participantId",
                    "sequence", "createdAtEpochMs", "reportJson")
        if any(key not in data for key in required):
            raise ValueError("missing diagnostic envelope field")
        return cls(
            batch_id=data["batchId"], event_id=data["eventId"], run_id=data["runId"],
            participant_id=data["participantId"], sequence=data["sequence"],
            created_at_epoch_ms=data["createdAtEpochMs"], report_json=data["reportJson"],
            schema_version=data["schemaVersion"],
        )
