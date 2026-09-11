"""Add service identity and ordered active-timer synchronization.

Revision ID: 20260814_timer_sync
Revises: 20260812_fidelity
Create Date: 2026-08-14
"""

from uuid import uuid4

from alembic import op
import sqlalchemy as sa


revision = "20260814_timer_sync"
down_revision = "20260812_fidelity"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("activity_events", sa.Column("duration_milliseconds", sa.Integer(), nullable=True))
    with op.batch_alter_table("activity_events") as batch_op:
        batch_op.create_check_constraint(
            "ck_activity_event_duration_ms",
            "duration_milliseconds IS NULL OR duration_milliseconds >= 0",
        )

    op.create_table(
        "server_instances",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("instance_uuid", sa.String(length=36), nullable=False, unique=True),
        sa.Column("sync_epoch", sa.String(length=36), nullable=False, unique=True),
        sa.Column("protocol_version", sa.Integer(), nullable=False, server_default="3"),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.execute(
        sa.text(
            "INSERT INTO server_instances(id, instance_uuid, sync_epoch, protocol_version, created_at) "
            "VALUES (1, :instance_uuid, :sync_epoch, 3, CURRENT_TIMESTAMP)"
        ).bindparams(instance_uuid=str(uuid4()), sync_epoch=str(uuid4()))
    )

    op.create_table(
        "timer_sessions",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("public_id", sa.String(length=36), nullable=False),
        sa.Column("owner_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("activity_node_id", sa.Integer(), sa.ForeignKey("activity_details.node_id"), nullable=False),
        sa.Column("state", sa.String(length=20), nullable=False),
        sa.Column("controller_device_id", sa.Integer(), sa.ForeignKey("client_devices.id"), nullable=False),
        sa.Column("control_generation", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("revision", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("next_command_sequence", sa.Integer(), nullable=False, server_default="2"),
        sa.Column("started_at", sa.DateTime(), nullable=False),
        sa.Column("state_changed_at", sa.DateTime(), nullable=False),
        sa.Column("ended_at", sa.DateTime(), nullable=True),
        sa.Column("timezone", sa.String(length=64), nullable=False),
        sa.Column("is_countdown", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("target_seconds", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("max_duration_seconds", sa.Integer(), nullable=False, server_default="86400"),
        sa.Column("active_elapsed_ms", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("last_heartbeat_at", sa.DateTime(), nullable=True),
        sa.Column("completed_event_id", sa.Integer(), sa.ForeignKey("activity_events.id"), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.UniqueConstraint("owner_user_id", "public_id", name="uq_timer_session_owner_public_id"),
        sa.CheckConstraint(
            "state IN ('running','paused','completed','cancelled')",
            name="ck_timer_session_state",
        ),
        sa.CheckConstraint("control_generation >= 1", name="ck_timer_session_generation"),
        sa.CheckConstraint("revision >= 1", name="ck_timer_session_revision"),
        sa.CheckConstraint("next_command_sequence >= 2", name="ck_timer_session_next_sequence"),
        sa.CheckConstraint("active_elapsed_ms >= 0", name="ck_timer_session_elapsed"),
        sa.CheckConstraint("max_duration_seconds > 0", name="ck_timer_session_max_duration"),
    )
    for column in [
        "public_id",
        "owner_user_id",
        "activity_node_id",
        "state",
        "controller_device_id",
        "completed_event_id",
    ]:
        op.create_index(f"ix_timer_sessions_{column}", "timer_sessions", [column])
    op.create_index(
        "uq_timer_session_owner_active",
        "timer_sessions",
        ["owner_user_id"],
        unique=True,
        sqlite_where=sa.text("state IN ('running','paused')"),
        postgresql_where=sa.text("state IN ('running','paused')"),
    )

    op.create_table(
        "timer_segments",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("session_id", sa.Integer(), sa.ForeignKey("timer_sessions.id"), nullable=False),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("started_at", sa.DateTime(), nullable=False),
        sa.Column("ended_at", sa.DateTime(), nullable=True),
        sa.Column("duration_ms", sa.Integer(), nullable=True),
        sa.UniqueConstraint("session_id", "sequence", name="uq_timer_segment_session_sequence"),
        sa.CheckConstraint("sequence >= 1", name="ck_timer_segment_sequence"),
        sa.CheckConstraint("duration_ms IS NULL OR duration_ms >= 0", name="ck_timer_segment_duration"),
    )
    op.create_index("ix_timer_segments_session_id", "timer_segments", ["session_id"])
    op.create_index(
        "uq_timer_segment_session_open",
        "timer_segments",
        ["session_id"],
        unique=True,
        sqlite_where=sa.text("ended_at IS NULL"),
        postgresql_where=sa.text("ended_at IS NULL"),
    )

    op.create_table(
        "timer_commands",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("device_id", sa.Integer(), sa.ForeignKey("client_devices.id"), nullable=False),
        sa.Column("command_id", sa.String(length=36), nullable=False),
        sa.Column("session_public_id", sa.String(length=36), nullable=False),
        sa.Column("command_sequence", sa.Integer(), nullable=False),
        sa.Column("command_type", sa.String(length=20), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=30), nullable=False),
        sa.Column("result_json", sa.String(), nullable=False, server_default="{}"),
        sa.Column("error_code", sa.String(length=100), nullable=True),
        sa.Column("received_at", sa.DateTime(), nullable=False),
        sa.Column("completed_at", sa.DateTime(), nullable=True),
        sa.UniqueConstraint("device_id", "command_id", name="uq_timer_command_device_command"),
        sa.CheckConstraint(
            "command_type IN ('start','pause','resume','stop','cancel','takeover')",
            name="ck_timer_command_type",
        ),
    )
    for column in [
        "user_id",
        "device_id",
        "command_id",
        "session_public_id",
        "command_sequence",
        "status",
    ]:
        op.create_index(f"ix_timer_commands_{column}", "timer_commands", [column])

    op.create_table(
        "duration_day_allocations",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("activity_event_id", sa.Integer(), sa.ForeignKey("activity_events.id"), nullable=False),
        sa.Column("local_date", sa.Date(), nullable=False),
        sa.Column("timezone", sa.String(length=64), nullable=False),
        sa.Column("duration_ms", sa.Integer(), nullable=False),
        sa.UniqueConstraint(
            "activity_event_id",
            "local_date",
            name="uq_duration_allocation_event_date",
        ),
        sa.CheckConstraint("duration_ms > 0", name="ck_duration_allocation_positive"),
    )
    op.create_index(
        "ix_duration_day_allocations_activity_event_id",
        "duration_day_allocations",
        ["activity_event_id"],
    )
    op.create_index(
        "ix_duration_day_allocations_local_date",
        "duration_day_allocations",
        ["local_date"],
    )


def downgrade() -> None:
    op.drop_table("duration_day_allocations")
    op.drop_table("timer_commands")
    op.drop_table("timer_segments")
    op.drop_table("timer_sessions")
    op.drop_table("server_instances")
    with op.batch_alter_table("activity_events") as batch_op:
        batch_op.drop_constraint("ck_activity_event_duration_ms", type_="check")
        batch_op.drop_column("duration_milliseconds")
