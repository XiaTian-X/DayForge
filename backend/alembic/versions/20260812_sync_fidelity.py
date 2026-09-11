"""Preserve Android goal and countdown configuration in Sync V2.

Revision ID: 20260812_fidelity
Revises: 20260803_v2
Create Date: 2026-08-12
"""

from alembic import op
import sqlalchemy as sa


revision = "20260812_fidelity"
down_revision = "20260803_v2"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "activity_details",
        sa.Column("is_countdown", sa.Boolean(), nullable=False, server_default=sa.false()),
    )
    op.add_column("goal_details", sa.Column("target_cycles", sa.Integer(), nullable=True))
    op.add_column(
        "goal_details",
        sa.Column(
            "failure_policy_json",
            sa.String(),
            nullable=False,
            server_default='{"schema_version":1,"type":"strict"}',
        ),
    )


def downgrade() -> None:
    op.drop_column("goal_details", "failure_policy_json")
    op.drop_column("goal_details", "target_cycles")
    op.drop_column("activity_details", "is_countdown")
