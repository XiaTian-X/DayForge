"""Validate object identity, icon purpose and references in one storage snapshot."""

from collections.abc import Callable
from typing import Any

from pydantic import ValidationError

from src.v2.appearance import IconAsset, ObjectAppearance, icon_allowed


NODE_SQL = """
SELECT a.*, n.owner_user_id AS object_owner, n.node_kind, d.completion_policy,
       s.owner_user_id AS asset_owner, s.public_id AS asset_uuid, s.metadata_json AS asset_json
FROM plan_node_appearances a LEFT JOIN plan_nodes n ON n.id=a.node_id
LEFT JOIN activity_details d ON d.node_id=n.id
LEFT JOIN account_icon_assets s ON s.id=a.icon_asset_id
"""
METRIC_SQL = """
SELECT a.*, m.owner_user_id AS object_owner,
       s.owner_user_id AS asset_owner, s.public_id AS asset_uuid, s.metadata_json AS asset_json
FROM metric_appearances a LEFT JOIN tracked_metrics m ON m.id=a.metric_id
LEFT JOIN account_icon_assets s ON s.id=a.icon_asset_id
"""


class ObjectAppearanceRecoveryError(ValueError):
    """Object appearance cannot be safely read or restored."""


def validate_object_row(row: dict[str, Any], *, metric: bool) -> ObjectAppearance:
    if row["owner_user_id"] != row["object_owner"]:
        raise ObjectAppearanceRecoveryError("object appearance ownership mismatch")
    one_time = False
    if not metric:
        if row["node_kind"] == "activity":
            if row["completion_policy"] not in {"recurring", "one_and_done"}:
                raise ObjectAppearanceRecoveryError(
                    "missing activity completion policy"
                )
            one_time = row["completion_policy"] == "one_and_done"
        elif row["node_kind"] != "goal":
            raise ObjectAppearanceRecoveryError("unknown appearance object kind")
    asset = None
    if row["icon_kind"] == "role":
        if row["icon_asset_id"] is not None:
            raise ObjectAppearanceRecoveryError("role contains an asset reference")
        icon = {"kind": "role", "role": row["icon_role"]}
    elif row["icon_kind"] == "asset":
        if (
            row["icon_role"] is not None
            or row["icon_asset_id"] is None
            or row["asset_owner"] != row["owner_user_id"]
        ):
            raise ObjectAppearanceRecoveryError("asset appearance ownership mismatch")
        asset = IconAsset.model_validate_json(row["asset_json"])
        if asset.asset_id != row["asset_uuid"]:
            raise ObjectAppearanceRecoveryError("asset appearance identity mismatch")
        icon = {"kind": "asset", "asset_id": row["asset_uuid"]}
    else:
        raise ObjectAppearanceRecoveryError("unknown appearance icon kind")
    appearance = ObjectAppearance.model_validate(
        dict(icon=icon, accent_color=row["accent_color"], icon_tint=row["icon_tint"])
    )
    if not icon_allowed(appearance.icon, one_time=one_time, asset=asset):
        raise ObjectAppearanceRecoveryError("object icon purpose mismatch")
    return appearance


def read_object_appearances(read_rows: Callable[[str], list[dict[str, Any]]]) -> None:
    try:
        for statement, metric in ((NODE_SQL, False), (METRIC_SQL, True)):
            for row in read_rows(statement):
                validate_object_row(row, metric=metric)
    except ValidationError as error:
        raise ObjectAppearanceRecoveryError(
            "invalid object appearance contract"
        ) from error
