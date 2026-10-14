"""Authenticated metadata-only recovery preflight; never byte/activation proof."""

from copy import deepcopy
from typing import Any
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker

from src.auth.models import User
from src.v2.asset_api_contract import AssetSyncContext
from src.v2.models import ClientDevice, UserSyncPolicy
from src.v2.recovery_snapshot_contract import ReplicaRecoveryPreviewResponse
from src.v2.replica_context import EPOCH_HEADER, INSTANCE_HEADER
from tests.account_fixtures import account_password_hash
from tests.test_asset_declarations import (
    ASSET,
    PACK,
    asset,
    pack,
    write_asset,
    write_pack,
)
from tests.test_challenge_profile import batch, call_push
from tests.test_http_commit_boundary import database_state, runtime_http as runtime_http
from tests.test_next_structural_sync import metric, operation
from tests.test_object_appearance import appearance
from tests.test_recovery_snapshot import PATH, parameters
from tests.test_v5_production_sync import production_v5 as production_v5


async def declared(production_v5, *, owner=1, revision=1):
    _, engine, headers, device = production_v5
    factory = async_sessionmaker(engine, expire_on_commit=False)
    if owner != 1:
        device = str(uuid4())
        async with factory.begin() as session:
            session.add(
                User(
                    id=owner,
                    username=f"preview-other-{owner}",
                    password_hash=account_password_hash(),
                )
            )
            await session.flush()
            other = ClientDevice(
                user_id=owner,
                public_id=device,
                installation_id=f"preview-other-{owner}",
                platform="android",
                device_class="interactive",
            )
            session.add(other)
            await session.flush()
            session.add(
                UserSyncPolicy(user_id=owner, primary_editor_device_id=other.id)
            )
    context = AssetSyncContext(
        server_instance_id=headers[INSTANCE_HEADER],
        sync_epoch=headers[EPOCH_HEADER],
        device_id=device,
    )
    request = asset(context)
    await write_asset(factory, request, owner)
    selected = pack(context, [request], revision=revision)
    await write_pack(factory, selected, owner)
    return selected


async def get_materials(production_v5, *, selected=True, revision=1):
    client, engine, headers, device = production_v5
    query = {**parameters(device), "include_materials": "1"}
    if selected:
        query.update(pack_id=PACK, pack_revision=str(revision))
    before = await database_state(engine)
    response = await client.get(PATH, headers=headers, params=query)
    assert response.status_code == 200, response.text
    assert response.headers["Cache-Control"] == "private, no-store"
    parsed = ReplicaRecoveryPreviewResponse.model_validate(response.json())
    assert parsed.material_preview is not None
    assert await database_state(engine) == before
    return response.json()


async def test_exact_pack_pending_variants_and_no_selection_remain_read_only(
    production_v5,
):
    selected = await declared(production_v5)
    response = await get_materials(production_v5)
    material = response["material_preview"]
    assert material["requested_pack"] == {"pack_id": PACK, "revision": 1}
    assert material["pack"] == selected.pack.model_dump(mode="json")
    assert material["missing_assets"] == []
    assert len(material["assets"]) == 1
    assert material["assets"][0]["asset"] == selected.pack.assets[0].model_dump(
        mode="json"
    )
    assert material["assets"][0]["ready_variants"] == []
    unselected = (await get_materials(production_v5, selected=False))[
        "material_preview"
    ]
    assert unselected == dict(
        material_contract=1,
        requested_pack=None,
        pack=None,
        assets=[],
        missing_assets=[],
    )


@pytest.mark.parametrize("case", ["missing", "different-version", "other-owner"])
async def test_missing_exact_pack_never_falls_back_or_leaks_other_owner(
    production_v5, case
):
    if case != "missing":
        await declared(
            production_v5,
            owner=2 if case == "other-owner" else 1,
            revision=1 if case == "other-owner" else 2,
        )
    material = (await get_materials(production_v5))["material_preview"]
    assert material == dict(
        material_contract=1,
        requested_pack={"pack_id": PACK, "revision": 1},
        pack=None,
        assets=[],
        missing_assets=[],
    )


async def test_live_direct_reference_is_included_without_selected_pack(production_v5):
    await declared(production_v5)
    client, _, headers, device = production_v5
    body = metric()
    body["appearance"] = appearance(asset_id=ASSET).model_dump(mode="json")
    await call_push(client, headers, batch(device, [operation(body, kind="metric")]))
    material = (await get_materials(production_v5, selected=False))["material_preview"]
    assert material["pack"] is None
    assert [item["asset"]["asset_id"] for item in material["assets"]] == [ASSET]
    assert material["missing_assets"] == []


async def test_missing_pack_asset_is_reported_not_silently_omitted(production_v5):
    await declared(production_v5)
    _, engine, _, _ = production_v5
    # Isolated damaged metadata fixture, not deletion through a product API.
    async with engine.begin() as connection:
        await connection.execute(
            text("DELETE FROM appearance_catalog WHERE asset_id IS NOT NULL")
        )
        await connection.execute(text("DELETE FROM account_icon_assets"))
    material = (await get_materials(production_v5))["material_preview"]
    assert material["pack"]["pack_id"] == PACK
    assert material["assets"] == [] and material["missing_assets"] == [ASSET]


async def test_ready_metadata_does_not_claim_native_file_proof(production_v5):
    await declared(production_v5)
    _, engine, _, _ = production_v5
    # Deliberately no filesystem installation: the result is diagnostic metadata.
    async with engine.begin() as connection:
        await connection.execute(
            text(
                "UPDATE account_icon_blobs SET ready_at='2026-10-13 00:00:00', validation_profile='png-v1'"
            )
        )
    material = (await get_materials(production_v5))["material_preview"]
    assert material["assets"][0]["ready_variants"] == ["light", "dark"]
    assert set(material) == {
        "material_contract",
        "requested_pack",
        "pack",
        "assets",
        "missing_assets",
    }


@pytest.mark.parametrize(
    "case",
    [
        "missing-id",
        "missing-revision",
        "no-opt-in",
        "zero",
        "leading-zero",
        "oversize",
        "boolean",
        "duplicate-id",
        "duplicate-opt-in",
        "unknown-owner",
    ],
)
async def test_noncanonical_material_query_is_rejected_without_writes(
    production_v5, case
):
    client, engine, headers, device = production_v5
    query: Any = {
        **parameters(device),
        "include_materials": "1",
        "pack_id": PACK,
        "pack_revision": "1",
    }
    if case == "missing-id":
        query.pop("pack_id")
    elif case == "missing-revision":
        query.pop("pack_revision")
    elif case == "no-opt-in":
        query.pop("include_materials")
    elif case in {"zero", "leading-zero", "oversize", "boolean"}:
        query["pack_revision"] = dict(
            zero="0",
            **{"leading-zero": "01", "oversize": "2147483648", "boolean": "true"},
        )[case]
    elif case.startswith("duplicate"):
        name = "pack_id" if case == "duplicate-id" else "include_materials"
        query = [*query.items(), (name, query[name])]
    else:
        query["owner_user_id"] = "2"
    before = await database_state(engine)
    response = await client.get(PATH, headers=headers, params=query)
    validation = case in {"zero", "leading-zero", "boolean"}
    assert response.status_code == (422 if validation else 400), response.text
    if not validation:
        assert response.json()["detail"]["code"] == "INVALID_SYNC_CONTEXT"
    assert await database_state(engine) == before


@pytest.mark.parametrize(
    "case",
    [
        "version",
        "revision-bool",
        "different-pack",
        "different-revision",
        "metadata",
        "wrong-scope",
        "wrong-device",
        "omitted",
        "duplicate",
        "overlap",
        "extra",
        "unknown-field",
    ],
)
async def test_complete_material_envelope_rejects_unbound_or_incomplete_evidence(
    production_v5, case
):
    await declared(production_v5)
    response = deepcopy(await get_materials(production_v5))
    material = response["material_preview"]
    if case == "version":
        material["material_contract"] = True
    elif case == "revision-bool":
        material["requested_pack"]["revision"] = True
    elif case == "different-pack":
        material["requested_pack"]["pack_id"] = str(uuid4())
    elif case == "different-revision":
        material["requested_pack"]["revision"] = 2
    elif case == "metadata":
        material["assets"][0]["asset"]["name"] = "changed"
    elif case in {"wrong-scope", "wrong-device"}:
        material["assets"][0]["context"][
            "sync_epoch" if case == "wrong-scope" else "device_id"
        ] = str(uuid4())
    elif case == "omitted":
        material["assets"] = []
    elif case == "duplicate":
        material["assets"].append(deepcopy(material["assets"][0]))
    elif case == "overlap":
        material["missing_assets"] = [ASSET]
    elif case == "extra":
        material["missing_assets"] = [str(uuid4())]
    else:
        material["activation_authorized"] = True
    with pytest.raises(ValueError):
        ReplicaRecoveryPreviewResponse.model_validate(response)


async def test_corrupt_stored_pack_asset_disagreement_has_stable_error(production_v5):
    await declared(production_v5)
    client, engine, headers, device = production_v5
    # Keep canonical stored JSON/byte length while simulating inconsistent archive rows.
    async with engine.begin() as connection:
        await connection.execute(
            text(
                "UPDATE account_icon_assets SET metadata_json=replace(metadata_json, '喝水', '饮水')"
            )
        )
    before = await database_state(engine)
    response = await client.get(
        PATH,
        headers=headers,
        params={
            **parameters(device),
            "include_materials": "1",
            "pack_id": PACK,
            "pack_revision": "1",
        },
    )
    assert response.status_code == 400, response.text
    assert response.json()["detail"]["code"] == "RECOVERY_STATE_INVALID"
    assert await database_state(engine) == before
