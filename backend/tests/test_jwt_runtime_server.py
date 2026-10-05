"""JWT smoke acceptance over real TCP, with an owned server and temporary data."""

from contextlib import contextmanager
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen
from uuid import uuid4

from alembic import command
import jwt

from scripts.sync_acceptance_fixture import AcceptanceClient, check_activity_payload
from src.config import Settings
from tests.test_alembic_migration import alembic_config


@contextmanager
def isolated_server(tmp_path, *, asset_root=None):
    database = tmp_path / "jwt-acceptance.sqlite"
    command.upgrade(alembic_config(str(database)), "head")
    key, password = secrets.token_urlsafe(48), secrets.token_urlsafe(24)
    setting_names = {name.casefold() for name in Settings.model_fields}
    environment = {
        name: value
        for name, value in os.environ.items()
        if name.casefold() not in setting_names
        and not name.upper().startswith("UVICORN_")
        and name.casefold() != "web_concurrency"
    }
    environment.update(
        ENVIRONMENT="production",
        SQLITE_DB_PATH=str(database),
        JWT_SECRET_KEY=key,
        JWT_ALGORITHM="HS256",
        ADMIN_USERNAME="jwt_acceptance_admin",
        ADMIN_PASSWORD=password,
        PYTHONPATH=str(Path(__file__).resolve().parents[1]),
    )
    if asset_root is not None:
        environment["ASSET_ROOT"] = str(asset_root)
    # Pass an already-bound loopback socket, avoiding a free-port race. The
    # child owns only this test's fresh DB; its cwd cannot load the project .env.
    with socket.socket() as listener, (tmp_path / "server.log").open("wb") as log:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        client = AcceptanceClient(f"http://127.0.0.1:{listener.getsockname()[1]}")
        process = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "uvicorn",
                "src.main:app",
                "--fd",
                str(listener.fileno()),
                "--workers",
                "1",
                "--lifespan",
                "on",
                "--no-access-log",
            ],
            cwd=tmp_path,
            env=environment,
            pass_fds=(listener.fileno(),),
            stdout=log,
            stderr=log,
        )
        try:
            deadline = time.monotonic() + 30
            while True:
                if process.poll() is not None:
                    raise AssertionError("isolated JWT server exited before readiness")
                try:
                    # A short timeout bounds startup polling even though the
                    # acceptance client's normal requests allow ten seconds.
                    with urlopen(client.base_url + "/health", timeout=0.2) as response:
                        assert response.status == 200
                    break
                except (URLError, TimeoutError):
                    if time.monotonic() >= deadline:
                        raise AssertionError("isolated JWT server was not ready")
                    time.sleep(0.05)
            yield client, key, password
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
                raise AssertionError("isolated JWT server did not shut down normally")


def test_real_server_login_refresh_sync_replay_isolation_and_bad_claims(
    tmp_path, monkeypatch
):
    # A developer's server flags must not spawn workers/reload supervisors,
    # disable startup or load a deployment env file into this isolated test.
    monkeypatch.setenv("WEB_CONCURRENCY", "2")
    monkeypatch.setenv("UVICORN_RELOAD", "true")
    monkeypatch.setenv("UVICORN_LIFESPAN", "off")
    monkeypatch.setenv("UVICORN_ENV_FILE", str(tmp_path / "must-not-load.env"))
    with isolated_server(tmp_path) as (client, key, password):
        admin = client.login("jwt_acceptance_admin", password)
        refreshed = client.request(
            "POST",
            "/api/v1/auth/refresh",
            payload={"refresh_token": admin["refresh_token"]},
        )
        assert refreshed["user_id"] == admin["user_id"]
        token = refreshed["access_token"]
        me = client.request("GET", "/api/v1/auth/users/me", token=token)
        assert me["public_id"] == admin["user_id"]
        device = client.register_device(token, "jwt-tcp-writer")
        snapshot = client.request(
            "GET", f"/api/v2/sync/bootstrap?device_id={device}", token=token
        )
        assert snapshot["changes"] == []
        entity = str(uuid4())
        operation = {
            "operation_id": str(uuid4()),
            "entity_type": "plan_node",
            "entity_uuid": entity,
            "action": "upsert",
            "base_revision": None,
            "payload": check_activity_payload("JWT acceptance habit"),
        }

        def push(item):
            return client.request(
                "POST",
                "/api/v2/sync/push",
                token=token,
                payload={"device_id": device, "operations": [item]},
            )["results"][0]

        created = push(operation)
        assert created["status"] == "applied" and created["revision"] == 1
        assert push(operation)["status"] == "already_applied"
        updated = push(
            operation
            | {
                "operation_id": str(uuid4()),
                "base_revision": 1,
                "payload": check_activity_payload("JWT acceptance updated"),
            }
        )
        assert updated["status"] == "applied" and updated["revision"] == 2
        deleted = push(
            operation
            | {
                "operation_id": str(uuid4()),
                "base_revision": 2,
                "action": "delete",
                "payload": {},
            }
        )
        assert deleted["status"] == "applied" and deleted["revision"] == 3
        delta = client.request(
            "GET",
            f"/api/v2/sync/changes?device_id={device}&cursor={snapshot['next_cursor']}",
            token=token,
        )
        assert [
            (change["operation"], change["revision"]) for change in delta["changes"]
        ] == [
            ("upsert", 1),
            ("upsert", 2),
            ("delete", 3),
        ]
        assert {change["entity_uuid"] for change in delta["changes"]} == {entity}

        member_password = secrets.token_urlsafe(24)
        client.request(
            "POST",
            "/api/v1/admin/users",
            token=token,
            expected=(201,),
            payload={
                "username": "jwt_acceptance_member",
                "password": member_password,
                "is_admin": False,
            },
        )
        member = client.login("jwt_acceptance_member", member_password)
        member_device = client.register_device(member["access_token"], "jwt-tcp-member")
        isolated = client.request(
            "GET",
            f"/api/v2/sync/changes?device_id={member_device}&cursor=0",
            token=member["access_token"],
        )
        assert isolated["changes"] == []

        for kind in ("access", "refresh"):
            claims = {"sub": str(me["id"]), "ver": 1, "type": kind, "exp": 2524608000}
            missing_exp = claims.copy()
            del missing_exp["exp"]
            for malformed in (
                missing_exp,
                claims | {"exp": None},
                claims | {"exp": float("inf")},
                claims | {"ver": True},
                claims | {"sub": str(2**63)},
            ):
                bad = jwt.encode(malformed, key, algorithm="HS256")
                if kind == "access":
                    body = client.request(
                        "GET",
                        "/api/v1/auth/users/me",
                        token=bad,
                        expected=(401,),
                    )
                    assert body == {"detail": "Could not validate credentials"}
                else:
                    body = client.request(
                        "POST",
                        "/api/v1/auth/refresh",
                        expected=(401,),
                        payload={"refresh_token": bad},
                    )
                    assert body == {"detail": "Invalid refresh token"}
        # Rejection did not invalidate healthy sessions or take the server down.
        assert client.request("GET", "/health") == {"status": "ok"}
        assert client.request("GET", "/api/v1/auth/users/me", token=token) == me
