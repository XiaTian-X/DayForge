"""Protocol-v5 envelopes and payloads; not mounted on the live v4 routes.

Entity storage still owns authorization, references, CAS and atomicity. Envelope
validation cannot establish those guarantees from self-declared response data.
"""

from typing import Literal

from pydantic import StrictBool, model_validator

from src.v2.appearance import ObjectAppearance, RoleIcon, icon_allowed
from src.v2.one_time import OneTimeIntent
from src.v2.one_time_sync import (
    NextActivityEventPayload,
    OneTimeEventProof,
    OneTimeProjection,
    rebuild_one_time_history,
    validate_one_time_binding,
)
from src.v2.schemas import (
    ApiModel,
    ActivityPayload,
    MetricCore,
    PlanNodeCore,
    SyncBootstrapResponse,
    SyncChangeResponse,
    SyncOperationRequest,
    SyncOperationResult,
    SyncPullResponse,
    SyncPushRequest,
)


class NextActivityPayload(ActivityPayload):
    completion_policy: Literal["recurring", "one_and_done"]
    is_countdown: StrictBool = False

    @model_validator(mode="after")
    def once_is_a_single_check(self):
        if self.completion_policy == "one_and_done" and (
            self.tracking_mode != "check"
            or self.target_value != 1
            or self.target_unit is not None
            or self.target_cycles is not None
            or self.failure_policy.type != "loose"
            or self.preferred_local_time is not None
            or self.origin_assignment_id is not None
        ):
            raise ValueError("one-time items are single checks, not scheduled habits")
        return self


class NextPlanNodePayload(PlanNodeCore):
    appearance: ObjectAppearance
    activity: NextActivityPayload | None = None

    @model_validator(mode="after")
    def appearance_role_matches_policy(self):
        once = (
            self.activity is not None
            and self.activity.completion_policy == "one_and_done"
        )
        if isinstance(self.appearance.icon, RoleIcon) and not icon_allowed(
            self.appearance.icon, one_time=once
        ):
            raise ValueError("task roles are reserved for one-time items")
        return self


class NextMetricPayload(MetricCore):
    appearance: ObjectAppearance

    @model_validator(mode="after")
    def no_task_role(self):
        if isinstance(self.appearance.icon, RoleIcon) and not icon_allowed(
            self.appearance.icon, one_time=False
        ):
            raise ValueError("metrics cannot use task roles")
        return self


class NextSyncPushRequest(SyncPushRequest):
    """Envelope only: invalid domain payloads receive per-operation rejections."""


def validate_next_sync_operation(operation: SyncOperationRequest) -> None:
    """Run after replay/authorization within the operation savepoint, not routing."""
    if operation.action != "upsert":
        return  # Existing delete-policy validation remains in the service.
    if operation.entity_type == "plan_node":
        NextPlanNodePayload.model_validate(operation.payload)
    elif operation.entity_type == "metric":
        NextMetricPayload.model_validate(operation.payload)
    elif operation.entity_type == "activity_event":
        payload = NextActivityEventPayload.model_validate(operation.payload)
        if payload.one_time is not None:
            validate_one_time_binding(
                entity_uuid=str(operation.entity_uuid),
                event_type=payload.event_type,
                reverts_event_uuid=str(payload.reverts_event_uuid)
                if payload.reverts_event_uuid
                else None,
                intent=payload.one_time,
                completion_policy="one_and_done",
            )


TASK_STATE_ERRORS = frozenset(
    {
        "TASK_STATE_CONFLICT",
        "TASK_ALREADY_COMPLETED",
        "TASK_COMPLETION_MISMATCH",
        "TASK_STATE_EXHAUSTED",
    }
)
PROOF_FIELDS = tuple(OneTimeEventProof.model_fields)


def event_proof(payload: dict, entity_uuid: str) -> OneTimeEventProof | None:
    intent = payload.get("one_time")
    after = payload.get("one_time_state_after")
    if intent is None and after is None:
        return None
    proof = OneTimeEventProof.model_validate(
        {key: payload.get(key) for key in PROOF_FIELDS}
    )
    if proof.public_id != entity_uuid:
        raise ValueError("outer identity does not match event proof")
    return proof


class NextSyncOperationResult(SyncOperationResult):
    one_time_conflict: OneTimeProjection | None = None

    @model_validator(mode="after")
    def validate_task_result(self):
        state_error = (
            self.entity_type == "activity_event"
            and self.error_code in TASK_STATE_ERRORS
        )
        if state_error != (self.one_time_conflict is not None):
            raise ValueError("task state rejection must carry its separate context")
        if self.one_time_conflict is not None and (
            self.status not in {"conflict", "rejected"}
            or self.entity is not None
            or self.revision is not None
            or self.base_entity is not None
            or self.local_entity is not None
        ):
            raise ValueError(
                "a rejected new task event has no successful entity/revision"
            )
        if self.entity_type == "activity_event" and self.entity is not None:
            event_proof(self.entity, str(self.entity_uuid))
        return self


def validate_task_result_binding(
    operation: SyncOperationRequest, result: NextSyncOperationResult
) -> None:
    if (operation.operation_id, operation.entity_type, operation.entity_uuid) != (
        result.operation_id,
        result.entity_type,
        result.entity_uuid,
    ):
        raise ValueError("response does not acknowledge this operation")
    if result.one_time_conflict is not None and (
        result.one_time_conflict.activity_uuid != operation.payload.get("activity_uuid")
        or operation.payload.get("one_time") is None
    ):
        raise ValueError("task context belongs to another activity/operation")
    intent = operation.payload.get("one_time")
    if intent is not None and result.status in {"applied", "already_applied"}:
        proof = event_proof(result.entity or {}, str(operation.entity_uuid))
        if (
            proof is None
            or proof.activity_uuid != operation.payload.get("activity_uuid")
            or proof.one_time != OneTimeIntent.model_validate(intent)
            or result.error_code is not None
        ):
            raise ValueError(
                "successful result does not prove the submitted task intent"
            )


class NextSyncPushResponse(ApiModel):
    results: list[NextSyncOperationResult]


def validate_change_proof(change: SyncChangeResponse) -> OneTimeEventProof | None:
    if change.entity_type == "activity_event" and change.operation == "upsert":
        return event_proof(change.payload, str(change.entity_uuid))
    return None


class NextSyncPullResponse(SyncPullResponse):
    @model_validator(mode="after")
    def immutable_proofs(self):
        for change in self.changes:
            validate_change_proof(change)
        return self


class NextSyncBootstrapResponse(SyncBootstrapResponse):
    one_time_checkpoints: list[OneTimeProjection]

    @model_validator(mode="after")
    def complete_task_snapshot(self):
        once: set[str] = set()
        activities: set[str] = set()
        seen: set[tuple[str, str]] = set()
        for change in self.changes:
            identity = (change.entity_type, str(change.entity_uuid))
            if (
                identity in seen
                or change.operation != "upsert"
                or change.sequence != 0
                or change.payload.get("deleted_at") is not None
            ):
                raise ValueError("bootstrap requires unique live synthetic upserts")
            seen.add(identity)
            if change.entity_type in {"plan_node", "metric"}:
                read_only = {"public_id", "revision", "updated_at", "deleted_at"}
                if change.entity_type == "metric":
                    read_only.add("created_at")
                body = {
                    key: value
                    for key, value in change.payload.items()
                    if key not in read_only
                }
                if change.entity_type == "plan_node":
                    plan = NextPlanNodePayload.model_validate(body)
                    if plan.activity is not None:
                        activities.add(identity[1])
                        if plan.activity.completion_policy == "one_and_done":
                            once.add(identity[1])
                else:
                    NextMetricPayload.model_validate(body)
        checkpoints = {item.activity_uuid: item for item in self.one_time_checkpoints}
        if (
            len(checkpoints) != len(self.one_time_checkpoints)
            or set(checkpoints) != once
        ):
            raise ValueError(
                "every visible one-time item requires exactly one checkpoint"
            )
        histories: dict[str, list[OneTimeEventProof]] = {key: [] for key in once}
        for change in self.changes:
            if change.entity_type != "activity_event":
                continue
            activity_id = change.payload.get("activity_uuid")
            if activity_id not in activities:
                raise ValueError("bootstrap fact has no visible activity")
            proof = validate_change_proof(change)
            if (activity_id in once) != (proof is not None):
                raise ValueError("fact intent does not match stored activity policy")
            if proof is not None:
                histories[proof.activity_uuid].append(proof)
        for activity_identity, checkpoint in checkpoints.items():
            rebuild_one_time_history(checkpoint, histories[activity_identity])
        return self
