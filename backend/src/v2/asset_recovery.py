"""Check the complete immutable account catalog in the caller's read snapshot.

This metadata-only stage cannot back up installed files. A ready blob therefore
fails closed until verified byte backup/restore is implemented, never silently
producing a database-only backup that claims to include all account resources.
"""

from collections.abc import Callable
from typing import Any, TypeVar

from pydantic import ValidationError

from src.v2.appearance import IconAsset, IconBlob, IconPack
from src.v2.asset_metadata import canonical_metadata


TABLES = (
    "appearance_accounts",
    "account_icon_blobs",
    "account_icon_assets",
    "account_icon_packs",
    "appearance_catalog",
)
QUOTA_FIELDS = (
    "byte_limit",
    "reserved_bytes",
    "asset_limit",
    "reserved_assets",
    "metadata_byte_limit",
    "reserved_metadata_bytes",
    "catalog_sequence",
)


class AssetRecoveryError(ValueError):
    """A stored resource graph cannot be safely restored or exported."""


def _integer(value: Any, *, minimum: int = 0) -> int:
    if type(value) is not int or not minimum <= value <= 9_223_372_036_854_775_807:
        raise AssetRecoveryError("invalid appearance integer")
    return value


def _owner(row: dict[str, Any], accounts: dict[int, dict[str, Any]]) -> int:
    owner = _integer(row["owner_user_id"], minimum=1)
    if owner not in accounts:
        raise AssetRecoveryError("missing appearance account")
    return owner


MetadataValue = TypeVar("MetadataValue", IconAsset, IconPack)


def _metadata(row: dict[str, Any], model: type[MetadataValue]) -> MetadataValue:
    value = model.model_validate_json(row["metadata_json"])
    canonical = canonical_metadata(value)
    if canonical != row["metadata_json"] or len(canonical.encode("utf-8")) != _integer(
        row["metadata_bytes"], minimum=1
    ):
        raise AssetRecoveryError("noncanonical appearance metadata or byte charge")
    return value


def validate_asset_rows(rows: dict[str, list[dict[str, Any]]]) -> None:
    accounts: dict[int, dict[str, Any]] = {}
    for row in rows["appearance_accounts"]:
        owner = _integer(row["user_id"], minimum=1)
        if owner in accounts:
            raise AssetRecoveryError("duplicate appearance account")
        for name in QUOTA_FIELDS:
            _integer(row[name])
        accounts[owner] = row

    blobs: dict[tuple[int, int], IconBlob] = {}
    digests: set[tuple[int, str]] = set()
    for row in rows["account_icon_blobs"]:
        owner = _owner(row, accounts)
        identity = (owner, _integer(row["id"], minimum=1))
        blob = IconBlob.model_validate(
            {name: row[name] for name in IconBlob.model_fields}
        )
        if identity in blobs or (owner, blob.sha256) in digests:
            raise AssetRecoveryError("duplicate account blob")
        if row["ready_at"] is not None:
            raise AssetRecoveryError("appearance byte backup is not implemented")
        blobs[identity] = blob
        digests.add((owner, blob.sha256))

    assets: dict[tuple[int, str], IconAsset] = {}
    asset_ids: set[tuple[int, int]] = set()
    used_blobs: set[tuple[int, int]] = set()
    charges = {owner: 0 for owner in accounts}
    for row in rows["account_icon_assets"]:
        owner = _owner(row, accounts)
        value = _metadata(row, IconAsset)
        identity = (owner, _integer(row["id"], minimum=1))
        if (
            value.asset_id != row["public_id"]
            or (owner, value.asset_id) in assets
            or identity in asset_ids
        ):
            raise AssetRecoveryError("invalid account asset identity")
        for variant in ("light", "dark"):
            blob_id = row[f"{variant}_blob_id"]
            description = getattr(value, variant)
            if blob_id is None:
                if description is not None:
                    raise AssetRecoveryError("missing asset blob reference")
                continue
            key = (owner, _integer(blob_id, minimum=1))
            if description is None or key not in blobs or blobs[key] != description:
                raise AssetRecoveryError("asset blob metadata or ownership mismatch")
            used_blobs.add(key)
        assets[(owner, value.asset_id)] = value
        asset_ids.add(identity)
        charges[owner] += row["metadata_bytes"]
    if used_blobs != set(blobs):
        raise AssetRecoveryError("unreferenced account blob")

    pack_ids: set[tuple[int, int]] = set()
    versions: set[tuple[int, str, int]] = set()
    for row in rows["account_icon_packs"]:
        owner = _owner(row, accounts)
        pack = _metadata(row, IconPack)
        identity = (owner, _integer(row["id"], minimum=1))
        revision = _integer(row["revision"], minimum=1)
        version_key = (owner, pack.pack_id, revision)
        if (
            pack.pack_id != row["pack_uuid"]
            or pack.revision != revision
            or identity in pack_ids
            or version_key in versions
        ):
            raise AssetRecoveryError("invalid account pack identity")
        for asset in pack.assets:
            if assets.get((owner, asset.asset_id)) != asset:
                raise AssetRecoveryError("pack references undeclared account asset")
        pack_ids.add(identity)
        versions.add(version_key)
        charges[owner] += row["metadata_bytes"]

    sequences: dict[int, set[int]] = {owner: set() for owner in accounts}
    seen_assets: set[tuple[int, int]] = set()
    seen_packs: set[tuple[int, int]] = set()
    for row in rows["appearance_catalog"]:
        owner = _owner(row, accounts)
        sequence = _integer(row["sequence"], minimum=1)
        if sequence in sequences[owner]:
            raise AssetRecoveryError("duplicate appearance sequence")
        sequences[owner].add(sequence)
        if row["kind"] == "asset" and row["pack_version_id"] is None:
            identity = (owner, _integer(row["asset_id"], minimum=1))
            if identity not in asset_ids or identity in seen_assets:
                raise AssetRecoveryError("invalid catalog asset reference")
            seen_assets.add(identity)
        elif row["kind"] == "pack" and row["asset_id"] is None:
            identity = (owner, _integer(row["pack_version_id"], minimum=1))
            if identity not in pack_ids or identity in seen_packs:
                raise AssetRecoveryError("invalid catalog pack reference")
            seen_packs.add(identity)
        else:
            raise AssetRecoveryError("invalid catalog entry kind")
    if seen_assets != asset_ids or seen_packs != pack_ids:
        raise AssetRecoveryError("incomplete appearance catalog")
    for owner, account in accounts.items():
        values = sequences[owner]
        # Do not allocate a range up to an untrusted 64-bit high watermark.
        if len(values) != account["catalog_sequence"] or max(values, default=0) != len(
            values
        ):
            raise AssetRecoveryError("inconsistent appearance catalog watermark")
        if (
            account["reserved_bytes"]
            != sum(
                blob.byte_length for (user, _), blob in blobs.items() if user == owner
            )
            or account["reserved_assets"] != sum(user == owner for user, _ in asset_ids)
            or account["reserved_metadata_bytes"] != charges[owner]
        ):
            raise AssetRecoveryError("inconsistent appearance quota reservation")
        # Lowered limits may legitimately be below existing immutable resources.


def read_asset_metadata(read_rows: Callable[[str], list[dict[str, Any]]]) -> None:
    try:
        validate_asset_rows(
            {name: read_rows(f"SELECT * FROM {name}") for name in TABLES}
        )
    except ValidationError as error:
        raise AssetRecoveryError("invalid stored appearance contract") from error
