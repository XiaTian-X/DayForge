import asyncio
import base64
from contextlib import closing, contextmanager
from dataclasses import replace
import logging
import json
from pathlib import Path
import sqlite3
from threading import Event
from typing import Any

from fastapi import HTTPException
import pytest
from sqlalchemy import event, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from src.appearance.input import ImageInputError
from src.auth.models import User
from src.auth.service import create_access_token
from src.storage.asset_files import AssetFiles
from src.storage.asset_io import AssetIoPool
from src.storage.database_adapter import DatabaseBusyError
from src.tokens.models import ApiToken
from src.tokens.service import generate_token, hash_token
from src.v2.asset_service import read_asset
from src.v2.asset_transfers import AssetTransfers
from src.v2.errors import DomainError
from tests.asset_file_fixtures import SVG, blob, directory, intents
from tests.test_asset_declarations import ASSET, asset, setup, snapshot, write_asset


@pytest.fixture(params=["jwt", "api"])
async def transfers(runtime_engine, tmp_path, request):
    sessions, contexts = await setup(runtime_engine)
    headers, owners = {}, {}
    for owner in (1, 2):
        await write_asset(
            sessions, asset(contexts[owner], light=blob(), dark=blob()), owner=owner
        )
        async with sessions.begin() as session:
            user = await session.get(User, owner)
            assert user is not None
            owners[owner] = user.public_id
            if request.param == "jwt":
                headers[owner] = "Bearer " + create_access_token(
                    {"sub": str(owner), "ver": user.auth_version}
                )
            else:
                key = generate_token()
                session.add(
                    ApiToken(
                        user_id=owner,
                        name="transfer-probe",
                        token_hash=hash_token(key),
                        prefix=key[:11],
                    )
                )
                headers[owner] = "Token " + key
    root = tmp_path / "assets"
    root.mkdir()
    files = AssetFiles(root)
    async with AssetIoPool(2) as pool:
        yield (
            AssetTransfers(sessions, files, pool),
            contexts,
            headers,
            owners,
            request.param,
        )


async def availability(service, owner=1):
    async with service.sessions() as session:
        return (
            await session.execute(
                text(
                    "SELECT ready_at,validation_profile FROM account_icon_blobs WHERE owner_user_id=:owner"
                ),
                {"owner": owner},
            )
        ).one()


async def token_usage(service):
    async with service.sessions() as session:
        return (
            await session.execute(
                text("SELECT last_used_at FROM api_tokens ORDER BY id")
            )
        ).all()


def barrier(monkeypatch, files, method):
    loop = asyncio.get_running_loop()
    reached, release = asyncio.Event(), Event()
    original = getattr(files, method)

    def held(*args, **kwargs):
        result = original(*args, **kwargs)
        loop.call_soon_threadsafe(reached.set)
        assert release.wait(timeout=5), "test must release actual file worker"
        return result

    monkeypatch.setattr(files, method, held)
    return reached, release


async def test_install_replay_read_and_account_isolation_survive_new_service(transfers):
    service, contexts, headers, owners, _ = transfers
    before = await snapshot(service.sessions)
    with pytest.raises(DomainError) as pending:
        await service.read(headers[1], contexts[1], ASSET, "light")
    assert pending.value.code == "ASSET_NOT_READY"
    first = await service.install(headers[1], contexts[1], ASSET, "light", SVG)
    assert not first.cleanup_pending
    ready = await availability(service)
    assert ready[0] is not None and ready[1] == "svg-v1"
    assert await availability(service, 2) == (None, None)
    after = await snapshot(service.sessions)
    assert after["appearance_accounts"] == before["appearance_accounts"]
    assert after["account_icon_assets"] == before["account_icon_assets"]
    assert after["appearance_catalog"] == before["appearance_catalog"]
    assert intents(service.files, owners[1]) == []
    reopened = AssetTransfers(
        service.sessions, AssetFiles(service.files.root), service.pool
    )
    assert await reopened.install(headers[1], contexts[1], ASSET, "light", SVG) == first
    assert await availability(service) == ready
    assert await reopened.read(headers[1], contexts[1], ASSET, "dark") == SVG
    async with service.sessions() as session:
        user = await session.get(User, 1)
        assert user is not None
        assert (await read_asset(session, user, contexts[1], ASSET)).ready_variants == [
            "light",
            "dark",
        ]
    with pytest.raises(DomainError) as other:
        await reopened.read(headers[2], contexts[2], ASSET, "light")
    assert other.value.code == "ASSET_NOT_READY"
    await reopened.install(headers[2], contexts[2], ASSET, "dark", SVG)
    assert await reopened.read(headers[2], contexts[2], ASSET, "light") == SVG
    assert (directory(service.files.root, owners[1]) / blob().sha256).stat().st_ino != (
        directory(service.files.root, owners[2]) / blob().sha256
    ).stat().st_ino


@pytest.mark.parametrize(
    "change",
    [
        "inactive",
        "credential",
        "device",
        "capability",
        "epoch",
        "instance",
        "owner",
        "descriptor",
    ],
)
async def test_changed_authority_during_file_stage_never_marks_ready(
    transfers, monkeypatch, change
):
    service, contexts, headers, owners, kind = transfers
    reached, release = barrier(monkeypatch, service.files, "publish")
    task = asyncio.create_task(
        service.install(headers[1], contexts[1], ASSET, "light", SVG)
    )
    try:
        await asyncio.wait_for(reached.wait(), timeout=5)
        assert await availability(service) == (None, None)
        assert all(row[0] is None for row in await token_usage(service))
        # A separate real connection commits while disk work is still blocked.
        async with service.sessions.begin() as writer:
            statements = {
                "inactive": "UPDATE users SET is_active=0 WHERE id=1",
                "credential": "UPDATE users SET auth_version=auth_version+1 WHERE id=1"
                if kind == "jwt"
                else "DELETE FROM api_tokens WHERE user_id=1",
                "device": "UPDATE client_devices SET revoked_at='2026-09-23 00:00:00' WHERE user_id=1",
                "capability": "UPDATE user_sync_policies SET primary_editor_device_id=NULL WHERE user_id=1",
                "epoch": "UPDATE server_instances SET sync_epoch='dddddddd-dddd-4ddd-8ddd-dddddddddddd'",
                "instance": "UPDATE server_instances SET instance_uuid='dddddddd-dddd-4ddd-8ddd-dddddddddddd'",
                "owner": "UPDATE users SET public_id='dddddddd-dddd-4ddd-8ddd-dddddddddddd' WHERE id=1",
                "descriptor": "UPDATE account_icon_blobs SET width=2 WHERE owner_user_id=1",
            }
            await writer.execute(text(statements[change]))
        release.set()
        with pytest.raises((HTTPException, DomainError)) as caught:
            await asyncio.wait_for(task, timeout=5)
        if change in {"inactive", "credential"}:
            assert (
                isinstance(caught.value, HTTPException)
                and caught.value.status_code == 401
            )
        else:
            expected = {
                "device": "DEVICE_NOT_FOUND",
                "capability": "DEVICE_CAPABILITY_DENIED",
                "epoch": "SYNC_EPOCH_MISMATCH",
                "instance": "SERVER_IDENTITY_MISMATCH",
                "owner": "ASSET_METADATA_CHANGED",
                "descriptor": "ASSET_METADATA_CORRUPT",
            }
            assert (
                isinstance(caught.value, DomainError)
                and caught.value.code == expected[change]
            )
        assert await availability(service) == (None, None)
        assert await availability(service, 2) == (None, None)
        assert all(row[0] is None for row in await token_usage(service))
        assert len(intents(service.files, owners[1])) == 1
    finally:
        release.set()
        await asyncio.gather(task, return_exceptions=True)


async def test_deferred_commit_failure_rolls_back_ready_and_token_then_retries(
    transfers,
):
    service, contexts, headers, owners, _ = transfers
    async with service.sessions.begin() as session:
        await session.execute(
            text(
                "CREATE TABLE install_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
            )
        )
        await session.execute(
            text(
                "CREATE TRIGGER install_commit_fault AFTER UPDATE OF ready_at ON account_icon_blobs BEGIN INSERT INTO install_fault VALUES (-999999); END"
            )
        )
    before = await snapshot(service.sessions)
    with pytest.raises(IntegrityError):
        await service.install(headers[1], contexts[1], ASSET, "light", SVG)
    assert await snapshot(service.sessions) == before
    assert all(row[0] is None for row in await token_usage(service))
    assert len(intents(service.files, owners[1])) == 1
    async with service.sessions.begin() as session:
        await session.execute(text("DROP TRIGGER install_commit_fault"))
    await service.install(headers[1], contexts[1], ASSET, "light", SVG)
    assert await service.read(headers[1], contexts[1], ASSET, "light") == SVG
    # Only the successful attempt was cleaned; the failed operation is recoverable.
    assert len(intents(service.files, owners[1])) == 1
    abandoned = intents(service.files, owners[1])[0]
    assert await service.recover(abandoned) == "committed"
    assert intents(service.files, owners[1]) == []
    assert await service.read(headers[1], contexts[1], ASSET, "light") == SVG


async def test_request_cancel_during_file_stage_cannot_continue_to_ready(
    transfers, monkeypatch
):
    service, contexts, headers, owners, _ = transfers
    reached, release = barrier(monkeypatch, service.files, "publish")
    task = asyncio.create_task(
        service.install(headers[1], contexts[1], ASSET, "light", SVG)
    )

    try:
        await asyncio.wait_for(reached.wait(), timeout=5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        release.set()
        await asyncio.wait_for(service.pool.aclose(), timeout=5)
        assert await availability(service) == (None, None)
        assert all(row[0] is None for row in await token_usage(service))
        assert len(intents(service.files, owners[1])) == 1
    finally:
        release.set()
        await asyncio.gather(task, return_exceptions=True)


async def test_cleanup_failure_does_not_erase_success_or_expose_exception(
    transfers, monkeypatch, caplog
):
    service, contexts, headers, owners, _ = transfers
    # The migrated fixture runs Alembic fileConfig in this test process; real
    # deployment migrates in a separate process. Restore only this logger for
    # the diagnostic assertion, then let monkeypatch restore the fixture state.
    monkeypatch.setattr(logging.getLogger("src.v2.asset_transfers"), "disabled", False)

    def fail(_receipt):
        raise OSError("private-source-and-credential-must-not-be-logged")

    monkeypatch.setattr(service.files, "finish", fail)
    result = await service.install(headers[1], contexts[1], ASSET, "light", SVG)
    assert result.receipt.blob == blob()
    assert result.cleanup_pending
    assert (await availability(service))[0] is not None
    assert len(intents(service.files, owners[1])) == 1
    assert "cleanup deferred" in caplog.text
    assert "private-source-and-credential" not in caplog.text


async def test_ready_replay_still_checks_actual_request_bytes(transfers):
    service, contexts, headers, _, _ = transfers
    await service.install(headers[1], contexts[1], ASSET, "light", SVG)
    before = await snapshot(service.sessions)
    with pytest.raises(ImageInputError, match="IMAGE_HASH"):
        await service.install(headers[1], contexts[1], ASSET, "light", b"x" * len(SVG))
    assert await snapshot(service.sessions) == before
    assert await service.read(headers[1], contexts[1], ASSET, "light") == SVG


async def test_download_reauthenticates_after_file_read(transfers, monkeypatch):
    service, contexts, headers, _, _ = transfers
    await service.install(headers[1], contexts[1], ASSET, "light", SVG)
    reached, release = barrier(monkeypatch, service.files, "read")
    task = asyncio.create_task(service.read(headers[1], contexts[1], ASSET, "light"))
    try:
        await asyncio.wait_for(reached.wait(), timeout=5)
        async with service.sessions.begin() as writer:
            await writer.execute(text("UPDATE users SET is_active=0 WHERE id=1"))
        release.set()
        with pytest.raises(HTTPException) as caught:
            await asyncio.wait_for(task, timeout=5)
        assert caught.value.status_code == 401
    finally:
        release.set()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.parametrize("kind", ["missing", "corrupt"])
async def test_unavailable_file_never_becomes_pending_or_empty_success(transfers, kind):
    service, contexts, headers, owners, _ = transfers
    await service.install(headers[1], contexts[1], ASSET, "light", SVG)
    ready = await availability(service)
    path = directory(service.files.root, owners[1]) / blob().sha256
    if kind == "missing":
        path.unlink()
    else:
        path.write_bytes(b"x" * len(SVG))
    with pytest.raises(FileNotFoundError if kind == "missing" else ImageInputError):
        await service.read(headers[1], contexts[1], ASSET, "light")
    assert await availability(service) == ready


async def test_other_device_or_missing_auth_never_starts_file_io(transfers):
    service, contexts, headers, _, _ = transfers
    with pytest.raises(HTTPException) as missing:
        await service.install(None, contexts[1], ASSET, "light", SVG)
    assert missing.value.status_code == 401
    with pytest.raises(DomainError) as other:
        await service.install(headers[1], contexts[2], ASSET, "light", SVG)
    assert other.value.code == "DEVICE_NOT_FOUND"
    assert list(service.files.root.iterdir()) == []


async def test_mismatched_file_receipt_cannot_mark_ready(transfers, monkeypatch):
    service, contexts, headers, owners, _ = transfers
    publish = service.files.publish

    def wrong(*args):
        return replace(publish(*args), owner_public_id=owners[2])

    monkeypatch.setattr(service.files, "publish", wrong)
    with pytest.raises(DomainError) as caught:
        await service.install(headers[1], contexts[1], ASSET, "light", SVG)
    assert caught.value.code == "ASSET_INSTALLATION_MISMATCH"
    assert await availability(service) == (None, None)
    assert await availability(service, 2) == (None, None)


async def test_pending_recovery_never_publishes_ready_and_preserves_cas(transfers):
    service, _, _, owners, _ = transfers
    from io import BytesIO

    with BytesIO(SVG) as source:
        installed = service.files.publish(owners[1], source, blob())
    before = await snapshot(service.sessions)
    assert await service.recover(installed) == "pending"
    assert await service.recover(installed) == "pending"
    assert await snapshot(service.sessions) == before
    assert await availability(service) == (None, None)
    assert intents(service.files, owners[1]) == []
    assert service.files.read(owners[1], blob(), "svg-v1") == SVG


@pytest.mark.parametrize("damage", ["missing", "corrupt", "descriptor"])
async def test_committed_recovery_preserves_bad_files_journal_and_ready_evidence(
    transfers, monkeypatch, damage
):
    service, contexts, headers, owners, _ = transfers

    def fail(_receipt):
        raise OSError("leave intent for recovery")

    with monkeypatch.context() as patch:
        patch.setattr(service.files, "finish", fail)
        await service.install(headers[1], contexts[1], ASSET, "light", SVG)
    installed = intents(service.files, owners[1])[0]
    path = directory(service.files.root, owners[1]) / blob().sha256
    if damage == "missing":
        path.unlink()
    elif damage == "corrupt":
        path.write_bytes(b"x" * len(SVG))
    else:
        async with service.sessions.begin() as session:
            await session.execute(
                text("UPDATE account_icon_blobs SET width=2 WHERE owner_user_id=1")
            )
    before = await snapshot(service.sessions)
    with pytest.raises(
        {
            "missing": FileNotFoundError,
            "corrupt": ImageInputError,
            "descriptor": DomainError,
        }[damage]
    ):
        await service.recover(installed)
    assert await snapshot(service.sessions) == before
    assert intents(service.files, owners[1]) == [installed]
    assert (await availability(service))[0] is not None


async def test_recovery_does_not_use_revoked_credentials_or_enable_account(transfers):
    service, _, _, owners, _ = transfers
    from io import BytesIO

    with BytesIO(SVG) as source:
        installed = service.files.publish(owners[1], source, blob())
    async with service.sessions.begin() as session:
        await session.execute(text("UPDATE users SET is_active=0 WHERE id=1"))
    before = await snapshot(service.sessions)
    assert await service.recover(installed) == "pending"
    assert await snapshot(service.sessions) == before
    async with service.sessions() as session:
        assert (
            await session.execute(text("SELECT is_active FROM users WHERE id=1"))
        ).scalar_one() == 0


async def test_unknown_account_intent_is_reported_without_cleanup(transfers):
    service, _, _, _, _ = transfers
    from io import BytesIO

    unknown = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    with BytesIO(SVG) as source:
        installed = service.files.publish(unknown, source, blob())
    with pytest.raises(DomainError) as caught:
        await service.recover(installed)
    assert caught.value.code == "ASSET_RECOVERY_ORPHAN"
    assert intents(service.files, unknown) == [installed]


async def test_real_writer_lock_during_final_phase_rolls_back_and_retries(
    transfers, runtime_engine, monkeypatch
):
    service, contexts, headers, owners, _ = transfers
    reached, release = barrier(monkeypatch, service.files, "publish")
    task = asyncio.create_task(
        service.install(headers[1], contexts[1], ASSET, "light", SVG)
    )

    def short_wait(connection, _record):
        cursor = connection.cursor()
        try:
            cursor.execute("PRAGMA busy_timeout=1")
        finally:
            cursor.close()

    try:
        await asyncio.wait_for(reached.wait(), timeout=5)
        before = await snapshot(service.sessions)
        event.listen(runtime_engine.sync_engine, "connect", short_wait)
        path = runtime_engine.url.database
        assert path is not None
        with closing(sqlite3.connect(path, isolation_level=None)) as writer:
            writer.execute("BEGIN IMMEDIATE")
            try:
                release.set()
                with pytest.raises(DatabaseBusyError):
                    await asyncio.wait_for(task, timeout=5)
                assert await snapshot(service.sessions) == before
                assert all(row[0] is None for row in await token_usage(service))
            finally:
                writer.rollback()
        assert len(intents(service.files, owners[1])) == 1
        await service.install(headers[1], contexts[1], ASSET, "light", SVG)
        assert await service.read(headers[1], contexts[1], ASSET, "light") == SVG
    finally:
        release.set()
        if event.contains(runtime_engine.sync_engine, "connect", short_wait):
            event.remove(runtime_engine.sync_engine, "connect", short_wait)
        await asyncio.gather(task, return_exceptions=True)


async def test_cancellation_after_real_commit_is_recovered_as_committed(
    transfers, runtime_engine
):
    service, contexts, headers, owners, kind = transfers

    def cancel_after_commit(session):
        if session.get_bind() is runtime_engine.sync_engine:
            raise asyncio.CancelledError("synthetic cancellation after durable commit")

    with checked_out(runtime_engine) as active:
        event.listen(Session, "after_commit", cancel_after_commit)
        try:
            with pytest.raises(asyncio.CancelledError):
                await service.install(headers[1], contexts[1], ASSET, "light", SVG)
        finally:
            event.remove(Session, "after_commit", cancel_after_commit)
        assert active == set(), "commit cancellation must return every connection"
    assert (await availability(service))[0] is not None
    if kind == "api":
        assert (await token_usage(service))[0][0] is not None
    installed = intents(service.files, owners[1])[0]
    assert await service.recover(installed) == "committed"
    assert await service.read(headers[1], contexts[1], ASSET, "light") == SVG
    assert intents(service.files, owners[1]) == []


@contextmanager
def checked_out(engine):
    active: set[int] = set()

    def checkout(_connection, record, _proxy):
        active.add(id(record))

    def checkin(_connection, record):
        active.remove(id(record))

    event.listen(engine.sync_engine, "checkout", checkout)
    event.listen(engine.sync_engine, "checkin", checkin)
    try:
        yield active
    finally:
        event.remove(engine.sync_engine, "checkout", checkout)
        event.remove(engine.sync_engine, "checkin", checkin)


async def test_png_install_uses_matching_profile_and_rejects_absent_dark_variant(
    transfers,
):
    service, contexts, headers, _, _ = transfers
    samples = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/next/png.json").read_text()
    )
    data = base64.b64decode(
        next(case["png"] for case in samples if case["name"] == "rgba"), validate=True
    )
    asset_id = "a1000000-0000-4000-8000-000000000002"
    description = blob(data, "image/png")
    await write_asset(
        service.sessions,
        asset(contexts[1], asset_id=asset_id, light=description, dark=None),
    )
    receipt = await service.install(headers[1], contexts[1], asset_id, "light", data)
    assert receipt.receipt.blob == description
    assert not receipt.cleanup_pending
    assert await service.read(headers[1], contexts[1], asset_id, "light") == data
    async with service.sessions() as session:
        assert (
            await session.execute(
                text(
                    "SELECT validation_profile FROM account_icon_blobs WHERE owner_user_id=1 AND sha256=:digest"
                ),
                {"digest": description.sha256},
            )
        ).scalar_one() == "png-v1"
    with pytest.raises(DomainError) as caught:
        await service.install(headers[1], contexts[1], asset_id, "dark", data)
    assert caught.value.code == "ASSET_VARIANT_NOT_FOUND"


@pytest.mark.parametrize("kind", ["mutable", "oversize"])
async def test_unbounded_or_mutable_transport_values_are_not_accepted(transfers, kind):
    service, contexts, headers, _, _ = transfers
    data: Any = bytearray(SVG) if kind == "mutable" else b"x" * 2_097_153
    with pytest.raises(TypeError if kind == "mutable" else ImageInputError):
        await service.install(headers[1], contexts[1], ASSET, "light", data)
    assert await availability(service) == (None, None)
    assert list(service.files.root.iterdir()) == []
