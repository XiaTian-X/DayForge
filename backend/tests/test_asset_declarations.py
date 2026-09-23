"""Real migrated metadata transactions; no production v5 HTTP activation."""

import asyncio
import json
from pathlib import Path

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.ext.asyncio import async_sessionmaker

from src.auth.models import User
from src.storage.database_adapter import is_sqlite_busy
from src.storage.sqlite_maintenance import inspect_database
from src.v2.asset_api_contract import (
    AssetDeclaration,
    AssetSyncContext,
    PackDeclaration,
)
from src.v2.asset_catalog import read_catalog
from src.v2.asset_models import (
    AccountIconAsset,
    AccountIconBlob,
    AccountIconPack,
    AppearanceAccount,
    AppearanceCatalog,
)
from src.v2.asset_service import (
    declare_asset,
    declare_pack,
    read_asset,
    read_pack,
    read_quota,
)
from src.v2.errors import DomainError
from src.v2.models import ClientDevice, ServerInstance, UserSyncPolicy
from tests.account_fixtures import account_password_hash


ASSET = "a1000000-0000-4000-8000-000000000001"
PACK = "b1000000-0000-4000-8000-000000000001"
MODELS = (
    AppearanceAccount,
    AccountIconBlob,
    AccountIconAsset,
    AccountIconPack,
    AppearanceCatalog,
)


async def setup(engine):
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory.begin() as session:
        identity = await session.get(ServerInstance, 1)
        assert identity is not None
        contexts = {}
        for owner in (1, 2):
            session.add(
                User(
                    id=owner,
                    username=f"asset-user-{owner}",
                    password_hash=account_password_hash(),
                )
            )
        await session.flush()
        for owner in (1, 2):
            device_id = f"c1000000-0000-4000-8000-{owner:012d}"
            session.add(
                ClientDevice(
                    id=owner,
                    user_id=owner,
                    public_id=device_id,
                    installation_id=f"asset-device-{owner}",
                    platform="android",
                    device_class="interactive",
                )
            )
            contexts[owner] = AssetSyncContext(
                server_instance_id=identity.instance_uuid,
                sync_epoch=identity.sync_epoch,
                device_id=device_id,
            )
        await session.flush()
        for owner in (1, 2):
            session.add(UserSyncPolicy(user_id=owner, primary_editor_device_id=owner))
    return factory, contexts


def asset(context, **changes):
    blob = dict(
        sha256="a" * 64, byte_length=96, media_type="image/png", width=24, height=24
    )
    value = dict(
        asset_id=ASSET,
        name="喝水",
        purpose="general",
        color_mode="template",
        light=blob,
        dark=blob,
    )
    value.update(changes)
    return AssetDeclaration.model_validate(dict(context=context, asset=value))


def pack(context, assets, *, revision=1):
    return PackDeclaration.model_validate(
        dict(
            context=context,
            pack=dict(
                format="dayforge.icon-pack",
                format_version=1,
                pack_id=PACK,
                revision=revision,
                name="家庭图标包",
                assets=[item.asset for item in assets],
                roles={"habit.water": assets[0].asset.asset_id},
                placeholder_asset_id=None,
            ),
        )
    )


async def write_asset(factory, request, owner=1):
    async with factory.begin() as session:
        user = await session.get(User, owner)
        assert user is not None
        return await declare_asset(session, user, request)


async def write_pack(factory, request, owner=1):
    async with factory.begin() as session:
        user = await session.get(User, owner)
        assert user is not None
        return await declare_pack(session, user, request)


async def snapshot(factory):
    async with factory() as session:
        return {
            model.__tablename__: [
                dict(row)
                for row in (
                    await session.execute(
                        text(f"SELECT * FROM {model.__tablename__} ORDER BY 1")
                    )
                ).mappings()
            ]
            for model in MODELS
        }


async def test_declaration_replay_dedup_versions_and_reopen_match_complete_recovery(
    runtime_engine,
):
    factory, contexts = await setup(runtime_engine)
    first = asset(contexts[1])
    result = await write_asset(factory, first)
    assert (
        result.asset == first.asset
        and result.context == contexts[1]
        and result.ready_variants == []
    )
    before = await snapshot(factory)
    assert await write_asset(factory, first) == result
    assert await snapshot(factory) == before
    second = asset(
        contexts[1], asset_id="a1000000-0000-4000-8000-000000000002", name="运动"
    )
    await write_asset(factory, second)
    for revision in (1, 2):
        request = pack(contexts[1], [first, second], revision=revision)
        assert await write_pack(factory, request) == request
        before = await snapshot(factory)
        assert await write_pack(factory, request) == request
        assert await snapshot(factory) == before
    async with factory() as session:
        user = await session.get(User, 1)
        assert user is not None
        quota = await read_quota(session, user, contexts[1])
        assert quota.reserved_bytes == 96 and quota.reserved_assets == 2
        rows = await snapshot(factory)
        assert quota.reserved_metadata_bytes == sum(
            row["metadata_bytes"]
            for model in (AccountIconAsset, AccountIconPack)
            for row in rows[model.__tablename__]
        )
        assert (
            await read_asset(session, user, contexts[1], ASSET)
        ).asset == first.asset
        assert (await read_pack(session, user, contexts[1], PACK, 2)).pack.revision == 2
    assert inspect_database(Path(runtime_engine.url.database)).valid


async def test_same_public_ids_and_hashes_are_isolated_and_reads_do_not_initialize(
    runtime_engine,
):
    factory, contexts = await setup(runtime_engine)
    await write_asset(factory, asset(contexts[1]))
    async with factory.begin() as session:
        user = await session.get(User, 2)
        assert user is not None
        assert (await read_quota(session, user, contexts[2])).reserved_bytes == 0
        assert (await read_catalog(session, user, contexts[2])).entries == []
        with pytest.raises(DomainError) as error:
            await read_asset(session, user, contexts[2], ASSET)
        assert error.value.code == "ASSET_NOT_FOUND"
        assert await session.get(AppearanceAccount, 2) is None
        assert not session.dirty and not session.new
    other = asset(contexts[2], name="独立素材")
    await write_asset(factory, other, owner=2)
    rows = await snapshot(factory)
    assert len(rows["account_icon_blobs"]) == 2
    assert [
        (row["user_id"], row["reserved_bytes"]) for row in rows["appearance_accounts"]
    ] == [(1, 96), (2, 96)]
    assert inspect_database(Path(runtime_engine.url.database)).valid


@pytest.mark.parametrize(
    "case,code",
    [
        ("foreign-device", "DEVICE_NOT_FOUND"),
        ("revoked", "DEVICE_NOT_FOUND"),
        ("facts-only", "DEVICE_CAPABILITY_DENIED"),
        ("hardware", "DEVICE_CAPABILITY_DENIED"),
        ("server", "SERVER_IDENTITY_MISMATCH"),
        ("epoch", "SYNC_EPOCH_MISMATCH"),
    ],
)
async def test_access_failures_never_create_metadata(runtime_engine, case, code):
    factory, contexts = await setup(runtime_engine)
    context = contexts[1]
    async with factory.begin() as session:
        if case == "revoked":
            await session.execute(
                text(
                    "UPDATE client_devices SET revoked_at=CURRENT_TIMESTAMP WHERE id=1"
                )
            )
        elif case == "facts-only":
            await session.execute(
                text(
                    "UPDATE user_sync_policies SET primary_editor_device_id=NULL WHERE user_id=1"
                )
            )
        elif case == "hardware":
            await session.execute(
                text("UPDATE client_devices SET device_class='hardware' WHERE id=1")
            )
    if case in ("foreign-device", "server", "epoch"):
        field = {
            "foreign-device": "device_id",
            "server": "server_instance_id",
            "epoch": "sync_epoch",
        }[case]
        context = context.model_copy(
            update={
                field: contexts[2].device_id
                if case == "foreign-device"
                else "d1000000-0000-4000-8000-000000000001"
            }
        )
    before = await snapshot(factory)
    with pytest.raises(DomainError) as error:
        await write_asset(factory, asset(context))
    assert error.value.code == code
    assert await snapshot(factory) == before


@pytest.mark.parametrize(
    "case,code",
    [
        ("identity", "ASSET_ID_REUSED"),
        ("hash", "ASSET_BLOB_METADATA_MISMATCH"),
        ("variants", "ASSET_BLOB_METADATA_MISMATCH"),
        ("pack-identity", "PACK_VERSION_REUSED"),
        ("pack-asset", "ASSET_ID_REUSED"),
        ("foreign-pack", "ASSET_NOT_FOUND"),
    ],
)
async def test_immutable_identity_and_descriptors_cannot_be_replaced(
    runtime_engine, case, code
):
    factory, contexts = await setup(runtime_engine)
    first = asset(contexts[1])
    await write_asset(factory, first)
    original_pack = pack(contexts[1], [first])
    await write_pack(factory, original_pack)
    before = await snapshot(factory)
    with pytest.raises(DomainError) as error:
        if case == "identity":
            await write_asset(factory, asset(contexts[1], name="替换"))
        elif case in ("hash", "variants"):
            changed = first.asset.light.model_dump()
            changed["width"] = 25
            await write_asset(
                factory,
                asset(
                    contexts[1],
                    asset_id="a1000000-0000-4000-8000-000000000002",
                    light=changed if case == "hash" else first.asset.light,
                    dark=changed,
                ),
            )
        elif case == "pack-identity":
            changed_pack = original_pack.pack.model_copy(update={"name": "替换"})
            await write_pack(
                factory, PackDeclaration(context=contexts[1], pack=changed_pack)
            )
        elif case == "pack-asset":
            await write_pack(
                factory,
                pack(contexts[1], [asset(contexts[1], name="不是原声明")], revision=2),
            )
        else:
            await write_pack(factory, pack(contexts[2], [first]), owner=2)
    assert error.value.code == code
    assert await snapshot(factory) == before


@pytest.mark.parametrize(
    "field,value",
    [
        ("byte_limit", 95),
        ("asset_limit", 0),
        ("metadata_byte_limit", 0),
        ("catalog_sequence", 9223372036854775807),
    ],
)
async def test_quota_overflow_or_catalog_exhaustion_rolls_back_all_reservations(
    runtime_engine, field, value
):
    factory, contexts = await setup(runtime_engine)
    async with factory.begin() as session:
        session.add(AppearanceAccount(user_id=1, **{field: value}))
    before = await snapshot(factory)
    with pytest.raises(DomainError) as error:
        await write_asset(factory, asset(contexts[1]))
    assert error.value.code == "ASSET_QUOTA_EXCEEDED"
    assert await snapshot(factory) == before


async def test_lowered_limits_allow_exact_retry_and_only_nonincreasing_dimensions(
    runtime_engine,
):
    factory, contexts = await setup(runtime_engine)
    first = asset(contexts[1])
    await write_asset(factory, first)
    original_pack = pack(contexts[1], [first])
    await write_pack(factory, original_pack)
    async with factory.begin() as session:
        await session.execute(
            text(
                "UPDATE appearance_accounts SET byte_limit=0,asset_limit=0,metadata_byte_limit=0 WHERE user_id=1"
            )
        )
    before = await snapshot(factory)
    assert (await write_asset(factory, first)).asset == first.asset
    assert await write_pack(factory, original_pack) == original_pack
    assert await snapshot(factory) == before
    with pytest.raises(DomainError):
        await write_pack(factory, pack(contexts[1], [first], revision=2))
    async with factory.begin() as session:
        await session.execute(
            text(
                "UPDATE appearance_accounts SET metadata_byte_limit=8388608 WHERE user_id=1"
            )
        )
    await write_pack(factory, pack(contexts[1], [first], revision=2))
    assert inspect_database(Path(runtime_engine.url.database)).valid


async def test_exact_quota_boundaries_and_utf8_metadata_charge(runtime_engine):
    factory, contexts = await setup(runtime_engine)
    request = asset(contexts[1])
    charge = len(
        json.dumps(
            request.asset.model_dump(mode="json"),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    )
    async with factory.begin() as session:
        session.add(
            AppearanceAccount(
                user_id=1, byte_limit=96, asset_limit=1, metadata_byte_limit=charge
            )
        )
    await write_asset(factory, request)
    rows = await snapshot(factory)
    quota = rows["appearance_accounts"][0]
    assert quota["reserved_bytes"] == 96 and quota["reserved_assets"] == 1
    assert quota["reserved_metadata_bytes"] == charge
    assert inspect_database(Path(runtime_engine.url.database)).valid


@pytest.mark.parametrize(
    "table", ["appearance_accounts", "account_icon_packs", "appearance_catalog"]
)
async def test_pack_write_failure_preserves_existing_assets_and_quota(
    runtime_engine, table
):
    factory, contexts = await setup(runtime_engine)
    request = asset(contexts[1])
    await write_asset(factory, request)
    before = await snapshot(factory)
    event = "UPDATE" if table == "appearance_accounts" else "INSERT"
    async with runtime_engine.begin() as connection:
        await connection.execute(
            text(
                f"CREATE TRIGGER fail_pack BEFORE {event} ON {table} BEGIN SELECT RAISE(ABORT, 'injected pack write'); END"
            )
        )
    with pytest.raises(IntegrityError, match="injected pack write"):
        await write_pack(factory, pack(contexts[1], [request]))
    assert await snapshot(factory) == before
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER fail_pack"))
    await write_pack(factory, pack(contexts[1], [request]))
    assert inspect_database(Path(runtime_engine.url.database)).valid


@pytest.mark.parametrize(
    "table",
    [
        "appearance_accounts",
        "account_icon_blobs",
        "account_icon_assets",
        "appearance_catalog",
    ],
)
async def test_insert_failure_at_each_stage_rolls_back_and_identity_can_retry(
    runtime_engine, table
):
    factory, contexts = await setup(runtime_engine)
    async with runtime_engine.begin() as connection:
        await connection.execute(
            text(
                f"CREATE TRIGGER fail_asset BEFORE INSERT ON {table} BEGIN SELECT RAISE(ABORT, 'injected asset write'); END"
            )
        )
    with pytest.raises(IntegrityError, match="injected asset write"):
        await write_asset(factory, asset(contexts[1]))
    assert all(not rows for rows in (await snapshot(factory)).values())
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER fail_asset"))
    await write_asset(factory, asset(contexts[1]))
    assert inspect_database(Path(runtime_engine.url.database)).valid


@pytest.mark.parametrize("same_identity", [False, True])
@pytest.mark.parametrize("new_account", [False, True])
async def test_real_concurrent_snapshot_writers_retry_without_double_reservation(
    runtime_engine, same_identity, new_account
):
    factory, contexts = await setup(runtime_engine)
    if not new_account:
        async with factory.begin() as session:
            session.add(AppearanceAccount(user_id=1, asset_limit=1))
    barrier = asyncio.Barrier(2)
    requests = [
        asset(contexts[1]),
        asset(
            contexts[1],
            asset_id=ASSET if same_identity else "a1000000-0000-4000-8000-000000000002",
        ),
    ]

    async def contender(request):
        try:
            async with factory.begin() as session:
                user = await session.get(User, 1)
                assert user is not None
                await read_quota(session, user, contexts[1])
                await barrier.wait()
                return await declare_asset(session, user, request)
        except OperationalError as error:
            assert is_sqlite_busy(error)
            return None

    results = await asyncio.wait_for(
        asyncio.gather(*(contender(request) for request in requests)), timeout=15
    )
    assert sum(value is None for value in results) == 1
    loser = requests[results.index(None)]
    if same_identity or new_account:
        assert (await write_asset(factory, loser)).asset == loser.asset
    else:
        with pytest.raises(DomainError) as error:
            await write_asset(factory, loser)
        assert error.value.code == "ASSET_QUOTA_EXCEEDED"
    rows = await snapshot(factory)
    expected_count = 2 if new_account and not same_identity else 1
    assert (
        len(rows["account_icon_assets"])
        == len(rows["appearance_catalog"])
        == expected_count
    )
    assert len(rows["account_icon_blobs"]) == 1
    assert rows["appearance_accounts"][0]["reserved_bytes"] == 96
    assert rows["appearance_accounts"][0]["reserved_assets"] == expected_count
    assert inspect_database(Path(runtime_engine.url.database)).valid
