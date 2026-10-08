"""Counting rules through real authenticated HTTP, migrated SQL and final COMMIT."""

from copy import deepcopy
from datetime import UTC, date, datetime
from uuid import uuid4

import pytest
from pydantic import ValidationError
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker

from src.auth.models import User
from src.auth.service import create_access_token
from src.v2.count_policy import CountDayPolicy
from src.v2.models import ActivityEvent
from src.v2.next_sync_contract import NextSyncBootstrapResponse
from tests.account_fixtures import account_password_hash
from tests.test_http_commit_boundary import database_state, runtime_http as runtime_http
from tests.test_next_structural_sync import node, operation
from tests.test_v5_production_sync import (
    production_v5 as production_v5,
    push,
    registration,
)


def plan(target=10, countdown=False):
    value = node(mode="count", countdown=countdown)
    value["activity"]["target_value"] = target
    return value


def fact(
    activity,
    target=10,
    countdown=False,
    value=1,
    day="2026-10-08",
    *,
    zone="Asia/Shanghai",
    stamp="2026-10-08T01:00:00Z",
):
    return operation(
        dict(
            activity_uuid=activity,
            event_type="count_delta",
            value=value,
            occurred_at=stamp,
            local_date=day,
            timezone=zone,
            count_policy=dict(target_value=target, is_countdown=countdown),
        ),
        identity=str(uuid4()),
        kind="activity_event",
    )


async def owned_count(production, *, target=10, countdown=False):
    client, engine, headers, device = production
    activity = str(uuid4())
    body = plan(target, countdown)
    result = (
        await push(client, headers, device, [operation(body, identity=activity)])
    )[0]
    assert result["status"] == "applied"
    return activity, body


@pytest.mark.parametrize(
    "target,direction",
    [
        (0, False),
        (-1, False),
        (2147483648, False),
        (True, False),
        (1.0, False),
        ("1", False),
        (1, 0),
        (1, "true"),
        (1, None),
    ],
)
def test_policy_requires_exact_positive_integer_and_boolean(target, direction):
    with pytest.raises(ValidationError):
        CountDayPolicy(target_value=target, is_countdown=direction)


@pytest.mark.parametrize("countdown", [False, True])
async def test_edit_up_and_down_undo_all_new_day_and_original_replay(
    production_v5, countdown
):
    client, engine, headers, device = production_v5
    activity, body = await owned_count(production_v5, countdown=countdown)
    first = fact(activity, countdown=countdown, value=6)
    applied = (await push(client, headers, device, [first]))[0]
    original = dict(target_value=10, is_countdown=countdown)
    assert (
        applied["status"] == "applied" and applied["entity"]["count_policy"] == original
    )
    for revision, target in ((1, 15), (2, 5)):
        changed = deepcopy(body)
        changed["activity"]["target_value"] = target
        changed["activity"]["is_countdown"] = not countdown
        assert (
            await push(
                client,
                headers,
                device,
                [operation(changed, identity=activity, revision=revision)],
            )
        )[0]["status"] == "applied"
        result = (
            await push(client, headers, device, [fact(activity, countdown=countdown)])
        )[0]
        assert (
            result["status"] == "applied"
            and result["entity"]["count_policy"] == original
        )
        new_rule = (
            await push(
                client,
                headers,
                device,
                [fact(activity, target=target, countdown=countdown)],
            )
        )[0]
        assert (
            new_rule["status"] == "conflict"
            and new_rule["error_code"] == "COUNT_DAY_POLICY_CONFLICT"
        )
    # Removing every effective fact does not remove the day's original rule.
    async with engine.connect() as connection:
        uuids = (
            (
                await connection.execute(
                    text(
                        "SELECT public_id FROM activity_events WHERE event_type='count_delta'"
                    )
                )
            )
            .scalars()
            .all()
        )
    for identity in uuids:
        undo = operation(
            dict(
                activity_uuid=activity,
                event_type="revert",
                reverts_event_uuid=identity,
                occurred_at="2026-10-08T02:00:00Z",
                local_date="2026-10-08",
                timezone="Asia/Shanghai",
            ),
            identity=str(uuid4()),
            kind="activity_event",
        )
        assert (await push(client, headers, device, [undo]))[0]["status"] == "applied"
    again = (
        await push(client, headers, device, [fact(activity, countdown=countdown)])
    )[0]
    assert again["status"] == "applied" and again["entity"]["count_policy"] == original
    next_day = (
        await push(
            client,
            headers,
            device,
            [
                fact(
                    activity,
                    target=5,
                    countdown=not countdown,
                    day="2026-10-09",
                    stamp="2026-10-09T01:00:00Z",
                )
            ],
        )
    )[0]
    assert (
        next_day["status"] == "applied"
        and next_day["entity"]["count_policy"]["target_value"] == 5
        and next_day["entity"]["count_policy"]["is_countdown"] is not countdown
    )
    # Lost response replay remains original, even after config changes and undo.
    assert (await push(client, headers, device, [first]))[0] == {
        **applied,
        "status": "already_applied",
    }
    response = await client.get(
        "/api/v2/sync/bootstrap", headers=headers, params=dict(device_id=device)
    )
    assert response.status_code == 200, response.text
    snapshot = NextSyncBootstrapResponse.model_validate(response.json())
    assert (
        next(
            item.payload
            for item in snapshot.changes
            if str(item.entity_uuid) == first["entity_uuid"]
        )
        == applied["entity"]
    )
    async with engine.connect() as connection:
        rows = (
            await connection.execute(
                text(
                    "SELECT local_date,target_value,is_countdown FROM activity_count_days ORDER BY local_date"
                )
            )
        ).all()
        assert rows == [
            ("2026-10-08", 10, int(countdown)),
            ("2026-10-09", 5, int(not countdown)),
        ]
        assert (
            await connection.execute(
                text(
                    "SELECT SUM(value) FROM activity_events WHERE event_type='count_delta' AND local_date='2026-10-08'"
                )
            )
        ).scalar_one() == 9


async def test_config_change_before_first_count_can_use_new_rule_but_stale_request_is_not_rebased(
    production_v5,
):
    client, engine, headers, device = production_v5
    activity, body = await owned_count(production_v5)
    stale = fact(activity)
    body["activity"]["target_value"] = 15
    assert (
        await push(
            client, headers, device, [operation(body, identity=activity, revision=1)]
        )
    )[0]["status"] == "applied"
    rejected = (await push(client, headers, device, [stale]))[0]
    assert (
        rejected["status"] == "conflict"
        and rejected["error_code"] == "COUNT_START_CONFIG_CHANGED"
    )
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_count_days"))
        ).scalar_one() == 0
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 0
    assert (await push(client, headers, device, [stale]))[0] == rejected
    assert (await push(client, headers, device, [fact(activity, target=15)]))[0][
        "status"
    ] == "applied"


@pytest.mark.parametrize(
    "shape",
    [
        None,
        {"target_value": 10, "is_countdown": "false"},
        {"target_value": 10.0, "is_countdown": False},
    ],
)
async def test_missing_or_coerced_policy_cannot_bypass_and_rejects_only_this_operation(
    production_v5, shape
):
    client, engine, headers, device = production_v5
    activity, _ = await owned_count(production_v5)
    bad = fact(activity)
    if shape is None:
        bad["payload"].pop("count_policy")
        bad["payload"]["metadata"] = {
            "count_policy": dict(target_value=10, is_countdown=False)
        }
    else:
        bad["payload"]["count_policy"] = shape
    results = await push(client, headers, device, [bad, fact(activity)])
    assert results[0]["status"] == "rejected"
    assert results[0]["error_code"] == (
        "COUNT_DAY_POLICY_REQUIRED" if shape is None else "INVALID_PAYLOAD"
    )
    assert results[1]["status"] == "applied"
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_count_days"))
        ).scalar_one() == 1
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 1


async def test_same_literal_date_two_timezones_two_devices_share_one_rule(
    production_v5,
):
    client, engine, headers, device = production_v5
    activity, _ = await owned_count(production_v5)
    first = fact(activity, stamp="2026-10-07T16:30:00Z")
    assert (await push(client, headers, device, [first]))[0]["status"] == "applied"
    registered = await client.post(
        "/api/v2/devices/register",
        headers=headers,
        json=registration(installation="count-second-device"),
    )
    assert registered.status_code == 200
    second_device = registered.json()["device_id"]
    second = fact(activity, zone="UTC", stamp="2026-10-08T10:00:00Z")
    assert (await push(client, headers, second_device, [second]))[0][
        "status"
    ] == "applied"
    conflict = (
        await push(client, headers, second_device, [fact(activity, target=11)])
    )[0]
    assert conflict["error_code"] == "COUNT_DAY_POLICY_CONFLICT"
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_count_days"))
        ).scalar_one() == 1
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 2


@pytest.mark.parametrize("undone", [False, True])
async def test_legacy_unknown_day_cannot_be_adopted_even_after_undo(
    production_v5, undone
):
    client, engine, headers, device = production_v5
    activity, _ = await owned_count(production_v5)
    identity = str(uuid4())
    async with async_sessionmaker(engine).begin() as session:
        # A retained pre-v5 fact genuinely has no original rule; do not backfill
        # it from today's mutable plan, even when its effective count is zero.
        owner, node_id = (
            await session.execute(
                text(
                    "SELECT owner_user_id,id FROM plan_nodes WHERE public_id=:activity"
                ),
                dict(activity=activity),
            )
        ).one()
        session.add(
            ActivityEvent(
                public_id=identity,
                owner_user_id=owner,
                activity_node_id=node_id,
                event_type="count_delta",
                value=1,
                occurred_at=datetime(2026, 10, 8, 1, tzinfo=UTC),
                local_date=date(2026, 10, 8),
                timezone="Asia/Shanghai",
            )
        )
    if undone:
        undo = operation(
            dict(
                activity_uuid=activity,
                event_type="revert",
                reverts_event_uuid=identity,
                occurred_at="2026-10-08T02:00:00Z",
                local_date="2026-10-08",
                timezone="Asia/Shanghai",
            ),
            identity=str(uuid4()),
            kind="activity_event",
        )
        assert (await push(client, headers, device, [undo]))[0]["status"] == "applied"
    before = await database_state(engine)
    rejected = (await push(client, headers, device, [fact(activity)]))[0]
    assert (
        rejected["status"] == "rejected"
        and rejected["error_code"] == "COUNT_DAY_POLICY_UNKNOWN"
    )
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_count_days"))
        ).scalar_one() == 0
        assert (
            await connection.execute(
                text(
                    "SELECT COUNT(*) FROM activity_events WHERE event_type='count_delta'"
                )
            )
        ).scalar_one() == 1
    # Only the rejection receipt may change, never the original unknown fact.
    after = await database_state(engine)
    assert after["activity_events"] == before["activity_events"]


async def test_missing_known_day_is_invalid_not_unknown_and_cannot_capture_new_rule(
    production_v5,
):
    client, engine, headers, device = production_v5
    activity, _ = await owned_count(production_v5)
    first = fact(activity, value=6)
    accepted = (await push(client, headers, device, [first]))[0]
    assert accepted["status"] == "applied"
    async with engine.begin() as connection:
        await connection.execute(text("DELETE FROM activity_count_days"))
    before = await database_state(engine)
    result = (await push(client, headers, device, [fact(activity)]))[0]
    assert (
        result["status"] == "rejected" and result["error_code"] == "COUNT_DAY_INVALID"
    )
    after = await database_state(engine)
    for table in ("activity_events", "activity_count_days", "sync_changes"):
        assert after[table] == before[table]
    assert (await push(client, headers, device, [first]))[0] == {
        **accepted,
        "status": "already_applied",
    }


@pytest.mark.parametrize("event_type", ["check_in", "revert", "duration"])
async def test_count_policy_cannot_be_attached_to_other_fact_types(
    production_v5, event_type
):
    client, engine, headers, device = production_v5
    activity, _ = await owned_count(production_v5)
    bad = fact(activity)
    bad["payload"]["event_type"] = event_type
    if event_type == "revert":
        bad["payload"].pop("value")
        bad["payload"]["reverts_event_uuid"] = str(uuid4())
    result = (await push(client, headers, device, [bad]))[0]
    assert result["status"] == "rejected" and result["error_code"] == "INVALID_PAYLOAD"
    async with engine.connect() as connection:
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_events"))
        ).scalar_one() == 0
        assert (
            await connection.execute(text("SELECT COUNT(*) FROM activity_count_days"))
        ).scalar_one() == 0


async def test_owned_same_public_uuid_different_rules_do_not_cross_accounts(
    production_v5,
):
    client, engine, headers, device = production_v5
    activity, _ = await owned_count(production_v5)
    assert (await push(client, headers, device, [fact(activity)]))[0][
        "status"
    ] == "applied"
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory.begin() as session:
        other = User(username="count-other", password_hash=account_password_hash())
        session.add(other)
        await session.flush()
        other_id = other.id
        token = create_access_token(dict(sub=str(other.id), ver=other.auth_version))
    other_headers = headers | {"Authorization": "Bearer " + token}
    registered = await client.post(
        "/api/v2/devices/register",
        headers=other_headers,
        json=registration(installation="count-other-account"),
    )
    assert registered.status_code == 200, registered.text
    other_device = registered.json()["device_id"]
    forbidden = (await push(client, other_headers, other_device, [fact(activity)]))[0]
    assert forbidden["error_code"] == "ACTIVITY_NOT_FOUND"
    assert (
        await push(
            client,
            other_headers,
            other_device,
            [operation(plan(20, True), identity=activity)],
        )
    )[0]["status"] == "applied"
    assert (
        await push(client, other_headers, other_device, [fact(activity, 20, True)])
    )[0]["status"] == "applied"
    async with engine.connect() as connection:
        rows = (
            await connection.execute(
                text(
                    "SELECT owner_user_id,target_value FROM activity_count_days ORDER BY target_value"
                )
            )
        ).all()
        assert rows[0][1] == 10 and rows[1] == (other_id, 20) and rows[0][0] != other_id


async def test_original_day_fact_change_log_and_receipt_rollback_on_real_commit_failure(
    production_v5,
):
    client, engine, headers, device = production_v5
    activity, _ = await owned_count(production_v5)
    original = fact(activity)
    async with engine.begin() as connection:
        await connection.execute(
            text(
                "CREATE TABLE commit_fault (bad_user INTEGER REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED)"
            )
        )
        await connection.execute(
            text(
                "CREATE TRIGGER count_commit_failure AFTER INSERT ON activity_count_days BEGIN INSERT INTO commit_fault VALUES (-999999); END"
            )
        )
    before = await database_state(engine)
    response = await client.post(
        "/api/v2/sync/push",
        headers=headers,
        json=dict(device_id=device, operations=[original]),
    )
    assert response.status_code == 500 and "applied" not in response.text
    assert await database_state(engine) == before
    async with engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER count_commit_failure"))
    accepted = (await push(client, headers, device, [original]))[0]
    assert accepted["status"] == "applied"
    assert (await push(client, headers, device, [original]))[0] == {
        **accepted,
        "status": "already_applied",
    }


@pytest.mark.parametrize("damage", ["rule", "delete-day", "proof"])
async def test_bootstrap_does_not_confirm_corrupt_or_missing_day_evidence(
    production_v5, damage
):
    client, engine, headers, device = production_v5
    activity, _ = await owned_count(production_v5)
    assert (await push(client, headers, device, [fact(activity)]))[0][
        "status"
    ] == "applied"
    async with engine.begin() as connection:
        if damage == "rule":
            await connection.execute(
                text("UPDATE activity_count_days SET target_value=9")
            )
        elif damage == "delete-day":
            await connection.execute(text("DELETE FROM activity_count_days"))
        else:
            await connection.execute(
                text("UPDATE activity_events SET count_policy_json='{}'")
            )
    before = await database_state(engine)
    response = await client.get(
        "/api/v2/sync/bootstrap", headers=headers, params=dict(device_id=device)
    )
    assert (
        response.status_code != 200
        and response.json()["detail"]["code"] == "COUNT_DAY_INVALID"
    ), response.text
    assert await database_state(engine) == before


@pytest.mark.parametrize("damage", ["fractional-rule", "wrong-rule", "proof"])
async def test_new_count_cannot_treat_damaged_stored_rule_as_a_valid_conflict(
    production_v5, damage
):
    client, engine, headers, device = production_v5
    activity, _ = await owned_count(production_v5)
    assert (await push(client, headers, device, [fact(activity)]))[0][
        "status"
    ] == "applied"
    async with engine.begin() as connection:
        if damage == "proof":
            await connection.execute(
                text("UPDATE activity_events SET count_policy_json='{}'")
            )
        else:
            await connection.execute(
                text("UPDATE activity_count_days SET target_value=:value"),
                dict(value=1.5 if damage == "fractional-rule" else 9),
            )
    before = await database_state(engine)
    result = (await push(client, headers, device, [fact(activity)]))[0]
    assert (
        result["status"] == "rejected" and result["error_code"] == "COUNT_DAY_INVALID"
    )
    after = await database_state(engine)
    for name in ("activity_events", "activity_count_days", "sync_changes"):
        assert after[name] == before[name]
