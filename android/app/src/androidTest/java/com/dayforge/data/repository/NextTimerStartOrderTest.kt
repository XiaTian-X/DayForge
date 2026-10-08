package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real file Room + original requests + loopback HTTP; no clock-based dependency ordering. */
@RunWith(AndroidJUnit4::class)
class NextTimerStartOrderTest : NextCoreRequestFixture() {
    private val codec = Json { encodeDefaults = true }
    private fun timerReply(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (!input.path.endsWith("/commands")) return if (input.path.endsWith("/identity")) reply(input)
            else successReply(input, (wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0) + 1)
        val command = codec.decodeFromString<TimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8)).commands.single()
        val policy = requireNotNull(command.startPolicy)
        assertEquals(60, policy.targetSeconds)
        assertFalse(input.body.toString(Charsets.UTF_8).contains("planQueueWatermark"))
        val timer = TimerSessionResponse(command.sessionId, timerHabit.uuid, "running", id(4), 1, 1, 2,
            command.occurredAt, command.occurredAt, timezone = "Asia/Shanghai", isCountdown = policy.isCountdown,
            targetSeconds = policy.targetSeconds, maxDurationSeconds = policy.maxDurationSeconds, activeElapsedMs = 0)
        return MaterialSocketServer.Reply(codec.encodeToString(TimerCommandBatchResponse(listOf(
            TimerCommandResult(command.commandId, command.sessionId, "applied", session = timer)), time)).toByteArray())
    }

    @Test fun offlineEditsCannotOvertakeStartAndDirectSendAlsoChecksExactAcceptedPredecessor() = runBlocking<Unit> {
        producer().write(local()) { db.habitDao().update(timerHabit.copy(name = "Before timer")) }
        val before = db.syncOutboxDao().getAll().single()
        val started = start()
        producer().write(local()) { db.habitDao().update(db.habitDao().getHabitById(timerHabit.id)!!.copy(targetValue = 5)) }
        val after = db.syncOutboxDao().getAll().last()
        val original = requireNotNull(db.nextRequestDao().origin(NEXT_TIMER, started.commandId))
        val born = decodeNextTimerIntent(original.intentJson)
        assertEquals(before.operationId, born.planPredecessorId)
        assertEquals(TimerStartPolicy(60, false, 180), born.command.startPolicy)
        register()
        val (http, server) = channel(::timerReply)
        val core = sender(http)
        val timers = NextTimerRequestStore(db, tokens, sessions, core)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { timers.sendAndAccept(access(), started.commandId) } as NextRequestException).reason)
        assertNull(db.nextRequestDao().transmission(NEXT_TIMER, started.commandId))
        core.sendAndAcceptOperation(access(), before.operationId)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { core.sendAndAcceptOperation(access(), after.operationId) } as NextRequestException).reason)
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, after.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, timers.sendAndAccept(access(), started.commandId))
        core.sendAndAcceptOperation(access(), after.operationId)
        assertEquals(1, server.requests.count { it.path.endsWith("/commands") })
        assertEquals(5, db.habitDao().getHabitById(timerHabit.id)!!.targetValue)
        assertEquals(original, db.nextRequestDao().origin(NEXT_TIMER, started.commandId))
        storage.reopen()
        assertEquals(TimerStartPolicy(60, false, 180), db.withTransaction {
            NextTimerPolicyStore(db).policy(requireNotNull(tokens.localCoreWriteAccess()), started.sessionUuid)
        })
        assertNotNull(db.timeLogDao().getTimeLogByUuid(started.sessionUuid))
    }

    @Test fun deliveredButUnacceptedStartStillBlocksFutureEditAndColdReplayKeepsOriginalBytes() = runBlocking<Unit> {
        val started = start()
        producer().write(local()) { db.habitDao().update(timerHabit.copy(targetValue = 2)) }
        val after = db.syncOutboxDao().getAll().single()
        register()
        val (http, _) = channel(::timerReply)
        val delivered = requireNotNull(NextTimerRequestStore(db, tokens, sessions, sender(http)).send(access(), started.commandId))
        val frozen = transmission(NEXT_TIMER, started.commandId).wireBytes.copyOf()
        storage.reopen()
        val core = sender(http)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { core.sendAndAcceptOperation(access(), after.operationId) } as NextRequestException).reason)
        val timers = NextTimerRequestStore(db, tokens, sessions, core)
        assertEquals(NextOperationAcceptance.COMMITTED, timers.sendAndAccept(access(), started.commandId))
        assertArrayEquals(frozen, transmission(NEXT_TIMER, started.commandId).wireBytes)
        assertEquals(delivered.requestId, db.nextRequestDao().acceptance(NEXT_TIMER, started.commandId)!!.requestId)
        core.sendAndAcceptOperation(access(), after.operationId)
        assertEquals(2, db.habitDao().getHabitById(timerHabit.id)!!.targetValue)
    }
}
