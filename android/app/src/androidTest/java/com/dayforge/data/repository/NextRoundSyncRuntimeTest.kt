package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual unified profile coordinator, not production activation or a service stopwatch. */
@RunWith(AndroidJUnit4::class)
class NextRoundSyncRuntimeTest : NextCoreRequestFixture() {
    private val json = Json { encodeDefaults = true }
    private lateinit var onceHabit: HabitEntity
    private val births = linkedMapOf<Pair<String, String>, ChallengeBirth>()
    private val accepted = mutableListOf<SyncV2Change>()
    private val replies = mutableMapOf<String, MaterialSocketServer.Reply>()
    private var countError: String? = null
    private var loseTimerControl = false
    private var loseFirst = false
    private var allowClosed = false
    private fun localOnce() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun runtime(http: NextSyncHttp) = NextSyncRuntime(db, tokens, sessions, http, preferences)
    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions,
        OneTimeAcceptedEventStore(db, tokens, sessions, localOnce()), NextTimerRequestStore(db, tokens, sessions, sender(http)))
    private fun initial(activity: String) = ChallengeRoundRecord(initialChallengeRoundHead(activity), null, null, null)
    private fun metadata() = ChallengeMetadata(1, listOf(habit.uuid, timerHabit.uuid).map {
        ChallengeCheckpoint(initial(it).head, listOf(initial(it))) }, births.values.toList())
    private fun canonical(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun snapshot(): RoundSyncBootstrapResponse {
        val meta = metadata()
        return RoundSyncBootstrapResponse(listOf(habit, timerHabit, onceHabit).map {
            canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it)) } + canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)),
            20, time, listOf(OneTimeProjection(onceHabit.uuid, OneTimeState(0, null, null))), 1, meta.checkpoints, meta.births)
    }
    private suspend fun setupObjects() {
        register()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val once = habit.copy(id = 0, uuid = id(13), name = "Independent once", habitType = HabitType.CHECK_IN,
                schedule = HabitSchedule.Once(), targetValue = 1, completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
            onceHabit = once.copy(id = db.habitDao().insert(once))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }
    private suspend fun initialize(http: NextSyncHttp) { setupObjects(); merger(http).bootstrap(access(), null, snapshot()) }
    private fun record(result: NextSyncOperationResult) {
        val body = requireNotNull(result.entity)
        accepted += SyncV2Change(20L + accepted.size + 1, result.entityType, result.entityUuid, "upsert",
            requireNotNull(result.revision), body, body.getValue("updated_at").jsonPrimitive.content)
    }
    private fun completed(timer: TimerSessionResponse) = canonical("activity_event", timer.sessionId, buildJsonObject {
        put("activity_uuid", timer.activityUuid); put("event_type", "duration_session"); put("value", JsonNull)
        put("duration_seconds", 60); put("duration_milliseconds", 60_000); put("started_at", time)
        put("ended_at", timer.endedAt); put("occurred_at", timer.endedAt); put("received_at", time)
        put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai"); put("source_type", "app")
        put("source_device_id", id(4)); put("external_event_id", timer.sessionId); put("note", "")
        put("reverts_event_uuid", JsonNull); put("metadata", buildJsonObject { put("timer_session_id", timer.sessionId) })
        put("day_allocations", buildJsonArray { add(buildJsonObject {
            put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai"); put("duration_milliseconds", 60_000)
        }) })
    })
    private fun respond(input: MaterialSocketServer.Input): MaterialSocketServer.Reply? {
        if (input.path.endsWith("/identity")) return reply(input)
        if (input.path.endsWith("/bootstrap")) {
            assertEquals("/api/v2/sync/rounds/bootstrap", input.path)
            return MaterialSocketServer.Reply(json.encodeToString(snapshot()).toByteArray())
        }
        if (input.path.endsWith("/changes")) {
            assertEquals("/api/v2/sync/rounds/changes", input.path)
            val cursor = input.target.substringAfter("cursor=").substringBefore('&').toLong()
            val meta = metadata()
            return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(accepted.filter { it.sequence > cursor },
                20L + accepted.size, false, time, 1, meta.checkpoints, meta.births)).toByteArray())
        }
        val source: String
        val response: MaterialSocketServer.Reply
        if (input.path.endsWith("/commands")) {
            assertEquals("/api/v2/timers/rounds/commands", input.path)
            val request = json.decodeFromString<RoundTimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8))
            val command = request.commands.single(); source = command.commandId
            replies[source]?.let { return it.copy(allowClientClose = allowClosed) }
            val stop = command.commandType == "stop"
            val controlLost = stop && loseTimerControl
            val terminal = stop && !controlLost
            val timer = TimerSessionResponse(command.sessionId, timerHabit.uuid, if (terminal) "completed" else "running",
                if (controlLost) id(5) else id(4), if (controlLost) 2 else 1, command.sequence, command.sequence + 1, time, command.occurredAt,
                endedAt = command.occurredAt.takeIf { terminal }, timezone = "Asia/Shanghai", isCountdown = false,
                targetSeconds = 60, maxDurationSeconds = 180, activeElapsedMs = if (terminal) 60_000 else 0,
                completedEventId = command.sessionId.takeIf { terminal })
            births["timer_session" to timer.sessionId] = ChallengeBirth("timer_session", timer.sessionId, request.contexts.single().head!!)
            if (terminal) {
                births["activity_event" to timer.sessionId] = ChallengeBirth("activity_event", timer.sessionId, request.contexts.single().head!!)
                val fact = completed(timer); accepted += fact.copy(sequence = 20L + accepted.size + 1)
            }
            val meta = metadata()
            response = MaterialSocketServer.Reply(json.encodeToString(RoundTimerCommandBatchResponse(listOf(
                TimerCommandResult(command.commandId, command.sessionId, if (controlLost) "conflict" else "applied",
                    errorCode = "CONTROL_LOST".takeIf { controlLost }, session = timer)), time, 1,
                meta.checkpoints, meta.births)).toByteArray())
        } else {
            assertEquals("/api/v2/sync/rounds/push", input.path)
            val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
            val operation = request.operations.single(); source = operation.operationId
            replies[source]?.let { return it.copy(allowClientClose = allowClosed) }
            val error = countError.takeIf { operation.payload["count_policy"] != null }
            val results = if (error != null) listOf(NextSyncOperationResult(source, operation.entityType, operation.entityUuid,
                "rejected", errorCode = error)) else json.decodeFromString<NextSyncPushResponse>(successReply(input,
                (operation.baseRevision ?: 0) + 1) { body ->
                if (operation.payload["one_time"] is JsonObject) {
                    val intent = operation.payload.getValue("one_time").jsonObject
                    JsonObject(body + ("one_time_state_after" to buildJsonObject {
                        put("version", intent.getValue("expected_version").jsonPrimitive.int + 1); put("head_event_uuid", intent.getValue("event_uuid"))
                        put("completion_event_uuid", if (intent["action"] == JsonPrimitive("complete")) intent.getValue("event_uuid") else JsonNull)
                    }))
                } else if (operation.entityType == "plan_node") JsonObject(body + ("created_at" to JsonPrimitive(time))) else body
            }.bytes.toString(Charsets.UTF_8)).results
            if (error == null) {
                results.forEach(::record)
                if (operation.entityType == "activity_event" && operation.payload["one_time"] == null)
                    births["activity_event" to operation.entityUuid] = ChallengeBirth("activity_event", operation.entityUuid, request.contexts.single().head!!)
            }
            val meta = metadata()
            response = MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(results, 1, meta.checkpoints, meta.births)).toByteArray())
            if (error != null) return response // Transient errors may become ready without a new request ID.
        }
        replies[source] = response
        if (loseFirst) { loseFirst = false; return null }
        return response.copy(allowClientClose = allowClosed)
    }
    private suspend fun countFact() {
        producer().writeRounds(producer().captureRounds()) {
            val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(210), value = 3,
                date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
                    .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                actualCompletedAt = millis, recordedTimezone = "Asia/Shanghai", recordedLocalDate = "2026-10-06")
            NextCountDayStore(db).capture(habit, fact); db.completionDao().insert(fact)
        }
    }
    private suspend fun observationFact() { producer().writeRounds(producer().captureRounds()) { metrics().recordValue(metric.id, 21.125, "original", millis) } }
    private suspend fun onceChain() {
        repeat(2) { n -> val state = localOnce().read(onceHabit.uuid).queue.optimisticState
            localOnce().appendRounds(producer().captureRounds(), OneTimeLocalCommand(onceHabit.uuid,
                PendingOneTimeIntent(id(500 + n), OneTimeIntent(id(600 + n), if (n == 0) "complete" else "undo",
                    state.version, state.headEventUuid, state.completionEventUuid)), millis + n, "Asia/Shanghai")) }
    }
    private suspend fun timerChain() {
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, uuid = id(20), startTime = millis,
                endTime = null, durationSeconds = 0, date = millis, timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start", occurredAt = millis,
                    expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
        }
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().finishTimerAndQueue(db.timeLogDao().getTimeLogByUuid(id(20))!!.id, millis + 60_000, 60, 0, 3, 60_000,
                TimerCommandEntity(commandId = id(22), sessionUuid = id(20), sequence = 2, commandType = "stop",
                    occurredAt = millis + 60_000, expectedControlGeneration = 1, activeElapsedMillis = 60_000), false)
        }
    }

    @Test fun realCoordinatorDrainsPlanTimerCountObservationAndOnceThenCommitsActualFactPageAndColdResume() = runBlocking {
        val (http, server) = channel(::respond); initialize(http)
        producer().writeRounds(producer().captureRounds()) { habits().updateHabit(timerHabit.copy(description = "Before start")) }
        timerChain(); countFact(); observationFact(); onceChain()
        storage.reopen(); runtime(http).syncRounds()
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertTrue(db.timeLogDao().getPendingTimerCommands().isEmpty())
        assertEquals(2, localOnce().read(onceHabit.uuid).confirmed.version); assertNull(localOnce().read(onceHabit.uuid).confirmed.completionEventUuid)
        assertEquals(60_000L, db.timeLogDao().getDayAllocations(id(20)).single().durationMillis)
        assertEquals(3, db.completionDao().getCompletionByUuid(id(210))!!.value); assertEquals(10, db.countDayDao().get(habit.id, "2026-10-06")!!.targetValue)
        assertEquals(26L, merger(http).state(access(), true)!!.cursor); assertNotNull(preferences.lastSyncTimestamp.first())
        val posts = server.requests.filter { it.method == "POST" }; assertEquals(7, posts.size)
        assertEquals("/api/v2/sync/rounds/push", posts.first().path); assertEquals(listOf("/api/v2/timers/rounds/commands", "/api/v2/timers/rounds/commands"), posts.drop(1).take(2).map { it.path })
        val calls = posts.size; storage.reopen(); runtime(http).syncRounds()
        assertEquals(calls, server.requests.count { it.method == "POST" }); assertEquals(26L, merger(http).state(access(), true)!!.cursor)
        rejected { runtime(http).sync() }; assertEquals(calls, server.requests.count { it.method == "POST" })
    }

    @Test fun coldProfileBootstrapUsesOnlyExplicitRoutesButLegacyPendingWorkStopsBeforeNetwork() = runBlocking {
        val (http, server) = channel(::respond); setupObjects()
        val row = observation(); val original = originalIntent(row)
        rejected { runtime(http).syncRounds() }; assertTrue(server.requests.isEmpty()); assertEquals(original, originalIntent(row))
        assertEquals(0, count("next_transmissions")); assertNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun cleanColdBootstrapAndEmptyPagesEstablishProfileCursorAndActualSyncSuccess() = runBlocking {
        val (http, server) = channel(::respond); setupObjects(); runtime(http).syncRounds()
        assertEquals(20L, merger(http).state(access(), true)!!.cursor); assertNotNull(preferences.lastSyncTimestamp.first())
        assertTrue(server.requests.any { it.path == "/api/v2/sync/rounds/bootstrap" }); assertTrue(server.requests.none { it.method == "POST" })
        storage.reopen(); runtime(http).syncRounds(); assertEquals(1, server.requests.count { it.path.endsWith("/bootstrap") })
    }

    @Test fun lostTimerReplyKeepsOriginalWholeEnvelopeAndUnifiedColdRetryDoesNotDuplicateFact() = runBlocking {
        val (http, server) = channel(::respond); initialize(http); timerChain(); loseFirst = true
        rejected { runtime(http).syncRounds() }; assertNull(preferences.lastSyncTimestamp.first()); assertEquals(2, db.timeLogDao().getPendingTimerCommands().size)
        val frozen = transmission(NEXT_TIMER, id(21)); storage.reopen(); runtime(http).syncRounds()
        val bodies = server.requests.filter { it.path.endsWith("/commands") }.map { it.body }
        assertEquals(3, bodies.size); assertArrayEquals(frozen.wireBytes, bodies[0]); assertArrayEquals(frozen.wireBytes, bodies[1])
        assertEquals(1, accepted.size); assertEquals(21L, merger(http).state(access(), true)!!.cursor)
        assertEquals(60_000L, db.timeLogDao().getDayAllocations(id(20)).single().durationMillis)
    }

    @Test fun permanentCountRejectionDoesNotStarveObservationAndCannotMarkWholeSyncSuccessful() = runBlocking {
        val (http, _) = channel(::respond); initialize(http); countFact(); observationFact(); countError = "COUNT_POLICY_CONFLICT"
        assertTrue(rejected { runtime(http).syncRounds() } is NextSyncAttention)
        assertEquals(1, count("next_rejections")); assertEquals(1, db.syncOutboxDao().getAll().size)
        assertEquals(1, count("next_acceptances")); assertNull(preferences.lastSyncTimestamp.first()); assertEquals(21L, merger(http).state(access(), true)!!.cursor)
    }

    @Test fun transientCountRejectionRemainsOriginalRetryAndNeverBecomesPermanentOrFakeSuccess() = runBlocking {
        val (http, server) = channel(::respond); initialize(http); countFact(); observationFact(); countError = "MISSING_PREDECESSOR"
        assertTrue(rejected { runtime(http).syncRounds() } is NextSyncRetryRequired)
        val row = db.syncOutboxDao().getAll().single(); val frozen = transmission(NEXT_OPERATION, row.operationId)
        assertEquals(0, count("next_rejections")); assertNull(preferences.lastSyncTimestamp.first()); countError = null
        storage.reopen(); runtime(http).syncRounds()
        val requests = server.requests.filter { it.path.endsWith("/push") && wireOperation(it)["operation_id"] == JsonPrimitive(row.operationId) }
        assertEquals(2, requests.size); requests.forEach { assertArrayEquals(frozen.wireBytes, it.body) }
        assertEquals(22L, merger(http).state(access(), true)!!.cursor); assertNotNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun lateRejectionCheckpointFaultRollsBackProofButOriginalRequestCanRecordAndRetryUnchanged() = runBlocking {
        val (http, _) = channel(::respond); initialize(http); countFact(); countError = "COUNT_POLICY_CONFLICT"
        val row = db.syncOutboxDao().getAll().single(); val delivery = sender(http).sendOperation(access(), row.operationId)!!
        val result = json.encodeToString(delivery.result.results.single())
        rejected { sender(http).recordRejection(access(), NEXT_OPERATION, row.operationId, delivery.transmissionProof, result) }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_reject AFTER INSERT ON next_rejections BEGIN UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1; UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1; END")
        rejected { sender(http).recordRejection(access(), NEXT_OPERATION, row.operationId, delivery.transmissionProof, result, delivery.challengeMetadata) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_reject"); assertEquals(0, count("next_rejections"))
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(20L, merger(http).state(access(), true)!!.cursor)
        sender(http).recordRejection(access(), NEXT_OPERATION, row.operationId, delivery.transmissionProof, result, delivery.challengeMetadata)
        assertEquals(1, count("next_rejections")); assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(20L, merger(http).state(access(), true)!!.cursor)
    }

    @Test fun mixedPlainOriginUnderProfileStopsAllQueuesBeforeAnyHttpOrConsumption() = runBlocking {
        val (http, server) = channel(::respond); initialize(http); countFact(); observationFact()
        val row = db.syncOutboxDao().getAll().first(); val original = originalIntent(row)
        val bare = json.encodeToString(roundOperationIntent(original.intentJson)!!.operation)
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?", arrayOf(bare, row.operationId))
        rejected { runtime(http).syncRounds() }; assertTrue(server.requests.isEmpty()); assertEquals(2, db.syncOutboxDao().getAll().size)
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("next_acceptances")); assertNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun actualTimerControlConflictNeedsFullMetadataAndReplyTimeAndLateFaultRollsBackBeforeUnifiedRejection() = runBlocking {
        val (http, server) = channel(::respond); initialize(http); timerChain(); observationFact(); loseTimerControl = true
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        timers.sendAndAccept(access(), id(21))
        val command = db.timeLogDao().getPendingTimerCommands().single()
        val delivery = timers.send(access(), command.commandId)!!
        val result = json.encodeToString(delivery.result.results.single())
        rejected { sender(http).recordRejection(access(), NEXT_TIMER, command.commandId, delivery.transmissionProof,
            result, serverTime = delivery.result.serverTime) }
        rejected { sender(http).recordRejection(access(), NEXT_TIMER, command.commandId, delivery.transmissionProof,
            result, delivery.challengeMetadata) }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_timer_reject AFTER INSERT ON next_rejections BEGIN UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1; UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1; END")
        rejected { sender(http).recordRejection(access(), NEXT_TIMER, command.commandId, delivery.transmissionProof,
            result, delivery.challengeMetadata, delivery.result.serverTime) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_timer_reject")
        assertEquals(0, count("next_rejections")); assertEquals(command, db.timeLogDao().getPendingTimerCommands().single())
        assertEquals(20L, merger(http).state(access(), true)!!.cursor)
        storage.reopen(); assertTrue(rejected { runtime(http).syncRounds() } is NextSyncAttention)
        assertEquals(1, count("next_rejections")); assertEquals(command, db.timeLogDao().getPendingTimerCommands().single())
        assertEquals(2, count("next_acceptances")); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertEquals(millis + 60_000, db.timeLogDao().getTimeLogByUuid(id(20))!!.endTime)
        assertTrue(db.timeLogDao().getDayAllocations(id(20)).isEmpty())
        assertEquals(1, accepted.size); assertEquals("metric_observation", accepted.single().entityType)
        val actualBirths = db.withTransaction { NextChallengeStore(db).activeInTransaction(access()).second.births }
        assertNull(actualBirths.singleOrNull { it.entityType == "activity_event" && it.entityUuid == id(20) })
        assertEquals(21L, merger(http).state(access(), true)!!.cursor); assertNull(preferences.lastSyncTimestamp.first())
        val stopRequests = server.requests.filter { it.path.endsWith("/commands") &&
            json.parseToJsonElement(it.body.toString(Charsets.UTF_8)).jsonObject.getValue("commands").jsonArray.single()
                .jsonObject["command_id"] == JsonPrimitive(command.commandId) }
        val frozen = transmission(NEXT_TIMER, command.commandId).wireBytes
        assertEquals(2, stopRequests.size); stopRequests.forEach { assertArrayEquals(frozen, it.body) }
        val posts = server.requests.count { it.method == "POST" }; storage.reopen()
        assertTrue(rejected { runtime(http).syncRounds() } is NextSyncAttention)
        assertEquals(posts, server.requests.count { it.method == "POST" }); assertNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun pageCommitFailureDoesNotUndoAcceptedUploadsOrMarkSuccessAndColdRetryOnlyReads() = runBlocking {
        val (http, server) = channel(::respond); initialize(http); countFact(); observationFact()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_page BEFORE UPDATE ON next_sync_state BEGIN SELECT RAISE(IGNORE); END")
        rejected { runtime(http).syncRounds() }; assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertEquals(2, count("next_acceptances")); assertEquals(20L, merger(http).state(access(), true)!!.cursor); assertNull(preferences.lastSyncTimestamp.first())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_page"); val posts = server.requests.count { it.method == "POST" }
        storage.reopen(); runtime(http).syncRounds()
        assertEquals(posts, server.requests.count { it.method == "POST" }); assertEquals(22L, merger(http).state(access(), true)!!.cursor)
        assertNotNull(preferences.lastSyncTimestamp.first()); assertEquals(3, db.completionDao().getCompletionByUuid(id(210))!!.value)
    }
}
