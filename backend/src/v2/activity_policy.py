"""Explicit completion-policy transitions, in the structural write transaction."""

from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.v2.errors import DomainError
from src.v2.models import ActivityDetail, ActivityEvent, TimerSession


async def prepare_completion_policy(
    session: AsyncSession, owner: int, detail: ActivityDetail, policy: str
) -> None:
    if detail.completion_policy == policy:
        if policy == "one_and_done" and detail.one_time_version is None:
            raise DomainError(
                "TASK_STATE_UNINITIALIZED",
                "Pre-v5 activity requires the coordinated data baseline",
            )
        return
    # Include tombstoned facts and every timer state: cancellation and soft
    # deletion do not erase history or authorize reinterpreting it as a task.
    for model in (ActivityEvent, TimerSession):
        history = await session.execute(
            select(col(model.id))
            .where(
                col(model.owner_user_id) == owner,
                col(model.activity_node_id) == detail.node_id,
            )
            .limit(1)
        )
        if history.first() is not None:
            raise DomainError(
                "COMPLETION_POLICY_LOCKED",
                "Completion policy cannot change after facts or timer history",
            )
    if detail.one_time_version not in (None, 0):
        raise DomainError(
            "COMPLETION_POLICY_LOCKED", "One-time history must be retained"
        )
    detail.one_time_version = 0 if policy == "one_and_done" else None
    detail.one_time_head_event_uuid = None
    detail.one_time_completion_event_uuid = None
