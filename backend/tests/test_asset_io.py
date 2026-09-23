import asyncio
from contextvars import ContextVar
import gc
from io import BytesIO
from threading import Event, get_ident

import pytest

from src.storage.asset_files import AssetFiles
from src.storage.asset_io import AssetIoBusy, AssetIoClosed, AssetIoPool
from tests.asset_file_fixtures import OWNER, SVG, blob, intents


async def reached(event: asyncio.Event) -> None:
    await asyncio.wait_for(event.wait(), timeout=5)


def blocker(loop, started, release, result=17, *, error=None):
    def operation():
        loop.call_soon_threadsafe(started.set)
        if not release.wait(timeout=5):
            raise AssertionError("test did not release worker")
        if error is not None:
            raise error
        return result

    return operation


@pytest.mark.parametrize("capacity", [0, -1, 9, True, 1.5])
async def test_capacity_is_small_explicit_and_not_coerced(capacity):
    with pytest.raises(ValueError, match="capacity"):
        AssetIoPool(capacity)


async def test_work_is_off_loop_and_copies_request_context():
    request = ContextVar("synthetic-request", default="absent")
    token = request.set("account-a")
    thread = get_ident()
    try:
        async with AssetIoPool(1) as pool:
            worker, value = await pool.run(lambda: (get_ident(), request.get()))
            assert worker != thread and value == "account-a"
    finally:
        request.reset(token)


async def test_cancelled_request_keeps_slot_until_actual_io_finishes():
    loop = asyncio.get_running_loop()
    pool = AssetIoPool(1)
    started, release = asyncio.Event(), Event()
    task = asyncio.create_task(pool.run(blocker(loop, started, release)))
    try:
        await reached(started)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        called = False

        def must_not_run():
            nonlocal called
            called = True

        for _ in range(20):
            with pytest.raises(AssetIoBusy):
                await pool.run(must_not_run)
        assert not called
        closing = asyncio.create_task(pool.aclose())
        await asyncio.sleep(0)
        assert not closing.done()
        with pytest.raises(AssetIoClosed):
            await pool.run(lambda: 1)
        release.set()
        await asyncio.wait_for(closing, timeout=5)
        await pool.aclose()
    finally:
        release.set()
        await pool.aclose()


async def test_every_slot_is_bounded_and_workers_can_be_reused():
    loop = asyncio.get_running_loop()
    releases = [Event(), Event()]
    starts = [asyncio.Event(), asyncio.Event()]
    async with AssetIoPool(2) as pool:
        tasks = [
            asyncio.create_task(pool.run(blocker(loop, starts[i], releases[i], i)))
            for i in range(2)
        ]
        try:
            await asyncio.gather(*(reached(event) for event in starts))
            with pytest.raises(AssetIoBusy):
                await pool.run(lambda: 3)
            releases[0].set()
            assert await tasks[0] == 0
            assert await pool.run(lambda: 7) == 7
            assert not tasks[1].done()
            releases[1].set()
            assert await tasks[1] == 1
        finally:
            for release in releases:
                release.set()
            await asyncio.gather(*tasks)


async def test_disk_failure_propagates_and_releases_capacity():
    failure = OSError("synthetic disk failure")

    def fail():
        raise failure

    async with AssetIoPool(1) as pool:
        with pytest.raises(OSError) as caught:
            await pool.run(fail)
        assert caught.value is failure
        assert await pool.run(lambda: 19) == 19


async def test_failure_after_request_cancellation_is_observed_without_db_publication():
    loop = asyncio.get_running_loop()
    release, started = Event(), asyncio.Event()
    problems = []
    handler = loop.get_exception_handler()
    loop.set_exception_handler(lambda _loop, context: problems.append(context))
    pool = AssetIoPool(1)
    published = False

    async def request():
        nonlocal published
        await pool.run(
            blocker(loop, started, release, error=OSError("abandoned disk error"))
        )
        published = True

    task = asyncio.create_task(request())
    try:
        await reached(started)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        release.set()
        await asyncio.wait_for(pool.aclose(), timeout=5)
        await asyncio.sleep(0)
        del task
        gc.collect()
        await asyncio.sleep(0)
        assert not published
        assert problems == []
    finally:
        release.set()
        await pool.aclose()
        loop.set_exception_handler(handler)


async def test_interrupted_drain_remains_closed_and_can_be_completed():
    loop = asyncio.get_running_loop()
    pool = AssetIoPool(1)
    release, started = Event(), asyncio.Event()
    task = asyncio.create_task(pool.run(blocker(loop, started, release)))
    try:
        await reached(started)
        closing = asyncio.create_task(pool.aclose())
        await asyncio.sleep(0)
        closing.cancel()
        with pytest.raises(asyncio.CancelledError):
            await closing
        with pytest.raises(AssetIoClosed):
            await pool.run(lambda: 1)
        assert not task.done()
        release.set()
        assert await task == 17
        await pool.aclose()
        with pytest.raises(AssetIoClosed):
            async with pool:
                pytest.fail("closed pool reopened")
    finally:
        release.set()
        await pool.aclose()


async def test_submission_failure_does_not_reserve_capacity(monkeypatch):
    async with AssetIoPool(1) as pool:
        original = pool._executor.submit

        def fail(*args, **kwargs):
            raise RuntimeError("synthetic executor rejection")

        with monkeypatch.context() as context:
            context.setattr(pool._executor, "submit", fail)
            with pytest.raises(RuntimeError, match="synthetic executor"):
                await pool.run(lambda: 1)
        assert pool._executor.submit == original
        assert await pool.run(lambda: 5) == 5


async def test_real_file_publish_after_cancel_leaves_only_pending_receipt(tmp_path):
    loop = asyncio.get_running_loop()
    started, release = asyncio.Event(), Event()
    store = AssetFiles(tmp_path)

    class PausedInput(BytesIO):
        def read(self, size=-1):
            loop.call_soon_threadsafe(started.set)
            assert release.wait(timeout=5)
            return super().read(size)

    source = PausedInput(SVG)
    pool = AssetIoPool(1)
    task = asyncio.create_task(pool.run(lambda: store.publish(OWNER, source, blob())))
    try:
        await reached(started)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        release.set()
        await asyncio.wait_for(pool.aclose(), timeout=5)
        assert store.read(OWNER, blob(), "svg-v1") == SVG
        assert len(intents(store)) == 1
        assert not source.closed  # caller must close only after worker drainage
    finally:
        release.set()
        await pool.aclose()
        source.close()


async def test_cancel_before_first_execution_never_calls_operation():
    called = False

    def operation():
        nonlocal called
        called = True

    async with AssetIoPool(1) as pool:
        task = asyncio.create_task(pool.run(operation))
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert not called
        assert await pool.run(lambda: 3) == 3


async def test_different_event_loop_is_rejected_without_work_or_shutdown():
    async with AssetIoPool(1) as pool:

        def other_loop():
            async def attempt():
                with pytest.raises(RuntimeError, match="different event loop"):
                    await pool.run(lambda: pytest.fail("wrong-loop work ran"))
                with pytest.raises(RuntimeError, match="different event loop"):
                    await pool.aclose()

            asyncio.run(attempt())

        await asyncio.to_thread(other_loop)
        assert await pool.run(lambda: 7) == 7
