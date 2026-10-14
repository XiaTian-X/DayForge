"""Explicit v5 challenge profile; original requests remain byte/hash compatible."""

from typing import Literal
from uuid import UUID

from pydantic import Field, StrictBool, field_validator, model_validator

from src.v2.challenge_round import (
    ChallengeRestartIntent,
    ChallengeRoundHead,
    ChallengeRoundRecord,
    rebuild_challenge_history,
)
from src.v2.contract_types import ContractModel, PublicId
from src.v2.next_sync_contract import (
    NextSyncBootstrapResponse,
    NextSyncPullResponse,
    NextSyncPushResponse,
)
from src.v2.schemas import (
    ApiModel,
    SyncOperationRequest,
    TimerCommandBatchRequest,
    TimerCommandBatchResponse,
    ActiveTimerResponse,
)


class ChallengeRestartOperation(ApiModel):
    operation_id: UUID
    entity_type: Literal["challenge_round"]
    entity_uuid: UUID
    action: Literal["upsert"]
    base_revision: None = None
    payload: ChallengeRestartIntent

    @model_validator(mode="after")
    def exact_identity(self):
        if str(self.entity_uuid) != self.payload.round_uuid:
            raise ValueError("restart identity must match the new round")
        return self


RoundOperation = SyncOperationRequest | ChallengeRestartOperation


class ChallengeSourceContext(ContractModel):
    source_uuid: PublicId
    head: ChallengeRoundHead | None
    legacy_initial: StrictBool = False
    affected_heads: list[ChallengeRoundHead] = Field(
        default_factory=list, max_length=1000
    )

    @model_validator(mode="after")
    def legacy_is_only_initial(self):
        if self.legacy_initial and self.head is not None and self.head.generation != 0:
            raise ValueError("legacy sources can only claim the initial baseline")
        identities = [head.activity_uuid for head in self.affected_heads]
        if len(set(identities)) != len(identities) or (
            self.legacy_initial
            and any(head.generation != 0 for head in self.affected_heads)
        ):
            raise ValueError("affected activities require unique original heads")
        return self


class ChallengeProfile(ApiModel):
    challenge_contract: Literal[1]

    @field_validator("challenge_contract", mode="before")
    @classmethod
    def exact_contract(cls, value):
        if type(value) is not int or value != 1:
            raise ValueError("explicit integer challenge contract 1 is required")
        return value


def _check_contexts(sources: list[str], contexts: list[ChallengeSourceContext]):
    ids = [context.source_uuid for context in contexts]
    if (
        len(set(sources)) != len(sources)
        or len(set(ids)) != len(ids)
        or set(ids) != set(sources)
    ):
        raise ValueError("every original source requires exactly one context")


class RoundSyncPushRequest(ChallengeProfile):
    device_id: UUID
    operations: list[RoundOperation] = Field(min_length=1, max_length=100)
    contexts: list[ChallengeSourceContext] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def exact_sources(self):
        _check_contexts(
            [str(item.operation_id) for item in self.operations], self.contexts
        )
        for item in self.operations:
            if isinstance(item, ChallengeRestartOperation):
                context = self.context_for(str(item.operation_id))
                if (
                    context.head is not None
                    or context.legacy_initial
                    or context.affected_heads
                ):
                    raise ValueError("restart carries its own immutable predecessor")
        return self

    def context_for(self, source_uuid: str) -> ChallengeSourceContext:
        return next(item for item in self.contexts if item.source_uuid == source_uuid)


class RoundTimerCommandBatchRequest(TimerCommandBatchRequest, ChallengeProfile):
    contexts: list[ChallengeSourceContext] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def exact_sources(self):
        _check_contexts([str(item.command_id) for item in self.commands], self.contexts)
        return self

    def context_for(self, source_uuid: str) -> ChallengeSourceContext:
        return next(item for item in self.contexts if item.source_uuid == source_uuid)


class ChallengeCheckpoint(ContractModel):
    head: ChallengeRoundHead
    records: list[ChallengeRoundRecord] = Field(min_length=1)

    @model_validator(mode="after")
    def complete_chain(self):
        if (
            rebuild_challenge_history(self.head.activity_uuid, self.records)
            != self.head
        ):
            raise ValueError("checkpoint must prove the complete current head")
        return self


class ChallengeBirth(ContractModel):
    entity_type: Literal["activity_event", "timer_session"]
    entity_uuid: PublicId
    head: ChallengeRoundHead


class ChallengeMetadata(ChallengeProfile):
    checkpoints: list[ChallengeCheckpoint]
    births: list[ChallengeBirth]

    @model_validator(mode="after")
    def unique_and_proven(self):
        heads = {item.head.activity_uuid: item for item in self.checkpoints}
        if len(heads) != len(self.checkpoints):
            raise ValueError("duplicate activity checkpoint")
        ids = [(item.entity_type, item.entity_uuid) for item in self.births]
        if len(set(ids)) != len(ids):
            raise ValueError("duplicate fact birth")
        for birth in self.births:
            checkpoint = heads.get(birth.head.activity_uuid)
            if checkpoint is None or not any(
                item.head == birth.head for item in checkpoint.records
            ):
                raise ValueError("birth has no proven checkpoint")
        return self


class RoundSyncPushResponse(NextSyncPushResponse, ChallengeMetadata):
    @model_validator(mode="after")
    def original_acknowledgements(self):
        records = {
            record.head.round_uuid: record
            for checkpoint in self.checkpoints
            for record in checkpoint.records
        }
        for result in self.results:
            if result.status not in {"applied", "already_applied"}:
                continue
            if result.entity_type == "challenge_round":
                record = ChallengeRoundRecord.model_validate(result.entity)
                if (
                    records.get(str(result.entity_uuid)) != record
                    or record.restart_operation_uuid != str(result.operation_id)
                    or result.revision != 1
                ):
                    raise ValueError(
                        "restart acknowledgement has no original checkpoint"
                    )
            elif (
                result.entity_type == "activity_event"
                and (result.entity or {}).get("one_time") is None
            ):
                _require_birth(
                    self,
                    "activity_event",
                    str(result.entity_uuid),
                    (result.entity or {}).get("activity_uuid"),
                )
        return self


class RoundSyncPullResponse(NextSyncPullResponse, ChallengeMetadata):
    @model_validator(mode="after")
    def proven_changes(self):
        _validate_changes(self)
        return self


class RoundSyncBootstrapResponse(NextSyncBootstrapResponse, ChallengeMetadata):
    @model_validator(mode="after")
    def complete_births(self):
        _validate_changes(self)
        return self


class RoundTimerCommandBatchResponse(TimerCommandBatchResponse, ChallengeMetadata):
    @model_validator(mode="after")
    def session_births(self):
        for result in self.results:
            if result.session is not None:
                _require_birth(
                    self,
                    "timer_session",
                    str(result.session.session_id),
                    str(result.session.activity_uuid),
                    mandatory=True,
                )
        return self


class RoundActiveTimerResponse(ActiveTimerResponse, ChallengeMetadata):
    @model_validator(mode="after")
    def session_birth(self):
        if self.session is not None:
            _require_birth(
                self,
                "timer_session",
                str(self.session.session_id),
                str(self.session.activity_uuid),
                mandatory=True,
            )
        return self


def _require_birth(
    metadata: ChallengeMetadata,
    kind: Literal["activity_event", "timer_session"],
    identity: str,
    activity: str | None,
    *,
    mandatory: bool = False,
):
    if not mandatory and not any(
        item.head.activity_uuid == activity for item in metadata.checkpoints
    ):
        return  # One-time items carry their separate immutable proof.
    if not any(
        item.entity_type == kind
        and item.entity_uuid == identity
        and item.head.activity_uuid == activity
        for item in metadata.births
    ):
        raise ValueError("returned source requires its original birth")


def _validate_changes(response):
    records = {
        record.head.round_uuid: record
        for checkpoint in response.checkpoints
        for record in checkpoint.records
    }
    for change in response.changes:
        if change.entity_type == "challenge_round":
            record = ChallengeRoundRecord.model_validate(change.payload)
            if (
                change.operation != "upsert"
                or change.revision != 1
                or records.get(str(change.entity_uuid)) != record
                or record.source_device_uuid
                != (
                    str(change.origin_device_id)
                    if change.origin_device_id is not None
                    else None
                )
            ):
                raise ValueError("immutable round change does not match its source")
        elif (
            change.entity_type == "activity_event"
            and change.payload.get("one_time") is None
        ):
            _require_birth(
                response,
                "activity_event",
                str(change.entity_uuid),
                change.payload.get("activity_uuid"),
            )
