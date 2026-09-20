"""Characterize immutable activity facts and metric links before extraction (#19)."""

from copy import deepcopy
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlmodel import select

from src.v2.models import (
    ActivityEvent,
    ActivityMetricLinkV2,
    EntityRevisionSnapshot,
    SyncChange,
)
from tests.test_metric_mutations import deletion
from tests.test_sync_v2 import (
    activity_operation,
    metric_operation,
    push,
    register_account,
    register_device,
)


def event_operation(activity_uuid, **payload):
    return {
        "operation_id": str(uuid4()),
        "entity_uuid": str(uuid4()),
        "entity_type": "activity_event",
        "action": "upsert",
        "payload": {
            "activity_uuid": activity_uuid,
            "event_type": "check_in",
            "occurred_at": "2026-01-01T07:30:00.123456Z",
            "local_date": "2025-12-31",
            "timezone": "America/Los_Angeles",
            "metadata": {"note": "offline fact"},
            **payload,
        },
    }


def link_operation(activity_uuid, metric_uuid):
    return {
        "operation_id": str(uuid4()),
        "entity_uuid": str(uuid4()),
        "entity_type": "activity_metric_link",
        "action": "upsert",
        "payload": {"activity_uuid": activity_uuid, "metric_uuid": metric_uuid},
    }


@pytest.fixture
async def fact_context(test_client, async_session):
    account = await register_account(test_client, async_session, "fact_link_owner")
    token = account["access_token"]
    device = await register_device(test_client, token, "fact-link-phone")

    async def submit(*operations):
        response = await push(test_client, token, device, list(operations))
        assert response.status_code == 200, response.text
        return response.json()["results"]

    activity, metric = activity_operation(), metric_operation()
    event = event_operation(activity["entity_uuid"])
    link = link_operation(activity["entity_uuid"], metric["entity_uuid"])
    operations = [activity, metric, event, link]
    originals = await submit(*operations)
    assert [item["status"] for item in originals] == ["applied"] * 4
    await async_session.commit()
    return submit, operations, originals, token, device


@pytest.mark.parametrize(
    "kind,case,code",
    [
        ("event", "delete", "USE_REVERT_EVENT"),
        ("event", "existing", "ENTITY_ALREADY_EXISTS"),
        ("event", "positive-base", "INVALID_BASE_REVISION"),
        ("event", "invalid-payload", "INVALID_PAYLOAD"),
        ("event", "missing-activity", "ACTIVITY_NOT_FOUND"),
        ("event", "source-device", "SOURCE_DEVICE_MISMATCH"),
        ("event", "revert-target", "REVERT_TARGET_NOT_FOUND"),
        ("link", "delete", "ENTITY_NOT_FOUND"),
        ("link", "existing", "REVISION_CONFLICT"),
        ("link", "positive-base", "ENTITY_NOT_FOUND"),
        ("link", "invalid-payload", "INVALID_PAYLOAD"),
        ("link", "missing-activity", "ACTIVITY_NOT_FOUND"),
        ("link", "missing-metric", "METRIC_NOT_FOUND"),
    ],
)
async def test_fact_link_rejection_is_cached_without_appending_history(
    async_session, fact_context, kind, case, code
):
    submit, operations, originals, _, _ = fact_context
    index = 2 if kind == "event" else 3
    candidate = deepcopy(operations[index])
    candidate.update(operation_id=str(uuid4()), entity_uuid=str(uuid4()))
    if case == "delete":
        candidate = deletion(candidate if kind == "link" else operations[index])
    elif case == "existing":
        candidate["entity_uuid"] = operations[index]["entity_uuid"]
    elif case == "positive-base":
        candidate["base_revision"] = 1
        if kind == "link":
            # Avoid the existing endpoint pair so this exercises base validation.
            assert (await submit(deletion(operations[index])))[0]["status"] == "applied"
    elif case == "invalid-payload":
        candidate["payload"] = {}
    elif case in {"missing-activity", "missing-metric"}:
        candidate["payload"][
            "activity_uuid" if case == "missing-activity" else "metric_uuid"
        ] = str(uuid4())
    elif case == "source-device":
        candidate["payload"]["source_device_id"] = str(uuid4())
    else:
        candidate["payload"].update(
            event_type="revert", reverts_event_uuid=str(uuid4())
        )
    before = {}
    for model in (EntityRevisionSnapshot, SyncChange):
        before[model] = [
            (row.entity_uuid, row.revision, row.payload_json)
            for row in (
                await async_session.execute(
                    select(model).order_by(model.entity_uuid, model.revision)
                )
            ).scalars()
        ]
    (rejected,) = await submit(candidate)
    assert rejected["error_code"] == code, rejected
    assert rejected["status"] == ("conflict" if case == "existing" else "rejected")
    if case == "existing":
        assert rejected["revision"] == 1
        assert rejected["entity"] == originals[index]["entity"]
    assert await submit(candidate) == [rejected]
    for model, expected in before.items():
        rows = (
            await async_session.execute(
                select(model).order_by(model.entity_uuid, model.revision)
            )
        ).scalars()
        assert [
            (row.entity_uuid, row.revision, row.payload_json) for row in rows
        ] == expected


@pytest.mark.parametrize("event_type", ["count_delta", "count_snapshot"])
async def test_count_integer_boundaries_and_fact_precision(fact_context, event_type):
    submit, _, _, _, device = fact_context
    count = activity_operation(tracking_mode="count")
    count["payload"]["title"] = "Count boundaries"
    assert (await submit(count))[0]["status"] == "applied"
    values = ["-2147483648", "2147483647", "0", "1.5", "-2147483649", "2147483648"]
    operations = [
        event_operation(count["entity_uuid"], event_type=event_type, value=value)
        for value in values
    ]
    results = await submit(*operations)
    for index, result in enumerate(results):
        if index >= 3:
            assert (
                result["status"] == "rejected"
                and result["error_code"] == "INVALID_COUNT_VALUE"
            )
            continue
        assert result["status"] == "applied"
        entity = result["entity"]
        assert entity["value"] == int(values[index])
        assert entity["occurred_at"] == "2026-01-01T07:30:00.123456Z"
        assert (
            entity["local_date"] == "2025-12-31"
            and entity["timezone"] == "America/Los_Angeles"
        )
        assert entity["source_device_id"] == device and entity["metadata"] == {
            "note": "offline fact"
        }
    assert await submit(*operations) == [
        {**result, "status": "already_applied"} if index < 3 else result
        for index, result in enumerate(results)
    ]


async def test_link_lifecycle_keeps_flags_revision_tombstone_and_endpoint_identity(
    async_session, fact_context
):
    submit, operations, originals, _, _ = fact_context
    activity, metric, _, link = operations
    assert originals[3]["entity"]["coefficient"] == 1
    update = deepcopy(link)
    update.update(operation_id=str(uuid4()), base_revision=1)
    update["payload"].update(
        coefficient="-0.123456",
        show_in_activity_detail=False,
        prompt_on_complete=True,
        is_active=False,
    )
    (changed,) = await submit(update)
    assert changed["status"] == "applied" and changed["revision"] == 2
    assert changed["entity"]["coefficient"] == -0.123456
    assert all(
        changed["entity"][key] == value
        for key, value in update["payload"].items()
        if key != "coefficient"
    )
    assert await submit(update) == [{**changed, "status": "already_applied"}]
    other_activity, other_metric = (
        activity_operation(),
        metric_operation(name="Other metric"),
    )
    other_activity["payload"]["title"] = "Other activity"
    assert [r["status"] for r in await submit(other_activity, other_metric)] == [
        "applied",
        "applied",
    ]
    for field, endpoint in (
        ("activity_uuid", other_activity),
        ("metric_uuid", other_metric),
    ):
        invalid = deepcopy(update)
        invalid.update(operation_id=str(uuid4()), base_revision=2)
        invalid["payload"][field] = endpoint["entity_uuid"]
        (denied,) = await submit(invalid)
        assert denied["error_code"] == "IMMUTABLE_LINK_ENDPOINTS"
    (stale,) = await submit(deletion(link, revision=1))
    assert (
        stale["error_code"] == "REVISION_CONFLICT"
        and stale["entity"] == changed["entity"]
    )
    (removed,) = await submit(deletion(link, revision=2))
    assert removed["status"] == "applied" and removed["revision"] == 3
    (repeated,) = await submit(deletion(link, revision=0))
    assert repeated["entity"] == removed["entity"] and repeated["revision"] == 3
    restore = deepcopy(link)
    restore.update(operation_id=str(uuid4()), base_revision=3)
    (denied,) = await submit(restore)
    assert (
        denied["error_code"] == "ENTITY_DELETED"
        and denied["entity"] == removed["entity"]
    )
    replacement = link_operation(activity["entity_uuid"], metric["entity_uuid"])
    assert (await submit(replacement))[0]["status"] == "applied"
    duplicate = link_operation(activity["entity_uuid"], metric["entity_uuid"])
    assert (await submit(duplicate))[0]["error_code"] == "LINK_ALREADY_EXISTS"
    for model in (EntityRevisionSnapshot, SyncChange):
        rows = (
            await async_session.execute(
                select(model).where(model.entity_uuid == link["entity_uuid"])
            )
        ).scalars()
        assert sorted(row.revision for row in rows) == [1, 2, 3]


async def test_fact_and_link_identical_uuids_remain_account_scoped(
    test_client, async_session, fact_context
):
    submit, operations, originals, _, _ = fact_context
    other = await register_account(test_client, async_session, "other_fact_link_owner")
    device = await register_device(
        test_client, other["access_token"], "other-fact-link-phone"
    )
    response = await push(test_client, other["access_token"], device, operations)
    assert response.status_code == 200
    assert [result["status"] for result in response.json()["results"]] == [
        "applied"
    ] * 4
    for index in (2, 3):
        probe = deepcopy(operations[index])
        probe["operation_id"] = str(uuid4())
        (conflict,) = await submit(probe)
        assert conflict["entity"] == originals[index]["entity"]
    for model in (ActivityEvent, ActivityMetricLinkV2):
        rows = (await async_session.execute(select(model))).scalars().all()
        assert len(rows) == 2 and len({row.owner_user_id for row in rows}) == 2


@pytest.mark.parametrize("table", ["sync_changes", "entity_revision_snapshots"])
@pytest.mark.parametrize(
    "mutation", ["event-create", "link-create", "link-update", "link-delete"]
)
async def test_fact_link_journal_failure_rolls_back_and_preserves_batch_progress(
    async_session, fact_context, table, mutation
):
    submit, operations, originals, _, _ = fact_context
    activity, metric, _, link = operations
    if mutation == "event-create":
        failing = event_operation(activity["entity_uuid"])
    elif mutation == "link-create":
        other = metric_operation(name="Unlinked metric")
        assert (await submit(other))[0]["status"] == "applied"
        failing = link_operation(activity["entity_uuid"], other["entity_uuid"])
    elif mutation == "link-update":
        failing = deepcopy(link)
        failing.update(operation_id=str(uuid4()), base_revision=1)
        failing["payload"].update(coefficient="99", prompt_on_complete=True)
    else:
        failing = deletion(link)
    await async_session.execute(
        text(
            f"CREATE TRIGGER reject_fact_link_journal BEFORE INSERT ON {table} "
            f"WHEN NEW.entity_uuid = '{failing['entity_uuid']}' "
            "BEGIN SELECT RAISE(ABORT, 'injected fact/link journal failure'); END"
        )
    )
    await async_session.commit()
    following = event_operation(activity["entity_uuid"])
    rejected, applied = await submit(failing, following)
    assert (
        rejected["status"] == "rejected"
        and rejected["error_code"] == "CONSTRAINT_VIOLATION"
    )
    assert applied["status"] == "applied"
    assert await submit(failing, following) == [
        rejected,
        {**applied, "status": "already_applied"},
    ]
    for index in (2, 3):
        probe = deepcopy(operations[index])
        probe["operation_id"] = str(uuid4())
        (current,) = await submit(probe)
        assert current["entity"] == originals[index]["entity"]
    for model in (EntityRevisionSnapshot, SyncChange):
        rows = (
            (
                await async_session.execute(
                    select(model).where(model.entity_uuid == failing["entity_uuid"])
                )
            )
            .scalars()
            .all()
        )
        assert [row.revision for row in rows] == (
            [] if mutation.endswith("create") else [1]
        )
    if mutation.endswith("create"):
        model = ActivityEvent if mutation == "event-create" else ActivityMetricLinkV2
        rows = (
            (
                await async_session.execute(
                    select(model).where(model.public_id == failing["entity_uuid"])
                )
            )
            .scalars()
            .all()
        )
        assert rows == []
