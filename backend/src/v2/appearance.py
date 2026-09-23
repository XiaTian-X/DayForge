"""Validated immutable icon metadata for the planned appearance-v1 contract.

Not an upload endpoint: byte decoding, quota reservation and authenticated
ownership are separate mandatory gates before these descriptions are installed.
"""

from typing import Annotated, Literal

from pydantic import Field, TypeAdapter, field_validator, model_validator

from src.v2.one_time import ContractModel, PublicId


RoleKey = Annotated[
    str, Field(pattern=r"^(habit|metric|goal|task)\.[a-z][a-z0-9_]{0,47}$")
]
Digest = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
Purpose = Literal["general", "task"]


class RoleIcon(ContractModel):
    kind: Literal["role"]
    role: RoleKey


class AssetIcon(ContractModel):
    kind: Literal["asset"]
    asset_id: PublicId


IconReference = Annotated[RoleIcon | AssetIcon, Field(discriminator="kind")]
ICON_REFERENCE: TypeAdapter[RoleIcon | AssetIcon] = TypeAdapter(IconReference)


class IconBlob(ContractModel):
    sha256: Digest
    byte_length: int = Field(ge=1, le=2_097_152)
    media_type: Literal["image/png", "image/svg+xml"]
    width: int = Field(ge=1, le=1024)
    height: int = Field(ge=1, le=1024)

    @model_validator(mode="after")
    def svg_budget(self):
        if self.media_type == "image/svg+xml" and self.byte_length > 524_288:
            raise ValueError("SVG exceeds the 512 KiB limit")
        return self


class IconAsset(ContractModel):
    asset_id: PublicId
    name: str = Field(min_length=1, max_length=80)
    purpose: Purpose
    color_mode: Literal["template", "original"]
    light: IconBlob
    dark: IconBlob | None

    @model_validator(mode="after")
    def display_name(self):
        if not self.name.strip() or any(ord(char) < 32 for char in self.name):
            raise ValueError("asset name must contain visible text, without controls")
        return self


class IconPack(ContractModel):
    format: Literal["dayforge.icon-pack"]
    format_version: Literal[1]
    pack_id: PublicId
    revision: int = Field(ge=1, le=2_147_483_647)
    name: str = Field(min_length=1, max_length=80)
    assets: list[IconAsset] = Field(min_length=1, max_length=128)
    roles: dict[RoleKey, PublicId] = Field(max_length=256)
    placeholder_asset_id: PublicId | None

    @field_validator("format_version", mode="before")
    @classmethod
    def exact_version_type(cls, value):
        if type(value) is not int:
            raise ValueError("format_version must be a JSON integer")
        return value

    @model_validator(mode="after")
    def validate_catalog(self):
        if not self.name.strip() or any(ord(char) < 32 for char in self.name):
            raise ValueError("pack name must contain visible text, without controls")
        assets = {asset.asset_id: asset for asset in self.assets}
        if len(assets) != len(self.assets):
            raise ValueError("duplicate asset identity")
        blobs: dict[str, IconBlob] = {}
        for asset in self.assets:
            for blob in (asset.light, asset.dark):
                if blob is None:
                    continue
                if blob.sha256 in blobs and blobs[blob.sha256] != blob:
                    raise ValueError("one digest cannot describe different bytes")
                blobs[blob.sha256] = blob
        if sum(blob.byte_length for blob in blobs.values()) > 67_108_864:
            raise ValueError("uncompressed pack exceeds 64 MiB")
        for role, asset_id in self.roles.items():
            mapped_asset = assets.get(asset_id)
            if mapped_asset is None:
                raise ValueError("role references an asset outside this pack")
            if role.startswith("task.") != (mapped_asset.purpose == "task"):
                raise ValueError("task roles and task assets are reserved")
        if self.placeholder_asset_id is not None:
            placeholder = assets.get(self.placeholder_asset_id)
            if placeholder is None or placeholder.purpose != "general":
                raise ValueError("placeholder must be a general asset in this pack")
        return self


def icon_allowed(
    icon: RoleIcon | AssetIcon,
    *,
    one_time: bool,
    asset: IconAsset | None = None,
) -> bool:
    """Unknown asset metadata is unresolved, never an authorization success."""
    if isinstance(icon, RoleIcon):
        return icon.role.startswith("task.") == one_time
    return (
        asset is not None
        and asset.asset_id == icon.asset_id
        and (asset.purpose == "task") == one_time
    )
