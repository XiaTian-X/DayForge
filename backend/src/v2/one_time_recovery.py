"""Validate durable one-time history using one caller-owned database snapshot.

The same portable row validator serves future bootstrap and backup/restore. It
does not infer old task states, mutate rows, authenticate callers or open a second
connection. UUIDs are account-scoped even when multiple accounts share them.
"""

from collections.abc import Callable, Mapping, Sequence
from typing import Any

from pydantic import ValidationError
from sqlalchemy import text
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import AsyncSession

from src.v2.errors import DomainError
from src.v2.one_time import OneTimeIntent, OneTimeState, OneTimeTransitionError
from src.v2.one_time_sync import (
    OneTimeEventProof,
    OneTimeProjection,
    rebuild_one_time_history,
)

Row = Mapping[str, Any]
ReadRows = Callable[[str, dict[str, int]], Sequence[Row]]

# Standard SELECT/JOIN/NULL predicates; no SQLite-specific domain rules. Keeping
# these projections shared also avoids a weaker separate backup-only validator.
ACTIVITIES_SQL = """
SELECT n.id AS node_id, n.owner_user_id, n.public_id AS activity_uuid,
       n.node_kind, n.deleted_at AS node_deleted_at,
       d.completion_policy, d.tracking_mode, d.one_time_version,
       d.one_time_head_event_uuid, d.one_time_completion_event_uuid
FROM activity_details d JOIN plan_nodes n ON n.id = d.node_id
"""
EVENTS_SQL = """
SELECT e.* FROM activity_events e
JOIN plan_nodes n ON n.id = e.activity_node_id
"""


class OneTimeRecoveryError(ValueError):
    """A stable machine code, without leaking rows from another account."""


def validate_one_time_rows(
    activities: Sequence[Row],
    events: Sequence[Row],
    *,
    require_initialized: bool,
) -> list[OneTimeProjection]:
    nodes = {row["node_id"]: row for row in activities}
    if len(nodes) != len(activities):
        raise OneTimeRecoveryError("TASK_STATE_DIVERGED")
    checkpoints: dict[int, OneTimeProjection] = {}
    histories: dict[int, list[OneTimeEventProof]] = {}
    try:
        for node_id, row in nodes.items():
            if row["node_kind"] != "activity":
                raise OneTimeRecoveryError("TASK_ACTIVITY_MISMATCH")
            version = row["one_time_version"]
            if version is None:
                if (
                    row["one_time_head_event_uuid"] is not None
                    or row["one_time_completion_event_uuid"] is not None
                ):
                    raise OneTimeRecoveryError("TASK_STATE_DIVERGED")
                if require_initialized and row["completion_policy"] == "one_and_done":
                    raise OneTimeRecoveryError("TASK_STATE_UNINITIALIZED")
                continue  # Explicit legacy boundary, not a guessed empty state.
            if (
                row["completion_policy"] != "one_and_done"
                or row["tracking_mode"] != "check"
            ):
                raise OneTimeRecoveryError("TASK_ACTIVITY_MISMATCH")
            checkpoints[node_id] = OneTimeProjection(
                activity_uuid=row["activity_uuid"],
                state=OneTimeState(
                    version=version,
                    head_event_uuid=row["one_time_head_event_uuid"],
                    completion_event_uuid=row["one_time_completion_event_uuid"],
                ),
            )
            histories[node_id] = []

        by_id = {row["id"]: row for row in events}
        if len(by_id) != len(events):
            raise OneTimeRecoveryError("TASK_EVENT_ID_REUSED")
        for event in events:
            node_id = event["activity_node_id"]
            node = nodes.get(node_id)
            if node is None or node["owner_user_id"] != event["owner_user_id"]:
                raise OneTimeRecoveryError("TASK_ACTIVITY_MISMATCH")
            expected = event["one_time_expected_version"]
            if expected is None:
                if node_id in checkpoints:
                    raise OneTimeRecoveryError("TASK_HISTORY_INCOMPLETE")
                if event["one_time_expected_head_event_uuid"] is not None:
                    raise OneTimeRecoveryError("TASK_STATE_DIVERGED")
                continue
            if node_id not in checkpoints:
                raise OneTimeRecoveryError("TASK_ACTIVITY_MISMATCH")
            if event["deleted_at"] is not None:
                raise OneTimeRecoveryError("TASK_HISTORY_INCOMPLETE")
            revert = by_id.get(event["reverts_event_id"])
            if event["reverts_event_id"] is not None and (
                revert is None
                or revert["owner_user_id"] != event["owner_user_id"]
                or revert["activity_node_id"] != node_id
                or revert["deleted_at"] is not None
            ):
                raise OneTimeRecoveryError("TASK_ACTIVITY_MISMATCH")
            reverts_uuid = revert["public_id"] if revert is not None else None
            intent = OneTimeIntent(
                event_uuid=event["public_id"],
                action="complete" if event["event_type"] == "check_in" else "undo",
                expected_version=expected,
                expected_head_event_uuid=event["one_time_expected_head_event_uuid"],
                reverts_event_uuid=reverts_uuid,
            )
            histories[node_id].append(
                OneTimeEventProof(
                    public_id=event["public_id"],
                    activity_uuid=node["activity_uuid"],
                    event_type=event["event_type"],
                    reverts_event_uuid=reverts_uuid,
                    one_time=intent,
                    one_time_state_after=OneTimeState(
                        version=expected + 1,
                        head_event_uuid=event["public_id"],
                        completion_event_uuid=event["public_id"]
                        if event["event_type"] == "check_in"
                        else None,
                    ),
                )
            )
        for node_id, checkpoint in checkpoints.items():
            rebuild_one_time_history(checkpoint, histories[node_id])
    except OneTimeTransitionError as error:
        raise OneTimeRecoveryError(str(error)) from error
    except ValidationError as error:
        raise OneTimeRecoveryError("TASK_STATE_DIVERGED") from error
    return list(checkpoints.values())


def read_one_time_history(
    read_rows: ReadRows,
    *,
    owner_user_id: int | None = None,
    require_initialized: bool = False,
) -> list[OneTimeProjection]:
    """Read both projections from the SAME transaction/connection.

    Account bootstrap excludes tombstoned parents, while a full backup also
    validates retained history under tombstones. Do not filter events by their
    self-declared owner: a cross-account FK mismatch must be detected, not hidden.
    """
    suffix = ""
    parameters: dict[str, int] = {}
    if owner_user_id is not None:
        suffix = " WHERE n.owner_user_id = :owner AND n.deleted_at IS NULL"
        parameters["owner"] = owner_user_id
    activities = read_rows(ACTIVITIES_SQL + suffix, parameters)
    events = read_rows(EVENTS_SQL + suffix, parameters)
    return validate_one_time_rows(
        activities, events, require_initialized=require_initialized
    )


def read_connection_history(
    connection: Connection,
    *,
    owner_user_id: int | None = None,
    require_initialized: bool = False,
) -> list[OneTimeProjection]:
    def read_rows(statement: str, parameters: dict[str, int]) -> Sequence[Row]:
        return [
            dict(row)
            for row in connection.execute(text(statement), parameters).mappings()
        ]

    return read_one_time_history(
        read_rows,
        owner_user_id=owner_user_id,
        require_initialized=require_initialized,
    )


async def read_one_time_checkpoints(
    session: AsyncSession, owner_user_id: int
) -> list[OneTimeProjection]:
    """For a future authenticated bootstrap, in its existing snapshot transaction."""
    try:
        return await session.run_sync(
            lambda current: read_connection_history(
                current.connection(),
                owner_user_id=owner_user_id,
                require_initialized=True,
            )
        )
    except OneTimeRecoveryError as error:
        raise DomainError(
            str(error), "One-time history cannot be recovered safely"
        ) from error
