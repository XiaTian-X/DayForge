"""Bounded blocking image/file work; cancellation never releases a running slot."""

import asyncio
from concurrent.futures import Future, ThreadPoolExecutor
from contextvars import copy_context
from typing import Any, Callable, Self, TypeVar


Result = TypeVar("Result")


class AssetIoBusy(RuntimeError):
    """No file worker is available; caller may retry without consuming input."""


class AssetIoClosed(RuntimeError):
    """The application is draining and cannot admit another file operation."""


class AssetIoPool:
    """One event-loop owner, bounded workers, no waiting/submission queue.

    Run only blocking file/decoder functions here, never database publication.
    Awaiting callers may be cancelled; submitted threads cannot be interrupted.
    Their slots remain occupied until the real concurrent Future is done.
    Lifecycle must await aclose before releasing storage/database dependencies.
    """

    def __init__(self, capacity: int = 2):
        if type(capacity) is not int or not 1 <= capacity <= 8:
            raise ValueError("asset worker capacity must be an integer from 1 to 8")
        self._loop = asyncio.get_running_loop()
        self._capacity = capacity
        self._executor = ThreadPoolExecutor(
            max_workers=capacity, thread_name_prefix="dayforge-assets"
        )
        self._active: set[Future[Any]] = set()
        self._idle = asyncio.Event()
        self._idle.set()
        self._closing = False

    def _check_loop(self) -> None:
        if asyncio.get_running_loop() is not self._loop:
            raise RuntimeError("asset worker pool belongs to a different event loop")

    async def run(self, operation: Callable[[], Result]) -> Result:
        self._check_loop()
        if self._closing:
            raise AssetIoClosed("asset worker pool is closing")
        if len(self._active) >= self._capacity:
            raise AssetIoBusy("asset worker pool is at capacity")
        # No await between admission and registration: cancellation cannot leak
        # a reserved slot. Submission errors have not altered active/idle state.
        submitted = self._executor.submit(copy_context().run, operation)
        self._active.add(submitted)
        self._idle.clear()
        submitted.add_done_callback(self._notify_finished)
        wrapped = asyncio.wrap_future(submitted, loop=self._loop)
        # A cancelled requester no longer awaits this future. Observe a later
        # disk failure without suppressing it for callers still awaiting it.
        wrapped.add_done_callback(self._observe)
        return await asyncio.shield(wrapped)

    @staticmethod
    def _observe(future: asyncio.Future) -> None:
        if not future.cancelled():
            future.exception()

    def _notify_finished(self, future: Future) -> None:
        try:
            self._loop.call_soon_threadsafe(self._finished, future)
        except RuntimeError:
            # Forced event-loop shutdown cannot be recovered here. Normal app
            # shutdown must drain before closing its loop; never reopen capacity.
            if not self._loop.is_closed():
                raise

    def _finished(self, future: Future) -> None:
        self._active.remove(future)
        if not self._active:
            self._idle.set()

    async def drain(self) -> None:
        """Wait for actual workers without reopening or closing admission.

        Recovery must separately exclude new request submissions before calling
        this method. An idle observation alone is not an exclusive storage lease.
        Cancelling this wait cannot cancel threads or release their capacity.
        """
        self._check_loop()
        await self._idle.wait()

    async def aclose(self) -> None:
        """Stop admission, await actual threads, then release the executor.

        Cancellation propagates but never reopens admission or kills a thread.
        The lifecycle owner can repeat aclose to complete interrupted draining.
        """
        self._check_loop()
        self._closing = True
        await self.drain()
        # All submitted futures really completed; no event-loop blocking join.
        self._executor.shutdown(wait=False, cancel_futures=False)

    async def __aenter__(self) -> Self:
        self._check_loop()
        if self._closing:
            raise AssetIoClosed("asset worker pool is closing")
        return self

    async def __aexit__(self, *_exc) -> None:
        await self.aclose()
