#!/bin/sh
set -eu

asset_root=
if [ "$#" -ne 0 ]; then
    if [ "$#" -ne 2 ] || [ "$1" != "--asset-root" ]; then
        echo "Usage: $0 [--asset-root /absolute/container/path]" >&2
        exit 2
    fi
    asset_root=$2
    case "$asset_root" in
        /) echo "Asset root must not be /" >&2; exit 2 ;;
        /*) ;;
        *) echo "Asset root must be an absolute container path" >&2; exit 2 ;;
    esac
fi

set -- compose exec -T backend python -m src.storage.cli sqlite-backup \
    --database /app/data/dayforge-v2.db --backup-dir /app/data/backups
if [ -n "$asset_root" ]; then
    set -- "$@" --asset-root "$asset_root"
fi
docker "$@"

echo "Backup created under ${DAYFORGE_DATA_DIR:-./data}/backups"
