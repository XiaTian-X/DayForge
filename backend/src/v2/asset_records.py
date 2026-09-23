"""Fail-closed decoding of immutable records at staged service read boundaries."""

from datetime import datetime
from typing import TypeVar

from pydantic import ValidationError
from src.appearance.profiles import IMAGE_PROFILES

from src.v2.appearance import IconAsset, IconBlob, IconPack
from src.v2.asset_api_contract import AppearanceQuota
from src.v2.asset_metadata import canonical_metadata
from src.v2.asset_models import (
    AccountIconAsset,
    AccountIconBlob,
    AccountIconPack,
    AppearanceAccount,
)
from src.v2.errors import DomainError


Value = TypeVar("Value", IconAsset, IconPack)


def _corrupt() -> DomainError:
    return DomainError(
        "ASSET_METADATA_CORRUPT", "Stored appearance metadata is inconsistent"
    )


def _decode(raw: str, byte_count: int, model: type[Value]) -> Value:
    try:
        value = model.model_validate_json(raw)
    except ValidationError as error:
        raise _corrupt() from error
    if (
        type(byte_count) is not int
        or canonical_metadata(value) != raw
        or len(raw.encode("utf-8")) != byte_count
    ):
        raise _corrupt()
    return value


def asset_value(row: AccountIconAsset) -> IconAsset:
    value = _decode(row.metadata_json, row.metadata_bytes, IconAsset)
    if value.asset_id != row.public_id:
        raise _corrupt()
    return value


def pack_value(row: AccountIconPack) -> IconPack:
    value = _decode(row.metadata_json, row.metadata_bytes, IconPack)
    if (
        value.pack_id != row.pack_uuid
        or type(row.revision) is not int
        or value.revision != row.revision
    ):
        raise _corrupt()
    return value


def blob_value(row: AccountIconBlob) -> IconBlob:
    try:
        return IconBlob.model_validate(
            {name: getattr(row, name) for name in IconBlob.model_fields}
        )
    except ValidationError as error:
        raise _corrupt() from error


def blob_ready(row: AccountIconBlob) -> bool:
    """Readiness is only meaningful together with a known media-matched profile.

    This validates metadata, not filesystem availability or authorization.
    """
    if row.ready_at is None:
        if row.validation_profile is not None:
            raise _corrupt()
        return False
    if (
        not isinstance(row.ready_at, datetime)
        or row.media_type not in IMAGE_PROFILES
        or row.validation_profile != IMAGE_PROFILES[row.media_type]
    ):
        raise _corrupt()
    return True


def quota_value(row: AppearanceAccount) -> AppearanceQuota:
    try:
        value = AppearanceQuota.model_validate(
            {name: getattr(row, name) for name in AppearanceQuota.model_fields}
        )
    except ValidationError as error:
        raise _corrupt() from error
    if (
        type(row.catalog_sequence) is not int
        or not 0 <= row.catalog_sequence <= 9_223_372_036_854_775_807
    ):
        raise _corrupt()
    return value
