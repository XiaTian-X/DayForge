"""Ordered, idempotent control plane for active duration timers."""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
from typing import Optional
from zoneinfo import ZoneInfo

from sqlalchemy import func
from sqlalchemy.engine import Result
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.auth.models import User
from src.v2.change_log import append_change
from src.v2.encoding import canonical_json, parse_json
from src.v2.entity_snapshots import serialize_activity_event_with_allocations
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.models import (
    ActivityDetail,
    ActivityEvent,
    ClientDevice,
    DurationDayAllocation,
    PlanNode,
    TimerCommand,
    TimerSegment,
    TimerSession,
    utc_now,
)
from src.v2.schemas import (
    ActiveTimerResponse,
    TimerCommandBatchRequest,
    TimerCommandBatchResponse,
    TimerCommandRequest,
    TimerCommandResult,
    TimerHeartbeatResponse,
    TimerSessionResponse,
)
from src.v2.device_service import require_device
from src.v2.time_utils import as_utc, local_date_at


ACTIVE_STATES = ("running", "paused")
MAX_TIMER_SECONDS = 24 * 60 * 60
MAX_FUTURE_SKEW = timedelta(minutes=5)


def _request_hash(command: TimerCommandRequest) -> str:
    import hashlib

    payload = canonical_json(command.model_dump(mode="json")).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


async def _get_session(
    db: AsyncSession,
    user_id: int,
    public_id: str,
) -> Optional[TimerSession]:
    result = await db.execute(
        select(TimerSession).where(
            col(TimerSession.owner_user_id) == user_id,
            col(TimerSession.public_id) == public_id,
        )
    )
    return result.scalar_one_or_none()


async def _get_active_session(db: AsyncSession, user_id: int) -> Optional[TimerSession]:
    result = await db.execute(
        select(TimerSession).where(
            col(TimerSession.owner_user_id) == user_id,
            col(TimerSession.state).in_(ACTIVE_STATES),
        )
    )
    return result.scalar_one_or_none()


async def serialize_timer_session(
    db: AsyncSession, timer: TimerSession
) -> TimerSessionResponse:
    activity = await db.get(PlanNode, timer.activity_node_id)
    controller = await db.get(ClientDevice, timer.controller_device_id)
    completed_event = (
        await db.get(ActivityEvent, timer.completed_event_id)
        if timer.completed_event_id is not None
        else None
    )
    if activity is None or controller is None:
        raise RuntimeError("timer session references missing activity or controller")
    return TimerSessionResponse.model_validate(
        {
            "session_id": timer.public_id,
            "activity_uuid": activity.public_id,
            "state": timer.state,
            "controller_device_id": controller.public_id,
            "control_generation": timer.control_generation,
            "revision": timer.revision,
            "next_command_sequence": timer.next_command_sequence,
            "started_at": timer.started_at,
            "state_changed_at": timer.state_changed_at,
            "ended_at": timer.ended_at,
            "timezone": timer.timezone,
            "is_countdown": timer.is_countdown,
            "target_seconds": timer.target_seconds,
            "max_duration_seconds": timer.max_duration_seconds,
            "active_elapsed_ms": timer.active_elapsed_ms,
            "last_heartbeat_at": timer.last_heartbeat_at,
            "completed_event_id": completed_event.public_id
            if completed_event
            else None,
        }
    )


def _validate_command_time(command: TimerCommandRequest) -> datetime:
    occurred_at = as_utc(command.occurred_at)
    if occurred_at > utc_now() + MAX_FUTURE_SKEW:
        raise DomainError("CLOCK_SKEW", "Timer command is too far in the future")
    return occurred_at


async def _next_segment_sequence(db: AsyncSession, timer_id: int) -> int:
    result: Result[tuple[int]] = await db.execute(
        select(func.coalesce(func.max(col(TimerSegment.sequence)), 0)).where(
            col(TimerSegment.session_id) == timer_id
        )
    )
    return int(result.scalar_one()) + 1


async def _open_segment(
    db: AsyncSession, timer: TimerSession, started_at: datetime
) -> None:
    db.add(
        TimerSegment(
            session_id=timer.id,
            sequence=await _next_segment_sequence(
                db, require_internal(timer.id, "TimerSession.id")
            ),
            started_at=started_at,
        )
    )
    await db.flush()


async def _close_segment(
    db: AsyncSession,
    timer: TimerSession,
    ended_at: datetime,
    *,
    clamp_to_remaining: bool = False,
    expected_active_elapsed_ms: Optional[int] = None,
) -> TimerSegment:
    result = await db.execute(
        select(TimerSegment).where(
            col(TimerSegment.session_id) == timer.id,
            col(TimerSegment.ended_at).is_(None),
        )
    )
    segment = result.scalar_one_or_none()
    if segment is None:
        raise DomainError("TIMER_SEGMENT_MISSING", "Running timer has no open segment")
    started_at = as_utc(segment.started_at)
    if ended_at < started_at:
        raise DomainError(
            "COMMAND_TIME_REVERSED", "Timer command predates the running segment"
        )
    duration_ms = int((ended_at - started_at).total_seconds() * 1000)
    if expected_active_elapsed_ms is not None:
        duration_ms = expected_active_elapsed_ms - timer.active_elapsed_ms
        if duration_ms < 0:
            raise DomainError(
                "ACTIVE_TIME_REVERSED", "Active elapsed time moved backwards"
            )
        ended_at = started_at + timedelta(milliseconds=duration_ms)
    if clamp_to_remaining:
        remaining = max(0, timer.max_duration_seconds * 1000 - timer.active_elapsed_ms)
        duration_ms = min(duration_ms, remaining)
        ended_at = started_at + timedelta(milliseconds=duration_ms)
    segment.ended_at = ended_at
    segment.duration_ms = duration_ms
    timer.active_elapsed_ms += duration_ms
    return segment


def _max_duration_seconds(activity: ActivityDetail) -> tuple[int, int]:
    target = int(Decimal(activity.target_value))
    if target <= 0:
        return 0, MAX_TIMER_SECONDS
    if activity.is_countdown:
        return target, min(target, MAX_TIMER_SECONDS)
    return target, min(target * 3, MAX_TIMER_SECONDS)


async def _start(
    db: AsyncSession,
    user: User,
    device: ClientDevice,
    command: TimerCommandRequest,
    occurred_at: datetime,
) -> TimerSession:
    if (
        await _get_session(
            db, require_internal(user.id, "User.id"), str(command.session_id)
        )
        is not None
    ):
        raise DomainError(
            "TIMER_SESSION_EXISTS", "Timer session UUID already exists", conflict=True
        )
    active = await _get_active_session(db, require_internal(user.id, "User.id"))
    if active is not None:
        raise DomainError(
            "ACTIVE_TIMER_EXISTS",
            "The account already has an active timer",
            conflict=True,
        )
    node_result = await db.execute(
        select(PlanNode, ActivityDetail)
        .join(ActivityDetail, col(ActivityDetail.node_id) == col(PlanNode.id))
        .where(
            col(PlanNode.owner_user_id) == user.id,
            col(PlanNode.public_id) == str(command.activity_uuid),
            col(PlanNode.deleted_at).is_(None),
            col(PlanNode.status) == "active",
        )
    )
    row = node_result.one_or_none()
    if row is None:
        raise DomainError("ACTIVITY_NOT_FOUND", "Duration activity was not found")
    node, activity = row
    if activity.tracking_mode != "duration":
        raise DomainError(
            "INVALID_ACTIVITY_MODE", "Only duration activities can start timers"
        )
    target_seconds, max_seconds = _max_duration_seconds(activity)
    timer = TimerSession(
        public_id=str(command.session_id),
        owner_user_id=user.id,
        activity_node_id=node.id,
        state="running",
        controller_device_id=device.id,
        control_generation=1,
        revision=1,
        next_command_sequence=2,
        started_at=occurred_at,
        state_changed_at=occurred_at,
        timezone=command.timezone,
        is_countdown=activity.is_countdown,
        target_seconds=target_seconds,
        max_duration_seconds=max_seconds,
        last_heartbeat_at=utc_now(),
    )
    db.add(timer)
    try:
        await db.flush()
    except IntegrityError as exc:
        raise DomainError(
            "ACTIVE_TIMER_EXISTS",
            "The account already has an active timer",
            conflict=True,
        ) from exc
    await _open_segment(db, timer, occurred_at)
    return timer


def _validate_existing_command(
    timer: TimerSession,
    device: ClientDevice,
    command: TimerCommandRequest,
) -> None:
    if command.sequence < timer.next_command_sequence:
        raise DomainError(
            "COMMAND_SEQUENCE_USED",
            "Timer command sequence was already used",
            conflict=True,
        )
    if command.sequence > timer.next_command_sequence:
        raise DomainError(
            "MISSING_PREDECESSOR",
            "A previous timer command has not arrived yet",
            conflict=True,
        )
    if (
        command.expected_revision is not None
        and command.expected_revision != timer.revision
    ):
        raise DomainError(
            "REVISION_CONFLICT", "Timer session revision changed", conflict=True
        )
    if command.expected_control_generation != timer.control_generation:
        raise DomainError(
            "CONTROL_LOST", "Timer control generation changed", conflict=True
        )
    if command.command_type != "takeover" and timer.controller_device_id != device.id:
        raise DomainError(
            "CONTROL_LOST", "This device does not control the timer", conflict=True
        )
    if timer.state not in ACTIVE_STATES:
        raise DomainError(
            "TIMER_NOT_ACTIVE", "Timer session is no longer active", conflict=True
        )


async def _create_allocations(
    db: AsyncSession,
    timer: TimerSession,
    event: ActivityEvent,
) -> None:
    zone = ZoneInfo(timer.timezone)
    result = await db.execute(
        select(TimerSegment)
        .where(
            col(TimerSegment.session_id) == timer.id,
            col(TimerSegment.ended_at).is_not(None),
        )
        .order_by(col(TimerSegment.sequence))
    )
    totals: dict[date, int] = {}
    for segment in result.scalars().all():
        cursor = as_utc(segment.started_at)
        end = as_utc(require_internal(segment.ended_at, "closed TimerSegment.ended_at"))
        while cursor < end:
            local_day = cursor.astimezone(zone).date()
            next_midnight = datetime.combine(
                local_day + timedelta(days=1),
                time.min,
                tzinfo=zone,
            ).astimezone(timezone.utc)
            chunk_end = min(end, next_midnight)
            duration_ms = int((chunk_end - cursor).total_seconds() * 1000)
            if duration_ms > 0:
                totals[local_day] = totals.get(local_day, 0) + duration_ms
            cursor = chunk_end
    for local_day, duration_ms in totals.items():
        db.add(
            DurationDayAllocation(
                activity_event_id=event.id,
                local_date=local_day,
                timezone=timer.timezone,
                duration_ms=duration_ms,
            )
        )


async def _complete(
    db: AsyncSession,
    user: User,
    device: ClientDevice,
    timer: TimerSession,
    command: TimerCommandRequest,
    occurred_at: datetime,
) -> None:
    if timer.state == "running":
        await _close_segment(
            db,
            timer,
            occurred_at,
            clamp_to_remaining=True,
            expected_active_elapsed_ms=command.active_elapsed_ms,
        )
    if (
        timer.target_seconds > 0
        and timer.active_elapsed_ms < timer.target_seconds * 1000
    ):
        raise DomainError(
            "TIMER_TARGET_NOT_REACHED", "Timer target has not been reached"
        )
    if timer.active_elapsed_ms > timer.max_duration_seconds * 1000:
        raise DomainError("TIMER_DURATION_LIMIT", "Timer exceeded its maximum duration")

    event = ActivityEvent(
        public_id=timer.public_id,
        owner_user_id=user.id,
        activity_node_id=timer.activity_node_id,
        event_type="duration_session",
        duration_seconds=timer.active_elapsed_ms // 1000,
        duration_milliseconds=timer.active_elapsed_ms,
        started_at=timer.started_at,
        ended_at=occurred_at,
        occurred_at=occurred_at,
        local_date=local_date_at(timer.started_at, timer.timezone),
        timezone=timer.timezone,
        source_type="app",
        source_device_public_id=device.public_id,
        external_event_id=timer.public_id,
        recorded_by_user_id=user.id,
        payload_json=canonical_json({"timer_session_id": timer.public_id}),
    )
    db.add(event)
    await db.flush()
    await _create_allocations(db, timer, event)

    timer.state = "completed"
    timer.ended_at = occurred_at
    timer.completed_event_id = event.id
    entity = await serialize_activity_event_with_allocations(
        db,
        event,
        require_internal(
            await db.get(PlanNode, timer.activity_node_id),
            "TimerSession.activity_node_id",
        ).public_id,
        None,
    )
    await append_change(
        db,
        user_id=require_internal(user.id, "User.id"),
        device_id=device.id,
        operation_id=str(command.command_id),
        entity_type="activity_event",
        entity_uuid=event.public_id,
        operation="upsert",
        revision=event.revision,
        payload=entity,
    )


async def _apply_existing(
    db: AsyncSession,
    user: User,
    device: ClientDevice,
    timer: TimerSession,
    command: TimerCommandRequest,
    occurred_at: datetime,
) -> TimerSession:
    _validate_existing_command(timer, device, command)
    if occurred_at < as_utc(timer.state_changed_at):
        raise DomainError(
            "COMMAND_TIME_REVERSED", "Timer command predates the current state"
        )

    if command.command_type == "pause":
        if timer.state != "running":
            raise DomainError(
                "INVALID_TIMER_TRANSITION", "Only a running timer can be paused"
            )
        await _close_segment(
            db,
            timer,
            occurred_at,
            clamp_to_remaining=True,
            expected_active_elapsed_ms=command.active_elapsed_ms,
        )
        timer.state = "paused"
    elif command.command_type == "resume":
        if timer.state != "paused":
            raise DomainError(
                "INVALID_TIMER_TRANSITION", "Only a paused timer can be resumed"
            )
        await _open_segment(db, timer, occurred_at)
        timer.state = "running"
    elif command.command_type == "stop":
        await _complete(db, user, device, timer, command, occurred_at)
    elif command.command_type == "cancel":
        if timer.state == "running":
            await _close_segment(
                db,
                timer,
                occurred_at,
                expected_active_elapsed_ms=command.active_elapsed_ms,
            )
        timer.state = "cancelled"
        timer.ended_at = occurred_at
    elif command.command_type == "takeover":
        if timer.controller_device_id == device.id:
            raise DomainError(
                "ALREADY_CONTROLLER", "This device already controls the timer"
            )
        if timer.state == "running":
            # The new controller cannot attest to the old controller's monotonic
            # elapsed time. Use the server-observable wall-clock interval, but
            # preserve the session's hard duration invariant so an offline
            # takeover can never leave a timer that is impossible to stop.
            await _close_segment(db, timer, occurred_at, clamp_to_remaining=True)
            await _open_segment(db, timer, occurred_at)
        timer.controller_device_id = require_internal(device.id, "ClientDevice.id")
        timer.control_generation += 1
    else:
        raise DomainError("INVALID_TIMER_COMMAND", "Unsupported timer command")

    timer.revision += 1
    timer.next_command_sequence += 1
    timer.state_changed_at = occurred_at
    timer.updated_at = utc_now()
    timer.last_heartbeat_at = utc_now()
    await db.flush()
    return timer


async def _apply_command(
    db: AsyncSession,
    user: User,
    device: ClientDevice,
    command: TimerCommandRequest,
) -> TimerSession:
    occurred_at = _validate_command_time(command)
    if command.command_type == "start":
        return await _start(db, user, device, command, occurred_at)
    timer = await _get_session(
        db, require_internal(user.id, "User.id"), str(command.session_id)
    )
    if timer is None:
        raise DomainError("TIMER_NOT_FOUND", "Timer session was not found")
    return await _apply_existing(db, user, device, timer, command, occurred_at)


async def process_timer_commands(
    user: User,
    request: TimerCommandBatchRequest,
    db: AsyncSession,
) -> TimerCommandBatchResponse:
    device = await require_device(
        require_internal(user.id, "User.id"), str(request.device_id), db
    )
    results: list[TimerCommandResult] = []

    for command in request.commands:
        request_hash = _request_hash(command)
        previous_result = await db.execute(
            select(TimerCommand).where(
                col(TimerCommand.device_id) == device.id,
                col(TimerCommand.command_id) == str(command.command_id),
            )
        )
        previous = previous_result.scalar_one_or_none()
        if previous is not None:
            if previous.request_hash != request_hash:
                results.append(
                    TimerCommandResult(
                        command_id=command.command_id,
                        session_id=command.session_id,
                        status="rejected",
                        error_code="COMMAND_ID_REUSED",
                        message="command_id was already used with a different request",
                    )
                )
            else:
                stored = parse_json(previous.result_json)
                if stored.get("status") == "applied":
                    stored["status"] = "already_applied"
                results.append(TimerCommandResult.model_validate(stored))
            continue

        record = TimerCommand(
            user_id=user.id,
            device_id=device.id,
            command_id=str(command.command_id),
            session_public_id=str(command.session_id),
            command_sequence=command.sequence,
            command_type=command.command_type,
            request_hash=request_hash,
            status="processing",
        )
        persist_result = True
        try:
            async with db.begin_nested():
                db.add(record)
                await db.flush()
        except IntegrityError:
            raise DomainError(
                "OPERATION_IN_PROGRESS", "The same timer command is being processed"
            )

        try:
            async with db.begin_nested():
                timer = await _apply_command(db, user, device, command)
                snapshot = await serialize_timer_session(db, timer)
            result = TimerCommandResult(
                command_id=command.command_id,
                session_id=command.session_id,
                status="applied",
                session=snapshot,
            )
            record.status = "applied"
        except DomainError as exc:
            current = await _get_session(
                db, require_internal(user.id, "User.id"), str(command.session_id)
            )
            failure_snapshot = (
                await serialize_timer_session(db, current) if current else None
            )
            result = TimerCommandResult(
                command_id=command.command_id,
                session_id=command.session_id,
                status="conflict" if exc.conflict else "rejected",
                error_code=exc.code,
                message=exc.message,
                session=failure_snapshot,
            )
            record.status = result.status
            record.error_code = exc.code

            # Arrival before a predecessor is a transport-ordering condition,
            # not a permanent rejection. Remove the idempotency reservation so
            # the exact same immutable command can succeed after its predecessor.
            if exc.code == "MISSING_PREDECESSOR":
                persist_result = False
                await db.delete(record)

        if persist_result:
            record.result_json = canonical_json(result.model_dump(mode="json"))
            record.completed_at = utc_now()
        results.append(result)

    await db.flush()
    return TimerCommandBatchResponse(results=results, server_time=utc_now())


async def get_active_timer(
    user: User,
    device_public_id: str,
    db: AsyncSession,
) -> ActiveTimerResponse:
    await require_device(require_internal(user.id, "User.id"), device_public_id, db)
    timer = await _get_active_session(db, require_internal(user.id, "User.id"))
    return ActiveTimerResponse(
        session=await serialize_timer_session(db, timer) if timer else None,
        server_time=utc_now(),
    )


async def get_timer_status(
    user: User,
    device_public_id: str,
    session_public_id: str,
    db: AsyncSession,
) -> ActiveTimerResponse:
    """Return an account-scoped session snapshot for explicit client recovery."""
    await require_device(require_internal(user.id, "User.id"), device_public_id, db)
    timer = await _get_session(
        db, require_internal(user.id, "User.id"), session_public_id
    )
    return ActiveTimerResponse(
        session=await serialize_timer_session(db, timer) if timer else None,
        server_time=utc_now(),
    )


async def heartbeat_timer(
    user: User,
    device_public_id: str,
    session_public_id: str,
    control_generation: int,
    db: AsyncSession,
) -> TimerHeartbeatResponse:
    device = await require_device(
        require_internal(user.id, "User.id"), device_public_id, db
    )
    timer = await _get_session(
        db, require_internal(user.id, "User.id"), session_public_id
    )
    if timer is None:
        raise DomainError("TIMER_NOT_FOUND", "Timer session was not found")
    if timer.state not in ACTIVE_STATES:
        raise DomainError(
            "TIMER_NOT_ACTIVE", "Timer session is no longer active", conflict=True
        )
    if (
        timer.controller_device_id != device.id
        or timer.control_generation != control_generation
    ):
        raise DomainError(
            "CONTROL_LOST", "This device no longer controls the timer", conflict=True
        )
    timer.last_heartbeat_at = utc_now()
    timer.updated_at = utc_now()
    await db.flush()
    return TimerHeartbeatResponse(
        accepted=True,
        session=await serialize_timer_session(db, timer),
        server_time=utc_now(),
    )


__all__ = [
    "get_active_timer",
    "get_timer_status",
    "heartbeat_timer",
    "process_timer_commands",
    "serialize_timer_session",
]
