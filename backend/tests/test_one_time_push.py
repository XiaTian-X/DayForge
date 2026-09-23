"""New fact semantics in the real sync transaction; v5 is not activated.

The test-only HTTP bridge shares real authentication and get_session. It proves
the commit boundary, not production routing, v5 negotiation or appearance writes.
"""

from uuid import UUID, uuid4

from fastapi import Depends, FastAPI
from httpx import ASGITransport, AsyncClient
import pytest
from sqlalchemy import select, text, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col

from src.auth.dependencies import get_current_user
from src.auth.models import User
from src.auth.router import router as auth_router
from src.database import get_session
from src.main import app
from src.tokens.models import ApiToken
from src.tokens.service import generate_token, hash_token
from src.v2.errors import DomainError
from src.v2.models import (
    ActivityDetail,
    ActivityEvent,
    ClientDevice,
    EntityRevisionSnapshot,
    PlanNode,
    SyncChange,
    SyncOperation,
    utc_now,
)
from src.v2.next_sync_contract import NextSyncPushResponse
from src.v2.one_time_storage import load_one_time_activity
from src.v2.router import _http_error
from src.v2.schemas import SyncPushRequest
from src.v2.service import process_push
from tests.account_fixtures import TEST_ACCOUNT_PASSWORD
from tests.test_http_commit_boundary import database_state
from tests.test_one_time_mutations import operation, setup_database
from tests.test_one_time_storage import ACTIVITY, EVENTS
from tests.test_push_orchestration import hide_initial_operation_lookup


def request(operations, owner=1):
    return SyncPushRequest.model_validate(
        {
            "device_id": f"93000000-0000-4000-8000-{owner:012d}",
            "operations": operations,
        }
    )


async def submit(factory, operations, owner=1):
    async with factory.begin() as session:
        user = await session.get(User, owner)
        assert user is not None
        result = await process_push(
            user, request(operations, owner), session, next_protocol=True
        )
        return result.model_dump(mode="json")["results"]


async def state(factory, owner=1):
    async with factory() as session:
        return (await load_one_time_activity(session, owner, ACTIVITY)).projection.state


async def test_causal_batch_and_lost_response_replay_keep_original_proofs(
    runtime_engine,
):
    factory = await setup_database(runtime_engine)
    operations = [operation(index) for index in range(3)]
    first = await submit(factory, operations)
    assert [result["status"] for result in first] == ["applied"] * 3
    assert [
        result["entity"]["one_time_state_after"]["version"] for result in first
    ] == [1, 2, 3]
    replay = await submit(factory, operations)
    assert replay == [{**result, "status": "already_applied"} for result in first]
    assert (await state(factory)).version == 3
    assert (await state(factory)).completion_event_uuid == EVENTS[2]
    async with factory() as session:
        for model in (ActivityEvent, SyncChange, EntityRevisionSnapshot, SyncOperation):
            assert len((await session.execute(select(model))).scalars().all()) == 3
        node = await session.get(PlanNode, 1)
        assert node is not None and node.revision == 1 and node.deleted_at is None


async def test_rejected_intent_replays_its_original_context_after_state_changes(
    runtime_engine,
):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation()])
    stale = operation()
    stale.operation_id = uuid4()
    stale.entity_uuid = UUID(EVENTS[3])
    stale.payload["one_time"]["event_uuid"] = EVENTS[3]
    rejected = (await submit(factory, [stale]))[0]
    assert rejected["status"] == "conflict"
    assert rejected["error_code"] == "TASK_STATE_CONFLICT"
    assert rejected["one_time_conflict"] == {
        "activity_uuid": ACTIVITY,
        "state": {
            "version": 1,
            "head_event_uuid": EVENTS[0],
            "completion_event_uuid": EVENTS[0],
        },
    }
    for field in ("entity", "revision", "base_entity", "local_entity"):
        assert rejected[field] is None
    await submit(factory, [operation(1), operation(2)])
    assert (await submit(factory, [stale]))[0] == rejected
    assert (await state(factory)).version == 3


@pytest.mark.parametrize(
    "case,code",
    [
        ("completed", "TASK_ALREADY_COMPLETED"),
        ("wrong-completion", "TASK_COMPLETION_MISMATCH"),
        ("wrong-head", "TASK_STATE_CONFLICT"),
        ("exhausted", "TASK_STATE_EXHAUSTED"),
    ],
)
async def test_all_state_rejections_have_a_bound_context_and_no_fact(
    runtime_engine, case, code
):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation()])
    candidate = operation(1)
    intent = candidate.payload["one_time"]
    if case == "completed":
        candidate.payload.update(event_type="check_in", reverts_event_uuid=None)
        intent.update(action="complete", reverts_event_uuid=None)
    elif case == "wrong-completion":
        candidate.payload["reverts_event_uuid"] = EVENTS[3]
        intent["reverts_event_uuid"] = EVENTS[3]
    elif case == "wrong-head":
        intent["expected_head_event_uuid"] = EVENTS[3]
    else:
        # Boundary seed is intentionally not a complete history fixture.
        async with factory.begin() as session:
            await session.execute(
                update(ActivityDetail)
                .where(col(ActivityDetail.node_id) == 1)
                .values(one_time_version=2_147_483_647)
            )
        intent["expected_version"] = 2_147_483_647
    expected = await state(factory)
    result = (await submit(factory, [candidate]))[0]
    assert result["error_code"] == code and result["status"] == "conflict"
    assert result["one_time_conflict"] == {
        "activity_uuid": ACTIVITY,
        "state": expected.model_dump(mode="json"),
    }
    assert result["entity"] is None and result["revision"] is None
    assert await state(factory) == expected
    async with factory() as session:
        assert len((await session.execute(select(ActivityEvent))).scalars().all()) == 1


@pytest.mark.parametrize("race", [False, True])
async def test_replay_precedes_domain_validation_and_rejects_changed_body(
    runtime_engine, monkeypatch, race
):
    factory = await setup_database(runtime_engine)
    original = operation()
    first = (await submit(factory, [original]))[0]
    async with factory.begin() as session:
        owner = await session.get(User, 1)
        assert owner is not None
        if race:
            hidden = hide_initial_operation_lookup(
                monkeypatch, session, str(original.operation_id)
            )
        replay = await process_push(
            owner, request([original]), session, next_protocol=True
        )
        assert replay.results[0].model_dump(mode="json") == {
            **first,
            "status": "already_applied",
        }
        if race:
            assert hidden == [True]
    changed = original.model_copy(deep=True)
    changed.payload["one_time"] = {"invalid": True}
    result = (await submit(factory, [changed]))[0]
    assert result["error_code"] == "OPERATION_ID_REUSED"
    assert result["one_time_conflict"] is None
    assert (await state(factory)).version == 1


async def test_invalid_item_does_not_abort_an_independent_good_item(runtime_engine):
    factory = await setup_database(runtime_engine)
    bad = operation()
    bad.operation_id = uuid4()
    bad.entity_uuid = UUID(EVENTS[3])
    bad.payload.pop("one_time")
    results = await submit(factory, [bad, operation()])
    assert results[0]["error_code"] == "INVALID_PAYLOAD"
    assert results[0]["one_time_conflict"] is None
    assert results[1]["status"] == "applied"
    assert (await state(factory)).version == 1


async def test_accounts_can_share_activity_event_and_operation_ids(runtime_engine):
    factory = await setup_database(runtime_engine)
    one = (await submit(factory, [operation()]))[0]
    two = (await submit(factory, [operation()], owner=2))[0]
    assert one["status"] == two["status"] == "applied"
    await submit(factory, [operation(1)], owner=1)
    assert (await state(factory, 1)).version == 2
    assert (await state(factory, 2)).version == 1
    assert (await submit(factory, [operation()], owner=2))[0] == {
        **two,
        "status": "already_applied",
    }


@pytest.mark.parametrize("kind", ["foreign", "revoked"])
async def test_invalid_device_cannot_replay_or_see_task_state(runtime_engine, kind):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation()])
    if kind == "revoked":
        async with factory.begin() as session:
            await session.execute(
                update(ClientDevice)
                .where(col(ClientDevice.id) == 1)
                .values(revoked_at=utc_now())
            )
    with pytest.raises(DomainError) as error:
        async with factory.begin() as session:
            user = await session.get(User, 2 if kind == "foreign" else 1)
            assert user is not None
            await process_push(
                user, request([operation()]), session, next_protocol=True
            )
    assert error.value.code == "DEVICE_NOT_FOUND" and error.value.entity is None
    assert (await state(factory, 1)).version == 1
    assert (await state(factory, 2)).version == 0


async def test_foreign_activity_and_tombstone_do_not_leak_projection(runtime_engine):
    factory = await setup_database(runtime_engine)
    await submit(factory, [operation()])
    foreign_id = str(uuid4())
    async with factory.begin() as session:
        await session.execute(
            update(PlanNode).where(col(PlanNode.id) == 2).values(public_id=foreign_id)
        )
    denied = (await submit(factory, [operation()], owner=2))[0]
    assert denied["error_code"] == "ACTIVITY_NOT_FOUND"
    assert denied["entity"] is None and denied["one_time_conflict"] is None
    async with factory.begin() as session:
        await session.execute(
            update(PlanNode).where(col(PlanNode.id) == 1).values(deleted_at=utc_now())
        )
    deleted = (await submit(factory, [operation(1)]))[0]
    assert deleted["error_code"] == "ENTITY_DELETED"
    assert deleted["entity"] is None and deleted["one_time_conflict"] is None
    # Already committed facts remain replayable, without reviving their parent.
    assert (await submit(factory, [operation()]))[0]["status"] == "already_applied"


async def test_facts_device_completes_and_undoes_but_cannot_delete(runtime_engine):
    factory = await setup_database(runtime_engine)
    deletion = {
        "operation_id": str(uuid4()),
        "entity_type": "plan_node",
        "entity_uuid": ACTIVITY,
        "action": "delete",
        "base_revision": 1,
    }
    results = await submit(factory, [operation(), deletion, operation(1)])
    assert [result["status"] for result in results] == [
        "applied",
        "rejected",
        "applied",
    ]
    assert results[1]["error_code"] == "DEVICE_CAPABILITY_DENIED"
    assert (await state(factory)).version == 2


@pytest.fixture(params=["jwt", "api-token"])
async def one_time_http(runtime_engine, request):
    factory = await setup_database(runtime_engine)
    bridge = FastAPI()
    bridge.exception_handlers.update(app.exception_handlers)
    bridge.include_router(auth_router, prefix="/api/v1")

    @bridge.post("/__test/one-time-push", response_model=NextSyncPushResponse)
    async def push(
        body: SyncPushRequest,
        user: User = Depends(get_current_user),
        session: AsyncSession = Depends(get_session, scope="function"),
    ):
        try:
            return await process_push(user, body, session, next_protocol=True)
        except DomainError as error:
            raise _http_error(error) from error

    async with AsyncClient(
        transport=ASGITransport(app=bridge, raise_app_exceptions=False),
        base_url="http://test",
    ) as client:
        login = await client.post(
            "/api/v1/auth/login",
            json={"username": "one-time-1", "password": TEST_ACCOUNT_PASSWORD},
        )
        assert login.status_code == 200
        headers = {"Authorization": "Bearer " + login.json()["access_token"]}
        if request.param == "api-token":
            key = generate_token()
            async with factory.begin() as session:
                session.add(
                    ApiToken(
                        user_id=1,
                        name="one-time-commit-test",
                        token_hash=hash_token(key),
                        prefix=key[:11],
                    )
                )
            headers = {"Authorization": "Token " + key}
        yield client, factory, headers


@pytest.mark.parametrize("failure", ["commit", "result", "cursor"])
async def test_http_outer_failure_rolls_back_all_then_original_identity_retries(
    runtime_engine, one_time_http, failure
):
    client, factory, headers = one_time_http
    async with runtime_engine.begin() as connection:
        if failure == "commit":
            await connection.execute(
                text(
                    "CREATE TABLE commit_fault (bad_user INTEGER REFERENCES users(id) "
                    "DEFERRABLE INITIALLY DEFERRED)"
                )
            )
            trigger = (
                "AFTER INSERT ON activity_events BEGIN "
                "INSERT INTO commit_fault VALUES (-999999); END"
            )
        else:
            target = (
                "UPDATE ON sync_operations"
                if failure == "result"
                else "INSERT ON sync_cursors"
            )
            trigger = f"BEFORE {target} BEGIN SELECT RAISE(ABORT, 'injected outer failure'); END"
        await connection.execute(text(f"CREATE TRIGGER inject_outer_failure {trigger}"))
    before = await database_state(runtime_engine)
    body = request([operation(), operation(1)]).model_dump(mode="json")
    failed = await client.post("/__test/one-time-push", headers=headers, json=body)
    assert failed.status_code == 500
    assert "applied" not in failed.text and "FOREIGN KEY" not in failed.text
    assert await database_state(runtime_engine) == before
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER inject_outer_failure"))
    first = await client.post("/__test/one-time-push", headers=headers, json=body)
    assert first.status_code == 200, first.text
    assert [r["status"] for r in first.json()["results"]] == ["applied", "applied"]
    assert (await state(factory)).version == 2  # Independent post-response connection.
    replay = await client.post("/__test/one-time-push", headers=headers, json=body)
    assert replay.status_code == 200
    assert replay.json()["results"] == [
        {**r, "status": "already_applied"} for r in first.json()["results"]
    ]


@pytest.mark.parametrize(
    "table", ["activity_events", "sync_changes", "entity_revision_snapshots"]
)
async def test_inner_write_failure_persists_only_rejection_and_not_half_a_fact(
    runtime_engine,
    table,
):
    factory = await setup_database(runtime_engine)
    async with runtime_engine.begin() as connection:
        await connection.execute(
            text(
                f"CREATE TRIGGER fail_event BEFORE INSERT ON {table} "
                "BEGIN SELECT RAISE(ABORT, 'injected event failure'); END"
            )
        )
    first = (await submit(factory, [operation()]))[0]
    assert first["error_code"] == "CONSTRAINT_VIOLATION"
    assert first["status"] == "rejected" and first["one_time_conflict"] is None
    async with factory() as session:
        for model in (ActivityEvent, SyncChange, EntityRevisionSnapshot):
            assert (await session.execute(select(model))).scalars().all() == []
        detail = await session.get(ActivityDetail, 1)
        assert detail is not None and detail.one_time_version == 0
        stored = (await session.execute(select(SyncOperation))).scalar_one()
        assert stored.status == "rejected"
    async with runtime_engine.begin() as connection:
        await connection.execute(text("DROP TRIGGER fail_event"))
    assert (await submit(factory, [operation()]))[0] == first
    corrected = operation()
    corrected.operation_id = uuid4()  # Explicit new intent, never rewrite a frozen ID.
    assert (await submit(factory, [corrected]))[0]["status"] == "applied"
    assert (await state(factory)).version == 1
