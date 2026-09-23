"""Real storage ownership and recovery; not yet structural sync/UI integration."""

from contextlib import closing
from pathlib import Path
import json
import sqlite3

from alembic import command
import pytest
from sqlalchemy import Engine, event, text
from sqlalchemy.exc import IntegrityError

from src.storage.logical_archive import export_archive, import_archive
from src.storage.sqlite_maintenance import (
    StorageValidationError,
    create_backup,
    inspect_database,
    restore_backup,
)
from src.v2.appearance import ObjectAppearance
from src.v2.errors import DomainError
from src.v2.event_mutations import mutate_activity_event
from src.v2.models import (
    ActivityDetail,
    ClientDevice,
    GoalDetail,
    PlanNode,
    TrackedMetric,
)
from src.v2.object_appearance import set_metric_appearance, set_node_appearance
from tests.test_alembic_migration import alembic_config
from tests.test_asset_declarations import ASSET, asset, setup, write_asset
from tests.test_http_commit_boundary import database_state
from tests.test_logical_archive import migrate, seed_source
from tests.test_logical_archive_identity import (
    database_dump,
    read_bundle,
    replace_records,
    write_bundle,
)
from tests.test_one_time_mutations import operation
from tests.test_one_time_storage import ACTIVITY


TASK_ASSET = "a1000000-0000-4000-8000-000000000002"


def appearance(*, role="habit.water", asset_id=None):
    return ObjectAppearance.model_validate(
        dict(
            icon=dict(kind="asset", asset_id=asset_id)
            if asset_id
            else dict(kind="role", role=role),
            accent_color="#Aa3344CC",
            icon_tint="object",
        )
    )


async def populated(engine):
    factory, contexts = await setup(engine)
    async with factory.begin() as session:
        for owner in (1, 2):
            for index, kind in enumerate(("goal", "activity", "activity")):
                session.add(
                    PlanNode(
                        id=owner * 10 + index,
                        owner_user_id=owner,
                        created_by_user_id=owner,
                        public_id=ACTIVITY
                        if index == 2
                        else f"d1000000-0000-4000-8000-{owner * 10 + index:012d}",
                        title=f"node-{owner}-{index}",
                        node_kind=kind,
                    )
                )
            session.add(
                TrackedMetric(
                    id=owner,
                    owner_user_id=owner,
                    created_by_user_id=owner,
                    name=f"metric-{owner}",
                    unit="kg",
                )
            )
        await session.flush()
        for owner in (1, 2):
            session.add(
                GoalDetail(
                    node_id=owner * 10,
                    evaluation_policy_json='{"schema_version":1,"type":"manual"}',
                )
            )
            session.add(
                ActivityDetail(
                    node_id=owner * 10 + 1,
                    tracking_mode="check",
                    completion_policy="recurring",
                )
            )
            session.add(
                ActivityDetail(
                    node_id=owner * 10 + 2,
                    tracking_mode="check",
                    completion_policy="one_and_done",
                    recurrence_rule_json='{"schema_version":1,"type":"once","due_date":null}',
                    failure_policy_json='{"schema_version":1,"type":"loose"}',
                    one_time_version=0,
                )
            )
    for owner in (1, 2):
        await write_asset(factory, asset(contexts[owner]), owner)
        await write_asset(
            factory,
            asset(contexts[owner], asset_id=TASK_ASSET, purpose="task", name="事项"),
            owner,
        )
    return factory, contexts


async def test_roles_and_account_assets_roundtrip_preserve_fact_projection(
    runtime_engine, tmp_path
):
    factory, _ = await populated(runtime_engine)
    async with factory.begin() as session:
        for owner in (1, 2):
            await set_node_appearance(
                session, owner, owner * 10, appearance(role="goal.focus")
            )
            await set_node_appearance(
                session, owner, owner * 10 + 1, appearance(asset_id=ASSET)
            )
            await set_node_appearance(
                session, owner, owner * 10 + 2, appearance(asset_id=TASK_ASSET)
            )
            await set_metric_appearance(
                session, owner, owner, appearance(role="metric.weight")
            )
        device = await session.get(ClientDevice, 1)
        assert device is not None
        await mutate_activity_event(
            session, 1, device, operation(), one_time_contract=True
        )
    async with factory.begin() as session:
        await set_node_appearance(session, 1, 12, appearance(role="task.custom"))
        detail = await session.get(ActivityDetail, 12)
        node = await session.get(PlanNode, 12)
        assert (
            detail is not None
            and detail.one_time_version == 1
            and detail.one_time_completion_event_uuid is not None
        )
        assert (
            node is not None and node.revision == 1
        )  # Primitive does not own structural revision.
    source = Path(runtime_engine.url.database)
    assert inspect_database(source).valid
    url = str(runtime_engine.url.set(drivername="sqlite"))
    bundle = export_archive(url, tmp_path / "source.zip")
    target = tmp_path / "target.sqlite"
    target_url = migrate(target)
    import_archive(target_url, bundle)
    assert inspect_database(target).valid
    assert (
        read_bundle(export_archive(target_url, tmp_path / "again.zip"))["collections"]
        == read_bundle(bundle)["collections"]
    )
    with closing(sqlite3.connect(target)) as connection:
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
        assert connection.execute(
            "SELECT COUNT(*) FROM plan_node_appearances"
        ).fetchone() == (6,)
        assert connection.execute(
            "SELECT COUNT(*) FROM metric_appearances"
        ).fetchone() == (2,)
    backup, _ = create_backup(source, tmp_path / "backups", apply_retention=False)
    physical = tmp_path / "physical.sqlite"
    restore_backup(backup, physical)
    assert inspect_database(physical).valid
    assert (
        read_bundle(export_archive(f"sqlite:///{physical}", tmp_path / "physical.zip"))[
            "collections"
        ]
        == read_bundle(bundle)["collections"]
    )


@pytest.mark.parametrize("kind", ["node", "metric"])
async def test_deleted_objects_do_not_receive_new_appearance(runtime_engine, kind):
    factory, _ = await populated(runtime_engine)
    async with factory.begin() as session:
        await session.execute(
            text(
                "UPDATE plan_nodes SET deleted_at=CURRENT_TIMESTAMP WHERE id=11"
                if kind == "node"
                else "UPDATE tracked_metrics SET deleted_at=CURRENT_TIMESTAMP WHERE id=1"
            )
        )
    before = await database_state(runtime_engine)
    with pytest.raises(DomainError) as error:
        async with factory.begin() as session:
            if kind == "node":
                await set_node_appearance(session, 1, 11, appearance())
            else:
                await set_metric_appearance(session, 1, 1, appearance())
    assert error.value.code == "ENTITY_DELETED"
    assert await database_state(runtime_engine) == before


async def test_asset_existing_only_in_another_account_is_not_resolved(runtime_engine):
    factory, contexts = await populated(runtime_engine)
    foreign_id = "a1000000-0000-4000-8000-000000000099"
    await write_asset(factory, asset(contexts[2], asset_id=foreign_id), owner=2)
    before = await database_state(runtime_engine)
    with pytest.raises(DomainError) as error:
        async with factory.begin() as session:
            await set_node_appearance(session, 1, 11, appearance(asset_id=foreign_id))
    assert error.value.code == "ASSET_NOT_FOUND"
    assert await database_state(runtime_engine) == before


@pytest.mark.parametrize("case", ["purpose", "color", "foreign-asset"])
async def test_modified_archive_rolls_back_identity_and_all_collections(
    runtime_engine, tmp_path, case
):
    factory, _ = await populated(runtime_engine)
    async with factory.begin() as session:
        await set_node_appearance(session, 1, 11, appearance())
        await set_metric_appearance(session, 1, 1, appearance(asset_id=ASSET))
    valid = export_archive(
        str(runtime_engine.url.set(drivername="sqlite")), tmp_path / "valid.zip"
    )
    bundle = read_bundle(valid)
    collection = (
        "metric_appearances" if case == "foreign-asset" else "plan_node_appearances"
    )
    records = [
        json.loads(line) for line in bundle["collections"][collection].splitlines()
    ]
    row = records[0]["data"]
    if case == "purpose":
        row["icon_role"] = "task.default"
    elif case == "color":
        row["accent_color"] = "#zzzzzz"
    else:
        assets = [
            json.loads(line)
            for line in bundle["collections"]["account_icon_assets"].splitlines()
        ]
        foreign = next(
            item
            for item in assets
            if item["data"]["owner_user_id"]["key"]
            != "user:" + row["owner_user_id"]["key"]
        )
        row["icon_asset_id"] = {"$ref": "account_icon_assets", "key": foreign["key"]}
    replace_records(bundle, collection, records)
    bad = write_bundle(tmp_path / "bad.zip", bundle)
    target = tmp_path / "target.sqlite"
    url = migrate(target)
    before = database_dump(target)
    with pytest.raises((StorageValidationError, IntegrityError)):
        import_archive(url, bad)
    assert database_dump(target) == before
    import_archive(url, valid)
    assert inspect_database(target).valid


@pytest.mark.parametrize(
    "kind,reference,code",
    [
        ("goal", "task-role", "ICON_PURPOSE_MISMATCH"),
        ("habit", "task-asset", "ICON_PURPOSE_MISMATCH"),
        ("task", "general-role", "ICON_PURPOSE_MISMATCH"),
        ("task", "general-asset", "ICON_PURPOSE_MISMATCH"),
        ("metric", "task-role", "ICON_PURPOSE_MISMATCH"),
        ("metric", "task-asset", "ICON_PURPOSE_MISMATCH"),
        ("habit", "missing-asset", "ASSET_NOT_FOUND"),
        ("foreign-node", "general-role", "ENTITY_NOT_FOUND"),
        ("foreign-metric", "general-role", "ENTITY_NOT_FOUND"),
    ],
)
async def test_purpose_and_object_owner_come_from_stored_objects(
    runtime_engine, kind, reference, code
):
    factory, _ = await populated(runtime_engine)
    values = {
        "task-role": appearance(role="task.default"),
        "task-asset": appearance(asset_id=TASK_ASSET),
        "general-role": appearance(),
        "general-asset": appearance(asset_id=ASSET),
        "missing-asset": appearance(asset_id="a1000000-0000-4000-8000-000000000099"),
    }
    before = await database_state(runtime_engine)
    with pytest.raises(DomainError) as error:
        async with factory.begin() as session:
            if kind in ("metric", "foreign-metric"):
                await set_metric_appearance(
                    session, 1, 2 if kind == "foreign-metric" else 1, values[reference]
                )
            else:
                await set_node_appearance(
                    session,
                    1,
                    {"goal": 10, "habit": 11, "task": 12, "foreign-node": 21}[kind],
                    values[reference],
                )
    assert error.value.code == code
    assert await database_state(runtime_engine) == before


@pytest.mark.parametrize(
    "statement",
    [
        "UPDATE plan_node_appearances SET node_id=21 WHERE node_id=11",
        "UPDATE metric_appearances SET metric_id=2 WHERE metric_id=1",
        "UPDATE plan_node_appearances SET icon_asset_id=(SELECT id FROM account_icon_assets WHERE owner_user_id=2 LIMIT 1) WHERE node_id=11",
        "UPDATE metric_appearances SET icon_asset_id=(SELECT id FROM account_icon_assets WHERE owner_user_id=2 LIMIT 1) WHERE metric_id=1",
        "UPDATE plan_node_appearances SET icon_role='habit.water' WHERE node_id=11",
        "UPDATE metric_appearances SET icon_kind='unknown' WHERE metric_id=1",
    ],
)
async def test_database_itself_rejects_cross_owner_parent_asset_and_invalid_shape(
    runtime_engine, statement
):
    factory, _ = await populated(runtime_engine)
    async with factory.begin() as session:
        await set_node_appearance(session, 1, 11, appearance(asset_id=ASSET))
        await set_metric_appearance(session, 1, 1, appearance(asset_id=ASSET))
    before = await database_state(runtime_engine)
    with pytest.raises(IntegrityError):
        async with runtime_engine.begin() as connection:
            await connection.execute(text(statement))
    assert await database_state(runtime_engine) == before


@pytest.mark.parametrize(
    "statement",
    [
        "UPDATE plan_node_appearances SET icon_role='task.default' WHERE node_id=11",
        "UPDATE plan_node_appearances SET accent_color='#zzzzzz' WHERE node_id=11",
        "UPDATE metric_appearances SET icon_role='task.default' WHERE metric_id=1",
    ],
)
async def test_semantically_invalid_roles_or_colors_cannot_be_backed_up(
    runtime_engine, tmp_path, statement
):
    factory, _ = await populated(runtime_engine)
    async with factory.begin() as session:
        await set_node_appearance(session, 1, 11, appearance())
        await set_metric_appearance(session, 1, 1, appearance(role="metric.weight"))
        await session.execute(text(statement))
    source = Path(runtime_engine.url.database)
    assert not inspect_database(source).valid
    with pytest.raises(StorageValidationError, match="object appearance"):
        export_archive(
            str(runtime_engine.url.set(drivername="sqlite")), tmp_path / "bad.zip"
        )
    with pytest.raises(StorageValidationError, match="object appearance"):
        create_backup(source, tmp_path / "backups", apply_retention=False)
    assert list((tmp_path / "backups").iterdir()) == []


async def test_outer_failure_rolls_back_appearance_and_downgrade_refuses_data(
    runtime_engine,
):
    factory, _ = await populated(runtime_engine)
    before = await database_state(runtime_engine)
    with pytest.raises(RuntimeError, match="later failure"):
        async with factory.begin() as session:
            await set_node_appearance(session, 1, 11, appearance())
            await set_metric_appearance(session, 1, 1, appearance(role="metric.weight"))
            raise RuntimeError("later failure")
    assert await database_state(runtime_engine) == before
    async with factory.begin() as session:
        await set_node_appearance(session, 1, 11, appearance())
    path = Path(runtime_engine.url.database)
    before_dump = database_dump(path)
    with pytest.raises(RuntimeError, match="object appearance data exists"):
        command.downgrade(alembic_config(str(path)), "000000000003")
    assert database_dump(path) == before_dump


def test_new_migration_failure_rolls_back_tables_indexes_and_all_previous_rows(
    tmp_path,
):
    path = tmp_path / "migration.sqlite"
    url = migrate(path, revision="000000000003")
    seed_source(url)
    before = database_dump(path)

    def fail(connection, cursor, statement, parameters, context, executemany):
        if (
            str(connection.engine.url.database) == str(path)
            and "CREATE TABLE metric_appearances" in statement
        ):
            raise RuntimeError("injected appearance DDL failure")

    event.listen(Engine, "before_cursor_execute", fail)
    try:
        with pytest.raises(RuntimeError, match="appearance DDL failure"):
            command.upgrade(alembic_config(str(path)), "head")
    finally:
        event.remove(Engine, "before_cursor_execute", fail)
    assert database_dump(path) == before
    command.upgrade(alembic_config(str(path)), "head")
    command.check(alembic_config(str(path)))
    command.downgrade(alembic_config(str(path)), "000000000003")
    assert database_dump(path) == before
