"""Capture a day rule within the existing immutable-event savepoint."""

from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.v2.count_policy import CountDayPolicy
from src.v2.count_recovery import decode_stored_policy
from src.v2.encoding import canonical_json, parse_json
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.models import ActivityCountDay, ActivityDetail, ActivityEvent, PlanNode


async def prepare_count_day(
    session: AsyncSession,
    user_id: int,
    activity: PlanNode,
    detail: ActivityDetail,
    event: ActivityEvent,
    policy: CountDayPolicy | None,
    *,
    next_protocol: bool,
) -> ActivityCountDay | None:
    if event.event_type not in {"count_delta", "count_snapshot"}:
        return None
    stored = (
        await session.execute(
            select(ActivityCountDay).where(
                col(ActivityCountDay.activity_node_id) == activity.id,
                col(ActivityCountDay.local_date) == event.local_date,
            )
        )
    ).scalar_one_or_none()
    if stored is not None and stored.owner_user_id != user_id:
        raise DomainError("COUNT_DAY_INVALID", "Count day ownership is invalid")
    if not next_protocol:
        if stored is not None:
            raise DomainError(
                "CLIENT_UPGRADE_REQUIRED", "This count day requires protocol 5"
            )
        return None
    if policy is None:
        raise DomainError(
            "COUNT_DAY_POLICY_REQUIRED", "Original count day rule is required"
        )
    if stored is not None:
        try:
            original = CountDayPolicy(
                target_value=stored.target_value, is_countdown=stored.is_countdown
            )
        except (ValueError, TypeError) as error:
            raise DomainError(
                "COUNT_DAY_INVALID", "Original count day rule is invalid"
            ) from error
        first = await session.get(ActivityEvent, stored.first_event_id)
        if (
            first is None
            or first.owner_user_id != user_id
            or first.activity_node_id != activity.id
            or first.local_date != event.local_date
            or first.deleted_at is not None
            or first.event_type not in {"count_delta", "count_snapshot"}
            or first.count_policy_json is None
        ):
            raise DomainError(
                "COUNT_DAY_INVALID", "Original count day proof is invalid"
            )
        try:
            if decode_stored_policy(first.count_policy_json) != original:
                raise ValueError("day proof mismatch")
        except (ValueError, TypeError) as error:
            raise DomainError(
                "COUNT_DAY_INVALID", "Original count day proof is invalid"
            ) from error
        if policy != original:
            raise DomainError(
                "COUNT_DAY_POLICY_CONFLICT",
                "This business date already has another count rule",
                conflict=True,
            )
    else:
        # Even undone legacy facts mean this day has begun; never fabricate its original target.
        prior = await session.execute(
            select(ActivityEvent.count_policy_json)
            .where(
                col(ActivityEvent.activity_node_id) == activity.id,
                col(ActivityEvent.local_date) == event.local_date,
                col(ActivityEvent.event_type).in_(("count_delta", "count_snapshot")),
            )
            .order_by(col(ActivityEvent.count_policy_json).is_(None))
            .limit(1)
        )
        previous = prior.first()
        if previous is not None:
            if previous[0] is not None:
                raise DomainError(
                    "COUNT_DAY_INVALID", "Original count day rule is missing"
                )
            raise DomainError(
                "COUNT_DAY_POLICY_UNKNOWN",
                "Existing count history has no original day rule",
            )
        if (
            detail.target_value != policy.target_value
            or detail.is_countdown != policy.is_countdown
        ):
            raise DomainError(
                "COUNT_START_CONFIG_CHANGED",
                "Original count configuration changed",
                conflict=True,
            )
    event.count_policy_json = canonical_json(policy.model_dump(mode="json"))
    return stored


async def persist_count_day(
    session: AsyncSession, event: ActivityEvent, stored: ActivityCountDay | None
) -> None:
    if event.count_policy_json is None or stored is not None:
        return
    policy = CountDayPolicy.model_validate(parse_json(event.count_policy_json))
    session.add(
        ActivityCountDay(
            owner_user_id=event.owner_user_id,
            activity_node_id=event.activity_node_id,
            local_date=event.local_date,
            target_value=policy.target_value,
            is_countdown=policy.is_countdown,
            first_event_id=require_internal(event.id, "ActivityEvent.id"),
        )
    )
    await (
        session.flush()
    )  # Unique activity/date elects one rule, including concurrent writers.
