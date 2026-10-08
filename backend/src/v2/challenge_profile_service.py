"""Owned original-source validation and authenticated round-aware read evidence."""

from typing import Literal
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col

from src.v2.challenge_models import (
    ActivityChallengeRound,
    ActivityChallengeEventBinding,
    ActivityChallengeTimerBinding,
)
from src.v2.challenge_recovery import read_connection_challenges
from src.v2.challenge_round import (
    ChallengeRoundHead,
    ChallengeRoundRecord,
    initial_round_head,
)
from src.v2.challenge_sync_contract import (
    ChallengeBirth,
    ChallengeCheckpoint,
    ChallengeMetadata,
    ChallengeSourceContext,
)
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.models import ActivityDetail, ActivityEvent, PlanNode, TimerSession
from src.v2.schemas import SyncOperationRequest, TimerCommandRequest


async def _histories(db: AsyncSession, owner: int):
    from src.v2.challenge_recovery import require_challenge_history

    await require_challenge_history(db, owner)
    return await db.run_sync(
        lambda current: read_connection_challenges(
            current.connection(), owner_user_id=owner
        )
    )


async def _activity(db: AsyncSession, owner: int, identity: str):
    return (
        await db.execute(
            select(PlanNode, ActivityDetail)
            .join(ActivityDetail, col(ActivityDetail.node_id) == col(PlanNode.id))
            .where(
                col(PlanNode.owner_user_id) == owner,
                col(PlanNode.public_id) == identity,
            )
        )
    ).one_or_none()


def _require_no_head(context: ChallengeSourceContext):
    if context.head is not None:
        raise DomainError(
            "CHALLENGE_BINDING_MISMATCH", "This source does not use a challenge round"
        )


async def _known_head(
    db: AsyncSession,
    owner: int,
    activity: PlanNode,
    context: ChallengeSourceContext,
    *,
    current: bool = False,
    histories=None,
) -> str:
    head = context.head
    if head is None:
        raise DomainError(
            "CHALLENGE_ROUND_REQUIRED", "The original challenge context is required"
        )
    if head.activity_uuid != activity.public_id:
        raise DomainError(
            "CHALLENGE_BINDING_MISMATCH", "Context belongs to another activity"
        )
    if histories is None:
        histories = await _histories(db, owner)
    initial = initial_round_head(activity.public_id)
    actual, records = histories.get(
        activity.id,
        (
            initial,
            (
                ChallengeRoundRecord(
                    head=initial,
                    source_device_uuid=None,
                    restart_operation_uuid=None,
                    restart_intent=None,
                ),
            ),
        ),
    )
    if head not in [record.head for record in records]:
        raise DomainError(
            "CHALLENGE_ROUND_NOT_FOUND",
            "The captured round is not known for this activity",
        )
    if current and actual != head:
        raise DomainError(
            "CHALLENGE_STATE_CONFLICT",
            "Activity has entered another challenge round",
            conflict=True,
        )
    return head.round_uuid


async def _bound_head(
    db: AsyncSession, activity: PlanNode, binding
) -> ChallengeRoundHead:
    if binding is None:
        return initial_round_head(activity.public_id)
    source = require_internal(
        await db.get(ActivityChallengeRound, binding.round_id), "ChallengeRound"
    )
    if (source.owner_user_id, source.activity_node_id) != (
        activity.owner_user_id,
        activity.id,
    ):
        raise DomainError("CHALLENGE_HISTORY_INVALID", "Foreign birth evidence")
    return ChallengeRoundHead(
        activity_uuid=activity.public_id,
        round_uuid=source.public_id,
        generation=source.generation,
    )


async def validate_operation_context(
    db: AsyncSession,
    owner: int,
    operation: SyncOperationRequest,
    context: ChallengeSourceContext,
) -> str | None:
    if context.affected_heads and (
        operation.entity_type != "plan_node" or operation.action != "delete"
    ):
        raise DomainError(
            "CHALLENGE_BINDING_MISMATCH",
            "Affected heads are only for a goal's child mutations",
        )
    if operation.entity_type not in {"plan_node", "activity_event"}:
        _require_no_head(context)
        return None
    identity = (
        str(operation.entity_uuid)
        if operation.entity_type == "plan_node"
        else operation.payload.get("activity_uuid")
    )
    row = await _activity(db, owner, str(identity))
    if row is None:
        if operation.entity_type == "plan_node" and operation.action == "delete":
            goal = (
                await db.execute(
                    select(PlanNode).where(
                        col(PlanNode.owner_user_id) == owner,
                        col(PlanNode.public_id) == str(identity),
                        col(PlanNode.node_kind) == "goal",
                    )
                )
            ).scalar_one_or_none()
            if goal is not None:
                _require_no_head(context)
                children = (
                    await db.execute(
                        select(PlanNode, ActivityDetail)
                        .join(
                            ActivityDetail,
                            col(ActivityDetail.node_id) == col(PlanNode.id),
                        )
                        .where(
                            col(PlanNode.owner_user_id) == owner,
                            col(PlanNode.parent_node_id) == goal.id,
                            col(PlanNode.deleted_at).is_(None),
                        )
                    )
                ).all()
                # Once items have no challenge head. An empty recurring set must not
                # authorize mutating unseen once children through their parent. Each
                # once child must first complete its own accepted detach/delete.
                if any(
                    detail.completion_policy != "recurring" for _, detail in children
                ):
                    raise DomainError(
                        "CHALLENGE_STATE_CONFLICT",
                        "Independent one-time children require their own accepted transition",
                        conflict=True,
                    )
                claimed = {head.activity_uuid: head for head in context.affected_heads}
                if set(claimed) != {child.public_id for child, _ in children}:
                    raise DomainError(
                        "CHALLENGE_STATE_CONFLICT",
                        "The goal's child challenge set changed",
                        conflict=True,
                    )
                histories = await _histories(db, owner)
                for child, _ in children:
                    await _known_head(
                        db,
                        owner,
                        child,
                        ChallengeSourceContext(
                            source_uuid=context.source_uuid,
                            head=claimed[child.public_id],
                        ),
                        current=True,
                        histories=histories,
                    )
                return None
        if operation.entity_type == "plan_node" and operation.action == "upsert":
            payload = operation.payload
            activity = payload.get("activity")
            if (
                isinstance(activity, dict)
                and activity.get("completion_policy") == "recurring"
            ):
                if context.head != initial_round_head(str(operation.entity_uuid)):
                    raise DomainError(
                        "CHALLENGE_BINDING_MISMATCH",
                        "New habits require their own explicit initial round",
                    )
                return context.head.round_uuid
        _require_no_head(context)
        if context.affected_heads:
            raise DomainError(
                "CHALLENGE_BINDING_MISMATCH",
                "Affected activities have no owned parent goal",
            )
        return None  # Normal domain dispatch supplies missing/foreign-entity errors.
    node, detail = row
    if context.affected_heads:
        raise DomainError(
            "CHALLENGE_BINDING_MISMATCH", "Habits cannot declare affected child rounds"
        )
    if detail.completion_policy != "recurring":
        _require_no_head(context)
        return None
    round_uuid = await _known_head(
        db, owner, node, context, current=operation.entity_type == "plan_node"
    )
    if operation.entity_type == "activity_event":
        original_uuid = operation.payload.get("reverts_event_uuid")
        original = (
            (
                await db.execute(
                    select(ActivityEvent).where(
                        col(ActivityEvent.owner_user_id) == owner,
                        col(ActivityEvent.public_id) == str(original_uuid),
                        col(ActivityEvent.activity_node_id) == node.id,
                    )
                )
            ).scalar_one_or_none()
            if original_uuid
            else None
        )
        if original is not None:
            binding = await db.get(ActivityChallengeEventBinding, original.id)
            if await _bound_head(db, node, binding) != context.head:
                raise DomainError(
                    "CHALLENGE_BINDING_MISMATCH", "Undo must retain its original round"
                )
    return round_uuid


async def validate_timer_context(
    db: AsyncSession,
    owner: int,
    command: TimerCommandRequest,
    context: ChallengeSourceContext,
) -> str:
    if context.affected_heads:
        raise DomainError(
            "CHALLENGE_BINDING_MISMATCH",
            "Timer commands have only their own original birth",
        )
    if command.command_type == "start":
        row = await _activity(db, owner, str(command.activity_uuid))
        if row is None:
            raise DomainError("ACTIVITY_NOT_FOUND", "Duration activity was not found")
        node, detail = row
        if detail.completion_policy != "recurring":
            raise DomainError(
                "INVALID_ACTIVITY_MODE", "One-time items do not use timers"
            )
        return await _known_head(db, owner, node, context)
    timer = (
        await db.execute(
            select(TimerSession).where(
                col(TimerSession.owner_user_id) == owner,
                col(TimerSession.public_id) == str(command.session_id),
            )
        )
    ).scalar_one_or_none()
    if timer is None:
        raise DomainError("TIMER_NOT_FOUND", "Timer session was not found")
    node = require_internal(await db.get(PlanNode, timer.activity_node_id), "PlanNode")
    expected = await _bound_head(
        db, node, await db.get(ActivityChallengeTimerBinding, timer.id)
    )
    if context.head != expected:
        raise DomainError(
            "CHALLENGE_BINDING_MISMATCH",
            "All timer commands must retain the session birth",
        )
    return await _known_head(db, owner, node, context)


async def read_challenge_metadata(
    db: AsyncSession,
    owner: int,
    *,
    events: set[str] | None = None,
    timers: set[str] | None = None,
) -> ChallengeMetadata:
    from src.v2.challenge_receipt_recovery import read_connection_challenge_receipts

    await db.run_sync(
        lambda current: read_connection_challenge_receipts(
            current.connection(), owner_user_id=owner
        )
    )
    histories = await _histories(db, owner)
    nodes = (
        await db.execute(
            select(PlanNode, ActivityDetail)
            .join(ActivityDetail, col(ActivityDetail.node_id) == col(PlanNode.id))
            .where(
                col(PlanNode.owner_user_id) == owner,
            )
        )
    ).all()
    checkpoints = []
    activities = {}
    for node, detail in nodes:
        if detail.completion_policy == "recurring":
            activities[node.id] = node
        elif node.id not in histories:
            continue
        baseline = initial_round_head(node.public_id)
        head, records = histories.get(
            node.id,
            (
                baseline,
                (
                    ChallengeRoundRecord(
                        head=baseline,
                        source_device_uuid=None,
                        restart_operation_uuid=None,
                        restart_intent=None,
                    ),
                ),
            ),
        )
        checkpoints.append(ChallengeCheckpoint(head=head, records=list(records)))
    births = []
    event_query = (
        select(ActivityEvent, ActivityChallengeEventBinding, ActivityChallengeRound)
        .outerjoin(
            ActivityChallengeEventBinding,
            col(ActivityChallengeEventBinding.event_id) == col(ActivityEvent.id),
        )
        .outerjoin(
            ActivityChallengeRound,
            col(ActivityChallengeRound.id)
            == col(ActivityChallengeEventBinding.round_id),
        )
        .where(col(ActivityEvent.owner_user_id) == owner)
    )
    timer_query = (
        select(TimerSession, ActivityChallengeTimerBinding, ActivityChallengeRound)
        .outerjoin(
            ActivityChallengeTimerBinding,
            col(ActivityChallengeTimerBinding.session_id) == col(TimerSession.id),
        )
        .outerjoin(
            ActivityChallengeRound,
            col(ActivityChallengeRound.id)
            == col(ActivityChallengeTimerBinding.round_id),
        )
        .where(col(TimerSession.owner_user_id) == owner)
    )
    if events is not None:
        event_query = event_query.where(col(ActivityEvent.public_id).in_(events))
    if timers is not None:
        timer_query = timer_query.where(col(TimerSession.public_id).in_(timers))
    sources: list[
        tuple[
            Literal["activity_event", "timer_session"],
            ActivityEvent | TimerSession,
            ActivityChallengeEventBinding | ActivityChallengeTimerBinding | None,
            ActivityChallengeRound | None,
        ]
    ] = [
        ("activity_event", source, binding, round_row)
        for source, binding, round_row in (await db.execute(event_query)).all()
    ]
    sources.extend(
        ("timer_session", source, binding, round_row)
        for source, binding, round_row in (await db.execute(timer_query)).all()
    )
    for kind, source, binding, round_row in sources:
        node = activities.get(source.activity_node_id)
        if node is None:
            continue
        if binding is not None and (
            round_row is None
            or (binding.owner_user_id, binding.activity_node_id) != (owner, node.id)
            or (round_row.owner_user_id, round_row.activity_node_id) != (owner, node.id)
        ):
            raise DomainError(
                "CHALLENGE_HISTORY_INVALID",
                "Original source has foreign birth evidence",
            )
        head = (
            initial_round_head(node.public_id)
            if round_row is None
            else ChallengeRoundHead(
                activity_uuid=node.public_id,
                round_uuid=round_row.public_id,
                generation=round_row.generation,
            )
        )
        births.append(
            ChallengeBirth(entity_type=kind, entity_uuid=source.public_id, head=head)
        )
    return ChallengeMetadata(
        challenge_contract=1, checkpoints=checkpoints, births=births
    )


async def validate_legacy_replay(
    db: AsyncSession, owner: int, original, context: ChallengeSourceContext, status: str
) -> None:
    """An old accepted source is at most baseline; never infer the current round."""
    if status != "applied":
        return
    if isinstance(original, TimerCommandRequest):
        timer = (
            await db.execute(
                select(TimerSession).where(
                    col(TimerSession.owner_user_id) == owner,
                    col(TimerSession.public_id) == str(original.session_id),
                )
            )
        ).scalar_one_or_none()
        if timer is None:
            raise DomainError(
                "CHALLENGE_RECEIPT_INVALID", "Accepted timer source is missing"
            )
        node = require_internal(
            await db.get(PlanNode, timer.activity_node_id), "PlanNode"
        )
        actual = await _bound_head(
            db, node, await db.get(ActivityChallengeTimerBinding, timer.id)
        )
    elif original.entity_type == "activity_event":
        event = (
            await db.execute(
                select(ActivityEvent).where(
                    col(ActivityEvent.owner_user_id) == owner,
                    col(ActivityEvent.public_id) == str(original.entity_uuid),
                )
            )
        ).scalar_one_or_none()
        if event is None:
            raise DomainError(
                "CHALLENGE_RECEIPT_INVALID", "Accepted fact source is missing"
            )
        node = require_internal(
            await db.get(PlanNode, event.activity_node_id), "PlanNode"
        )
        detail = require_internal(
            await db.get(ActivityDetail, node.id), "ActivityDetail"
        )
        if detail.completion_policy != "recurring":
            _require_no_head(context)
            return
        actual = await _bound_head(
            db, node, await db.get(ActivityChallengeEventBinding, event.id)
        )
    else:
        # Original structural receipts prove a mutation, not a new fact birth.
        if context.head is not None and (
            original.entity_type != "plan_node"
            or context.head.activity_uuid != str(original.entity_uuid)
        ):
            raise DomainError(
                "CHALLENGE_BINDING_MISMATCH", "Legacy structure has a foreign context"
            )
        return
    if actual.generation != 0 or actual != context.head:
        raise DomainError(
            "CHALLENGE_BINDING_MISMATCH", "Legacy receipt cannot acquire a new birth"
        )
