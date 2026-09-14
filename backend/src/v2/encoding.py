"""Deterministic encoding shared by sync persistence, merging and timers."""

from datetime import datetime
import hashlib
import json
from typing import Any

from fastapi.encoders import jsonable_encoder

from src.v2.schemas import SyncOperationRequest, utc_iso


def canonical_json(value: Any) -> str:
    """Serialize payloads deterministically for hashing and storage."""
    return json.dumps(
        jsonable_encoder(value),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def parse_json(value: str) -> dict[str, Any]:
    parsed = json.loads(value or "{}")
    return parsed if isinstance(parsed, dict) else {}


def jsonable_utc(value: Any) -> Any:
    """Encode nested sync payloads without ever emitting a naive timestamp."""
    if isinstance(value, datetime):
        return utc_iso(value)
    if isinstance(value, dict):
        return {key: jsonable_utc(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [jsonable_utc(item) for item in value]
    return jsonable_encoder(value)


def operation_hash(operation: SyncOperationRequest) -> str:
    encoded = canonical_json(operation.model_dump(mode="json")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()
