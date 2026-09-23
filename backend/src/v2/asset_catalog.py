"""Account catalog paging in one caller-owned snapshot, independent of sync cursors."""

from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.auth.models import User
from src.v2.asset_access import require_asset_access
from src.v2.asset_api_contract import (
    AppearanceCatalogPage,
    AssetCatalogEntry,
    AssetSyncContext,
    PackCatalogEntry,
)
from src.v2.asset_models import (
    AccountIconAsset,
    AccountIconPack,
    AppearanceAccount,
    AppearanceCatalog,
)
from src.v2.errors import DomainError
from src.v2.asset_records import asset_value, pack_value, quota_value


async def read_catalog(
    session: AsyncSession,
    user: User,
    context: AssetSyncContext,
    *,
    after: int = 0,
    through: int | None = None,
    limit: int = 100,
) -> AppearanceCatalogPage:
    owner = await require_asset_access(session, user, context)
    account = await session.get(AppearanceAccount, owner)
    if account is not None:
        quota_value(account)
    maximum = account.catalog_sequence if account is not None else 0
    if through is None:
        through = maximum
    if (
        type(after) is not int
        or type(through) is not int
        or type(limit) is not int
        or not 0 <= after <= through <= maximum
        or not 1 <= limit <= 100
    ):
        raise DomainError("INVALID_CURSOR", "Invalid appearance catalog page boundary")
    rows = list(
        (
            await session.execute(
                select(AppearanceCatalog)
                .where(
                    col(AppearanceCatalog.owner_user_id) == owner,
                    col(AppearanceCatalog.sequence) > after,
                    col(AppearanceCatalog.sequence) <= through,
                )
                .order_by(col(AppearanceCatalog.sequence))
                .limit(limit + 1)
            )
        ).scalars()
    )
    if len(rows) != min(limit + 1, through - after) or any(
        row.sequence != after + index + 1 for index, row in enumerate(rows)
    ):
        raise DomainError("ASSET_METADATA_CORRUPT", "Catalog history is incomplete")
    has_more = len(rows) > limit
    entries: list[AssetCatalogEntry | PackCatalogEntry] = []
    for row in rows[:limit]:
        if row.kind == "asset":
            asset = (
                await session.execute(
                    select(AccountIconAsset).where(
                        col(AccountIconAsset.owner_user_id) == owner,
                        col(AccountIconAsset.id) == row.asset_id,
                    )
                )
            ).scalar_one_or_none()
            if asset is None:
                raise DomainError(
                    "ASSET_METADATA_CORRUPT", "Catalog asset is unavailable"
                )
            entries.append(
                AssetCatalogEntry(
                    sequence=row.sequence,
                    kind="asset",
                    asset=asset_value(asset),
                )
            )
        else:
            pack = (
                await session.execute(
                    select(AccountIconPack).where(
                        col(AccountIconPack.owner_user_id) == owner,
                        col(AccountIconPack.id) == row.pack_version_id,
                    )
                )
            ).scalar_one_or_none()
            if row.kind != "pack" or pack is None:
                raise DomainError(
                    "ASSET_METADATA_CORRUPT", "Catalog pack is unavailable"
                )
            entries.append(
                PackCatalogEntry(
                    sequence=row.sequence,
                    kind="pack",
                    pack=pack_value(pack),
                )
            )
    return AppearanceCatalogPage(
        context=context,
        entries=entries,
        next_cursor=entries[-1].sequence if has_more else through,
        through_sequence=through,
        has_more=has_more,
    )
