"""Read-only receipt verification for runtime reads and physical/logical recovery."""

from sqlalchemy import inspect, text

from src.v2.challenge_receipts import validate_receipt_context
from src.v2.challenge_recovery import decode_round
from src.v2.challenge_round import ChallengeRoundHead, initial_round_head
from src.v2.challenge_sync_contract import ChallengeRestartOperation
from src.v2.encoding import parse_json
from src.v2.errors import DomainError
from src.v2.next_sync_contract import (
    NextSyncOperationResult,
    validate_task_result_binding,
)
from src.v2.schemas import SyncOperationRequest, TimerCommandResult, TimerCommandRequest


def validate_challenge_receipts(read_rows, *, owner_user_id: int | None = None):
    parameters = {} if owner_user_id is None else {"owner": owner_user_id}
    suffix = "" if owner_user_id is None else " WHERE owner_user_id=:owner"
    devices = {
        row["id"]: row
        for row in read_rows(
            "SELECT * FROM client_devices"
            + ("" if owner_user_id is None else " WHERE user_id=:owner"),
            parameters,
        )
    }
    nodes = {
        row["id"]: row
        for row in read_rows("SELECT * FROM plan_nodes" + suffix, parameters)
    }
    details = {
        row["node_id"]: row
        for row in read_rows(
            "SELECT * FROM activity_details"
            + (
                ""
                if owner_user_id is None
                else " WHERE node_id IN (SELECT id FROM plan_nodes WHERE owner_user_id=:owner)"
            ),
            parameters,
        )
    }
    rounds = {
        row["id"]: row
        for row in read_rows(
            "SELECT * FROM activity_challenge_rounds" + suffix, parameters
        )
    }
    events = {
        row["public_id"]: row
        for row in read_rows("SELECT * FROM activity_events" + suffix, parameters)
    }
    timers = {
        row["public_id"]: row
        for row in read_rows("SELECT * FROM timer_sessions" + suffix, parameters)
    }
    event_bindings = {
        row["event_id"]: row
        for row in read_rows(
            "SELECT * FROM activity_challenge_event_bindings" + suffix, parameters
        )
    }
    timer_bindings = {
        row["session_id"]: row
        for row in read_rows(
            "SELECT * FROM activity_challenge_timer_bindings" + suffix, parameters
        )
    }

    def birth(source, bindings):
        binding = bindings.get(source["id"])
        node = nodes[source["activity_node_id"]]
        if binding is None:
            return initial_round_head(node["public_id"])
        row = rounds[binding["round_id"]]
        return ChallengeRoundHead(
            activity_uuid=node["public_id"],
            round_uuid=row["public_id"],
            generation=row["generation"],
        )

    try:
        for table, timer_kind in (("sync_operations", False), ("timer_commands", True)):
            for row in read_rows(
                "SELECT * FROM "
                + table
                + ("" if owner_user_id is None else " WHERE user_id=:owner"),
                parameters,
            ):
                encoded = row.get("challenge_context_json")
                if encoded is None:
                    continue  # Frozen old receipts are not upgraded or reinterpreted.
                if (
                    type(encoded) is not str
                    or len(encoded.encode("utf-8")) > 2 * 1024 * 1024
                ):
                    raise ValueError("invalid receipt sidecar size")
                value, original = validate_receipt_context(encoded, row["request_hash"])
                device = devices[row["device_id"]]
                if (
                    device["user_id"] != row["user_id"]
                    or (value.request_kind == "timer_command") != timer_kind
                    or row["status"] == "processing"
                ):
                    raise ValueError("foreign or incomplete receipt")
                result: TimerCommandResult | NextSyncOperationResult
                if timer_kind:
                    if not isinstance(original, TimerCommandRequest) or (
                        row["command_id"],
                        row["session_public_id"],
                        row["command_sequence"],
                        row["command_type"],
                    ) != (
                        str(original.command_id),
                        str(original.session_id),
                        original.sequence,
                        original.command_type,
                    ):
                        raise ValueError("timer receipt identity changed")
                    result = TimerCommandResult.model_validate(
                        parse_json(row["result_json"])
                    )
                    if (result.command_id, result.session_id) != (
                        original.command_id,
                        original.session_id,
                    ):
                        raise ValueError("timer acknowledgement changed")
                    if result.status == "applied":
                        source = timers[str(original.session_id)]
                        if (
                            source["owner_user_id"] != row["user_id"]
                            or birth(source, timer_bindings) != value.source.head
                            or result.session is None
                            or str(result.session.session_id) != source["public_id"]
                        ):
                            raise ValueError(
                                "timer acknowledgement has no original birth"
                            )
                else:
                    if isinstance(original, TimerCommandRequest) or (
                        row["operation_id"],
                        row["entity_type"],
                        row["entity_uuid"],
                        row["action"],
                        row["base_revision"],
                    ) != (
                        str(original.operation_id),
                        original.entity_type,
                        str(original.entity_uuid),
                        original.action,
                        original.base_revision,
                    ):
                        raise ValueError("operation receipt identity changed")
                    result = NextSyncOperationResult.model_validate(
                        parse_json(row["result_json"])
                    )
                    if (
                        result.operation_id,
                        result.entity_uuid,
                        result.entity_type,
                    ) != (
                        original.operation_id,
                        original.entity_uuid,
                        original.entity_type,
                    ):
                        raise ValueError("operation acknowledgement changed")
                    if isinstance(original, SyncOperationRequest):
                        validate_task_result_binding(original, result)
                    if result.status == "applied" and isinstance(
                        original, ChallengeRestartOperation
                    ):
                        source = next(
                            item
                            for item in rounds.values()
                            if item["owner_user_id"] == row["user_id"]
                            and item["public_id"] == str(original.entity_uuid)
                        )
                        record = decode_round(
                            source,
                            nodes[source["activity_node_id"]]["public_id"],
                            device["public_id"],
                        )
                        if (
                            source["source_device_id"] != row["device_id"]
                            or record.restart_intent != original.payload
                            or record.restart_operation_uuid
                            != str(original.operation_id)
                            or result.entity != record.model_dump(mode="json")
                            or result.revision != 1
                            or value.source.head is not None
                            or value.source.legacy_initial
                        ):
                            raise ValueError(
                                "restart receipt does not prove durable creation"
                            )
                    elif (
                        result.status == "applied"
                        and original.entity_type == "activity_event"
                    ):
                        source = events[str(original.entity_uuid)]
                        recurring = (
                            details[source["activity_node_id"]]["completion_policy"]
                            == "recurring"
                        )
                        if source["owner_user_id"] != row["user_id"] or (
                            birth(source, event_bindings) != value.source.head
                            if recurring
                            else value.source.head is not None
                        ):
                            raise ValueError(
                                "fact acknowledgement has no original birth"
                            )
                if (
                    result.status != row["status"]
                    or result.error_code != row["error_code"]
                    or row["completed_at"] is None
                ):
                    raise ValueError("receipt status is not final/canonical")
    except (KeyError, ValueError, TypeError, StopIteration) as error:
        raise DomainError(
            "CHALLENGE_RECEIPT_INVALID", "Challenge receipts cannot be recovered safely"
        ) from error


def read_connection_challenge_receipts(connection, *, owner_user_id: int | None = None):
    columns = inspect(connection).get_columns("sync_operations")
    if not any(column["name"] == "challenge_context_json" for column in columns):
        return
    validate_challenge_receipts(
        lambda statement, parameters: [
            dict(row)
            for row in connection.execute(text(statement), parameters).mappings()
        ],
        owner_user_id=owner_user_id,
    )
