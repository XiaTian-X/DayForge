"""Inactive account asset lifecycle, recovery and bounded request admission.

No HTTP route or global application hook uses this yet. The future transport
must enter request() BEFORE reading an upload body, enforce a bounded read with
a deadline there, and send the result within the same admitted request scope.
Ordinary fact synchronization never depends on this runtime's availability.
"""

import asyncio
from contextlib import asynccontextmanager
from typing import AsyncIterator

from src.storage.asset_io import AssetIoBusy, AssetIoClosed
from src.storage.asset_root import AssetRootLease, ScanLimits, scan_installations
from src.v2.asset_api_contract import AssetSyncContext, AssetTransferReceipt, Variant
from src.v2.asset_transfers import AssetTransfers


class AssetRecoveryRequired(RuntimeError):
    """Writes are not admitted until exclusive recovery succeeds."""


class AssetRuntime:
    def __init__(self, transfers: AssetTransfers, limits: ScanLimits = ScanLimits()):
        self._loop = asyncio.get_running_loop()
        self._transfers = transfers
        self._pool = transfers.pool
        self._lease = AssetRootLease(transfers.files.root)
        self._limits = limits
        self._active = 0
        self._ready = False
        self._recovering = False
        self._closing = False
        self._idle = asyncio.Event()
        self._idle.set()
        self._close_task: asyncio.Task[None] | None = None
        self._root_released = False

    def _check_loop(self) -> None:
        if asyncio.get_running_loop() is not self._loop:
            raise RuntimeError("asset runtime belongs to a different event loop")

    @property
    def ready(self) -> bool:
        return self._ready and not self._recovering and not self._closing

    def _signal_idle(self) -> None:
        if not self._active and not self._recovering:
            self._idle.set()

    @asynccontextmanager
    async def request(self) -> AsyncIterator["_AssetRequest"]:
        self._check_loop()
        if self._closing:
            raise AssetIoClosed("asset runtime is closing")
        if not self.ready:
            raise AssetRecoveryRequired("asset recovery is required")
        if self._active >= self._pool.capacity:
            raise AssetIoBusy("asset request capacity is exhausted")
        self._active += 1
        self._idle.clear()
        request = _AssetRequest(self)
        try:
            yield request
        finally:
            request._active = False
            self._active -= 1
            self._signal_idle()

    async def recover(self) -> int:
        """Exclusive bounded scan; every uncertainty keeps admission closed.

        Retry after current admitted requests finish. Draining also waits for
        file jobs left behind by cancelled requests or a prior cancelled scan.
        A failure is observable to the owning lifespan/maintenance coordinator;
        it is not a reason to shut down unrelated synchronization services.
        """
        self._check_loop()
        if self._closing:
            raise AssetIoClosed("asset runtime is closing")
        if self._active or self._recovering:
            raise AssetIoBusy("asset operations must finish before recovery")
        self._ready = False
        self._recovering = True
        self._idle.clear()
        try:
            await self._pool.drain()
            await self._pool.run(self._lease.acquire)
            inventory = await self._pool.run(
                lambda: scan_installations(self._transfers.files, self._limits)
            )
            for installed in inventory:
                await self._transfers.recover(installed)
            # No caller may upload while recovering. Only successfully checked
            # and reconciled evidence permits another bounded round of writes.
            if not self._closing:
                self._ready = True
            return len(inventory)
        finally:
            self._recovering = False
            self._signal_idle()

    async def _finish_close(self) -> None:
        await self._idle.wait()
        await self._pool.drain()
        if not self._root_released:
            await self._pool.run(self._lease.release)
            self._root_released = True
        await self._pool.aclose()

    async def aclose(self) -> None:
        """Stop requests, drain actual I/O, release root last; concurrent-safe.

        A cancelled waiter does not cancel shutdown or release a still-used root.
        Keep this object in the owning lifespan's finally block even if startup
        recovery fails. Failed shutdown is observable and can be retried.
        """
        self._check_loop()
        self._closing = True
        if (
            self._close_task is None
            or self._close_task.cancelled()
            or (self._close_task.done() and self._close_task.exception() is not None)
        ):
            self._close_task = asyncio.create_task(self._finish_close())
            self._close_task.add_done_callback(self._observe_close)
        await asyncio.shield(self._close_task)

    @staticmethod
    def _observe_close(task: asyncio.Task[None]) -> None:
        if not task.cancelled():
            task.exception()


class _AssetRequest:
    """One operation owned by one admitted task; cannot escape its scope."""

    def __init__(self, runtime: AssetRuntime):
        self._runtime = runtime
        self._task = asyncio.current_task()
        self._active = True
        self._used = False

    def _consume(self) -> None:
        if not self._active or self._used or asyncio.current_task() is not self._task:
            raise RuntimeError("asset request is not an active unused task-owned scope")
        self._used = True

    async def install(
        self,
        header: str | None,
        context: AssetSyncContext,
        asset_id: str,
        variant: Variant,
        data: bytes,
    ) -> AssetTransferReceipt:
        self._consume()
        try:
            result = await self._runtime._transfers.install(
                header, context, asset_id, variant, data
            )
        except BaseException:
            # Includes cancellation even if the worker or DB committed later.
            # Conservatively recover even a pre-I/O rejection; never accumulate
            # journals by guessing which phase a generic exception came from.
            self._runtime._ready = False
            raise
        if result.cleanup_pending:
            self._runtime._ready = False
        return result.receipt

    async def read(
        self,
        header: str | None,
        context: AssetSyncContext,
        asset_id: str,
        variant: Variant,
    ) -> bytes:
        self._consume()
        return await self._runtime._transfers.read(header, context, asset_id, variant)
