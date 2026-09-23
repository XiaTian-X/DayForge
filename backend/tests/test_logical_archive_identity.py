"""Account-scoped archive identities and immutable pre-fix v1 compatibility.

fixtures/logical-archive-v1.json was exported by commit 272109b using
test_logical_archive.seed_source, before the v2 writer existed. It contains
only synthetic data and preserves the original collection bytes/checksums.
"""

from contextlib import closing
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sqlite3
import zipfile

import pytest
from sqlalchemy import MetaData, create_engine, select

from src.storage import logical_archive
from src.storage.logical_archive import TABLE_ORDER, export_archive, import_archive
from src.storage.sqlite_maintenance import StorageValidationError
from tests.test_logical_archive import migrate, seed_source


SCOPED_TABLES = {
    "plan_nodes",
    "tracked_metrics",
    "activity_metric_links_v2",
    "activity_events",
    "metric_observations",
    "timer_sessions",
}
FIXTURE = Path(__file__).parent / "fixtures" / "logical-archive-v1.json"


def read_bundle(path):
    with zipfile.ZipFile(path) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        return {
            "manifest": manifest,
            "collections": {
                name: archive.read(spec["file"]).decode()
                for name, spec in manifest["collections"].items()
            },
        }


def write_bundle(path, bundle):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("manifest.json", json.dumps(bundle["manifest"]))
        for name, content in bundle["collections"].items():
            archive.writestr(f"collections/{name}.jsonl", content)
    return path


def replace_records(bundle, name, records):
    content = "".join(
        json.dumps(row, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        + "\n"
        for row in records
    )
    bundle["collections"][name] = content
    bundle["manifest"]["collections"][name].update(
        rows=len(records), sha256=hashlib.sha256(content.encode()).hexdigest()
    )


def database_dump(path):
    with closing(sqlite3.connect(path)) as connection:
        return list(connection.iterdump())


def duplicate_domain_for_second_owner(url):
    """Copy the complete graph, retaining UUIDs but remapping every internal FK."""
    engine = create_engine(url)
    try:
        metadata = MetaData()
        metadata.reflect(engine)
        with engine.begin() as connection:
            connection.exec_driver_sql("PRAGMA foreign_keys=ON")
            remapped = {("users", 101): 205}
            for name in TABLE_ORDER:
                if name in {"users", "households", "household_memberships"}:
                    continue
                table = metadata.tables[name]
                primary = next(iter(table.primary_key.columns)).name
                rows = [dict(row._mapping) for row in connection.execute(select(table))]
                for row in rows:
                    old_id = row[primary]
                    for column in table.columns:
                        foreign = next(iter(column.foreign_keys), None)
                        if foreign is not None and row[column.name] is not None:
                            row[column.name] = remapped[
                                (foreign.column.table.name, row[column.name])
                            ]
                    if primary == "id":
                        del row[primary]
                    if name == "client_devices":
                        row["public_id"] = "20000000-0000-4000-8000-000000000002"
                    if name == "api_tokens":
                        row["token_hash"] = "synthetic-second-token-hash"
                    if row.get("source_device_public_id"):
                        row["source_device_public_id"] = (
                            "20000000-0000-4000-8000-000000000002"
                        )
                    if name == "plan_nodes":
                        row["title"] = "第二账户：" + row["title"]
                        # Creator and owner need not be the same account.
                        row["created_by_user_id"] = 101
                    if name == "tracked_metrics":
                        row["name"] = "第二账户：" + row["name"]
                    if name == "metric_observations":
                        row["value"] = 68.25
                    result = connection.execute(table.insert().values(**row))
                    inserted_key = result.inserted_primary_key
                    assert inserted_key is not None and inserted_key[0] is not None
                    remapped[(name, old_id)] = inserted_key[0]
            assert connection.exec_driver_sql("PRAGMA foreign_key_check").all() == []
    finally:
        engine.dispose()


def test_same_public_ids_round_trip_without_crossing_accounts(tmp_path):
    source = tmp_path / "source.sqlite"
    target = tmp_path / "target.sqlite"
    source_url, target_url = migrate(source), migrate(target)
    instance, epoch = seed_source(source_url)
    duplicate_domain_for_second_owner(source_url)
    archive = export_archive(source_url, tmp_path / "source.zip")
    bundle = read_bundle(archive)
    assert bundle["manifest"]["format_version"] == 2
    for name in SCOPED_TABLES:
        records = [
            json.loads(line) for line in bundle["collections"][name].splitlines()
        ]
        assert len({row["key"] for row in records}) == len(records)
        for row in records:
            data = row["data"]
            assert (
                row["key"]
                == f"owner:{data['owner_user_id']['key']}:{data['public_id']}"
            )
    new_epoch = import_archive(target_url, archive)
    assert new_epoch != epoch
    exported = read_bundle(export_archive(target_url, tmp_path / "again.zip"))
    assert exported["collections"] == bundle["collections"]
    assert exported["manifest"]["server_instance_id"] == instance
    assert exported["manifest"]["source_sync_epoch"] == new_epoch
    with closing(sqlite3.connect(target)) as connection:
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
        assert connection.execute("SELECT id FROM users ORDER BY id").fetchall() == [
            (1,),
            (2,),
        ]
        assert connection.execute(
            "SELECT u.username, n.title, p.title, m.name, o.value, t.active_elapsed_ms, s.duration_ms "
            "FROM users u JOIN plan_nodes n ON n.owner_user_id=u.id "
            "JOIN plan_nodes p ON p.id=n.parent_node_id AND p.owner_user_id=u.id "
            "JOIN activity_details d ON d.node_id=n.id "
            "JOIN activity_metric_links_v2 l ON l.activity_node_id=n.id AND l.owner_user_id=u.id "
            "JOIN tracked_metrics m ON m.id=l.metric_id AND m.owner_user_id=u.id "
            "JOIN metric_observations o ON o.metric_id=m.id AND o.owner_user_id=u.id "
            "JOIN timer_sessions t ON t.activity_node_id=n.id AND t.owner_user_id=u.id "
            "JOIN activity_events e ON e.id=t.completed_event_id AND e.owner_user_id=u.id AND e.activity_node_id=n.id "
            "JOIN timer_segments s ON s.session_id=t.id ORDER BY u.id"
        ).fetchall() == [
            ("父亲", "睡前阅读", "健康目标", "体重", 67.125, 120000, 120000),
            (
                "孩子",
                "第二账户：睡前阅读",
                "第二账户：健康目标",
                "第二账户：体重",
                68.25,
                120000,
                120000,
            ),
        ]
        assert connection.execute(
            "SELECT u.username, a.local_date, a.duration_ms FROM duration_day_allocations a "
            "JOIN activity_events e ON e.id=a.activity_event_id JOIN users u ON u.id=e.owner_user_id "
            "ORDER BY u.id, a.local_date"
        ).fetchall() == [
            (name, day, ms)
            for name in ("父亲", "孩子")
            for day, ms in (("2026-08-13", 30000), ("2026-08-14", 90000))
        ]


def test_frozen_v1_archive_imports_then_exports_v2(tmp_path):
    bundle = json.loads(FIXTURE.read_text())
    # Archives require an exact schema match; restore the immutable old fixture
    # to its declared revision, not an implicitly changing current head.
    target_url = migrate(
        tmp_path / "target.sqlite", revision=bundle["manifest"]["alembic_head"]
    )
    epoch = import_archive(target_url, write_bundle(tmp_path / "v1.zip", bundle))
    assert epoch != bundle["manifest"]["source_sync_epoch"]
    exported = read_bundle(export_archive(target_url, tmp_path / "v2.zip"))
    assert exported["manifest"]["format_version"] == 2
    assert (
        exported["manifest"]["server_instance_id"]
        == bundle["manifest"]["server_instance_id"]
    )
    assert {
        name: spec["rows"] for name, spec in exported["manifest"]["collections"].items()
    } == {
        name: spec["rows"] for name, spec in bundle["manifest"]["collections"].items()
    }
    # The importer also verifies every old collection checksum after ID remapping.
    assert exported["collections"]["users"] == bundle["collections"]["users"]
    assert exported["collections"]["api_tokens"] == bundle["collections"]["api_tokens"]


@pytest.mark.parametrize("version", [None, True, 1.0, "1", 0, 3])
def test_unsupported_or_noninteger_version_leaves_target_unchanged(tmp_path, version):
    bundle = json.loads(FIXTURE.read_text())
    bundle["manifest"]["format_version"] = version
    target = tmp_path / "target.sqlite"
    url = migrate(target)
    before = database_dump(target)
    with pytest.raises(
        StorageValidationError, match="unsupported logical archive version"
    ):
        import_archive(url, write_bundle(tmp_path / "bad.zip", bundle))
    assert database_dump(target) == before


@pytest.mark.parametrize("version", [1, 2])
@pytest.mark.parametrize(
    "damage,message",
    [
        ("duplicate", "duplicate logical key"),
        ("checksum", "checksum mismatch"),
        ("reference", "unresolved target reference"),
        ("identity", "post-import logical verification failed"),
    ],
)
def test_bad_archive_rolls_back_identity_and_every_table(
    tmp_path, version, damage, message
):
    bundle = json.loads(FIXTURE.read_text())
    if version == 2:
        source_url = migrate(tmp_path / "source.sqlite")
        seed_source(source_url)
        bundle = read_bundle(export_archive(source_url, tmp_path / "v2.zip"))
    name = "timer_segments"
    records = [json.loads(line) for line in bundle["collections"][name].splitlines()]
    if damage == "duplicate":
        records.append(deepcopy(records[0]))
    elif damage == "reference":
        records[0]["data"]["session_id"]["key"] = "missing-session"
    elif damage == "identity":
        records[0]["key"] = "wrong-identity"
    replace_records(bundle, name, records)
    if damage == "checksum":
        bundle["manifest"]["collections"][name]["sha256"] = "0" * 64
    target = tmp_path / "target.sqlite"
    url = migrate(target, revision=bundle["manifest"]["alembic_head"])
    before = database_dump(target)
    with pytest.raises(StorageValidationError, match=message):
        import_archive(url, write_bundle(tmp_path / "bad.zip", bundle))
    assert database_dump(target) == before


def test_export_rejects_identity_collision_without_replacing_existing_file(
    tmp_path, monkeypatch
):
    url = migrate(tmp_path / "source.sqlite")
    seed_source(url)
    original = logical_archive._identity_key

    def collision(table, row, primary_keys, *args, **kwargs):
        if table == "plan_nodes":
            return "duplicate"
        return original(table, row, primary_keys, *args, **kwargs)

    monkeypatch.setattr(logical_archive, "_identity_key", collision)
    path = tmp_path / "protected.zip"
    path.write_bytes(b"existing-backup")
    with pytest.raises(StorageValidationError, match="duplicate logical key"):
        export_archive(url, path)
    assert path.read_bytes() == b"existing-backup"
    assert list(tmp_path.glob("*.incomplete")) == []
