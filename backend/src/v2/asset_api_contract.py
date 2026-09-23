"""Forward authenticated appearance API values; bytes and storage are not trusted here."""

from typing import Annotated, Literal

from pydantic import Field, model_validator

from src.v2.appearance import IconAsset, IconBlob, IconPack
from src.v2.contract_types import ContractModel, PublicId


Sequence = Annotated[int, Field(ge=0, le=9_223_372_036_854_775_807)]
Variant = Literal["light", "dark"]


class AssetSyncContext(ContractModel):
    server_instance_id: PublicId
    sync_epoch: PublicId
    device_id: PublicId


class AssetDeclaration(ContractModel):
    context: AssetSyncContext
    asset: IconAsset


class PackDeclaration(ContractModel):
    context: AssetSyncContext
    pack: IconPack


class AssetRecord(ContractModel):
    context: AssetSyncContext
    asset: IconAsset
    ready_variants: list[Variant] = Field(max_length=2)

    @model_validator(mode="after")
    def valid_availability(self):
        if len(set(self.ready_variants)) != len(self.ready_variants):
            raise ValueError("duplicate variant")
        if self.asset.dark is None and "dark" in self.ready_variants:
            raise ValueError("dark variant is not declared")
        if self.asset.dark == self.asset.light and ("light" in self.ready_variants) != (
            "dark" in self.ready_variants
        ):
            raise ValueError("identical account blob has one availability state")
        return self


class AssetTransferReceipt(ContractModel):
    context: AssetSyncContext
    asset_id: PublicId
    variant: Variant
    blob: IconBlob


class AppearanceQuota(ContractModel):
    byte_limit: Sequence
    reserved_bytes: Sequence
    asset_limit: Sequence
    reserved_assets: Sequence
    metadata_byte_limit: Sequence
    reserved_metadata_bytes: Sequence
    # An administrator may lower limits below existing usage. That must not make
    # the read response invalid, nor revoke existing assets or identical retries.


class AssetCatalogEntry(ContractModel):
    sequence: int = Field(ge=1, le=9_223_372_036_854_775_807)
    kind: Literal["asset"]
    asset: IconAsset


class PackCatalogEntry(ContractModel):
    sequence: int = Field(ge=1, le=9_223_372_036_854_775_807)
    kind: Literal["pack"]
    pack: IconPack


CatalogEntry = Annotated[
    AssetCatalogEntry | PackCatalogEntry, Field(discriminator="kind")
]


class AppearanceCatalogPage(ContractModel):
    context: AssetSyncContext
    entries: list[CatalogEntry] = Field(max_length=100)
    next_cursor: Sequence
    through_sequence: Sequence
    has_more: bool

    @model_validator(mode="after")
    def valid_page(self):
        sequences = [entry.sequence for entry in self.entries]
        if sequences != sorted(set(sequences)):
            raise ValueError("catalog entries must have strictly increasing sequence")
        identities = [
            ("asset", entry.asset.asset_id)
            if isinstance(entry, AssetCatalogEntry)
            else ("pack", entry.pack.pack_id, entry.pack.revision)
            for entry in self.entries
        ]
        if len(set(identities)) != len(identities):
            raise ValueError("immutable metadata cannot have duplicate catalog entries")
        if self.next_cursor > self.through_sequence or any(
            value > self.next_cursor for value in sequences
        ):
            raise ValueError("catalog cursor does not bound entries")
        if self.has_more:
            if (
                not sequences
                or self.next_cursor != sequences[-1]
                or self.next_cursor >= self.through_sequence
            ):
                raise ValueError(
                    "continuation must advance before the frozen watermark"
                )
        elif self.next_cursor != self.through_sequence:
            raise ValueError("completed page must reach the frozen watermark")
        return self


def validate_catalog_binding(
    context: AssetSyncContext,
    after: int,
    through: int | None,
    page: AppearanceCatalogPage,
) -> None:
    if page.context != context or not 0 <= after <= page.through_sequence:
        raise ValueError("catalog belongs to another scope or has regressed")
    if through is not None and through != page.through_sequence:
        raise ValueError("catalog continuation changed its watermark")
    if any(entry.sequence <= after for entry in page.entries):
        raise ValueError("catalog replay cannot advance the requested page")


def validate_asset_record_binding(
    request: AssetDeclaration, response: AssetRecord
) -> None:
    if request.context != response.context or request.asset != response.asset:
        raise ValueError(
            "asset response does not acknowledge this immutable declaration"
        )


def validate_pack_binding(request: PackDeclaration, response: PackDeclaration) -> None:
    if request != response:
        raise ValueError(
            "pack response does not acknowledge this immutable declaration"
        )


def validate_transfer_binding(
    request: AssetDeclaration, variant: Variant, receipt: AssetTransferReceipt
) -> None:
    expected = request.asset.light if variant == "light" else request.asset.dark
    if (
        request.context != receipt.context
        or request.asset.asset_id != receipt.asset_id
        or receipt.variant != variant
        or expected is None
        or expected != receipt.blob
    ):
        raise ValueError(
            "transfer receipt belongs to another scope, variant or content"
        )
