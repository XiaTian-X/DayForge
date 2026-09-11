"""Add immutable entity revision snapshots for three-way merge.

Revision ID: 20260814_revision_merge
Revises: 20260814_household_devices
Create Date: 2026-08-14
"""

from alembic import op
import sqlalchemy as sa


revision = "20260814_revision_merge"
down_revision = "20260814_household_devices"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "entity_revision_snapshots",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("owner_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("entity_type", sa.String(length=40), nullable=False),
        sa.Column("entity_uuid", sa.String(length=36), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("operation", sa.String(length=20), nullable=False),
        sa.Column("payload_json", sa.String(), nullable=False, server_default="{}"),
        sa.Column("payload_hash", sa.String(length=64), nullable=False),
        sa.Column("origin_device_id", sa.Integer(), sa.ForeignKey("client_devices.id"), nullable=True),
        sa.Column("origin_operation_id", sa.String(length=36), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.UniqueConstraint(
            "owner_user_id",
            "entity_type",
            "entity_uuid",
            "revision",
            name="uq_entity_revision_snapshot",
        ),
        sa.CheckConstraint("revision >= 1", name="ck_entity_revision_snapshot_revision"),
        sa.CheckConstraint(
            "operation IN ('upsert','delete')",
            name="ck_entity_revision_snapshot_operation",
        ),
    )
    for column in [
        "owner_user_id",
        "entity_type",
        "entity_uuid",
        "origin_device_id",
        "origin_operation_id",
        "created_at",
    ]:
        op.create_index(
            f"ix_entity_revision_snapshots_{column}",
            "entity_revision_snapshots",
            [column],
        )


def downgrade() -> None:
    op.drop_table("entity_revision_snapshots")
