#!/bin/sh
set -eu

docker compose exec -T backend python -m src.storage.cli sqlite-backup \
    --database /app/data/dayforge-v2.db \
    --backup-dir /app/data/backups

echo "Backup created under ${DAYFORGE_DATA_DIR:-./data}/backups"
