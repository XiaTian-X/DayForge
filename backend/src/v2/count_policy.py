"""Immutable per-business-day counting rules; actual count values remain facts."""

from pydantic import Field, StrictBool, StrictInt

from src.v2.contract_types import ContractModel


class CountDayPolicy(ContractModel):
    target_value: StrictInt = Field(ge=1, le=2_147_483_647)
    is_countdown: StrictBool


def event_count_policy(payload: dict) -> CountDayPolicy | None:
    value = payload.get("count_policy")
    if value is None:
        return None  # Historical absence is unknown, never today's target.
    if payload.get("event_type") not in {"count_delta", "count_snapshot"}:
        raise ValueError("count policy is only valid on immutable count facts")
    if payload.get("one_time") is not None:
        raise ValueError("one-time items cannot contain count policies")
    return CountDayPolicy.model_validate(value)
