"""Private durable sidecars; never replace original request fingerprints."""

from typing import Any, Literal

from pydantic import BaseModel, ValidationError

from src.v2.challenge_sync_contract import (
    ChallengeProfile,
    ChallengeSourceContext,
    ChallengeRestartOperation,
)
from src.v2.contract_types import PublicId
from src.v2.encoding import (
    canonical_json,
    operation_hash,
    parse_json,
    timer_command_hash,
)
from src.v2.errors import DomainError
from src.v2.replica_context import ReplicaIdentity
from src.v2.schemas import SyncOperationRequest, TimerCommandRequest


class ChallengeReceiptContext(ChallengeProfile):
    request_kind: Literal["sync_operation", "timer_command"]
    server_instance_id: PublicId
    sync_epoch: PublicId
    source: ChallengeSourceContext
    original: dict[str, Any]


def require_receipt_owner(actual_owner: int, authenticated_owner: int) -> None:
    if actual_owner != authenticated_owner:
        raise DomainError(
            "SYNC_RECEIPT_INVALID", "The original receipt has invalid ownership"
        )


def receipt_context(
    original: BaseModel,
    source: ChallengeSourceContext,
    replica: ReplicaIdentity | None,
    *,
    timer: bool = False,
) -> str:
    if replica is None:
        raise DomainError(
            "INVALID_SYNC_CONTEXT",
            "Challenge profile requires captured protocol 5 context",
        )
    value = ChallengeReceiptContext(
        challenge_contract=1,
        request_kind="timer_command" if timer else "sync_operation",
        server_instance_id=replica.server_instance_id,
        sync_epoch=replica.sync_epoch,
        source=source,
        original=original.model_dump(mode="json"),
    )
    return canonical_json(value.model_dump(mode="json"))


def validate_receipt_context(encoded: str, request_hash: str):
    try:
        value = ChallengeReceiptContext.model_validate(parse_json(encoded))
        if canonical_json(value.model_dump(mode="json")) != encoded:
            raise ValueError("noncanonical sidecar")
        replica = ReplicaIdentity(value.server_instance_id, value.sync_epoch)
        original: TimerCommandRequest | ChallengeRestartOperation | SyncOperationRequest
        if value.request_kind == "timer_command":
            original = TimerCommandRequest.model_validate(value.original)
            digest = timer_command_hash(original, replica=replica)
            identity = str(original.command_id)
        else:
            original = (
                ChallengeRestartOperation.model_validate(value.original)
                if value.original.get("entity_type") == "challenge_round"
                else SyncOperationRequest.model_validate(value.original)
            )
            digest = operation_hash(original, replica=replica)
            identity = str(original.operation_id)
        if (
            digest != request_hash
            or value.source.source_uuid != identity
            or original.model_dump(mode="json") != value.original
        ):
            raise ValueError("sidecar does not prove original source/hash")
        return value, original
    except (ValueError, TypeError, ValidationError) as error:
        raise DomainError(
            "CHALLENGE_RECEIPT_INVALID",
            "Challenge receipt context cannot be recovered safely",
        ) from error


def replay_context_error(
    previous: str | None, incoming: str | None, request_hash: str
) -> str | None:
    if previous is not None:
        validate_receipt_context(previous, request_hash)
        if incoming is None:
            return "CLIENT_UPGRADE_REQUIRED"
        if previous != incoming:
            return "CHALLENGE_SOURCE_REUSED"
    elif incoming is not None:
        value, _ = validate_receipt_context(incoming, request_hash)
        if not value.source.legacy_initial:
            return "CHALLENGE_LEGACY_SOURCE_REQUIRED"
    return None
