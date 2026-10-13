"""Owned stopped-backup/restore peers for Android's explicit replica recovery."""

import argparse
import asyncio
from contextlib import ExitStack, closing, contextmanager
import hashlib
import json
from pathlib import Path
import secrets
import signal
import sqlite3
import time

from httpx import AsyncClient
from sqlalchemy import create_engine
from sqlmodel import Session

from src.auth.models import User
from src.storage.sqlite_maintenance import (
    create_backup,
    inspect_database,
    restore_backup,
)
from src.v2.challenge_round import initial_round_head
from src.v2.replica_context import EPOCH_HEADER, INSTANCE_HEADER
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD, account_password_hash
from tests.asset_file_fixtures import SVG, blob
from tests.test_asset_declarations import asset, pack
from tests.test_challenge_profile import batch, snapshot
from tests.test_jwt_runtime_server import isolated_server
from tests.test_logical_archive import migrate
from tests.test_next_structural_sync import node, operation
from tests.test_product_restore_http import accepted, authenticated, context


PREFIX = "7c160000-0000-4000-8000-"
INSTALLATION = PREFIX + "000000000001"
ACCOUNT = PREFIX + "000000000011"
ISOLATED_ACCOUNT = PREFIX + "000000000012"
CHANGED = PREFIX + "000000000101"
MISSING = PREFIX + "000000000102"
ADDED = PREFIX + "000000000103"
ASSET = PREFIX + "000000000201"
PACK = PREFIX + "000000000301"
USERNAME = "joint-recovery"
ISOLATED_USERNAME = "joint-recovery-isolated"


def source_accounts(database):
    engine = create_engine(migrate(database))
    try:
        with Session(engine) as session:
            for username, public in (
                (USERNAME, ACCOUNT),
                (ISOLATED_USERNAME, ISOLATED_ACCOUNT),
            ):
                session.add(
                    User(
                        username=username,
                        public_id=public,
                        password_hash=account_password_hash(),
                    )
                )
            session.commit()
    finally:
        engine.dispose()
    # Account seeds and this explicit opt-in affect only the newly migrated
    # fixture. Devices, all business data and material writes use real HTTP.
    with closing(sqlite3.connect(database)) as connection:
        connection.execute("UPDATE server_instances SET protocol_version=5")
        connection.commit()


async def write_habit(client, headers, device, identity, title, revision=None):
    body = node(title=title)
    body["appearance"]["icon"] = {"kind": "asset", "asset_id": ASSET}
    body["appearance"]["icon_tint"] = "theme"
    item = operation(body, identity=identity, revision=revision)
    head = initial_round_head(identity).model_dump(mode="json")
    await accepted(client, headers, batch(device, [item], {item["operation_id"]: head}))


async def seed_source(url):
    async with AsyncClient(base_url=url) as client:
        tokens, headers, device = await authenticated(client, USERNAME, INSTALLATION)
        assert tokens["user_id"] == ACCOUNT
        declaration = asset(
            context(headers, device),
            asset_id=ASSET,
            name="Recovery template",
            light=blob(SVG, "image/svg+xml"),
            dark=None,
        )
        declared = await client.put(
            f"/api/v2/appearance/assets/{ASSET}",
            headers=headers,
            json=declaration.model_dump(mode="json"),
        )
        assert declared.status_code == 200, declared.text
        uploaded = await client.put(
            f"/api/v2/appearance/assets/{ASSET}/content/light",
            params=context(headers, device).model_dump(),
            headers=headers | {"Content-Type": "application/octet-stream"},
            content=SVG,
        )
        assert uploaded.status_code == 200, uploaded.text
        package = pack(context(headers, device), [declaration]).model_dump(mode="json")
        package["pack"].update(pack_id=PACK, name="Recovery pack")
        created = await client.put(
            f"/api/v2/appearance/packs/{PACK}/versions/1",
            headers=headers,
            json=package,
        )
        assert created.status_code == 200, created.text
        await write_habit(client, headers, device, CHANGED, "Recovery original")
        await authenticated(client, ISOLATED_USERNAME, INSTALLATION + "-isolated")
        return tokens, headers, device


async def prepare_live_peers(source, restored, saved):
    tokens, old_headers, original_device = saved
    async with (
        AsyncClient(base_url=source) as source_client,
        AsyncClient(base_url=restored) as restored_client,
    ):
        _, source_headers, source_device = await authenticated(
            source_client, USERNAME, INSTALLATION, original_device=original_device
        )
        assert source_headers[EPOCH_HEADER] == old_headers[EPOCH_HEADER]
        await write_habit(
            source_client,
            source_headers,
            source_device,
            CHANGED,
            "Recovery acknowledged change",
            revision=1,
        )
        await write_habit(
            source_client,
            source_headers,
            source_device,
            MISSING,
            "Recovery acknowledged missing",
        )
        old_access = await restored_client.get(
            "/api/v1/auth/users/me", headers=old_headers
        )
        old_refresh = await restored_client.post(
            "/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
        )
        assert old_access.status_code == old_refresh.status_code == 401
        _, new_headers, restored_device = await authenticated(
            restored_client, USERNAME, INSTALLATION, original_device=original_device
        )
        assert new_headers[INSTANCE_HEADER] == old_headers[INSTANCE_HEADER]
        assert new_headers[EPOCH_HEADER] != old_headers[EPOCH_HEADER]
        await write_habit(
            restored_client,
            new_headers,
            restored_device,
            ADDED,
            "Recovery restored addition",
        )
        for client, headers, device, expected in (
            (
                source_client,
                source_headers,
                source_device,
                {
                    CHANGED: "Recovery acknowledged change",
                    MISSING: "Recovery acknowledged missing",
                },
            ),
            (
                restored_client,
                new_headers,
                restored_device,
                {CHANGED: "Recovery original", ADDED: "Recovery restored addition"},
            ),
        ):
            state = await snapshot(client, headers, device)
            assert {
                row["entity_uuid"]: row["payload"]["title"] for row in state["changes"]
            } == expected
            stored = await client.get(
                f"/api/v2/appearance/assets/{ASSET}/content/light",
                headers=headers,
                params=context(headers, device).model_dump(),
            )
            assert stored.status_code == 200 and stored.content == SVG
        _, wrong_headers, wrong_device = await authenticated(
            restored_client, ISOLATED_USERNAME, INSTALLATION + "-isolated"
        )
        assert (await snapshot(restored_client, wrong_headers, wrong_device))[
            "changes"
        ] == []
        denied = await restored_client.get(
            f"/api/v2/appearance/assets/{ASSET}/content/light",
            headers=wrong_headers,
            params=context(wrong_headers, wrong_device).model_dump(),
        )
        assert denied.status_code == 404
        return {
            "source_url": source,
            "restored_url": restored,
            "installation_id": INSTALLATION,
            "account_id": ACCOUNT,
            "server_instance_id": new_headers[INSTANCE_HEADER],
            "source_epoch": old_headers[EPOCH_HEADER],
            "restored_epoch": new_headers[EPOCH_HEADER],
            "device_id": restored_device,
            "changed_id": CHANGED,
            "missing_id": MISSING,
            "added_id": ADDED,
            "asset_id": ASSET,
            "pack_id": PACK,
            "asset_sha256": hashlib.sha256(SVG).hexdigest(),
            "same_signing_key": True,
            "old_jwt_rejected": True,
            "wrong_owner_denied": True,
        }


def terminal_proof(
    database, assets, *, epoch, instance, device, expected, post_recovery_metric
):
    inspected = inspect_database(database, asset_root=assets)
    assert inspected.valid and inspected.active_timer_count == 0
    assert inspected.protocol_version == 5 and inspected.sync_epoch == epoch
    assert inspected.server_instance_id == instance
    with closing(sqlite3.connect(database)) as connection:
        rows = connection.execute(
            "SELECT p.public_id,p.title FROM plan_nodes p JOIN users u "
            "ON p.owner_user_id=u.id WHERE u.public_id=?",
            (ACCOUNT,),
        ).fetchall()
        assert dict(rows) == expected
        foreign = connection.execute(
            "SELECT count(*) FROM plan_nodes p JOIN users u "
            "ON p.owner_user_id=u.id WHERE u.public_id=?",
            (ISOLATED_ACCOUNT,),
        ).fetchone()
        assert foreign == (0,)
        registered = connection.execute(
            "SELECT d.public_id,d.registered_protocol_version FROM client_devices d "
            "JOIN users u ON d.user_id=u.id WHERE u.public_id=? AND d.installation_id=?",
            (ACCOUNT, INSTALLATION),
        ).fetchall()
        assert registered == [(device, 5)]
        metrics = connection.execute(
            "SELECT u.public_id,m.name FROM tracked_metrics m JOIN users u "
            "ON m.owner_user_id=u.id"
        ).fetchall()
        expected_metrics = (
            [(ACCOUNT, "Recovery new metric")] if post_recovery_metric else []
        )
        assert metrics == expected_metrics
    return {
        "valid": inspected.valid,
        "active_timer_count": inspected.active_timer_count,
        "protocol_version": inspected.protocol_version,
        "server_instance_id": inspected.server_instance_id,
        "sync_epoch": inspected.sync_epoch,
        "alembic_head": inspected.alembic_head,
        "row_counts": inspected.row_counts,
        "device_id": device,
        "post_recovery_metric_count": len(metrics),
        "post_recovery_metric_name": metrics[0][1] if metrics else None,
    }


@contextmanager
def recovery_servers(directory):
    directory.mkdir(exist_ok=False)
    source = directory / "source.sqlite"
    restored = directory / "restored.sqlite"
    assets = directory / "source-assets"
    restored_assets = directory / "restored-assets"
    assets.mkdir()
    restored_assets.mkdir()
    source_accounts(source)
    credentials = (secrets.token_urlsafe(48), TEST_ACCOUNT_PASSWORD)
    initial = directory / "initial-server"
    initial.mkdir()
    with isolated_server(
        initial, database=source, asset_root=assets, credentials=credentials
    ) as (server, _, _):
        saved = asyncio.run(seed_source(server.base_url))
    # Both source process and all file workers have exited before backup.
    inspected = inspect_database(source, asset_root=assets)
    assert inspected.valid and inspected.alembic_head is not None
    backup, _ = create_backup(
        source, directory / "backups", kind="manual", asset_root=assets
    )
    _, epoch = restore_backup(
        backup,
        restored,
        asset_root=restored_assets,
        expected_alembic_head=inspected.alembic_head,
    )
    assert epoch != inspected.sync_epoch
    with ExitStack() as stack:
        servers = []
        for name, database, root in (
            ("source-server", source, assets),
            ("restored-server", restored, restored_assets),
        ):
            logs = directory / name
            logs.mkdir()
            server, _, _ = stack.enter_context(
                isolated_server(
                    logs, database=database, asset_root=root, credentials=credentials
                )
            )
            servers.append(server)
        ready = asyncio.run(
            prepare_live_peers(servers[0].base_url, servers[1].base_url, saved)
        )
        assert ready["restored_epoch"] == epoch
        yield ready
    proof = ready | {
        "source": terminal_proof(
            source,
            assets,
            epoch=ready["source_epoch"],
            instance=ready["server_instance_id"],
            device=ready["device_id"],
            post_recovery_metric=False,
            expected={
                CHANGED: "Recovery acknowledged change",
                MISSING: "Recovery acknowledged missing",
            },
        ),
        "restored": terminal_proof(
            restored,
            restored_assets,
            epoch=epoch,
            instance=ready["server_instance_id"],
            device=ready["device_id"],
            post_recovery_metric=True,
            expected={
                CHANGED: "Recovery original",
                ADDED: "Recovery restored addition",
            },
        ),
        "backup_stopped": True,
    }
    (directory / "complete.json").write_text(json.dumps(proof, indent=2) + "\n")


def serve(directory: Path) -> None:
    stopping = False

    def request_stop(_signal, _frame):
        nonlocal stopping
        stopping = True

    previous = {
        kind: signal.signal(kind, request_stop)
        for kind in (signal.SIGTERM, signal.SIGINT)
    }
    try:
        with recovery_servers(directory) as ready:
            temporary = directory / "ready.tmp"
            temporary.write_text(json.dumps(ready) + "\n")
            temporary.rename(directory / "ready.json")
            while not stopping:
                time.sleep(0.05)
    finally:
        for kind, handler in previous.items():
            signal.signal(kind, handler)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    serve(parser.parse_args().directory)
