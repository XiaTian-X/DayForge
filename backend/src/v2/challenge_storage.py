"""Actual challenge persistence inside caller-owned transactions.

Birth capture is used by guarded v5 fact/timer producers. The challenge-profile
dispatcher owns replica admission, the original shared SyncOperation receipt
and wire/source verification before acknowledging success; this helper alone
never establishes that API authority.
No helper commits, authenticates self-declared owner fields, or resets facts.
"""

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col

from src.v2.challenge_models import (
    ActivityChallengeRound,
    ActivityChallengeHead,
    ActivityChallengeEventBinding,
    ActivityChallengeTimerBinding,
)
from src.v2.challenge_round import (
    ChallengeRestartIntent,
    ChallengeRoundRecord,
    ChallengeTransitionError,
    advance_challenge,
    initial_round_head,
)
from src.v2.challenge_recovery import (
    ChallengeRecoveryError,
    decode_round,
    read_connection_challenges,
)
from src.v2.device_service import (
    STRUCTURE_CAPABILITY,
    capabilities_for_device,
    require_device,
)
from src.v2.encoding import canonical_json
from src.v2.errors import DomainError
from src.v2.change_log import append_change
from src.v2.entity_snapshots import serialize_plan_node
from src.v2.invariants import require_internal
from src.v2.models import (
    ActivityDetail,
    ActivityEvent,
    ClientDevice,
    EntityRevisionSnapshot,
    PlanNode,
    TimerSession,
    utc_now,
)
from src.v2.contract_types import PublicId
from pydantic import TypeAdapter


async def _owned_activity(session: AsyncSession, user_id: int, node_id: int):
    row = (
        await session.execute(
            select(PlanNode, ActivityDetail)
            .join(ActivityDetail, col(ActivityDetail.node_id) == col(PlanNode.id))
            .where(
                col(PlanNode.owner_user_id) == user_id,
                col(PlanNode.id) == node_id,
                col(PlanNode.node_kind) == "activity",
            )
            .execution_options(populate_existing=True)
        )
    ).one_or_none()
    if row is None:
        raise DomainError("ACTIVITY_NOT_FOUND", "Activity was not found")
    return row


async def _validate_history(session: AsyncSession, user_id: int, node_id: int):
    try:
        return await session.run_sync(
            lambda current: read_connection_challenges(
                current.connection(), owner_user_id=user_id, activity_node_id=node_id
            )
        )
    except ChallengeRecoveryError as error:
        raise DomainError(
            "CHALLENGE_HISTORY_INVALID", "Challenge history cannot be recovered safely"
        ) from error


async def ensure_initial_challenge(
    session: AsyncSession, user_id: int, node_id: int
) -> ActivityChallengeRound:
    node, _ = await _owned_activity(session, user_id, node_id)
    histories = await _validate_history(session, user_id, node_id)
    if node_id in histories:
        head, _ = histories[node_id]
        return require_internal(
            (
                await session.execute(
                    select(ActivityChallengeRound).where(
                        col(ActivityChallengeRound.owner_user_id) == user_id,
                        col(ActivityChallengeRound.activity_node_id) == node_id,
                        col(ActivityChallengeRound.public_id) == head.round_uuid,
                    )
                )
            ).scalar_one_or_none(),
            "ChallengeRound",
        )
    head = initial_round_head(node.public_id)
    initial = ActivityChallengeRound(
        owner_user_id=user_id,
        activity_node_id=node_id,
        public_id=head.round_uuid,
        generation=0,
    )
    session.add(initial)
    await session.flush()
    session.add(
        ActivityChallengeHead(
            activity_node_id=node_id,
            owner_user_id=user_id,
            round_id=require_internal(initial.id, "ChallengeRound.id"),
        )
    )
    await session.flush()
    return initial


async def _resolve_birth(
    session: AsyncSession, user_id: int, node_id: int, round_uuid: str | None
) -> ActivityChallengeRound:
    current = await ensure_initial_challenge(session, user_id, node_id)
    if round_uuid is None:
        if current.generation != 0:
            raise DomainError(
                "CHALLENGE_ROUND_REQUIRED",
                "This activity requires an explicit original round",
            )
        return current  # Only the explicit baseline, never a guessed positive head.
    TypeAdapter(PublicId).validate_python(round_uuid)
    row = (
        await session.execute(
            select(ActivityChallengeRound).where(
                col(ActivityChallengeRound.owner_user_id) == user_id,
                col(ActivityChallengeRound.activity_node_id) == node_id,
                col(ActivityChallengeRound.public_id) == round_uuid,
            )
        )
    ).scalar_one_or_none()
    if row is None:
        raise DomainError(
            "CHALLENGE_ROUND_NOT_FOUND",
            "Original round was not found for this activity",
        )
    return row


async def bind_created_event(
    session: AsyncSession, event: ActivityEvent, *, round_uuid: str | None = None
) -> None:
    event_id = require_internal(event.id, "ActivityEvent.id")
    node, detail = await _owned_activity(
        session, event.owner_user_id, event.activity_node_id
    )
    if detail.completion_policy != "recurring":
        if round_uuid is not None:
            raise DomainError(
                "INVALID_PAYLOAD", "One-time items do not use challenge rounds"
            )
        return
    if node.deleted_at is not None:
        raise DomainError("ENTITY_DELETED", "Activity has been deleted")
    if await session.get(ActivityChallengeEventBinding, event_id) is not None:
        raise DomainError(
            "CHALLENGE_BINDING_EXISTS", "An original fact birth cannot be rewritten"
        )
    if event.event_type == "duration_session":
        raise DomainError(
            "TIMER_COMMAND_REQUIRED", "Timer completion must inherit the session birth"
        )
    if event.reverts_event_id is not None:
        original = await session.get(ActivityEvent, event.reverts_event_id)
        if original is None or (original.owner_user_id, original.activity_node_id) != (
            event.owner_user_id,
            event.activity_node_id,
        ):
            raise DomainError(
                "REVERT_TARGET_NOT_FOUND",
                "Undo target does not belong to this activity",
            )
        binding = await session.get(
            ActivityChallengeEventBinding, event.reverts_event_id
        )
        if binding is None:
            # An explicitly identified old fact is at most the baseline.
            baseline_uuid = initial_round_head(node.public_id).round_uuid
            source = await _resolve_birth(
                session, event.owner_user_id, event.activity_node_id, baseline_uuid
            )
        else:
            await _validate_history(
                session, event.owner_user_id, event.activity_node_id
            )
            source = require_internal(
                await session.get(ActivityChallengeRound, binding.round_id),
                "ChallengeRound",
            )
        if round_uuid is not None and round_uuid != source.public_id:
            raise DomainError(
                "CHALLENGE_BINDING_MISMATCH", "Undo must retain the original birth"
            )
    else:
        source = await _resolve_birth(
            session, event.owner_user_id, event.activity_node_id, round_uuid
        )
    session.add(
        ActivityChallengeEventBinding(
            event_id=event_id,
            owner_user_id=event.owner_user_id,
            activity_node_id=event.activity_node_id,
            round_id=require_internal(source.id, "ChallengeRound.id"),
        )
    )
    await session.flush()


async def capture_timer_birth(
    session: AsyncSession, timer: TimerSession, *, round_uuid: str | None = None
) -> None:
    session_id = require_internal(timer.id, "TimerSession.id")
    binding = await session.get(ActivityChallengeTimerBinding, session_id)
    if binding is not None:
        await _validate_history(session, timer.owner_user_id, timer.activity_node_id)
        source = require_internal(
            await session.get(ActivityChallengeRound, binding.round_id),
            "ChallengeRound",
        )
        if round_uuid is not None and round_uuid != source.public_id:
            raise DomainError(
                "CHALLENGE_BINDING_MISMATCH", "Timer birth cannot be rewritten"
            )
        return
    _, detail = await _owned_activity(
        session, timer.owner_user_id, timer.activity_node_id
    )
    if detail.completion_policy != "recurring" or detail.tracking_mode != "duration":
        raise DomainError(
            "INVALID_ACTIVITY_MODE",
            "Only recurring duration activities have timer births",
        )
    source = await _resolve_birth(
        session, timer.owner_user_id, timer.activity_node_id, round_uuid
    )
    session.add(
        ActivityChallengeTimerBinding(
            session_id=session_id,
            owner_user_id=timer.owner_user_id,
            activity_node_id=timer.activity_node_id,
            round_id=require_internal(source.id, "ChallengeRound.id"),
        )
    )
    await session.flush()


async def inherit_completed_timer_birth(
    session: AsyncSession, timer: TimerSession
) -> None:
    binding = require_internal(
        await session.get(
            ActivityChallengeTimerBinding, require_internal(timer.id, "TimerSession.id")
        ),
        "Timer birth",
    )
    event = require_internal(
        await session.get(ActivityEvent, timer.completed_event_id), "Timer completion"
    )
    if (
        timer.state != "completed"
        or event.event_type != "duration_session"
        or event.public_id != timer.public_id
        or (event.owner_user_id, event.activity_node_id)
        != (timer.owner_user_id, timer.activity_node_id)
    ):
        raise DomainError(
            "CHALLENGE_BINDING_MISMATCH", "Completion must prove the original timer"
        )
    current = await session.get(ActivityChallengeEventBinding, event.id)
    if current is not None:
        if (current.owner_user_id, current.activity_node_id, current.round_id) != (
            binding.owner_user_id,
            binding.activity_node_id,
            binding.round_id,
        ):
            raise DomainError(
                "CHALLENGE_BINDING_MISMATCH",
                "Completion birth differs from its session",
            )
        return
    session.add(
        ActivityChallengeEventBinding(
            event_id=require_internal(event.id, "ActivityEvent.id"),
            owner_user_id=event.owner_user_id,
            activity_node_id=event.activity_node_id,
            round_id=binding.round_id,
        )
    )
    await session.flush()
    await _validate_history(session, timer.owner_user_id, timer.activity_node_id)


async def restart_stored_challenge(
    session: AsyncSession,
    user_id: int,
    device_uuid: str,
    operation_uuid: str,
    intent: ChallengeRestartIntent,
) -> ChallengeRoundRecord:
    """Durable CAS/creation inside a SAVEPOINT + caller's outer transaction.

    It is not a standalone API receipt. Replica/raw-operation admission and the
    shared SyncOperation namespace are the caller's responsibility.
    """
    TypeAdapter(PublicId).validate_python(operation_uuid)
    device = await require_device(user_id, device_uuid, session)
    device_id = require_internal(device.id, "ClientDevice.id")
    encoded = canonical_json(intent.model_dump(mode="json"))
    prior = (
        await session.execute(
            select(ActivityChallengeRound).where(
                col(ActivityChallengeRound.source_device_id) == device_id,
                col(ActivityChallengeRound.restart_operation_uuid) == operation_uuid,
            )
        )
    ).scalar_one_or_none()
    if prior is not None:
        if prior.owner_user_id != user_id or prior.restart_intent_json != encoded:
            raise DomainError(
                "OPERATION_ID_REUSED",
                "Restart operation was reused with another intent",
            )
        node, _ = await _owned_activity(session, user_id, prior.activity_node_id)
        await _validate_history(session, user_id, prior.activity_node_id)
        return decode_round(prior.model_dump(), node.public_id, device.public_id)
    capabilities, _ = await capabilities_for_device(session, device)
    if STRUCTURE_CAPABILITY not in capabilities:
        raise DomainError(
            "DEVICE_CAPABILITY_DENIED", "This device cannot restart challenges"
        )
    node_id = (
        await session.execute(
            select(col(PlanNode.id)).where(
                col(PlanNode.owner_user_id) == user_id,
                col(PlanNode.public_id) == intent.activity_uuid,
                col(PlanNode.node_kind) == "activity",
            )
        )
    ).scalar_one_or_none()
    if node_id is None:
        raise DomainError("ACTIVITY_NOT_FOUND", "Activity was not found")
    async with session.begin_nested():
        node, detail = await _owned_activity(session, user_id, node_id)
        if node.deleted_at is not None:
            raise DomainError("ENTITY_DELETED", "Activity has been deleted")
        if detail.completion_policy != "recurring" or detail.target_cycles is None:
            raise DomainError(
                "CHALLENGE_NOT_SUPPORTED",
                "Restart requires a finite recurring challenge",
            )
        previous = await ensure_initial_challenge(session, user_id, node_id)
        unfinished = (
            select(col(TimerSession.id))
            .where(
                col(TimerSession.owner_user_id) == user_id,
                col(TimerSession.activity_node_id) == node_id,
                col(TimerSession.state).in_(("running", "paused")),
            )
            .exists()
        )
        has_unfinished = bool((await session.execute(select(unfinished))).scalar_one())
        try:
            previous_device = (
                require_internal(
                    await session.get(ClientDevice, previous.source_device_id),
                    "Round source",
                )
                if previous.source_device_id is not None
                else None
            )
            before_record = decode_round(
                previous.model_dump(),
                node.public_id,
                previous_device.public_id if previous_device else None,
            )
            head = advance_challenge(
                before_record.head,
                intent,
                actual_plan_revision=node.revision,
                has_unfinished_timer=has_unfinished,
            )
        except ChallengeTransitionError as error:
            raise DomainError(
                str(error), "Challenge does not accept this restart", conflict=True
            ) from error
        changed = await session.execute(
            update(PlanNode)
            .where(
                col(PlanNode.id) == node_id,
                col(PlanNode.owner_user_id) == user_id,
                col(PlanNode.revision) == intent.expected_plan_revision,
                col(PlanNode.deleted_at).is_(None),
                ~unfinished,
            )
            .values(status="active", revision=node.revision + 1, updated_at=utc_now())
            .returning(col(PlanNode.id))
            .execution_options(synchronize_session="fetch")
        )
        if changed.scalar_one_or_none() is None:
            raise DomainError(
                "CHALLENGE_STATE_CONFLICT", "Plan changed during restart", conflict=True
            )
        created = ActivityChallengeRound(
            owner_user_id=user_id,
            activity_node_id=node_id,
            public_id=head.round_uuid,
            generation=head.generation,
            source_device_id=device_id,
            restart_operation_uuid=operation_uuid,
            restart_intent_json=encoded,
        )
        session.add(created)
        await session.flush()
        switched = await session.execute(
            update(ActivityChallengeHead)
            .where(
                col(ActivityChallengeHead.owner_user_id) == user_id,
                col(ActivityChallengeHead.activity_node_id) == node_id,
                col(ActivityChallengeHead.round_id) == previous.id,
            )
            .values(round_id=require_internal(created.id, "ChallengeRound.id"))
            .returning(col(ActivityChallengeHead.activity_node_id))
            .execution_options(synchronize_session="fetch")
        )
        if switched.scalar_one_or_none() is None:
            raise DomainError(
                "CHALLENGE_STATE_CONFLICT", "Another restart already won", conflict=True
            )
        await _validate_history(session, user_id, node_id)
        record = decode_round(created.model_dump(), node.public_id, device.public_id)
        # Baseline birth capture alone does not publish an unsupported entity
        # into legacy v5 pull. An admitted challenge restart publishes both steps.
        if previous.generation == 0:
            initial_snapshot = (
                await session.execute(
                    select(EntityRevisionSnapshot).where(
                        col(EntityRevisionSnapshot.owner_user_id) == user_id,
                        col(EntityRevisionSnapshot.entity_type) == "challenge_round",
                        col(EntityRevisionSnapshot.entity_uuid)
                        == before_record.head.round_uuid,
                    )
                )
            ).scalar_one_or_none()
            if initial_snapshot is None:
                await append_change(
                    session,
                    user_id=user_id,
                    device_id=None,
                    operation_id=None,
                    entity_type="challenge_round",
                    entity_uuid=before_record.head.round_uuid,
                    operation="upsert",
                    revision=1,
                    payload=before_record.model_dump(mode="json"),
                )
            elif initial_snapshot.payload_json != canonical_json(
                before_record.model_dump(mode="json")
            ):
                raise DomainError(
                    "CHALLENGE_HISTORY_INVALID",
                    "Initial round snapshot differs from its origin",
                )
        await append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            operation_id=operation_uuid,
            entity_type="challenge_round",
            entity_uuid=record.head.round_uuid,
            operation="upsert",
            revision=1,
            payload=record.model_dump(mode="json"),
        )
        await append_change(
            session,
            user_id=user_id,
            device_id=device_id,
            operation_id=operation_uuid,
            entity_type="plan_node",
            entity_uuid=node.public_id,
            operation="upsert",
            revision=node.revision,
            payload=await serialize_plan_node(session, node, next_protocol=True),
        )
        return record
