"""Create account-scoped v2 domain and incremental sync schema.

Revision ID: 20260803_v2
Revises: 000000000001
Create Date: 2026-08-03
"""

from alembic import op
import sqlalchemy as sa


revision = "20260803_v2"
down_revision = "000000000001"
branch_labels = None
depends_on = None


def _sync_columns() -> list[sa.Column]:
    return [
        sa.Column("public_id", sa.String(length=36), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.Column("deleted_at", sa.DateTime(), nullable=True),
    ]


def upgrade() -> None:
    op.add_column("users", sa.Column("public_id", sa.String(length=36), nullable=True))
    op.add_column(
        "users",
        sa.Column("status", sa.String(length=20), nullable=False, server_default="active"),
    )
    op.execute(
        """
        UPDATE users
        SET public_id = lower(hex(randomblob(4))) || '-' ||
                        lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(6)))
        WHERE public_id IS NULL
        """
    )
    with op.batch_alter_table("users") as batch_op:
        batch_op.alter_column("public_id", existing_type=sa.String(length=36), nullable=False)
    op.create_index("ix_users_public_id", "users", ["public_id"], unique=True)
    op.create_index("ix_users_status", "users", ["status"])

    op.create_table(
        "user_profiles",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("display_name", sa.String(length=100), nullable=True),
        sa.Column("avatar_url", sa.String(length=500), nullable=True),
        sa.Column("timezone", sa.String(length=64), nullable=False, server_default="UTC"),
        sa.Column("locale", sa.String(length=20), nullable=False, server_default="zh-CN"),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.UniqueConstraint("user_id", name="uq_user_profiles_user"),
    )
    op.create_index("ix_user_profiles_user_id", "user_profiles", ["user_id"])

    op.create_table(
        "households",
        sa.Column("id", sa.Integer(), primary_key=True),
        *_sync_columns(),
        sa.Column("name", sa.String(length=100), nullable=False),
        sa.Column("created_by_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.UniqueConstraint("public_id", name="uq_households_public_id"),
    )
    op.create_index("ix_households_public_id", "households", ["public_id"])
    op.create_index("ix_households_deleted_at", "households", ["deleted_at"])
    op.create_index("ix_households_created_by_user_id", "households", ["created_by_user_id"])

    op.create_table(
        "household_memberships",
        sa.Column("id", sa.Integer(), primary_key=True),
        *_sync_columns(),
        sa.Column("household_id", sa.Integer(), sa.ForeignKey("households.id"), nullable=False),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("role", sa.String(length=20), nullable=False, server_default="member"),
        sa.Column("status", sa.String(length=20), nullable=False, server_default="active"),
        sa.Column("joined_at", sa.DateTime(), nullable=True),
        sa.UniqueConstraint("public_id", name="uq_household_memberships_public_id"),
        sa.UniqueConstraint("household_id", "user_id", name="uq_household_membership_user"),
        sa.CheckConstraint("role IN ('owner','admin','member')", name="ck_household_membership_role"),
        sa.CheckConstraint(
            "status IN ('invited','active','left','removed')",
            name="ck_household_membership_status",
        ),
    )
    op.create_index("ix_household_memberships_public_id", "household_memberships", ["public_id"])
    op.create_index("ix_household_memberships_deleted_at", "household_memberships", ["deleted_at"])
    op.create_index("ix_household_memberships_household_id", "household_memberships", ["household_id"])
    op.create_index("ix_household_memberships_user_id", "household_memberships", ["user_id"])

    op.create_table(
        "client_devices",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("public_id", sa.String(length=36), nullable=False),
        sa.Column("installation_id", sa.String(length=100), nullable=False),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("platform", sa.String(length=20), nullable=False),
        sa.Column("app_version", sa.String(length=50), nullable=True),
        sa.Column("display_name", sa.String(length=100), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("last_seen_at", sa.DateTime(), nullable=False),
        sa.Column("revoked_at", sa.DateTime(), nullable=True),
        sa.UniqueConstraint("public_id", name="uq_client_devices_public_id"),
        sa.UniqueConstraint("user_id", "installation_id", name="uq_client_device_installation"),
        sa.CheckConstraint("platform IN ('android','ios','desktop')", name="ck_client_device_platform"),
    )
    op.create_index("ix_client_devices_public_id", "client_devices", ["public_id"])
    op.create_index("ix_client_devices_installation_id", "client_devices", ["installation_id"])
    op.create_index("ix_client_devices_user_id", "client_devices", ["user_id"])
    op.create_index("ix_client_devices_revoked_at", "client_devices", ["revoked_at"])

    op.create_table(
        "plan_nodes",
        sa.Column("id", sa.Integer(), primary_key=True),
        *_sync_columns(),
        sa.Column("owner_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("created_by_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("parent_node_id", sa.Integer(), sa.ForeignKey("plan_nodes.id"), nullable=True),
        sa.Column("node_kind", sa.String(length=20), nullable=False),
        sa.Column("title", sa.String(length=100), nullable=False),
        sa.Column("description", sa.String(length=1000), nullable=False, server_default=""),
        sa.Column("icon", sa.String(length=100), nullable=False, server_default="favorite"),
        sa.Column("color_hex", sa.String(length=20), nullable=False, server_default="#2196F3"),
        sa.Column("status", sa.String(length=20), nullable=False, server_default="active"),
        sa.Column("visibility", sa.String(length=20), nullable=False, server_default="private"),
        sa.Column("sort_order", sa.Integer(), nullable=False, server_default="0"),
        sa.UniqueConstraint("owner_user_id", "public_id", name="uq_plan_node_owner_public_id"),
        sa.CheckConstraint("node_kind IN ('goal','activity')", name="ck_plan_node_kind"),
        sa.CheckConstraint(
            "status IN ('active','paused','completed','failed','archived')",
            name="ck_plan_node_status",
        ),
        sa.CheckConstraint("parent_node_id IS NULL OR parent_node_id != id", name="ck_plan_node_not_self_parent"),
    )
    op.create_index("ix_plan_nodes_public_id", "plan_nodes", ["public_id"])
    op.create_index("ix_plan_nodes_deleted_at", "plan_nodes", ["deleted_at"])
    op.create_index("ix_plan_nodes_owner_user_id", "plan_nodes", ["owner_user_id"])
    op.create_index("ix_plan_nodes_created_by_user_id", "plan_nodes", ["created_by_user_id"])
    op.create_index("ix_plan_nodes_parent_node_id", "plan_nodes", ["parent_node_id"])
    op.create_index("ix_plan_nodes_node_kind", "plan_nodes", ["node_kind"])
    op.create_index("ix_plan_nodes_status", "plan_nodes", ["status"])

    op.create_table(
        "goal_details",
        sa.Column("node_id", sa.Integer(), sa.ForeignKey("plan_nodes.id"), primary_key=True),
        sa.Column("start_date", sa.Date(), nullable=True),
        sa.Column("due_date", sa.Date(), nullable=True),
        sa.Column("evaluation_policy_json", sa.String(), nullable=False),
        sa.Column("manual_result", sa.String(length=20), nullable=True),
    )
    op.create_table(
        "activity_details",
        sa.Column("node_id", sa.Integer(), sa.ForeignKey("plan_nodes.id"), primary_key=True),
        sa.Column("tracking_mode", sa.String(length=20), nullable=False),
        sa.Column("recurrence_rule_json", sa.String(), nullable=False),
        sa.Column("completion_policy", sa.String(length=20), nullable=False),
        sa.Column("target_value", sa.Numeric(18, 4), nullable=False, server_default="1"),
        sa.Column("target_unit", sa.String(length=50), nullable=True),
        sa.Column("target_cycles", sa.Integer(), nullable=True),
        sa.Column("failure_policy_json", sa.String(), nullable=False),
        sa.Column("preferred_local_time", sa.Time(), nullable=True),
        sa.Column("timezone", sa.String(length=64), nullable=False, server_default="UTC"),
        sa.Column("origin_assignment_id", sa.String(length=36), nullable=True),
        sa.CheckConstraint("tracking_mode IN ('check','count','duration')", name="ck_activity_tracking_mode"),
        sa.CheckConstraint(
            "completion_policy IN ('recurring','one_and_done')",
            name="ck_activity_completion_policy",
        ),
        sa.CheckConstraint("target_value >= 0", name="ck_activity_target_value"),
        sa.CheckConstraint("target_cycles IS NULL OR target_cycles > 0", name="ck_activity_target_cycles"),
    )
    op.create_index("ix_activity_details_origin_assignment_id", "activity_details", ["origin_assignment_id"])

    op.create_table(
        "activity_events",
        sa.Column("id", sa.Integer(), primary_key=True),
        *_sync_columns(),
        sa.Column("owner_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("activity_node_id", sa.Integer(), sa.ForeignKey("activity_details.node_id"), nullable=False),
        sa.Column("event_type", sa.String(length=30), nullable=False),
        sa.Column("value", sa.Numeric(18, 4), nullable=True),
        sa.Column("duration_seconds", sa.Integer(), nullable=True),
        sa.Column("started_at", sa.DateTime(), nullable=True),
        sa.Column("ended_at", sa.DateTime(), nullable=True),
        sa.Column("occurred_at", sa.DateTime(), nullable=False),
        sa.Column("local_date", sa.Date(), nullable=False),
        sa.Column("timezone", sa.String(length=64), nullable=False),
        sa.Column("note", sa.String(length=1000), nullable=False, server_default=""),
        sa.Column("source_type", sa.String(length=30), nullable=False, server_default="app"),
        sa.Column("source_device_public_id", sa.String(length=36), nullable=True),
        sa.Column("external_event_id", sa.String(length=200), nullable=True),
        sa.Column("recorded_by_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=True),
        sa.Column("reverts_event_id", sa.Integer(), sa.ForeignKey("activity_events.id"), nullable=True),
        sa.Column("payload_json", sa.String(), nullable=False, server_default="{}"),
        sa.Column("received_at", sa.DateTime(), nullable=False),
        sa.UniqueConstraint("owner_user_id", "public_id", name="uq_activity_event_owner_public_id"),
        sa.UniqueConstraint("source_device_public_id", "external_event_id", name="uq_activity_event_external_source"),
        sa.CheckConstraint(
            "event_type IN ('check_in','count_delta','count_snapshot','duration_session','revert')",
            name="ck_activity_event_type",
        ),
        sa.CheckConstraint("duration_seconds IS NULL OR duration_seconds >= 0", name="ck_activity_event_duration"),
    )
    for column in [
        "public_id", "deleted_at", "owner_user_id", "activity_node_id", "event_type",
        "occurred_at", "local_date", "source_device_public_id", "recorded_by_user_id",
        "reverts_event_id",
    ]:
        op.create_index(f"ix_activity_events_{column}", "activity_events", [column])
    op.create_index(
        "uq_activity_event_revert_target",
        "activity_events",
        ["reverts_event_id"],
        unique=True,
        sqlite_where=sa.text("reverts_event_id IS NOT NULL"),
        postgresql_where=sa.text("reverts_event_id IS NOT NULL"),
    )

    op.create_table(
        "tracked_metrics",
        sa.Column("id", sa.Integer(), primary_key=True),
        *_sync_columns(),
        sa.Column("owner_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("created_by_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("name", sa.String(length=100), nullable=False),
        sa.Column("description", sa.String(length=1000), nullable=False, server_default=""),
        sa.Column("unit", sa.String(length=50), nullable=False),
        sa.Column("decimal_places", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("aggregation_type", sa.String(length=30), nullable=False, server_default="average"),
        sa.Column("target_direction", sa.String(length=30), nullable=True),
        sa.Column("target_value", sa.Numeric(20, 6), nullable=True),
        sa.Column("target_value_upper", sa.Numeric(20, 6), nullable=True),
        sa.Column("icon", sa.String(length=100), nullable=False, server_default="favorite"),
        sa.Column("color_hex", sa.String(length=20), nullable=False, server_default="#2196F3"),
        sa.Column("status", sa.String(length=20), nullable=False, server_default="active"),
        sa.UniqueConstraint("owner_user_id", "public_id", name="uq_tracked_metric_owner_public_id"),
        sa.CheckConstraint("decimal_places BETWEEN 0 AND 6", name="ck_tracked_metric_decimal_places"),
    )
    for column in ["public_id", "deleted_at", "owner_user_id", "created_by_user_id", "status"]:
        op.create_index(f"ix_tracked_metrics_{column}", "tracked_metrics", [column])

    op.create_table(
        "metric_observations",
        sa.Column("id", sa.Integer(), primary_key=True),
        *_sync_columns(),
        sa.Column("owner_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("metric_id", sa.Integer(), sa.ForeignKey("tracked_metrics.id"), nullable=False),
        sa.Column("value", sa.Numeric(20, 6), nullable=False),
        sa.Column("unit", sa.String(length=50), nullable=False),
        sa.Column("occurred_at", sa.DateTime(), nullable=False),
        sa.Column("local_date", sa.Date(), nullable=False),
        sa.Column("timezone", sa.String(length=64), nullable=False),
        sa.Column("note", sa.String(length=1000), nullable=False, server_default=""),
        sa.Column("source_type", sa.String(length=30), nullable=False, server_default="app"),
        sa.Column("source_device_public_id", sa.String(length=36), nullable=True),
        sa.Column("external_event_id", sa.String(length=200), nullable=True),
        sa.Column("recorded_by_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=True),
        sa.Column("payload_json", sa.String(), nullable=False, server_default="{}"),
        sa.Column("received_at", sa.DateTime(), nullable=False),
        sa.UniqueConstraint("owner_user_id", "public_id", name="uq_metric_observation_owner_public_id"),
        sa.UniqueConstraint("source_device_public_id", "external_event_id", name="uq_metric_observation_external_source"),
    )
    for column in [
        "public_id", "deleted_at", "owner_user_id", "metric_id", "occurred_at",
        "local_date", "source_device_public_id", "recorded_by_user_id",
    ]:
        op.create_index(f"ix_metric_observations_{column}", "metric_observations", [column])

    op.create_table(
        "activity_metric_links_v2",
        sa.Column("id", sa.Integer(), primary_key=True),
        *_sync_columns(),
        sa.Column("owner_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("activity_node_id", sa.Integer(), sa.ForeignKey("activity_details.node_id"), nullable=False),
        sa.Column("metric_id", sa.Integer(), sa.ForeignKey("tracked_metrics.id"), nullable=False),
        sa.Column("coefficient", sa.Numeric(20, 6), nullable=False, server_default="1"),
        sa.Column("show_in_activity_detail", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("prompt_on_complete", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.UniqueConstraint("owner_user_id", "public_id", name="uq_activity_metric_link_owner_public_id"),
    )
    for column in ["public_id", "deleted_at", "owner_user_id", "activity_node_id", "metric_id"]:
        op.create_index(f"ix_activity_metric_links_v2_{column}", "activity_metric_links_v2", [column])
    op.create_index(
        "uq_activity_metric_link_active_pair",
        "activity_metric_links_v2",
        ["activity_node_id", "metric_id"],
        unique=True,
        sqlite_where=sa.text("deleted_at IS NULL"),
        postgresql_where=sa.text("deleted_at IS NULL"),
    )

    op.create_table(
        "sync_operations",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("device_id", sa.Integer(), sa.ForeignKey("client_devices.id"), nullable=False),
        sa.Column("operation_id", sa.String(length=36), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=30), nullable=False),
        sa.Column("entity_type", sa.String(length=40), nullable=False),
        sa.Column("entity_uuid", sa.String(length=36), nullable=False),
        sa.Column("action", sa.String(length=20), nullable=False),
        sa.Column("base_revision", sa.Integer(), nullable=True),
        sa.Column("result_json", sa.String(), nullable=False, server_default="{}"),
        sa.Column("error_code", sa.String(length=100), nullable=True),
        sa.Column("received_at", sa.DateTime(), nullable=False),
        sa.Column("completed_at", sa.DateTime(), nullable=True),
        sa.UniqueConstraint("device_id", "operation_id", name="uq_sync_operation_device_operation"),
    )
    for column in ["user_id", "device_id", "operation_id", "status", "entity_uuid"]:
        op.create_index(f"ix_sync_operations_{column}", "sync_operations", [column])

    op.create_table(
        "sync_changes",
        sa.Column("sequence", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("recipient_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("entity_type", sa.String(length=40), nullable=False),
        sa.Column("entity_uuid", sa.String(length=36), nullable=False),
        sa.Column("operation", sa.String(length=20), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("payload_json", sa.String(), nullable=False, server_default="{}"),
        sa.Column("origin_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("origin_device_id", sa.Integer(), sa.ForeignKey("client_devices.id"), nullable=True),
        sa.Column("origin_operation_id", sa.String(length=36), nullable=True),
        sa.Column("changed_at", sa.DateTime(), nullable=False),
        sqlite_autoincrement=True,
    )
    for column in [
        "recipient_user_id", "entity_type", "entity_uuid", "origin_user_id",
        "origin_device_id", "origin_operation_id", "changed_at",
    ]:
        op.create_index(f"ix_sync_changes_{column}", "sync_changes", [column])
    op.create_index(
        "ix_sync_changes_recipient_sequence",
        "sync_changes",
        ["recipient_user_id", "sequence"],
    )

    op.create_table(
        "sync_cursors",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("device_id", sa.Integer(), sa.ForeignKey("client_devices.id"), nullable=False),
        sa.Column("last_pulled_sequence", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("last_pull_at", sa.DateTime(), nullable=True),
        sa.Column("last_push_at", sa.DateTime(), nullable=True),
        sa.UniqueConstraint("user_id", "device_id", name="uq_sync_cursor_user_device"),
    )
    op.create_index("ix_sync_cursors_user_id", "sync_cursors", ["user_id"])
    op.create_index("ix_sync_cursors_device_id", "sync_cursors", ["device_id"])


def downgrade() -> None:
    op.drop_table("sync_cursors")
    op.drop_table("sync_changes")
    op.drop_table("sync_operations")
    op.drop_table("activity_metric_links_v2")
    op.drop_table("metric_observations")
    op.drop_table("tracked_metrics")
    op.drop_table("activity_events")
    op.drop_table("activity_details")
    op.drop_table("goal_details")
    op.drop_table("plan_nodes")
    op.drop_table("client_devices")
    op.drop_table("household_memberships")
    op.drop_table("households")
    op.drop_table("user_profiles")
    op.drop_index("ix_users_status", table_name="users")
    op.drop_index("ix_users_public_id", table_name="users")
    with op.batch_alter_table("users") as batch_op:
        batch_op.drop_column("status")
        batch_op.drop_column("public_id")
