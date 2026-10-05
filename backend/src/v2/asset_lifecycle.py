"""Application-owned resources. Material faults never take down fact/timer routes."""

import asyncio
from contextlib import asynccontextmanager
import logging
from pathlib import Path

from src.storage.asset_files import AssetFiles
from src.storage.asset_io import AssetIoBusy, AssetIoClosed, AssetIoPool
from src.storage.transactions import SessionFactory
from src.v2.asset_runtime import AssetRuntime, AssetRecoveryRequired
from src.v2.asset_transfers import AssetTransfers


logger = logging.getLogger(__name__)


class AppearanceLifecycle:
    def __init__(self, sessions: SessionFactory, root: Path | None):
        self.sessions = sessions
        self.parser = AssetIoPool(2)
        self.runtime = (
            AssetRuntime(AssetTransfers(sessions, AssetFiles(root), AssetIoPool(2)))
            if root is not None
            else None
        )
        self._active = 0
        self._idle = asyncio.Event()
        self._idle.set()
        self._wake = asyncio.Event()
        self.startup_finished = asyncio.Event()
        self.available = asyncio.Event()
        self._closing = False
        self._close_task: asyncio.Task[None] | None = None
        self._recovery = (
            asyncio.create_task(self._recover()) if self.runtime is not None else None
        )

    async def _recover(self) -> None:
        assert self.runtime is not None
        delay = 1
        while not self._closing:
            self.available.clear()
            try:
                await self.runtime.recover()
            except Exception:
                # Owned background recovery is fault-isolated, visibly unavailable,
                # and retried with a bound. Request/programming errors are not hidden.
                logger.warning("Appearance recovery deferred; content unavailable")
                self.available.clear()
                self.startup_finished.set()
                # A coalesced request cannot bypass backoff or start another scan.
                await self._wait_retry(delay)
                delay = min(delay * 2, 60)
            else:
                # Rejections during this scan/backoff are already covered by
                # the successful reconciliation, not a reason for another scan.
                self._wake.clear()
                self.available.set()
                self.startup_finished.set()
                delay = 1
                await self._wake.wait()

    async def _wait_retry(self, seconds: int) -> None:
        await asyncio.sleep(seconds)

    def require_open(self) -> None:
        if self._closing:
            raise AssetIoClosed("appearance is closing")

    @asynccontextmanager
    async def metadata(self):
        self.require_open()
        if self._active >= 2:
            raise AssetIoBusy("appearance request capacity is exhausted")
        self._active += 1
        self._idle.clear()
        try:
            yield
        finally:
            self._active -= 1
            if not self._active:
                self._idle.set()

    @asynccontextmanager
    async def content(self):
        self.require_open()
        if self.runtime is None:
            raise AssetRecoveryRequired("appearance root is not configured")
        try:
            async with self.runtime.request() as admitted:
                yield admitted
        finally:
            if not self.runtime.ready:
                self.available.clear()
                self._wake.set()

    async def _finish_close(self) -> None:
        try:
            if self._recovery is not None:
                self._recovery.cancel()
                try:
                    await self._recovery
                except asyncio.CancelledError:
                    pass
        finally:
            await self._idle.wait()
            try:
                if self.runtime is not None:
                    await self.runtime.aclose()
            finally:
                await self.parser.aclose()

    async def aclose(self) -> None:
        self._closing = True
        self.available.clear()
        if (
            self._close_task is None
            or self._close_task.cancelled()
            or (self._close_task.done() and self._close_task.exception() is not None)
        ):
            self._close_task = asyncio.create_task(self._finish_close())
        # Cancellation must not let the caller dispose DB before real I/O ends.
        cancelled = False
        while not self._close_task.done():
            try:
                await asyncio.shield(self._close_task)
            except asyncio.CancelledError:
                cancelled = True
        self._close_task.result()
        if cancelled:
            raise asyncio.CancelledError
