"""Protocol-next one-time check contract; not enabled by the v4 sync router yet.

This reducer has no storage or permission authority. Callers must authenticate,
resolve the account's activity, replay operation IDs, and atomically persist the
returned projection together with its immutable event and outbox/change log.
"""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


PublicId = Annotated[
    str,
    Field(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
]
StateVersion = Annotated[int, Field(strict=True, ge=0, le=2_147_483_647)]


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


class OneTimeState(ContractModel):
    version: StateVersion
    head_event_uuid: PublicId | None
    completion_event_uuid: PublicId | None

    @model_validator(mode="after")
    def consistent_projection(self):
        if (self.version == 0) != (self.head_event_uuid is None):
            raise ValueError("only the initial state has no head event")
        completed = self.completion_event_uuid is not None
        if completed != (self.version % 2 == 1):
            raise ValueError("complete and undo must alternate")
        if completed and self.completion_event_uuid != self.head_event_uuid:
            raise ValueError("completion must be the current head event")
        return self


class OneTimeIntent(ContractModel):
    event_uuid: PublicId
    action: Literal["complete", "undo"]
    expected_version: StateVersion
    expected_head_event_uuid: PublicId | None
    reverts_event_uuid: PublicId | None

    @model_validator(mode="after")
    def consistent_intent(self):
        if (self.expected_version == 0) != (self.expected_head_event_uuid is None):
            raise ValueError("initial precondition must have no head")
        if (self.action == "undo") != (self.reverts_event_uuid is not None):
            raise ValueError("only undo must target a completion event")
        if self.event_uuid in (
            self.expected_head_event_uuid,
            self.reverts_event_uuid,
        ):
            raise ValueError("each transition requires a new event identity")
        return self


class OneTimeTransitionError(ValueError):
    """Stable error code; the authenticated caller supplies the current state."""


def advance_one_time(
    state: OneTimeState, intent: OneTimeIntent, *, deleted: bool = False
) -> OneTimeState:
    if deleted:
        raise OneTimeTransitionError("ENTITY_DELETED")
    if (
        intent.expected_version != state.version
        or intent.expected_head_event_uuid != state.head_event_uuid
    ):
        raise OneTimeTransitionError("TASK_STATE_CONFLICT")
    if state.version == 2_147_483_647:
        raise OneTimeTransitionError("TASK_STATE_EXHAUSTED")
    if intent.action == "complete":
        if state.completion_event_uuid is not None:
            raise OneTimeTransitionError("TASK_ALREADY_COMPLETED")
        completion = intent.event_uuid
    else:
        if state.completion_event_uuid != intent.reverts_event_uuid:
            raise OneTimeTransitionError("TASK_COMPLETION_MISMATCH")
        completion = None
    return OneTimeState(
        version=state.version + 1,
        head_event_uuid=intent.event_uuid,
        completion_event_uuid=completion,
    )
