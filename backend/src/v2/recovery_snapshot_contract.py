"""Read-only candidate evidence, never a recovery activation ticket."""

from typing import Literal

from pydantic import field_validator, model_validator

from src.v2.challenge_sync_contract import (
    RoundActiveTimerResponse,
    RoundSyncBootstrapResponse,
)
from src.v2.contract_types import ContractModel, PublicId, exact_integer
from src.v2.schemas import DeviceResponse, TimerSessionResponse


class ReplicaRecoveryPreviewResponse(ContractModel):
    recovery_contract: Literal[1]
    protocol_version: Literal[5]
    account_id: PublicId
    server_instance_id: PublicId
    sync_epoch: PublicId
    device: DeviceResponse
    snapshot: RoundSyncBootstrapResponse
    active_timer: TimerSessionResponse | None

    @field_validator("recovery_contract", "protocol_version", mode="before")
    @classmethod
    def integer_versions(cls, value):
        return exact_integer(value)

    @model_validator(mode="after")
    def proven_unfinished_timer(self):
        if self.active_timer is not None:
            if self.active_timer.state not in {"running", "paused"}:
                raise ValueError("preview active timer must be unfinished")
            RoundActiveTimerResponse.model_validate(
                {
                    "session": self.active_timer.model_dump(mode="json"),
                    "server_time": self.snapshot.server_time,
                    "challenge_contract": self.snapshot.challenge_contract,
                    "checkpoints": self.snapshot.checkpoints,
                    "births": self.snapshot.births,
                }
            )
            activities = {
                str(change.entity_uuid)
                for change in self.snapshot.changes
                if change.entity_type == "plan_node"
            }
            if str(self.active_timer.activity_uuid) not in activities:
                raise ValueError("active timer has no live activity in snapshot")
        return self
