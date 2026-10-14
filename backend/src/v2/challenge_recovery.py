"""Read-only durable lineage validation, including tombstones and old facts.

Unbound legacy facts are not assigned to the current head. They can only be
interpreted as the explicit initial baseline by coordinated future consumers.
"""

from collections.abc import Mapping, Sequence
from typing import Any

from sqlalchemy import text
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.v2.challenge_round import (
    ChallengeRoundHead,
    ChallengeRoundRecord,
    rebuild_challenge_history,
)
from src.v2.encoding import canonical_json, parse_json
from src.v2.one_time_recovery import ReadRows
from src.v2.challenge_models import (
    ActivityChallengeRound,
    ActivityChallengeTimerBinding,
)
from src.v2.models import TimerSession
from src.v2.errors import DomainError


class ChallengeRecoveryError(ValueError):
    pass


def decode_round(
    row: Mapping[str, Any], activity_uuid: str, device_uuid: str | None
) -> ChallengeRoundRecord:
    encoded = row["restart_intent_json"]
    intent = None
    if encoded is not None:
        if type(encoded) is not str or len(encoded) > 1024:
            raise ValueError("invalid restart evidence")
        intent = parse_json(encoded)
    record = ChallengeRoundRecord.model_validate(
        {
            "head": {
                "activity_uuid": activity_uuid,
                "round_uuid": row["public_id"],
                "generation": row["generation"],
            },
            "source_device_uuid": device_uuid,
            "restart_operation_uuid": row["restart_operation_uuid"],
            "restart_intent": intent,
        }
    )
    if (
        encoded is not None
        and canonical_json(
            record.restart_intent.model_dump(mode="json")
            if record.restart_intent
            else None
        )
        != encoded
    ):
        raise ValueError("noncanonical restart evidence")
    return record


def validate_challenge_rows(
    nodes: Sequence[Mapping[str, Any]],
    devices: Sequence[Mapping[str, Any]],
    rounds: Sequence[Mapping[str, Any]],
    heads: Sequence[Mapping[str, Any]],
    events: Sequence[Mapping[str, Any]],
    timers: Sequence[Mapping[str, Any]],
    event_bindings: Sequence[Mapping[str, Any]],
    timer_bindings: Sequence[Mapping[str, Any]],
) -> dict[int, tuple[ChallengeRoundHead, tuple[ChallengeRoundRecord, ...]]]:
    try:
        parents = {row["id"]: row for row in nodes}
        sources = {row["id"]: row for row in devices}
        by_round = {row["id"]: row for row in rounds}
        facts = {row["id"]: row for row in events}
        sessions = {row["id"]: row for row in timers}
        completed_sessions: dict[int, list[Mapping[str, Any]]] = {}
        for timer in timers:
            if timer["completed_event_id"] is not None:
                completed_sessions.setdefault(timer["completed_event_id"], []).append(
                    timer
                )
        if any(
            len(index) != len(rows)
            for index, rows in (
                (parents, nodes),
                (sources, devices),
                (by_round, rounds),
                (facts, events),
                (sessions, timers),
            )
        ):
            raise ValueError("duplicate internal identity")
        histories: dict[int, list[ChallengeRoundRecord]] = {}
        used_sources: set[tuple[int, str]] = set()
        for row in rounds:
            parent = parents[row["activity_node_id"]]
            device = (
                sources[row["source_device_id"]]
                if row["source_device_id"] is not None
                else None
            )
            if (
                parent["node_kind"] != "activity"
                or parent["owner_user_id"] != row["owner_user_id"]
                or (device is not None and device["user_id"] != row["owner_user_id"])
            ):
                raise ValueError("foreign round origin")
            record = decode_round(
                row, parent["public_id"], device["public_id"] if device else None
            )
            if device is not None:
                if record.restart_operation_uuid is None:
                    raise ValueError("restart source has no operation identity")
                source = (device["id"], record.restart_operation_uuid)
                if source in used_sources:
                    raise ValueError("reused restart operation")
                used_sources.add(source)
            histories.setdefault(parent["id"], []).append(record)
        result = {}
        seen_heads: set[int] = set()
        for row in heads:
            parent = parents[row["activity_node_id"]]
            round_row = by_round[row["round_id"]]
            if (
                parent["id"] in seen_heads
                or row["owner_user_id"] != parent["owner_user_id"]
                or (round_row["owner_user_id"], round_row["activity_node_id"])
                != (row["owner_user_id"], parent["id"])
            ):
                raise ValueError("foreign or duplicate round head")
            records = histories[parent["id"]]
            head = rebuild_challenge_history(parent["public_id"], records)
            if (round_row["public_id"], round_row["generation"]) != (
                head.round_uuid,
                head.generation,
            ):
                raise ValueError("head does not prove the complete latest chain")
            result[parent["id"]] = (
                head,
                tuple(sorted(records, key=lambda record: record.head.generation)),
            )
            seen_heads.add(parent["id"])
        if seen_heads != histories.keys():
            raise ValueError("round history has no complete head")

        def validate_bindings(rows, originals, key):
            bindings: dict[int, Mapping[str, Any]] = {}
            for row in rows:
                original = originals[row[key]]
                round_row = by_round[row["round_id"]]
                identity = (row["owner_user_id"], row["activity_node_id"])
                if (
                    row[key] in bindings
                    or identity
                    != (original["owner_user_id"], original["activity_node_id"])
                    or identity
                    != (round_row["owner_user_id"], round_row["activity_node_id"])
                ):
                    raise ValueError("foreign or duplicate birth binding")
                bindings[row[key]] = row
            return bindings

        fact_rounds = validate_bindings(event_bindings, facts, "event_id")
        timer_rounds = validate_bindings(timer_bindings, sessions, "session_id")
        for event_id, binding in fact_rounds.items():
            fact = facts[event_id]
            if fact["one_time_expected_version"] is not None:
                raise ValueError("one-time fact cannot have a challenge birth")
            if fact["event_type"] == "duration_session":
                matching = completed_sessions.get(event_id, [])
                if (
                    len(matching) != 1
                    or matching[0]["id"] not in timer_rounds
                    or matching[0]["state"] != "completed"
                    or timer_rounds[matching[0]["id"]]["round_id"]
                    != binding["round_id"]
                ):
                    raise ValueError("duration birth has no complete original session")
            reverted_id = fact["reverts_event_id"]
            if reverted_id is not None:
                original = facts[reverted_id]
                original_binding = fact_rounds.get(reverted_id)
                if (
                    (original["owner_user_id"], original["activity_node_id"])
                    != (binding["owner_user_id"], binding["activity_node_id"])
                    or (
                        original_binding is None
                        and by_round[binding["round_id"]]["generation"] != 0
                    )
                    or (
                        original_binding is not None
                        and original_binding["round_id"] != binding["round_id"]
                    )
                ):
                    raise ValueError("undo changed the original birth")
        for session_id, binding in timer_rounds.items():
            timer = sessions[session_id]
            event_id = timer["completed_event_id"]
            if timer["state"] == "completed":
                event_binding = fact_rounds[event_id]
                fact = facts[event_id]
                if (
                    event_binding["round_id"] != binding["round_id"]
                    or fact["event_type"] != "duration_session"
                    or fact["public_id"] != timer["public_id"]
                ):
                    raise ValueError("completion did not inherit timer birth")
            elif event_id is not None:
                raise ValueError(
                    "unfinished or cancelled timer has completion evidence"
                )
        return result
    except (ValueError, KeyError, TypeError) as error:
        raise ChallengeRecoveryError("CHALLENGE_HISTORY_INVALID") from error


def read_challenge_history(
    read_rows: ReadRows,
    *,
    owner_user_id: int | None = None,
    activity_node_id: int | None = None,
):
    where: list[str] = []
    parameters: dict[str, int] = {}
    if owner_user_id is not None:
        where.append("owner_user_id=:owner")
        parameters["owner"] = owner_user_id
    if activity_node_id is not None:
        where.append("activity_node_id=:activity")
        parameters["activity"] = activity_node_id
    suffix = " WHERE " + " AND ".join(where) if where else ""
    node_suffix = suffix.replace("activity_node_id", "id")
    device_suffix = " WHERE user_id=:owner" if owner_user_id is not None else ""
    return validate_challenge_rows(
        read_rows("SELECT * FROM plan_nodes" + node_suffix, parameters),
        read_rows("SELECT * FROM client_devices" + device_suffix, parameters),
        read_rows("SELECT * FROM activity_challenge_rounds" + suffix, parameters),
        read_rows("SELECT * FROM activity_challenge_heads" + suffix, parameters),
        read_rows("SELECT * FROM activity_events" + suffix, parameters),
        read_rows("SELECT * FROM timer_sessions" + suffix, parameters),
        read_rows(
            "SELECT * FROM activity_challenge_event_bindings" + suffix, parameters
        ),
        read_rows(
            "SELECT * FROM activity_challenge_timer_bindings" + suffix, parameters
        ),
    )


def read_connection_challenges(
    connection: Connection,
    *,
    owner_user_id: int | None = None,
    activity_node_id: int | None = None,
):
    return read_challenge_history(
        lambda statement, parameters: [
            dict(row)
            for row in connection.execute(text(statement), parameters).mappings()
        ],
        owner_user_id=owner_user_id,
        activity_node_id=activity_node_id,
    )


async def require_roundless_view(session: AsyncSession, owner_user_id: int) -> None:
    """Current v4/v5 readers cannot silently fall back to all-history progress.

    A positive round requires the explicit challenge-aware profile rather than
    the legacy v4/v5 reader. This
    read-only check never flushes caller work and does not replace admission.
    """
    with session.no_autoflush:
        positive = await session.execute(
            select(col(ActivityChallengeRound.id))
            .where(
                col(ActivityChallengeRound.owner_user_id) == owner_user_id,
                col(ActivityChallengeRound.generation) > 0,
            )
            .limit(1)
        )
    if positive.first() is not None:
        raise DomainError(
            "CLIENT_UPGRADE_REQUIRED",
            "This account requires the coordinated challenge-round protocol",
        )


async def require_challenge_history(session: AsyncSession, owner_user_id: int) -> None:
    try:
        await session.run_sync(
            lambda current: read_connection_challenges(
                current.connection(), owner_user_id=owner_user_id
            )
        )
    except ChallengeRecoveryError as error:
        raise DomainError(
            "CHALLENGE_HISTORY_INVALID",
            "Challenge birth evidence cannot be recovered safely",
        ) from error


async def require_roundless_timer(
    session: AsyncSession, owner_user_id: int, session_uuid: str
) -> None:
    """Allow legacy baseline terminals, not new commands on a positive birth."""
    with session.no_autoflush:
        source = await session.execute(
            select(col(TimerSession.id))
            .join(
                ActivityChallengeTimerBinding,
                col(ActivityChallengeTimerBinding.session_id) == col(TimerSession.id),
            )
            .join(
                ActivityChallengeRound,
                col(ActivityChallengeRound.id)
                == col(ActivityChallengeTimerBinding.round_id),
            )
            .where(
                col(TimerSession.owner_user_id) == owner_user_id,
                col(TimerSession.public_id) == session_uuid,
                col(ActivityChallengeRound.generation) > 0,
            )
            .limit(1)
        )
    if source.first() is not None:
        raise DomainError(
            "CLIENT_UPGRADE_REQUIRED",
            "This timer requires its original challenge context",
        )
