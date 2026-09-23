"""Add account-bound object appearance without rebuilding existing parent tables."""

from alembic import context, op
import sqlalchemy as sa

revision = "000000000004"
down_revision = "000000000003"
branch_labels = None
depends_on = None

# Frozen migration declarations, independent of evolving application models.
TABLES = (
    (
        "plan_node_appearances",
        "node_appearance",
        "node_id",
        "plan_nodes",
        "uq_plan_node_owner_identity",
    ),
    (
        "metric_appearances",
        "metric_appearance",
        "metric_id",
        "tracked_metrics",
        "uq_metric_owner_identity",
    ),
)


def upgrade() -> None:
    for table, prefix, identity, parent, index in TABLES:
        # SQLite permits a unique parent index for a composite FK. This avoids
        # reconstructing a parent with existing event/timer references.
        op.create_index(index, parent, ["owner_user_id", "id"], unique=True)
        op.create_table(
            table,
            sa.Column("owner_user_id", sa.Integer(), nullable=False),
            sa.Column("icon_kind", sa.String(8), nullable=False),
            sa.Column("icon_role", sa.String(64), nullable=True),
            sa.Column("icon_asset_id", sa.Integer(), nullable=True),
            sa.Column("accent_color", sa.String(9), nullable=False),
            sa.Column("icon_tint", sa.String(8), nullable=False),
            sa.Column(identity, sa.Integer(), nullable=False),
            sa.PrimaryKeyConstraint(identity),
            sa.ForeignKeyConstraint(["owner_user_id"], ["users.id"]),
            sa.ForeignKeyConstraint(
                ["owner_user_id", identity],
                [f"{parent}.owner_user_id", f"{parent}.id"],
                name=f"fk_{prefix}_owner_object",
            ),
            sa.ForeignKeyConstraint(
                ["owner_user_id", "icon_asset_id"],
                ["account_icon_assets.owner_user_id", "account_icon_assets.id"],
                name=f"fk_{prefix}_owner_asset",
            ),
            sa.CheckConstraint(
                "(icon_kind = 'role' AND icon_role IS NOT NULL AND length(icon_role) BETWEEN 1 AND 64 AND icon_asset_id IS NULL) OR (icon_kind = 'asset' AND icon_role IS NULL AND icon_asset_id IS NOT NULL)",
                name=f"ck_{prefix}_icon_shape",
            ),
            sa.CheckConstraint(
                "icon_tint IN ('theme','object')", name=f"ck_{prefix}_tint"
            ),
            sa.CheckConstraint(
                "length(accent_color) IN (7,9) AND substr(accent_color,1,1) = '#'",
                name=f"ck_{prefix}_accent",
            ),
        )


def downgrade() -> None:
    if context.is_offline_mode():
        raise RuntimeError(
            "object appearance downgrade requires an online data-safety check"
        )
    connection = op.get_bind()
    for table, *_ in TABLES:
        if connection.execute(sa.text(f"SELECT 1 FROM {table} LIMIT 1")).first():
            raise RuntimeError(
                "object appearance data exists; use a matching backup or forward repair"
            )
    for table, _, _, parent, index in reversed(TABLES):
        op.drop_table(table)
        op.drop_index(index, table_name=parent)
