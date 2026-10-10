package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real physical Room, original producers, HTTP rejections and cold proof reads. No recovery writes. */
@RunWith(AndroidJUnit4::class)
class SyncProblemReaderTest : NextCoreRequestFixture() {
    private val codec = Json { encodeDefaults = true }
    private lateinit var http: NextSyncHttp
    private lateinit var server: MaterialSocketServer
    private lateinit var onceHabit: HabitEntity
    private var rejectedCode: String? = null
    private var normalizeName: String? = null
    private var normalizeTarget: Int? = null
    private fun localOnce() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun onceStore() = NextOneTimeRequestStore(db, tokens, sessions, http,
        OneTimeAcceptedEventStore(db, tokens, sessions, localOnce()), sender(http))
    private fun reader() = NextSyncRuntime(db, tokens, sessions, http, preferences)
    private fun metadata() = ChallengeMetadata(1, listOf(habit, timerHabit).map {
        val record = ChallengeRoundRecord(initialChallengeRoundHead(it.uuid), null, null, null)
        ChallengeCheckpoint(record.head, listOf(record))
    }, emptyList())
    private fun canonical(row: HabitEntity) = SyncV2Change(0, "plan_node", row.uuid, "upsert", 1,
        JsonObject(NextStructureMapper.writePlan(row) + mapOf("public_id" to JsonPrimitive(row.uuid),
            "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
            "deleted_at" to JsonNull)), time)
    private suspend fun initialize() {
        register()
        val channel = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else {
                val meta = metadata()
                val result = if (input.path.endsWith("/commands")) {
                    val cmd = codec.decodeFromString<RoundTimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8)).commands.single()
                    return@channel MaterialSocketServer.Reply(codec.encodeToString(RoundTimerCommandBatchResponse(
                        listOf(TimerCommandResult(cmd.commandId, cmd.sessionId, "rejected", errorCode = "TIMER_CONTROL_CONFLICT")),
                        time, 1, meta.checkpoints, meta.births)).toByteArray())
                } else {
                    val op = codec.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8)).operations.single()
                    val code = rejectedCode
                    if (code != null) NextSyncOperationResult(op.operationId, op.entityType, op.entityUuid,
                        "rejected", errorCode = code)
                    else codec.decodeFromString<NextSyncPushResponse>(successReply(input, 2) { body ->
                        val originalBirth = JsonObject(body + ("created_at" to JsonPrimitive(time)))
                        if (body["activity"] is JsonObject && normalizeTarget != null) JsonObject(originalBirth +
                            ("activity" to JsonObject(body.getValue("activity").jsonObject + ("target_value" to JsonPrimitive(normalizeTarget)))))
                        else normalizeName?.let { JsonObject(originalBirth + ("name" to JsonPrimitive(it))) } ?: originalBirth
                    }.bytes.toString(Charsets.UTF_8)).results.single()
                }
                MaterialSocketServer.Reply(codec.encodeToString(RoundSyncPushResponse(listOf(result), 1,
                    meta.checkpoints, meta.births)).toByteArray())
            }
        }
        http = channel.first; server = channel.second
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val item = habit.copy(id = 0, uuid = id(13), name = "Once", habitType = HabitType.CHECK_IN,
                schedule = HabitSchedule.Once(), targetValue = 1, completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
            onceHabit = item.copy(id = db.habitDao().insert(item))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val meta = metadata()
        val metricChange = SyncV2Change(0, "metric", metric.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writeMetric(metric) + mapOf("public_id" to JsonPrimitive(metric.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
                "deleted_at" to JsonNull)), time)
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions, localOnce()),
            NextTimerRequestStore(db, tokens, sessions, sender(http))).bootstrap(access(), null,
            RoundSyncBootstrapResponse(listOf(habit, timerHabit, onceHabit).map(::canonical) + metricChange, 20,
                time, listOf(OneTimeProjection(onceHabit.uuid, OneTimeState(0, null, null))), 1, meta.checkpoints, meta.births))
    }
    private suspend fun edit(name: String): SyncOutboxEntity {
        producer().writeRounds(producer().captureRounds()) {
            db.metricDao().update(requireNotNull(db.metricDao().getMetricById(metric.id)).copy(name = name))
        }
        return db.syncOutboxDao().getAll().last()
    }
    private suspend fun start(): TimerCommandEntity {
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, startTime = millis, endTime = null,
                durationSeconds = 0, date = millis, uuid = id(20), timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
        }
        return db.timeLogDao().getPendingTimerCommands().single()
    }
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun readOnly(): SyncProblems.Next {
        val before = proof()
        val preferencesBefore = dataStore.data.first()
        val calls = server.requests.size
        val result = reader().readProblems() as SyncProblems.Next
        assertEquals(before, proof()); assertEquals(preferencesBefore, dataStore.data.first())
        assertEquals(calls, server.requests.size)
        return result
    }
    private suspend fun refuse(row: SyncOutboxEntity) {
        rejectedCode = "INVALID_PAYLOAD"
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        sender(http).recordRejection(access(), NEXT_OPERATION, row.operationId, delivery.transmissionProof,
            encodeSyncRequest(NextSyncOperationResult.serializer(), delivery.result.results.single()).toString(Charsets.UTF_8), delivery.challengeMetadata)
    }

    @Test fun ordinaryPendingIsNotAnIssueAndPermanentRejectionIsReadOnlyAfterColdReopen() = runBlocking<Unit> {
        initialize(); val row = edit("Local")
        assertEquals(0, readOnly().count); refuse(row)
        val expected = listOf(NextSyncProblem(NEXT_OPERATION, row.operationId, "metric", metric.uuid, "INVALID_PAYLOAD"))
        assertEquals(expected, readOnly().items)
        storage.reopen(); assertEquals(expected, readOnly().items)
        assertEquals(row, db.syncOutboxDao().getById(row.id))
    }
    @Test fun permissionLossShowsOnlyActualUnsentPermissionBlockAndStillReadsSavedRejection() = runBlocking<Unit> {
        initialize(); val first = edit("First"); refuse(first)
        val second = edit("Second")
        register(permissions = caps - "structure.write", revision = 2)
        val items = readOnly().items
        assertEquals("INVALID_PAYLOAD", items.single { it.requestId == first.operationId }.code)
        assertEquals("PERMISSION_DENIED", items.single { it.requestId == second.operationId }.code)
    }
    @Test fun sameEntityPredecessorWaitAndRealOverlapAreComputedWithoutInstallingReplacement() = runBlocking<Unit> {
        initialize(); val first = edit("First"); val second = edit("Second")
        assertEquals("CAUSAL_PREDECESSOR_PENDING", readOnly().items.single().code)
        normalizeName = "Server"
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), first.operationId))
        val problem = readOnly().items.single()
        assertEquals(second.operationId, problem.requestId); assertEquals("STRUCTURAL_CAUSAL_CONFLICT", problem.code)
        assertEquals(listOf("name"), problem.fields); assertEquals(0, count("next_structural_supersessions"))
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, second.operationId))
    }
    @Test fun cleanStructuralMergePreviewDoesNotActuallyRebase() = runBlocking<Unit> {
        initialize(); val first = edit("First")
        producer().writeRounds(producer().captureRounds()) {
            db.metricDao().update(db.metricDao().getMetricById(metric.id)!!.copy(unit = "lbs"))
        }
        val second = db.syncOutboxDao().getAll().last()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), first.operationId))
        assertEquals(0, readOnly().count); assertEquals(second, db.syncOutboxDao().getById(second.id))
        assertEquals(0, count("next_structural_supersessions"))
    }
    @Test fun timerPermanentRejectionUsesCommandAndSessionIdentityNotLegacyRow() = runBlocking<Unit> {
        initialize(); val row = start(); val core = sender(http)
        val delivery = requireNotNull(NextTimerRequestStore(db, tokens, sessions, core).send(access(), row.commandId))
        core.recordRejection(access(), NEXT_TIMER, row.commandId, delivery.transmissionProof,
            encodeSyncRequest(TimerCommandResult.serializer(), delivery.result.results.single()).toString(Charsets.UTF_8),
            delivery.challengeMetadata, delivery.result.serverTime)
        val problem = readOnly().items.single()
        assertEquals(NEXT_TIMER, problem.kind); assertEquals(row.commandId, problem.requestId)
        assertEquals(row.sessionUuid, problem.entityUuid); assertEquals("TIMER_CONTROL_CONFLICT", problem.code)
        storage.reopen(); assertEquals(listOf(problem), readOnly().items)
    }
    @Test fun timerStartRuleMismatchIsReadOnlyAndDoesNotCompareAgainstTheMutableLocalPlan() = runBlocking<Unit> {
        initialize()
        producer().writeRounds(producer().captureRounds()) { db.habitDao().update(timerHabit.copy(name = "Before start")) }
        val predecessor = db.syncOutboxDao().getAll().single(); val row = start()
        normalizeTarget = 120
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), predecessor.operationId))
        val problem = readOnly().items.single()
        assertEquals(row.commandId, problem.requestId); assertEquals("TIMER_START_CONFIG_CHANGED", problem.code)
        assertNull(db.nextRequestDao().transmission(NEXT_TIMER, row.commandId))
    }
    @Test fun rejectedTimerAttachmentStillRequiresExactSessionAndActualBirth() = runBlocking<Unit> {
        initialize(); val row = start(); val core = sender(http)
        val delivery = requireNotNull(NextTimerRequestStore(db, tokens, sessions, core).send(access(), row.commandId))
        val timer = TimerSessionResponse(row.sessionUuid, timerHabit.uuid, "running", id(4), 1, 1, 2,
            time, time, timezone = "Asia/Shanghai", isCountdown = false, targetSeconds = 60,
            maxDurationSeconds = 180, activeElapsedMs = 0)
        val result = delivery.result.results.single().copy(session = timer)
        val meta = metadata().copy(births = listOf(ChallengeBirth("timer_session", row.sessionUuid,
            initialChallengeRoundHead(timerHabit.uuid))))
        core.recordRejection(access(), NEXT_TIMER, row.commandId, delivery.transmissionProof,
            encodeSyncRequest(TimerCommandResult.serializer(), result).toString(Charsets.UTF_8), meta, delivery.result.serverTime)
        assertEquals("TIMER_CONTROL_CONFLICT", readOnly().items.single().code)
        val saved = db.nextSyncStateDao().rejections().single()
        for (badTimer in listOf(timer.copy(sessionId = id(92)), timer.copy(completedEventId = id(93)))) {
            val bytes = encodeSyncRequest(TimerCommandResult.serializer(), result.copy(session = badTimer))
            db.openHelper.writableDatabase.execSQL("UPDATE next_rejections SET resultJson=?,resultHash=?",
                arrayOf(bytes.toString(Charsets.UTF_8), nextRequestHash(bytes)))
            val before = proof()
            assertNotNull(rejected { reader().readProblems() }); assertEquals(before, proof())
        }
        db.openHelper.writableDatabase.execSQL("UPDATE next_rejections SET resultJson=?,resultHash=?",
            arrayOf(saved.resultJson, saved.resultHash))
        storage.reopen(); assertEquals("TIMER_CONTROL_CONFLICT", readOnly().items.single().code)
    }
    @Test fun countStartRuleMismatchPreservesOriginalDayPolicyAndUnsentFact() = runBlocking<Unit> {
        initialize()
        producer().writeRounds(producer().captureRounds()) { db.habitDao().update(habit.copy(name = "Before count")) }
        val predecessor = db.syncOutboxDao().getAll().single()
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val event = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(90),
            date = millis - 8 * 60 * 60 * 1000L, actualCompletedAt = millis, value = 1, recordedTimezone = "Asia/Shanghai")
        producer().writeRounds(producer().captureRounds()) {
            NextCountDayStore(db).capture(current, event); db.completionDao().insertForSync(event)
        }
        val fact = db.syncOutboxDao().getAll().last()
        normalizeTarget = 20
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), predecessor.operationId))
        val problem = readOnly().items.single()
        assertEquals(fact.operationId, problem.requestId); assertEquals("COUNT_START_CONFIG_CHANGED", problem.code)
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, fact.operationId))
        assertEquals(10, db.withTransaction { NextCountDayStore(db).read(current, "2026-10-06")!!.policy.targetValue })
    }
    @Test fun onceRejectionIsCountedExactlyOnceAndDoesNotRewriteProjectionOrQueue() = runBlocking<Unit> {
        initialize()
        localOnce().appendRounds(producer().captureRounds(), OneTimeLocalCommand(onceHabit.uuid,
            PendingOneTimeIntent(id(60), OneTimeIntent(id(61), "complete", 0, null, null)), millis, "Asia/Shanghai"))
        rejectedCode = "INVALID_PAYLOAD"
        assertTrue(onceStore().sendAndAccept(access(), id(60)) is NextOneTimeOutcome.Rejected)
        assertTrue(db.nextSyncStateDao().rejections().isEmpty())
        val problem = readOnly().items.single()
        assertEquals(id(60), problem.requestId); assertEquals("one_time_event", problem.entityType)
        assertEquals("INVALID_PAYLOAD", problem.code)
        storage.reopen(); assertEquals(listOf(problem), readOnly().items)
    }
    @Test fun wrongAccountOrReplicaCannotReadPriorAccountProblem() = runBlocking<Unit> {
        initialize(); refuse(edit("Local"))
        tokens.saveLoginSession("other", "other-refresh", "member", id(100), false)
        register(); assertNotNull(rejected { reader().readProblems() })
    }
    @Test fun plainProfileAndOrphanNeverFallBackToLegacyActions() = runBlocking<Unit> {
        initialize(); refuse(edit("Local")); val saved = db.nextSyncStateDao().rejections().single()
        db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET challengeContract=0")
        assertNotNull(rejected { reader().readProblems() })
        db.clearAllData(); db.nextSyncStateDao().insertRejection(saved)
        assertNotNull(rejected { reader().readProblems() })
    }
    @Test fun damagedResultHashMissingOriginAndRawTypeDamageNeverSilentlyDisappear() = runBlocking<Unit> {
        initialize(); val row = edit("Local"); refuse(row)
        val sql = db.openHelper.writableDatabase
        val original = db.nextSyncStateDao().rejections().single()
        sql.execSQL("UPDATE next_rejections SET resultHash='damaged'")
        assertNotNull(rejected { reader().readProblems() })
        sql.execSQL("UPDATE next_rejections SET resultHash=?", arrayOf(original.resultHash))
        sql.execSQL("UPDATE next_request_origins SET protocol=5.5")
        assertNotNull(rejected { reader().readProblems() })
        sql.execSQL("UPDATE next_request_origins SET protocol=5")
        sql.execSQL("DELETE FROM next_structural_dependencies WHERE operationId=?", arrayOf(row.operationId))
        sql.execSQL("DELETE FROM next_request_origins WHERE requestId=?", arrayOf(row.operationId))
        assertNotNull(rejected { reader().readProblems() })
    }
    @Test fun sameCountRejectionChangeEmitsAndReadsNewCode() = runBlocking<Unit> {
        initialize(); val row = edit("Local"); refuse(row)
        val saved = db.nextSyncStateDao().rejections().single()
        val result = codec.decodeFromString<NextSyncOperationResult>(saved.resultJson).copy(errorCode = "ENTITY_DELETED")
        val bytes = encodeSyncRequest(NextSyncOperationResult.serializer(), result)
        val initial = CompletableDeferred<Unit>()
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            reader().problemChanges().first {
                val code = (reader().readProblems() as SyncProblems.Next).items.single().code
                if (code == "INVALID_PAYLOAD") initial.complete(Unit)
                code == "ENTITY_DELETED"
            }
        }
        withTimeout(5000) { initial.await() }
        db.withTransaction { db.openHelper.writableDatabase.execSQL(
            "UPDATE next_rejections SET resultJson=?,resultHash=?", arrayOf(bytes.toString(Charsets.UTF_8), nextRequestHash(bytes))) }
        withTimeout(5000) { changed.await() }
        assertEquals(1, readOnly().count)
    }
    @Test fun cancellationPropagatesWithoutMutatingAnyProof() = runBlocking<Unit> {
        initialize(); refuse(edit("Local")); val before = proof()
        sessions.exclusive {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { reader().readProblems(); fail("cancelled read ran") }
            job.cancelAndJoin()
        }
        assertEquals(before, proof())
    }
    @Test fun frozenUnknownOutcomeIsNotMisreportedAsPermanentRejectionOrRebasedByDisplay() = runBlocking<Unit> {
        initialize(); val row = edit("Local")
        sender(http).sendOperation(access(), row.operationId) // A delivered response alone is not an acceptance receipt.
        val wire = db.nextRequestDao().transmission(NEXT_OPERATION, row.operationId)!!.wireBytes.copyOf()
        assertEquals(0, readOnly().count)
        assertArrayEquals(wire, db.nextRequestDao().transmission(NEXT_OPERATION, row.operationId)!!.wireBytes)
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
    }
    @Test fun legacySnapshotPreservesRealOldRowsButLogoutDoesNotPublishTheirContent() = runBlocking<Unit> {
        initialize(); db.clearAllData()
        val row = SyncOutboxEntity(operationId = id(80), recordType = "metric", entityUuid = metric.uuid,
            wireEntityUuid = metric.uuid, action = "upsert", lastError = "legacy", errorCode = "INVALID_PAYLOAD",
            deadLetteredAt = millis, createdAt = millis)
        val saved = row.copy(id = db.syncOutboxDao().insert(row))
        val before = proof()
        val result = reader().readProblems() as SyncProblems.Legacy
        assertEquals(listOf(saved), result.changes); assertEquals(1, result.count); assertEquals(before, proof())
        tokens.clearTokens()
        val loggedOut = reader().readProblems() as SyncProblems.Legacy
        assertEquals(0, loggedOut.count); assertNull(loggedOut.session)
        assertEquals(before, proof())
    }
}
