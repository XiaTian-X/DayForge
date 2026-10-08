"""Explicit challenge-aware v5 routes; never activate or loosen formal v4."""

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy.ext.asyncio import AsyncSession

from src.auth.dependencies import get_current_user
from src.auth.models import User
from src.database import get_session
from src.v2.challenge_profile_service import read_challenge_metadata
from src.v2.challenge_sync_contract import (
    RoundSyncPushRequest,
    RoundSyncPushResponse,
    RoundSyncPullResponse,
    RoundSyncBootstrapResponse,
    RoundTimerCommandBatchRequest,
    RoundTimerCommandBatchResponse,
    RoundActiveTimerResponse,
)
from src.v2.encoding import canonical_json
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.read_service import bootstrap, pull_changes
from src.v2.replica_context import (
    ReplicaIdentity,
    PROTOCOL_HEADER,
    INSTANCE_HEADER,
    EPOCH_HEADER,
)
from src.v2.router import _request_replica, _http_error
from src.v2.service import process_push
from src.v2.timer_service import (
    process_timer_commands,
    get_active_timer,
    get_timer_status,
)

PROFILE_DOCS = {
    "x-dayforge-v5-challenge-profile": {
        "challenge_contract": 1,
        "requires_actual_server_version": 5,
        "requires_registered_device_version": 5,
        "activation": "guarded-not-default",
        "contract": "contracts/next/openapi.json",
    },
    "parameters": [
        {
            "name": name,
            "in": "header",
            "required": True,
            "schema": {
                "type": "string",
                **({"enum": ["5"]} if name == PROTOCOL_HEADER else {"format": "uuid"}),
            },
        }
        for name in (PROTOCOL_HEADER, INSTANCE_HEADER, EPOCH_HEADER)
    ],
}

router = APIRouter(prefix="/api/v2", tags=["v5-challenge-profile"])


async def _admit(
    http: Request, user: User, db: AsyncSession, device: UUID
) -> ReplicaIdentity:
    if http.method == "GET" and http.query_params.getlist("challenge_contract") != [
        "1"
    ]:
        raise DomainError(
            "INVALID_CHALLENGE_CONTEXT", "A single challenge_contract=1 is required"
        )
    replica = await _request_replica(http, user, db, str(device))
    if replica is None:
        raise DomainError(
            "CLIENT_UPGRADE_REQUIRED",
            "Challenge contract requires actual server and registered device protocol 5",
        )
    return replica


def _batch_size(request) -> None:
    if (
        len(canonical_json(request.model_dump(mode="json")).encode("utf-8"))
        > 1024 * 1024
    ):
        raise HTTPException(
            413,
            detail={"code": "BATCH_TOO_LARGE", "message": "Sync batch exceeds 1 MiB"},
        )


@router.post(
    "/sync/rounds/push",
    response_model=RoundSyncPushResponse,
    openapi_extra=PROFILE_DOCS,
)
async def push(
    request: RoundSyncPushRequest,
    http: Request,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_session, scope="function"),
) -> RoundSyncPushResponse:
    _batch_size(request)
    try:
        replica = await _admit(http, user, db, request.device_id)
        response = await process_push(
            user, request, db, next_protocol=True, replica=replica
        )
        events = {
            str(result.entity_uuid)
            for result in response.results
            if result.entity_type == "activity_event"
            and result.status in {"applied", "already_applied"}
        }
        metadata = await read_challenge_metadata(
            db, require_internal(user.id, "User.id"), events=events, timers=set()
        )
        return RoundSyncPushResponse.model_validate(
            {**response.model_dump(mode="json"), **metadata.model_dump(mode="json")}
        )
    except DomainError as error:
        raise _http_error(error) from error


@router.get(
    "/sync/rounds/bootstrap",
    response_model=RoundSyncBootstrapResponse,
    openapi_extra=PROFILE_DOCS,
)
async def full_snapshot(
    http: Request,
    device_id: UUID,
    challenge_contract: str = Query(..., pattern="^1$"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_session, scope="function"),
) -> RoundSyncBootstrapResponse:
    try:
        await _admit(http, user, db, device_id)
        response = await bootstrap(
            user, str(device_id), db, next_protocol=True, round_profile=True
        )
        events = {
            str(change.entity_uuid)
            for change in response.changes
            if change.entity_type == "activity_event"
        }
        metadata = await read_challenge_metadata(
            db, require_internal(user.id, "User.id"), events=events
        )
        return RoundSyncBootstrapResponse.model_validate(
            {**response.model_dump(mode="json"), **metadata.model_dump(mode="json")}
        )
    except DomainError as error:
        raise _http_error(error) from error


@router.get(
    "/sync/rounds/changes",
    response_model=RoundSyncPullResponse,
    openapi_extra=PROFILE_DOCS,
)
async def changes(
    http: Request,
    device_id: UUID,
    challenge_contract: str = Query(..., pattern="^1$"),
    cursor: int = Query(0, ge=0),
    limit: int = Query(500, ge=1, le=1000),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_session, scope="function"),
) -> RoundSyncPullResponse:
    try:
        await _admit(http, user, db, device_id)
        response = await pull_changes(
            user,
            str(device_id),
            cursor,
            limit,
            db,
            next_protocol=True,
            round_profile=True,
        )
        ids = {
            str(change.entity_uuid)
            for change in response.changes
            if change.entity_type == "activity_event"
        }
        metadata = await read_challenge_metadata(
            db, require_internal(user.id, "User.id"), events=ids, timers=ids
        )
        return RoundSyncPullResponse.model_validate(
            {**response.model_dump(mode="json"), **metadata.model_dump(mode="json")}
        )
    except DomainError as error:
        raise _http_error(error) from error


@router.post(
    "/timers/rounds/commands",
    response_model=RoundTimerCommandBatchResponse,
    openapi_extra=PROFILE_DOCS,
)
async def commands(
    request: RoundTimerCommandBatchRequest,
    http: Request,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_session, scope="function"),
) -> RoundTimerCommandBatchResponse:
    _batch_size(request)
    try:
        replica = await _admit(http, user, db, request.device_id)
        response = await process_timer_commands(
            user, request, db, next_protocol=True, replica=replica
        )
        ids = {
            str(result.session_id)
            for result in response.results
            if result.session is not None
        }
        metadata = await read_challenge_metadata(
            db, require_internal(user.id, "User.id"), events=ids, timers=ids
        )
        return RoundTimerCommandBatchResponse.model_validate(
            {**response.model_dump(mode="json"), **metadata.model_dump(mode="json")}
        )
    except DomainError as error:
        raise _http_error(error) from error


@router.get(
    "/timers/rounds/active",
    response_model=RoundActiveTimerResponse,
    openapi_extra=PROFILE_DOCS,
)
async def active(
    http: Request,
    device_id: UUID,
    challenge_contract: str = Query(..., pattern="^1$"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_session, scope="function"),
) -> RoundActiveTimerResponse:
    return await _timer_read(http, user, db, device_id, None)


@router.get(
    "/timers/rounds/session/{session_id}",
    response_model=RoundActiveTimerResponse,
    openapi_extra=PROFILE_DOCS,
)
async def timer_status(
    http: Request,
    session_id: UUID,
    device_id: UUID,
    challenge_contract: str = Query(..., pattern="^1$"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_session, scope="function"),
) -> RoundActiveTimerResponse:
    return await _timer_read(http, user, db, device_id, session_id)


async def _timer_read(
    http: Request, user: User, db: AsyncSession, device: UUID, identity: UUID | None
) -> RoundActiveTimerResponse:
    try:
        await _admit(http, user, db, device)
        response = (
            await get_active_timer(user, str(device), db)
            if identity is None
            else await get_timer_status(user, str(device), str(identity), db)
        )
        ids = {str(response.session.session_id)} if response.session else set()
        metadata = await read_challenge_metadata(
            db, require_internal(user.id, "User.id"), events=ids, timers=ids
        )
        return RoundActiveTimerResponse.model_validate(
            {**response.model_dump(mode="json"), **metadata.model_dump(mode="json")}
        )
    except DomainError as error:
        raise _http_error(error) from error
