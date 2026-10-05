"""Production appearance boundary, dormant until all three v5 proofs agree."""

import asyncio
from typing import Annotated

from fastapi import (
    APIRouter,
    Depends,
    Header,
    HTTPException,
    Path,
    Query,
    Request,
    Security,
)
from fastapi.security import APIKeyHeader, HTTPBearer
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from fastapi.routing import APIRoute
from pydantic import TypeAdapter, ValidationError
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.requests import ClientDisconnect

from src.appearance.input import ImageInputError
from src.appearance.png_structure import PngValidationError
from src.appearance.svg_path import SvgValidationError
from src.auth.dependencies import authenticate_header
from src.auth.models import User
from src.database import get_session
from src.storage.asset_files import AssetFileError
from src.storage.asset_io import AssetIoBusy, AssetIoClosed
from src.storage.database_adapter import DatabaseBusyError
from src.v2.asset_api_contract import (
    AppearanceCatalogPage,
    AppearanceQuota,
    AssetDeclaration,
    AssetRecord,
    AssetSyncContext,
    AssetTransferReceipt,
    PackDeclaration,
    Variant,
)
from src.v2.asset_catalog import read_catalog
from src.v2.asset_http_input import (
    AppearanceRequest,
    JSON_LIMIT,
    REQUEST_SECONDS,
    header_values,
    invalid,
    receive_bytes,
    single_header,
)
from src.v2.asset_runtime import AssetRecoveryRequired
from src.v2.asset_service import (
    declare_asset,
    declare_pack,
    read_asset,
    read_pack,
    read_quota,
)
from src.v2.contract_types import PublicId
from src.v2.errors import DomainError
from src.v2.protocol_admission import require_next_protocol, require_next_server


class AppearanceJSONResponse(JSONResponse):
    def render(self, content) -> bytes:
        data = super().render(content)
        if len(data) > JSON_LIMIT:
            raise DomainError(
                "ASSET_RESPONSE_TOO_LARGE", "Appearance response exceeds its limit"
            )
        return data


def _failure(code: str, status: int) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={
            "detail": {
                "code": code,
                "message": "Appearance request could not be completed",
            }
        },
        headers={"Retry-After": "1"} if status == 503 else None,
    )


def _domain_failure(error: DomainError) -> JSONResponse:
    code = "ASSET_CONTENT_PENDING" if error.code == "ASSET_NOT_READY" else error.code
    statuses = {
        "CLIENT_UPGRADE_REQUIRED": 426,
        "DEVICE_CAPABILITY_DENIED": 403,
        "DEVICE_NOT_FOUND": 404,
        "ASSET_NOT_FOUND": 404,
        "PACK_NOT_FOUND": 404,
        "ASSET_VARIANT_NOT_FOUND": 404,
        "ASSET_QUOTA_EXCEEDED": 413,
        "ASSET_INPUT_TOO_LARGE": 413,
        "ASSET_INPUT_INVALID": 422,
        "ASSET_VARIANT_INVALID": 422,
        "INVALID_CURSOR": 422,
        "ASSET_METADATA_CORRUPT": 503,
        "ASSET_RESPONSE_TOO_LARGE": 503,
        "ASSET_INSTALLATION_MISMATCH": 503,
    }
    return _failure(code, statuses.get(code, 409))


def _error_response(error: Exception, request: Request) -> JSONResponse | None:
    if isinstance(error, DomainError):
        return _domain_failure(error)
    if isinstance(error, RequestValidationError):
        return _failure("ASSET_INPUT_INVALID", 422)
    if isinstance(error, HTTPException):
        code = (
            error.detail.get("code", "AUTHENTICATION_REQUIRED")
            if isinstance(error.detail, dict)
            else "AUTHENTICATION_REQUIRED"
        )
        return _failure(code, error.status_code)
    if isinstance(error, DatabaseBusyError):
        return _failure("DATABASE_BUSY", 503)
    if isinstance(error, (ImageInputError, PngValidationError, SvgValidationError)):
        return (
            _failure("ASSET_CONTENT_UNAVAILABLE", 503)
            if request.method == "GET"
            else _failure("ASSET_INPUT_INVALID", 422)
        )
    if isinstance(
        error,
        (
            AssetIoBusy,
            AssetIoClosed,
            AssetRecoveryRequired,
            TimeoutError,
            AssetFileError,
            OSError,
        ),
    ):
        return _failure("ASSET_CONTENT_UNAVAILABLE", 503)
    return None


def _query(request: Request, *, catalog: bool = False) -> dict[str, str]:
    values = request.query_params.multi_items()
    allowed = {"server_instance_id", "sync_epoch", "device_id"}
    if catalog:
        allowed |= {"after", "through", "limit"}
    if len(values) != len({key for key, _ in values}) or any(
        key not in allowed for key, _ in values
    ):
        raise invalid()
    return dict(values)


def _content_context(request: Request) -> AssetSyncContext:
    try:
        return AssetSyncContext.model_validate(_query(request))
    except ValidationError as error:
        raise invalid() from error


class AppearanceRoute(APIRoute):
    """One ASGI task and admission spanning receive, COMMIT and response send."""

    def get_route_handler(self):
        original = super().get_route_handler()

        async def handler(request: Request):
            try:
                return await original(
                    AppearanceRequest(request.scope, receive=request.receive)
                )
            except RequestValidationError as error:
                # FastAPI's inner exception wrapper would otherwise echo the
                # rejected source and allocate a response for every field error.
                raise HTTPException(
                    422, detail={"code": "ASSET_INPUT_INVALID"}
                ) from error
            except HTTPException as error:
                if isinstance(error.detail, dict) and "code" in error.detail:
                    raise
                raise HTTPException(
                    error.status_code, detail={"code": "AUTHENTICATION_REQUIRED"}
                ) from error

        return handler

    async def handle(self, scope, receive, send) -> None:
        request = Request(scope, receive=receive)
        started = False

        async def tracked_send(message):
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
            await send(message)

        async def serve():
            try:
                await super(AppearanceRoute, self).handle(scope, receive, tracked_send)
            except ClientDisconnect:
                return
            except Exception as error:
                if started:
                    raise
                response = _error_response(error, request)
                if response is None:
                    # Keep admission through the 500 send too. Re-raise AFTER
                    # sending so programmer/DB failures remain observable.
                    await _failure("ASSET_INTERNAL_ERROR", 500)(
                        scope, receive, tracked_send
                    )
                    raise
                await response(scope, receive, tracked_send)

        try:
            async with asyncio.timeout(REQUEST_SECONDS):
                header = single_header(request, "authorization")
                protocols = header_values(request, "x-dayforge-protocol")
                lifecycle = request.app.state.appearance
                lifecycle.require_open()
                async with lifecycle.sessions() as session:
                    await authenticate_header(header, session, record_token_use=False)
                    await require_next_server(session, protocols)
                request.state.appearance_header = header
                request.state.appearance_protocols = protocols
                binary = "/content/" in self.path
                if binary:
                    context = _content_context(request)
                    try:
                        TypeAdapter(PublicId).validate_python(
                            request.path_params["asset_id"]
                        )
                        TypeAdapter(Variant).validate_python(
                            request.path_params["variant"]
                        )
                    except ValidationError as error:
                        raise invalid() from error
                    runtime = lifecycle.runtime
                    if runtime is None:
                        raise AssetRecoveryRequired("appearance root is not configured")
                    async with lifecycle.content() as admitted:
                        authorization = await runtime.authorize(
                            header,
                            context,
                            request.path_params["asset_id"],
                            request.path_params["variant"],
                            write=request.method == "PUT",
                            protocol_headers=protocols,
                        )
                        request.state.appearance_authorization = authorization
                        request.state.appearance_transfer = admitted
                        await serve()
                else:
                    if request.method == "PUT":
                        if request.query_params:
                            raise invalid()
                    else:
                        _query(request, catalog=self.path.endswith("/catalog"))
                    async with lifecycle.metadata():
                        await serve()
        except ClientDisconnect:
            return
        except Exception as error:
            if started:
                raise
            response = _error_response(error, request)
            if response is None:
                raise
        else:
            return
        await response(scope, receive, send)


router = APIRouter(
    prefix="/api/v2/appearance",
    tags=["v5-appearance"],
    route_class=AppearanceRoute,
    default_response_class=AppearanceJSONResponse,
    responses={
        401: {"description": "Authentication required"},
        403: {"description": "Device capability denied"},
        404: {"description": "Owned identity or declared variant not found"},
        409: {
            "description": "Immutable conflict, stale context or pending content; inspect detail.code"
        },
        413: {"description": "Input or account quota limit exceeded"},
        422: {"description": "Invalid input or path binding"},
        426: {
            "description": "Coordinated v5 activation and explicit device/request proofs required"
        },
        503: {
            "description": "Temporarily unavailable; retain original identity and retry with backoff"
        },
    },
    dependencies=[
        Security(HTTPBearer(auto_error=False, scheme_name="AppearanceBearer")),
        Security(
            APIKeyHeader(
                name="Authorization", auto_error=False, scheme_name="AppearanceApiToken"
            )
        ),
    ],
)


Session = Annotated[AsyncSession, Depends(get_session, scope="function")]


async def actor(request: Request, session: Session) -> User:
    return await authenticate_header(request.state.appearance_header, session)


Actor = Annotated[User, Depends(actor)]
Protocol = Annotated[str, Header(alias="X-DayForge-Protocol", pattern="^5$")]
Id = Annotated[PublicId, Path()]
Revision = Annotated[int, Path(ge=1, le=2_147_483_647)]


async def context(
    server_instance_id: Annotated[PublicId, Query()],
    sync_epoch: Annotated[PublicId, Query()],
    device_id: Annotated[PublicId, Query()],
) -> AssetSyncContext:
    return AssetSyncContext(
        server_instance_id=server_instance_id,
        sync_epoch=sync_epoch,
        device_id=device_id,
    )


Context = Annotated[AssetSyncContext, Depends(context)]


async def authorize(
    request: Request, session: AsyncSession, user: User, scope: AssetSyncContext
) -> None:
    await require_next_protocol(
        session, user, scope.device_id, request.state.appearance_protocols
    )


@router.put("/assets/{asset_id}", response_model=AssetRecord)
async def put_asset(
    asset_id: Id,
    body: AssetDeclaration,
    request: Request,
    session: Session,
    user: Actor,
    x_protocol: Protocol,
):
    if body.asset.asset_id != asset_id:
        raise invalid()
    await authorize(request, session, user, body.context)
    return await declare_asset(session, user, body)


@router.get("/assets/{asset_id}", response_model=AssetRecord)
async def get_asset(
    asset_id: Id,
    request: Request,
    scope: Context,
    session: Session,
    user: Actor,
    x_protocol: Protocol,
):
    await authorize(request, session, user, scope)
    return await read_asset(session, user, scope, asset_id)


@router.put("/packs/{pack_id}/versions/{revision}", response_model=PackDeclaration)
async def put_pack(
    pack_id: Id,
    revision: Revision,
    body: PackDeclaration,
    request: Request,
    session: Session,
    user: Actor,
    x_protocol: Protocol,
):
    if body.pack.pack_id != pack_id or body.pack.revision != revision:
        raise invalid()
    await authorize(request, session, user, body.context)
    return await declare_pack(session, user, body)


@router.get("/packs/{pack_id}/versions/{revision}", response_model=PackDeclaration)
async def get_pack(
    pack_id: Id,
    revision: Revision,
    request: Request,
    scope: Context,
    session: Session,
    user: Actor,
    x_protocol: Protocol,
):
    await authorize(request, session, user, scope)
    return await read_pack(session, user, scope, pack_id, revision)


@router.get("/quota", response_model=AppearanceQuota)
async def get_quota(
    request: Request,
    scope: Context,
    session: Session,
    user: Actor,
    x_protocol: Protocol,
):
    await authorize(request, session, user, scope)
    return await read_quota(session, user, scope)


@router.get("/catalog", response_model=AppearanceCatalogPage)
async def get_catalog(
    request: Request,
    scope: Context,
    session: Session,
    user: Actor,
    x_protocol: Protocol,
    after: Annotated[int, Query(ge=0, le=9_223_372_036_854_775_807)] = 0,
    through: Annotated[int | None, Query(ge=0, le=9_223_372_036_854_775_807)] = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 100,
):
    await authorize(request, session, user, scope)
    return await read_catalog(
        session,
        user,
        scope,
        after=after,
        through=through,
        limit=limit,
        max_response_bytes=JSON_LIMIT,
    )


@router.put(
    "/assets/{asset_id}/content/{variant}",
    response_model=AssetTransferReceipt,
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                media: {"schema": {"type": "string", "format": "binary"}}
                for media in ("application/octet-stream", "image/png", "image/svg+xml")
            },
        }
    },
)
async def put_content(
    asset_id: Id,
    variant: Variant,
    request: Request,
    scope: Context,
    x_protocol: Protocol,
):
    binding = request.state.appearance_authorization
    data = await receive_bytes(
        request,
        binding.blob.byte_length,
        media=("application/octet-stream", binding.blob.media_type),
    )
    if len(data) != binding.blob.byte_length:
        raise invalid()
    return await request.state.appearance_transfer.install(
        request.state.appearance_header,
        scope,
        asset_id,
        variant,
        data,
        protocol_headers=request.state.appearance_protocols,
        authorization=binding,
    )


@router.get(
    "/assets/{asset_id}/content/{variant}",
    response_class=Response,
    responses={
        200: {
            "content": {
                media: {"schema": {"type": "string", "format": "binary"}}
                for media in ("image/png", "image/svg+xml")
            }
        }
    },
)
async def get_content(
    asset_id: Id,
    variant: Variant,
    request: Request,
    scope: Context,
    x_protocol: Protocol,
):
    data = await request.state.appearance_transfer.read(
        request.state.appearance_header,
        scope,
        asset_id,
        variant,
        protocol_headers=request.state.appearance_protocols,
        authorization=request.state.appearance_authorization,
    )
    return Response(
        data,
        media_type=request.state.appearance_authorization.blob.media_type,
        headers={
            "Cache-Control": "private, no-store",
            "X-Content-Type-Options": "nosniff",
        },
    )


class AppearanceNoRedirect:
    """Do not redirect credentialed material requests; legacy routes are unchanged."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if (
            scope["type"] == "http"
            and scope["path"].startswith("/api/v2/appearance/")
            and scope["path"].endswith("/")
        ):
            await _failure("ASSET_NOT_FOUND", 404)(scope, receive, send)
        else:
            await self.app(scope, receive, send)
