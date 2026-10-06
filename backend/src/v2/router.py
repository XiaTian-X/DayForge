"""HTTP endpoints for device management and the v2 sync protocol."""

from uuid import UUID

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import Field, TypeAdapter, ValidationError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import col, select

from src.auth.dependencies import get_current_user
from src.auth.models import User
from src.database import get_session
from src.v2.models import ClientDevice
from src.v2.schemas import (
    ActiveTimerResponse,
    DeviceRegisterRequest,
    DeviceEditingUpdate,
    DeviceResponse,
    ServerIdentityResponse,
    SyncBootstrapResponse,
    SyncPullResponse,
    SyncPushRequest,
    SyncPushResponse,
    TimerCommandBatchRequest,
    TimerCommandBatchResponse,
    TimerHeartbeatRequest,
    TimerHeartbeatResponse,
)
from src.v2.encoding import canonical_json
from src.v2.errors import DomainError
from src.v2.invariants import require_internal
from src.v2.service import process_push
from src.v2.read_service import bootstrap, pull_changes
from src.v2.device_service import (
    make_primary,
    register_device,
    revoke,
    set_structural_editing,
    to_device_response,
)
from src.v2.system_service import server_identity_response
from src.v2.next_sync_contract import (
    NextDeviceRegisterRequest,
    NextSyncPushRequest,
    NextSyncPushResponse,
    NextSyncPullResponse,
    NextSyncBootstrapResponse,
)
from src.v2.protocol_admission import require_sync_protocol
from src.v2.replica_context import (
    EPOCH_HEADER,
    INSTANCE_HEADER,
    PROTOCOL_HEADER,
    ReplicaIdentity,
)
from src.v2.timer_service import (
    get_active_timer,
    get_timer_status,
    heartbeat_timer,
    process_timer_commands,
)


router = APIRouter(prefix="/api/v2", tags=["v2-sync"])

_LEGACY_PAGE_LIMIT: TypeAdapter[int] = TypeAdapter(Annotated[int, Field(ge=1, le=500)])


def _next_contract(request_model: str | None, response_model: str) -> dict:
    """Document guarded alternatives without a permissive response-model union."""
    return {
        "x-dayforge-protocol-5": {
            "contract": "contracts/next/openapi.json",
            "request_model": request_model,
            "response_model": response_model,
            "required_headers": [PROTOCOL_HEADER, INSTANCE_HEADER, EPOCH_HEADER],
            "requires_actual_server_version": 5,
        }
    }


async def _request_replica(
    http: Request, user: User, session: AsyncSession, device_id: str | None
) -> ReplicaIdentity | None:
    return await require_sync_protocol(
        session,
        user,
        device_id,
        tuple(http.headers.getlist(PROTOCOL_HEADER)),
        tuple(http.headers.getlist(INSTANCE_HEADER)),
        tuple(http.headers.getlist(EPOCH_HEADER)),
    )


def _http_error(error: DomainError) -> HTTPException:
    code_to_status = {
        "DEVICE_NOT_FOUND": status.HTTP_404_NOT_FOUND,
        "DEVICE_REVOKED": status.HTTP_403_FORBIDDEN,
        "INVALID_CURSOR": status.HTTP_400_BAD_REQUEST,
        "OPERATION_IN_PROGRESS": status.HTTP_409_CONFLICT,
        "CLIENT_UPGRADE_REQUIRED": status.HTTP_426_UPGRADE_REQUIRED,
    }
    return HTTPException(
        status_code=code_to_status.get(
            error.code,
            status.HTTP_409_CONFLICT if error.conflict else status.HTTP_400_BAD_REQUEST,
        ),
        detail={"code": error.code, "message": error.message},
    )


@router.get("/system/identity", response_model=ServerIdentityResponse)
async def get_server_identity(
    session: AsyncSession = Depends(get_session, scope="function"),
) -> ServerIdentityResponse:
    """Return the non-secret identity used to verify local/proxy failover."""
    return await server_identity_response(session)


@router.post(
    "/devices/register",
    response_model=DeviceResponse,
    openapi_extra=_next_contract("NextDeviceRegisterRequest", "DeviceResponse"),
)
async def register_client_device(
    request: DeviceRegisterRequest,
    http: Request,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> DeviceResponse:
    try:
        replica = await _request_replica(http, current_user, session, None)
        if replica is not None:
            # The legacy model coerces "5" / 5.0 to 5. Validate the cached ORIGINAL
            # JSON, not its normalized DTO, before publishing a registration proof.
            try:
                request = NextDeviceRegisterRequest.model_validate(await http.json())
            except ValidationError as error:
                raise HTTPException(
                    status_code=422, detail={"code": "INVALID_SYNC_INPUT"}
                ) from error
        return await register_device(current_user, request, session)
    except DomainError as error:
        raise _http_error(error) from error


@router.get("/devices", response_model=list[DeviceResponse])
async def list_client_devices(
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> list[DeviceResponse]:
    result = await session.execute(
        select(ClientDevice)
        .where(
            col(ClientDevice.user_id) == current_user.id,
            col(ClientDevice.revoked_at).is_(None),
        )
        .order_by(col(ClientDevice.last_seen_at).desc())
    )
    return [
        await to_device_response(session, device) for device in result.scalars().all()
    ]


async def _owned_active_device(
    session: AsyncSession,
    user_id: int,
    device_id: UUID,
) -> ClientDevice:
    result = await session.execute(
        select(ClientDevice).where(
            col(ClientDevice.user_id) == user_id,
            col(ClientDevice.public_id) == str(device_id),
            col(ClientDevice.revoked_at).is_(None),
        )
    )
    device = result.scalar_one_or_none()
    if device is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "DEVICE_NOT_FOUND", "message": "Device was not found"},
        )
    return device


@router.post("/devices/{device_id}/make-primary", response_model=DeviceResponse)
async def make_device_primary(
    device_id: UUID,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> DeviceResponse:
    device = await _owned_active_device(
        session, require_internal(current_user.id, "User.id"), device_id
    )
    try:
        await make_primary(session, device)
    except ValueError as error:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail=str(error)
        ) from error
    return await to_device_response(session, device)


@router.patch("/devices/{device_id}/editing", response_model=DeviceResponse)
async def update_device_editing(
    device_id: UUID,
    request: DeviceEditingUpdate,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> DeviceResponse:
    device = await _owned_active_device(
        session, require_internal(current_user.id, "User.id"), device_id
    )
    try:
        await set_structural_editing(session, device, request.structural_edit_enabled)
    except ValueError as error:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail=str(error)
        ) from error
    return await to_device_response(session, device)


@router.delete("/devices/{device_id}", status_code=status.HTTP_204_NO_CONTENT)
async def revoke_client_device(
    device_id: UUID,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> None:
    device = await _owned_active_device(
        session, require_internal(current_user.id, "User.id"), device_id
    )
    await revoke(session, device)


@router.post(
    "/sync/push",
    response_model=SyncPushResponse,
    openapi_extra=_next_contract("NextSyncPushRequest", "NextSyncPushResponse"),
)
async def push_sync_operations(
    request: SyncPushRequest,
    http: Request,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> SyncPushResponse | JSONResponse:
    if (
        len(canonical_json(request.model_dump(mode="json")).encode("utf-8"))
        > 1024 * 1024
    ):
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail={"code": "BATCH_TOO_LARGE", "message": "Sync batch exceeds 1 MiB"},
        )
    try:
        replica = await _request_replica(
            http, current_user, session, str(request.device_id)
        )
        if replica is not None:
            # Envelopes have the same fields; domain validation still follows
            # exact replay inside the per-operation savepoint.
            next_request = NextSyncPushRequest.model_validate(
                request.model_dump(mode="json")
            )
            response = await process_push(
                current_user,
                next_request,
                session,
                next_protocol=True,
                replica=replica,
            )
            validated = NextSyncPushResponse.model_validate(
                response.model_dump(mode="json")
            )
            # Do not let the v4 serializer drop separate task-conflict fields.
            # Validation/serialization still precede the shared final COMMIT.
            return JSONResponse(validated.model_dump(mode="json"))
        return await process_push(current_user, request, session)
    except DomainError as error:
        raise _http_error(error) from error


@router.get(
    "/sync/changes",
    response_model=SyncPullResponse,
    openapi_extra=_next_contract(None, "NextSyncPullResponse"),
)
async def get_sync_changes(
    http: Request,
    device_id: UUID,
    cursor: int = Query(default=0, ge=0),
    limit: int = Query(
        default=500,
        ge=1,
        le=1000,
        json_schema_extra={"x-dayforge-protocol-maximum": {"4": 500, "5": 1000}},
    ),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> SyncPullResponse | JSONResponse:
    try:
        replica = await _request_replica(http, current_user, session, str(device_id))
        if replica is not None:
            response = await pull_changes(
                current_user, str(device_id), cursor, limit, session, next_protocol=True
            )
            validated = NextSyncPullResponse.model_validate(
                response.model_dump(mode="json")
            )
            return JSONResponse(validated.model_dump(mode="json"))
        try:
            _LEGACY_PAGE_LIMIT.validate_python(http.query_params.get("limit", 500))
        except ValidationError as error:
            raise RequestValidationError(
                [
                    {**item, "loc": ("query", "limit", *item["loc"])}
                    for item in error.errors(include_url=False)
                ]
            ) from error
        return await pull_changes(current_user, str(device_id), cursor, limit, session)
    except DomainError as error:
        raise _http_error(error) from error


@router.get(
    "/sync/bootstrap",
    response_model=SyncBootstrapResponse,
    openapi_extra=_next_contract(None, "NextSyncBootstrapResponse"),
)
async def get_sync_bootstrap(
    http: Request,
    device_id: UUID,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> SyncBootstrapResponse | JSONResponse:
    try:
        replica = await _request_replica(http, current_user, session, str(device_id))
        if replica is not None:
            response = await bootstrap(
                current_user, str(device_id), session, next_protocol=True
            )
            validated = NextSyncBootstrapResponse.model_validate(
                response.model_dump(mode="json")
            )
            return JSONResponse(validated.model_dump(mode="json"))
        return await bootstrap(current_user, str(device_id), session)
    except DomainError as error:
        raise _http_error(error) from error


@router.post(
    "/timers/commands",
    response_model=TimerCommandBatchResponse,
    openapi_extra=_next_contract(
        "TimerCommandBatchRequest", "TimerCommandBatchResponse"
    ),
)
async def submit_timer_commands(
    request: TimerCommandBatchRequest,
    http: Request,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> TimerCommandBatchResponse:
    try:
        replica = await _request_replica(
            http, current_user, session, str(request.device_id)
        )
        if replica is not None:
            return await process_timer_commands(
                current_user, request, session, next_protocol=True, replica=replica
            )
        return await process_timer_commands(current_user, request, session)
    except DomainError as error:
        raise _http_error(error) from error


@router.get(
    "/timers/active",
    response_model=ActiveTimerResponse,
    openapi_extra=_next_contract(None, "ActiveTimerResponse"),
)
async def read_active_timer(
    http: Request,
    device_id: UUID,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> ActiveTimerResponse:
    try:
        await _request_replica(http, current_user, session, str(device_id))
        return await get_active_timer(current_user, str(device_id), session)
    except DomainError as error:
        raise _http_error(error) from error


@router.get(
    "/timers/{session_id}",
    response_model=ActiveTimerResponse,
    openapi_extra=_next_contract(None, "ActiveTimerResponse"),
)
async def read_timer_status(
    http: Request,
    session_id: UUID,
    device_id: UUID,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> ActiveTimerResponse:
    try:
        await _request_replica(http, current_user, session, str(device_id))
        return await get_timer_status(
            current_user,
            str(device_id),
            str(session_id),
            session,
        )
    except DomainError as error:
        raise _http_error(error) from error


@router.post(
    "/timers/{session_id}/heartbeat",
    response_model=TimerHeartbeatResponse,
    openapi_extra=_next_contract("TimerHeartbeatRequest", "TimerHeartbeatResponse"),
)
async def submit_timer_heartbeat(
    session_id: UUID,
    request: TimerHeartbeatRequest,
    http: Request,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> TimerHeartbeatResponse:
    try:
        await _request_replica(http, current_user, session, str(request.device_id))
        return await heartbeat_timer(
            current_user,
            str(request.device_id),
            str(session_id),
            request.control_generation,
            session,
        )
    except DomainError as error:
        raise _http_error(error) from error


__all__ = ["router"]
