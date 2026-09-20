"""Administrative storage command line entry point."""

from __future__ import annotations

import argparse
from pathlib import Path
from alembic.config import Config
from alembic.script import ScriptDirectory

from src.storage.sqlite_maintenance import (
    StorageValidationError,
    backfill_revision_snapshots,
    create_backup,
    inspect_database,
    restore_backup,
)
from src.storage.logical_archive import export_archive, import_archive


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m src.storage.cli")
    commands = parser.add_subparsers(dest="command", required=True)

    backup = commands.add_parser(
        "sqlite-backup", help="create a verified SQLite backup"
    )
    backup.add_argument("--database", type=Path, required=True)
    backup.add_argument("--backup-dir", type=Path, required=True)
    backup.add_argument("--kind", choices=("scheduled", "manual"), default="scheduled")

    restore = commands.add_parser(
        "sqlite-restore", help="restore a verified SQLite backup"
    )
    restore.add_argument("--database", type=Path, required=True)
    restore.add_argument("--backup-file", type=Path, required=True)
    restore.add_argument("--cancel-active-timers", action="store_true")

    verify = commands.add_parser("sqlite-verify", help="verify a live SQLite database")
    verify.add_argument("--database", type=Path, required=True)

    snapshots = commands.add_parser(
        "snapshot-backfill",
        help="backfill revision snapshots from retained sync changes",
    )
    snapshots.add_argument("--database", type=Path, required=True)

    logical_export = commands.add_parser(
        "logical-export", help="create a versioned portable database archive"
    )
    logical_export.add_argument("--database-url", required=True)
    logical_export.add_argument("--output", type=Path, required=True)

    logical_import = commands.add_parser(
        "logical-import",
        help="import a portable archive into an empty migrated database",
    )
    logical_import.add_argument("--database-url", required=True)
    logical_import.add_argument("--archive", type=Path, required=True)
    return parser


def _current_alembic_head() -> str:
    return ScriptDirectory.from_config(Config("alembic.ini")).get_current_head()


def main() -> int:
    args = _parser().parse_args()
    try:
        if args.command == "sqlite-backup":
            database, manifest = create_backup(
                args.database,
                args.backup_dir,
                kind=args.kind,
            )
            print(f"backup={database}")
            print(f"manifest={manifest}")
        elif args.command == "sqlite-restore":
            safety, epoch = restore_backup(
                args.backup_file,
                args.database,
                cancel_active_timers=args.cancel_active_timers,
                expected_alembic_head=_current_alembic_head(),
            )
            print(f"pre_restore_backup={safety or ''}")
            print(f"sync_epoch={epoch}")
        elif args.command == "sqlite-verify":
            inspection = inspect_database(args.database)
            if not inspection.valid:
                raise StorageValidationError(str(inspection))
            print(inspection)
        elif args.command == "snapshot-backfill":
            print(f"inserted={backfill_revision_snapshots(args.database)}")
        elif args.command == "logical-export":
            print(f"archive={export_archive(args.database_url, args.output)}")
        elif args.command == "logical-import":
            print(f"sync_epoch={import_archive(args.database_url, args.archive)}")
    except StorageValidationError as error:
        raise SystemExit(f"storage validation failed: {error}") from error
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
