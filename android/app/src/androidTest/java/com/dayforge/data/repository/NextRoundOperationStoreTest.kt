package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual NEW source → frozen round HTTP → common Room ACK; physical file DB and cold reopen. */
@RunWith(AndroidJUnit4::class)
class NextRoundOperationStoreTest : NextCoreRequestFixture() {
    private val json = Json { encodeDefaults = true }
    private fun initial(activity: String) = ChallengeRoundRecord(initialChallengeRoundHead(activity), null, null, null)
    private fun restarted() = ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, id(301), 1), id(4), id(302),
        ChallengeRestartIntent(habit.uuid, id(301), initialChallengeRoundUuid(habit.uuid), 0, 1))
    private fun metadata(restart: Boolean = false, births: List<ChallengeBirth> = emptyList(), extra: String? = null) =
        ChallengeMetadata(1, listOf(ChallengeCheckpoint(if (restart) restarted().head else initial(habit.uuid).head,
            if (restart) listOf(initial(habit.uuid), restarted()) else listOf(initial(habit.uuid))),
            ChallengeCheckpoint(initial(timerHabit.uuid).head, listOf(initial(timerHabit.uuid)))) +
            extra?.let { listOf(ChallengeCheckpoint(initial(it).head, listOf(initial(it)))) }.orEmpty(), births)
    private fun canonical(type: String, identity: String, body: JsonObject, revision: Long = 1) =
        SyncV2Change(0, type, identity, "upsert", revision, JsonObject(body + mapOf(
            "public_id" to JsonPrimitive(identity), "revision" to JsonPrimitive(revision), "created_at" to JsonPrimitive(time),
            "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun merger(http: NextSyncHttp): NextSyncMergeStore {
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        return NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
    }
    private suspend fun initialize(http: NextSyncHttp, restart: Boolean = false) {
        register()
        val meta = metadata(restart)
        merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(
            canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit), if (restart) 2 else 1),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), 20, time, emptyList(),
            1, meta.checkpoints, meta.births))
    }
    private fun roundReply(input: MaterialSocketServer.Input, meta: ChallengeMetadata = metadata()): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        assertEquals("/api/v2/sync/rounds/push", input.path)
        val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
        val operation = request.operations.single()
        val births = if (operation.entityType == "activity_event") meta.births + ChallengeBirth("activity_event",
            operation.entityUuid, request.contexts.single().head!!) else meta.births
        val revision = (operation.baseRevision ?: 0) + 1
        val ordinaryReply = successReply(input, revision) { body ->
            // This existing metric was created by the bootstrap, not by this edit. Its original
            // server timestamp (including precision) is immutable; other entities keep their proof.
            if (operation.entityType == "metric" && operation.entityUuid == metric.uuid)
                JsonObject(body + ("created_at" to JsonPrimitive(time))) else body
        }
        val ordinary = json.decodeFromString<NextSyncPushResponse>(ordinaryReply.bytes.toString(Charsets.UTF_8))
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(ordinary.results, 1, meta.checkpoints, births)).toByteArray())
    }
    private suspend fun countFact(scope: NextRoundWriteScope, identity: String = id(210), value: Int = 3) {
        producer().writeRounds(scope) {
            val current = db.habitDao().getHabitById(habit.id)!!
            val fact = CompletionEntity(habitId = current.id, habitUuid = current.uuid, uuid = identity,
                value = value, date = millis, actualCompletedAt = millis, recordedTimezone = "Etc/UTC",
                recordedLocalDate = "2026-10-06")
            NextCountDayStore(db).capture(current, fact)
            db.completionDao().insert(fact)
        }
    }

    @Test fun actualMetricWriteFrozenSendAckAndColdReplayDoNotAdvanceCursorOrOverwriteLaterEdit() = runBlocking {
        val (http, server) = channel { roundReply(it) }; initialize(http)
        val scope = producer().captureRounds()
        producer().writeRounds(scope) { metrics().updateMetric(metric.copy(name = "New profile")) }
        val row = db.syncOutboxDao().getAll().single()
        val original = db.nextRequestDao().origin(NEXT_OPERATION, row.operationId)!!
        val intent = roundOperationIntent(original.intentJson)!!
        assertNull(intent.context.head); assertEquals(id(4), intent.capturedDeviceId)
        val store = sender(http)
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), row.operationId))
        val wire = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        val receipt = db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId)!!
        producer().writeRounds(producer().captureRounds()) {
            metrics().updateMetric(db.metricDao().getMetricByUuid(metric.uuid)!!.copy(name = "Later offline"))
        }
        storage.reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals("Later offline", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, row.operationId))
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertArrayEquals(wire, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        assertEquals(20L, merger(http).state(access(), true)!!.cursor)
    }

    @Test fun newCountFactCommitsOriginalBirthAndSharedDayPolicyAtomically() = runBlocking {
        val (http, _) = channel { roundReply(it, metadata(true)) }; initialize(http, true)
        countFact(producer().captureRounds())
        val row = db.syncOutboxDao().getAll().single()
        val source = roundOperationIntent(db.nextRequestDao().origin(NEXT_OPERATION, row.operationId)!!.intentJson)!!
        assertEquals(restarted().head, source.context.head)
        assertEquals(CountDayPolicy(10, false), CountDayPolicy.fromJson(source.operation.payload.getValue("count_policy")))
        assertEquals(CountDayPolicy(10, false), CountHistoryReader(db, tokens, sessions)
            .read(db.habitDao().getHabitById(habit.id)!!, LocalDate.parse("2026-10-06")).todayPolicy)
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        storage.reopen()
        assertEquals(restarted().head, merger(http).challengeMetadata(access())!!.requireBirth("activity_event", row.entityUuid, habit.uuid).head)
        assertEquals(10, db.countDayDao().get(habit.id, "2026-10-06")!!.targetValue)
        assertEquals(CountDayPolicy(10, false), CountHistoryReader(db, tokens, sessions)
            .read(db.habitDao().getHabitById(habit.id)!!, LocalDate.parse("2026-10-06")).todayPolicy)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertEquals(20L, merger(http).state(access(), true)!!.cursor)
    }

    @Test fun droppedResponseColdRetryUsesExactOriginalWholeProfileEnvelope() = runBlocking {
        var drop = true
        val (http, server) = channel { input ->
            val response = roundReply(input)
            if (input.path.endsWith("/push") && drop) null else response
        }; initialize(http)
        producer().writeRounds(producer().captureRounds()) { metrics().updateMetric(metric.copy(name = "Lost response")) }
        val row = db.syncOutboxDao().getAll().single()
        rejected { sender(http).sendOperation(access(), row.operationId) }
        val original = transmission(NEXT_OPERATION, row.operationId)
        storage.reopen(); drop = false
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        val bodies = server.requests.filter { it.path.endsWith("/push") }.map { it.body }
        assertEquals(2, bodies.size); assertArrayEquals(bodies[0], bodies[1])
        assertArrayEquals(original.wireBytes, transmission(NEXT_OPERATION, row.operationId).wireBytes)
    }

    @Test fun staleDisplayedRoundRejectsAndRollsBackButUnrelatedMetricCanStillBeEdited() = runBlocking {
        val (http, _) = channel { roundReply(it, metadata(true)) }; initialize(http)
        val stale = producer().captureRounds()
        val store = merger(http); val state = store.state(access(), true)!!
        store.page(access(), state, RoundSyncPullResponse(emptyList(), 21, false, time, 1, metadata(true).checkpoints, emptyList()))
        val error = rejected { countFact(stale) }
        assertEquals("SYNC_CHALLENGE_STALE_ACTION", error.message)
        assertNull(db.completionDao().getCompletionByUuid(id(210))); assertEquals(0, count("sync_outbox"))
        producer().writeRounds(stale) { metrics().updateMetric(metric.copy(name = "Unrelated")) }
        assertEquals("Unrelated", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        val catchup = rejected { countFact(producer().captureRounds()) }
        assertEquals("SYNC_CHALLENGE_PLAN_CATCHUP_REQUIRED", catchup.message)
        assertEquals(1, count("sync_outbox"))
    }

    @Test fun historicalUndoNeedsFreshActionTicketAndInheritsOriginalBirthNotCurrentHead() = runBlocking {
        val oldBirth = ChallengeBirth("activity_event", id(210), initial(habit.uuid).head)
        var meta = metadata()
        val (http, _) = channel { roundReply(it, meta) }; initialize(http)
        countFact(producer().captureRounds())
        val fact = db.syncOutboxDao().getAll().single()
        sender(http).sendAndAcceptOperation(access(), fact.operationId)
        val stale = producer().captureRounds()
        meta = metadata(true, listOf(oldBirth))
        val store = merger(http); val state = store.state(access(), true)!!
        val plan = canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit), 2).copy(sequence = 21)
        store.page(access(), state, RoundSyncPullResponse(listOf(plan), 21, false, time, 1, meta.checkpoints, meta.births))
        rejected { producer().writeRounds(stale) { db.completionDao().delete(db.completionDao().getCompletionByUuid(id(210))!!) } }
        assertNotNull(db.completionDao().getCompletionByUuid(id(210)))
        producer().writeRounds(producer().captureRounds()) { db.completionDao().delete(db.completionDao().getCompletionByUuid(id(210))!!) }
        val undo = db.syncOutboxDao().getAll().single()
        val source = roundOperationIntent(db.nextRequestDao().origin(NEXT_OPERATION, undo.operationId)!!.intentJson)!!
        assertEquals(initial(habit.uuid).head, source.context.head)
        assertNotEquals(id(210), source.operation.entityUuid)
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), undo.operationId))
        assertEquals(initial(habit.uuid).head, store.challengeMetadata(access())!!.requireBirth(
            "activity_event", source.operation.entityUuid, habit.uuid).head)
        assertEquals(restarted().head, store.challengeMetadata(access())!!.checkpoints.single { it.head.activityUuid == habit.uuid }.head)
        assertEquals(10, db.countDayDao().get(habit.id, "2026-10-06")!!.targetValue)
    }

    @Test fun pendingUndoInheritsImmutableLocalFactAndOriginalRowsAreNeverRewritten() = runBlocking {
        val (http, _) = channel { roundReply(it) }; initialize(http)
        countFact(producer().captureRounds())
        val first = db.syncOutboxDao().getAll().single()
        val before = db.nextRequestDao().origin(NEXT_OPERATION, first.operationId)!!
        producer().writeRounds(producer().captureRounds()) { db.completionDao().delete(db.completionDao().getCompletionByUuid(id(210))!!) }
        val undo = db.syncOutboxDao().getAll().last()
        val source = roundOperationIntent(db.nextRequestDao().origin(NEXT_OPERATION, undo.operationId)!!.intentJson)!!
        assertEquals(initial(habit.uuid).head, source.context.head)
        assertEquals(before, db.nextRequestDao().origin(NEXT_OPERATION, first.operationId))
        assertEquals(2, count("sync_outbox"))
    }

    @Test fun missingOrWrongAckBirthAndSidecarTriggerCannotConsumeWorkOrPublishCursor() = runBlocking {
        val (http, _) = channel { roundReply(it) }; initialize(http)
        countFact(producer().captureRounds())
        val row = db.syncOutboxDao().getAll().single()
        val store = sender(http); val delivery = store.sendOperation(access(), row.operationId)!!
        rejected { store.acceptOperation(delivery.copy(challengeMetadata = null)) }
        rejected { store.acceptOperation(delivery.copy(challengeMetadata = metadata())) }
        rejected { store.acceptOperation(delivery.copy(challengeMetadata = metadata(true,
            listOf(ChallengeBirth("activity_event", row.entityUuid, restarted().head))))) }
        assertUnaccepted(row)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_ack_fault AFTER INSERT ON next_challenge_births BEGIN UPDATE metrics SET name='wrong side effect'; END")
        rejected { store.acceptOperation(delivery) }
        assertUnaccepted(row); assertEquals(metric.name, db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertEquals(0, count("next_challenge_births")); assertEquals(20L, merger(http).state(access(), true)!!.cursor)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_ack_fault")
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
    }

    @Test fun lateReceiptTriggerRollsBackNewBirthAndBusinessThenSameDeliveryRetries() = runBlocking {
        val (http, _) = channel { roundReply(it) }; initialize(http)
        countFact(producer().captureRounds())
        val row = db.syncOutboxDao().getAll().single()
        val store = sender(http); val delivery = store.sendOperation(access(), row.operationId)!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_receipt_fault AFTER INSERT ON next_acceptances BEGIN UPDATE next_challenge_state SET metadataHash='bad'; END")
        rejected { store.acceptOperation(delivery) }
        assertUnaccepted(row); assertEquals(0, count("next_challenge_births"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_receipt_fault"); storage.reopen()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
    }

    @Test fun finalDeferredCommitFailureRollsBackBirthReceiptAndQueueRetirementTogether() = runBlocking {
        val (http, _) = channel { roundReply(it) }; initialize(http)
        countFact(producer().captureRounds())
        val row = db.syncOutboxDao().getAll().single()
        val store = sender(http); val delivery = store.sendOperation(access(), row.operationId)!!
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE round_commit_guard (value TEXT PRIMARY KEY, FOREIGN KEY(value) REFERENCES next_challenge_rounds(roundUuid) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER round_commit_fault AFTER INSERT ON next_acceptances BEGIN INSERT INTO round_commit_guard(value) VALUES('" + id(999) + "'); END")
        rejected { store.acceptOperation(delivery) }
        // A deferred COMMIT failure can leave the framework writer in a native transaction.
        // Close the real file first, as after process death; a WAL reader is not rollback proof.
        storage.reopen()
        assertUnaccepted(row); assertEquals(0, count("next_challenge_births")); assertEquals(0, count("round_commit_guard"))
        assertEquals(20L, merger(http).state(access(), true)!!.cursor)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_commit_fault")
        db.openHelper.writableDatabase.execSQL("DROP TABLE round_commit_guard")
        storage.reopen()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
    }

    @Test fun coherentlyChangedCursorCannotBePublishedByProducerOrLateAcceptanceTrigger() = runBlocking {
        val (http, _) = channel { roundReply(it) }; initialize(http)
        val before = merger(http).state(access(), true)!!
        rejected { producer().writeRounds(producer().captureRounds()) {
            metrics().updateMetric(metric.copy(name = "Illegal cursor"))
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1")
            sql.execSQL("UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1")
        } }
        assertEquals(before, merger(http).state(access(), true))
        assertEquals(metric.name, db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        countFact(producer().captureRounds())
        val row = db.syncOutboxDao().getAll().single()
        val store = sender(http); val delivery = store.sendOperation(access(), row.operationId)!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER round_cursor_fault AFTER INSERT ON next_acceptances BEGIN UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1; UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1; END")
        rejected { store.acceptOperation(delivery) }
        assertUnaccepted(row); assertEquals(0, count("next_challenge_births"))
        assertEquals(before, merger(http).state(access(), true))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER round_cursor_fault")
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertEquals(before, merger(http).state(access(), true))
    }

    @Test fun sameDayNewRoundKeepsSharedOriginalCountPolicyDespiteNewPlanTarget() = runBlocking {
        var meta = metadata()
        val (http, _) = channel { roundReply(it, meta) }; initialize(http)
        countFact(producer().captureRounds())
        val old = db.syncOutboxDao().getAll().single()
        sender(http).sendAndAcceptOperation(access(), old.operationId)
        meta = metadata(true, listOf(ChallengeBirth("activity_event", old.entityUuid, initial(habit.uuid).head)))
        val store = merger(http)
        val plan = canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit.copy(targetValue = 20)), 2).copy(sequence = 21)
        store.page(access(), store.state(access(), true)!!, RoundSyncPullResponse(listOf(plan), 21, false, time, 1,
            meta.checkpoints, meta.births))
        countFact(producer().captureRounds(), id(211), 4)
        val newer = db.syncOutboxDao().getAll().single()
        val source = roundOperationIntent(db.nextRequestDao().origin(NEXT_OPERATION, newer.operationId)!!.intentJson)!!
        assertEquals(restarted().head, source.context.head)
        assertEquals(CountDayPolicy(10, false), CountDayPolicy.fromJson(source.operation.payload.getValue("count_policy")))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), newer.operationId))
        assertEquals(20, db.habitDao().getHabitById(habit.id)!!.targetValue)
        assertEquals(10, db.countDayDao().get(habit.id, "2026-10-06")!!.targetValue)
        assertEquals(setOf(3, 4), db.completionDao().getAllCompletionsOnce().map { it.value }.toSet())
        assertEquals(CountDayPolicy(10, false), CountHistoryReader(db, tokens, sessions)
            .read(db.habitDao().getHabitById(habit.id)!!, LocalDate.parse("2026-10-06")).todayPolicy)
        assertEquals(initial(habit.uuid).head, store.challengeMetadata(access())!!.requireBirth("activity_event", old.entityUuid, habit.uuid).head)
        assertEquals(restarted().head, store.challengeMetadata(access())!!.requireBirth("activity_event", newer.entityUuid, habit.uuid).head)
    }

    @Test fun structuralUnsentReplacementRetainsOriginalExplicitProfileAndHead() = runBlocking {
        val (http, server) = channel { roundReply(it) }
        initialize(http)
        producer().writeRounds(producer().captureRounds()) { habits().updateHabit(habit.copy(name = "First")) }
        val first = db.syncOutboxDao().getAll().single()
        producer().writeRounds(producer().captureRounds()) {
            habits().updateHabit(db.habitDao().getHabitById(habit.id)!!.copy(name = "Second"))
        }
        val second = db.syncOutboxDao().getAll().last()
        val original = db.nextRequestDao().origin(NEXT_OPERATION, second.operationId)!!
        assertEquals(JsonPrimitive("Second"), roundOperationIntent(original.intentJson)!!.operation.payload["title"])
        val store = sender(http)
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), first.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), second.operationId))
        val replacement = db.nextStructuralCausalDao().supersession(second.operationId)!!
        val source = roundOperationIntent(db.nextRequestDao().origin(NEXT_OPERATION, replacement.replacementId)!!.intentJson)!!
        assertEquals(initial(habit.uuid).head, source.context.head)
        assertEquals(replacement.replacementId, source.context.sourceUuid)
        assertEquals(JsonPrimitive("Second"), source.operation.payload["title"])
        assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, second.operationId))
        assertEquals("Second", db.habitDao().getHabitById(habit.id)!!.name)
        assertTrue(server.requests.filter { it.path.endsWith("/push") }.all { it.path.contains("/rounds/") })
        storage.reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), second.operationId))
    }

    @Test fun freshActivityAndItsFactInSameTransactionUseExplicitInitialIdentityWithoutBackfill() = runBlocking {
        val fresh = habit.copy(id = 0, uuid = id(401), name = "Fresh")
        val (http, _) = channel { roundReply(it, metadata(extra = fresh.uuid)) }; initialize(http)
        producer().writeRounds(producer().captureRounds()) {
            val key = db.habitDao().insert(fresh)
            val fact = CompletionEntity(habitId = key, habitUuid = fresh.uuid, uuid = id(402), value = 2,
                date = millis, actualCompletedAt = millis, recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06")
            NextCountDayStore(db).capture(fresh.copy(id = key), fact); db.completionDao().insert(fact)
        }
        val rows = db.syncOutboxDao().getAll()
        assertEquals(2, rows.size)
        rows.forEach {
            assertEquals(initialChallengeRoundHead(fresh.uuid),
                roundOperationIntent(db.nextRequestDao().origin(NEXT_OPERATION, it.operationId)!!.intentJson)!!.context.head)
        }
        assertEquals(2, count("next_challenge_rounds")); assertEquals(0, count("next_challenge_births"))
        rows.forEach { assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), it.operationId)) }
        assertEquals(initialChallengeRoundHead(fresh.uuid), merger(http).challengeMetadata(access())!!.requireBirth("activity_event", id(402), fresh.uuid).head)
    }

    @Test fun changedDeviceOrPermissionRejectsBeforeCallbackAndOrphanTimerRollsBackEntireBatch() = runBlocking {
        val (http, _) = channel { roundReply(it) }; initialize(http)
        val scope = producer().captureRounds()
        register(permissions = setOf("sync.read"), revision = 2)
        rejected { producer().writeRounds(scope) { error("stale callback executed") } }
        val denied = producer().captureRounds()
        rejected { producer().writeRounds(denied) { metrics().updateMetric(metric.copy(name = "Denied")) } }
        assertEquals(metric.name, db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        register()
        rejected { producer().writeRounds(producer().captureRounds()) {
            metrics().updateMetric(metric.copy(name = "Must roll back"))
            db.timeLogDao().insertTimerCommand(TimerCommandEntity(commandId = id(405), sessionUuid = id(406),
                sequence = 1, commandType = "start", occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid,
                timezone = "Etc/UTC"))
        } }
        assertEquals(metric.name, db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("timer_command_outbox")); assertEquals(0, count("next_request_origins"))
    }

    @Test fun damagedLocalProfileTagOrOriginalContextCannotDowngradeToPlainSend() = runBlocking {
        val (http, server) = channel { roundReply(it) }; initialize(http)
        producer().writeRounds(producer().captureRounds()) { metrics().updateMetric(metric.copy(name = "Strict")) }
        val row = db.syncOutboxDao().getAll().single()
        val original = db.nextRequestDao().origin(NEXT_OPERATION, row.operationId)!!
        for (tag in listOf("\"1\"", "1.0", "true", "2")) {
            val body = original.intentJson.replace("\"challenge_contract\":1", "\"challenge_contract\":$tag")
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?", arrayOf(body, row.operationId))
            rejected { sender(http).sendOperation(access(), row.operationId) }
        }
        assertEquals(0, server.requests.count { it.path.endsWith("/push") }); assertEquals(0, count("next_transmissions"))
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?",
            arrayOf(original.intentJson, row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
    }
}
