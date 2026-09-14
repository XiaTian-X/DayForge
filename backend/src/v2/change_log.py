"""Persist a revision snapshot and its change entry in the caller's transaction."""

import hashlib
from typing import Any, Optional

from sqlalchemy.ext.asyncio import AsyncSession

from src.v2.encoding import canonical_json
from src.v2.models import EntityRevisionSnapshot, SyncChange


async def append_change(
    session: AsyncSession,
    *,
    user_id: int,
    device_id: Optional[int],
    operation_id: Optional[str],
    entity_type: str,
    entity_uuid: str,
    operation: str,
    revision: int,
    payload: dict[str, Any],
) -> SyncChange:
    payload_json = canonical_json(payload)
    snapshot = EntityRevisionSnapshot(
        owner_user_id=user_id,
        entity_type=entity_type,
        entity_uuid=entity_uuid,
        revision=revision,
        operation=operation,
        payload_json=payload_json,
        payload_hash=hashlib.sha256(payload_json.encode("utf-8")).hexdigest(),
        origin_device_id=device_id,
        origin_operation_id=operation_id,
    )
    session.add(snapshot)
    change = SyncChange(
        recipient_user_id=user_id,
        entity_type=entity_type,
        entity_uuid=entity_uuid,
        operation=operation,
        revision=revision,
        payload_json=payload_json,
        origin_user_id=user_id,
        origin_device_id=device_id,
        origin_operation_id=operation_id,
    )
    session.add(change)
    await session.flush()
    return change
