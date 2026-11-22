package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.*
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountIconRuntime
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real bootstrap, producer originals and TCP acceptance. Recovery never writes a projection. */
@RunWith(AndroidJUnit4::class)
class NextReplicaBusinessProofReaderTest : NextCoreRequestFixture() {
    private val json = Json { encodeDefaults = true }
    private lateinit var http: NextSyncHttp
    private lateinit var peer: MaterialSocketServer
    private val births = linkedMapOf<Pair<String, String>, ChallengeBirth>()
    private var onceHabit: HabitEntity? = null
    private var goal: HabitEntity? = null
    private fun metadata() = ChallengeMetadata(1, listOf(habit.uuid, timerHabit.uuid).map { uuid ->
        val head = initialChallengeRoundHead(uuid)
        ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
    }, births.values.toList())
    private fun canonical(type: String, uuid: String, payload: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun once() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun merger() = NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions, once()),
        NextTimerRequestStore(db, tokens, sessions, sender(http)))
    private suspend fun initialize(remoteTimer: Boolean = false, changedTimerMode: Boolean = false, withOnce: Boolean = false,
        withGraph: Boolean = false, withOnceHistory: Boolean = false) {
        require(!withOnceHistory || withOnce)
        register()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            if (withGraph) {
                val row = habit.copy(id = 0, uuid = id(14), name = "Parent goal", habitType = HabitType.GOAL,
                    targetValue = 1, completionPolicy = null, appearance = ObjectAppearance(IconReference.Role("goal.default"), "#123456", "object"),
                    planMetadata = requireNotNull(habit.planMetadata).copy(timezone = null))
                goal = row.copy(id = db.habitDao().insert(row))
                habit = habit.copy(parentHabitId = row.uuid); db.habitDao().update(habit)
            }
            if (changedTimerMode) {
                timerHabit = timerHabit.copy(habitType = HabitType.CHECK_IN, targetValue = 0,
                    planMetadata = requireNotNull(timerHabit.planMetadata).copy(targetUnit = null))
                db.habitDao().update(timerHabit)
            }
            if (withOnce) {
                val row = habit.copy(id = 0, uuid = id(13), name = "Independent once", habitType = HabitType.CHECK_IN,
                    schedule = HabitSchedule.Once(), targetValue = 1, completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                    appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
                onceHabit = row.copy(id = db.habitDao().insert(row))
            }
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val channel = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else {
                val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
                val operation = request.operations.single()
                if (operation.entityType == "activity_event") {
                    births["activity_event" to operation.entityUuid] = ChallengeBirth("activity_event", operation.entityUuid, request.contexts.single().head!!)
                }
                val response = json.decodeFromString<NextSyncPushResponse>(successReply(input).bytes.toString(Charsets.UTF_8))
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(response.results, 1, meta.checkpoints, meta.births)).toByteArray())
            }
        }
        http = channel.first; peer = channel.second
        if (remoteTimer) {
            val head = initialChallengeRoundHead(timerHabit.uuid)
            births["timer_session" to id(30)] = ChallengeBirth("timer_session", id(30), head)
            births["activity_event" to id(30)] = ChallengeBirth("activity_event", id(30), head)
        }
        val meta = metadata()
        val changes = listOf(habit, timerHabit).plus(listOfNotNull(onceHabit, goal)).map {
            canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it))
        } + canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)) +
            (if (remoteTimer) listOf(duration()) else emptyList()) + (if (withGraph) listOf(canonical("activity_metric_link", id(15),
                SyncV2Mapper.link(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid, metricId = metric.id,
                    metricUuid = metric.uuid, uuid = id(15), isActive = false, createdAt = millis, updatedAt = millis)))) else emptyList()) +
            (if (withOnceHistory) listOf(onceHistory(false), onceHistory(true)) else emptyList())
        merger().bootstrap(access(), null, RoundSyncBootstrapResponse(changes, 20, time,
            listOfNotNull(onceHabit?.let { OneTimeProjection(it.uuid, if (withOnceHistory)
                OneTimeState(2, id(111), null) else OneTimeState(0, null, null)) }),
            1, meta.checkpoints, meta.births))
    }
    private fun onceHistory(undo: Boolean) = canonical("activity_event", id(if (undo) 111 else 110), buildJsonObject {
        val event = id(if (undo) 111 else 110)
        put("activity_uuid", requireNotNull(onceHabit).uuid); put("event_type", if (undo) "revert" else "check_in")
        put("value", if (undo) JsonNull else JsonPrimitive("1.0"))
        put("occurred_at", time); put("received_at", time); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
        put("source_type", "app"); put("source_device_id", id(4)); put("note", "Retained once history")
        put("metadata", buildJsonObject {})
        for (key in listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "external_event_id")) put(key, JsonNull)
        put("reverts_event_uuid", if (undo) JsonPrimitive(id(110)) else JsonNull)
        put("one_time", buildJsonObject {
            put("event_uuid", event); put("action", if (undo) "undo" else "complete"); put("expected_version", if (undo) 1 else 0)
            put("expected_head_event_uuid", if (undo) JsonPrimitive(id(110)) else JsonNull)
            put("reverts_event_uuid", if (undo) JsonPrimitive(id(110)) else JsonNull)
        })
        put("one_time_state_after", buildJsonObject {
            put("version", if (undo) 2 else 1); put("head_event_uuid", event)
            put("completion_event_uuid", if (undo) JsonNull else JsonPrimitive(event))
        })
    })
    private fun duration() = canonical("activity_event", id(30), buildJsonObject {
        val end = Instant.ofEpochMilli(millis + 60_000).toString()
        put("activity_uuid", timerHabit.uuid); put("event_type", "duration_session"); put("value", JsonNull)
        put("duration_seconds", 60); put("duration_milliseconds", 60_000); put("started_at", time); put("ended_at", end)
        put("occurred_at", end); put("received_at", time); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
        put("source_type", "app"); put("source_device_id", id(4)); put("external_event_id", id(30)); put("note", "")
        put("reverts_event_uuid", JsonNull); put("metadata", buildJsonObject { put("timer_session_id", id(30)) })
        put("day_allocations", buildJsonArray { add(buildJsonObject {
            put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai"); put("duration_milliseconds", 60_000)
        }) })
    })
    private suspend fun countFact(): SyncOutboxEntity {
        producer().writeRounds(producer().captureRounds()) {
            val current = db.habitDao().getHabitById(habit.id)!!
            val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(40), value = 6,
                actualCompletedAt = millis, date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
                    .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                recordedTimezone = "Asia/Shanghai", createdAt = millis)
            NextCountDayStore(db).capture(current, fact)
            db.completionDao().insertForSync(fact)
        }
        return db.syncOutboxDao().getAll().last()
    }
    private suspend fun observe(): SyncOutboxEntity {
        // Only this fixture producer needs real editor material ownership; the original
        // test lifecycle and all reader assertions retain their existing scope.
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        val iconDatabase = AccountIconDatabase.open(app)
        val iconMetadata = AccountIconRepository(iconDatabase, tokens, sessions)
        val iconStore = AccountIconStore(iconMetadata, AccountIconFiles(directory))
        val imports = AccountIconImport(iconMetadata, iconStore, AccountIconTransfers(iconDatabase, iconMetadata, iconStore))
        val controller = AccountIconController({ AccountIconRuntime(iconMetadata, iconStore,
            AccountIconRenderer(iconMetadata, iconStore), imports,
            AccountIconDocuments(imports, app.contentResolver), {}) }, tokens)
        return try {
            val editor = NextObjectEditor(db, tokens, sessions, controller)
            val repository = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(),
                db.habitMetricLinkDao(), nextObjectEditor = editor)
            val selected = editor.metric(metric.id)
            val actual = requireNotNull(selected.value)
            assertEquals(metric.uuid, actual.uuid); assertNotNull(actual.appearance)
            val beforeLogs = db.metricLogDao().getAllLogsForMetric(actual.id).associateBy { it.id }
            val beforeQueue = db.syncOutboxDao().getAll().associateBy { it.id }
            fun originCount() = db.openHelper.writableDatabase.query("SELECT count(*) FROM main.next_request_origins").use {
                assertTrue(it.moveToFirst()); it.getLong(0).also { _ -> assertFalse(it.moveToNext()) }
            }
            val beforeOrigins = originCount()
            val observationId = repository.recordValue(actual.id, 21.125, "captured value", millis,
                authority = requireNotNull(selected.authority), expectedMetricUuid = actual.uuid)
            val logs = db.metricLogDao().getAllLogsForMetric(actual.id).associateBy { it.id }
            val observation = logs.getValue(observationId)
            assertEquals(beforeLogs, logs - observationId); assertEquals(beforeLogs.size + 1, logs.size)
            assertEquals(actual.id, observation.metricId); assertEquals(21.125, observation.value, 0.0)
            assertEquals("captured value", observation.note); assertEquals(millis, observation.date)
            val queue = db.syncOutboxDao().getAll().associateBy { it.id }
            val queued = queue.getValue((queue.keys - beforeQueue.keys).single())
            assertEquals(beforeQueue, queue - queued.id); assertEquals(beforeQueue.size + 1, queue.size)
            assertEquals("metric_log", queued.recordType); assertEquals("upsert", queued.action)
            assertEquals(observation.uuid, queued.entityUuid); assertEquals(observation.uuid, queued.wireEntityUuid)
            val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, queued.operationId))
            assertEquals(queued.id, origin.queueId); assertEquals(queued.operationId, origin.requestId)
            assertEquals(NextRequestSql.sourceHash(queued), origin.sourceHash); assertEquals(5, origin.protocol)
            val operation = requireNotNull(roundOperationIntent(origin.intentJson)).operation
            assertEquals(queued.operationId, operation.operationId); assertEquals(observation.uuid, operation.entityUuid)
            assertEquals("metric_observation", operation.entityType); assertEquals("upsert", operation.action)
            assertEquals(beforeOrigins + 1, originCount())
            queued
        } finally {
            try { controller.awaitImages() } finally {
                controller.close(); iconDatabase.close()
                assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
            }
        }
    }
    private suspend fun activeTimer() {
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, startTime = millis, endTime = null,
                durationSeconds = 0, date = millis, uuid = id(20), timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
        }
    }
    private suspend fun localProof(): ReplicaLocalFactProof {
        val hash = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().hash }
        val schema = NextReplicaSchemaValidator(app, db, tokens, sessions).validate(access(), hash)
        val requests = NextReplicaRequestProofReader(db, tokens, sessions, preferences, http).read(schema)
        val configuration = NextReplicaConfigProofReader(db, tokens, sessions).read(requests)
        return NextReplicaLocalFactProofReader(db, tokens, sessions).read(configuration)
    }
    private fun reader() = NextReplicaBusinessProofReader(db, tokens, sessions, preferences, http)
    private suspend fun unchanged(block: suspend () -> Unit) {
        val image = storage.snapshotClosedDatabase(); val prefs = dataStore.data.first(); val original = access(); val calls = peer.requests.size
        block()
        assertArrayEquals(image, storage.snapshotClosedDatabase()); assertEquals(prefs, dataStore.data.first())
        assertEquals(original, access()); assertEquals(calls, peer.requests.size)
    }
    private suspend fun sqlWrite(statement: String, args: Array<Any> = emptyArray()) = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        sql.execSQL(statement, args)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
    }

    @Test fun completeCleanProjectionIsReadOnlyNotActivationAndSurvivesColdReopen() = runBlocking<Unit> {
        initialize(); val local = localProof()
        unchanged { val proof = reader().read(local)
            assertEquals(3, proof.records.size); assertTrue(proof.unconfirmed.isEmpty()); assertFalse(proof.hasUnfinishedTimer)
            assertTrue(proof.projectionGaps.isEmpty()); assertFalse(proof.hasProjectionReview)
            assertFalse(proof.activationAuthorized)
            assertTrue(runCatching { (proof.records as MutableMap<*, *>).clear() }.isFailure)
            assertTrue(runCatching { (proof.unconfirmed as MutableSet<*>).clear() }.isFailure)
            storage.reopen(); assertEquals(proof.records, reader().read(local).records) }
    }
    @Test fun sameIdentityPendingStructureEditIsVisibleEvenWithAuthorityAndUnchangedRowCount() = runBlocking<Unit> {
        initialize()
        producer().writeRounds(producer().captureRounds()) { db.habitDao().update(habit.copy(name = "New title")) }
        val local = localProof()
        unchanged { val proof = reader().read(local)
            assertEquals(setOf(ReplicaRecordKey("plan_node", habit.uuid)), proof.unconfirmed)
            assertEquals("New title", proof.records.getValue(ReplicaRecordKey("plan_node", habit.uuid)).getValue("name").jsonObject.getValue("value").jsonPrimitive.content)
            assertTrue(local.configuration.requests.hasUnsettledRequests); assertFalse(proof.activationAuthorized) }
    }
    @Test fun missingOriginalDoesNotTurnChangedSameIdentityMetricIntoConfirmedState() = runBlocking<Unit> {
        initialize(); sqlWrite("UPDATE metrics SET name='Unproved edit' WHERE uuid=?", arrayOf(metric.uuid))
        val local = localProof()
        unchanged { val proof = reader().read(local)
            assertEquals(setOf(ReplicaRecordKey("metric", metric.uuid)), proof.unconfirmed)
            assertFalse(local.configuration.requests.hasUnsettledRequests); assertFalse(proof.activationAuthorized) }
    }
    @Test fun originalCountRuleSurvivesTargetAndTrackingModeChangesWithoutRelabellingHistory() = runBlocking<Unit> {
        initialize(); countFact()
        producer().writeRounds(producer().captureRounds()) {
            db.habitDao().update(habit.copy(habitType = HabitType.TIMER, targetValue = 2,
                planMetadata = requireNotNull(habit.planMetadata).copy(targetUnit = "second")))
        }
        val local = localProof()
        unchanged { val proof = reader().read(local)
            assertEquals(setOf(ReplicaRecordKey("plan_node", habit.uuid), ReplicaRecordKey("activity_event", id(40))), proof.unconfirmed)
            assertEquals(6L, proof.records.getValue(ReplicaRecordKey("activity_event", id(40))).getValue("value").jsonObject.getValue("value").jsonPrimitive.long)
            assertEquals(CountDayPolicy(10, false), db.countDayDao().forHabit(habit.id).single().policy)
            assertFalse(proof.hasUnfinishedTimer); assertFalse(proof.activationAuthorized) }
    }
    @Test fun actualAcceptedCountIsConfirmedButChangingItsImmutableQuantityIsRejected() = runBlocking<Unit> {
        initialize(); val row = countFact()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        val local = localProof()
        unchanged { assertTrue(reader().read(local).unconfirmed.isEmpty()) }
        sqlWrite("UPDATE completions SET value=7 WHERE uuid=?", arrayOf(id(40)))
        val changed = localProof(); unchanged { rejected { reader().read(changed) } }
    }
    @Test fun completedRemoteTimerKeepsAllAllocationsAfterCurrentModeChangedToCheck() = runBlocking<Unit> {
        initialize(remoteTimer = true, changedTimerMode = true); val local = localProof()
        unchanged { val proof = reader().read(local)
            assertEquals(4, proof.records.size); assertTrue(proof.unconfirmed.isEmpty()); assertFalse(proof.hasUnfinishedTimer)
            assertFalse(proof.activationAuthorized)
            val full = db.withTransaction { TimerHistoryReader(db, tokens, sessions).recoveryInTransaction(timerHabit, LocalDate.of(2000, 1, 1)) }
            assertEquals(60_000L, full.completedMillis[LocalDate.parse("2026-10-06")]); assertEquals(listOf(id(30)), full.logs.map { it.uuid })
            assertFalse(full.hasUnfinishedTimer)
            assertTrue(runCatching { (full.logs as MutableList<*>).clear() }.isFailure)
            assertEquals("TIMER_ACTIVITY_CHANGED", rejected { TimerHistoryReader(db, tokens, sessions).read(timerHabit) }.message) }
    }
    @Test fun completedTimerAllocationCorruptionCannotHideBehindChangedTrackingMode() = runBlocking<Unit> {
        initialize(remoteTimer = true, changedTimerMode = true)
        sqlWrite("UPDATE timelog_day_allocations SET durationMillis=59000 WHERE sessionUuid=?", arrayOf(id(30)))
        val local = localProof(); unchanged { rejected { reader().read(local) } }
    }
    @Test fun sharedRecoveryScopeKeepsNonemptyCountAndTimerEvidenceAfterATrackingModeChange() = runBlocking<Unit> {
        initialize(remoteTimer = true, changedTimerMode = true); countFact()
        val local = localProof()
        val original = access()
        val day = LocalDate.of(2000, 1, 1)
        unchanged {
            // unchanged reopens Room before the callback; bind to that current instance.
            val counts = RecurringCompletionEvidenceReader(db, tokens, sessions)
            val timers = TimerHistoryReader(db, tokens, sessions)
            val expected = sessions.exclusive { db.withTransaction {
                counts.recoveryInTransaction(habit, day) to timers.recoveryInTransaction(timerHabit, day)
            } }
            val actual = ReplicaFactRecoveryReadScope.withSnapshot(db, tokens, sessions, original) {
                counts.recoveryInTransaction(habit, day, it) to timers.recoveryInTransaction(timerHabit, day, it)
            }
            assertEquals(expected.first, actual.first)
            assertEquals(listOf(6), actual.first.completions.map { it.value })
            assertEquals(expected.second.logs, actual.second.logs)
            assertEquals(expected.second.completedMillis, actual.second.completedMillis)
            assertEquals(60_000L, actual.second.completedMillis[LocalDate.parse("2026-10-06")])
            assertFalse(actual.second.hasUnfinishedTimer)
            assertEquals(HabitType.CHECK_IN, timerHabit.habitType)
            val proof = reader().read(local)
            assertTrue(ReplicaRecordKey("activity_event", id(40)) in proof.unconfirmed)
            assertTrue(ReplicaRecordKey("activity_event", id(30)) in proof.records)
        }
    }
    @Test fun activeTimerAndOriginalStartRemainUnconfirmedAndBlockAcrossReadOnlyPermission() = runBlocking<Unit> {
        initialize(); activeTimer(); register(permissions = setOf("sync.read"), revision = 2)
        val local = localProof()
        unchanged { val proof = reader().read(local)
            assertTrue(proof.hasUnfinishedTimer); assertTrue(local.configuration.requests.hasUnsettledRequests)
            assertEquals(setOf(ReplicaRecordKey("activity_event", id(20))), proof.unconfirmed); assertFalse(proof.activationAuthorized) }
    }
    @Test fun fractionalMetricObservationIsNotCoercedToIntegerOrInventedAsAccepted() = runBlocking<Unit> {
        initialize(); observe(); val local = localProof()
        unchanged { val proof = reader().read(local)
            assertEquals(setOf(ReplicaRecordKey("metric_observation", db.metricLogDao().getAllLogsForMetric(metric.id).single().uuid)), proof.unconfirmed)
            assertEquals(21.125, db.metricLogDao().getAllLogsForMetric(metric.id).single().value, 0.0) }
    }
    @Test fun acceptedObservationSameIdentityValueEditStillNeedsExplicitReview() = runBlocking<Unit> {
        initialize(); val row = observe()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        val first = localProof(); unchanged { assertTrue(reader().read(first).unconfirmed.isEmpty()) }
        sqlWrite("UPDATE metric_logs SET value=22.125 WHERE uuid=?", arrayOf(row.entityUuid))
        val local = localProof(); unchanged { assertEquals(setOf(ReplicaRecordKey("metric_observation", row.entityUuid)), reader().read(local).unconfirmed) }
    }
    @Test fun rawBooleanAndIntegerWrapAreRejectedBeforeRoomCoercion() = runBlocking<Unit> {
        initialize()
        for ((column, broken, healthy) in listOf(Triple("isActive", 2L, 1L), Triple("targetValue", 4_294_967_306L, 10L))) {
            sqlWrite("UPDATE habits SET $column=? WHERE uuid=?", arrayOf<Any>(broken, habit.uuid))
            val local = localProof(); unchanged { rejected { reader().read(local) } }
            sqlWrite("UPDATE habits SET $column=? WHERE uuid=?", arrayOf<Any>(healthy, habit.uuid))
        }
    }
    @Test fun duplicateJsonAndWrappedScheduleCannotBecomeCanonicalCurrentStructure() = runBlocking<Unit> {
        initialize()
        for (schedule in listOf("{\"type\":\"daily\",\"type\":\"daily\"}", "{\"type\":\"monthly\",\"dayOfMonth\":4294967297}")) {
            sqlWrite("UPDATE habits SET schedule=? WHERE uuid=?", arrayOf(schedule, habit.uuid))
            val local = localProof(); unchanged { rejected { reader().read(local) } }
        }
    }
    @Test fun ordinaryParentAndCycleCannotPassTheSingleGoalParentRule() = runBlocking<Unit> {
        initialize(); sqlWrite("UPDATE habits SET parentHabitId=? WHERE uuid=?", arrayOf(timerHabit.uuid, habit.uuid))
        val local = localProof(); unchanged { rejected { reader().read(local) } }
    }
    @Test fun actualPendingOnceUsesFrozenOriginalFactAndNotAConfirmedBaseline() = runBlocking<Unit> {
        initialize(withOnce = true); val activity = onceHabit!!
        once().appendRounds(producer().captureRounds(), OneTimeLocalCommand(activity.uuid,
            PendingOneTimeIntent(id(100), OneTimeIntent(id(101), "complete", 0, null, null)), millis, "Asia/Shanghai"))
        val local = localProof()
        unchanged { val proof = reader().read(local)
            assertEquals(setOf(ReplicaRecordKey("activity_event", id(101))), proof.unconfirmed); assertFalse(proof.activationAuthorized) }
        sqlWrite("UPDATE completions SET date=date+1 WHERE uuid=?", arrayOf(id(101)))
        val changed = localProof(); unchanged { rejected { reader().read(changed) } }
    }
    @Test fun validOnceHeadCannotHideCorruptOlderRetainedTransitionDuringReadOnlyRecovery() = runBlocking<Unit> {
        initialize(withOnce = true, withOnceHistory = true)
        val activity = requireNotNull(onceHabit)
        val confirmed = requireNotNull(db.habitDao().getHabitByUuid(activity.uuid))
        assertEquals(2, confirmed.oneTimeConfirmedVersion); assertEquals(id(111), confirmed.oneTimeConfirmedHeadEventUuid)
        assertNull(confirmed.oneTimeConfirmedCompletionEventUuid)
        assertEquals(setOf(id(110), id(111)), db.completionDao().getByHabitOnce(activity.id).map { it.uuid }.toSet())
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val local = localProof()
        unchanged {
            val proof = reader().read(local)
            assertTrue(proof.records.keys.containsAll(setOf(ReplicaRecordKey("activity_event", id(110)),
                ReplicaRecordKey("activity_event", id(111)))))
            assertFalse(proof.hasProjectionReview); assertFalse(proof.activationAuthorized)
            storage.reopen(); assertEquals(proof.records, reader().read(local).records)
        }
        val original = requireNotNull(db.completionDao().getCompletionByUuid(id(110)))
        // A valid latest undo does not make the retained earlier complete-at-odd-version legal.
        sqlWrite("UPDATE completions SET oneTimeExpectedVersion=1, oneTimeExpectedHeadEventUuid=? WHERE uuid=?",
            arrayOf(id(777), original.uuid))
        val corrupt = requireNotNull(db.completionDao().getCompletionByUuid(original.uuid))
        val changed = localProof()
        unchanged {
            val failure = rejected { reader().read(changed) }
            assertTrue(failure is OneTimeLocalException)
            assertEquals(OneTimeLocalException.Reason.INVALID_LOCAL_STATE, (failure as OneTimeLocalException).reason)
            assertEquals(corrupt, db.completionDao().getCompletionByUuid(original.uuid))
            assertEquals(confirmed, db.habitDao().getHabitByUuid(activity.uuid))
            assertTrue(db.syncOutboxDao().getAll().isEmpty())
        }
    }
    @Test fun originalValuesAndAuthorizationInvalidateEarlierWholeProjectionProof() = runBlocking<Unit> {
        initialize(); val local = localProof(); sqlWrite("UPDATE metrics SET name='Changed' WHERE uuid=?", arrayOf(metric.uuid))
        unchanged { assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { reader().read(local) }.message) }
        val current = localProof(); register(permissions = setOf("sync.read"), revision = 2)
        unchanged { assertEquals("SYNC_ACCOUNT_CHANGED", rejected { reader().read(current) }.message) }
    }
    @Test fun acceptedFactMissingItsProjectionIsExplicitReviewEvenWithNoPendingQueue() = runBlocking<Unit> {
        initialize(); val row = countFact()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        sqlWrite("DELETE FROM completions WHERE uuid=?", arrayOf(row.entityUuid))
        val local = localProof()
        unchanged { val proof = reader().read(local)
            assertTrue(proof.unconfirmed.isEmpty()); assertFalse(local.configuration.requests.hasUnsettledRequests)
            assertEquals(setOf(ReplicaRecordKey("activity_event", row.entityUuid)), proof.projectionGaps)
            assertTrue(proof.hasProjectionReview); assertFalse(proof.activationAuthorized)
            assertTrue(runCatching { (proof.projectionGaps as MutableSet<*>).clear() }.isFailure) }
    }
    @Test fun actualAcceptedRevertExplainsAbsentProjectionWithoutLosingOriginalAuthority() = runBlocking<Unit> {
        initialize(); val first = countFact()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), first.operationId))
        val fact = db.completionDao().getCompletionByUuid(first.entityUuid)!!
        producer().writeRounds(producer().captureRounds()) { db.completionDao().delete(fact) }
        val undo = db.syncOutboxDao().getAll().single()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), undo.operationId))
        val local = localProof()
        unchanged { val proof = reader().read(local)
            assertFalse(proof.hasProjectionReview); assertTrue(proof.projectionGaps.isEmpty()); assertFalse(proof.activationAuthorized)
            assertEquals(0, count("completions")); assertNotNull(db.syncOutboxDao().getState("activity_event", first.entityUuid))
            assertNotNull(db.syncOutboxDao().getState("activity_event", undo.wireEntityUuid!!)) }
    }
    @Test fun actualParentTombstoneExplainsCascadedHistoryWithoutDemandingCurrentParent() = runBlocking<Unit> {
        initialize(); val row = countFact()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        val original = canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit))
        val deletion = original.copy(sequence = 21, operation = "delete", revision = 2, payload =
            JsonObject(original.payload + mapOf("revision" to JsonPrimitive(2), "deleted_at" to JsonPrimitive(time))))
        val meta = metadata(); val state = merger().state(access(), true)!!
        merger().page(access(), state, RoundSyncPullResponse(listOf(deletion), 21, false, time, 1, meta.checkpoints, meta.births))
        assertNull(db.habitDao().getHabitByUuid(habit.uuid)); assertEquals(0, count("completions"))
        val local = localProof()
        unchanged { val proof = reader().read(local)
            assertEquals(2, proof.records.size); assertFalse(proof.hasProjectionReview); assertFalse(proof.activationAuthorized)
            assertNotNull(db.syncOutboxDao().getState("activity_event", row.entityUuid)) }
    }
    @Test fun completeGoalChildMetricGraphRetainsInactiveLinkAndDetectsActualPendingLinkEdit() = runBlocking<Unit> {
        initialize(withGraph = true); val local = localProof()
        unchanged { val proof = reader().read(local)
            assertEquals(5, proof.records.size); assertFalse(proof.hasProjectionReview); assertFalse(proof.activationAuthorized)
            assertTrue(ReplicaRecordKey("plan_node", goal!!.uuid) in proof.records)
            assertTrue(ReplicaRecordKey("activity_metric_link", id(15)) in proof.records)
            assertFalse(db.habitMetricLinkDao().getLinkByUuid(id(15))!!.isActive) }
        producer().writeRounds(producer().captureRounds()) {
            val link = db.habitMetricLinkDao().getLinkByUuid(id(15))!!
            db.habitMetricLinkDao().update(link.copy(coefficient = 2.0, showInHabitDetail = false))
        }
        val edited = localProof()
        unchanged { val proof = reader().read(edited)
            assertEquals(setOf(ReplicaRecordKey("activity_metric_link", id(15))), proof.unconfirmed)
            assertTrue(proof.hasProjectionReview); assertTrue(edited.configuration.requests.hasUnsettledRequests) }
    }
    @Test fun linkCannotAliasASecondHabitUuidWhileRetainingTheFirstLocalForeignKey() = runBlocking<Unit> {
        initialize(withGraph = true)
        sqlWrite("UPDATE habit_metric_links SET habitUuid=? WHERE uuid=?", arrayOf(timerHabit.uuid, id(15)))
        val local = localProof(); unchanged { rejected { reader().read(local) } }
    }
}
