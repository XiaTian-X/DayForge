"""Same-transaction current-scope proof and pure recovery snapshot projection."""

from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.auth.models import User
from src.v2.challenge_profile_service import read_challenge_metadata
from src.v2.challenge_sync_contract import RoundSyncBootstrapResponse
from src.v2.device_service import to_device_response
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.models import ClientDevice, TimerSession
from src.v2.read_service import read_bootstrap_snapshot
from src.v2.recovery_snapshot_contract import (
    ReplicaRecoveryPreviewResponse,
    ReplicaRecoveryMaterialPreview,
    RecoveryPackVersion,
    recovery_material_ids,
)
from src.v2.asset_api_contract import AssetSyncContext
from src.v2.asset_service import read_asset, read_pack
from src.v2.replica_context import ReplicaIdentity
from src.v2.timer_service import serialize_timer_session


async def read_recovery_preview(
    user: User,
    device: ClientDevice,
    replica: ReplicaIdentity,
    session: AsyncSession,
    *,
    include_materials: bool = False,
    requested_pack: RecoveryPackVersion | None = None,
) -> ReplicaRecoveryPreviewResponse:
    owner = require_internal(user.id, "User.id")
    # Refuse accidental caller writes rather than flushing them during preview.
    if requested_pack is not None and not include_materials:
        raise ValueError("selected pack requires explicit material preview")
    if session.new or session.dirty or session.deleted:
        raise RuntimeError("recovery preview requires a clean transaction")
    snapshot = await read_bootstrap_snapshot(
        user, session, next_protocol=True, round_profile=True
    )
    events = {
        str(change.entity_uuid)
        for change in snapshot.changes
        if change.entity_type == "activity_event"
    }
    metadata = await read_challenge_metadata(session, owner, events=events)
    timers = (
        (
            await session.execute(
                select(TimerSession).where(
                    col(TimerSession.owner_user_id) == owner,
                    col(TimerSession.state).in_({"running", "paused"}),
                )
            )
        )
        .scalars()
        .all()
    )
    if len(timers) > 1:
        raise DomainError("RECOVERY_STATE_INVALID", "Multiple unfinished timers")
    response = ReplicaRecoveryPreviewResponse.model_validate(
        {
            "recovery_contract": 1,
            "protocol_version": 5,
            "account_id": user.public_id,
            "server_instance_id": replica.server_instance_id,
            "sync_epoch": replica.sync_epoch,
            "device": await to_device_response(session, device),
            "snapshot": RoundSyncBootstrapResponse.model_validate(
                {
                    **snapshot.model_dump(mode="json"),
                    **metadata.model_dump(mode="json"),
                }
            ),
            "active_timer": await serialize_timer_session(session, timers[0])
            if timers
            else None,
        }
    )
    if include_materials:
        context = AssetSyncContext(
            server_instance_id=replica.server_instance_id,
            sync_epoch=replica.sync_epoch,
            device_id=device.public_id,
        )
        pack = None
        if requested_pack is not None:
            try:
                pack = (
                    await read_pack(
                        session,
                        user,
                        context,
                        requested_pack.pack_id,
                        requested_pack.revision,
                    )
                ).pack
            except DomainError as error:
                if error.code != "PACK_NOT_FOUND":
                    raise
        present = []
        missing = []
        for identifier in sorted(recovery_material_ids(response.snapshot, pack)):
            try:
                present.append(await read_asset(session, user, context, identifier))
            except DomainError as error:
                if error.code != "ASSET_NOT_FOUND":
                    raise
                missing.append(identifier)
        try:
            material = ReplicaRecoveryMaterialPreview(
                material_contract=1,
                requested_pack=requested_pack,
                pack=pack,
                assets=present,
                missing_assets=missing,
            )
            # Revalidate the complete envelope, not an unchecked model_copy update.
            response = ReplicaRecoveryPreviewResponse.model_validate(
                {
                    **response.model_dump(mode="json"),
                    "material_preview": material.model_dump(mode="json"),
                }
            )
        except ValidationError as error:
            raise DomainError(
                "RECOVERY_STATE_INVALID", "Recovery material evidence is inconsistent"
            ) from error
    if session.new or session.dirty or session.deleted:
        raise RuntimeError("recovery preview cannot mutate its transaction")
    return response
