"""Explicit v5 object appearance, separate from legacy icon/color columns."""

from sqlalchemy import CheckConstraint, ForeignKeyConstraint
from sqlmodel import Field, SQLModel


class ObjectAppearanceFields(SQLModel):
    owner_user_id: int = Field(foreign_key="users.id")
    icon_kind: str = Field(max_length=8)
    icon_role: str | None = Field(default=None, max_length=64)
    icon_asset_id: int | None = None
    accent_color: str = Field(max_length=9)
    icon_tint: str = Field(max_length=8)


def appearance_constraints(prefix: str, identity: str, parent: str):
    return (
        ForeignKeyConstraint(
            ["owner_user_id", identity],
            [f"{parent}.owner_user_id", f"{parent}.id"],
            name=f"fk_{prefix}_owner_object",
        ),
        ForeignKeyConstraint(
            ["owner_user_id", "icon_asset_id"],
            ["account_icon_assets.owner_user_id", "account_icon_assets.id"],
            name=f"fk_{prefix}_owner_asset",
        ),
        CheckConstraint(
            "(icon_kind = 'role' AND icon_role IS NOT NULL AND length(icon_role) BETWEEN 1 AND 64 AND icon_asset_id IS NULL) OR "
            "(icon_kind = 'asset' AND icon_role IS NULL AND icon_asset_id IS NOT NULL)",
            name=f"ck_{prefix}_icon_shape",
        ),
        CheckConstraint("icon_tint IN ('theme','object')", name=f"ck_{prefix}_tint"),
        CheckConstraint(
            "length(accent_color) IN (7,9) AND substr(accent_color,1,1) = '#'",
            name=f"ck_{prefix}_accent",
        ),
    )


class PlanNodeAppearance(ObjectAppearanceFields, table=True):
    __tablename__ = "plan_node_appearances"
    __table_args__ = appearance_constraints("node_appearance", "node_id", "plan_nodes")

    node_id: int = Field(primary_key=True)


class MetricAppearance(ObjectAppearanceFields, table=True):
    __tablename__ = "metric_appearances"
    __table_args__ = appearance_constraints(
        "metric_appearance", "metric_id", "tracked_metrics"
    )

    metric_id: int = Field(primary_key=True)
