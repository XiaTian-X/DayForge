"""Actual main app, migrated SQLite and production COMMIT dependencies."""

import asyncio
import base64
import json
from pathlib import Path
import sqlite3
from threading import Event
from urllib.parse import urlencode
from types import SimpleNamespace

from httpx import ASGITransport, AsyncClient, Client
import pytest
from sqlalchemy import text

from src.auth.models import User
from src.auth.service import create_access_token
from src.main import app
from src.storage.asset_files import AssetFiles
from src.tokens.models import ApiToken
from src.tokens.service import generate_token, hash_token
from tests.asset_file_fixtures import SVG, blob, directory, intents
from tests.test_asset_declarations import ASSET, PACK, asset, pack, setup, snapshot
from tests.test_asset_transfers import barrier
from tests.test_http_commit_boundary import database_state
from tests.test_sync_contract_matrix import fixture, with_device
from tests.test_asset_declarations import write_asset, write_pack
from src.storage.asset_root import AssetRootLease, AssetRootBusy
from tests.test_jwt_runtime_server import isolated_server


@pytest.fixture(params=["jwt", "api"])
async def production_assets(runtime_engine, tmp_path, monkeypatch, request):
    sessions, contexts = await setup(runtime_engine)
    headers = {}
    async with sessions.begin() as session:
        await session.execute(text("UPDATE server_instances SET protocol_version=5"))
        await session.execute(
            text("UPDATE client_devices SET registered_protocol_version=5")
        )
        for owner in (1, 2):
            user = await session.get(User, owner)
            assert user is not None
            if request.param == "jwt":
                header = "Bearer " + create_access_token(
                    {"sub": str(owner), "ver": user.auth_version}
                )
            else:
                key = generate_token()
                session.add(
                    ApiToken(
                        user_id=owner,
                        name="production-probe",
                        token_hash=hash_token(key),
                        prefix=key[:11],
                    )
                )
                header = "Token " + key
            headers[owner] = {"Authorization": header, "X-DayForge-Protocol": "5"}
    root = tmp_path / "appearance"
    root.mkdir()
    monkeypatch.setattr(
        "src.main.get_settings",
        lambda: SimpleNamespace(
            ASSET_ROOT=root, ADMIN_USERNAME=None, ADMIN_PASSWORD=None
        ),
    )
    async with app.router.lifespan_context(app):
        lifecycle = app.state.appearance
        await asyncio.wait_for(lifecycle.startup_finished.wait(), 5)
        assert lifecycle.runtime.ready
        async with AsyncClient(
            transport=ASGITransport(app=app, raise_app_exceptions=False),
            base_url="http://test",
        ) as client:
            yield client, sessions, contexts, headers, lifecycle, root


def asset_path():
    return f"/api/v2/appearance/assets/{ASSET}"


def content_path(variant="light"):
    return asset_path() + "/content/" + variant


async def declare(probe, owner=1):
    client, _, contexts, headers, _, _ = probe
    declaration = asset(contexts[owner], light=blob(), dark=blob())
    response = await client.put(
        asset_path(), headers=headers[owner], json=declaration.model_dump(mode="json")
    )
    assert response.status_code == 200, response.text
    return declaration


async def test_production_all_paths_replay_owned_bytes_and_catalog(production_assets):
    client, sessions, contexts, headers, _, root = production_assets
    first = await declare(production_assets)
    pending = await client.get(
        content_path(), params=contexts[1].model_dump(), headers=headers[1]
    )
    assert (
        pending.status_code == 409
        and pending.json()["detail"]["code"] == "ASSET_CONTENT_PENDING"
    )
    declaration = pack(contexts[1], [first])
    pack_path = f"/api/v2/appearance/packs/{PACK}/versions/1"
    for _ in range(2):
        result = await client.put(
            pack_path, headers=headers[1], json=declaration.model_dump(mode="json")
        )
        assert result.status_code == 200 and result.json() == declaration.model_dump(
            mode="json"
        )
    assert (
        await client.get(pack_path, params=contexts[1].model_dump(), headers=headers[1])
    ).json() == declaration.model_dump(mode="json")
    stored = await snapshot(sessions)
    assert len(stored["appearance_catalog"]) == 2
    for _ in range(2):
        uploaded = await client.put(
            content_path(),
            params=contexts[1].model_dump(),
            headers=headers[1] | {"Content-Type": "application/octet-stream"},
            content=SVG,
        )
        assert uploaded.status_code == 200, uploaded.text
        assert uploaded.json()["blob"] == blob().model_dump(mode="json")
    fetched = await client.get(
        content_path("dark"), params=contexts[1].model_dump(), headers=headers[1]
    )
    assert fetched.status_code == 200 and fetched.content == SVG
    assert fetched.headers["content-type"].split(";")[0] == "image/svg+xml"
    assert fetched.headers["cache-control"] == "private, no-store"
    assert fetched.headers["x-content-type-options"] == "nosniff"
    result = await client.get(
        asset_path(), params=contexts[1].model_dump(), headers=headers[1]
    )
    assert result.json()["ready_variants"] == ["light", "dark"]
    catalog = await client.get(
        "/api/v2/appearance/catalog",
        params=contexts[1].model_dump() | {"limit": 1},
        headers=headers[1],
    )
    page = catalog.json()
    assert catalog.status_code == 200 and page["has_more"] and page["next_cursor"] == 1
    next_page = await client.get(
        "/api/v2/appearance/catalog",
        params=contexts[1].model_dump() | {"after": 1, "through": 2},
        headers=headers[1],
    )
    assert next_page.status_code == 200 and not next_page.json()["has_more"]
    quota = await client.get(
        "/api/v2/appearance/quota", params=contexts[1].model_dump(), headers=headers[1]
    )
    assert quota.status_code == 200 and quota.json()["reserved_bytes"] == len(SVG)
    assert quota.json()["reserved_assets"] == 1
    foreign = await client.get(
        content_path(), params=contexts[2].model_dump(), headers=headers[2]
    )
    assert foreign.status_code == 404
    assert intents(AssetFiles(root), (await sessions_user(sessions, 1)).public_id) == []


async def sessions_user(sessions, owner):
    async with sessions() as session:
        return await session.get(User, owner)


@pytest.mark.parametrize(
    "change",
    [
        "server4",
        "proof4",
        "proof-null",
        "missing",
        "duplicate",
        "future",
        "epoch",
        "foreign-device",
        "disabled",
        "capability",
    ],
)
async def test_production_gate_rejections_do_not_mutate(production_assets, change):
    client, sessions, contexts, headers, _, _ = production_assets
    request_headers = list(headers[1].items())
    body = asset(contexts[1]).model_dump(mode="json")
    expected = 426
    async with sessions.begin() as session:
        if change == "server4":
            await session.execute(
                text("UPDATE server_instances SET protocol_version=4")
            )
        elif change in {"proof4", "proof-null"}:
            await session.execute(
                text(
                    "UPDATE client_devices SET registered_protocol_version=:value WHERE id=1"
                ),
                {"value": 4 if change == "proof4" else None},
            )
        elif change == "disabled":
            await session.execute(
                text("UPDATE users SET is_active=0,status='disabled' WHERE id=1")
            )
            expected = 401
        elif change == "capability":
            await session.execute(
                text("UPDATE client_devices SET structural_edit_enabled=0 WHERE id=1")
            )
            await session.execute(
                text(
                    "UPDATE user_sync_policies SET primary_editor_device_id=NULL WHERE user_id=1"
                )
            )
            expected = 403
    if change == "missing":
        request_headers = request_headers[:1]
    elif change == "duplicate":
        request_headers.append(("X-DayForge-Protocol", "5"))
    elif change == "future":
        request_headers[1] = ("X-DayForge-Protocol", "6")
    elif change == "epoch":
        body["context"]["sync_epoch"] = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        expected = 409
    elif change == "foreign-device":
        body["context"]["device_id"] = contexts[2].device_id
        expected = 404
    before = await database_state(sessions.kw["bind"])
    result = await client.put(asset_path(), headers=request_headers, json=body)
    assert result.status_code == expected, result.text
    assert await database_state(sessions.kw["bind"]) == before


@pytest.mark.parametrize(
    "invalid_body",
    [
        b'{"context":{},"context":{}}',
        b'{"x":NaN}',
        b'{"x":1e999}',
        b'{"x":"\\ud800"}',
        b'{"x":' + b"[" * 17 + b"0" + b"]" * 17 + b"}",
        b"\xff",
        b"[]",
        b"{",
    ],
)
async def test_bounded_json_rejects_noncanonical_input(production_assets, invalid_body):
    client, sessions, _, headers, _, _ = production_assets
    before = await database_state(sessions.kw["bind"])
    response = await client.put(
        asset_path(),
        headers=headers[1] | {"Content-Type": "application/json"},
        content=invalid_body,
    )
    assert (
        response.status_code == 422
        and response.json()["detail"]["code"] == "ASSET_INPUT_INVALID"
    )
    assert len(response.content) < 300
    assert await database_state(sessions.kw["bind"]) == before


@pytest.mark.parametrize(
    "case",
    [
        "unknown-query",
        "duplicate-query",
        "path-binding",
        "uppercase",
        "compressed",
        "wrong-mime",
        "oversized",
        "unknown-body",
        "no-redirect",
    ],
)
async def test_production_input_binding_is_strict(production_assets, case):
    client, sessions, contexts, headers, _, _ = production_assets
    body = asset(contexts[1]).model_dump(mode="json")
    path = asset_path()
    request_headers = headers[1] | {"Content-Type": "application/json"}
    if case == "unknown-query":
        path += "?owner_user_id=1"
    elif case == "duplicate-query":
        path = (
            "/api/v2/appearance/quota?"
            + "&".join(f"{k}={v}" for k, v in contexts[1].model_dump().items())
            + "&device_id="
            + contexts[1].device_id
        )
    elif case == "path-binding":
        body["asset"]["asset_id"] = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    elif case == "uppercase":
        path = path.replace(ASSET, ASSET.upper())
    elif case == "compressed":
        request_headers["Content-Encoding"] = "gzip"
    elif case == "wrong-mime":
        request_headers["Content-Type"] = "text/plain"
    elif case == "oversized":
        request_headers["Content-Length"] = "1048577"
    elif case == "unknown-body":
        body["owner"] = 1
    elif case == "no-redirect":
        path += "/"
    before = await database_state(sessions.kw["bind"])
    result = await (
        client.get(path, headers=request_headers)
        if case == "duplicate-query"
        else client.put(
            path, headers=request_headers, content=json.dumps(body).encode()
        )
    )
    assert result.status_code == (
        413 if case == "oversized" else 404 if case == "no-redirect" else 422
    ), result.text
    assert "location" not in result.headers
    assert await database_state(sessions.kw["bind"]) == before


@pytest.mark.parametrize("phase", ["body", "file"])
@pytest.mark.parametrize(
    "revocation",
    [
        "proof",
        "server",
        "epoch",
        "user",
        "device",
        "capability",
        "credential",
        "identity",
    ],
)
async def test_reauthorization_after_network_and_file_io(
    production_assets, monkeypatch, phase, revocation
):
    client, sessions, contexts, headers, lifecycle, _ = production_assets
    await declare(production_assets)
    reached, released = asyncio.Event(), asyncio.Event()
    thread_release = None
    if phase == "file":
        reached, thread_release = barrier(
            monkeypatch, lifecycle.runtime._transfers.files, "publish"
        )

    async def source():
        if phase == "body":
            reached.set()
            await released.wait()
        yield SVG

    request = asyncio.create_task(
        client.put(
            content_path(),
            params=contexts[1].model_dump(),
            headers=headers[1] | {"Content-Type": "application/octet-stream"},
            content=source(),
        )
    )
    try:
        await asyncio.wait_for(reached.wait(), 5)
        sql = {
            "proof": "UPDATE client_devices SET registered_protocol_version=4 WHERE id=1",
            "server": "UPDATE server_instances SET protocol_version=4",
            "epoch": "UPDATE server_instances SET sync_epoch='eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'",
            "user": "UPDATE users SET is_active=0,status='disabled' WHERE id=1",
            "device": "UPDATE client_devices SET revoked_at='2026-10-05T00:00:00Z' WHERE id=1",
            "capability": "UPDATE user_sync_policies SET primary_editor_device_id=NULL WHERE user_id=1",
            "credential": "DELETE FROM api_tokens WHERE user_id=1"
            if headers[1]["Authorization"].startswith("Token ")
            else "UPDATE users SET auth_version=auth_version+1 WHERE id=1",
            "identity": "UPDATE server_instances SET instance_uuid='eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'",
        }[revocation]
        # An independent connection can write while network or file work is held.
        async with sessions.begin() as session:
            await session.execute(text(sql))
        released.set()
        if thread_release is not None:
            thread_release.set()
        result = await request
        assert result.status_code in {401, 403, 404, 409, 426}, result.text
        assert (
            result.status_code
            == {
                "proof": 426,
                "server": 426,
                "epoch": 409,
                "user": 401,
                "device": 404,
                "capability": 403,
                "credential": 401,
                "identity": 409,
            }[revocation]
        )
        async with sessions() as session:
            assert (
                await session.execute(
                    text(
                        "SELECT ready_at FROM account_icon_blobs WHERE owner_user_id=1"
                    )
                )
            ).scalar_one() is None
    finally:
        released.set()
        if thread_release is not None:
            thread_release.set()
        if not request.done():
            request.cancel()
            await asyncio.gather(request, return_exceptions=True)


async def test_production_commit_failure_preserves_pending_and_original_retry(
    production_assets,
):
    client, sessions, contexts, headers, lifecycle, root = production_assets
    await declare(production_assets)
    async with sessions.kw["bind"].begin() as connection:
        await connection.execute(
            text(
                "CREATE TABLE content_commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
            )
        )
        await connection.execute(
            text(
                "CREATE TRIGGER content_failure AFTER UPDATE OF ready_at ON account_icon_blobs BEGIN INSERT INTO content_commit_fault VALUES (-999999); END"
            )
        )
    before = await snapshot(sessions)
    result = await client.put(
        content_path(),
        params=contexts[1].model_dump(),
        headers=headers[1] | {"Content-Type": "application/octet-stream"},
        content=SVG,
    )
    assert result.status_code == 500
    assert await snapshot(sessions) == before
    owner = (await sessions_user(sessions, 1)).public_id
    assert (directory(root, owner) / blob().sha256).read_bytes() == SVG
    async with sessions.kw["bind"].begin() as connection:
        await connection.execute(text("DROP TRIGGER content_failure"))
    # Explicit retry after bounded recovery; wait on the actual recovery signal, not elapsed sleep.
    await asyncio.wait_for(lifecycle.available.wait(), 5)
    assert intents(AssetFiles(root), owner) == []
    retry = await client.put(
        content_path(),
        params=contexts[1].model_dump(),
        headers=headers[1] | {"Content-Type": "application/octet-stream"},
        content=SVG,
    )
    assert retry.status_code == 200, retry.text


async def raw_call(path, headers, *, method="GET", query=None, receive=None, send=None):
    messages = []

    async def default_receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def record(message):
        messages.append(message)
        if send is not None:
            await send(message)

    await app(
        {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "scheme": "http",
            "method": method,
            "path": path,
            "root_path": "",
            "raw_path": path.encode(),
            "query_string": urlencode(query or {}).encode(),
            "headers": [(k.lower().encode(), v.encode()) for k, v in headers.items()],
            "client": ("127.0.0.1", 12345),
            "server": ("test", 80),
        },
        receive or default_receive,
        record,
    )
    return messages


@pytest.mark.parametrize(
    "kind", ["body", "response", "metadata-response", "metadata-error-response"]
)
async def test_full_http_capacity_never_consumes_third_input(production_assets, kind):
    client, _, contexts, headers, _, _ = production_assets
    await declare(production_assets)
    if kind == "response":
        uploaded = await client.put(
            content_path(),
            params=contexts[1].model_dump(),
            headers=headers[1] | {"Content-Type": "application/octet-stream"},
            content=SVG,
        )
        assert uploaded.status_code == 200
    reached = [asyncio.Event(), asyncio.Event()]
    releases = [asyncio.Event(), asyncio.Event()]
    calls = []
    metadata = kind.startswith("metadata")
    error = kind == "metadata-error-response"
    path = (
        asset_path()
        if error
        else "/api/v2/appearance/quota"
        if metadata
        else content_path()
    )
    method = "PUT" if kind == "body" or error else "GET"
    request_headers = headers[1] | (
        {"Content-Type": "application/octet-stream"} if kind == "body" else {}
    )
    if error:
        request_headers["Content-Type"] = "application/json"

    async def error_body():
        return {
            "type": "http.request",
            "body": asset(
                contexts[1],
                light=blob(),
                dark=blob(),
                name="immutable identity cannot be renamed",
            )
            .model_dump_json()
            .encode(),
            "more_body": False,
        }

    def receiver(index):
        async def receive():
            reached[index].set()
            await releases[index].wait()
            return {"type": "http.request", "body": SVG, "more_body": False}

        return receive

    def sender(index):
        async def send(message):
            if message["type"] == "http.response.body":
                reached[index].set()
                await releases[index].wait()

        return send

    try:
        for index in range(2):
            calls.append(
                asyncio.create_task(
                    raw_call(
                        path,
                        request_headers,
                        method=method,
                        query=None if error else contexts[1].model_dump(),
                        receive=error_body
                        if error
                        else receiver(index)
                        if kind == "body"
                        else None,
                        send=sender(index) if kind != "body" else None,
                    )
                )
            )
            await asyncio.wait_for(reached[index].wait(), 5)
        await asyncio.wait_for(asyncio.gather(*(event.wait() for event in reached)), 5)
        consumed = False

        async def forbidden_receive():
            nonlocal consumed
            consumed = True
            pytest.fail("Full HTTP admission must reject before receive")

        third = await raw_call(
            path,
            request_headers,
            method=method,
            query=None if error else contexts[1].model_dump(),
            receive=forbidden_receive,
        )
        assert third[0]["status"] == 503 and not consumed
        assert (await client.get("/health")).status_code == 200
        completed = []
        for index, call in enumerate(calls):
            releases[index].set()
            completed.append(await call)
        assert all(
            messages[0]["status"] == (409 if error else 200) for messages in completed
        )
    finally:
        for release in releases:
            release.set()
        await asyncio.gather(*calls, return_exceptions=True)


@pytest.mark.parametrize(
    "reason", ["missing-auth", "protocol", "foreign-device", "oversized-length"]
)
async def test_upload_rejected_before_receiving_any_bytes(production_assets, reason):
    _, _, contexts, headers, _, _ = production_assets
    await declare(production_assets)
    request_headers = headers[1] | {"Content-Type": "application/octet-stream"}
    query = contexts[1].model_dump()
    expected = {
        "missing-auth": 401,
        "protocol": 426,
        "foreign-device": 404,
        "oversized-length": 413,
    }[reason]
    if reason == "missing-auth":
        del request_headers["Authorization"]
    elif reason == "protocol":
        del request_headers["X-DayForge-Protocol"]
    elif reason == "foreign-device":
        query["device_id"] = contexts[2].device_id
    else:
        request_headers["Content-Length"] = str(len(SVG) + 1)

    async def forbidden_receive():
        pytest.fail("Rejected request consumed body")

    messages = await raw_call(
        content_path(),
        request_headers,
        method="PUT",
        query=query,
        receive=forbidden_receive,
    )
    assert messages[0]["status"] == expected


async def test_http_cancelled_worker_is_drained_before_recovery_and_retry(
    production_assets, monkeypatch
):
    client, sessions, contexts, headers, lifecycle, root = production_assets
    await declare(production_assets)
    reached, release = barrier(
        monkeypatch, lifecycle.runtime._transfers.files, "publish"
    )
    task = asyncio.create_task(
        client.put(
            content_path(),
            params=contexts[1].model_dump(),
            headers=headers[1] | {"Content-Type": "application/octet-stream"},
            content=SVG,
        )
    )
    try:
        await asyncio.wait_for(reached.wait(), 5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert not lifecycle.runtime.ready
        response = await client.get(
            content_path(), params=contexts[1].model_dump(), headers=headers[1]
        )
        assert response.status_code == 503
        assert not lifecycle.available.is_set()
        owner = (await sessions_user(sessions, 1)).public_id
        assert len(intents(AssetFiles(root), owner)) == 1
        release.set()
        await asyncio.wait_for(lifecycle.available.wait(), 5)
        assert intents(AssetFiles(root), owner) == []
        async with sessions() as session:
            assert (
                await session.execute(
                    text(
                        "SELECT ready_at FROM account_icon_blobs WHERE owner_user_id=1"
                    )
                )
            ).scalar_one() is None
        retry = await client.put(
            content_path(),
            params=contexts[1].model_dump(),
            headers=headers[1] | {"Content-Type": "application/octet-stream"},
            content=SVG,
        )
        assert retry.status_code == 200
    finally:
        release.set()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.parametrize("bad", ["missing", "corrupt"])
async def test_ready_content_storage_fault_is_not_pending_or_success(
    production_assets, bad
):
    client, sessions, contexts, headers, _, root = production_assets
    await declare(production_assets)
    installed = await client.put(
        content_path(),
        params=contexts[1].model_dump(),
        headers=headers[1] | {"Content-Type": "application/octet-stream"},
        content=SVG,
    )
    assert installed.status_code == 200
    owner = (await sessions_user(sessions, 1)).public_id
    target = directory(root, owner) / blob().sha256
    if bad == "missing":
        target.unlink()  # Exact synthetic fixture file; no production path.
    else:
        target.write_bytes(b"corrupt fixture")
    result = await client.get(
        content_path(), params=contexts[1].model_dump(), headers=headers[1]
    )
    assert (
        result.status_code == 503
        and result.json()["detail"]["code"] == "ASSET_CONTENT_UNAVAILABLE"
    )
    async with sessions() as session:
        assert (
            await session.execute(
                text("SELECT ready_at FROM account_icon_blobs WHERE owner_user_id=1")
            )
        ).scalar_one() is not None


@pytest.mark.parametrize("stage", ["metadata", "content"])
async def test_total_receive_deadline_and_disconnect_leave_no_writes(
    production_assets, monkeypatch, stage
):
    _, sessions, contexts, headers, lifecycle, _ = production_assets
    await declare(production_assets)
    monkeypatch.setattr("src.v2.asset_http_input.BODY_SECONDS", 0.01)
    path = asset_path() if stage == "metadata" else content_path()
    request_headers = headers[1] | {
        "Content-Type": "application/json"
        if stage == "metadata"
        else "application/octet-stream"
    }
    before = await database_state(sessions.kw["bind"])

    async def stalled():
        await asyncio.Event().wait()

    result = await raw_call(
        path,
        request_headers,
        method="PUT",
        query=None if stage == "metadata" else contexts[1].model_dump(),
        receive=stalled,
    )
    assert result[0]["status"] == 503
    assert await database_state(sessions.kw["bind"]) == before
    assert lifecycle.runtime.ready


@pytest.mark.parametrize("fault", ["unset", "missing", "unknown", "link"])
async def test_lifespan_material_failure_does_not_block_sync_or_timer(
    runtime_engine, tmp_path, monkeypatch, fault
):
    sessions, contexts = await setup(runtime_engine)
    root = tmp_path / "unavailable-material"
    if fault in {"unknown", "link"}:
        root.mkdir()
        if fault == "unknown":
            (root / "unproven.part").write_bytes(b"retain evidence")
        else:
            other = tmp_path / "other"
            other.mkdir()
            (root / "accounts").symlink_to(other, target_is_directory=True)
    async with sessions() as session:
        user = await session.get(User, 1)
        header = {
            "Authorization": "Bearer "
            + create_access_token({"sub": "1", "ver": user.auth_version})
        }
    monkeypatch.setattr(
        "src.main.get_settings",
        lambda: SimpleNamespace(
            ASSET_ROOT=None if fault == "unset" else root,
            ADMIN_USERNAME=None,
            ADMIN_PASSWORD=None,
        ),
    )
    async with app.router.lifespan_context(app):
        lifecycle = app.state.appearance
        if fault != "unset":
            await asyncio.wait_for(lifecycle.startup_finished.wait(), 5)
            assert not lifecycle.runtime.ready
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            identity = await client.get("/api/v2/system/identity")
            assert (
                identity.status_code == 200 and identity.json()["protocol_version"] == 4
            )
            rejected = await client.put(
                asset_path(),
                headers=header | {"X-DayForge-Protocol": "5"},
                json=asset(contexts[1]).model_dump(mode="json"),
            )
            assert rejected.status_code == 426
            pushed = await client.post(
                "/api/v2/sync/push",
                headers=header,
                json=with_device(
                    fixture("client/push-all-entities.json"), contexts[1].device_id
                ),
            )
            assert pushed.status_code == 200
            assert all(item["status"] == "applied" for item in pushed.json()["results"])
            timers = await client.post(
                "/api/v2/timers/commands",
                headers=header,
                json=with_device(
                    fixture("client/timer-commands.json"), contexts[1].device_id
                ),
            )
            assert timers.status_code == 200
            assert all(item["status"] == "applied" for item in timers.json()["results"])
            assert (await client.get("/health")).status_code == 200
            # Test-only activation lets us verify root failure, not the v4 gate.
            async with sessions.begin() as session:
                await session.execute(
                    text("UPDATE server_instances SET protocol_version=5")
                )
                await session.execute(
                    text("UPDATE client_devices SET registered_protocol_version=5")
                )
            declared = await client.put(
                asset_path(),
                headers=header | {"X-DayForge-Protocol": "5"},
                json=asset(contexts[1], light=blob(), dark=blob()).model_dump(
                    mode="json"
                ),
            )
            assert declared.status_code == 200
            content = await client.get(
                content_path(),
                headers=header | {"X-DayForge-Protocol": "5"},
                params=contexts[1].model_dump(),
            )
            assert content.status_code == 503
    if fault == "missing":
        assert not root.exists()
    elif fault == "unknown":
        assert (root / "unproven.part").read_bytes() == b"retain evidence"
    elif fault == "link":
        assert (root / "accounts").is_symlink()


async def test_deferred_cleanup_success_and_backoff_then_real_recovery(
    production_assets, monkeypatch
):
    client, sessions, contexts, headers, lifecycle, root = production_assets
    await declare(production_assets)
    files = lifecycle.runtime._transfers.files
    original = files.finish
    original_recover = lifecycle.runtime.recover
    scans = 0

    async def count_recover():
        nonlocal scans
        scans += 1
        return await original_recover()

    monkeypatch.setattr(lifecycle.runtime, "recover", count_recover)
    retries, retry_reached, release_retry = [], asyncio.Event(), asyncio.Event()

    def fail(receipt):
        raise OSError("must not leak this path or credential")

    async def backoff(seconds):
        retries.append(seconds)
        retry_reached.set()
        await release_retry.wait()

    monkeypatch.setattr(files, "finish", fail)
    monkeypatch.setattr(lifecycle, "_wait_retry", backoff)
    uploaded = await client.put(
        content_path(),
        params=contexts[1].model_dump(),
        headers=headers[1] | {"Content-Type": "application/octet-stream"},
        content=SVG,
    )
    assert uploaded.status_code == 200
    await asyncio.wait_for(retry_reached.wait(), 5)
    owner = (await sessions_user(sessions, 1)).public_id
    assert len(intents(files, owner)) == 1 and not lifecycle.runtime.ready
    for _ in range(3):
        rejected = await client.get(
            content_path(), params=contexts[1].model_dump(), headers=headers[1]
        )
        assert rejected.status_code == 503
    assert retries == [1]  # New requests cannot start scans or bypass backoff.
    async with sessions() as session:
        assert (
            await session.execute(
                text("SELECT ready_at FROM account_icon_blobs WHERE owner_user_id=1")
            )
        ).scalar_one() is not None
    monkeypatch.setattr(files, "finish", original)
    release_retry.set()
    await asyncio.wait_for(lifecycle.available.wait(), 5)
    assert intents(files, owner) == []
    assert scans == 2
    downloaded = await client.get(
        content_path(), params=contexts[1].model_dump(), headers=headers[1]
    )
    assert downloaded.status_code == 200 and downloaded.content == SVG
    assert scans == 2
    assert (directory(root, owner) / blob().sha256).read_bytes() == SVG


async def test_lifespan_cancelled_shutdown_joins_actual_io_before_database_disposal(
    runtime_engine, tmp_path, monkeypatch
):
    from src.database import dispose_engine

    sessions, contexts = await setup(runtime_engine)
    await write_asset(sessions, asset(contexts[1], light=blob(), dark=blob()))
    async with sessions.begin() as session:
        await session.execute(text("UPDATE server_instances SET protocol_version=5"))
        await session.execute(
            text("UPDATE client_devices SET registered_protocol_version=5")
        )
        user = await session.get(User, 1)
        headers = {
            "Authorization": "Bearer "
            + create_access_token({"sub": "1", "ver": user.auth_version}),
            "X-DayForge-Protocol": "5",
            "Content-Type": "application/octet-stream",
        }
    root = tmp_path / "shutdown-root"
    root.mkdir()
    monkeypatch.setattr(
        "src.main.get_settings",
        lambda: SimpleNamespace(
            ASSET_ROOT=root, ADMIN_USERNAME=None, ADMIN_PASSWORD=None
        ),
    )
    disposed, closing_started = asyncio.Event(), asyncio.Event()

    async def traced_dispose():
        disposed.set()
        await dispose_engine()

    monkeypatch.setattr("src.main.dispose_engine", traced_dispose)
    context_manager = app.router.lifespan_context(app)
    await context_manager.__aenter__()
    lifecycle = app.state.appearance
    await asyncio.wait_for(lifecycle.available.wait(), 5)
    original_close = lifecycle._finish_close

    async def traced_close():
        closing_started.set()
        await original_close()

    monkeypatch.setattr(lifecycle, "_finish_close", traced_close)
    reached, release = barrier(
        monkeypatch, lifecycle.runtime._transfers.files, "publish"
    )

    async def input_bytes():
        return {"type": "http.request", "body": SVG, "more_body": False}

    task = asyncio.create_task(
        raw_call(
            content_path(),
            headers,
            method="PUT",
            query=contexts[1].model_dump(),
            receive=input_bytes,
        )
    )
    shutdown = None
    try:
        await asyncio.wait_for(reached.wait(), 5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        shutdown = asyncio.create_task(context_manager.__aexit__(None, None, None))
        await asyncio.wait_for(closing_started.wait(), 5)
        shutdown.cancel()
        await asyncio.sleep(0)
        assert not shutdown.done() and not disposed.is_set()
        other = AssetRootLease(root)
        with pytest.raises(AssetRootBusy):
            other.acquire()
        rejected = await raw_call(
            content_path(), headers, query=contexts[1].model_dump()
        )
        assert rejected[0]["status"] == 503
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(shutdown, 5)
        assert disposed.is_set()
        other.acquire()
        other.release()
    finally:
        release.set()
        await asyncio.gather(task, return_exceptions=True)
        if shutdown is not None:
            await asyncio.gather(shutdown, return_exceptions=True)
        else:
            await context_manager.__aexit__(None, None, None)


async def test_catalog_byte_budget_pages_without_losing_frozen_entries(
    production_assets,
):
    client, sessions, contexts, headers, _, _ = production_assets
    declarations = []
    for index in range(128):
        declaration = asset(
            contexts[1],
            asset_id=f"a1000000-0000-4000-8000-{index + 1:012d}",
            name="图" * 80,
        )
        await write_asset(sessions, declaration)
        declarations.append(declaration)
    for revision in range(1, 21):
        await write_pack(sessions, pack(contexts[1], declarations, revision=revision))
    cursor = 128
    seen: list[int] = []
    pages = 0
    while cursor < 148:
        response = await client.get(
            "/api/v2/appearance/catalog",
            params=contexts[1].model_dump()
            | {"after": cursor, "through": 148, "limit": 100},
            headers=headers[1],
        )
        assert response.status_code == 200, response.text
        assert len(response.content) <= 1_048_576
        value = response.json()
        assert value["through_sequence"] == 148 and value["next_cursor"] > cursor
        seen.extend(entry["sequence"] for entry in value["entries"])
        cursor = value["next_cursor"]
        pages += 1
    assert pages > 1 and seen == list(range(129, 149))


async def test_android_json_charset_and_declared_png_mime_work_without_fallback(
    production_assets,
):
    client, sessions, contexts, headers, _, _ = production_assets
    samples = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/next/png.json").read_text()
    )
    data = base64.b64decode(
        next(item["png"] for item in samples if item["name"] == "rgba"), validate=True
    )
    declaration = asset(contexts[1], light=blob(data, "image/png"), dark=None)
    result = await client.put(
        asset_path(),
        headers=headers[1] | {"Content-Type": "application/json; charset=utf-8"},
        content=declaration.model_dump_json().encode(),
    )
    assert result.status_code == 200
    uploaded = await client.put(
        content_path(),
        params=contexts[1].model_dump(),
        headers=headers[1] | {"Content-Type": "image/png"},
        content=data,
    )
    assert uploaded.status_code == 200, uploaded.text
    downloaded = await client.get(
        content_path(), params=contexts[1].model_dump(), headers=headers[1]
    )
    assert downloaded.status_code == 200 and downloaded.content == data
    assert downloaded.headers["content-type"] == "image/png"
    absent = await client.get(
        content_path("dark"), params=contexts[1].model_dump(), headers=headers[1]
    )
    assert absent.status_code == 404 and "location" not in absent.headers
    async with sessions() as session:
        assert (
            await session.execute(
                text(
                    "SELECT validation_profile FROM account_icon_blobs WHERE owner_user_id=1"
                )
            )
        ).scalar_one() == "png-v1"


def test_real_tcp_production_appearance_commits_before_receipt_and_retains_v4_gate(
    tmp_path,
):
    root = tmp_path / "tcp-assets"
    root.mkdir()
    with isolated_server(tmp_path, asset_root=root) as (acceptance, _, password):
        token = acceptance.login("jwt_acceptance_admin", password)["access_token"]
        device = acceptance.register_device(token, "appearance-tcp")
        identity = acceptance.request("GET", "/api/v2/system/identity")
        assert identity["protocol_version"] == 4
        scope = {key: identity[key] for key in ("server_instance_id", "sync_epoch")} | {
            "device_id": device
        }
        with Client(
            base_url=acceptance.base_url, timeout=10, follow_redirects=False
        ) as client:
            headers = {"Authorization": "Bearer " + token, "X-DayForge-Protocol": "5"}
            declaration = asset(scope, light=blob(), dark=None).model_dump(mode="json")
            assert (
                client.put(asset_path(), headers=headers, json=declaration).status_code
                == 426
            )
            # This owned temporary server alone is activated, never a real deployment.
            with sqlite3.connect(tmp_path / "jwt-acceptance.sqlite") as connection:
                connection.execute("UPDATE server_instances SET protocol_version=5")
                connection.execute(
                    "UPDATE client_devices SET registered_protocol_version=5"
                )
            assert (
                client.put(
                    asset_path(),
                    headers=headers
                    | {"Content-Type": "application/json; charset=utf-8"},
                    content=json.dumps(declaration).encode(),
                ).status_code
                == 200
            )
            response = client.put(
                content_path(),
                headers=headers | {"Content-Type": "image/svg+xml"},
                params=scope,
                content=SVG,
            )
            assert response.status_code == 200
            with sqlite3.connect(tmp_path / "jwt-acceptance.sqlite") as connection:
                row = connection.execute(
                    "SELECT ready_at,validation_profile FROM account_icon_blobs"
                ).fetchone()
                assert row[0] is not None and row[1] == "svg-v1"
            actual = client.get(content_path(), params=scope, headers=headers)
            assert actual.status_code == 200 and actual.content == SVG
            duplicate = client.get(
                content_path(),
                params=scope,
                headers=[*headers.items(), ("X-DayForge-Protocol", "5")],
            )
            assert duplicate.status_code == 426


@pytest.mark.parametrize("failure", ["commit", "response"])
async def test_production_metadata_real_commit_and_response_failure_rollback(
    production_assets, monkeypatch, failure
):
    from src.v2.asset_router import AppearanceJSONResponse

    client, sessions, contexts, headers, _, _ = production_assets
    if failure == "commit":
        async with sessions.kw["bind"].begin() as connection:
            await connection.execute(
                text(
                    "CREATE TABLE metadata_commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
                )
            )
            await connection.execute(
                text(
                    "CREATE TRIGGER metadata_outer_failure AFTER INSERT ON account_icon_assets BEGIN INSERT INTO metadata_commit_fault VALUES (-999999); END"
                )
            )
    before = await database_state(sessions.kw["bind"])
    original = AppearanceJSONResponse.render

    def fail(_self, _content):
        raise RuntimeError("response construction failed before acknowledgement")

    with monkeypatch.context() as patch:
        if failure == "response":
            patch.setattr(AppearanceJSONResponse, "render", fail)
        result = await client.put(
            asset_path(),
            headers=headers[1],
            json=asset(contexts[1]).model_dump(mode="json"),
        )
    assert result.status_code == 500
    assert await database_state(sessions.kw["bind"]) == before
    if failure == "commit":
        async with sessions.kw["bind"].begin() as connection:
            await connection.execute(text("DROP TRIGGER metadata_outer_failure"))
    assert AppearanceJSONResponse.render == original
    result = await client.put(
        asset_path(),
        headers=headers[1],
        json=asset(contexts[1]).model_dump(mode="json"),
    )
    assert result.status_code == 200
    stored = await snapshot(sessions)
    assert len(stored["appearance_catalog"]) == 1
    assert len(stored["account_icon_assets"]) == 1


@pytest.mark.parametrize(
    "kind",
    [
        "wrong-hash",
        "short",
        "oversize",
        "wrong-declared-mime",
        "compressed",
        "unknown-json",
        "wrong-charset",
    ],
)
async def test_finite_errors_do_not_echo_source_or_acknowledge_bad_bytes(
    production_assets, kind
):
    client, sessions, contexts, headers, lifecycle, _ = production_assets
    await declare(production_assets)
    before = await snapshot(sessions)
    if kind in {"unknown-json", "wrong-charset"}:
        body = asset(contexts[1], light=blob(), dark=blob()).model_dump(mode="json")
        body["unrecognized-secret"] = "DO_NOT_ECHO_THIS_SOURCE"
        result = await client.put(
            asset_path(),
            headers=headers[1]
            | {
                "Content-Type": "application/json; charset=latin-1"
                if kind == "wrong-charset"
                else "application/json"
            },
            content=json.dumps(body).encode(),
        )
    else:
        data = (
            b"X" * len(SVG)
            if kind == "wrong-hash"
            else SVG[:-1]
            if kind == "short"
            else SVG + b"x"
            if kind == "oversize"
            else SVG
        )
        request_headers = headers[1] | {
            "Content-Type": "image/png"
            if kind == "wrong-declared-mime"
            else "image/svg+xml"
        }
        if kind == "compressed":
            request_headers["Content-Encoding"] = "gzip"
        result = await client.put(
            content_path(),
            params=contexts[1].model_dump(),
            headers=request_headers,
            content=data,
        )
    assert result.status_code == (413 if kind == "oversize" else 422), result.text
    assert result.json()["detail"]["code"] in {
        "ASSET_INPUT_INVALID",
        "ASSET_INPUT_TOO_LARGE",
    }
    assert len(result.content) < 300 and "DO_NOT_ECHO_THIS_SOURCE" not in result.text
    assert await snapshot(sessions) == before
    if not lifecycle.runtime.ready:
        await asyncio.wait_for(lifecycle.available.wait(), 5)


async def test_failed_runtime_shutdown_still_joins_actual_parser_and_can_retry(
    production_assets, monkeypatch
):
    _, _, _, _, lifecycle, _ = production_assets
    loop = asyncio.get_running_loop()
    parsing, release_failed, released = asyncio.Event(), asyncio.Event(), Event()

    def parser_work():
        loop.call_soon_threadsafe(parsing.set)
        assert released.wait(timeout=5), "test must release the actual parser worker"

    task = asyncio.create_task(lifecycle.parser.run(parser_work))
    lease = lifecycle.runtime._lease
    original = lease.release
    calls = 0

    def release_once_fails():
        nonlocal calls
        calls += 1
        if calls == 1:
            loop.call_soon_threadsafe(release_failed.set)
            raise OSError("owned lease release failed")
        original()

    monkeypatch.setattr(lease, "release", release_once_fails)
    closing = None
    try:
        await asyncio.wait_for(parsing.wait(), 5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        closing = asyncio.create_task(lifecycle.aclose())
        await asyncio.wait_for(release_failed.wait(), 5)
        await asyncio.sleep(0)
        assert (
            not closing.done()
        )  # Failed runtime close cannot skip actual parser work.
        released.set()
        with pytest.raises(OSError, match="owned lease release failed"):
            await asyncio.wait_for(closing, 5)
        await lifecycle.aclose()
        assert calls == 2 and not lifecycle.runtime.ready
    finally:
        released.set()
        await asyncio.gather(task, return_exceptions=True)
        if closing is not None:
            await asyncio.gather(closing, return_exceptions=True)


async def test_closed_lifespan_does_not_recreate_unused_database(monkeypatch):
    from src import database

    monkeypatch.setattr(database, "_engine", None)
    monkeypatch.setattr(
        "src.main.get_settings",
        lambda: SimpleNamespace(
            ASSET_ROOT=None, ADMIN_USERNAME=None, ADMIN_PASSWORD=None
        ),
    )

    def unused():
        pytest.fail("Appearance ownership/shutdown must not create an unused engine")

    monkeypatch.setattr("src.main.get_engine", unused)
    async with app.router.lifespan_context(app):
        assert database._engine is None
    denied = await raw_call("/api/v2/appearance/quota", {})
    assert denied[0]["status"] == 503
    assert database._engine is None
