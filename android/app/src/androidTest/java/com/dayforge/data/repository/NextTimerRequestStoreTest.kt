package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextTimerRequestStoreTest : NextCoreRequestFixture() {
    private val codec = Json { encodeDefaults = true }
    private fun timers(http: NextSyncHttp) = NextTimerRequestStore(db, tokens, sessions, sender(http))

    @Before fun configureFullTimerTarget() = runBlocking<Unit> {
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            // Android stores minutes; the authoritative fixture's targetSeconds=60 is one minute.
            timerHabit = timerHabit.copy(targetValue = 1)
            db.habitDao().update(timerHabit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }

    private fun timerReply(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        if (input.path.endsWith("/push")) return successReply(input)
        val command = codec.decodeFromString<TimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8)).commands.single()
        val kind = command.commandType
        val state = when (kind) { "pause" -> "paused"; "stop" -> "completed"; "cancel" -> "cancelled"; else -> "running" }
        val elapsed = when (kind) { "start" -> 0L; "resume" -> 30_000L; else -> command.activeElapsedMs ?: 0L }
        val timer = TimerSessionResponse(command.sessionId, timerHabit.uuid, state, id(4), 1, command.sequence,
            command.sequence + 1, time, command.occurredAt,
            endedAt = command.occurredAt.takeIf { state in setOf("completed", "cancelled") },
            timezone = "Asia/Shanghai", isCountdown = false, targetSeconds = 60, maxDurationSeconds = 180,
            activeElapsedMs = elapsed, lastHeartbeatAt = "2026-10-06T01:00:00.123456Z",
            completedEventId = command.sessionId.takeIf { state == "completed" })
        return MaterialSocketServer.Reply(codec.encodeToString(TimerCommandBatchResponse(listOf(
            TimerCommandResult(command.commandId, command.sessionId, "applied", session = timer)), time)).toByteArray())
    }

    private suspend fun offlineChain(): List<TimerCommandEntity> {
        val first = start(); val dao = db.timeLogDao(); val log = dao.getTimeLogByUuid(first.sessionUuid)!!
        producer().write(local()) {
            dao.updatePauseAndQueue(log.id, true, millis + 30_000, 0, 3, millis + 30_000, 30_000, null, null,
                TimerCommandEntity(commandId = id(22), sessionUuid = first.sessionUuid, sequence = 2, commandType = "pause",
                    occurredAt = millis + 30_000, expectedControlGeneration = 1, activeElapsedMillis = 30_000))
        }
        producer().write(local()) {
            dao.updatePauseAndQueue(log.id, false, null, 10_000, 4, millis + 40_000, 30_000, null, null,
                TimerCommandEntity(commandId = id(23), sessionUuid = first.sessionUuid, sequence = 3, commandType = "resume",
                    occurredAt = millis + 40_000, expectedControlGeneration = 1),
                TimerSegmentEntity(sessionUuid = first.sessionUuid, sequence = 2, startedAt = millis + 40_000))
        }
        producer().write(local()) {
            dao.finishTimerAndQueue(log.id, millis + 70_000, 60, 10_000, 5, 60_000,
                TimerCommandEntity(commandId = id(24), sessionUuid = first.sessionUuid, sequence = 4, commandType = "stop",
                    occurredAt = millis + 70_000, expectedControlGeneration = 1, activeElapsedMillis = 60_000), false)
        }
        return dao.getPendingTimerCommands()
    }

    private suspend fun unaccepted(row: TimerCommandEntity) {
        assertEquals(row, db.timeLogDao().getTimerCommand(row.id))
        assertNull(db.nextRequestDao().acceptance(NEXT_TIMER, row.commandId)); assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun fullOfflineChainDrainsWithoutRewindingCompletedLocalTimerAndColdReplayUsesNoHttp() = runBlocking<Unit> {
        val commands = offlineChain(); val local = db.timeLogDao().getTimeLogByUuid(commands.first().sessionUuid)!!
        val segments = db.timeLogDao().getTimerSegments(local.uuid)
        storage.reopen(); register(); val (http, server) = channel(::timerReply)
        val store = timers(http)
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAccept(access(), commands.first().commandId))
        assertEquals(local, db.timeLogDao().getTimeLogByUuid(local.uuid))
        assertEquals(commands.drop(1), db.timeLogDao().getPendingTimerCommands())
        assertEquals(3, store.pushPending(access())); assertEquals(0, count("timer_command_outbox"))
        assertEquals(local, db.timeLogDao().getTimeLogByUuid(local.uuid)); assertEquals(segments, db.timeLogDao().getTimerSegments(local.uuid))
        assertEquals(0, count("sync_entity_state")); assertEquals(0, count("completions"))
        val sent = server.requests.filter { it.path.endsWith("/commands") }.map { it.body.copyOf() }
        assertEquals(4, sent.size); assertEquals(4, count("next_acceptances"))
        storage.reopen(); val requests = server.requests.size
        commands.forEach { assertEquals(NextOperationAcceptance.REPLAYED, timers(http).sendAndAccept(access(), it.commandId)) }
        assertEquals(requests, server.requests.size); assertEquals(0L, tokens.syncCursor.first())
        commands.forEachIndexed { index, row -> assertArrayEquals(sent[index], transmission(NEXT_TIMER, row.commandId).wireBytes) }
    }

    @Test fun offlineCancelAcknowledgementsNeverRecreateRemovedSessionOrConsumeUnrelatedWork() = runBlocking<Unit> {
        val first = start(); val log = db.timeLogDao().getTimeLogByUuid(first.sessionUuid)!!
        producer().write(local()) {
            db.timeLogDao().deleteTimerAndQueue(log, TimerCommandEntity(commandId = id(25), sessionUuid = first.sessionUuid,
                sequence = 2, commandType = "cancel", occurredAt = millis + 10_000, expectedControlGeneration = 1,
                activeElapsedMillis = 10_000))
        }
        val own = db.timeLogDao().getPendingTimerCommands()
        val unrelated = TimerCommandEntity(commandId = id(800), sessionUuid = id(801), sequence = 1, commandType = "start",
            occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai")
        val otherId = db.timeLogDao().insertTimerCommand(unrelated)
        register(); val (http, server) = channel(::timerReply)
        own.forEach { assertEquals(NextOperationAcceptance.COMMITTED, timers(http).sendAndAccept(access(), it.commandId)) }
        assertNull(db.timeLogDao().getTimeLogByUuid(first.sessionUuid)); assertTrue(db.timeLogDao().getTimerSegments(first.sessionUuid).isEmpty())
        assertEquals(unrelated.copy(id = otherId), db.timeLogDao().getTimerCommand(otherId))
        storage.reopen(); val requests = server.requests.size
        own.forEach { assertEquals(NextOperationAcceptance.REPLAYED, timers(http).sendAndAccept(access(), it.commandId)) }
        assertEquals(requests, server.requests.size); assertEquals(2, count("next_acceptances")); assertEquals(0, count("sync_entity_state"))
    }

    @Test fun firstSendAndLateAcceptanceCannotJumpPendingOrRejectedSameSessionPredecessor() = runBlocking<Unit> {
        val commands = offlineChain(); register(); val (http, server) = channel(::timerReply)
        val store = timers(http)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { store.sendAndAccept(access(), commands[1].commandId) } as NextRequestException).reason)
        assertEquals(0, count("next_transmissions")); assertTrue(server.requests.all { it.path.endsWith("/identity") })
        // The lower-level transport can deliver a frozen response, but that is not local acceptance.
        val delivered = sender(http).sendCommand(access(), commands[1].commandId)!!
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { store.accept(delivered) } as NextRequestException).reason)
        commands.forEach { unaccepted(it) }
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAccept(access(), commands[0].commandId))
        assertEquals(NextOperationAcceptance.COMMITTED, store.accept(delivered))
        assertEquals(commands.drop(2), db.timeLogDao().getPendingTimerCommands())
    }

    @Test fun malformedSuccessfulResultNeverAcknowledgesAndCoherentColdReceiptDamageIsRejected() = runBlocking<Unit> {
        val first = start(); register(); val (http, server) = channel(::timerReply)
        val store = timers(http); val delivered = sender(http).sendCommand(access(), first.commandId)!!
        val result = delivered.result.results.single(); val good = result.session!!
        val bad = listOf(result.copy(status = "conflict"), result.copy(session = null), result.copy(commandId = id(810)),
            result.copy(errorCode = "INVALID_TIMER"), result.copy(message = "not success")) + listOf(
            good.copy(sessionId = id(811)), good.copy(activityUuid = id(812)), good.copy(controllerDeviceId = id(813)),
            good.copy(controlGeneration = 2), good.copy(revision = 2), good.copy(nextCommandSequence = 3),
            good.copy(startedAt = "2026-10-06T00:00:01Z"), good.copy(stateChangedAt = "2026-10-06T00:00:01Z"),
            good.copy(state = "completed"), good.copy(endedAt = time), good.copy(activeElapsedMs = 1),
            good.copy(completedEventId = first.sessionUuid), good.copy(timezone = "invalid")).map { result.copy(session = it) }
        for (broken in bad) {
            assertNotNull(rejected { store.accept(delivered.copy(result = delivered.result.copy(results = listOf(broken)))) })
            unaccepted(first); assertEquals(0, count("sync_entity_state"))
        }
        assertEquals(NextOperationAcceptance.COMMITTED, store.accept(delivered))
        val receipt = db.nextRequestDao().acceptance(NEXT_TIMER, first.commandId)!!
        val corrupt = codec.encodeToString(result.copy(status = "applied", session = good.copy(activeElapsedMs = 1)))
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf<Any>(corrupt, nextRequestHash(corrupt.toByteArray()), first.commandId))
        storage.reopen(); val requests = server.requests.size
        assertNotNull(rejected { timers(http).sendAndAccept(access(), first.commandId) }); assertEquals(requests, server.requests.size)
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf<Any>(receipt.resultJson, receipt.resultHash, first.commandId))
        assertEquals(NextOperationAcceptance.REPLAYED, timers(http).sendAndAccept(access(), first.commandId))
        assertEquals(requests, server.requests.size)
    }

    @Test fun receiptQueueAndFinalCommitFailuresRollBackExactCommandWithoutTimerMutation() = runBlocking<Unit> {
        val first = start(); val local = db.timeLogDao().getTimeLogByUuid(first.sessionUuid)
        register(); val (http, _) = channel(::timerReply); val store = timers(http)
        val delivered = sender(http).sendCommand(access(), first.commandId)!!
        val sql = db.openHelper.writableDatabase
        for (fault in listOf("BEFORE INSERT ON next_acceptances BEGIN SELECT RAISE(ABORT,'receipt'); END",
            "BEFORE INSERT ON next_acceptances BEGIN SELECT RAISE(IGNORE); END",
            "AFTER DELETE ON timer_command_outbox BEGIN UPDATE next_acceptances SET resultHash='broken'; END",
            "AFTER INSERT ON next_acceptances BEGIN UPDATE timelogs SET durationSeconds=77; END")) {
            sql.execSQL("CREATE TRIGGER timer_ack_fault $fault")
            assertNotNull(rejected { store.accept(delivered) }); sql.execSQL("DROP TRIGGER timer_ack_fault")
            unaccepted(first); assertEquals(local, db.timeLogDao().getTimeLogByUuid(first.sessionUuid))
        }
        sql.execSQL("CREATE TABLE timer_commit_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE timer_commit_child(id INTEGER REFERENCES timer_commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER timer_commit AFTER INSERT ON next_acceptances BEGIN INSERT INTO timer_commit_child VALUES(1); END")
        assertNotNull(rejected { store.accept(delivered) })
        storage.reopen(); unaccepted(first); assertEquals(local, db.timeLogDao().getTimeLogByUuid(first.sessionUuid))
        assertEquals(0, count("timer_commit_child")); db.openHelper.writableDatabase.execSQL("DROP TRIGGER timer_commit")
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).accept(delivered))
        storage.reopen(); assertEquals(NextOperationAcceptance.REPLAYED, timers(http).sendAndAccept(access(), first.commandId))
    }

    @Test fun responseLossReplaysExactOriginalCommandThenColdAcceptanceNeedsNoNetwork() = runBlocking<Unit> {
        val first = start(); register(); var lost = true
        val (http, server) = channel { input ->
            if (input.path.endsWith("/commands") && lost) { lost = false; null } else timerReply(input)
        }
        assertTrue(rejected { timers(http).sendAndAccept(access(), first.commandId) } is IOException); unaccepted(first)
        val original = transmission(NEXT_TIMER, first.commandId).wireBytes.copyOf()
        storage.reopen(); assertEquals(NextOperationAcceptance.COMMITTED, timers(http).sendAndAccept(access(), first.commandId))
        server.requests.filter { it.path.endsWith("/commands") }.also { assertEquals(2, it.size) }.forEach { assertArrayEquals(original, it.body) }
        val requests = server.requests.size; storage.reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, timers(http).sendAndAccept(access(), first.commandId)); assertEquals(requests, server.requests.size)
    }

    @Test fun cancelledAndStaleAccountAcceptanceLeaveOriginalCommandAndSessionIntact() = runBlocking<Unit> {
        val first = start(); register(); val (http, _) = channel(::timerReply)
        val delivered = sender(http).sendCommand(access(), first.commandId)!!
        val local = db.timeLogDao().getTimeLogByUuid(first.sessionUuid)
        sessions.exclusive {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { timers(http).accept(delivered) }
            job.cancelAndJoin()
        }
        unaccepted(first); assertEquals(local, db.timeLogDao().getTimeLogByUuid(first.sessionUuid))
        tokens.saveLoginSession("synthetic-other", "synthetic-r", "other", id(820), false)
        assertEquals(NextRequestException.Reason.STALE_ACCESS,
            (rejected { timers(http).accept(delivered) } as NextRequestException).reason)
        unaccepted(first); assertEquals(local, db.timeLogDao().getTimeLogByUuid(first.sessionUuid))
    }

    private fun duration(session: String, elapsed: Long = 60_000): SyncV2Change {
        val end = Instant.ofEpochMilli(millis + 70_000).toString()
        val body = buildJsonObject {
            put("public_id", session); put("revision", 1); put("created_at", end); put("updated_at", end); put("deleted_at", JsonNull)
            put("activity_uuid", timerHabit.uuid); put("event_type", "duration_session"); put("value", JsonNull)
            put("duration_seconds", elapsed / 1000); put("duration_milliseconds", elapsed); put("started_at", time); put("ended_at", end)
            put("occurred_at", end); put("received_at", end); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
            put("source_type", "app"); put("source_device_id", id(4)); put("external_event_id", session); put("reverts_event_uuid", JsonNull)
            put("note", ""); put("metadata", buildJsonObject { put("timer_session_id", session) })
            put("day_allocations", JsonArray(listOf(buildJsonObject {
                put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai"); put("duration_milliseconds", elapsed)
            })))
        }
        return SyncV2Change(0, "activity_event", session, "upsert", 1, body, end)
    }

    @Test fun completeStopReceiptEnablesActualAtomicRecoveryAndWrongFactOrLateReceiptMutationRollsBack() = runBlocking<Unit> {
        register(); val (http, _) = channel(::timerReply)
        producer().write(local()) { habits().updateHabit(timerHabit.copy(description = "Published")) }
        val planIntent = db.syncOutboxDao().getAll().single()
        sender(http).sendAndAcceptOperation(access(), planIntent.operationId)
        val commands = offlineChain(); assertEquals(4, timers(http).pushPending(access()))
        val planShadow = db.syncOutboxDao().getState("plan_node", timerHabit.uuid)!!
        val plan = SyncV2Change(0, "plan_node", timerHabit.uuid, "upsert", planShadow.revision,
            Json.parseToJsonElement(planShadow.payloadJson!!).jsonObject, time)
        fun restoreStore() = OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers(http))
        val context = restoreStore().context(); val stage = restoreStore().beginRecovery(context, null)
        val snapshot = NextSyncBootstrapResponse(listOf(plan, duration(commands.first().sessionUuid)), 17, time, emptyList())
        val local = db.timeLogDao().getTimeLogByUuid(commands.first().sessionUuid)
        val broken = snapshot.copy(changes = listOf(plan, duration(commands.first().sessionUuid, 60_001)))
        assertNotNull(rejected { restoreStore().acceptRecovery(context, stage, broken) })
        assertEquals(local, db.timeLogDao().getTimeLogByUuid(commands.first().sessionUuid)); assertEquals(stage, restoreStore().recoveryState(context))
        assertNull(db.syncOutboxDao().getState("activity_event", commands.first().sessionUuid))
        val stopId = commands.last().commandId
        val receipt = db.nextRequestDao().acceptance(NEXT_TIMER, stopId)!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER late_timer_receipt AFTER INSERT ON sync_entity_state WHEN NEW.entityType='activity_event' BEGIN UPDATE next_acceptances SET resultHash='broken' WHERE requestId='$stopId'; END")
        assertNotNull(rejected { restoreStore().acceptRecovery(context, stage, snapshot) })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER late_timer_receipt")
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_TIMER, stopId)); assertEquals(local, db.timeLogDao().getTimeLogByUuid(commands.first().sessionUuid))
        assertEquals(stage, restoreStore().recoveryState(context)); assertEquals(0L, tokens.syncCursor.first())
        assertEquals(NextRecoveryStateEntity.ACCEPTED_DATA, restoreStore().acceptRecovery(context, stage, snapshot).phase)
        storage.reopen()
        val completed = db.timeLogDao().getTimeLogByUuid(commands.first().sessionUuid)!!
        assertEquals(60_000L, completed.timerActiveElapsedMillis); assertEquals(60, completed.durationSeconds)
        assertEquals(local!!.startTime, completed.startTime); assertEquals(local.endTime, completed.endTime)
        assertEquals(60_000L, db.timeLogDao().getDayAllocations(completed.uuid).sumOf { it.durationMillis })
        assertEquals(2, db.timeLogDao().getTimerSegments(completed.uuid).size); assertEquals(0, count("timer_command_outbox"))
        assertEquals(0L, tokens.syncCursor.first()) // Candidate accepted; runtime activation is a later joint step.
    }

    @Test fun noReceiptOrUnresolvedSuffixCannotBeAdoptedAsACompletedTimerByRecovery() = runBlocking<Unit> {
        val commands = offlineChain(); register(); val (http, _) = channel(::timerReply)
        val fact = duration(commands.first().sessionUuid); val captured = access()
        assertNull(db.withTransaction { timers(http).completionProofInTransaction(captured, fact) })
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).sendAndAccept(access(), commands.first().commandId))
        assertNull(db.withTransaction { timers(http).completionProofInTransaction(captured, fact) })
        assertEquals(commands.drop(1), db.timeLogDao().getPendingTimerCommands()); assertEquals(0, count("sync_entity_state"))
    }

    @Test fun knownPredecessorProtectsColdStopElapsedAndGenerationAndPausedStopUsesActualAcceptedPause() = runBlocking<Unit> {
        val commands = offlineChain(); register(); val (http, server) = channel(::timerReply)
        assertEquals(4, timers(http).pushPending(access()))
        val stop = commands.last(); val saved = db.nextRequestDao().acceptance(NEXT_TIMER, stop.commandId)!!
        val result = codec.decodeFromString<TimerCommandResult>(saved.resultJson)
        for (bad in listOf(result.session!!.copy(activeElapsedMs = 60_001), result.session.copy(controlGeneration = 2),
            result.session.copy(startedAt = "2026-10-05T00:00:00Z"))) {
            val broken = codec.encodeToString(result.copy(session = bad))
            db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
                arrayOf<Any>(broken, nextRequestHash(broken.toByteArray()), stop.commandId))
            storage.reopen(); val requests = server.requests.size
            assertNotNull(rejected { timers(http).sendAndAccept(access(), stop.commandId) }); assertEquals(requests, server.requests.size)
        }
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf<Any>(saved.resultJson, saved.resultHash, stop.commandId))
        assertEquals(NextOperationAcceptance.REPLAYED, timers(http).sendAndAccept(access(), stop.commandId))
        val paused = result.session!!.copy(state = "paused", endedAt = null, completedEventId = null,
            activeElapsedMs = 30_000, targetSeconds = 30, maxDurationSeconds = 90, revision = 2,
            nextCommandSequence = 3, stateChangedAt = "2026-10-06T00:00:30Z")
        val command = TimerCommandRequest(id(830), paused.sessionId, 3, "stop", "2026-10-06T00:00:40Z", 1,
            activeElapsedMs = 9_999)
        val terminal = paused.copy(state = "completed", endedAt = command.occurredAt, stateChangedAt = command.occurredAt,
            completedEventId = paused.sessionId, revision = 3, nextCommandSequence = 4)
        NextTimerResultMapper.validate(command, TimerCommandResult(command.commandId, command.sessionId, "applied", session = terminal), id(4))
        NextTimerResultMapper.validateAfter(paused, command, terminal)
    }

    @Test fun takeoverAndClampedStopKeepCapturedPolicyAndDoNotManufactureCompletion() {
        for (state in listOf("running", "paused")) {
            val previous = TimerSessionResponse(id(840), timerHabit.uuid, state, id(841), 1, 6, 7, time,
                "2026-10-06T00:00:40Z", timezone = "Asia/Shanghai", isCountdown = false, targetSeconds = 60,
                maxDurationSeconds = 180, activeElapsedMs = 30_000)
            val command = TimerCommandRequest(id(842), previous.sessionId, 7, "takeover", "2026-10-06T00:01:10Z", 1, 6)
            val current = previous.copy(controllerDeviceId = id(4), controlGeneration = 2, revision = 7,
                nextCommandSequence = 8, stateChangedAt = command.occurredAt, activeElapsedMs = if (state == "running") 60_000 else 30_000)
            NextTimerResultMapper.validate(command, TimerCommandResult(command.commandId, command.sessionId, "applied", session = current), id(4))
            NextTimerResultMapper.validateAfter(previous, command, current)
            assertNull(current.completedEventId); assertNull(current.endedAt)
        }
        val before = TimerSessionResponse(id(850), timerHabit.uuid, "running", id(4), 1, 1, 2, time, time,
            timezone = "Asia/Shanghai", isCountdown = true, targetSeconds = 60, maxDurationSeconds = 60, activeElapsedMs = 0)
        val stop = TimerCommandRequest(id(851), before.sessionId, 2, "stop", "2026-10-06T00:01:00Z", 1, 1,
            activeElapsedMs = 60_001)
        val completed = before.copy(state = "completed", revision = 2, nextCommandSequence = 3, stateChangedAt = stop.occurredAt,
            endedAt = stop.occurredAt, completedEventId = before.sessionId, activeElapsedMs = 60_000)
        NextTimerResultMapper.validate(stop, TimerCommandResult(stop.commandId, stop.sessionId, "applied", session = completed), id(4))
        NextTimerResultMapper.validateAfter(before, stop, completed)
    }
}
