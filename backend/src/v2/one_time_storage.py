"""Account-scoped v5 storage primitives, within the caller's sync transaction.

These primitives neither authenticate a device nor commit/acknowledge an operation.
The v5 mutation path must append the fact, journal and replay result in the same
transaction; the currently active v4 router does not call them.
"""

from dataclasses import dataclass

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col

from src.v2.errors import DomainError
from src.v2.models import ActivityDetail, ActivityEvent, PlanNode
from src.v2.one_time import (
    OneTimeIntent,
    OneTimeState,
    OneTimeTransitionError,
    advance_one_time,
)
from src.v2.one_time_sync import (
    OneTimeEventProof,
    OneTimeProjection,
    validate_one_time_binding,
)


@dataclass(frozen=True)
class StoredOneTimeActivity:
    node_id: int
    projection: OneTimeProjection


class OneTimeStateConflict(DomainError):
    def __init__(self, code: str, projection: OneTimeProjection):
        super().__init__(
            code, "One-time item state does not accept this intent", conflict=True
        )
        self.projection = projection


async def load_one_time_activity(
    session: AsyncSession, user_id: int, activity_uuid: str
) -> StoredOneTimeActivity:
    # Select values rather than ORM identities: a CAS retry must not return a
    # cached pre-update object from the session's identity map.
    result = await session.execute(
        select(
            col(PlanNode.id),
            col(PlanNode.public_id),
            col(PlanNode.deleted_at),
            col(ActivityDetail.completion_policy),
            col(ActivityDetail.tracking_mode),
            col(ActivityDetail.one_time_version),
            col(ActivityDetail.one_time_head_event_uuid),
            col(ActivityDetail.one_time_completion_event_uuid),
        )
        .join(ActivityDetail, col(ActivityDetail.node_id) == col(PlanNode.id))
        .where(
            col(PlanNode.owner_user_id) == user_id,
            col(PlanNode.public_id) == activity_uuid,
            col(PlanNode.node_kind) == "activity",
        )
    )
    row = result.one_or_none()
    if row is None:
        raise DomainError("ACTIVITY_NOT_FOUND", "Activity was not found")
    node_id, public_id, deleted_at, policy, tracking, version, head, completion = row
    if deleted_at is not None:
        raise DomainError("ENTITY_DELETED", "Activity has been deleted")
    if policy != "one_and_done" or tracking != "check":
        raise DomainError(
            "INVALID_PAYLOAD", "One-time intent requires a one-time check"
        )
    if version is None:
        raise DomainError(
            "TASK_STATE_UNINITIALIZED",
            "Pre-v5 activity requires the coordinated data baseline",
        )
    if node_id is None:
        raise RuntimeError("stored activity has no internal identity")
    return StoredOneTimeActivity(
        node_id,
        OneTimeProjection(
            activity_uuid=public_id,
            state=OneTimeState(
                version=version, head_event_uuid=head, completion_event_uuid=completion
            ),
        ),
    )


async def advance_stored_one_time(
    session: AsyncSession, user_id: int, activity_uuid: str, intent: OneTimeIntent
) -> OneTimeProjection:
    """CAS only; the caller must append the immutable fact before committing."""
    current = await load_one_time_activity(session, user_id, activity_uuid)
    existing = await session.execute(
        select(col(ActivityEvent.id)).where(
            col(ActivityEvent.owner_user_id) == user_id,
            col(ActivityEvent.public_id) == intent.event_uuid,
        )
    )
    if existing.scalar_one_or_none() is not None:
        raise DomainError(
            "ENTITY_ALREADY_EXISTS", "An event with this UUID already exists"
        )
    state = current.projection.state
    try:
        after = advance_one_time(state, intent)
    except OneTimeTransitionError as exc:
        raise OneTimeStateConflict(str(exc), current.projection) from exc
    owned_live = select(col(PlanNode.id)).where(
        col(PlanNode.owner_user_id) == user_id,
        col(PlanNode.id) == current.node_id,
        col(PlanNode.deleted_at).is_(None),
    )
    result = await session.execute(
        update(ActivityDetail)
        .where(
            col(ActivityDetail.node_id).in_(owned_live),
            col(ActivityDetail.completion_policy) == "one_and_done",
            col(ActivityDetail.tracking_mode) == "check",
            col(ActivityDetail.one_time_version) == state.version,
            col(ActivityDetail.one_time_head_event_uuid) == state.head_event_uuid,
            col(ActivityDetail.one_time_completion_event_uuid)
            == state.completion_event_uuid,
        )
        .values(
            one_time_version=after.version,
            one_time_head_event_uuid=after.head_event_uuid,
            one_time_completion_event_uuid=after.completion_event_uuid,
        )
        .returning(col(ActivityDetail.node_id))
        .execution_options(synchronize_session="fetch")
    )
    if result.scalar_one_or_none() is None:
        latest = await load_one_time_activity(session, user_id, activity_uuid)
        raise OneTimeStateConflict("TASK_STATE_CONFLICT", latest.projection)
    return OneTimeProjection(activity_uuid=activity_uuid, state=after)


def capture_one_time_intent(
    event: ActivityEvent, intent: OneTimeIntent, reverts_event_uuid: str | None
) -> None:
    if (
        event.id is not None
        or event.one_time_expected_version is not None
        or event.one_time_expected_head_event_uuid is not None
    ):
        raise ValueError("cannot rewrite a stored event intent")
    validate_one_time_binding(
        entity_uuid=event.public_id,
        event_type=event.event_type,
        reverts_event_uuid=reverts_event_uuid,
        intent=intent,
        completion_policy="one_and_done",
    )
    event.one_time_expected_version = intent.expected_version
    event.one_time_expected_head_event_uuid = intent.expected_head_event_uuid


def stored_event_proof(
    event: ActivityEvent, activity_uuid: str, reverts_event_uuid: str | None
) -> OneTimeEventProof | None:
    version = event.one_time_expected_version
    if version is None:
        if event.one_time_expected_head_event_uuid is not None:
            raise RuntimeError("stored event has partial one-time preconditions")
        return None
    intent = OneTimeIntent(
        event_uuid=event.public_id,
        action="complete" if event.event_type == "check_in" else "undo",
        expected_version=version,
        expected_head_event_uuid=event.one_time_expected_head_event_uuid,
        reverts_event_uuid=reverts_event_uuid,
    )
    return OneTimeEventProof.model_validate(
        {
            "public_id": event.public_id,
            "activity_uuid": activity_uuid,
            "event_type": event.event_type,
            "reverts_event_uuid": reverts_event_uuid,
            "one_time": intent,
            "one_time_state_after": OneTimeState(
                version=version + 1,
                head_event_uuid=event.public_id,
                completion_event_uuid=event.public_id
                if intent.action == "complete"
                else None,
            ),
        }
    )
