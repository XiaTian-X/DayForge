"""Forward one-time sync rules. No v4 route or persistence is enabled here.

Authenticated callers own account/epoch scoping and atomic persistence. A proof
is the one-time portion of an immutable activity-event snapshot, not a new API.
"""

from typing import Literal

from pydantic import model_validator

from src.v2.contract_types import ContractModel, PublicId
from src.v2.one_time import (
    OneTimeIntent,
    OneTimeState,
    OneTimeTransitionError,
    advance_one_time,
)
from src.v2.schemas import ActivityEventPayload


def validate_one_time_binding(
    *,
    entity_uuid: str,
    event_type: str,
    reverts_event_uuid: str | None,
    intent: OneTimeIntent | None,
    completion_policy: str,
) -> None:
    """Bind outer event identity/type to the intent, using the stored policy."""
    if completion_policy == "recurring":
        if intent is not None:
            raise OneTimeTransitionError("INVALID_PAYLOAD")
        return
    if (
        completion_policy != "one_and_done"
        or intent is None
        or intent.event_uuid != entity_uuid
        or event_type != ("check_in" if intent.action == "complete" else "revert")
        or reverts_event_uuid != intent.reverts_event_uuid
    ):
        raise OneTimeTransitionError("INVALID_PAYLOAD")


class NextActivityEventPayload(ActivityEventPayload):
    """Existing UTC/source rules plus explicit intent; DB policy is checked later."""

    one_time: OneTimeIntent | None = None

    @model_validator(mode="after")
    def valid_one_time_shape(self):
        if self.one_time is not None:
            validate_one_time_binding(
                entity_uuid=self.one_time.event_uuid,
                event_type=self.event_type,
                reverts_event_uuid=(
                    str(self.reverts_event_uuid) if self.reverts_event_uuid else None
                ),
                intent=self.one_time,
                completion_policy="one_and_done",
            )
            if any(
                value is not None
                for value in (
                    self.duration_seconds,
                    self.duration_milliseconds,
                    self.started_at,
                    self.ended_at,
                )
            ) or (self.event_type == "revert" and self.value is not None):
                raise ValueError("one-time checks cannot contain count/timer results")
        return self


def _expected_state(intent: OneTimeIntent) -> OneTimeState:
    return OneTimeState(
        version=intent.expected_version,
        head_event_uuid=intent.expected_head_event_uuid,
        completion_event_uuid=(
            intent.expected_head_event_uuid if intent.expected_version % 2 else None
        ),
    )


class OneTimeEventProof(ContractModel):
    public_id: PublicId
    activity_uuid: PublicId
    event_type: Literal["check_in", "revert"]
    reverts_event_uuid: PublicId | None
    one_time: OneTimeIntent
    one_time_state_after: OneTimeState

    @model_validator(mode="after")
    def valid_transition(self):
        validate_one_time_binding(
            entity_uuid=self.public_id,
            event_type=self.event_type,
            reverts_event_uuid=self.reverts_event_uuid,
            intent=self.one_time,
            completion_policy="one_and_done",
        )
        expected = advance_one_time(_expected_state(self.one_time), self.one_time)
        if expected != self.one_time_state_after:
            raise ValueError("state_after does not describe this immutable event")
        return self


class OneTimeProjection(ContractModel):
    """Also the shape of result.one_time_conflict; not an activity_event entity."""

    activity_uuid: PublicId
    state: OneTimeState


def merge_one_time_projection(
    current: OneTimeProjection, incoming: OneTimeProjection, *, deleted: bool = False
) -> OneTimeProjection:
    if deleted:
        raise OneTimeTransitionError("ENTITY_DELETED")
    if current.activity_uuid != incoming.activity_uuid:
        raise OneTimeTransitionError("TASK_ACTIVITY_MISMATCH")
    if incoming.state.version < current.state.version:
        return current
    if incoming.state.version == current.state.version:
        if incoming.state != current.state:
            raise OneTimeTransitionError("TASK_STATE_DIVERGED")
        return current
    return incoming


def rebuild_one_time_history(
    checkpoint: OneTimeProjection, events: list[OneTimeEventProof]
) -> OneTimeProjection:
    """Validate the complete bootstrap history before activating its projection."""
    activity_uuid = checkpoint.activity_uuid
    projection = OneTimeProjection(
        activity_uuid=activity_uuid,
        state=OneTimeState(version=0, head_event_uuid=None, completion_event_uuid=None),
    )
    seen: set[str] = set()
    for event in sorted(events, key=lambda item: item.one_time_state_after.version):
        if event.activity_uuid != activity_uuid:
            raise OneTimeTransitionError("TASK_ACTIVITY_MISMATCH")
        if event.public_id in seen:
            raise OneTimeTransitionError("TASK_EVENT_ID_REUSED")
        seen.add(event.public_id)
        expected_version = projection.state.version + 1
        if event.one_time_state_after.version > expected_version:
            raise OneTimeTransitionError("TASK_HISTORY_INCOMPLETE")
        if event.one_time_state_after.version < expected_version:
            raise OneTimeTransitionError("TASK_STATE_DIVERGED")
        state = advance_one_time(projection.state, event.one_time)
        projection = OneTimeProjection(activity_uuid=activity_uuid, state=state)
    if projection.state.version < checkpoint.state.version:
        raise OneTimeTransitionError("TASK_HISTORY_INCOMPLETE")
    if projection.state != checkpoint.state:
        raise OneTimeTransitionError("TASK_STATE_DIVERGED")
    return projection


class PendingOneTimeIntent(ContractModel):
    operation_id: PublicId
    intent: OneTimeIntent


class OneTimeQueueView(ContractModel):
    optimistic_state: OneTimeState
    awaiting_replay_operation_ids: list[PublicId]
    blocked_operation_ids: list[PublicId]


def project_pending_one_time(
    confirmed: OneTimeState,
    pending: list[PendingOneTimeIntent],
    *,
    rejected_operation_id: str | None = None,
) -> OneTimeQueueView:
    """Never acknowledges, removes, rewrites or re-bases a frozen operation.

    A newer confirmed base may already contain a pending operation whose response
    was lost. Mismatch alone is therefore awaiting_replay, NOT a server rejection.
    The transport retries the original IDs; only explicit rejection quarantines
    a causal suffix. Call once per authenticated account/epoch/activity queue.
    """
    operation_ids = [item.operation_id for item in pending]
    event_ids = [item.intent.event_uuid for item in pending]
    if (
        len(set(operation_ids)) != len(operation_ids)
        or len(set(event_ids)) != len(event_ids)
        or (
            rejected_operation_id is not None
            and rejected_operation_id not in operation_ids
        )
    ):
        raise OneTimeTransitionError("INVALID_PENDING_CHAIN")
    previous: OneTimeState | None = None
    for item in pending:
        before = _expected_state(item.intent)
        if previous is not None and before != previous:
            raise OneTimeTransitionError("INVALID_PENDING_CHAIN")
        try:
            previous = advance_one_time(before, item.intent)
        except OneTimeTransitionError as exc:
            raise OneTimeTransitionError("INVALID_PENDING_CHAIN") from exc
    rejected_index = (
        operation_ids.index(rejected_operation_id)
        if rejected_operation_id is not None
        else len(pending)
    )
    state = confirmed
    for index, item in enumerate(pending):
        if item.operation_id == rejected_operation_id:
            return OneTimeQueueView(
                optimistic_state=state,
                awaiting_replay_operation_ids=[],
                blocked_operation_ids=operation_ids[index:],
            )
        if _expected_state(item.intent) != state:
            return OneTimeQueueView(
                optimistic_state=state,
                awaiting_replay_operation_ids=operation_ids[index:rejected_index],
                blocked_operation_ids=operation_ids[rejected_index:],
            )
        state = advance_one_time(state, item.intent)
    return OneTimeQueueView(
        optimistic_state=state,
        awaiting_replay_operation_ids=[],
        blocked_operation_ids=[],
    )
