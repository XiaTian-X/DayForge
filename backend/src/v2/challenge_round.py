"""D-018 round identities and causal contracts; NOT an API or write authority.

Storage must authenticate, resolve the owner's activity, replay original IDs,
validate the actual plan/timer/permissions, and commit CAS, records and logs
atomically. A valid serialized record does not prove any of those checks.
"""

from typing import Annotated
from uuid import UUID, uuid5

from pydantic import Field, model_validator

from src.v2.contract_types import ContractModel, PublicId

Generation = Annotated[int, Field(strict=True, ge=0, le=2_147_483_647)]
PlanRevision = Annotated[int, Field(strict=True, ge=1, le=9_223_372_036_854_775_807)]
INITIAL_ROUND_NAME = "dayforge.challenge.initial.v1"


def initial_round_uuid(activity_uuid: str) -> str:
    """Only the explicit initial baseline; never inferred from the current head."""
    if not isinstance(activity_uuid, str) or str(UUID(activity_uuid)) != activity_uuid:
        raise ValueError("activity identity must be a canonical UUID")
    return str(uuid5(UUID(activity_uuid), INITIAL_ROUND_NAME))


class ChallengeRoundHead(ContractModel):
    activity_uuid: PublicId
    round_uuid: PublicId
    generation: Generation

    @model_validator(mode="after")
    def baseline_is_unambiguous(self):
        if (self.generation == 0) != (
            self.round_uuid == initial_round_uuid(self.activity_uuid)
        ):
            raise ValueError("only generation zero has the initial baseline identity")
        return self


class ChallengeRestartIntent(ContractModel):
    activity_uuid: PublicId
    round_uuid: PublicId
    expected_round_uuid: PublicId
    expected_generation: Generation
    expected_plan_revision: PlanRevision

    @model_validator(mode="after")
    def new_identity_and_exact_predecessor(self):
        ChallengeRoundHead(
            activity_uuid=self.activity_uuid,
            round_uuid=self.expected_round_uuid,
            generation=self.expected_generation,
        )
        if self.round_uuid in (
            self.expected_round_uuid,
            initial_round_uuid(self.activity_uuid),
        ):
            raise ValueError("restart must create a fresh non-baseline round identity")
        return self


class ChallengeRoundRecord(ContractModel):
    """Immutable creation proof, not a claim of authenticated acceptance."""

    head: ChallengeRoundHead
    source_device_uuid: PublicId | None
    restart_operation_uuid: PublicId | None
    restart_intent: ChallengeRestartIntent | None

    @model_validator(mode="after")
    def creation_matches_head(self):
        if self.head.generation == 0:
            if any(
                value is not None
                for value in (
                    self.source_device_uuid,
                    self.restart_operation_uuid,
                    self.restart_intent,
                )
            ):
                raise ValueError("initial baseline does not invent a restart source")
        else:
            intent = self.restart_intent
            if (
                intent is None
                or self.source_device_uuid is None
                or self.restart_operation_uuid is None
                or intent.activity_uuid != self.head.activity_uuid
                or intent.round_uuid != self.head.round_uuid
                or intent.expected_generation + 1 != self.head.generation
            ):
                raise ValueError("round creation must bind the exact restart intent")
        return self


class ChallengeTransitionError(ValueError):
    """Stable domain code; the authenticated storage layer supplies context."""


def initial_round_head(activity_uuid: str) -> ChallengeRoundHead:
    return ChallengeRoundHead(
        activity_uuid=activity_uuid,
        round_uuid=initial_round_uuid(activity_uuid),
        generation=0,
    )


def advance_challenge(
    current: ChallengeRoundHead,
    intent: ChallengeRestartIntent,
    *,
    actual_plan_revision: int,
    has_unfinished_timer: bool = False,
    deleted: bool = False,
) -> ChallengeRoundHead:
    """Preflight only; durable SQL CAS and original operation replay remain required."""
    if deleted:
        raise ChallengeTransitionError("ENTITY_DELETED")
    if current.activity_uuid != intent.activity_uuid:
        raise ChallengeTransitionError("CHALLENGE_ACTIVITY_MISMATCH")
    if (
        current.generation != intent.expected_generation
        or current.round_uuid != intent.expected_round_uuid
    ):
        raise ChallengeTransitionError("CHALLENGE_STATE_CONFLICT")
    if (
        type(actual_plan_revision) is not int
        or actual_plan_revision != intent.expected_plan_revision
    ):
        raise ChallengeTransitionError("CHALLENGE_PLAN_CHANGED")
    if has_unfinished_timer:
        raise ChallengeTransitionError("CHALLENGE_TIMER_UNFINISHED")
    if current.generation == 2_147_483_647:
        raise ChallengeTransitionError("CHALLENGE_STATE_EXHAUSTED")
    if actual_plan_revision == 9_223_372_036_854_775_807:
        raise ChallengeTransitionError("CHALLENGE_PLAN_REVISION_EXHAUSTED")
    return ChallengeRoundHead(
        activity_uuid=current.activity_uuid,
        round_uuid=intent.round_uuid,
        generation=current.generation + 1,
    )


def apply_challenge_record(
    current: ChallengeRoundHead, incoming: ChallengeRoundRecord
) -> ChallengeRoundHead:
    """Advance one proven step, or keep the head on old-page delivery.

    Callers still compare old immutable records against durable sources; this
    function only manages the head and cannot authorize accepting a foreign fact.
    """
    head = incoming.head
    if head.activity_uuid != current.activity_uuid:
        raise ChallengeTransitionError("CHALLENGE_ACTIVITY_MISMATCH")
    if head.generation < current.generation:
        return current
    if head.generation == current.generation:
        if head != current:
            raise ChallengeTransitionError("CHALLENGE_STATE_DIVERGED")
        return current
    if head.generation != current.generation + 1:
        raise ChallengeTransitionError("CHALLENGE_CHAIN_INCOMPLETE")
    if incoming.restart_intent is None or (
        incoming.restart_intent.expected_round_uuid != current.round_uuid
    ):
        raise ChallengeTransitionError("CHALLENGE_STATE_DIVERGED")
    return head


def rebuild_challenge_history(
    activity_uuid: str, records: list[ChallengeRoundRecord]
) -> ChallengeRoundHead:
    """Complete bootstrap/archive chain; order independent, never time-based."""
    current = initial_round_head(activity_uuid)
    if any(record.head.activity_uuid != activity_uuid for record in records):
        raise ChallengeTransitionError("CHALLENGE_ACTIVITY_MISMATCH")
    ordered = sorted(records, key=lambda record: record.head.generation)
    if not ordered or ordered[0].head != current:
        raise ChallengeTransitionError("CHALLENGE_CHAIN_INCOMPLETE")
    seen_rounds: set[str] = set()
    seen_sources: set[tuple[str | None, str | None]] = set()
    for generation, record in enumerate(ordered):
        source = record.source_device_uuid, record.restart_operation_uuid
        if record.head.round_uuid in seen_rounds or (
            generation > 0 and source in seen_sources
        ):
            raise ChallengeTransitionError("CHALLENGE_HISTORY_INVALID")
        if record.head.generation != generation:
            raise ChallengeTransitionError("CHALLENGE_CHAIN_INCOMPLETE")
        current = apply_challenge_record(current, record)
        seen_rounds.add(record.head.round_uuid)
        seen_sources.add(source)
    return current
