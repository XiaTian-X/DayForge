"""Read-only candidate evidence, never a recovery activation ticket."""

from typing import Literal

from pydantic import Field, field_validator, model_validator

from src.v2.appearance import AssetIcon, IconPack, ObjectAppearance
from src.v2.asset_api_contract import AssetRecord, AssetSyncContext

from src.v2.challenge_sync_contract import (
    RoundActiveTimerResponse,
    RoundSyncBootstrapResponse,
)
from src.v2.contract_types import ContractModel, PublicId, exact_integer
from src.v2.schemas import DeviceResponse, TimerSessionResponse


class RecoveryPackVersion(ContractModel):
    pack_id: PublicId
    revision: int = Field(ge=1, le=2_147_483_647)


class ReplicaRecoveryMaterialPreview(ContractModel):
    """Metadata gaps only, not native bytes, selection or activation authority."""

    material_contract: Literal[1]
    requested_pack: RecoveryPackVersion | None
    pack: IconPack | None
    assets: list[AssetRecord] = Field(max_length=50_000)
    missing_assets: list[PublicId] = Field(max_length=50_000)

    @field_validator("material_contract", mode="before")
    @classmethod
    def integer_version(cls, value):
        return exact_integer(value)

    @model_validator(mode="after")
    def exact_pack_and_partition(self):
        if self.pack is not None and (
            self.requested_pack is None
            or self.pack.pack_id != self.requested_pack.pack_id
            or self.pack.revision != self.requested_pack.revision
        ):
            raise ValueError("preview cannot substitute another pack/version")
        present = [str(record.asset.asset_id) for record in self.assets]
        missing = list(self.missing_assets)
        if present != sorted(set(present)) or missing != sorted(set(missing)):
            raise ValueError("preview asset identities must be sorted and unique")
        if set(present) & set(missing):
            raise ValueError("preview asset cannot be both present and missing")
        if self.pack is not None:
            known = {str(record.asset.asset_id): record.asset for record in self.assets}
            if any(
                known.get(str(asset.asset_id), asset) != asset
                for asset in self.pack.assets
            ):
                raise ValueError("preview pack and asset metadata disagree")
        return self


def recovery_material_ids(
    snapshot: RoundSyncBootstrapResponse, pack: IconPack | None
) -> set[str]:
    required = {str(asset.asset_id) for asset in pack.assets} if pack else set()
    for change in snapshot.changes:
        if change.entity_type in {"plan_node", "metric"}:
            appearance = ObjectAppearance.model_validate(change.payload["appearance"])
            if isinstance(appearance.icon, AssetIcon):
                required.add(str(appearance.icon.asset_id))
    return required


class ReplicaRecoveryPreviewResponse(ContractModel):
    recovery_contract: Literal[1]
    protocol_version: Literal[5]
    account_id: PublicId
    server_instance_id: PublicId
    sync_epoch: PublicId
    device: DeviceResponse
    snapshot: RoundSyncBootstrapResponse
    active_timer: TimerSessionResponse | None
    material_preview: ReplicaRecoveryMaterialPreview | None = None

    @field_validator("recovery_contract", "protocol_version", mode="before")
    @classmethod
    def integer_versions(cls, value):
        return exact_integer(value)

    @model_validator(mode="after")
    def proven_unfinished_timer(self):
        if self.material_preview is not None:
            material = self.material_preview
            context = AssetSyncContext(
                server_instance_id=self.server_instance_id,
                sync_epoch=self.sync_epoch,
                device_id=str(self.device.device_id),
            )
            if any(record.context != context for record in material.assets):
                raise ValueError("preview asset belongs to another replica/device")
            actual = {str(record.asset.asset_id) for record in material.assets} | set(
                material.missing_assets
            )
            if actual != recovery_material_ids(self.snapshot, material.pack):
                raise ValueError("preview material dependencies are incomplete")
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
