"""Alembic environment configuration for database migrations."""

from logging.config import fileConfig
import os

from sqlalchemy import engine_from_config
from sqlalchemy import pool
import sqlalchemy as sa

from alembic import context

# Import SQLModel and all models to register them with metadata
from sqlmodel import SQLModel
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Import all models to register them with SQLModel.metadata
from src.auth.models import User  # noqa: F401
from src.tokens.models import ApiToken  # noqa: F401
from src.v2 import asset_models as asset_models  # Register appearance tables.
from src.v2.models import (  # noqa: F401
    ActivityDetail,
    ActivityEvent,
    ActivityMetricLinkV2,
    ClientDevice,
    DurationDayAllocation,
    EntityRevisionSnapshot,
    GoalDetail,
    Household,
    HouseholdMembership,
    MetricObservation,
    PlanNode,
    ServerInstance,
    SyncChange,
    SyncCursor,
    SyncOperation,
    TimerCommand,
    TimerSegment,
    TimerSession,
    TrackedMetric,
    UserProfile,
    UserSyncPolicy,
)

# Import the validated synchronous migration URL from the storage adapter.
from src.config import get_migration_database_url
from src.storage.database_adapter import configure_sqlite_transactions

# this is the Alembic Config object, which provides
# access to the values within the .ini file in use.
config = context.config

# Set sqlalchemy.url from environment only if not already set in config
# This allows tests to override the URL via config.set_main_option()
if (
    config.get_main_option("sqlalchemy.url") is None
    or config.get_main_option("sqlalchemy.url") == "driver://user:pass@localhost/dbname"
):
    config.set_main_option("sqlalchemy.url", get_migration_database_url())

# Interpret the config file for Python logging.
# This line sets up loggers basically.
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# add your model's MetaData object here
# for 'autogenerate' support
target_metadata = SQLModel.metadata


def compare_type(
    context, inspected_column, metadata_column, inspected_type, metadata_type
):
    """Ignore SQLite's loss of SQLAlchemy Enum reflection metadata.

    Legacy models use Python Enum columns, which SQLite persists and reflects as
    VARCHAR/TEXT. Treating that representation as a migration on every check
    creates false positives while real non-enum type changes remain visible.
    """
    if (
        context.dialect.name == "sqlite"
        and isinstance(metadata_type, sa.Enum)
        and isinstance(inspected_type, (sa.String, sa.Text))
    ):
        return False
    return None


def run_migrations_offline() -> None:
    """Run migrations in 'offline' mode.

    This configures the context with just a URL
    and not an Engine, though an Engine is acceptable
    here as well.  By skipping the Engine creation
    we don't even need a DBAPI to be available.

    Calls to context.execute() here emit the given string to the
    script output.

    """
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=compare_type,
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    """Run migrations in 'online' mode.

    In this scenario we need to create an Engine
    and associate a connection with the context.

    """
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    if connectable.dialect.name == "sqlite":
        configure_sqlite_transactions(connectable)
    try:
        with connectable.connect() as connection:
            context.configure(
                connection=connection,
                target_metadata=target_metadata,
                compare_type=compare_type,
                transactional_ddl=True if connection.dialect.name == "sqlite" else None,
            )

            with context.begin_transaction():
                context.run_migrations()
    finally:
        connectable.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
