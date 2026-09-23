"""Owner-bound object appearance primitives; caller owns revision and transaction."""

from pydantic import ValidationError
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.v2.appearance import AssetIcon, ObjectAppearance, RoleIcon, icon_allowed
from src.v2.asset_models import AccountIconAsset
from src.v2.asset_records import asset_value
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.models import ActivityDetail, PlanNode, TrackedMetric
from src.v2.object_appearance_models import (
    MetricAppearance,
    ObjectAppearanceFields,
    PlanNodeAppearance,
)
from src.v2.object_appearance_recovery import (
    METRIC_SQL,
    NODE_SQL,
    ObjectAppearanceRecoveryError,
    validate_object_row,
)


async def read_object_appearance(
    session: AsyncSession, owner: int, object_id: int, *, metric: bool = False
) -> ObjectAppearance:
    statement = METRIC_SQL if metric else NODE_SQL
    identity = "metric_id" if metric else "node_id"
    row = (
        (
            await session.execute(
                text(
                    statement
                    + f" WHERE a.owner_user_id=:owner AND a.{identity}=:identity"
                ),
                {"owner": owner, "identity": object_id},
            )
        )
        .mappings()
        .one_or_none()
    )
    if row is None:
        raise DomainError(
            "APPEARANCE_STATE_UNINITIALIZED",
            "Pre-v5 object requires the coordinated data baseline",
        )
    try:
        return validate_object_row(dict(row), metric=metric)
    except (ValidationError, ObjectAppearanceRecoveryError) as error:
        raise DomainError(
            "APPEARANCE_STATE_INVALID", "Stored object appearance is invalid"
        ) from error


async def _resolved_fields(
    session: AsyncSession, owner: int, appearance: ObjectAppearance, *, one_time: bool
) -> ObjectAppearanceFields:
    reference = appearance.icon
    asset_id = None
    asset = None
    if isinstance(reference, AssetIcon):
        row = (
            await session.execute(
                select(AccountIconAsset).where(
                    col(AccountIconAsset.owner_user_id) == owner,
                    col(AccountIconAsset.public_id) == reference.asset_id,
                )
            )
        ).scalar_one_or_none()
        if row is None:
            raise DomainError("ASSET_NOT_FOUND", "The account icon asset was not found")
        asset = asset_value(row)
        asset_id = require_internal(row.id, "asset.id")
    if not icon_allowed(reference, one_time=one_time, asset=asset):
        raise DomainError(
            "ICON_PURPOSE_MISMATCH", "Task icons are reserved for one-time items"
        )
    return ObjectAppearanceFields(
        owner_user_id=owner,
        icon_kind=reference.kind,
        icon_role=reference.role if isinstance(reference, RoleIcon) else None,
        icon_asset_id=asset_id,
        accent_color=appearance.accent_color,
        icon_tint=appearance.icon_tint,
    )


async def _node_policy(session: AsyncSession, owner: int, node_id: int) -> bool:
    row = (
        await session.execute(
            select(
                PlanNode.node_kind,
                PlanNode.deleted_at,
                ActivityDetail.completion_policy,
            )
            .outerjoin(ActivityDetail, col(ActivityDetail.node_id) == col(PlanNode.id))
            .where(col(PlanNode.id) == node_id, col(PlanNode.owner_user_id) == owner)
        )
    ).first()
    if row is None:
        raise DomainError("ENTITY_NOT_FOUND", "Plan node was not found")
    if row.deleted_at is not None:
        raise DomainError("ENTITY_DELETED", "Plan node is deleted")
    if row.node_kind == "goal":
        return False
    if row.completion_policy not in {"recurring", "one_and_done"}:
        raise DomainError("APPEARANCE_STATE_INVALID", "Activity policy is unavailable")
    return row.completion_policy == "one_and_done"


async def set_node_appearance(
    session: AsyncSession, owner: int, node_id: int, appearance: ObjectAppearance
) -> None:
    one_time = await _node_policy(session, owner, node_id)
    values = await _resolved_fields(session, owner, appearance, one_time=one_time)
    row = (
        await session.execute(
            select(PlanNodeAppearance).where(
                col(PlanNodeAppearance.owner_user_id) == owner,
                col(PlanNodeAppearance.node_id) == node_id,
            )
        )
    ).scalar_one_or_none()
    if row is None:
        session.add(PlanNodeAppearance(node_id=node_id, **values.model_dump()))
    else:
        for name, value in values.model_dump().items():
            setattr(row, name, value)
    await session.flush()


async def set_metric_appearance(
    session: AsyncSession, owner: int, metric_id: int, appearance: ObjectAppearance
) -> None:
    metric = (
        await session.execute(
            select(TrackedMetric).where(
                col(TrackedMetric.id) == metric_id,
                col(TrackedMetric.owner_user_id) == owner,
            )
        )
    ).scalar_one_or_none()
    if metric is None:
        raise DomainError("ENTITY_NOT_FOUND", "Metric was not found")
    if metric.deleted_at is not None:
        raise DomainError("ENTITY_DELETED", "Metric is deleted")
    values = await _resolved_fields(session, owner, appearance, one_time=False)
    row = (
        await session.execute(
            select(MetricAppearance).where(
                col(MetricAppearance.owner_user_id) == owner,
                col(MetricAppearance.metric_id) == metric_id,
            )
        )
    ).scalar_one_or_none()
    if row is None:
        session.add(MetricAppearance(metric_id=metric_id, **values.model_dump()))
    else:
        for name, value in values.model_dump().items():
            setattr(row, name, value)
    await session.flush()
