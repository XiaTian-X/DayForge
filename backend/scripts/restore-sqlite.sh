#!/bin/sh
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "Usage: $0 dayforge-YYYYMMDDTHHMMSSZ-xxxxxxxx.db [--cancel-active-timers]" >&2
    exit 2
fi

backup_name=$1
case "$backup_name" in
    */*) echo "Backup name must not contain a path" >&2; exit 2 ;;
    dayforge-[0-9]*T[0-9]*Z-[0-9a-f]*.db) ;;
    *) echo "Backup must be a filename created by backup-sqlite.sh" >&2; exit 2 ;;
esac

cancel_flag=${2:-}
case "$cancel_flag" in
    ""|--cancel-active-timers) ;;
    *) echo "Unknown restore option: $cancel_flag" >&2; exit 2 ;;
esac

restart_backend() {
    docker compose up -d backend >/dev/null
}
trap restart_backend EXIT INT TERM

docker compose stop backend
docker compose run --rm --no-deps \
    backend python -m src.storage.cli sqlite-restore \
    --backup-file "/app/data/backups/$backup_name" \
    --database /app/data/dayforge-v2.db \
    $cancel_flag

trap - EXIT INT TERM
restart_backend
echo "Restore completed and backend restarted"
