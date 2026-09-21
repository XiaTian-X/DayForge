"""End-to-end tests for the complete SQLite Alembic migration chain."""

import os
import sqlite3
import tempfile
import warnings
from contextlib import closing

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect, text
from sqlalchemy import CheckConstraint, ForeignKeyConstraint, UniqueConstraint
from sqlmodel import SQLModel

from tests.test_logical_archive import seed_source


PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def alembic_config(database_path: str) -> Config:
    config = Config(os.path.join(PROJECT_ROOT, "alembic.ini"))
    config.set_main_option("script_location", os.path.join(PROJECT_ROOT, "alembic"))
    config.set_main_option("sqlalchemy.url", f"sqlite:///{database_path}")
    return config


def test_upgrade_existing_database_preserves_all_rows_and_schema(tmp_path):
    path = tmp_path / "existing.sqlite"
    config = alembic_config(str(path))
    command.upgrade(config, "head")
    seed_source(f"sqlite:///{path}")

    def snapshot():
        with closing(sqlite3.connect(path)) as connection:
            return list(connection.iterdump())

    before = snapshot()
    with warnings.catch_warnings():
        warnings.simplefilter("error", DeprecationWarning)
        command.upgrade(config, "head")
        command.check(config)
    assert snapshot() == before


def test_migrated_constraints_and_indexes_match_models(tmp_path):
    path = tmp_path / "constraints.sqlite"
    command.upgrade(alembic_config(str(path)), "head")
    engine = create_engine(f"sqlite:///{path}")
    try:
        inspector = inspect(engine)
        for table in SQLModel.metadata.sorted_tables:
            checks = {
                item["name"]: " ".join(item["sqltext"].split())
                for item in inspector.get_check_constraints(table.name)
            }
            assert checks == {
                constraint.name: " ".join(str(constraint.sqltext).split())
                for constraint in table.constraints
                if isinstance(constraint, CheckConstraint)
            }, table.name
            assert {
                (tuple(item["column_names"]), bool(item["unique"]))
                for item in inspector.get_indexes(table.name)
            } == {
                (tuple(column.name for column in index.columns), bool(index.unique))
                for index in table.indexes
            }, table.name
            assert {
                tuple(item["column_names"])
                for item in inspector.get_unique_constraints(table.name)
            } == {
                tuple(column.name for column in constraint.columns)
                for constraint in table.constraints
                if isinstance(constraint, UniqueConstraint)
            }, table.name
            assert {
                (
                    tuple(item["constrained_columns"]),
                    item["referred_table"],
                    tuple(item["referred_columns"]),
                )
                for item in inspector.get_foreign_keys(table.name)
            } == {
                (
                    tuple(column.name for column in constraint.columns),
                    constraint.referred_table.name,
                    tuple(element.column.name for element in constraint.elements),
                )
                for constraint in table.constraints
                if isinstance(constraint, ForeignKeyConstraint)
            }, table.name
    finally:
        engine.dispose()


def test_clean_database_upgrades_to_complete_v2_schema():
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as temp:
        database_path = temp.name
    try:
        config = alembic_config(database_path)
        command.upgrade(config, "head")
        command.check(config)
        engine = create_engine(f"sqlite:///{database_path}")
        inspector = inspect(engine)
        tables = set(inspector.get_table_names())
        expected = {
            "users",
            "plan_nodes",
            "goal_details",
            "activity_details",
            "activity_events",
            "tracked_metrics",
            "metric_observations",
            "client_devices",
            "user_sync_policies",
            "sync_operations",
            "sync_changes",
            "sync_cursors",
            "server_instances",
            "timer_sessions",
            "timer_segments",
            "timer_commands",
            "duration_day_allocations",
            "entity_revision_snapshots",
        }
        assert expected <= tables
        assert tables.isdisjoint(
            {
                "habits",
                "completions",
                "timelogs",
                "metrics",
                "metric_logs",
                "habit_metric_links",
            }
        )

        user_columns = {column["name"] for column in inspector.get_columns("users")}
        assert {"public_id", "status"} <= user_columns
        plan_columns = {
            column["name"] for column in inspector.get_columns("plan_nodes")
        }
        assert {"public_id", "parent_node_id", "revision", "deleted_at"} <= plan_columns
        activity_columns = {
            column["name"] for column in inspector.get_columns("activity_details")
        }
        assert "is_countdown" in activity_columns
        goal_columns = {
            column["name"] for column in inspector.get_columns("goal_details")
        }
        assert {"target_cycles", "failure_policy_json"} <= goal_columns
        event_columns = {
            column["name"] for column in inspector.get_columns("activity_events")
        }
        assert "duration_milliseconds" in event_columns
        device_columns = {
            column["name"] for column in inspector.get_columns("client_devices")
        }
        assert {
            "device_class",
            "structural_edit_enabled",
            "capability_revision",
        } <= device_columns

        with engine.connect() as connection:
            revision = connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one()
            assert revision == "000000000001"
            identity = connection.execute(
                text(
                    "SELECT instance_uuid, sync_epoch, protocol_version FROM server_instances"
                )
            ).one()
            assert len(identity.instance_uuid) == 36
            assert len(identity.sync_epoch) == 36
            assert identity.protocol_version == 4
    finally:
        os.unlink(database_path)


def test_migration_chain_downgrades_to_empty_database():
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as temp:
        database_path = temp.name
    try:
        config = alembic_config(database_path)
        command.upgrade(config, "head")
        command.downgrade(config, "base")
        tables = set(
            inspect(create_engine(f"sqlite:///{database_path}")).get_table_names()
        )
        assert tables <= {"alembic_version", "sqlite_sequence"}
    finally:
        os.unlink(database_path)


def test_sync_change_sequence_is_monotonic_after_delete():
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as temp:
        database_path = temp.name
    try:
        command.upgrade(alembic_config(database_path), "head")
        engine = create_engine(f"sqlite:///{database_path}")
        with engine.begin() as connection:
            connection.execute(
                text(
                    """
                    INSERT INTO users
                        (public_id, username, password_hash, is_active, status,
                         is_verified, is_admin, auth_version, created_at, updated_at)
                    VALUES
                        ('00000000-0000-0000-0000-000000000001', 'migration-user',
                         'hash', 1, 'active', 0, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """
                )
            )
            user_id = connection.execute(
                text("SELECT id FROM users WHERE username='migration-user'")
            ).scalar_one()
            connection.execute(
                text(
                    """
                    INSERT INTO sync_changes
                        (recipient_user_id, entity_type, entity_uuid, operation, revision,
                         payload_json, origin_user_id, changed_at)
                    VALUES
                        (:user_id, 'plan_node', '00000000-0000-0000-0000-000000000010',
                         'upsert', 1, '{}', :user_id, CURRENT_TIMESTAMP)
                    """
                ),
                {"user_id": user_id},
            )
            first = connection.execute(
                text("SELECT max(sequence) FROM sync_changes")
            ).scalar_one()
            connection.execute(text("DELETE FROM sync_changes"))
            connection.execute(
                text(
                    """
                    INSERT INTO sync_changes
                        (recipient_user_id, entity_type, entity_uuid, operation, revision,
                         payload_json, origin_user_id, changed_at)
                    VALUES
                        (:user_id, 'plan_node', '00000000-0000-0000-0000-000000000011',
                         'upsert', 1, '{}', :user_id, CURRENT_TIMESTAMP)
                    """
                ),
                {"user_id": user_id},
            )
            second = connection.execute(
                text("SELECT max(sequence) FROM sync_changes")
            ).scalar_one()
            assert second > first
    finally:
        os.unlink(database_path)
