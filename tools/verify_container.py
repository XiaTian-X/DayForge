"""Hosted-CI-only container rehearsal, with isolated synthetic data and no publishing.

The probe is streamed into the image's Python runtime. It uses the shared wire
fixtures, including a synthetic offline timer command history, not device timing.
"""

import argparse
import hashlib
import json
import os
import secrets
import sqlite3
import subprocess
import sys
import time
from contextlib import closing
from pathlib import Path
from uuid import uuid4


DATABASE = "/app/data/dayforge-ci.sqlite"
MARKER = "90000000-0000-4000-8000-000000000099"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def require_hosted_ci(environment):
    require(
        environment.get("GITHUB_ACTIONS") == "true"
        and environment.get("RUNNER_ENVIRONMENT") == "github-hosted",
        "Container verification runs only on GitHub-hosted CI, not this development device",
    )


def require_statuses(response, expected, count):
    require(
        [item["status"] for item in response["results"]] == [expected] * count,
        "Unexpected operation acknowledgements",
    )


def compare_restored(before, after):
    require(before["tables"] == after["tables"], "Restored table content changed")
    require(before["identity"] == after["identity"], "Server identity changed")
    require(before["epoch"] != after["epoch"], "Restore did not rotate sync epoch")


def database_snapshot():
    with closing(sqlite3.connect(f"file:{DATABASE}?mode=ro", uri=True)) as db:
        require(
            db.execute("PRAGMA integrity_check").fetchall() == [("ok",)],
            "Integrity failed",
        )
        require(
            not db.execute("PRAGMA foreign_key_check").fetchall(), "Foreign keys failed"
        )
        tables = {}
        for (name,) in db.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        ):
            if name.startswith("sqlite_"):
                continue
            # The only deliberately changed field on normal restore is sync_epoch.
            columns = [row[1] for row in db.execute(f'PRAGMA table_info("{name}")')]
            if name == "server_instances":
                columns.remove("sync_epoch")
            selected = ",".join(
                '"' + column.replace('"', '""') + '"' for column in columns
            )
            rows = sorted(
                json.dumps(row, default=str)
                for row in db.execute(f'SELECT {selected} FROM "{name}"')
            )
            tables[name] = {
                "rows": len(rows),
                "sha256": hashlib.sha256("\n".join(rows).encode()).hexdigest(),
            }
        identity, epoch = db.execute(
            "SELECT instance_uuid, sync_epoch FROM server_instances WHERE id=1"
        ).fetchone()
        return {"tables": tables, "identity": identity, "epoch": epoch}


def probe(stage):
    if stage == "snapshot":
        print(json.dumps(database_snapshot()))
        return
    if stage == "backup-path":
        backups = list(Path("/app/data/backups").glob("dayforge-*.db"))
        require(len(backups) == 1, "Expected one manual backup")
        print(backups[0])
        return
    if stage == "corrupt-backup":
        import shutil

        source = next(Path("/app/data/backups").glob("dayforge-*.db"))
        target = Path("/app/data/corrupt") / source.name
        target.parent.mkdir()
        shutil.copy2(source, target)
        shutil.copy2(str(source) + ".manifest.json", str(target) + ".manifest.json")
        with target.open("ab") as output:
            output.write(b"intentional CI checksum fault")
        print(target)
        return

    sys.path.insert(0, "/ci-scripts")
    from sync_acceptance_fixture import AcceptanceClient, check_activity_payload

    client = AcceptanceClient("http://127.0.0.1:8000")
    if stage == "health":
        require(client.request("GET", "/health") == {"status": "ok"}, "Health failed")
        require(os.getuid() != 0, "Runtime must be non-root")
        return
    login = client.login(os.environ["ADMIN_USERNAME"], os.environ["ADMIN_PASSWORD"])
    token = login["access_token"]
    device = client.register_device(token, "container-ci")
    if stage == "mutate":
        response = client.request(
            "POST",
            "/api/v2/sync/push",
            token=token,
            payload={
                "device_id": device,
                "operations": [
                    {
                        "operation_id": MARKER,
                        "entity_type": "plan_node",
                        "entity_uuid": MARKER,
                        "action": "upsert",
                        "payload": check_activity_payload("After backup"),
                    }
                ],
            },
        )
        require_statuses(response, "applied", 1)
        return
    for file, endpoint, count in (
        ("push-all-entities.json", "/api/v2/sync/push", 7),
        ("timer-commands.json", "/api/v2/timers/commands", 4),
    ):
        payload = json.loads((Path("/ci-contracts/sync-v2/client") / file).read_text())
        payload["device_id"] = device
        response = client.request("POST", endpoint, token=token, payload=payload)
        require_statuses(
            response, "applied" if stage == "seed" else "already_applied", count
        )
        replay = client.request("POST", endpoint, token=token, payload=payload)
        require_statuses(replay, "already_applied", count)
    snapshot = client.request(
        "GET", f"/api/v2/sync/bootstrap?device_id={device}", token=token
    )
    changes = snapshot["changes"]
    require(not snapshot.get("has_more"), "Unexpected fixture pagination")
    require(
        MARKER not in {item["entity_uuid"] for item in changes},
        "Post-backup record survived restore",
    )
    require(
        {item["entity_type"] for item in changes}
        == {
            "plan_node",
            "metric",
            "activity_event",
            "metric_observation",
            "activity_metric_link",
        },
        "Missing entity type in HTTP bootstrap",
    )
    # Timer sessions use their dedicated endpoint, not the fact bootstrap stream.
    timer = client.request(
        "GET",
        f"/api/v2/timers/80000000-0000-4000-8000-000000000001?device_id={device}",
        token=token,
    )["session"]
    require(
        timer is not None
        and timer["state"] == "completed"
        and timer["active_elapsed_ms"] == 60000
        and timer["completed_event_id"] is not None,
        "Completed timer missing",
    )
    require(
        timer["completed_event_id"] in {item["entity_uuid"] for item in changes},
        "Timer completion fact missing from bootstrap",
    )
    # Fixture processing must populate the recovery-sensitive collections, not just an empty DB.
    snapshot = database_snapshot()
    for name in (
        "users",
        "client_devices",
        "plan_nodes",
        "tracked_metrics",
        "activity_events",
        "metric_observations",
        "activity_metric_links_v2",
        "timer_sessions",
        "timer_segments",
        "timer_commands",
        "duration_day_allocations",
        "sync_operations",
        "sync_changes",
        "entity_revision_snapshots",
    ):
        require(snapshot["tables"][name]["rows"] > 0, f"Empty fixture table: {name}")


def run(image):
    require_hosted_ci(os.environ)
    require(
        image.startswith("sha256:") and len(image) == 71, "Use a locally built image ID"
    )
    root = Path(__file__).resolve().parents[1]
    name = "dayforge-ci-" + uuid4().hex
    volume = name + "-data"
    environment = dict(
        os.environ,
        JWT_SECRET_KEY=secrets.token_hex(32),
        ADMIN_USERNAME="container_admin",
        ADMIN_PASSWORD="Ci-" + secrets.token_hex(16),
    )
    source = Path(__file__).read_text()
    common = [
        "--network",
        "none",
        "--mount",
        f"type=volume,src={volume},dst=/app/data",
        "--mount",
        f"type=bind,src={root / 'backend/scripts'},dst=/ci-scripts,readonly",
        "--mount",
        f"type=bind,src={root / 'contracts'},dst=/ci-contracts,readonly",
        "-e",
        "JWT_SECRET_KEY",
        "-e",
        "ADMIN_USERNAME",
        "-e",
        "ADMIN_PASSWORD",
        "-e",
        "ENVIRONMENT=production",
        "-e",
        f"SQLITE_DB_PATH={DATABASE}",
    ]

    def docker(*args, input=None, expected=0, error_contains=None):
        result = subprocess.run(
            ["docker", *args],
            input=input,
            text=True,
            capture_output=True,
            env=environment,
            timeout=120,
            check=False,
        )
        if (expected == 0 and result.returncode != 0) or (
            expected != 0 and result.returncode == 0
        ):
            # Do not echo environment, auth responses or database contents to public logs.
            diagnostic = result.stderr[-3000:]
            for key in ("JWT_SECRET_KEY", "ADMIN_PASSWORD"):
                diagnostic = diagnostic.replace(environment[key], "[redacted]")
            raise RuntimeError(
                f"Container command failed: {args[0]} (exit {result.returncode}): {diagnostic}"
            )
        if error_contains is not None:
            require(
                error_contains in result.stdout + result.stderr,
                "Failure was not the injected fault",
            )
        return result.stdout.strip()

    def inside(stage, online=False):
        if online:
            return docker(
                "exec", "-i", name, "python", "-", "--probe", stage, input=source
            )
        return docker(
            "run",
            "--rm",
            "-i",
            *common,
            image,
            "python",
            "-",
            "--probe",
            stage,
            input=source,
        )

    def cli(command, *args, expected=0, error_contains=None):
        return docker(
            "run",
            "--rm",
            *common,
            image,
            "python",
            "-m",
            "src.storage.cli",
            command,
            "--database",
            DATABASE,
            *args,
            expected=expected,
            error_contains=error_contains,
        )

    def ready():
        for _ in range(30):
            try:
                inside("health", online=True)
                return
            except RuntimeError:
                time.sleep(1)
        raise RuntimeError("Container never became ready")

    created = False
    try:
        docker("volume", "create", "--label", "dayforge.scope=ci-verification", volume)
        created = True
        docker("run", "--detach", "--name", name, *common, image)
        ready()
        inside("seed", online=True)
        docker("stop", name)
        before = json.loads(inside("snapshot"))
        cli("sqlite-verify")
        cli("sqlite-backup", "--backup-dir", "/app/data/backups", "--kind", "manual")
        backup = inside("backup-path")
        docker("start", name)
        ready()
        inside("mutate", online=True)
        docker("stop", name)
        changed = json.loads(inside("snapshot"))
        require(changed["tables"] != before["tables"], "Mutation did not persist")
        corrupt = inside("corrupt-backup")
        cli(
            "sqlite-restore",
            "--backup-file",
            corrupt,
            expected=1,
            error_contains="backup checksum mismatch",
        )
        require(
            json.loads(inside("snapshot")) == changed,
            "Failed restore changed live database",
        )
        docker(
            "run",
            "--rm",
            *common,
            image,
            "sh",
            "-c",
            "alembic upgrade ci_invalid_revision && exec uvicorn src.main:app --host 0.0.0.0 --port 8000 --workers 1",
            expected=1,
            error_contains="Can't locate revision identified by 'ci_invalid_revision'",
        )
        require(
            json.loads(inside("snapshot")) == changed,
            "Rejected upgrade changed live database",
        )
        cli("sqlite-restore", "--backup-file", backup)
        compare_restored(before, json.loads(inside("snapshot")))
        cli("sqlite-verify")
        docker("start", name)
        ready()
        inside("verify", online=True)
        print(
            "PASS: non-root startup, migration, six-entity HTTP sync, timer replay, backup, rejected corruption/upgrade, restore and epoch rotation"
        )
    finally:
        subprocess.run(
            ["docker", "rm", "-f", name], capture_output=True, timeout=30, check=False
        )
        if created:
            subprocess.run(
                ["docker", "volume", "rm", volume],
                check=True,
                capture_output=True,
                timeout=30,
            )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--image")
    group.add_argument(
        "--probe",
        choices=(
            "health",
            "seed",
            "verify",
            "mutate",
            "snapshot",
            "backup-path",
            "corrupt-backup",
        ),
    )
    args = parser.parse_args()
    if args.probe:
        probe(args.probe)
    else:
        run(args.image)
