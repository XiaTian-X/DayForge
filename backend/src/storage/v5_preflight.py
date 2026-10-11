"""Read-only inventory for a coordinated v5 empty-baseline maintenance window.

An empty snapshot is a candidate, not a release authorization, a write lease,
or evidence that Android, deployed artifacts or recovery have been accepted.
Never turn this report into an automatic reset/activation fallback.
"""

from contextlib import closing
from dataclasses import dataclass
from pathlib import Path
import os
import sqlite3
import stat
from uuid import UUID

from alembic.config import Config
from alembic.script import ScriptDirectory
from sqlmodel import SQLModel

from src.auth.models import User as User  # Register retained identity columns.
from src.tokens.models import ApiToken as ApiToken
from src.v2.models import ServerInstance as ServerInstance
from src.v2 import asset_models as asset_models
from src.v2 import challenge_models as challenge_models
from src.v2 import object_appearance_models as object_appearance_models
from src.storage.asset_files import AssetFileError, _child_directory, _root_directory
from src.storage.asset_root import LOCK_NAME
from src.storage.logical_archive import TABLE_ORDER, TRANSPORT_TABLES
from src.time_utils import utc_now


# Classify EVERY current collection, including tombstones and retained receipts.
# Unknown future tables/columns fail closed until this inventory is reviewed.
RETAINED_TABLES = frozenset(
    {"users", "api_tokens", "user_profiles", "households", "household_memberships"}
)
BASELINE_TABLES = frozenset((*TABLE_ORDER, *TRANSPORT_TABLES)) - RETAINED_TABLES
EXPECTED_TABLES = (
    RETAINED_TABLES
    | BASELINE_TABLES
    | {
        "server_instances",
        "alembic_version",
    }
)
SUPPORTED_PREFLIGHT_HEAD = "000000000009"
REMAINING_GATES = (
    "confirmed_target_and_stopped_writers",
    "matching_backend_android_artifacts_and_migration_provenance",
    "android_empty_baseline_and_durable_queue_review",
    "asset_write_permissions_lease_and_durability",
    "old_registration_requests_and_frozen_v4_rejection",
    "joint_regression_and_verified_recovery",
    "phone_tablet_and_deployment_acceptance",
    "explicit_coordinated_activation_approval",
)


@dataclass(frozen=True)
class V5Preflight:
    checked_at: str
    expected_alembic_head: str
    alembic_head: str | None = None
    server_instance_id: str | None = None
    sync_epoch: str | None = None
    protocol_version: int | None = None
    occupied_baseline_tables: dict[str, int] | None = None
    retained_row_counts: dict[str, int] | None = None
    blockers: tuple[str, ...] = ()
    database_inventory_checked: bool = False
    asset_root_empty: bool = False
    format: str = "dayforge.v5-preflight"
    format_version: int = 1
    snapshot_only: bool = True
    activation_authorized: bool = False
    remaining_gates: tuple[str, ...] = REMAINING_GATES

    @property
    def empty_baseline_candidate(self) -> bool:
        return (
            self.database_inventory_checked
            and self.asset_root_empty
            and not self.blockers
            and self.alembic_head
            == self.expected_alembic_head
            == SUPPORTED_PREFLIGHT_HEAD
            and self.protocol_version in (4, 5)
            and _canonical_id(self.server_instance_id)
            and _canonical_id(self.sync_epoch)
            and self.occupied_baseline_tables == {}
            and self.retained_row_counts is not None
        )


def _current_head() -> str:
    # Independent of cwd, deployment .env and runtime Settings construction.
    backend = Path(__file__).resolve().parents[2]
    config = Config(str(backend / "alembic.ini"))
    config.set_main_option("script_location", str(backend / "alembic"))
    head = ScriptDirectory.from_config(config).get_current_head()
    if not isinstance(head, str):
        raise ValueError("v5 preflight requires one current migration head")
    return head


def _tables(connection: sqlite3.Connection) -> set[str]:
    return {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    }


def _current_columns(connection: sqlite3.Connection) -> bool:
    for table in sorted(EXPECTED_TABLES):
        # These names are our fixed inventory, never a name interpolated from input.
        expected = (
            {"version_num"}
            if table == "alembic_version"
            else set(SQLModel.metadata.tables[table].columns.keys())
        )
        actual = {
            row[1] for row in connection.execute(f'PRAGMA table_xinfo("{table}")')
        }
        if actual != expected:
            return False
    return True


def _canonical_id(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        return str(UUID(value)) == value
    except ValueError:
        return False


def _asset_baseline(root: Path | None) -> str | None:
    if root is None:
        return "ASSET_ROOT_NOT_CHECKED"
    try:
        # No lease acquisition, write probe, recovery, creation or cleanup. An existing
        # empty lock and empty accounts skeleton are allowed; anything else needs review.
        with _root_directory(root) as descriptor, os.scandir(descriptor) as entries:
            for entry in entries:
                if entry.name == LOCK_NAME:
                    info = entry.stat(follow_symlinks=False)
                    if (
                        not stat.S_ISREG(info.st_mode)
                        or info.st_nlink != 1
                        or info.st_size != 0
                    ):
                        return "ASSET_ROOT_INVALID"
                elif entry.name == "accounts":
                    with _child_directory(descriptor, "accounts") as accounts:
                        with os.scandir(accounts) as owners:
                            if next(owners, None) is not None:
                                return "ASSET_BASELINE_REVIEW_REQUIRED"
                else:
                    return "ASSET_BASELINE_REVIEW_REQUIRED"
    except (OSError, AssetFileError):
        return "ASSET_ROOT_INVALID"
    return None


def preflight_v5(database: Path, *, asset_root: Path | None = None) -> V5Preflight:
    """One SQLite read snapshot; report no data values, credentials or I/O errors.

    mode=ro/query_only protect SQL data. SQLite may coordinate its WAL/SHM files;
    immutable=1 is deliberately NOT used because it would ignore live WAL data.
    The asset inventory is only a point-in-time read, not an exclusive root lease.
    """
    expected = _current_head()
    checked = utc_now().isoformat().replace("+00:00", "Z")
    if expected != SUPPORTED_PREFLIGHT_HEAD:
        return V5Preflight(
            checked, expected, blockers=("PREFLIGHT_SCHEMA_REVIEW_REQUIRED",)
        )
    try:
        path = database.absolute()
        if database.is_symlink() or not path.is_file():
            return V5Preflight(checked, expected, blockers=("DATABASE_UNAVAILABLE",))
        with closing(
            sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=5)
        ) as connection:
            connection.execute("PRAGMA query_only=ON")
            connection.execute("BEGIN")
            if _tables(connection) != EXPECTED_TABLES or not _current_columns(
                connection
            ):
                return V5Preflight(
                    checked, expected, blockers=("DATABASE_SCHEMA_SHAPE_MISMATCH",)
                )
            versions = connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchall()
            if len(versions) != 1 or versions[0][0] != expected:
                return V5Preflight(
                    checked, expected, blockers=("DATABASE_MIGRATION_HEAD_MISMATCH",)
                )
            integrity = connection.execute("PRAGMA integrity_check").fetchall()
            if (
                integrity != [("ok",)]
                or connection.execute("PRAGMA foreign_key_check").fetchone() is not None
            ):
                return V5Preflight(
                    checked,
                    expected,
                    expected,
                    blockers=("DATABASE_INTEGRITY_INVALID",),
                )
            identities = connection.execute(
                "SELECT id, instance_uuid, sync_epoch, protocol_version, typeof(protocol_version) "
                "FROM server_instances"
            ).fetchall()
            if (
                len(identities) != 1
                or identities[0][0] != 1
                or not all(_canonical_id(value) for value in identities[0][1:3])
            ):
                return V5Preflight(
                    checked, expected, expected, blockers=("SERVER_IDENTITY_INVALID",)
                )
            _, instance, epoch, protocol, protocol_type = identities[0]
            if protocol_type != "integer" or protocol not in (4, 5):
                return V5Preflight(
                    checked,
                    expected,
                    expected,
                    instance,
                    epoch,
                    blockers=("SERVER_PROTOCOL_UNSUPPORTED",),
                )
            occupied = {}
            for table in sorted(BASELINE_TABLES):
                count = connection.execute(
                    f'SELECT COUNT(*) FROM "{table}"'
                ).fetchone()[0]
                if count:
                    occupied[table] = count
            retained = {
                table: connection.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[
                    0
                ]
                for table in sorted(RETAINED_TABLES)
            }
            blockers = []
            if occupied:
                blockers.append("DATABASE_BASELINE_REVIEW_REQUIRED")
            root_error = _asset_baseline(asset_root)
            if root_error:
                blockers.append(root_error)
            return V5Preflight(
                checked,
                expected,
                expected,
                instance,
                epoch,
                protocol,
                occupied,
                retained,
                tuple(blockers),
                database_inventory_checked=True,
                asset_root_empty=root_error is None,
            )
    except (OSError, sqlite3.Error):
        # CLI reports finite codes only; driver messages can contain paths/schema/data.
        return V5Preflight(
            checked, expected, blockers=("DATABASE_UNAVAILABLE_OR_INVALID",)
        )
