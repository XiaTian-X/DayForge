"""Immutable account declarations in caller-owned transactions; no active routes.

Reservation, blobs, declarations and directory entries commit together. SQLite
snapshot/unique races must roll back the complete request before identity retry;
this module never commits or retries inside a stale transaction.
"""

from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.auth.models import User
from src.v2.appearance import IconBlob
from src.v2.asset_access import require_asset_access
from src.v2.asset_api_contract import (
    AppearanceQuota,
    AssetDeclaration,
    AssetRecord,
    AssetSyncContext,
    PackDeclaration,
)
from src.v2.asset_metadata import canonical_metadata
from src.v2.asset_models import (
    AccountIconAsset,
    AccountIconBlob,
    AccountIconPack,
    AppearanceAccount,
    AppearanceCatalog,
)
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.asset_records import asset_value, blob_value, pack_value, quota_value


MAX_SEQUENCE = 9_223_372_036_854_775_807


async def _asset_row(
    session: AsyncSession, owner: int, asset_id: str
) -> AccountIconAsset | None:
    return (
        await session.execute(
            select(AccountIconAsset).where(
                col(AccountIconAsset.owner_user_id) == owner,
                col(AccountIconAsset.public_id) == asset_id,
            )
        )
    ).scalar_one_or_none()


async def _pack_row(
    session: AsyncSession, owner: int, pack_id: str, revision: int
) -> AccountIconPack | None:
    return (
        await session.execute(
            select(AccountIconPack).where(
                col(AccountIconPack.owner_user_id) == owner,
                col(AccountIconPack.pack_uuid) == pack_id,
                col(AccountIconPack.revision) == revision,
            )
        )
    ).scalar_one_or_none()


async def _asset_record(
    session: AsyncSession, row: AccountIconAsset, context: AssetSyncContext
) -> AssetRecord:
    asset = asset_value(row)
    ready = []
    for variant, identifier in (
        ("light", row.light_blob_id),
        ("dark", row.dark_blob_id),
    ):
        if identifier is None:
            if getattr(asset, variant) is not None:
                raise DomainError(
                    "ASSET_METADATA_CORRUPT", "Stored asset metadata is inconsistent"
                )
            continue
        blob = (
            await session.execute(
                select(AccountIconBlob).where(
                    col(AccountIconBlob.owner_user_id) == row.owner_user_id,
                    col(AccountIconBlob.id) == identifier,
                )
            )
        ).scalar_one_or_none()
        if blob is None or blob_value(blob) != getattr(asset, variant):
            raise DomainError(
                "ASSET_METADATA_CORRUPT", "Stored asset metadata is inconsistent"
            )
        if blob.ready_at is not None:
            ready.append(variant)
    return AssetRecord.model_validate(
        dict(context=context, asset=asset, ready_variants=ready)
    )


async def read_asset(
    session: AsyncSession, user: User, context: AssetSyncContext, asset_id: str
) -> AssetRecord:
    owner = await require_asset_access(session, user, context)
    row = await _asset_row(session, owner, asset_id)
    if row is None:
        raise DomainError("ASSET_NOT_FOUND", "Asset was not found")
    return await _asset_record(session, row, context)


async def read_pack(
    session: AsyncSession,
    user: User,
    context: AssetSyncContext,
    pack_id: str,
    revision: int,
) -> PackDeclaration:
    owner = await require_asset_access(session, user, context)
    row = await _pack_row(session, owner, pack_id, revision)
    if row is None:
        raise DomainError("PACK_NOT_FOUND", "Pack version was not found")
    return PackDeclaration(context=context, pack=pack_value(row))


async def read_quota(
    session: AsyncSession, user: User, context: AssetSyncContext
) -> AppearanceQuota:
    owner = await require_asset_access(session, user, context)
    row = await session.get(AppearanceAccount, owner)
    if row is None:
        row = AppearanceAccount(user_id=owner)  # Read defaults without creating a row.
    return quota_value(row)


async def _reserve(
    session: AsyncSession,
    owner: int,
    *,
    byte_count: int,
    asset_count: int,
    metadata_count: int,
) -> int:
    account = await session.get(AppearanceAccount, owner)
    if account is None:
        account = AppearanceAccount(user_id=owner)
        session.add(account)
        await session.flush()
    quota_value(account)
    changes = dict(
        reserved_bytes=byte_count,
        reserved_assets=asset_count,
        reserved_metadata_bytes=metadata_count,
    )
    limits = dict(
        reserved_bytes="byte_limit",
        reserved_assets="asset_limit",
        reserved_metadata_bytes="metadata_byte_limit",
    )
    conditions = [
        col(AppearanceAccount.user_id) == owner,
        col(AppearanceAccount.catalog_sequence) < MAX_SEQUENCE,
    ]
    for field, delta in changes.items():
        if delta > 0:
            column = col(getattr(AppearanceAccount, field))
            # Subtract the bounded delta from the limit, avoiding signed overflow.
            conditions.append(
                column <= col(getattr(AppearanceAccount, limits[field])) - delta
            )
    result = await session.execute(
        update(AppearanceAccount)
        .where(*conditions)
        .values(
            **{
                name: col(getattr(AppearanceAccount, name)) + delta
                for name, delta in changes.items()
            },
            catalog_sequence=col(AppearanceAccount.catalog_sequence) + 1,
        )
        .returning(col(AppearanceAccount.catalog_sequence))
        .execution_options(synchronize_session="fetch")
    )
    sequence = result.scalar_one_or_none()
    if sequence is None:
        raise DomainError(
            "ASSET_QUOTA_EXCEEDED",
            "The declaration exceeds account appearance capacity",
        )
    return sequence


async def declare_asset(
    session: AsyncSession, user: User, request: AssetDeclaration
) -> AssetRecord:
    owner = await require_asset_access(session, user, request.context, write=True)
    asset = request.asset
    metadata = canonical_metadata(asset)
    existing = await _asset_row(session, owner, asset.asset_id)
    if existing is not None:
        if existing.metadata_json != metadata:
            raise DomainError(
                "ASSET_ID_REUSED",
                "An immutable asset identity cannot be changed",
                conflict=True,
            )
        return await _asset_record(session, existing, request.context)

    descriptions: dict[str, IconBlob] = {}
    for description in (asset.light, asset.dark):
        if description is None:
            continue
        if (
            description.sha256 in descriptions
            and descriptions[description.sha256] != description
        ):
            raise DomainError(
                "ASSET_BLOB_METADATA_MISMATCH",
                "One account digest cannot describe different bytes",
                conflict=True,
            )
        descriptions[description.sha256] = description
    blobs: dict[str, AccountIconBlob] = {}
    missing: list[IconBlob] = []
    for digest, description in descriptions.items():
        row = (
            await session.execute(
                select(AccountIconBlob).where(
                    col(AccountIconBlob.owner_user_id) == owner,
                    col(AccountIconBlob.sha256) == digest,
                )
            )
        ).scalar_one_or_none()
        if row is None:
            missing.append(description)
        elif blob_value(row) != description:
            raise DomainError(
                "ASSET_BLOB_METADATA_MISMATCH",
                "One account digest cannot describe different bytes",
                conflict=True,
            )
        else:
            blobs[digest] = row

    sequence = await _reserve(
        session,
        owner,
        byte_count=sum(value.byte_length for value in missing),
        asset_count=1,
        metadata_count=len(metadata.encode("utf-8")),
    )
    for description in missing:
        blob = AccountIconBlob(owner_user_id=owner, **description.model_dump())
        session.add(blob)
        blobs[description.sha256] = blob
    await session.flush()
    stored_asset = AccountIconAsset(
        owner_user_id=owner,
        public_id=asset.asset_id,
        light_blob_id=require_internal(blobs[asset.light.sha256].id, "blob.id"),
        dark_blob_id=require_internal(blobs[asset.dark.sha256].id, "blob.id")
        if asset.dark is not None
        else None,
        metadata_json=metadata,
        metadata_bytes=len(metadata.encode("utf-8")),
    )
    session.add(stored_asset)
    await session.flush()
    session.add(
        AppearanceCatalog(
            owner_user_id=owner,
            sequence=sequence,
            kind="asset",
            asset_id=require_internal(stored_asset.id, "asset.id"),
        )
    )
    await session.flush()
    return await _asset_record(session, stored_asset, request.context)


async def declare_pack(
    session: AsyncSession, user: User, request: PackDeclaration
) -> PackDeclaration:
    owner = await require_asset_access(session, user, request.context, write=True)
    pack = request.pack
    metadata = canonical_metadata(pack)
    existing = await _pack_row(session, owner, pack.pack_id, pack.revision)
    if existing is not None:
        if existing.metadata_json != metadata:
            raise DomainError(
                "PACK_VERSION_REUSED",
                "An immutable pack version cannot be changed",
                conflict=True,
            )
        return PackDeclaration(
            context=request.context,
            pack=pack_value(existing),
        )
    for asset in pack.assets:
        row = await _asset_row(session, owner, asset.asset_id)
        if row is None:
            raise DomainError(
                "ASSET_NOT_FOUND", "A required account asset was not found"
            )
        if row.metadata_json != canonical_metadata(asset):
            raise DomainError(
                "ASSET_ID_REUSED",
                "Pack metadata differs from its declared account asset",
                conflict=True,
            )
    sequence = await _reserve(
        session,
        owner,
        byte_count=0,
        asset_count=0,
        metadata_count=len(metadata.encode("utf-8")),
    )
    stored_pack = AccountIconPack(
        owner_user_id=owner,
        pack_uuid=pack.pack_id,
        revision=pack.revision,
        metadata_json=metadata,
        metadata_bytes=len(metadata.encode("utf-8")),
    )
    session.add(stored_pack)
    await session.flush()
    session.add(
        AppearanceCatalog(
            owner_user_id=owner,
            sequence=sequence,
            kind="pack",
            pack_version_id=require_internal(stored_pack.id, "pack.id"),
        )
    )
    await session.flush()
    return PackDeclaration(context=request.context, pack=pack)
