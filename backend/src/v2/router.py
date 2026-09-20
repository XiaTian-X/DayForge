"""HTTP endpoints for device management and the v2 sync protocol."""

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlmodel import select

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
from src.v2.timer_service import (
    get_active_timer,
    get_timer_status,
    heartbeat_timer,
    process_timer_commands,
)


router = APIRouter(prefix="/api/v2", tags=["v2-sync"])


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


@router.post("/devices/register", response_model=DeviceResponse)
async def register_client_device(
    request: DeviceRegisterRequest,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> DeviceResponse:
    try:
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
            ClientDevice.user_id == current_user.id, ClientDevice.revoked_at.is_(None)
        )
        .order_by(ClientDevice.last_seen_at.desc())
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
            ClientDevice.user_id == user_id,
            ClientDevice.public_id == str(device_id),
            ClientDevice.revoked_at.is_(None),
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
    device = await _owned_active_device(session, current_user.id, device_id)
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
    device = await _owned_active_device(session, current_user.id, device_id)
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
    device = await _owned_active_device(session, current_user.id, device_id)
    await revoke(session, device)


@router.post("/sync/push", response_model=SyncPushResponse)
async def push_sync_operations(
    request: SyncPushRequest,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> SyncPushResponse:
    if (
        len(canonical_json(request.model_dump(mode="json")).encode("utf-8"))
        > 1024 * 1024
    ):
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail={"code": "BATCH_TOO_LARGE", "message": "Sync batch exceeds 1 MiB"},
        )
    try:
        return await process_push(current_user, request, session)
    except DomainError as error:
        raise _http_error(error) from error


@router.get("/sync/changes", response_model=SyncPullResponse)
async def get_sync_changes(
    device_id: UUID,
    cursor: int = Query(default=0, ge=0),
    limit: int = Query(default=500, ge=1, le=500),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> SyncPullResponse:
    try:
        return await pull_changes(current_user, str(device_id), cursor, limit, session)
    except DomainError as error:
        raise _http_error(error) from error


@router.get("/sync/bootstrap", response_model=SyncBootstrapResponse)
async def get_sync_bootstrap(
    device_id: UUID,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> SyncBootstrapResponse:
    try:
        return await bootstrap(current_user, str(device_id), session)
    except DomainError as error:
        raise _http_error(error) from error


@router.post("/timers/commands", response_model=TimerCommandBatchResponse)
async def submit_timer_commands(
    request: TimerCommandBatchRequest,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> TimerCommandBatchResponse:
    try:
        return await process_timer_commands(current_user, request, session)
    except DomainError as error:
        raise _http_error(error) from error


@router.get("/timers/active", response_model=ActiveTimerResponse)
async def read_active_timer(
    device_id: UUID,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> ActiveTimerResponse:
    try:
        return await get_active_timer(current_user, str(device_id), session)
    except DomainError as error:
        raise _http_error(error) from error


@router.get("/timers/{session_id}", response_model=ActiveTimerResponse)
async def read_timer_status(
    session_id: UUID,
    device_id: UUID,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> ActiveTimerResponse:
    try:
        return await get_timer_status(
            current_user,
            str(device_id),
            str(session_id),
            session,
        )
    except DomainError as error:
        raise _http_error(error) from error


@router.post("/timers/{session_id}/heartbeat", response_model=TimerHeartbeatResponse)
async def submit_timer_heartbeat(
    session_id: UUID,
    request: TimerHeartbeatRequest,
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session, scope="function"),
) -> TimerHeartbeatResponse:
    try:
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
