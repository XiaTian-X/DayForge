"""Preserve original counting rules without inventing targets for old facts."""

from alembic import context, op
import sqlalchemy as sa


revision = "000000000007"
down_revision = "000000000006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "activity_events", sa.Column("count_policy_json", sa.String(), nullable=True)
    )
    op.create_table(
        "activity_count_days",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "owner_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False
        ),
        sa.Column(
            "activity_node_id",
            sa.Integer(),
            sa.ForeignKey("plan_nodes.id"),
            nullable=False,
        ),
        sa.Column("local_date", sa.Date(), nullable=False),
        sa.Column("target_value", sa.Integer(), nullable=False),
        sa.Column("is_countdown", sa.Boolean(), nullable=False),
        sa.Column(
            "first_event_id",
            sa.Integer(),
            sa.ForeignKey("activity_events.id"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["owner_user_id", "activity_node_id"],
            ["plan_nodes.owner_user_id", "plan_nodes.id"],
            name="fk_count_day_owner_activity",
        ),
        sa.UniqueConstraint("first_event_id", name="uq_count_day_first_event"),
        sa.UniqueConstraint(
            "activity_node_id", "local_date", name="uq_count_day_activity_date"
        ),
        sa.CheckConstraint(
            "target_value BETWEEN 1 AND 2147483647", name="ck_count_day_target"
        ),
        sa.CheckConstraint(
            "is_countdown IN (false, true)", name="ck_count_day_direction"
        ),
    )
    op.create_index(
        "ix_activity_count_days_owner_user_id", "activity_count_days", ["owner_user_id"]
    )


def downgrade() -> None:
    if context.is_offline_mode():
        raise RuntimeError("count policy downgrade requires an online evidence check")
    connection = op.get_bind()
    if (
        connection.execute(sa.text("SELECT 1 FROM activity_count_days LIMIT 1")).first()
        or connection.execute(
            sa.text(
                "SELECT 1 FROM activity_events WHERE count_policy_json IS NOT NULL LIMIT 1"
            )
        ).first()
    ):
        raise RuntimeError(
            "count day rules exist; use forward repair or a matching backup"
        )
    op.drop_table("activity_count_days")
    op.drop_column("activity_events", "count_policy_json")
