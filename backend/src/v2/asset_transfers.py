"""Inactive two-phase asset service; no v4 route or application lifecycle wiring."""

from dataclasses import dataclass
from io import BytesIO
import logging

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlmodel import col, select

from src.appearance.input import ImageInputError
from src.appearance.profiles import IMAGE_PROFILES
from src.auth.dependencies import authenticate_header
from src.auth.models import User
from src.storage.asset_files import AssetFiles, InstallReceipt
from src.storage.asset_io import AssetIoPool
from src.storage.transactions import storage_transaction
from src.time_utils import utc_now
from src.v2.appearance import IconBlob
from src.v2.asset_access import require_asset_access
from src.v2.asset_api_contract import AssetSyncContext, AssetTransferReceipt, Variant
from src.v2.asset_models import AccountIconBlob
from src.v2.asset_records import blob_ready, blob_value
from src.v2.asset_service import read_asset
from src.v2.errors import DomainError


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class _Binding:
    owner_public_id: str
    blob: IconBlob


@dataclass(frozen=True)
class InstalledTransfer:
    """Committed acknowledgement plus internal cleanup state, not a wire model."""

    receipt: AssetTransferReceipt
    cleanup_pending: bool


class AssetTransfers:
    """The transport provides immutable, already bounded bytes, never a path.

    This internal entry point does not implement HTTP body admission/deadlines.
    Frozen bytes let cancellation leave an owned worker alive without closing a
    request-owned file beneath it. DB sessions never cross into that worker.
    Files/pool/session factory belong to the application, not each request.
    """

    def __init__(
        self,
        sessions: async_sessionmaker[AsyncSession],
        files: AssetFiles,
        pool: AssetIoPool,
    ):
        self.sessions = sessions
        self.files = files
        self.pool = pool

    async def _binding(
        self,
        session: AsyncSession,
        header: str | None,
        context: AssetSyncContext,
        asset_id: str,
        variant: Variant,
        *,
        write: bool,
        record_use: bool,
    ) -> tuple[_Binding, AccountIconBlob]:
        user = await authenticate_header(header, session, record_token_use=record_use)
        owner = await require_asset_access(session, user, context, write=write)
        record = await read_asset(session, user, context, asset_id)
        if variant not in {"light", "dark"}:
            raise DomainError("ASSET_VARIANT_INVALID", "Unknown asset variant")
        expected = record.asset.light if variant == "light" else record.asset.dark
        if expected is None:
            raise DomainError("ASSET_VARIANT_NOT_FOUND", "This variant is not declared")
        row = (
            await session.execute(
                select(AccountIconBlob).where(
                    col(AccountIconBlob.owner_user_id) == owner,
                    col(AccountIconBlob.sha256) == expected.sha256,
                )
            )
        ).scalar_one_or_none()
        if row is None or blob_value(row) != expected:
            raise DomainError(
                "ASSET_METADATA_CORRUPT", "Stored asset metadata is inconsistent"
            )
        blob_ready(row)
        return _Binding(user.public_id, expected), row

    @staticmethod
    def _same_binding(before: _Binding, after: _Binding) -> None:
        if before != after:
            raise DomainError(
                "ASSET_METADATA_CHANGED",
                "The account or immutable blob changed during transfer",
            )

    async def install(
        self,
        header: str | None,
        context: AssetSyncContext,
        asset_id: str,
        variant: Variant,
        data: bytes,
    ) -> InstalledTransfer:
        if type(data) is not bytes:
            raise TypeError("asset transfer requires immutable bytes")
        if len(data) > 2_097_152:
            raise ImageInputError("IMAGE_BYTE_LENGTH")
        async with self.sessions() as initial:
            before, _ = await self._binding(
                initial,
                header,
                context,
                asset_id,
                variant,
                write=True,
                record_use=False,
            )

        # The first snapshot is closed. Even a pending source's slow image
        # validation and fsync do not hold a database transaction or writer lock.
        def publish() -> InstallReceipt:
            with BytesIO(data) as source:
                return self.files.publish(before.owner_public_id, source, before.blob)

        installed = await self.pool.run(publish)
        if (
            installed.owner_public_id != before.owner_public_id
            or installed.blob != before.blob
            or installed.profile != IMAGE_PROFILES[before.blob.media_type]
        ):
            raise DomainError(
                "ASSET_INSTALLATION_MISMATCH",
                "File receipt does not match the authorized blob",
            )
        async with storage_transaction(self.sessions) as final:
            after, row = await self._binding(
                final, header, context, asset_id, variant, write=True, record_use=True
            )
            self._same_binding(before, after)
            if not blob_ready(row):
                row.ready_at = utc_now()
                row.validation_profile = installed.profile
                await final.flush()
        # Reaching here proves COMMIT, not just flush. A cancelled/failed commit
        # leaves its intent: recovery must inspect DB state, never guess rollback.
        cleanup_pending = False
        try:
            await self.pool.run(lambda: self.files.finish(installed))
        except Exception:
            cleanup_pending = True
            # Already committed bytes remain available. No credentials, original
            # exception text or user filenames are written to diagnostics.
            logger.warning(
                "Asset installation cleanup deferred",
                extra={"operation_id": installed.operation_id},
            )
        return InstalledTransfer(
            AssetTransferReceipt(
                context=context, asset_id=asset_id, variant=variant, blob=before.blob
            ),
            cleanup_pending,
        )

    async def read(
        self,
        header: str | None,
        context: AssetSyncContext,
        asset_id: str,
        variant: Variant,
    ) -> bytes:
        async with self.sessions() as initial:
            before, row = await self._binding(
                initial,
                header,
                context,
                asset_id,
                variant,
                write=False,
                record_use=False,
            )
            if not blob_ready(row):
                raise DomainError(
                    "ASSET_NOT_READY", "Asset bytes have not been installed"
                )
        profile = IMAGE_PROFILES[before.blob.media_type]
        data = await self.pool.run(
            lambda: self.files.read(before.owner_public_id, before.blob, profile)
        )
        async with storage_transaction(self.sessions) as final:
            after, row = await self._binding(
                final, header, context, asset_id, variant, write=False, record_use=True
            )
            self._same_binding(before, after)
            if not blob_ready(row):
                raise DomainError(
                    "ASSET_NOT_READY", "Asset bytes are no longer installed"
                )
        return data

    async def recover(self, installed: InstallReceipt) -> str:
        """Reconcile one canonical intent before admitting asset requests.

        This does not authenticate a user or publish ready. The startup/CLI
        coordinator must bound enumeration, report failures and keep recovery
        exclusive of uploads/restores. Unknown ownership preserves evidence.
        """
        async with self.sessions() as session:
            owner = (
                await session.execute(
                    select(User.id).where(
                        col(User.public_id) == installed.owner_public_id
                    )
                )
            ).scalar_one_or_none()
            row = (
                None
                if owner is None
                else (
                    await session.execute(
                        select(AccountIconBlob).where(
                            col(AccountIconBlob.owner_user_id) == owner,
                            col(AccountIconBlob.sha256) == installed.blob.sha256,
                        )
                    )
                ).scalar_one_or_none()
            )
            if row is None:
                raise DomainError(
                    "ASSET_RECOVERY_ORPHAN",
                    "Installation no longer has a matching account declaration",
                )
            if blob_value(row) != installed.blob:
                raise DomainError(
                    "ASSET_METADATA_CHANGED",
                    "Installation and declared blob no longer match",
                )
            committed = blob_ready(row)

        def finish() -> None:
            if committed:
                self.files.read(
                    installed.owner_public_id, installed.blob, installed.profile
                )
            self.files.finish(installed)

        await self.pool.run(finish)
        return "committed" if committed else "pending"
