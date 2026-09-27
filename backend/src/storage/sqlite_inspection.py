"""Read-only SQLite metadata inspection, with explicit ready-byte verification."""

from collections.abc import Callable
from contextlib import closing
from dataclasses import dataclass
from pathlib import Path
import sqlite3
from typing import Any

from src.storage.asset_archive import ArchiveBlob
from src.storage.asset_files import AssetFiles
from src.storage.errors import StorageValidationError
from src.v2.asset_recovery import AssetRecoveryError, ReadyBlob, read_asset_metadata
from src.v2.object_appearance_recovery import (
    ObjectAppearanceRecoveryError,
    read_object_appearances,
)
from src.v2.one_time_recovery import OneTimeRecoveryError, read_one_time_history


@dataclass(frozen=True)
class DatabaseInspection:
    integrity_check: str
    foreign_key_errors: list[dict[str, Any]]
    domain_errors: list[str]
    alembic_head: str | None
    server_instance_id: str | None
    sync_epoch: str | None
    protocol_version: int | None
    active_timer_count: int
    row_counts: dict[str, int]
    tombstone_counts: dict[str, int]

    @property
    def valid(self) -> bool:
        return (
            self.integrity_check == "ok"
            and not self.foreign_key_errors
            and not self.domain_errors
        )


def _table_names(connection: sqlite3.Connection) -> list[str]:
    return [
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        )
    ]


AssetVerifier = Callable[[tuple[ArchiveBlob, ...]], None]


def inspect_database(
    path: Path, *, asset_root: Path | None = None
) -> DatabaseInspection:
    """Validate metadata and, when configured, every ready file in that snapshot."""

    def verify(entries: tuple[ArchiveBlob, ...]) -> None:
        if asset_root is None:
            raise AssetRecoveryError("appearance bytes require an asset root")
        try:
            files = AssetFiles(asset_root)
            for entry in entries:
                files.read(entry.owner_public_id, entry.blob, entry.profile)
        except (ValueError, OSError) as error:
            raise AssetRecoveryError(
                "appearance bytes are unavailable or invalid"
            ) from error

    return _inspect_database(
        path, verify_assets=verify if asset_root is not None else None
    )


def _inspect_database(
    path: Path, *, verify_assets: AssetVerifier | None = None
) -> DatabaseInspection:
    path = path.resolve()
    if not path.is_file():
        raise StorageValidationError(f"database not found: {path}")
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("BEGIN")  # All inspection fields describe one snapshot.
        integrity_rows = connection.execute("PRAGMA integrity_check").fetchall()
        integrity = (
            "ok"
            if len(integrity_rows) == 1 and integrity_rows[0][0] == "ok"
            else "; ".join(str(row[0]) for row in integrity_rows)
        )
        foreign_keys = [
            dict(row) for row in connection.execute("PRAGMA foreign_key_check")
        ]
        tables = _table_names(connection)
        row_counts = {
            table: int(
                connection.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
            )
            for table in tables
        }
        tombstone_counts: dict[str, int] = {}
        for table in tables:
            columns = {
                row[1] for row in connection.execute(f'PRAGMA table_info("{table}")')
            }
            if "deleted_at" in columns:
                tombstone_counts[table] = int(
                    connection.execute(
                        f'SELECT COUNT(*) FROM "{table}" WHERE deleted_at IS NOT NULL'
                    ).fetchone()[0]
                )

        alembic_head = None
        if "alembic_version" in tables:
            row = connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone()
            alembic_head = row[0] if row else None

        identity = None
        if "server_instances" in tables:
            identity = connection.execute(
                "SELECT instance_uuid, sync_epoch, protocol_version FROM server_instances WHERE id = 1"
            ).fetchone()
        active_timers = 0
        if "timer_sessions" in tables:
            active_timers = int(
                connection.execute(
                    "SELECT COUNT(*) FROM timer_sessions WHERE state IN ('running','paused')"
                ).fetchone()[0]
            )

        domain_errors: list[str] = []
        if "appearance_accounts" in tables:

            def verify_snapshot(values: tuple[ReadyBlob, ...]) -> None:
                identities = connection.execute(
                    "SELECT id, public_id FROM users"
                ).fetchall()
                owners = dict(identities)
                if len(owners) != len(identities) or len(set(owners.values())) != len(
                    owners
                ):
                    raise AssetRecoveryError("ambiguous appearance public ownership")
                try:
                    entries = tuple(
                        ArchiveBlob(
                            owners[value.owner_user_id], value.blob, value.profile
                        )
                        for value in values
                    )
                except (KeyError, ValueError) as error:
                    raise AssetRecoveryError(
                        "invalid appearance public ownership"
                    ) from error
                if verify_assets is not None:
                    verify_assets(entries)

            try:
                read_asset_metadata(
                    lambda statement: [
                        dict(row) for row in connection.execute(statement)
                    ],
                    verify_ready=verify_snapshot if verify_assets is not None else None,
                )
            except AssetRecoveryError as error:
                domain_errors.append(f"invalid appearance metadata: {error}")
        if "plan_node_appearances" in tables:
            try:
                read_object_appearances(
                    lambda statement: [
                        dict(row) for row in connection.execute(statement)
                    ]
                )
            except ObjectAppearanceRecoveryError as error:
                domain_errors.append(f"invalid object appearance: {error}")
        if "activity_details" in tables:
            detail_columns = {
                row[1]
                for row in connection.execute('PRAGMA table_info("activity_details")')
            }
            if "one_time_version" in detail_columns:
                try:
                    read_one_time_history(
                        lambda statement, parameters: [
                            dict(row)
                            for row in connection.execute(statement, parameters)
                        ]
                    )
                except OneTimeRecoveryError as error:
                    domain_errors.append(f"invalid one-time history: {error}")
        if "server_instances" in tables and row_counts["server_instances"] != 1:
            domain_errors.append("server_instances must contain exactly one row")
        if "plan_nodes" in tables:
            invalid_goals = connection.execute(
                "SELECT COUNT(*) FROM plan_nodes "
                "WHERE node_kind = 'goal' AND parent_node_id IS NOT NULL"
            ).fetchone()[0]
            if invalid_goals:
                domain_errors.append(f"{invalid_goals} goal nodes have a parent")
            invalid_parents = connection.execute(
                "SELECT COUNT(*) FROM plan_nodes child "
                "JOIN plan_nodes parent ON parent.id = child.parent_node_id "
                "WHERE child.node_kind != 'activity' OR parent.node_kind != 'goal' "
                "OR parent.parent_node_id IS NOT NULL"
            ).fetchone()[0]
            if invalid_parents:
                domain_errors.append(
                    f"{invalid_parents} plan nodes violate the single-parent hierarchy"
                )
        if "entity_revision_snapshots" in tables:
            duplicate_snapshots = connection.execute(
                "SELECT COUNT(*) FROM ("
                "SELECT 1 FROM entity_revision_snapshots "
                "GROUP BY owner_user_id, entity_type, entity_uuid, revision HAVING COUNT(*) > 1)"
            ).fetchone()[0]
            if duplicate_snapshots:
                domain_errors.append(
                    f"{duplicate_snapshots} duplicate revision snapshots"
                )

        return DatabaseInspection(
            integrity_check=integrity,
            foreign_key_errors=foreign_keys,
            domain_errors=domain_errors,
            alembic_head=alembic_head,
            server_instance_id=identity[0] if identity else None,
            sync_epoch=identity[1] if identity else None,
            protocol_version=int(identity[2]) if identity else None,
            active_timer_count=active_timers,
            row_counts=row_counts,
            tombstone_counts=tombstone_counts,
        )


def _copy_with_sqlite_backup(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with (
        closing(
            sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True)
        ) as source_db,
        closing(sqlite3.connect(target)) as target_db,
    ):
        source_db.backup(target_db)
