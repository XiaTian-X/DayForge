"""Add challenge lineage and immutable fact/session birth bindings; no backfill."""

from alembic import context, op
import sqlalchemy as sa

revision = "000000000008"
down_revision = "000000000007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    for table, name, columns in (
        ("client_devices", "uq_client_device_owner_identity", ["user_id", "id"]),
        (
            "activity_events",
            "uq_activity_event_owner_activity_identity",
            ["owner_user_id", "activity_node_id", "id"],
        ),
        (
            "timer_sessions",
            "uq_timer_session_owner_activity_identity",
            ["owner_user_id", "activity_node_id", "id"],
        ),
    ):
        op.create_index(name, table, columns, unique=True)
    op.create_table(
        "activity_challenge_rounds",
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
        sa.Column("public_id", sa.String(36), nullable=False),
        sa.Column("generation", sa.Integer(), nullable=False),
        sa.Column(
            "source_device_id",
            sa.Integer(),
            sa.ForeignKey("client_devices.id"),
            nullable=True,
        ),
        sa.Column("restart_operation_uuid", sa.String(36), nullable=True),
        sa.Column("restart_intent_json", sa.String(), nullable=True),
        sa.UniqueConstraint(
            "owner_user_id", "public_id", name="uq_challenge_round_public"
        ),
        sa.UniqueConstraint(
            "activity_node_id", "generation", name="uq_challenge_round_generation"
        ),
        sa.UniqueConstraint(
            "source_device_id",
            "restart_operation_uuid",
            name="uq_challenge_round_source",
        ),
        sa.UniqueConstraint(
            "owner_user_id",
            "activity_node_id",
            "id",
            name="uq_challenge_round_owned_identity",
        ),
        sa.ForeignKeyConstraint(
            ["owner_user_id", "activity_node_id"],
            ["plan_nodes.owner_user_id", "plan_nodes.id"],
            name="fk_challenge_round_activity",
        ),
        sa.ForeignKeyConstraint(
            ["owner_user_id", "source_device_id"],
            ["client_devices.user_id", "client_devices.id"],
            name="fk_challenge_round_device",
        ),
        sa.CheckConstraint(
            "generation BETWEEN 0 AND 2147483647", name="ck_challenge_round_generation"
        ),
        sa.CheckConstraint(
            "(generation = 0 AND source_device_id IS NULL AND restart_operation_uuid IS NULL AND restart_intent_json IS NULL) OR (generation > 0 AND source_device_id IS NOT NULL AND restart_operation_uuid IS NOT NULL AND restart_intent_json IS NOT NULL)",
            name="ck_challenge_round_source",
        ),
    )
    op.create_index(
        "ix_activity_challenge_rounds_owner_user_id",
        "activity_challenge_rounds",
        ["owner_user_id"],
    )
    op.create_table(
        "activity_challenge_heads",
        sa.Column(
            "activity_node_id",
            sa.Integer(),
            sa.ForeignKey("activity_details.node_id"),
            primary_key=True,
        ),
        sa.Column(
            "owner_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False
        ),
        sa.Column(
            "round_id",
            sa.Integer(),
            sa.ForeignKey("activity_challenge_rounds.id"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["owner_user_id", "activity_node_id", "round_id"],
            [
                "activity_challenge_rounds.owner_user_id",
                "activity_challenge_rounds.activity_node_id",
                "activity_challenge_rounds.id",
            ],
            name="fk_challenge_head_round",
        ),
    )
    for table, source, source_table, fk_source, fk_round in (
        (
            "activity_challenge_event_bindings",
            "event_id",
            "activity_events",
            "fk_challenge_binding_event",
            "fk_challenge_event_round",
        ),
        (
            "activity_challenge_timer_bindings",
            "session_id",
            "timer_sessions",
            "fk_challenge_binding_timer",
            "fk_challenge_timer_round",
        ),
    ):
        op.create_table(
            table,
            sa.Column(
                source,
                sa.Integer(),
                sa.ForeignKey(f"{source_table}.id"),
                primary_key=True,
            ),
            sa.Column(
                "owner_user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False
            ),
            sa.Column(
                "activity_node_id",
                sa.Integer(),
                sa.ForeignKey("activity_details.node_id"),
                nullable=False,
            ),
            sa.Column(
                "round_id",
                sa.Integer(),
                sa.ForeignKey("activity_challenge_rounds.id"),
                nullable=False,
            ),
            sa.ForeignKeyConstraint(
                ["owner_user_id", "activity_node_id", source],
                [
                    f"{source_table}.owner_user_id",
                    f"{source_table}.activity_node_id",
                    f"{source_table}.id",
                ],
                name=fk_source,
            ),
            sa.ForeignKeyConstraint(
                ["owner_user_id", "activity_node_id", "round_id"],
                [
                    "activity_challenge_rounds.owner_user_id",
                    "activity_challenge_rounds.activity_node_id",
                    "activity_challenge_rounds.id",
                ],
                name=fk_round,
            ),
        )


def downgrade() -> None:
    if context.is_offline_mode():
        raise RuntimeError("challenge downgrade requires an online evidence check")
    connection = op.get_bind()
    tables = (
        "activity_challenge_timer_bindings",
        "activity_challenge_event_bindings",
        "activity_challenge_heads",
        "activity_challenge_rounds",
    )
    if any(
        connection.execute(sa.text(f"SELECT 1 FROM {table} LIMIT 1")).first()
        for table in tables
    ):
        raise RuntimeError(
            "challenge history exists; use forward repair or a matching backup"
        )
    for table in tables:
        op.drop_table(table)
    op.drop_index(
        "uq_timer_session_owner_activity_identity", table_name="timer_sessions"
    )
    op.drop_index(
        "uq_activity_event_owner_activity_identity", table_name="activity_events"
    )
    op.drop_index("uq_client_device_owner_identity", table_name="client_devices")
