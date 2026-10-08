"""Preserve v5 challenge context beside, not inside, original receipt hashes."""

from alembic import context, op
import sqlalchemy as sa

revision = "000000000009"
down_revision = "000000000008"
branch_labels = None
depends_on = None


def upgrade() -> None:
    for table in ("sync_operations", "timer_commands"):
        op.add_column(
            table, sa.Column("challenge_context_json", sa.String(), nullable=True)
        )


def downgrade() -> None:
    if context.is_offline_mode():
        raise RuntimeError("Challenge receipt downgrade requires online validation")
    connection = op.get_bind()
    for table in ("sync_operations", "timer_commands"):
        if connection.execute(
            sa.text(
                f"SELECT 1 FROM {table} WHERE challenge_context_json IS NOT NULL LIMIT 1"
            )
        ).first():
            raise RuntimeError("Challenge receipt evidence must not be discarded")
    for table in ("timer_commands", "sync_operations"):
        op.drop_column(table, "challenge_context_json")
