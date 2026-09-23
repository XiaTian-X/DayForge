"""Frozen catalog snapshots and read-only account scope over real SQLite."""

import pytest
from sqlalchemy import text

from src.auth.models import User
from src.v2.asset_catalog import read_catalog
from src.v2.asset_models import AppearanceAccount
from src.v2.asset_service import read_asset, read_pack, read_quota
from src.v2.errors import DomainError
from tests.test_asset_declarations import (
    ASSET,
    PACK,
    asset,
    pack,
    setup,
    snapshot,
    write_asset,
    write_pack,
)


async def page(factory, context, **kwargs):
    async with factory() as session:
        user = await session.get(User, 1)
        assert user is not None
        return await read_catalog(session, user, context, **kwargs)


async def seeded(engine):
    factory, contexts = await setup(engine)
    first = asset(contexts[1])
    await write_asset(factory, first)
    await write_pack(factory, pack(contexts[1], [first]))
    await write_pack(factory, pack(contexts[1], [first], revision=2))
    return factory, contexts, first


async def test_pages_freeze_watermark_repeat_exactly_and_exclude_new_entries(
    runtime_engine,
):
    factory, contexts, first = await seeded(runtime_engine)
    opening = await page(factory, contexts[1], limit=1)
    assert (
        opening.through_sequence == 3 and opening.next_cursor == 1 and opening.has_more
    )
    assert [entry.kind for entry in opening.entries] == ["asset"]
    assert opening.entries[0].asset == first.asset
    assert "ready_variants" not in opening.model_dump_json()
    await write_pack(factory, pack(contexts[1], [first], revision=3))
    assert await page(factory, contexts[1], through=3, limit=1) == opening
    middle = await page(factory, contexts[1], after=1, through=3, limit=1)
    assert (
        middle.next_cursor == 2
        and middle.has_more
        and middle.entries[0].pack.revision == 1
    )
    final = await page(factory, contexts[1], after=2, through=3, limit=1)
    assert (
        final.next_cursor == 3
        and not final.has_more
        and final.entries[0].pack.revision == 2
    )
    empty = await page(factory, contexts[1], after=3, through=3, limit=1)
    assert empty.entries == [] and empty.next_cursor == 3 and not empty.has_more
    latest = await page(factory, contexts[1], after=3, limit=1)
    assert latest.through_sequence == 4 and latest.entries[0].pack.revision == 3
    assert latest.context == contexts[1] and not latest.has_more


async def test_catalog_and_quota_keep_read_snapshot_and_never_touch_sync_cursors(
    runtime_engine,
):
    factory, contexts, first = await seeded(runtime_engine)
    before = await snapshot(factory)
    async with factory.begin() as reader:
        user = await reader.get(User, 1)
        assert user is not None
        original = await read_catalog(reader, user, contexts[1])
        quota = await read_quota(reader, user, contexts[1])
        await write_pack(factory, pack(contexts[1], [first], revision=3))
        assert await read_catalog(reader, user, contexts[1]) == original
        assert await read_quota(reader, user, contexts[1]) == quota
        assert not reader.dirty and not reader.new
    after = await snapshot(factory)
    assert len(after["appearance_catalog"]) == len(before["appearance_catalog"]) + 1
    async with factory() as session:
        for name in (
            "sync_changes",
            "sync_cursors",
            "sync_operations",
            "entity_revision_snapshots",
        ):
            assert (
                await session.execute(text(f"SELECT COUNT(*) FROM {name}"))
            ).scalar_one() == 0


@pytest.mark.parametrize(
    "kwargs",
    [
        {"after": -1},
        {"after": 4},
        {"after": True},
        {"after": 1.0},
        {"through": 4},
        {"through": -1},
        {"through": False},
        {"through": 3.0},
        {"after": 2, "through": 1},
        {"limit": 0},
        {"limit": 101},
        {"limit": True},
    ],
)
async def test_invalid_boundaries_do_not_advance_or_create_state(
    runtime_engine, kwargs
):
    factory, contexts, _ = await seeded(runtime_engine)
    before = await snapshot(factory)
    with pytest.raises(DomainError) as error:
        await page(factory, contexts[1], **kwargs)
    assert error.value.code == "INVALID_CURSOR"
    assert await snapshot(factory) == before


@pytest.mark.parametrize("sequence,limit", [(1, 1), (2, 1), (3, 2)])
async def test_missing_catalog_entry_cannot_be_reported_as_a_complete_page(
    runtime_engine, sequence, limit
):
    factory, contexts, _ = await seeded(runtime_engine)
    async with factory.begin() as session:
        await session.execute(
            text("DELETE FROM appearance_catalog WHERE sequence=:value"),
            {"value": sequence},
        )
    with pytest.raises(DomainError) as error:
        await page(factory, contexts[1], limit=limit)
    assert error.value.code == "ASSET_METADATA_CORRUPT"


async def test_zero_state_reads_do_not_create_quota_or_policy_or_update_device(
    runtime_engine,
):
    factory, contexts = await setup(runtime_engine)
    async with factory.begin() as session:
        await session.execute(text("DELETE FROM user_sync_policies"))
    async with factory.begin() as session:
        user = await session.get(User, 1)
        assert user is not None
        before = (
            await session.execute(
                text("SELECT last_seen_at FROM client_devices WHERE id=1")
            )
        ).scalar_one()
        quota = await read_quota(session, user, contexts[1])
        result = await read_catalog(session, user, contexts[1])
        assert (
            quota.byte_limit == 268435456
            and quota.asset_limit == 1000
            and quota.metadata_byte_limit == 8388608
        )
        assert (
            quota.reserved_bytes
            == quota.reserved_assets
            == quota.reserved_metadata_bytes
            == 0
        )
        assert (
            result.entries == []
            and result.through_sequence == result.next_cursor == 0
            and not result.has_more
        )
        assert await session.get(AppearanceAccount, 1) is None
        assert (
            await session.execute(text("SELECT COUNT(*) FROM user_sync_policies"))
        ).scalar_one() == 0
        assert (
            await session.execute(
                text("SELECT last_seen_at FROM client_devices WHERE id=1")
            )
        ).scalar_one() == before
        assert not session.dirty and not session.new


@pytest.mark.parametrize(
    "case",
    [
        "asset-json",
        "asset-charge",
        "asset-identity",
        "pack-json",
        "pack-identity",
        "quota",
        "sequence",
    ],
)
@pytest.mark.parametrize("entry", ["direct", "catalog"])
async def test_corrupt_metadata_has_a_stable_error_and_never_advances(
    runtime_engine, case, entry
):
    factory, contexts, _ = await seeded(runtime_engine)
    statements = {
        "asset-json": "UPDATE account_icon_assets SET metadata_json='{}'",
        "asset-charge": "UPDATE account_icon_assets SET metadata_bytes=metadata_bytes+1",
        "asset-identity": "UPDATE account_icon_assets SET public_id='a1000000-0000-4000-8000-000000000002'",
        "pack-json": "UPDATE account_icon_packs SET metadata_json='{}'",
        "pack-identity": "UPDATE account_icon_packs SET pack_uuid='b1000000-0000-4000-8000-000000000002'",
        "quota": "UPDATE appearance_accounts SET asset_limit=2.5",
        "sequence": "UPDATE appearance_accounts SET catalog_sequence=3.5",
    }
    async with factory.begin() as session:
        await session.execute(text(statements[case]))
    before = await snapshot(factory)
    async with factory.begin() as session:
        user = await session.get(User, 1)
        assert user is not None
        with pytest.raises(DomainError) as error:
            if entry == "catalog":
                await read_catalog(session, user, contexts[1])
            elif case.startswith("asset"):
                await read_asset(
                    session,
                    user,
                    contexts[1],
                    ASSET
                    if case != "asset-identity"
                    else "a1000000-0000-4000-8000-000000000002",
                )
            elif case.startswith("pack"):
                await read_pack(
                    session,
                    user,
                    contexts[1],
                    PACK
                    if case != "pack-identity"
                    else "b1000000-0000-4000-8000-000000000002",
                    1,
                )
            else:
                await read_quota(session, user, contexts[1])
        assert error.value.code == "ASSET_METADATA_CORRUPT"
    assert await snapshot(factory) == before


async def test_corrupt_quota_is_rejected_before_reservation(runtime_engine):
    factory, contexts, _ = await seeded(runtime_engine)
    async with factory.begin() as session:
        await session.execute(
            text("UPDATE appearance_accounts SET reserved_assets=2.5")
        )
    before = await snapshot(factory)
    with pytest.raises(DomainError) as error:
        await write_asset(
            factory, asset(contexts[1], asset_id="a1000000-0000-4000-8000-000000000002")
        )
    assert error.value.code == "ASSET_METADATA_CORRUPT"
    assert await snapshot(factory) == before
