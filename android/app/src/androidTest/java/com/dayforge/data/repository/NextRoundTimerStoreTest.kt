package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real file Room/auth/socket and original offline commands, not a service stopwatch test. */
@RunWith(AndroidJUnit4::class)
class NextRoundTimerStoreTest : NextCoreRequestFixture() {
    private val json = Json { encodeDefaults = true }
    private val policies = mutableMapOf<String, TimerStartPolicy>()
    private var returnedRestart = false
    private fun initial(activity: String) = ChallengeRoundRecord(initialChallengeRoundHead(activity), null, null, null)
    private fun restart() = ChallengeRoundRecord(ChallengeRoundHead(timerHabit.uuid, id(301), 1), id(4), id(302),
        ChallengeRestartIntent(timerHabit.uuid, id(301), initialChallengeRoundUuid(timerHabit.uuid), 0, 1))
    private fun metadata(births: List<ChallengeBirth> = emptyList(), newer: Boolean = false) = ChallengeMetadata(1,
        listOf(ChallengeCheckpoint(initial(habit.uuid).head, listOf(initial(habit.uuid))),
            ChallengeCheckpoint(if (newer) restart().head else initial(timerHabit.uuid).head,
                if (newer) listOf(initial(timerHabit.uuid), restart()) else listOf(initial(timerHabit.uuid)))), births)
    private fun timers(http: NextSyncHttp) = NextTimerRequestStore(db, tokens, sessions, sender(http))
    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions,
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences),
            timerRequests = timers(http)), timers(http))
    private fun canonical(h: HabitEntity, revision: Long = 1) = SyncV2Change(0, "plan_node", h.uuid, "upsert", revision,
        JsonObject(NextStructureMapper.writePlan(h) + mapOf("public_id" to JsonPrimitive(h.uuid),
            "revision" to JsonPrimitive(revision), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
            "deleted_at" to JsonNull)), time)
    private suspend fun initialize(http: NextSyncHttp, countdown: Boolean = false) {
        register()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1")
            timerHabit = timerHabit.copy(isCountdown = countdown)
            db.habitDao().update(timerHabit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0")
        }
        val meta = metadata()
        val metricChange = SyncV2Change(0, "metric", metric.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writeMetric(metric) + mapOf("public_id" to JsonPrimitive(metric.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
                "deleted_at" to JsonNull)), time)
        merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(canonical(habit), canonical(timerHabit), metricChange),
            20, time, emptyList(), 1, meta.checkpoints, meta.births))
    }
    private fun respond(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        if (input.path.endsWith("/push")) {
            assertEquals("/api/v2/sync/rounds/push", input.path)
            val op = wireOperation(input)
            val ordinary = json.decodeFromString<NextSyncPushResponse>(successReply(input,
                op["base_revision"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long?.plus(1) ?: 1).bytes.toString(Charsets.UTF_8))
            val meta = metadata()
            return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(ordinary.results, 1,
                meta.checkpoints, meta.births)).toByteArray())
        }
        assertEquals("/api/v2/timers/rounds/commands", input.path)
        val request = json.decodeFromString<RoundTimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8))
        val command = request.commands.single()
        assertEquals(initial(timerHabit.uuid).head, request.contexts.single().head)
        command.startPolicy?.let { policies[command.sessionId] = it }
        val policy = policies[command.sessionId]!!
        val state = when (command.commandType) { "pause" -> "paused"; "stop" -> "completed"; "cancel" -> "cancelled"; else -> "running" }
        val elapsed = when (command.commandType) { "start" -> 0L; "resume" -> 30_000L; else -> command.activeElapsedMs ?: 0L }
        val timer = TimerSessionResponse(command.sessionId, timerHabit.uuid, state, id(4), 1, command.sequence,
            command.sequence + 1, time, command.occurredAt, endedAt = command.occurredAt.takeIf { state in setOf("completed", "cancelled") },
            timezone = "Asia/Shanghai", isCountdown = policy.isCountdown, targetSeconds = policy.targetSeconds,
            maxDurationSeconds = policy.maxDurationSeconds, activeElapsedMs = elapsed,
            completedEventId = command.sessionId.takeIf { state == "completed" })
        val births = listOf(ChallengeBirth("timer_session", command.sessionId, initial(timerHabit.uuid).head)) +
            command.sessionId.takeIf { state == "completed" }?.let { listOf(ChallengeBirth("activity_event", it, initial(timerHabit.uuid).head)) }.orEmpty()
        val meta = metadata(births, returnedRestart && state == "completed")
        return MaterialSocketServer.Reply(json.encodeToString(RoundTimerCommandBatchResponse(listOf(
            TimerCommandResult(command.commandId, command.sessionId, "applied", session = timer)), time, 1,
            meta.checkpoints, meta.births)).toByteArray())
    }
    private suspend fun startRound(ticket: NextRoundWriteScope? = null): TimerCommandEntity {
        producer().writeRounds(ticket ?: producer().captureRounds()) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, startTime = millis, endTime = null,
                durationSeconds = 0, date = millis, uuid = id(20), timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
        }
        return db.timeLogDao().getPendingTimerCommands().single()
    }
    private suspend fun offlineChain(): List<TimerCommandEntity> {
        val first = startRound(); val dao = db.timeLogDao(); val log = dao.getTimeLogByUuid(first.sessionUuid)!!
        producer().writeRounds(producer().captureRounds()) {
            dao.updatePauseAndQueue(log.id, true, millis + 30_000, 0, 3, millis + 30_000, 30_000, null, null,
                TimerCommandEntity(commandId = id(22), sessionUuid = first.sessionUuid, sequence = 2, commandType = "pause",
                    occurredAt = millis + 30_000, expectedControlGeneration = 1, activeElapsedMillis = 30_000))
        }
        producer().writeRounds(producer().captureRounds()) {
            dao.updatePauseAndQueue(log.id, false, null, 10_000, 4, millis + 40_000, 30_000, null, null,
                TimerCommandEntity(commandId = id(23), sessionUuid = first.sessionUuid, sequence = 3, commandType = "resume",
                    occurredAt = millis + 40_000, expectedControlGeneration = 1),
                TimerSegmentEntity(sessionUuid = first.sessionUuid, sequence = 2, startedAt = millis + 40_000))
        }
        producer().writeRounds(producer().captureRounds()) {
            dao.finishTimerAndQueue(log.id, millis + 70_000, 60, 10_000, 5, 60_000,
                TimerCommandEntity(commandId = id(24), sessionUuid = first.sessionUuid, sequence = 4, commandType = "stop",
                    occurredAt = millis + 70_000, expectedControlGeneration = 1, activeElapsedMillis = 60_000), false)
        }
        return dao.getPendingTimerCommands()
    }
    private suspend fun unaccepted(command: TimerCommandEntity) {
        assertEquals(command, db.timeLogDao().getTimerCommand(command.id))
        assertNull(db.nextRequestDao().acceptance(NEXT_TIMER, command.commandId))
    }
    private suspend fun fullChain(countdown: Boolean) {
        val (http, server) = channel(::respond); initialize(http, countdown)
        val rows = offlineChain(); val local = db.timeLogDao().getTimeLogByUuid(id(20))!!
        val segments = db.timeLogDao().getTimerSegments(id(20))
        rows.forEach { val original = roundTimerIntent(db.nextRequestDao().origin(NEXT_TIMER, it.commandId)!!.intentJson)!!
            assertEquals(initial(timerHabit.uuid).head, original.context.head); assertEquals(id(4), original.capturedDeviceId) }
        assertEquals(TimerStartPolicy(60, countdown, if (countdown) 60 else 180),
            decodeNextTimerIntent(db.nextRequestDao().origin(NEXT_TIMER, id(21))!!.intentJson).command.startPolicy)
        storage.reopen()
        assertEquals(4, timers(http).pushPending(access()))
        assertEquals(local, db.timeLogDao().getTimeLogByUuid(id(20))); assertEquals(segments, db.timeLogDao().getTimerSegments(id(20)))
        assertEquals(initial(timerHabit.uuid).head, merger(http).challengeMetadata(access())!!.requireBirth("activity_event", id(20), timerHabit.uuid).head)
        assertEquals(20L, merger(http).state(access(), true)!!.cursor); assertEquals(0, count("completions"))
        val requests = server.requests.size
        storage.reopen(); rows.forEach { assertEquals(NextOperationAcceptance.REPLAYED, timers(http).sendAndAccept(access(), it.commandId)) }
        assertEquals(requests, server.requests.size); assertEquals(local, db.timeLogDao().getTimeLogByUuid(id(20)))
    }
    @Test fun forwardOfflineStartPauseResumeStopAckAndColdReplayPreserveFullLocalState() = runBlocking { fullChain(false) }
    @Test fun countdownOfflineStartPauseResumeStopKeepOriginalPolicyAndBirth() = runBlocking { fullChain(true) }

    @Test fun separatelyCreatedOfflineTimerKeepsFullLocalChainButWaitsForActualCreationAckBeforeCommandHttp() = runBlocking {
        val (http, server) = channel(::respond); initialize(http)
        val fresh = timerHabit.copy(id = 0, uuid = id(501), name = "New offline timer")
        timerHabit = producer().writeRounds(producer().captureRounds()) { fresh.copy(id = db.habitDao().insert(fresh)) }
        val creation = db.syncOutboxDao().getAll().single()
        val origin = db.nextRequestDao().origin(NEXT_OPERATION, creation.operationId)!!
        assertTrue(roundOperationIntent(origin.intentJson)!!.initialCreation)
        storage.reopen(); val commands = offlineChain()
        val local = db.timeLogDao().getTimeLogByUuid(id(20))!!; val segments = db.timeLogDao().getTimerSegments(id(20))
        commands.forEach {
            assertEquals(initialChallengeRoundHead(fresh.uuid), roundTimerIntent(db.nextRequestDao().origin(NEXT_TIMER, it.commandId)!!.intentJson)!!.context.head)
        }
        assertNull(merger(http).challengeMetadata(access())!!.checkpoints.singleOrNull { it.head.activityUuid == fresh.uuid })
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { timers(http).send(access(), commands.first().commandId) } as NextRequestException).reason)
        val probe = server.requests.single()
        assertEquals("GET", probe.method); assertEquals("/api/v2/system/identity", probe.path); assertNull(probe.headers["authorization"])
        assertEquals(0, count("next_transmissions"))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), creation.operationId))
        storage.reopen(); assertEquals(4, timers(http).pushPending(access()))
        assertEquals(local, db.timeLogDao().getTimeLogByUuid(id(20))); assertEquals(segments, db.timeLogDao().getTimerSegments(id(20)))
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, creation.operationId))
        assertEquals(initialChallengeRoundHead(fresh.uuid), merger(http).challengeMetadata(access())!!.requireBirth("activity_event", id(20), fresh.uuid).head)
        assertEquals(60_000L, local.timerActiveElapsedMillis); assertEquals(0, count("completions"))
        assertEquals(4, server.requests.count { it.path.endsWith("/commands") }); assertEquals(20L, merger(http).state(access(), true)!!.cursor)
    }

    @Test fun lateCreationOriginOnlyFaultDuringOfflineTimerSuccessorRollsBackFullTransitionAndOriginalRootRemainsUsable() = runBlocking {
        val (http, server) = channel(::respond); initialize(http)
        val fresh = timerHabit.copy(id = 0, uuid = id(501), name = "Pending timer proof")
        timerHabit = producer().writeRounds(producer().captureRounds()) { fresh.copy(id = db.habitDao().insert(fresh)) }
        val creation = db.syncOutboxDao().getAll().single()
        val origin = db.nextRequestDao().origin(NEXT_OPERATION, creation.operationId)!!
        val first = startRound(); val original = db.timeLogDao().getTimeLogByUuid(first.sessionUuid)!!
        val segments = db.timeLogDao().getTimerSegments(first.sessionUuid)
        suspend fun pause() = producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().updatePauseAndQueue(original.id, true, millis + 30_000, 0, 3, millis + 30_000, 30_000, null, null,
                TimerCommandEntity(commandId = id(22), sessionUuid = first.sessionUuid, sequence = 2, commandType = "pause",
                    occurredAt = millis + 30_000, expectedControlGeneration = 1, activeElapsedMillis = 30_000))
        }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER timer_root_origin_fault AFTER INSERT ON next_request_origins WHEN NEW.requestId='${id(22)}' BEGIN UPDATE next_request_origins SET intentJson=replace(intentJson,'${id(4)}','${id(9)}') WHERE requestId='${creation.operationId}'; END")
        rejected { pause() }; db.openHelper.writableDatabase.execSQL("DROP TRIGGER timer_root_origin_fault")
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, creation.operationId))
        assertEquals(original, db.timeLogDao().getTimeLogByUuid(first.sessionUuid)); assertEquals(segments, db.timeLogDao().getTimerSegments(first.sessionUuid))
        assertEquals(listOf(first), db.timeLogDao().getPendingTimerCommands()); assertNull(db.nextRequestDao().origin(NEXT_TIMER, id(22)))
        assertEquals(creation, db.syncOutboxDao().getById(creation.id)); assertEquals(2, count("next_request_origins"))
        storage.reopen(); pause()
        assertEquals(2, db.timeLogDao().getPendingTimerCommands().size)
        assertTrue(db.timeLogDao().getTimeLogByUuid(first.sessionUuid)!!.isPaused)
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, creation.operationId))
        assertEquals(initialChallengeRoundHead(fresh.uuid), roundTimerIntent(db.nextRequestDao().origin(NEXT_TIMER, id(22))!!.intentJson)!!.context.head)
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("next_acceptances")); assertEquals(0, count("next_challenge_births"))
        assertTrue(server.requests.isEmpty())
    }

    @Test fun lostResponseColdRetryUsesSameWholeRoundEnvelopeAndAcceptedPolicyRemainsUsable() = runBlocking {
        var lose = true
        val (http, server) = channel { input -> val result = respond(input)
            if (input.path.endsWith("/commands") && lose) { lose = false; null } else result }
        initialize(http); val start = startRound()
        rejected { timers(http).sendAndAccept(access(), start.commandId) }; unaccepted(start)
        val original = transmission(NEXT_TIMER, start.commandId)
        storage.reopen(); assertEquals(NextOperationAcceptance.COMMITTED, timers(http).sendAndAccept(access(), start.commandId))
        val bodies = server.requests.filter { it.path.endsWith("/commands") }.map { it.body }
        assertEquals(2, bodies.size); bodies.forEach { assertArrayEquals(original.wireBytes, it) }
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().deleteTimerAndQueue(db.timeLogDao().getTimeLogByUuid(start.sessionUuid)!!,
                TimerCommandEntity(commandId = id(25), sessionUuid = start.sessionUuid, sequence = 2, commandType = "cancel",
                    occurredAt = millis + 10_000, expectedControlGeneration = 1, activeElapsedMillis = 10_000))
        }
        val cancel = db.timeLogDao().getPendingTimerCommands().single()
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).sendAndAccept(access(), cancel.commandId))
        assertNull(db.timeLogDao().getTimeLogByUuid(start.sessionUuid)); assertTrue(db.timeLogDao().getTimerSegments(start.sessionUuid).isEmpty())
        storage.reopen(); assertEquals(NextOperationAcceptance.REPLAYED, timers(http).sendAndAccept(access(), cancel.commandId))
        assertEquals(3, server.requests.count { it.path.endsWith("/commands") })
    }

    @Test fun lateTerminalAckWithNewerHeadKeepsOldSessionBirthAndDoesNotInventPlanRevision() = runBlocking {
        val (http, _) = channel(::respond); initialize(http); offlineChain(); returnedRestart = true
        assertEquals(4, timers(http).pushPending(access()))
        val meta = merger(http).challengeMetadata(access())!!
        assertEquals(restart().head, meta.checkpoints.single { it.head.activityUuid == timerHabit.uuid }.head)
        assertEquals(initial(timerHabit.uuid).head, meta.requireBirth("timer_session", id(20), timerHabit.uuid).head)
        assertEquals(1L, db.syncOutboxDao().getState("plan_node", timerHabit.uuid)!!.revision)
        storage.reopen(); assertEquals(NextOperationAcceptance.REPLAYED, timers(http).sendAndAccept(access(), id(21)))
        assertEquals(restart().head, merger(http).challengeMetadata(access())!!.checkpoints.single { it.head.activityUuid == timerHabit.uuid }.head)
    }

    @Test fun staleStartTicketAndOrphanSuccessorRollBackBusinessAndDoNotCaptureNewSource() = runBlocking {
        val (http, _) = channel(::respond); initialize(http); val stale = producer().captureRounds()
        val store = merger(http); val meta = metadata(newer = true)
        store.page(access(), store.state(access(), true)!!, RoundSyncPullResponse(listOf(canonical(timerHabit, 2).copy(sequence = 21)),
            21, false, time, 1, meta.checkpoints, meta.births))
        assertEquals("SYNC_CHALLENGE_STALE_ACTION", rejected { startRound(stale) }.message)
        assertNull(db.timeLogDao().getTimeLogByUuid(id(20))); assertEquals(0, count("timer_command_outbox"))
        rejected { producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().insertTimerCommand(TimerCommandEntity(commandId = id(28), sessionUuid = id(29), sequence = 2,
                commandType = "pause", occurredAt = millis + 1, expectedControlGeneration = 1, activeElapsedMillis = 1))
        } }
        assertEquals(0, count("next_request_origins")); assertEquals(0, count("timer_command_outbox"))
    }

    @Test fun wrongAckBirthSidecarAndLateReceiptFaultsLeaveOriginalCommandAndCursorForExactRetry() = runBlocking {
        val (http, _) = channel(::respond); initialize(http); val first = startRound()
        val local = db.timeLogDao().getTimeLogByUuid(first.sessionUuid)
        val delivery = timers(http).send(access(), first.commandId)!!
        rejected { timers(http).accept(delivery.copy(challengeMetadata = null)) }
        val wrong = metadata(listOf(ChallengeBirth("timer_session", first.sessionUuid, restart().head)), true)
        rejected { timers(http).accept(delivery.copy(challengeMetadata = wrong)) }
        val faults = listOf("BEFORE INSERT ON next_challenge_births BEGIN SELECT RAISE(IGNORE); END",
            "AFTER INSERT ON next_challenge_births BEGIN UPDATE timelogs SET durationSeconds=77; END",
            "AFTER INSERT ON next_acceptances BEGIN UPDATE next_challenge_state SET metadataHash='bad'; END",
            "AFTER INSERT ON next_acceptances BEGIN UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1; UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1; END")
        faults.forEach { fault ->
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_timer_fault $fault")
            rejected { timers(http).accept(delivery) }; db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_timer_fault")
            unaccepted(first); assertEquals(local, db.timeLogDao().getTimeLogByUuid(first.sessionUuid))
            assertEquals(0, count("next_challenge_births")); assertEquals(20L, merger(http).state(access(), true)!!.cursor)
        }
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).accept(delivery))
    }

    @Test fun realDeferredFinalCommitFailureRollsBackBirthReceiptAndQueueAndColdRetrySucceeds() = runBlocking {
        val (http, _) = channel(::respond); initialize(http); val first = startRound()
        val delivery = timers(http).send(access(), first.commandId)!!
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE round_timer_guard(value TEXT REFERENCES next_challenge_rounds(roundUuid) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER round_timer_commit AFTER INSERT ON next_acceptances BEGIN INSERT INTO round_timer_guard VALUES('" + id(999) + "'); END")
        rejected { timers(http).accept(delivery) }; storage.reopen(); unaccepted(first)
        assertEquals(0, count("round_timer_guard")); assertEquals(0, count("next_challenge_births"))
        assertEquals(20L, merger(http).state(access(), true)!!.cursor)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_timer_commit")
        db.openHelper.writableDatabase.execSQL("DROP TABLE round_timer_guard")
        storage.reopen(); assertEquals(NextOperationAcceptance.COMMITTED, timers(http).accept(delivery))
    }

    @Test fun causalPlanAndSameSessionPredecessorsStillRequireOriginalAcceptedReceipts() = runBlocking {
        val (http, _) = channel(::respond); initialize(http)
        producer().writeRounds(producer().captureRounds()) { habits().updateHabit(timerHabit.copy(description = "Before start")) }
        val plan = db.syncOutboxDao().getAll().single(); val commands = offlineChain()
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { timers(http).sendAndAccept(access(), commands.first().commandId) } as NextRequestException).reason)
        assertEquals(0, count("next_transmissions"))
        sender(http).sendAndAcceptOperation(access(), plan.operationId)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { timers(http).sendAndAccept(access(), commands[1].commandId) } as NextRequestException).reason)
        producer().writeRounds(producer().captureRounds()) {
            habits().updateHabit(db.habitDao().getHabitById(timerHabit.id)!!.copy(targetValue = 2))
        }
        val later = db.syncOutboxDao().getAll().single()
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendAndAcceptOperation(access(), later.operationId) } as NextRequestException).reason)
        assertEquals(4, timers(http).pushPending(access()))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), later.operationId))
        assertEquals(TimerStartPolicy(60, false, 180), decodeNextTimerIntent(db.nextRequestDao().origin(NEXT_TIMER, id(21))!!.intentJson).command.startPolicy)
        assertEquals(2, db.habitDao().getHabitById(timerHabit.id)!!.targetValue)
    }

    @Test fun cancelledAcceptanceAndChangedAuthenticationNeverConsumeOriginalTimer() = runBlocking {
        val (http, _) = channel(::respond); initialize(http); val first = startRound()
        val delivery = timers(http).send(access(), first.commandId)!!
        sessions.exclusive { val job = launch(start = CoroutineStart.UNDISPATCHED) { timers(http).accept(delivery) }; job.cancelAndJoin() }
        unaccepted(first); assertEquals(0, count("next_challenge_births"))
        tokens.saveLoginSession("synthetic-other", "synthetic-r", "other", id(820), false)
        assertEquals(NextRequestException.Reason.STALE_ACCESS, (rejected { timers(http).accept(delivery) } as NextRequestException).reason)
        unaccepted(first); assertEquals(0, count("next_challenge_births"))
    }

    @Test fun strictOriginalProfileTagOrCapturedDeviceDamageCannotDowngradeTimerSend() = runBlocking {
        val (http, server) = channel(::respond); initialize(http); val first = startRound()
        val original = db.nextRequestDao().origin(NEXT_TIMER, first.commandId)!!
        val bodies = listOf("\"1\"", "1.0", "true", "2").map {
            original.intentJson.replace("\"challenge_contract\":1", "\"challenge_contract\":$it")
        } + original.intentJson.replace(id(4), id(41))
        bodies.forEach {
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?", arrayOf(it, first.commandId))
            rejected { timers(http).sendAndAccept(access(), first.commandId) }
        }
        assertEquals(0, count("next_transmissions")); assertTrue(server.requests.all { it.path.endsWith("/identity") })
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?", arrayOf(original.intentJson, first.commandId))
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).sendAndAccept(access(), first.commandId))
    }

    @Test fun completedBirthAndOriginalStopEnableActualFactPageAndSharedAllocationWithoutManualResult() = runBlocking {
        val (http, _) = channel(::respond); initialize(http); offlineChain(); assertEquals(4, timers(http).pushPending(access()))
        val end = Instant.ofEpochMilli(millis + 70_000).toString()
        val body = buildJsonObject {
            put("public_id", id(20)); put("revision", 1); put("created_at", end); put("updated_at", end); put("deleted_at", JsonNull)
            put("activity_uuid", timerHabit.uuid); put("event_type", "duration_session"); put("value", JsonNull)
            put("duration_seconds", 60); put("duration_milliseconds", 60_000); put("started_at", time); put("ended_at", end)
            put("occurred_at", end); put("received_at", end); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
            put("source_type", "app"); put("source_device_id", id(4)); put("external_event_id", id(20)); put("reverts_event_uuid", JsonNull)
            put("note", ""); put("metadata", buildJsonObject { put("timer_session_id", id(20)) })
            put("day_allocations", JsonArray(listOf(buildJsonObject {
                put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai"); put("duration_milliseconds", 60_000)
            })))
        }
        val store = merger(http); val before = store.state(access(), true)!!; val meta = store.challengeMetadata(access())!!
        val fact = SyncV2Change(21, "activity_event", id(20), "upsert", 1, body, end)
        val page = RoundSyncPullResponse(listOf(fact), 21, false, end, 1, meta.checkpoints, meta.births)
        // Internally consistent duration/allocation and within the start policy, but not the actual
        // original stop's 60 seconds. It cannot acquire acceptance by replacing that stop proof.
        val wrongFact = JsonObject(body + mapOf("duration_seconds" to JsonPrimitive(61),
            "duration_milliseconds" to JsonPrimitive(61_000), "day_allocations" to JsonArray(listOf(buildJsonObject {
                put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai"); put("duration_milliseconds", 61_000)
            }))))
        rejected { store.page(access(), before, page.copy(changes = listOf(fact.copy(payload = wrongFact)))) }
        assertEquals(before, store.state(access(), true)); assertNull(db.syncOutboxDao().getState("activity_event", id(20)))
        store.page(access(), before, page)
        storage.reopen(); assertEquals(21L, merger(http).state(access(), true)!!.cursor)
        assertEquals(60_000L, db.timeLogDao().getDayAllocations(id(20)).single().durationMillis)
        assertNotNull(db.syncOutboxDao().getState("activity_event", id(20)))
        assertEquals(initial(timerHabit.uuid).head, merger(http).challengeMetadata(access())!!.requireBirth("activity_event", id(20), timerHabit.uuid).head)
    }
}
