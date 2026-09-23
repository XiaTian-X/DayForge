"""Add explicit one-time projections and immutable event preconditions.

No existing facts are interpreted or converted. NULL marks the pre-v5 boundary;
the online protocol remains v4 until a separately confirmed coordinated cutover.
"""

from alembic import context, op
import sqlalchemy as sa

revision = "000000000002"
down_revision = "000000000001"
branch_labels = None
depends_on = None

# Frozen migration definitions, not imports from evolving application models.
STATE_CHECK = (
    "(one_time_version IS NULL AND one_time_head_event_uuid IS NULL AND one_time_completion_event_uuid IS NULL) OR "
    "(completion_policy = 'one_and_done' AND tracking_mode = 'check' AND one_time_version IS NOT NULL "
    "AND one_time_version >= 0 AND one_time_version <= 2147483647 AND "
    "((one_time_version = 0 AND one_time_head_event_uuid IS NULL AND one_time_completion_event_uuid IS NULL) OR "
    "(one_time_version > 0 AND one_time_head_event_uuid IS NOT NULL AND length(one_time_head_event_uuid) = 36 AND "
    "((one_time_version % 2 = 1 AND one_time_completion_event_uuid IS NOT NULL AND one_time_completion_event_uuid = one_time_head_event_uuid) OR "
    "(one_time_version % 2 = 0 AND one_time_completion_event_uuid IS NULL)))))"
)
INTENT_CHECK = (
    "(one_time_expected_version IS NULL AND one_time_expected_head_event_uuid IS NULL) OR "
    "(one_time_expected_version IS NOT NULL AND one_time_expected_version >= 0 AND one_time_expected_version < 2147483647 AND "
    "((one_time_expected_version = 0 AND one_time_expected_head_event_uuid IS NULL) OR "
    "(one_time_expected_version > 0 AND one_time_expected_head_event_uuid IS NOT NULL AND length(one_time_expected_head_event_uuid) = 36 "
    "AND one_time_expected_head_event_uuid != public_id)) AND "
    "((event_type = 'check_in' AND one_time_expected_version % 2 = 0 AND reverts_event_id IS NULL) OR "
    "(event_type = 'revert' AND one_time_expected_version % 2 = 1 AND reverts_event_id IS NOT NULL)))"
)


def upgrade() -> None:
    op.add_column(
        "activity_details", sa.Column("one_time_version", sa.Integer(), nullable=True)
    )
    op.add_column(
        "activity_details",
        sa.Column("one_time_head_event_uuid", sa.String(36), nullable=True),
    )
    # Column-attached checks allow SQLite ADD COLUMN without dropping a referenced
    # parent table or disabling foreign keys; both checks inspect existing rows.
    op.add_column(
        "activity_details",
        sa.Column(
            "one_time_completion_event_uuid",
            sa.String(36),
            sa.CheckConstraint(STATE_CHECK, name="ck_activity_one_time_state"),
            nullable=True,
        ),
    )
    op.add_column(
        "activity_events",
        sa.Column("one_time_expected_version", sa.Integer(), nullable=True),
    )
    op.add_column(
        "activity_events",
        sa.Column(
            "one_time_expected_head_event_uuid",
            sa.String(36),
            sa.CheckConstraint(INTENT_CHECK, name="ck_activity_event_one_time_intent"),
            nullable=True,
        ),
    )


def downgrade() -> None:
    if context.is_offline_mode():
        raise RuntimeError("one-time downgrade requires an online data-safety check")
    connection = op.get_bind()
    for table, column in (
        ("activity_details", "one_time_version"),
        ("activity_events", "one_time_expected_version"),
    ):
        if connection.execute(
            sa.text(f"SELECT 1 FROM {table} WHERE {column} IS NOT NULL LIMIT 1")
        ).first():
            raise RuntimeError(
                "one-time data exists; use a matching backup or forward repair"
            )
    # Drop the check-bearing column first; never rewrite sqlite_schema or turn off FK.
    op.drop_column("activity_events", "one_time_expected_head_event_uuid")
    op.drop_column("activity_events", "one_time_expected_version")
    op.drop_column("activity_details", "one_time_completion_event_uuid")
    op.drop_column("activity_details", "one_time_head_event_uuid")
    op.drop_column("activity_details", "one_time_version")
