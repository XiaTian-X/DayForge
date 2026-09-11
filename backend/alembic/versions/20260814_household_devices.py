"""Add device capabilities and per-user primary editor policy.

Revision ID: 20260814_household_devices
Revises: 20260814_timer_sync
Create Date: 2026-08-14
"""

from alembic import op
import sqlalchemy as sa


revision = "20260814_household_devices"
down_revision = "20260814_timer_sync"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("client_devices") as batch_op:
        batch_op.drop_constraint("ck_client_device_platform", type_="check")
        batch_op.create_check_constraint(
            "ck_client_device_platform",
            "platform IN ('android','ios','desktop','hardware','service')",
        )
        batch_op.add_column(
            sa.Column("device_class", sa.String(length=20), nullable=False, server_default="interactive")
        )
        batch_op.add_column(
            sa.Column("structural_edit_enabled", sa.Boolean(), nullable=False, server_default=sa.false())
        )
        batch_op.add_column(
            sa.Column("capability_revision", sa.Integer(), nullable=False, server_default="1")
        )
        batch_op.create_check_constraint(
            "ck_client_device_class",
            "device_class IN ('interactive','hardware','automation')",
        )
        batch_op.create_check_constraint(
            "ck_client_device_capability_revision",
            "capability_revision >= 1",
        )
        batch_op.create_index("ix_client_devices_device_class", ["device_class"])

    op.create_table(
        "user_sync_policies",
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), primary_key=True),
        sa.Column(
            "primary_editor_device_id",
            sa.Integer(),
            sa.ForeignKey("client_devices.id"),
            nullable=True,
        ),
        sa.Column("revision", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.CheckConstraint("revision >= 1", name="ck_user_sync_policy_revision"),
    )
    op.create_index(
        "ix_user_sync_policies_primary_editor_device_id",
        "user_sync_policies",
        ["primary_editor_device_id"],
    )
    op.execute("UPDATE server_instances SET protocol_version = 4 WHERE id = 1")


def downgrade() -> None:
    op.execute("UPDATE server_instances SET protocol_version = 3 WHERE id = 1")
    op.drop_table("user_sync_policies")
    with op.batch_alter_table("client_devices") as batch_op:
        batch_op.drop_index("ix_client_devices_device_class")
        batch_op.drop_constraint("ck_client_device_capability_revision", type_="check")
        batch_op.drop_constraint("ck_client_device_class", type_="check")
        batch_op.drop_column("capability_revision")
        batch_op.drop_column("structural_edit_enabled")
        batch_op.drop_column("device_class")
        batch_op.drop_constraint("ck_client_device_platform", type_="check")
        batch_op.create_check_constraint(
            "ck_client_device_platform",
            "platform IN ('android','ios','desktop')",
        )
