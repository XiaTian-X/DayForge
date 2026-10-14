"""Owned real backend for physical testbed integration; never a deployment tool."""

import argparse
from contextlib import closing
import json
from pathlib import Path
import secrets
import signal
import sqlite3
import time

from sqlalchemy import create_engine
from sqlmodel import Session

from src.auth.models import User
from src.storage.sqlite_maintenance import inspect_database
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD, account_password_hash
from tests.test_jwt_runtime_server import isolated_server
from tests.test_logical_archive import migrate


def serve(directory: Path) -> None:
    # The gate creates this unique ignored output directory. No existing DB,
    # project .env, formal deployment path or externally supplied credentials.
    directory.mkdir(exist_ok=False)
    database = directory / "joint.sqlite"
    engine = create_engine(migrate(database))
    try:
        with Session(engine) as session:
            for name in (
                "joint-forward",
                "joint-countdown",
                "joint-core",
                "joint-once",
                "joint-material",
                "joint-config",
                "joint-scheduler",
                "joint-scheduler-replace",
            ):
                session.add(User(username=name, password_hash=account_password_hash()))
            session.commit()
    finally:
        engine.dispose()
    with closing(sqlite3.connect(database)) as connection:
        connection.execute("UPDATE server_instances SET protocol_version=5")
        connection.commit()
    assets = directory / "assets"
    assets.mkdir()
    # uv and the owned process group may both deliver a stop signal. A handler
    # must not take Event/Condition locks while interrupting that same thread.
    stopping = False

    def request_stop(_signal, _frame):
        nonlocal stopping
        stopping = True

    # This is an owned CLI process, not an in-process fixture. Keep its handlers
    # through interpreter exit: a late uv relay after serve() returns must not
    # restore KeyboardInterrupt/SIGTERM between the terminal proof and exit(0).
    for kind in (signal.SIGTERM, signal.SIGINT):
        signal.signal(kind, request_stop)
    with isolated_server(
        directory,
        database=database,
        asset_root=assets,
        credentials=(secrets.token_urlsafe(48), TEST_ACCOUNT_PASSWORD),
    ) as (client, _, _):
        ready = directory / "ready.tmp"
        ready.write_text(json.dumps({"url": client.base_url}) + "\n")
        ready.rename(directory / "ready.json")
        while not stopping:
            time.sleep(0.05)
    inspected = inspect_database(database, asset_root=assets)
    if not inspected.valid or inspected.active_timer_count:
        raise ValueError("Joint backend did not finish with valid terminal data")
    (directory / "complete.json").write_text(
        json.dumps(
            {
                "valid": inspected.valid,
                "active_timer_count": inspected.active_timer_count,
                "protocol_version": inspected.protocol_version,
                "server_instance_id": inspected.server_instance_id,
                "sync_epoch": inspected.sync_epoch,
                "alembic_head": inspected.alembic_head,
                "row_counts": inspected.row_counts,
            },
            indent=2,
        )
        + "\n"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    serve(parser.parse_args().directory)
