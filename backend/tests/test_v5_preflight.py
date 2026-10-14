"""Real migrated SQLite/root/CLI checks. No activation, destructive reset or deployment."""

from contextlib import closing
from dataclasses import asdict
from datetime import datetime, UTC
import hashlib
import json
import os
import sqlite3
import subprocess
import sys

import pytest
from sqlalchemy import create_engine
from sqlmodel import Session

from src.auth.models import User
from src.storage import cli, v5_preflight
from src.storage.asset_root import LOCK_NAME, AssetRootLease
from src.storage.v5_preflight import (
    BASELINE_TABLES,
    EXPECTED_TABLES,
    RETAINED_TABLES,
    preflight_v5,
)
from src.v2.models import ClientDevice, PlanNode, UserProfile
from tests.test_logical_archive import PROJECT_ROOT, migrate


OWNER = "ac400000-0000-4000-8000-000000000001"


@pytest.fixture
def baseline(tmp_path):
    database = tmp_path / "candidate.sqlite"
    url = migrate(database)
    engine = create_engine(url)
    try:
        with Session(engine) as session:
            session.add(
                User(
                    id=101,
                    public_id=OWNER,
                    username="seed-only-owner",
                    password_hash="synthetic-do-not-print",
                )
            )
            session.add(
                UserProfile(
                    user_id=101,
                    display_name="private-display",
                    timezone="Asia/Shanghai",
                )
            )
            session.commit()
    finally:
        engine.dispose()
    with closing(sqlite3.connect(database)) as connection:
        # The offline fixture avoids WAL/SHM coordination when comparing file bytes.
        connection.execute("PRAGMA journal_mode=DELETE")
    root = tmp_path / "assets"
    root.mkdir()
    return database, root


def rows(database):
    with closing(sqlite3.connect(database)) as connection:
        return list(connection.iterdump())


@pytest.mark.parametrize("protocol", [4, 5])
def test_empty_baseline_retains_accounts_and_never_authorizes_or_writes(
    baseline, protocol
):
    database, root = baseline
    with closing(sqlite3.connect(database)) as connection:
        connection.execute(
            "UPDATE server_instances SET protocol_version=?", (protocol,)
        )
        connection.commit()
    before = rows(database)
    image = hashlib.sha256(database.read_bytes()).hexdigest()
    report = preflight_v5(database, asset_root=root)
    assert report.empty_baseline_candidate
    assert report.database_inventory_checked and report.asset_root_empty
    assert report.protocol_version == protocol
    assert report.retained_row_counts is not None
    assert (
        report.retained_row_counts["users"]
        == report.retained_row_counts["user_profiles"]
        == 1
    )
    assert report.occupied_baseline_tables == {}
    assert report.blockers == ()
    assert report.activation_authorized is False and report.snapshot_only is True
    assert "android_empty_baseline_and_durable_queue_review" in report.remaining_gates
    assert "explicit_coordinated_activation_approval" in report.remaining_gates
    assert "asset_write_permissions_lease_and_durability" in report.remaining_gates
    assert rows(database) == before
    assert hashlib.sha256(database.read_bytes()).hexdigest() == image
    assert list(root.iterdir()) == []
    output = json.dumps(asdict(report))
    assert "synthetic-do-not-print" not in output and "private-display" not in output
    assert "seed-only-owner" not in output


def test_inventory_covers_every_migrated_table_and_separates_retained_identity(
    baseline,
):
    database, _ = baseline
    with closing(sqlite3.connect(database)) as connection:
        actual = v5_preflight._tables(connection)
    assert actual == EXPECTED_TABLES
    assert BASELINE_TABLES.isdisjoint(RETAINED_TABLES)
    assert {
        "client_devices",
        "user_sync_policies",
        "sync_changes",
        "sync_cursors",
        "sync_operations",
        "timer_commands",
        "entity_revision_snapshots",
        "activity_challenge_event_bindings",
        "appearance_accounts",
        "appearance_catalog",
        "account_icon_blobs",
        "account_icon_assets",
        "account_icon_packs",
    } <= BASELINE_TABLES
    assert not v5_preflight.V5Preflight("synthetic", "head").empty_baseline_candidate


def test_future_migration_requires_inventory_review_before_opening_database(
    baseline, monkeypatch
):
    database, root = baseline
    before = rows(database)
    monkeypatch.setattr(v5_preflight, "_current_head", lambda: "future-head")

    def unexpected_open(*args, **kwargs):
        pytest.fail("An unreviewed migration must not open the selected database")

    with monkeypatch.context() as patch:
        patch.setattr(v5_preflight.sqlite3, "connect", unexpected_open)
        report = preflight_v5(database, asset_root=root)
    assert report.blockers == ("PREFLIGHT_SCHEMA_REVIEW_REQUIRED",)
    assert not report.database_inventory_checked
    assert not report.empty_baseline_candidate and not report.activation_authorized
    assert rows(database) == before


@pytest.mark.parametrize("retained", ["device", "tombstone"])
def test_deleted_business_and_old_registration_need_review_without_cleanup(
    baseline, retained
):
    database, root = baseline
    engine = create_engine(f"sqlite:///{database}")
    try:
        with Session(engine) as session:
            if retained == "device":
                session.add(
                    ClientDevice(
                        user_id=101,
                        installation_id="old-installation",
                        platform="android",
                        registered_protocol_version=4,
                    )
                )
                table = "client_devices"
            else:
                session.add(
                    PlanNode(
                        owner_user_id=101,
                        created_by_user_id=101,
                        public_id="ac400000-0000-4000-8000-000000000002",
                        node_kind="goal",
                        title="private tombstone",
                        deleted_at=datetime(2026, 10, 12, tzinfo=UTC),
                    )
                )
                table = "plan_nodes"
            session.commit()
    finally:
        engine.dispose()
    before = rows(database)
    report = preflight_v5(database, asset_root=root)
    assert report.occupied_baseline_tables is not None
    assert report.occupied_baseline_tables[table] == 1
    assert report.blockers == ("DATABASE_BASELINE_REVIEW_REQUIRED",)
    assert not report.empty_baseline_candidate and not report.activation_authorized
    assert rows(database) == before


@pytest.mark.parametrize(
    "change,code",
    [
        (
            "CREATE TABLE unknown_collection(id INTEGER)",
            "DATABASE_SCHEMA_SHAPE_MISMATCH",
        ),
        (
            "ALTER TABLE tracked_metrics ADD COLUMN unreviewed TEXT",
            "DATABASE_SCHEMA_SHAPE_MISMATCH",
        ),
        ("DROP TABLE metric_appearances", "DATABASE_SCHEMA_SHAPE_MISMATCH"),
        (
            "UPDATE alembic_version SET version_num='unknown-head'",
            "DATABASE_MIGRATION_HEAD_MISMATCH",
        ),
        (
            "INSERT INTO alembic_version VALUES('second-head')",
            "DATABASE_MIGRATION_HEAD_MISMATCH",
        ),
        ("DELETE FROM server_instances", "SERVER_IDENTITY_INVALID"),
        (
            "UPDATE server_instances SET instance_uuid='private-bad-id'",
            "SERVER_IDENTITY_INVALID",
        ),
        (
            "UPDATE server_instances SET sync_epoch='AC400000-0000-4000-8000-000000000001'",
            "SERVER_IDENTITY_INVALID",
        ),
        (
            "UPDATE server_instances SET protocol_version=6",
            "SERVER_PROTOCOL_UNSUPPORTED",
        ),
        (
            "UPDATE server_instances SET protocol_version=4.5",
            "SERVER_PROTOCOL_UNSUPPORTED",
        ),
    ],
)
def test_bad_shape_head_identity_and_unknown_or_noninteger_protocol_fail_closed(
    baseline, change, code
):
    database, root = baseline
    with closing(sqlite3.connect(database)) as connection:
        connection.execute(change)
        connection.commit()
    before = rows(database)
    report = preflight_v5(database, asset_root=root)
    assert report.blockers == (code,)
    assert not report.empty_baseline_candidate and not report.activation_authorized
    assert rows(database) == before
    assert "private-bad-id" not in json.dumps(asdict(report))


def test_foreign_key_damage_is_not_an_empty_candidate(baseline):
    database, root = baseline
    with closing(sqlite3.connect(database)) as connection:
        connection.execute("UPDATE user_profiles SET user_id=9999")
        connection.commit()
    before = rows(database)
    report = preflight_v5(database, asset_root=root)
    assert report.blockers == ("DATABASE_INTEGRITY_INVALID",)
    assert not report.empty_baseline_candidate
    assert rows(database) == before


@pytest.mark.parametrize("kind", ["missing", "corrupt", "symlink"])
def test_invalid_database_does_not_create_repair_or_follow_files(tmp_path, kind):
    database = tmp_path / "chosen.sqlite"
    target = tmp_path / "private.sqlite"
    target.write_bytes(b"private-not-sqlite")
    if kind == "corrupt":
        database.write_bytes(b"invalid-database")
    elif kind == "symlink":
        database.symlink_to(target)
    before = sorted(path.name for path in tmp_path.iterdir())
    report = preflight_v5(database)
    assert not report.empty_baseline_candidate and not report.activation_authorized
    assert report.blockers[0] in {
        "DATABASE_UNAVAILABLE",
        "DATABASE_UNAVAILABLE_OR_INVALID",
    }
    assert sorted(path.name for path in tmp_path.iterdir()) == before
    assert target.read_bytes() == b"private-not-sqlite"


@pytest.mark.parametrize(
    "kind,code",
    [
        ("none", "ASSET_ROOT_NOT_CHECKED"),
        ("missing", "ASSET_ROOT_INVALID"),
        ("link", "ASSET_ROOT_INVALID"),
        ("fifo-lock", "ASSET_ROOT_INVALID"),
        ("bad-lock", "ASSET_ROOT_INVALID"),
        ("linked-accounts", "ASSET_ROOT_INVALID"),
        ("orphan-owner", "ASSET_BASELINE_REVIEW_REQUIRED"),
        ("unknown-file", "ASSET_BASELINE_REVIEW_REQUIRED"),
    ],
)
def test_material_residue_and_bad_roots_are_preserved_not_recovered(
    baseline, kind, code, tmp_path
):
    database, root = baseline
    chosen = root
    if kind == "none":
        chosen = None
    elif kind == "missing":
        chosen = tmp_path / "missing-root"
    elif kind == "link":
        chosen = tmp_path / "root-link"
        chosen.symlink_to(root, target_is_directory=True)
    elif kind == "fifo-lock":
        os.mkfifo(root / LOCK_NAME)
    elif kind == "bad-lock":
        (root / LOCK_NAME).write_bytes(b"unrecognized-lock")
    elif kind == "linked-accounts":
        (root / "accounts").symlink_to(tmp_path, target_is_directory=True)
    elif kind == "orphan-owner":
        (root / "accounts" / OWNER).mkdir(parents=True)
    elif kind == "unknown-file":
        (root / "private-file").write_bytes(b"keep-private")
    before = sorted(path.name for path in root.iterdir())
    report = preflight_v5(database, asset_root=chosen)
    assert report.blockers == (code,)
    assert not report.empty_baseline_candidate and not report.activation_authorized
    assert sorted(path.name for path in root.iterdir()) == before
    if kind == "unknown-file":
        assert (root / "private-file").read_bytes() == b"keep-private"
    if kind == "bad-lock":
        assert (root / LOCK_NAME).read_bytes() == b"unrecognized-lock"
    assert not (tmp_path / "missing-root").exists()


def test_empty_skeleton_and_existing_lease_do_not_prove_stopped_writers(baseline):
    database, root = baseline
    (root / "accounts").mkdir()
    lease = AssetRootLease(root)
    try:
        lease.acquire()
        lock_inode = (root / LOCK_NAME).stat().st_ino
        report = preflight_v5(database, asset_root=root)
        assert report.empty_baseline_candidate
        assert report.activation_authorized is False
        assert "confirmed_target_and_stopped_writers" in report.remaining_gates
        assert (root / LOCK_NAME).stat().st_ino == lock_inode
    finally:
        lease.release()


def test_all_database_inventory_fields_use_one_live_wal_snapshot(baseline, monkeypatch):
    database, root = baseline
    with closing(sqlite3.connect(database)) as writer:
        writer.execute("PRAGMA journal_mode=WAL")
        original = v5_preflight._tables

        def write_between_queries(reader):
            tables = original(
                reader
            )  # Establish the actual read snapshot, not immutable=1.
            writer.execute("UPDATE server_instances SET protocol_version=5")
            writer.execute("UPDATE users SET username='later-name'")
            writer.commit()
            return tables

        with monkeypatch.context() as patch:
            patch.setattr(v5_preflight, "_tables", write_between_queries)
            report = preflight_v5(database, asset_root=root)
        assert report.empty_baseline_candidate and report.protocol_version == 4
        assert report.retained_row_counts is not None
        assert report.retained_row_counts["users"] == 1
        # A later invocation sees the committed WAL; it cannot reuse the old candidate as proof.
        fresh = preflight_v5(database, asset_root=root)
        assert fresh.protocol_version == 5
        assert (
            writer.execute("SELECT username FROM users").fetchone()[0] == "later-name"
        )


def test_actual_cli_is_cwd_independent_sanitized_and_has_no_apply_mode(
    baseline, tmp_path
):
    database, root = baseline
    environment = {**os.environ, "PYTHONPATH": str(PROJECT_ROOT)}
    before = rows(database)

    def run(*extra):
        return subprocess.run(
            [
                sys.executable,
                "-m",
                "src.storage.cli",
                "sqlite-v5-preflight",
                "--database",
                str(database),
                *extra,
            ],
            cwd=tmp_path,
            env=environment,
            capture_output=True,
            text=True,
            timeout=15,
        )

    success = run("--asset-root", str(root))
    assert success.returncode == 0, success.stderr
    result = json.loads(success.stdout)
    assert result["empty_baseline_candidate"] and not result["activation_authorized"]
    assert result["format"] == "dayforge.v5-preflight" and result["format_version"] == 1
    blocked = run()
    assert blocked.returncode == 2
    assert json.loads(blocked.stdout)["blockers"] == ["ASSET_ROOT_NOT_CHECKED"]
    rejected = run("--asset-root", str(root), "--apply")
    assert rejected.returncode == 2
    assert "unrecognized arguments: --apply" in rejected.stderr
    assert rows(database) == before
    assert (
        not cli._parser()
        .parse_args(["sqlite-verify", "--database", str(database)])
        .command
        == "sqlite-v5-preflight"
    )
