"""Same-transaction current-scope proof and pure recovery snapshot projection."""

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
from src.v2.recovery_snapshot_contract import ReplicaRecoveryPreviewResponse
from src.v2.replica_context import ReplicaIdentity
from src.v2.timer_service import serialize_timer_session


async def read_recovery_preview(
    user: User,
    device: ClientDevice,
    replica: ReplicaIdentity,
    session: AsyncSession,
) -> ReplicaRecoveryPreviewResponse:
    owner = require_internal(user.id, "User.id")
    # Refuse accidental caller writes rather than flushing them during preview.
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
    if session.new or session.dirty or session.deleted:
        raise RuntimeError("recovery preview cannot mutate its transaction")
    return response
