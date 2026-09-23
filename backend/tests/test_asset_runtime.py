import asyncio
from io import BytesIO

import pytest

from src.storage.asset_io import AssetIoBusy, AssetIoClosed
from src.storage.asset_files import AssetFileError
from src.storage.asset_root import AssetRootBusy, AssetRootLease
from src.v2.errors import DomainError
from src.v2.asset_runtime import AssetRecoveryRequired, AssetRuntime
from tests.asset_file_fixtures import SVG, blob, intents, directory
from tests.test_asset_declarations import ASSET
from tests.test_asset_transfers import availability, barrier
from tests.test_asset_transfers import transfers as transfers
from tests.test_sync_contract_matrix import fixture, with_device


@pytest.fixture
async def runtime(transfers):
    manager = AssetRuntime(transfers[0])
    try:
        yield manager
    finally:
        await manager.aclose()


async def install(runtime, contexts, headers):
    async with runtime.request() as request:
        return await request.install(headers[1], contexts[1], ASSET, "light", SVG)


async def test_startup_recovery_precedes_requests_and_wire_receipts_unchanged(
    runtime, transfers
):
    service, contexts, headers, _, _ = transfers
    assert not runtime.ready
    with pytest.raises(AssetRecoveryRequired):
        async with runtime.request():
            pytest.fail("request admitted before startup recovery")
    assert await runtime.recover() == 0
    receipt = await install(runtime, contexts, headers)
    assert receipt.blob == blob()
    async with runtime.request() as request:
        assert await request.read(headers[1], contexts[1], ASSET, "light") == SVG
    assert runtime.ready
    assert (await availability(service))[0] is not None


async def test_capacity_covers_body_and_response_scope_not_only_file_threads(
    runtime, transfers
):
    await runtime.recover()
    async with runtime.request():
        async with runtime.request():
            # No I/O is running, but two transports already own their memory.
            with pytest.raises(AssetIoBusy):
                async with runtime.request():
                    pytest.fail("extra body reader was admitted")
            with pytest.raises(AssetIoBusy):
                await runtime.recover()
    assert runtime.ready
    async with runtime.request():
        pass


async def test_request_cannot_be_reused_or_escape_task_or_scope(runtime, transfers):
    _, contexts, headers, _, _ = transfers
    await runtime.recover()
    async with runtime.request() as request:
        with pytest.raises(RuntimeError, match="task-owned"):
            await asyncio.create_task(
                request.install(headers[1], contexts[1], ASSET, "light", SVG)
            )
        await request.install(headers[1], contexts[1], ASSET, "light", SVG)
        with pytest.raises(RuntimeError, match="task-owned"):
            await request.read(headers[1], contexts[1], ASSET, "light")
    with pytest.raises(RuntimeError, match="task-owned"):
        await request.read(headers[1], contexts[1], ASSET, "light")


async def test_cancelled_install_blocks_retry_until_actual_thread_and_recovery_finish(
    runtime, transfers, monkeypatch
):
    service, contexts, headers, owners, _ = transfers
    await runtime.recover()
    reached, release = barrier(monkeypatch, service.files, "publish")
    task = asyncio.create_task(install(runtime, contexts, headers))
    recovering = None
    try:
        await asyncio.wait_for(reached.wait(), timeout=5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert not runtime.ready
        with pytest.raises(AssetRecoveryRequired):
            await install(runtime, contexts, headers)
        recovering = asyncio.create_task(runtime.recover())
        await asyncio.sleep(0)
        assert not recovering.done()
        with pytest.raises(AssetIoBusy):
            await runtime.recover()
        assert len(intents(service.files, owners[1])) == 1
        release.set()
        assert await asyncio.wait_for(recovering, timeout=5) == 1
        assert await availability(service) == (None, None)
        assert intents(service.files, owners[1]) == []
        assert runtime.ready
        await install(runtime, contexts, headers)
    finally:
        release.set()
        await asyncio.gather(task, return_exceptions=True)
        if recovering is not None:
            await asyncio.gather(recovering, return_exceptions=True)


async def test_deferred_cleanup_acknowledges_commit_but_closes_new_admission(
    runtime, transfers, monkeypatch
):
    service, contexts, headers, owners, _ = transfers
    await runtime.recover()

    def fail(_receipt):
        raise OSError("synthetic cleanup failure")

    with monkeypatch.context() as patch:
        patch.setattr(service.files, "finish", fail)
        receipt = await install(runtime, contexts, headers)
    assert receipt.blob == blob()
    assert (await availability(service))[0] is not None
    assert not runtime.ready
    assert len(intents(service.files, owners[1])) == 1
    with pytest.raises(AssetRecoveryRequired):
        await install(runtime, contexts, headers)
    assert await runtime.recover() == 1
    assert runtime.ready
    async with runtime.request() as request:
        assert await request.read(headers[1], contexts[1], ASSET, "light") == SVG


async def test_cancelled_recovery_never_reopens_admission_or_releases_root(
    runtime, transfers, monkeypatch
):
    service, _, _, _, _ = transfers
    reached, release = barrier(monkeypatch, runtime._lease, "acquire")
    task = asyncio.create_task(runtime.recover())
    other = AssetRootLease(service.files.root)
    try:
        await asyncio.wait_for(reached.wait(), timeout=5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert not runtime.ready
        with pytest.raises(AssetRootBusy):
            other.acquire()
        release.set()
        assert await runtime.recover() == 0
        assert runtime.ready
    finally:
        release.set()
        other.release()
        await asyncio.gather(task, return_exceptions=True)


async def test_cancelled_close_keeps_root_until_actual_worker_finishes(
    runtime, transfers, monkeypatch
):
    service, contexts, headers, _, _ = transfers
    await runtime.recover()
    reached, release = barrier(monkeypatch, service.files, "publish")
    task = asyncio.create_task(install(runtime, contexts, headers))
    other = AssetRootLease(service.files.root)
    closing = None
    try:
        await asyncio.wait_for(reached.wait(), timeout=5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        closing = asyncio.create_task(runtime.aclose())
        await asyncio.sleep(0)
        closing.cancel()
        with pytest.raises(asyncio.CancelledError):
            await closing
        with pytest.raises(AssetRootBusy):
            other.acquire()
        with pytest.raises(AssetIoClosed):
            await install(runtime, contexts, headers)
        release.set()
        await asyncio.gather(runtime.aclose(), runtime.aclose())
        other.acquire()
        assert not runtime.ready
        with pytest.raises(AssetIoClosed):
            await runtime.recover()
    finally:
        release.set()
        other.release()
        await asyncio.gather(task, return_exceptions=True)
        if closing is not None:
            await asyncio.gather(closing, return_exceptions=True)


async def test_bad_inventory_does_not_block_real_fact_and_timer_http(
    runtime, transfers, runtime_client
):
    service, contexts, headers, _, _ = transfers
    unknown = service.files.root / "unknown-keep"
    unknown.write_bytes(b"evidence")
    with pytest.raises(AssetFileError, match="UNKNOWN_FILE"):
        await runtime.recover()
    assert not runtime.ready
    for name, endpoint, count in [
        ("push-all-entities.json", "/api/v2/sync/push", 7),
        ("timer-commands.json", "/api/v2/timers/commands", 4),
    ]:
        response = await runtime_client.post(
            endpoint,
            headers={"Authorization": headers[1]},
            json=with_device(fixture("client/" + name), contexts[1].device_id),
        )
        assert response.status_code == 200, response.text
        assert [item["status"] for item in response.json()["results"]] == [
            "applied"
        ] * count
    assert unknown.read_bytes() == b"evidence"
    assert not runtime.ready


@pytest.mark.parametrize("damage", ["orphan", "corrupt-ready", "unproven-temporary"])
async def test_recovery_failure_keeps_closed_and_preserves_uncertain_files(
    runtime, transfers, monkeypatch, damage
):
    service, contexts, headers, owners, _ = transfers
    if damage == "orphan":
        owner = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        with BytesIO(SVG) as source:
            installed = service.files.publish(owner, source, blob())
    else:
        owner = owners[1]
        await runtime.recover()

        def fail(_receipt):
            raise OSError("retain intent")

        with monkeypatch.context() as patch:
            patch.setattr(service.files, "finish", fail)
            await install(runtime, contexts, headers)
        installed = intents(service.files, owner)[0]
        if damage == "corrupt-ready":
            (directory(service.files.root, owner) / blob().sha256).write_bytes(
                b"corrupt"
            )
        else:
            service.files.finish(installed)
            (
                directory(service.files.root, owner) / (installed.stem + ".part")
            ).write_bytes(b"partial")
    before = {
        path: path.read_bytes()
        for path in service.files.root.rglob("*")
        if path.is_file()
    }
    for _ in range(2):
        with pytest.raises((DomainError, ValueError)):
            await runtime.recover()
        assert not runtime.ready
        with pytest.raises(AssetRecoveryRequired):
            await install(runtime, contexts, headers)
        assert {path: path.read_bytes() for path in before} == before


async def test_shutdown_failure_can_retry_without_releasing_another_root_owner(
    runtime, transfers, monkeypatch
):
    service, _, _, _, _ = transfers
    await runtime.recover()
    shutdown = service.pool._executor.shutdown
    calls = 0

    def fail_once(*args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise RuntimeError("synthetic shutdown failure")
        return shutdown(*args, **kwargs)

    monkeypatch.setattr(service.pool._executor, "shutdown", fail_once)
    with pytest.raises(RuntimeError, match="synthetic shutdown"):
        await runtime.aclose()
    other = AssetRootLease(service.files.root)
    try:
        other.acquire()
        await runtime.aclose()
        third = AssetRootLease(service.files.root)
        with pytest.raises(AssetRootBusy):
            third.acquire()
    finally:
        other.release()
    assert not runtime.ready


async def test_wrong_loop_never_admits_recovers_or_closes(runtime):
    await runtime.recover()

    def other_loop():
        async def attempt():
            with pytest.raises(RuntimeError, match="different event loop"):
                async with runtime.request():
                    pytest.fail("wrong loop admitted")
            with pytest.raises(RuntimeError, match="different event loop"):
                await runtime.recover()
            with pytest.raises(RuntimeError, match="different event loop"):
                await runtime.aclose()

        asyncio.run(attempt())

    await asyncio.to_thread(other_loop)
    assert runtime.ready


async def test_later_success_cannot_clear_another_admitted_requests_recovery_flag(
    runtime, transfers, monkeypatch
):
    service, contexts, headers, owners, _ = transfers
    await runtime.recover()

    def fail(_receipt):
        raise OSError("first cleanup fails")

    async with runtime.request() as first, runtime.request() as second:
        with monkeypatch.context() as patch:
            patch.setattr(service.files, "finish", fail)
            await first.install(headers[1], contexts[1], ASSET, "light", SVG)
        assert not runtime.ready
        await second.install(headers[2], contexts[2], ASSET, "light", SVG)
        assert not runtime.ready
    assert len(intents(service.files, owners[1])) == 1
    assert intents(service.files, owners[2]) == []
    assert await runtime.recover() == 1
    assert runtime.ready


async def test_shutdown_waits_for_admitted_body_scope_even_without_file_io(
    runtime, transfers
):
    service, _, _, _, _ = transfers
    await runtime.recover()
    other = AssetRootLease(service.files.root)
    closing = None
    try:
        async with runtime.request():
            closing = asyncio.create_task(runtime.aclose())
            await asyncio.sleep(0)
            assert not closing.done()
            with pytest.raises(AssetRootBusy):
                other.acquire()
            with pytest.raises(AssetIoClosed):
                async with runtime.request():
                    pytest.fail("new body admitted during shutdown")
        await asyncio.wait_for(closing, timeout=5)
        other.acquire()
    finally:
        other.release()
        if closing is not None:
            await asyncio.gather(closing, return_exceptions=True)
