#!/bin/sh
set -eu

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 backup.db [--cancel-active-timers] [--asset-root /absolute/container/path]" >&2
    exit 2
fi

backup_name=$1
shift
case "$backup_name" in
    */*) echo "Backup name must not contain a path" >&2; exit 2 ;;
    dayforge-[0-9]*T[0-9]*Z-[0-9a-f]*.db) ;;
    *) echo "Backup must be a filename created by backup-sqlite.sh" >&2; exit 2 ;;
esac

cancel_flag=
asset_root=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --cancel-active-timers)
            if [ -n "$cancel_flag" ]; then
                echo "Duplicate timer cancellation option" >&2; exit 2
            fi
            cancel_flag=$1
            shift
            ;;
        --asset-root)
            if [ "$#" -lt 2 ] || [ -n "$asset_root" ]; then
                echo "Asset root must be supplied exactly once" >&2; exit 2
            fi
            asset_root=$2
            case "$asset_root" in
                /) echo "Asset root must not be /" >&2; exit 2 ;;
                /*) ;;
                *) echo "Asset root must be an absolute container path" >&2; exit 2 ;;
            esac
            shift 2
            ;;
        *) echo "Unknown restore option" >&2; exit 2 ;;
    esac
done

# A restore error can occur after the DB rename/COMMIT. Never automatically
# start a server whose epoch/files or final fsync outcome has not been checked.
if ! docker compose stop backend; then
    echo "Could not confirm backend stop; inspect service state before retrying" >&2
    exit 1
fi
report_failure() {
    result=$?
    if [ "$result" -ne 0 ]; then
        echo "Maintenance failed; no automatic restart/retry. Inspect database epoch, assets and service state before manually starting backend." >&2
    fi
}
trap report_failure EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

set -- compose run --rm --no-deps \
    backend python -m src.storage.cli sqlite-restore \
    --backup-file "/app/data/backups/$backup_name" \
    --database /app/data/dayforge-v2.db
if [ -n "$cancel_flag" ]; then
    set -- "$@" "$cancel_flag"
fi
if [ -n "$asset_root" ]; then
    set -- "$@" --asset-root "$asset_root"
fi
docker "$@"

set -- compose run --rm --no-deps backend python -m src.storage.cli sqlite-verify \
    --database /app/data/dayforge-v2.db
if [ -n "$asset_root" ]; then
    set -- "$@" --asset-root "$asset_root"
fi
docker "$@"
docker compose up -d backend

trap - EXIT INT TERM
echo "Restore verified; backend start requested (check service health)"
