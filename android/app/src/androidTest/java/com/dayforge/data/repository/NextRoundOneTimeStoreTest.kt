package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual once CAS stays independent from round heads; real Room/auth/socket and cold restart. */
@RunWith(AndroidJUnit4::class)
class NextRoundOneTimeStoreTest : NextCoreRequestFixture() {
    private val json = Json { encodeDefaults = true }
    private fun localOnce() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun facts() = OneTimeAcceptedEventStore(db, tokens, sessions, localOnce())
    private fun store(http: NextSyncHttp) = NextOneTimeRequestStore(db, tokens, sessions, http, facts(), sender(http))
    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions, facts(), NextTimerRequestStore(db, tokens, sessions, sender(http)))
    private fun initial() = ChallengeRoundRecord(initialChallengeRoundHead(timerHabit.uuid), null, null, null)
    private fun restart() = ChallengeRoundRecord(ChallengeRoundHead(timerHabit.uuid, id(301), 1), id(4), id(302),
        ChallengeRestartIntent(timerHabit.uuid, id(301), initialChallengeRoundUuid(timerHabit.uuid), 0, 1))
    private fun metadata(newer: Boolean = false) = ChallengeMetadata(1, listOf(ChallengeCheckpoint(
        if (newer) restart().head else initial().head, if (newer) listOf(initial(), restart()) else listOf(initial()))), emptyList())
    private fun canonical(type: String, uuid: String, payload: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private suspend fun initialize(http: NextSyncHttp) {
        register()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(habitType = HabitType.CHECK_IN, schedule = HabitSchedule.Once(), targetValue = 1,
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val meta = metadata()
        merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(
            canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), 20, time,
            listOf(OneTimeProjection(habit.uuid, OneTimeState(0, null, null))), 1, meta.checkpoints, meta.births))
    }
    private suspend fun command(n: Int): OneTimeLocalCommand {
        val state = localOnce().read(habit.uuid).queue.optimisticState
        return OneTimeLocalCommand(habit.uuid, PendingOneTimeIntent(id(n), OneTimeIntent(id(n + 1000),
            if (state.completionEventUuid == null) "complete" else "undo", state.version, state.headEventUuid,
            state.completionEventUuid)), millis + n, "Asia/Shanghai")
    }
    private suspend fun append(n: Int): SyncOutboxEntity {
        localOnce().appendRounds(producer().captureRounds(), command(n))
        return requireNotNull(db.syncOutboxDao().getByOperationId(id(n)))
    }
    private fun response(input: MaterialSocketServer.Input, newer: Boolean = false): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        assertEquals("/api/v2/sync/rounds/push", input.path)
        val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
        assertNull(request.contexts.single().head); assertTrue(request.contexts.single().affectedHeads.isEmpty())
        val ordinary = json.decodeFromString<NextSyncPushResponse>(successReply(input) { body ->
            val intent = body.getValue("one_time").jsonObject
            JsonObject(body + ("one_time_state_after" to buildJsonObject {
                put("version", intent.getValue("expected_version").jsonPrimitive.int + 1)
                put("head_event_uuid", intent.getValue("event_uuid"))
                put("completion_event_uuid", if (intent.getValue("action") == JsonPrimitive("complete")) intent.getValue("event_uuid") else JsonNull)
            }))
        }.bytes.toString(Charsets.UTF_8))
        val meta = metadata(newer)
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(ordinary.results, 1, meta.checkpoints, meta.births)).toByteArray())
    }
    private fun committed() = NextOneTimeOutcome.Accepted(NextOperationAcceptance.COMMITTED)
    private fun replayed() = NextOneTimeOutcome.Accepted(NextOperationAcceptance.REPLAYED)
    private suspend fun pending(row: SyncOutboxEntity) {
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertEquals(20L, db.nextSyncStateDao().rows().single().cursor)
    }

    @Test fun offlineCompleteUndoCompletePreservesIdentityIndependentCasAndColdReceiptsWithoutHttp() = runBlocking {
        val (http, server) = channel { response(it) }; initialize(http)
        val rows = List(3) { append(100 + it) }
        rows.forEach { val source = roundOperationIntent(originalIntent(it).intentJson)!!
            assertNull(source.context.head); assertEquals(id(4), source.capturedDeviceId) }
        assertEquals(3, localOnce().read(habit.uuid).queue.optimisticState.version)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { store(http).sendAndAccept(access(), rows[1].operationId) } as NextRequestException).reason)
        assertEquals(0, count("next_transmissions")); assertEquals(3, store(http).pushPending(access()))
        assertEquals(3, localOnce().read(habit.uuid).confirmed.version); assertEquals(rows.last().entityUuid, localOnce().read(habit.uuid).confirmed.completionEventUuid)
        assertEquals(3, db.completionDao().getByHabitOnce(habit.id).size); assertEquals(0, count("next_challenge_births"))
        val calls = server.requests.size; storage.reopen()
        rows.forEach { assertEquals(replayed(), store(http).sendAndAccept(access(), it.operationId)) }
        assertEquals(calls, server.requests.size); assertEquals(20L, db.nextSyncStateDao().rows().single().cursor)
    }

    @Test fun lostResponseColdRetryKeepsWholeEnvelopeAndLaterRoundHeadDoesNotChangeOnceState() = runBlocking {
        var lose = true
        val (http, server) = channel { input -> val reply = response(input, true)
            if (input.path.endsWith("/push") && lose) { lose = false; null } else reply }
        initialize(http); val row = append(110)
        rejected { store(http).sendAndAccept(access(), row.operationId) }; pending(row)
        val before = transmission(NEXT_OPERATION, row.operationId)
        storage.reopen(); assertEquals(committed(), store(http).sendAndAccept(access(), row.operationId))
        server.requests.filter { it.path.endsWith("/push") }.also { assertEquals(2, it.size) }.forEach { assertArrayEquals(before.wireBytes, it.body) }
        assertEquals(restart().head, merger(http).challengeMetadata(access())!!.checkpoints.single().head)
        assertEquals(1L, db.syncOutboxDao().getState("plan_node", timerHabit.uuid)!!.revision)
        append(111); assertEquals(1, store(http).pushPending(access()))
        assertNull(localOnce().read(habit.uuid).confirmed.completionEventUuid); assertEquals(0, count("next_challenge_births"))
        storage.reopen(); assertEquals(replayed(), store(http).sendAndAccept(access(), row.operationId))
        assertEquals(restart().head, merger(http).challengeMetadata(access())!!.checkpoints.single().head)
    }

    @Test fun inFlightUndoCanWriteWithoutNetworkHoldingMutexAndIsNotOverwrittenByCompleteAck() = runBlocking {
        val arrived = CountDownLatch(1); val release = CountDownLatch(1)
        val (http, _) = channel { input -> if (input.path.endsWith("/push")) { arrived.countDown(); check(release.await(10, TimeUnit.SECONDS)) }; response(input) }
        initialize(http); val row = append(120)
        val request = async(Dispatchers.IO) { store(http).sendAndAccept(access(), row.operationId) }
        try {
            withContext(Dispatchers.IO) { assertTrue(arrived.await(10, TimeUnit.SECONDS)) }
            val undo = withTimeout(5000) { append(121) }
            release.countDown(); assertEquals(committed(), request.await())
            assertEquals(undo, db.syncOutboxDao().getById(undo.id)); assertNull(localOnce().read(habit.uuid).queue.optimisticState.completionEventUuid)
            assertEquals(1, localOnce().read(habit.uuid).confirmed.version)
        } finally { release.countDown(); request.cancelAndJoin() }
    }

    @Test fun badMetadataSyntheticOnceBirthAndLateProofFaultsRollBackEntireAckAndAllowSameDelivery() = runBlocking {
        val (http, _) = channel { response(it, true) }; initialize(http); val row = append(130)
        val before = localOnce().read(habit.uuid); val delivery = store(http).send(access(), row.operationId)!!
        rejected { store(http).accept(delivery.copy(challengeMetadata = null)) }
        rejected { store(http).accept(delivery.copy(challengeMetadata = metadata(true).copy(births =
            listOf(ChallengeBirth("activity_event", row.entityUuid, initial().head))))) }
        val faults = listOf("BEFORE INSERT ON next_challenge_rounds BEGIN SELECT RAISE(IGNORE); END",
            "AFTER INSERT ON next_challenge_rounds BEGIN UPDATE completions SET value=99; END",
            "AFTER INSERT ON next_acceptances BEGIN UPDATE next_challenge_state SET metadataHash='bad'; END",
            "AFTER INSERT ON next_acceptances BEGIN UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1; UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1; END")
        for (fault in faults) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_once_fault $fault")
            rejected { store(http).accept(delivery) }; db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_once_fault")
            pending(row); assertEquals(before, localOnce().read(habit.uuid)); assertEquals(1, count("next_challenge_rounds"))
            assertNull(db.syncOutboxDao().getState("activity_event", row.entityUuid))
        }
        assertEquals(committed(), store(http).accept(delivery))
    }

    @Test fun realDeferredFinalCommitFailurePreservesFactQueueHistoryAndColdExactRetry() = runBlocking {
        val (http, _) = channel { response(it, true) }; initialize(http); val row = append(140)
        val delivery = store(http).send(access(), row.operationId)!!; val before = localOnce().read(habit.uuid)
        db.openHelper.writableDatabase.execSQL("CREATE TABLE round_once_commit(value TEXT REFERENCES next_challenge_rounds(roundUuid) DEFERRABLE INITIALLY DEFERRED)")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_once_final AFTER INSERT ON next_acceptances BEGIN INSERT INTO round_once_commit VALUES('${id(999)}'); END")
        rejected { store(http).accept(delivery) }; storage.reopen(); pending(row)
        assertEquals(before, localOnce().read(habit.uuid)); assertEquals(0, count("round_once_commit")); assertEquals(1, count("next_challenge_rounds"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_once_final"); db.openHelper.writableDatabase.execSQL("DROP TABLE round_once_commit")
        storage.reopen(); assertEquals(committed(), store(http).accept(delivery)); storage.reopen()
        assertEquals(replayed(), store(http).sendAndAccept(access(), row.operationId))
    }

    @Test fun producerAndFirstJournalFaultsDoNotCommitSourceOrAlterCheckpointAndRetryWorks() = runBlocking {
        val (http, server) = channel { response(it) }; initialize(http)
        val cmd = command(150); val ticket = producer().captureRounds()
        val faults = listOf("BEFORE INSERT ON next_request_origins BEGIN SELECT RAISE(IGNORE); END",
            "AFTER INSERT ON next_request_origins BEGIN UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1; UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1; END")
        for (fault in faults) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_once_birth $fault")
            rejected { localOnce().appendRounds(ticket, cmd) }; db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_once_birth")
            assertEquals(0, count("completions")); assertEquals(0, count("local_fact_submissions")); assertEquals(0, count("next_request_origins")); assertEquals(0, db.syncOutboxDao().count())
        }
        assertFalse(localOnce().appendRounds(ticket, cmd).alreadyStored); assertTrue(localOnce().appendRounds(ticket, cmd).alreadyStored)
        val row = db.syncOutboxDao().getAll().single()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_once_journal AFTER INSERT ON next_transmissions BEGIN UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1; UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1; END")
        rejected { store(http).sendAndAccept(access(), row.operationId) }; db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_once_journal")
        pending(row); assertEquals(0, count("next_transmissions")); assertEquals(0, count("one_time_transmissions")); assertTrue(server.requests.all { it.path.endsWith("/identity") })
        assertEquals(committed(), store(http).sendAndAccept(access(), row.operationId))
    }

    @Test fun rejectedHeadAndSuffixRetainIndependentCasAndColdRejectionDoesNotRetryOrFakeHistory() = runBlocking {
        val (http, server) = channel { input -> if (input.path.endsWith("/identity")) reply(input) else {
            val op = wireOperation(input); val meta = metadata(true)
            val result = NextSyncOperationResult(op.getValue("operation_id").jsonPrimitive.content, "activity_event",
                op.getValue("entity_uuid").jsonPrimitive.content, "conflict", errorCode = "TASK_STATE_CONFLICT",
                oneTimeConflict = OneTimeProjection(habit.uuid, OneTimeState(1, id(900), id(900))))
            MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1, meta.checkpoints, meta.births)).toByteArray())
        } }
        initialize(http); val rows = List(2) { append(160 + it) }
        val outcome = store(http).sendAndAccept(access(), rows.first().operationId); assertTrue(outcome is NextOneTimeOutcome.Rejected)
        assertEquals(rows.map { it.operationId }, localOnce().read(habit.uuid).queue.blockedOperationIds)
        assertEquals(0, localOnce().read(habit.uuid).confirmed.version); assertEquals(0, count("next_acceptances")); assertEquals(0, count("next_challenge_births"))
        assertEquals(rows[1], db.syncOutboxDao().getById(rows[1].id)); storage.reopen(); val calls = server.requests.size
        assertEquals(outcome, store(http).sendAndAccept(access(), rows.first().operationId)); assertEquals(calls, server.requests.size)
        rejected { store(http).pushPending(access()) }; assertEquals(calls, server.requests.size)
    }

    @Test fun plainBoundariesAndDamagedOriginalProfileCannotDowngradeOrAdoptOnceWork() = runBlocking {
        val (http, server) = channel { response(it) }; initialize(http)
        var called = false; val cmd = command(170)
        rejected { localOnce().append(local(), cmd) { called = true } }; assertFalse(called)
        val row = append(170); val original = originalIntent(row)
        rejected { facts().prepare(habit.uuid) }; assertEquals(0, count("one_time_transmissions"))
        val bodies = listOf("\"1\"", "1.0", "true", "2").map { original.intentJson.replace("\"challenge_contract\":1", "\"challenge_contract\":$it") } +
            original.intentJson.replace(id(4), id(41)) + json.encodeToString(roundOperationIntent(original.intentJson)!!.operation)
        for (body in bodies) {
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?", arrayOf(body, row.operationId))
            rejected { store(http).sendAndAccept(access(), row.operationId) }; pending(row)
        }
        assertEquals(0, count("next_transmissions")); assertTrue(server.requests.all { it.path.endsWith("/identity") })
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?", arrayOf(original.intentJson, row.operationId))
        val delivery = store(http).send(access(), row.operationId)!!
        val prepared = PreparedOneTimeSubmission(OneTimeSyncContext(local(), id(4)), decodeNextOperationIntent(original.intentJson))
        rejected { facts().acknowledge(prepared, delivery.result.results.single()) }; pending(row)
        assertEquals(committed(), store(http).accept(delivery))
    }

    @Test fun changedWriteAuthorityAndCancelledOrLateAccountAckPreserveOriginalFactAndWork() = runBlocking {
        val (http, _) = channel { response(it) }; initialize(http); val ticket = producer().captureRounds(); val cmd = command(180)
        register(device = id(44)); var called = false
        rejected { localOnce().appendRounds(ticket, cmd) { called = true } }; assertFalse(called); assertEquals(0, count("completions"))
        register(); val row = append(180); val delivery = store(http).send(access(), row.operationId)!!
        sessions.exclusive { val job = launch(start = CoroutineStart.UNDISPATCHED) { store(http).accept(delivery) }; job.cancelAndJoin() }
        pending(row); tokens.saveLoginSession("other", "other-refresh", "other", id(888), false)
        rejected { store(http).accept(delivery) }; pending(row); assertEquals(0, count("next_acceptances"))
    }
}
