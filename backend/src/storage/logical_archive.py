"""Versioned, database-independent logical archive using public identities."""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
import zipfile
from datetime import UTC, date, datetime, time
from decimal import Decimal
from pathlib import Path
from typing import Any
from uuid import uuid4

from sqlalchemy import (
    Boolean,
    Date,
    DateTime,
    MetaData,
    Numeric,
    Time,
    create_engine,
    select,
)
from sqlalchemy.engine import Connection, Engine

from src.storage.sqlite_maintenance import StorageValidationError
from src.storage.database_adapter import configure_sqlite_transactions
from src.v2.one_time_recovery import OneTimeRecoveryError, read_connection_history


LOGICAL_FORMAT_VERSION = 2
READABLE_FORMAT_VERSIONS = frozenset({1, LOGICAL_FORMAT_VERSION})
TABLE_ORDER = (
    "users",
    "user_profiles",
    "api_tokens",
    "households",
    "household_memberships",
    "client_devices",
    "user_sync_policies",
    "plan_nodes",
    "goal_details",
    "activity_details",
    "tracked_metrics",
    "activity_metric_links_v2",
    "activity_events",
    "metric_observations",
    "timer_sessions",
    "timer_segments",
    "timer_commands",
    "duration_day_allocations",
    "sync_operations",
    "entity_revision_snapshots",
)
TRANSPORT_TABLES = ("sync_changes", "sync_cursors")


def _canonical(value: Any) -> Any:
    if isinstance(value, datetime):
        normalized = value if value.tzinfo else value.replace(tzinfo=UTC)
        return normalized.astimezone(UTC).isoformat().replace("+00:00", "Z")
    if isinstance(value, (date, time)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, bytes):
        raise StorageValidationError(
            "binary database values are not supported by logical archives"
        )
    return value


def _json_line(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def _digest(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _reflect(engine: Engine) -> MetaData:
    metadata = MetaData()
    metadata.reflect(bind=engine)
    missing = {"server_instances", "users"} - set(metadata.tables)
    if missing:
        raise StorageValidationError(
            f"database is missing required tables: {sorted(missing)}"
        )
    return metadata


def _source_rows(
    connection: Connection, metadata: MetaData
) -> dict[str, list[dict[str, Any]]]:
    names = ["server_instances", *TABLE_ORDER]
    return {
        name: [
            dict(row._mapping)
            for row in connection.execute(select(metadata.tables[name]))
        ]
        for name in names
        if name in metadata.tables
    }


def _identity_key(
    table: str,
    row: dict[str, Any],
    primary_keys: dict[tuple[str, Any], str],
    *,
    format_version: int = LOGICAL_FORMAT_VERSION,
) -> str:
    if table == "server_instances":
        return "singleton"
    if row.get("public_id") is not None:
        # Public domain UUIDs are unique within an owner, not across accounts.
        # v1 is retained only to verify/import existing unambiguous archives.
        if format_version >= 2 and "owner_user_id" in row:
            try:
                owner = primary_keys[("users", row["owner_user_id"])]
            except KeyError as error:
                raise StorageValidationError(
                    f"unresolved source owner reference in {table}"
                ) from error
            return f"owner:{owner}:{row['public_id']}"
        return str(row["public_id"])
    if table == "api_tokens":
        return str(row["token_hash"])
    if table in {"user_profiles", "user_sync_policies"}:
        return f"user:{primary_keys[('users', row['user_id'])]}"
    if table in {"goal_details", "activity_details"}:
        return f"node:{primary_keys[('plan_nodes', row['node_id'])]}"
    if table == "timer_segments":
        return (
            f"{primary_keys[('timer_sessions', row['session_id'])]}:{row['sequence']}"
        )
    if table == "timer_commands":
        return (
            f"{primary_keys[('client_devices', row['device_id'])]}:{row['command_id']}"
        )
    if table == "duration_day_allocations":
        return f"{primary_keys[('activity_events', row['activity_event_id'])]}:{row['local_date']}"
    if table == "sync_operations":
        return f"{primary_keys[('client_devices', row['device_id'])]}:{row['operation_id']}"
    if table == "entity_revision_snapshots":
        owner = primary_keys[("users", row["owner_user_id"])]
        return f"{owner}:{row['entity_type']}:{row['entity_uuid']}:{row['revision']}"
    raise StorageValidationError(f"archive has no logical key for table {table}")


def _build_primary_keys(
    metadata: MetaData,
    rows: dict[str, list[dict[str, Any]]],
    *,
    format_version: int = LOGICAL_FORMAT_VERSION,
) -> dict[tuple[str, Any], str]:
    identities: dict[tuple[str, Any], str] = {}
    for table_name in ("server_instances", *TABLE_ORDER):
        table = metadata.tables.get(table_name)
        if table is None:
            continue
        primary_columns = list(table.primary_key.columns)
        seen: set[str] = set()
        for row in rows.get(table_name, []):
            key = _identity_key(
                table_name, row, identities, format_version=format_version
            )
            if key in seen:
                raise StorageValidationError(f"duplicate logical key in {table_name}")
            seen.add(key)
            if len(primary_columns) == 1:
                identities[(table_name, row[primary_columns[0].name])] = key
    return identities


def _portable_collections(
    connection: Connection,
    metadata: MetaData,
    *,
    format_version: int = LOGICAL_FORMAT_VERSION,
) -> tuple[dict[str, bytes], dict[str, Any]]:
    rows = _source_rows(connection, metadata)
    identities = _build_primary_keys(metadata, rows, format_version=format_version)
    collections: dict[str, bytes] = {}
    identity_row = rows["server_instances"]
    if len(identity_row) != 1:
        raise StorageValidationError("logical archive requires one server identity")
    for table_name in ("server_instances", *TABLE_ORDER):
        table = metadata.tables.get(table_name)
        if table is None:
            continue
        records: list[dict[str, Any]] = []
        for row in rows.get(table_name, []):
            key = _identity_key(
                table_name, row, identities, format_version=format_version
            )
            data: dict[str, Any] = {}
            for column in table.columns:
                if column.name == "id" and column.primary_key:
                    continue
                if table_name == "server_instances" and column.name == "sync_epoch":
                    continue
                value = row[column.name]
                foreign_key = next(iter(column.foreign_keys), None)
                if value is not None and foreign_key is not None:
                    referenced_table = foreign_key.column.table.name
                    try:
                        referenced_key = identities[(referenced_table, value)]
                    except KeyError as error:
                        raise StorageValidationError(
                            f"unresolved source reference {table_name}.{column.name}={value}"
                        ) from error
                    data[column.name] = {
                        "$ref": referenced_table,
                        "key": referenced_key,
                    }
                else:
                    data[column.name] = _canonical(value)
            records.append({"key": key, "data": data})
        records.sort(key=lambda item: item["key"])
        collections[table_name] = b"".join(_json_line(record) for record in records)
    return collections, identity_row[0]


def _archive_engine(database_url: str) -> Engine:
    engine = create_engine(database_url, future=True)
    if engine.dialect.name == "sqlite":
        configure_sqlite_transactions(engine)
    return engine


def _validate_one_time_history(connection: Connection, metadata: MetaData) -> None:
    details = metadata.tables.get("activity_details")
    if details is None or "one_time_version" not in details.c:
        return  # Matching pre-v5 schema has no projection to recover or infer.
    try:
        read_connection_history(connection)
    except OneTimeRecoveryError as error:
        raise StorageValidationError(f"invalid one-time history: {error}") from error


def export_archive(database_url: str, archive_path: Path) -> Path:
    engine = _archive_engine(database_url)
    try:
        metadata = _reflect(engine)
        with engine.connect() as connection:
            has_active_timers = False
            if "timer_sessions" in metadata.tables:
                timer = metadata.tables["timer_sessions"]
                has_active_timers = bool(
                    connection.execute(
                        select(timer.c.id).where(
                            timer.c.state.in_(("running", "paused"))
                        )
                    ).fetchall()
                )
            if has_active_timers:
                raise StorageValidationError(
                    "logical export requires a maintenance window without active timers"
                )
            _validate_one_time_history(connection, metadata)
            collections, identity = _portable_collections(connection, metadata)
            alembic_head = (
                connection.execute(
                    select(metadata.tables["alembic_version"].c.version_num)
                ).scalar_one()
                if "alembic_version" in metadata.tables
                else None
            )
    finally:
        engine.dispose()

    manifest = {
        "format_version": LOGICAL_FORMAT_VERSION,
        "created_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "alembic_head": alembic_head,
        "server_instance_id": identity["instance_uuid"],
        "source_sync_epoch": identity["sync_epoch"],
        "collections": {
            name: {
                "file": f"collections/{name}.jsonl",
                "rows": content.count(b"\n"),
                "sha256": _digest(content),
            }
            for name, content in collections.items()
        },
    }
    archive_path = archive_path.resolve()
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{archive_path.name}.", suffix=".incomplete", dir=archive_path.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        with zipfile.ZipFile(
            temporary, "w", compression=zipfile.ZIP_DEFLATED
        ) as archive:
            archive.writestr(
                "manifest.json",
                json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True)
                + "\n",
            )
            for name, content in collections.items():
                archive.writestr(f"collections/{name}.jsonl", content)
        os.chmod(temporary, 0o600)
        os.replace(temporary, archive_path)
    finally:
        temporary.unlink(missing_ok=True)
    return archive_path


def _read_archive(
    archive_path: Path,
) -> tuple[dict[str, Any], dict[str, list[dict[str, Any]]]]:
    with zipfile.ZipFile(archive_path) as archive:
        names = set(archive.namelist())
        if "manifest.json" not in names:
            raise StorageValidationError("logical archive has no manifest")
        manifest = json.loads(archive.read("manifest.json"))
        version = manifest.get("format_version")
        if type(version) is not int or version not in READABLE_FORMAT_VERSIONS:
            raise StorageValidationError("unsupported logical archive version")
        expected = {"manifest.json"}
        collections: dict[str, list[dict[str, Any]]] = {}
        for name, specification in manifest.get("collections", {}).items():
            member = specification["file"]
            if member != f"collections/{name}.jsonl" or member not in names:
                raise StorageValidationError(f"invalid collection path for {name}")
            expected.add(member)
            content = archive.read(member)
            if _digest(content) != specification["sha256"]:
                raise StorageValidationError(f"collection checksum mismatch: {name}")
            records = [json.loads(line) for line in content.splitlines() if line]
            if len(records) != specification["rows"]:
                raise StorageValidationError(f"collection row count mismatch: {name}")
            if len({record["key"] for record in records}) != len(records):
                raise StorageValidationError(f"duplicate logical key in {name}")
            collections[name] = records
        if names != expected:
            raise StorageValidationError("logical archive contains undeclared files")
    return manifest, collections


def _coerce(column, value: Any) -> Any:
    if value is None:
        return None
    if isinstance(column.type, DateTime):
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if isinstance(column.type, Date) and not isinstance(column.type, DateTime):
        return date.fromisoformat(str(value))
    if isinstance(column.type, Time):
        return time.fromisoformat(str(value))
    if isinstance(column.type, Numeric):
        return Decimal(str(value))
    if isinstance(column.type, Boolean):
        return bool(value)
    return value


def _insert_collection(
    connection: Connection,
    table,
    records: list[dict[str, Any]],
    target_keys: dict[tuple[str, str], Any],
) -> None:
    pending = list(records)
    while pending:
        deferred: list[dict[str, Any]] = []
        progressed = False
        for record in pending:
            values: dict[str, Any] = {}
            unresolved = False
            for name, value in record["data"].items():
                if isinstance(value, dict) and set(value) == {"$ref", "key"}:
                    reference = (value["$ref"], value["key"])
                    if reference not in target_keys:
                        unresolved = True
                        break
                    value = target_keys[reference]
                values[name] = _coerce(table.c[name], value)
            if unresolved:
                deferred.append(record)
                continue
            result = connection.execute(table.insert().values(**values))
            primary_columns = list(table.primary_key.columns)
            if len(primary_columns) != 1:
                raise StorageValidationError(
                    f"unsupported composite primary key in {table.name}"
                )
            primary_name = primary_columns[0].name
            primary_value = values.get(primary_name)
            if primary_value is None:
                inserted_key = result.inserted_primary_key
                if inserted_key is None or inserted_key[0] is None:
                    raise StorageValidationError(
                        f"missing inserted primary key in {table.name}"
                    )
                primary_value = inserted_key[0]
            target_keys[(table.name, record["key"])] = primary_value
            progressed = True
        if not progressed and deferred:
            sample = deferred[0]
            raise StorageValidationError(
                f"unresolved target reference while importing {table.name}/{sample['key']}"
            )
        pending = deferred


def import_archive(database_url: str, archive_path: Path) -> str:
    manifest, collections = _read_archive(archive_path.resolve())
    engine = _archive_engine(database_url)
    new_epoch = str(uuid4())
    try:
        metadata = _reflect(engine)
        if (
            manifest.get("alembic_head") is not None
            and "alembic_version" in metadata.tables
        ):
            with engine.connect() as connection:
                target_head = connection.execute(
                    select(metadata.tables["alembic_version"].c.version_num)
                ).scalar_one()
            if target_head != manifest["alembic_head"]:
                raise StorageValidationError(
                    "target Alembic head differs from the archive"
                )

        with engine.begin() as connection:
            for name in TABLE_ORDER:
                if name in metadata.tables:
                    count = connection.execute(select(metadata.tables[name])).first()
                    if count is not None:
                        raise StorageValidationError(
                            f"target table is not empty: {name}"
                        )

            server_records = collections.get("server_instances", [])
            if len(server_records) != 1:
                raise StorageValidationError("archive must contain one server identity")
            server = metadata.tables["server_instances"]
            server_values = {
                name: _coerce(server.c[name], value)
                for name, value in server_records[0]["data"].items()
            }
            server_values["sync_epoch"] = new_epoch
            existing = connection.execute(
                select(server.c.id).where(server.c.id == 1)
            ).first()
            if existing:
                connection.execute(
                    server.update().where(server.c.id == 1).values(**server_values)
                )
            else:
                connection.execute(server.insert().values(id=1, **server_values))

            target_keys: dict[tuple[str, str], Any] = {
                ("server_instances", "singleton"): 1
            }
            for name in TABLE_ORDER:
                if name in collections:
                    if name not in metadata.tables:
                        raise StorageValidationError(
                            f"target schema has no collection table {name}"
                        )
                    _insert_collection(
                        connection,
                        metadata.tables[name],
                        collections[name],
                        target_keys,
                    )
            _validate_one_time_history(connection, metadata)
            for name in TRANSPORT_TABLES:
                if name in metadata.tables:
                    connection.execute(metadata.tables[name].delete())
            if engine.dialect.name == "sqlite":
                connection.exec_driver_sql(
                    "DELETE FROM sqlite_sequence WHERE name IN ('sync_changes','sync_cursors')"
                )
            rebuilt, _ = _portable_collections(
                connection, metadata, format_version=manifest["format_version"]
            )
            for name, specification in manifest["collections"].items():
                content = rebuilt.get(name)
                if content is None or _digest(content) != specification["sha256"]:
                    raise StorageValidationError(
                        f"post-import logical verification failed: {name}"
                    )
    finally:
        engine.dispose()
    return new_epoch
