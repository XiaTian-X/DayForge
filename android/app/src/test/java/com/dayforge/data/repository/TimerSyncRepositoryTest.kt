package com.dayforge.data.repository

import com.dayforge.data.api.SyncV2Api
import com.dayforge.data.api.dto.TimerCommandBatchResponse
import com.dayforge.data.api.dto.TimerCommandResult
import com.dayforge.data.api.dto.TimerSessionResponse
import com.dayforge.data.api.dto.TimerStatusResponse
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.TimerCommandEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSyncRepositoryTest {
    private val api = mockk<SyncV2Api>()
    private val dao = mockk<TimeLogDao>(relaxed = true)
    private val repository = TimerSyncRepository(api, dao)

    @Test
    fun `applied ordered commands are removed only after acknowledgement`() = runTest {
        val start = command(1, 1, "start")
        val stop = command(2, 2, "stop")
        coEvery { dao.getPendingTimerCommands(any()) } returnsMany listOf(
            listOf(start, stop), emptyList()
        )
        coEvery { api.pushTimerCommands(any()) } returns TimerCommandBatchResponse(
            results = listOf(
                TimerCommandResult(start.commandId, start.sessionUuid, "applied"),
                TimerCommandResult(stop.commandId, stop.sessionUuid, "applied")
            ),
            serverTime = "2026-08-14T00:00:00Z"
        )

        repository.pushPending("device")

        coVerify(exactly = 1) { dao.deleteTimerCommand(1) }
        coVerify(exactly = 1) { dao.deleteTimerCommand(2) }
    }

    @Test
    fun `lost response retries the exact timer command until acknowledged`() = runTest {
        val stop = command(2, 2, "stop")
        coEvery { dao.getPendingTimerCommands(any()) } returnsMany listOf(
            listOf(stop),
            listOf(stop),
            emptyList()
        )
        val requests = mutableListOf<com.dayforge.data.api.dto.TimerCommandBatchRequest>()
        coEvery { api.pushTimerCommands(capture(requests)) } throws IOException("response lost") andThen
            TimerCommandBatchResponse(
                listOf(
                    TimerCommandResult(
                        stop.commandId,
                        stop.sessionUuid,
                        "already_applied"
                    )
                ),
                "2026-08-14T00:00:00Z"
            )

        assertTrue(runCatching { repository.pushPending("device") }.exceptionOrNull() is IOException)
        repository.pushPending("device")

        assertEquals(2, requests.size)
        assertEquals(requests[0].commands, requests[1].commands)
        coVerify(exactly = 1) { dao.deleteTimerCommand(stop.id) }
    }

    @Test
    fun `missing predecessor remains retryable without changing command identity`() = runTest {
        val stop = command(2, 2, "stop")
        coEvery { dao.getPendingTimerCommands(any()) } returns listOf(stop)
        coEvery { api.pushTimerCommands(any()) } returns TimerCommandBatchResponse(
            listOf(
                TimerCommandResult(
                    stop.commandId, stop.sessionUuid, "conflict",
                    errorCode = "MISSING_PREDECESSOR"
                )
            ),
            "2026-08-14T00:00:00Z"
        )

        repository.pushPending("device")

        coVerify(exactly = 0) { dao.deleteTimerCommand(any()) }
        coVerify(exactly = 1) {
            dao.markTimerCommandAttempt(2, "MISSING_PREDECESSOR", null)
        }
    }

    @Test
    fun `permanent rejection is quarantined for user attention`() = runTest {
        val start = command(1, 1, "start")
        coEvery { dao.getPendingTimerCommands(any()) } returns listOf(start)
        coEvery { api.pushTimerCommands(any()) } returns TimerCommandBatchResponse(
            listOf(
                TimerCommandResult(
                    start.commandId, start.sessionUuid, "rejected",
                    errorCode = "ACTIVITY_NOT_FOUND", message = "missing"
                )
            ),
            "2026-08-14T00:00:00Z"
        )

        val result = runCatching { repository.pushPending("device") }

        assertTrue(result.exceptionOrNull() is TimerSyncRequiresAttentionException)
        coVerify(exactly = 1) {
            dao.deadLetterTimerCommand(1, "ACTIVITY_NOT_FOUND", "missing", any())
        }
    }

    @Test
    fun `manual retry replaces rejected command id before reactivating it`() = runTest {
        val replacement = slot<String>()

        repository.retryRejectedCommand(7)

        coVerify(exactly = 1) {
            dao.retryRejectedTimerCommand(7, capture(replacement))
        }
        assertNotEquals("", replacement.captured)
        assertEquals(36, replacement.captured.length)
    }

    @Test
    fun `server recovery cancels an active owned session before clearing local data`() = runTest {
        val rejected = command(7, 2, "stop").copy(deadLetteredAt = 10)
        coEvery { dao.getRejectedTimerCommand(7) } returns rejected
        coEvery { api.timerStatus(rejected.sessionUuid, "device") } returns
            TimerStatusResponse(activeSession(), "2026-08-14T00:00:00Z")
        val request = slot<com.dayforge.data.api.dto.TimerCommandBatchRequest>()
        coEvery { api.pushTimerCommands(capture(request)) } answers {
            val cancel = request.captured.commands.single()
            TimerCommandBatchResponse(
                listOf(TimerCommandResult(cancel.commandId, cancel.sessionId, "applied")),
                "2026-08-14T00:00:01Z"
            )
        }

        repository.cancelRejectedCommandAndUseServer(7, "device")

        val cancel = request.captured.commands.single()
        assertEquals("cancel", cancel.commandType)
        assertEquals(3, cancel.sequence)
        assertEquals(4, cancel.expectedRevision)
        assertEquals(2, cancel.expectedControlGeneration)
        coVerify(exactly = 1) {
            dao.resolveRejectedTimerCommand(rejected.sessionUuid, true)
        }
    }

    @Test
    fun `server recovery keeps local data when cancel acknowledgement does not match`() = runTest {
        val rejected = command(7, 2, "stop").copy(deadLetteredAt = 10)
        coEvery { dao.getRejectedTimerCommand(7) } returns rejected
        coEvery { api.timerStatus(rejected.sessionUuid, "device") } returns
            TimerStatusResponse(activeSession(), "2026-08-14T00:00:00Z")
        coEvery { api.pushTimerCommands(any()) } returns TimerCommandBatchResponse(
            listOf(TimerCommandResult("different-command", rejected.sessionUuid, "applied")),
            "2026-08-14T00:00:01Z"
        )

        val result = runCatching {
            repository.cancelRejectedCommandAndUseServer(7, "device")
        }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 0) { dao.resolveRejectedTimerCommand(any(), any()) }
    }

    @Test
    fun `server recovery keeps local completed record until normal pull replaces it`() = runTest {
        val rejected = command(7, 2, "stop").copy(deadLetteredAt = 10)
        coEvery { dao.getRejectedTimerCommand(7) } returns rejected
        coEvery { api.timerStatus(rejected.sessionUuid, "device") } returns
            TimerStatusResponse(activeSession().copy(state = "completed"), "2026-08-14T00:00:00Z")

        repository.cancelRejectedCommandAndUseServer(7, "device")

        coVerify(exactly = 0) { api.pushTimerCommands(any()) }
        coVerify(exactly = 1) {
            dao.resolveRejectedTimerCommand(rejected.sessionUuid, false)
        }
    }

    @Test
    fun `server recovery leaves another controller active and clears only stale local data`() = runTest {
        val rejected = command(7, 2, "stop").copy(deadLetteredAt = 10)
        coEvery { dao.getRejectedTimerCommand(7) } returns rejected
        coEvery { api.timerStatus(rejected.sessionUuid, "device") } returns TimerStatusResponse(
            activeSession().copy(controllerDeviceId = "other-device"),
            "2026-08-14T00:00:00Z"
        )

        repository.cancelRejectedCommandAndUseServer(7, "device")

        coVerify(exactly = 0) { api.pushTimerCommands(any()) }
        coVerify(exactly = 1) {
            dao.resolveRejectedTimerCommand(rejected.sessionUuid, true)
        }
    }

    private fun command(id: Long, sequence: Int, type: String) = TimerCommandEntity(
        id = id,
        commandId = "00000000-0000-4000-8000-00000000000$id",
        sessionUuid = "10000000-0000-4000-8000-000000000000",
        sequence = sequence,
        commandType = type,
        occurredAt = 1_786_656_000_000,
        expectedControlGeneration = if (type == "start") 0 else 1,
        activityUuid = if (type == "start") "20000000-0000-4000-8000-000000000000" else null,
        timezone = if (type == "start") "Asia/Shanghai" else null
    )

    private fun activeSession() = TimerSessionResponse(
        sessionId = "10000000-0000-4000-8000-000000000000",
        activityUuid = "20000000-0000-4000-8000-000000000000",
        state = "running",
        controllerDeviceId = "device",
        controlGeneration = 2,
        revision = 4,
        nextCommandSequence = 3,
        startedAt = "2026-08-14T00:00:00Z",
        stateChangedAt = "2026-08-14T00:00:00Z",
        timezone = "Asia/Shanghai",
        isCountdown = true,
        targetSeconds = 60,
        maxDurationSeconds = 60,
        activeElapsedMs = 60_000
    )
}
