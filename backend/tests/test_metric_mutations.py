"""Characterize metric mutation boundaries through the sync API (#19)."""

from copy import deepcopy
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlmodel import select

from src.v2.models import EntityRevisionSnapshot, MetricObservation, SyncChange
from tests.test_sync_v2 import metric_operation, push, register_account, register_device


def observation_operation(metric_uuid):
    return {
        "operation_id": str(uuid4()), "entity_uuid": str(uuid4()),
        "entity_type": "metric_observation", "action": "upsert",
        "payload": {
            "metric_uuid": metric_uuid, "value": "60.123456", "unit": "kg",
            "occurred_at": "2026-01-01T07:30:00.123456Z", "local_date": "2025-12-31",
            "timezone": "America/Los_Angeles", "metadata": {"note": "local fact"},
        },
    }


def deletion(operation, revision=1):
    return {
        "operation_id": str(uuid4()), "entity_uuid": operation["entity_uuid"],
        "entity_type": operation["entity_type"], "action": "delete",
        "base_revision": revision, "payload": {},
    }


@pytest.fixture
async def metric_context(test_client, async_session):
    account = await register_account(test_client, async_session, "metric_mutations_owner")
    token = account["access_token"]
    device = await register_device(test_client, token, "metric-mutations-phone")

    async def submit(*operations):
        response = await push(test_client, token, device, list(operations))
        assert response.status_code == 200, response.text
        return response.json()["results"]

    metric = metric_operation()
    observation = observation_operation(metric["entity_uuid"])
    results = await submit(metric, observation)
    assert [item["status"] for item in results] == ["applied", "applied"]
    await async_session.commit()
    return submit, metric, observation, results, token, device


@pytest.mark.parametrize("kind", ["metric", "observation"])
@pytest.mark.parametrize("case", ["missing-delete", "positive-base", "invalid-payload", "stale-delete", "existing-upsert"])
async def test_metric_rejections_are_cached_without_changing_entity_history(async_session, metric_context, kind, case):
    submit, metric, observation, originals, _, _ = metric_context
    original = metric if kind == "metric" else observation
    operation = deepcopy(original)
    operation["operation_id"] = str(uuid4())
    if case == "missing-delete":
        operation = deletion(original)
        operation["entity_uuid"] = str(uuid4())
        code = "ENTITY_NOT_FOUND"
    elif case == "positive-base":
        operation.update(entity_uuid=str(uuid4()), base_revision=1)
        code = "ENTITY_NOT_FOUND" if kind == "metric" else "INVALID_BASE_REVISION"
    elif case == "invalid-payload":
        operation.update(entity_uuid=str(uuid4()), payload={})
        code = "INVALID_PAYLOAD"
    elif case == "stale-delete":
        operation = deletion(original, revision=0)
        code = "REVISION_CONFLICT"
    else:
        code = "REVISION_CONFLICT" if kind == "metric" else "ENTITY_ALREADY_EXISTS"
    result, = await submit(operation)
    assert result["error_code"] == code, result
    is_conflict = case in {"stale-delete", "existing-upsert"}
    assert result["status"] == ("conflict" if is_conflict else "rejected")
    if is_conflict:
        assert result["revision"] == 1
        assert result["entity"] == originals[0 if kind == "metric" else 1]["entity"]
    assert await submit(operation) == [result]
    for model in (EntityRevisionSnapshot, SyncChange):
        rows = (await async_session.execute(select(model))).scalars().all()
        assert len(rows) == 2
        assert {row.entity_uuid: row.revision for row in rows} == {
            metric["entity_uuid"]: 1, observation["entity_uuid"]: 1,
        }


@pytest.mark.parametrize("kind", ["metric", "observation"])
async def test_repeated_delete_does_not_append_history_or_restore_tombstone(async_session, metric_context, kind):
    submit, metric, observation, _, _, _ = metric_context
    original = metric if kind == "metric" else observation
    first = deletion(original)
    removed, = await submit(first)
    assert removed["status"] == "applied" and removed["revision"] == 2
    assert removed["entity"]["deleted_at"].endswith("Z")
    assert await submit(first) == [{**removed, "status": "already_applied"}]
    second = deletion(original, revision=0)
    repeated, = await submit(second)
    assert repeated["status"] == "applied"
    assert repeated["entity"] == removed["entity"]
    assert repeated["revision"] == 2
    restore = deepcopy(original)
    restore.update(operation_id=str(uuid4()), base_revision=2)
    denied, = await submit(restore)
    assert denied["status"] == "conflict"
    assert denied["error_code"] == ("ENTITY_DELETED" if kind == "metric" else "ENTITY_ALREADY_EXISTS")
    assert denied["entity"] == removed["entity"]
    for model in (EntityRevisionSnapshot, SyncChange):
        rows = (await async_session.execute(select(model).where(model.entity_uuid == original["entity_uuid"]))).scalars().all()
        assert sorted(row.revision for row in rows) == [1, 2]


@pytest.mark.parametrize("reference", ["missing", "deleted", "other-owner", "other-device"])
async def test_observation_rejects_unavailable_metric_or_device_impersonation(test_client, async_session, metric_context, reference):
    submit, metric, _, _, _, _ = metric_context
    candidate = observation_operation(metric["entity_uuid"])
    if reference == "missing":
        candidate["payload"]["metric_uuid"] = str(uuid4())
    elif reference == "deleted":
        assert (await submit(deletion(metric)))[0]["status"] == "applied"
    elif reference == "other-owner":
        other = await register_account(test_client, async_session, "other_metric_owner")
        other_device = await register_device(test_client, other["access_token"], "other-metric-phone")
        foreign = metric_operation(name="Private metric")
        created = await push(test_client, other["access_token"], other_device, [foreign])
        assert created.json()["results"][0]["status"] == "applied"
        candidate["payload"]["metric_uuid"] = foreign["entity_uuid"]
    else:
        candidate["payload"]["source_device_id"] = str(uuid4())
    result, = await submit(candidate)
    assert result["status"] == "rejected"
    assert result["error_code"] == ("SOURCE_DEVICE_MISMATCH" if reference == "other-device" else "METRIC_NOT_FOUND")
    assert await submit(candidate) == [result]
    assert (await async_session.execute(select(MetricObservation).where(
        MetricObservation.public_id == candidate["entity_uuid"],
    ))).scalar_one_or_none() is None


async def test_external_observation_dedup_preserves_attribution_and_next_item(async_session, metric_context):
    submit, metric, _, _, _, device = metric_context
    original = observation_operation(metric["entity_uuid"])
    original["payload"].update(source_type="api", external_event_id="scale-reading-1", source_device_id=device)
    created, = await submit(original)
    assert created["status"] == "applied"
    entity = created["entity"]
    assert entity["source_device_id"] == device
    assert entity["external_event_id"] == "scale-reading-1"
    assert entity["metadata"] == original["payload"]["metadata"]
    assert entity["occurred_at"] == original["payload"]["occurred_at"]
    assert entity["local_date"] == "2025-12-31"
    assert entity["value"] == 60.123456
    assert await submit(original) == [{**created, "status": "already_applied"}]
    duplicate = deepcopy(original)
    duplicate.update(operation_id=str(uuid4()), entity_uuid=str(uuid4()))
    next_item = observation_operation(metric["entity_uuid"])
    rejected, applied = await submit(duplicate, next_item)
    assert rejected["error_code"] == "DUPLICATE_EXTERNAL_EVENT"
    assert rejected["status"] == "rejected" and applied["status"] == "applied"
    assert await submit(duplicate, next_item) == [rejected, {**applied, "status": "already_applied"}]
    rows = (await async_session.execute(select(MetricObservation))).scalars().all()
    assert {row.public_id for row in rows} == {original["entity_uuid"], next_item["entity_uuid"], metric_context[2]["entity_uuid"]}


@pytest.mark.parametrize("table", ["entity_revision_snapshots", "sync_changes"])
@pytest.mark.parametrize("mutation", ["metric-update", "metric-delete", "observation-create", "observation-delete"])
async def test_metric_journal_failure_rolls_back_mutation_and_allows_next_operation(async_session, metric_context, table, mutation):
    submit, metric, observation, originals, _, _ = metric_context
    if mutation == "metric-update":
        failing = deepcopy(metric)
        failing.update(operation_id=str(uuid4()), base_revision=1)
        failing["payload"]["name"] = "Must roll back"
    elif mutation == "metric-delete":
        failing = deletion(metric)
    elif mutation == "observation-delete":
        failing = deletion(observation)
    else:
        failing = observation_operation(metric["entity_uuid"])
    await async_session.execute(text(
        f"CREATE TRIGGER reject_metric_journal BEFORE INSERT ON {table} "
        f"WHEN NEW.entity_uuid = '{failing['entity_uuid']}' "
        "BEGIN SELECT RAISE(ABORT, 'injected metric journal failure'); END"
    ))
    await async_session.commit()
    following = observation_operation(metric["entity_uuid"])
    rejected, applied = await submit(failing, following)
    assert rejected["status"] == "rejected" and rejected["error_code"] == "CONSTRAINT_VIOLATION"
    assert applied["status"] == "applied"
    # Read fresh authoritative entities through the normal conflict response.
    for original, expected in zip((metric, observation), originals):
        probe = deepcopy(original)
        probe["operation_id"] = str(uuid4())
        current, = await submit(probe)
        assert current["status"] == "conflict"
        assert current["entity"] == expected["entity"]
    assert await submit(failing, following) == [rejected, {**applied, "status": "already_applied"}]
    for model in (EntityRevisionSnapshot, SyncChange):
        rows = (await async_session.execute(select(model))).scalars().all()
        assert len(rows) == 3
        assert {row.entity_uuid: row.revision for row in rows} == {
            metric["entity_uuid"]: 1, observation["entity_uuid"]: 1, following["entity_uuid"]: 1,
        }
    facts = (await async_session.execute(select(MetricObservation))).scalars().all()
    assert {fact.public_id for fact in facts} == {observation["entity_uuid"], following["entity_uuid"]}
