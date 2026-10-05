"""Deterministic encoding shared by sync persistence, merging and timers."""

from datetime import datetime
import hashlib
import json
from typing import Any

from fastapi.encoders import jsonable_encoder

from src.v2.schemas import SyncOperationRequest, TimerCommandRequest, utc_iso
from src.v2.replica_context import ReplicaIdentity


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


def _request_hash(
    payload: dict[str, Any], kind: str, replica: ReplicaIdentity | None
) -> str:
    if replica is not None:
        if type(replica) is not ReplicaIdentity:
            raise ValueError("A captured replica identity is required")
        payload = {
            "protocol_version": 5,
            "request_kind": kind,
            "server_instance_id": replica.server_instance_id,
            "sync_epoch": replica.sync_epoch,
            "payload": payload,
        }
    encoded = canonical_json(payload).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def operation_hash(
    operation: SyncOperationRequest, *, replica: ReplicaIdentity | None = None
) -> str:
    return _request_hash(operation.model_dump(mode="json"), "sync_operation", replica)


def timer_command_hash(
    command: TimerCommandRequest, *, replica: ReplicaIdentity | None = None
) -> str:
    return _request_hash(command.model_dump(mode="json"), "timer_command", replica)
