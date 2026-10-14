package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.reminder.*
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.checkin.actionIntent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Real producers, accepted metadata/Plan pages, shared consumers and original source proofs. */
@RunWith(AndroidJUnit4::class)
class CountRoundConsumerTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private var restarted = false
    private val births = linkedMapOf<String, ChallengeBirth>()
    private val today get() = DateTimeUtils.today()
    @Before fun finiteChallengeFixture() = runBlocking<Unit> {
        habit = habit.copy(targetCycles = 3, bestTime = 540,
            planMetadata = requireNotNull(habit.planMetadata).copy(preferredLocalTime = "09:00"))
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }
    private fun initial(uuid: String) = ChallengeRoundRecord(initialChallengeRoundHead(uuid), null, null, null)
    private fun restart() = ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, id(701), 1), id(4), id(702),
        ChallengeRestartIntent(habit.uuid, id(701), initialChallengeRoundUuid(habit.uuid), 0, 1))
    private fun metadata(): ChallengeMetadata {
        val history = listOf(initial(habit.uuid)) + if (restarted) listOf(restart()) else emptyList()
        return ChallengeMetadata(1, listOf(ChallengeCheckpoint(history.last().head, history),
            ChallengeCheckpoint(initial(timerHabit.uuid).head, listOf(initial(timerHabit.uuid)))), births.values.toList())
    }
    private fun canonical(row: HabitEntity, revision: Long) = SyncV2Change(0, "plan_node", row.uuid, "upsert", revision,
        JsonObject(NextStructureMapper.writePlan(row) + mapOf("public_id" to JsonPrimitive(row.uuid),
            "revision" to JsonPrimitive(revision), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
            "deleted_at" to JsonNull)), time, id(4))
    private fun reader() = CountHistoryReader(db, tokens, sessions)
    private fun widgets() = WidgetFactReader(db, tokens, sessions, preferences, reader())
    private fun repository() = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
        nextObjectEditor = editor, countHistoryReader = reader(), widgetFactReader = widgets())
    private fun calculator() = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao(), reader()),
        db.completionDao(), db.timeLogDao(), countHistoryReader = reader())
    private suspend fun current() = requireNotNull(db.habitDao().getHabitById(habit.id))
    private suspend fun history() = reader().read(current(), today)
    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions,
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)),
        NextTimerRequestStore(db, tokens, sessions, sender(http)))
    private suspend fun initialize(): NextSyncHttp {
        register()
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
                val operation = request.operations.single()
                births[operation.entityUuid] = ChallengeBirth("activity_event", operation.entityUuid,
                    requireNotNull(request.contexts.single().head))
                val ordinary = json.decodeFromString<NextSyncPushResponse>(successReply(input, 1).bytes.toString(Charsets.UTF_8))
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(ordinary.results, 1,
                    meta.checkpoints, meta.births)).toByteArray())
            }
        }
        val meta = metadata()
        val metricChange = SyncV2Change(0, "metric", metric.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writeMetric(metric) + mapOf("public_id" to JsonPrimitive(metric.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
                "deleted_at" to JsonNull)), time)
        merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(canonical(habit, 1), canonical(timerHabit, 1), metricChange),
            20, time, emptyList(), 1, meta.checkpoints, meta.births))
        return http
    }
    private suspend fun advance(http: NextSyncHttp, target: Int = 10, countdown: Boolean = false) {
        val store = merger(http)
        val state = requireNotNull(store.state(access(), true))
        restarted = true
        val meta = metadata()
        store.page(access(), state, RoundSyncPullResponse(listOf(canonical(current().copy(targetValue = target,
            isCountdown = countdown), 2).copy(sequence = state.cursor + 1)), state.cursor + 1, false,
            time, 1, meta.checkpoints, meta.births))
    }
    private suspend fun fact(n: Int, quantity: Int, date: LocalDate = today): CompletionEntity {
        val row = current()
        val zone = ZoneId.systemDefault()
        val original = CompletionEntity(habitId = row.id, habitUuid = row.uuid, uuid = id(n), value = quantity,
            date = date.atStartOfDay(zone).toInstant().toEpochMilli(),
            actualCompletedAt = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            recordedTimezone = zone.id, recordedLocalDate = date.toString())
        val saved = producer().writeRounds(producer().captureRounds()) {
            NextCountDayStore(db).capture(row, original)
            db.completionDao().insert(original)
        }
        return original.copy(id = saved)
    }
    private suspend fun accept(http: NextSyncHttp, fact: CompletionEntity) {
        val source = db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid }
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), source.operationId))
    }
    private fun durable() = listOf("habits", "completions", "count_days", "sync_outbox", "sync_entity_state",
        "next_request_origins", "next_transmissions", "next_acceptances", "next_challenge_state",
        "next_challenge_rounds", "next_challenge_births").associateWith { table ->
        db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { column ->
                    val type = c.getType(column)
                    val value = when (type) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(
                            c.getBlob(column), android.util.Base64.NO_WRAP)
                        else -> c.getString(column)
                    }
                    type to value
                })
            }
        }
    }

    @Test fun sameDayRestartResetsOnlyQuantitiesAndKeepsOriginalDayPolicyBeforeAndAfterAck() = runBlocking<Unit> {
        val http = initialize()
        val old = fact(800, 10); accept(http, old)
        val original = db.nextRequestDao().origin(NEXT_OPERATION,
            db.openHelper.readableDatabase.query("SELECT requestId FROM next_request_origins WHERE intentJson LIKE ?",
                arrayOf("%${old.uuid}%")).use { assertTrue(it.moveToFirst()); it.getString(0) })!!
        advance(http, 20, true)
        val empty = history()
        assertEquals(restart().head, empty.roundHead)
        assertEquals(CountDayPolicy(10, false), empty.todayPolicy)
        assertEquals(0L, empty.todayQuantity); assertNull(empty.firstDate); assertFalse(empty.completedToday)
        assertFalse(FailureCheckerUtils.countHasFailed(current().copy(targetCycles = 3, failMode = FailMode.STRICT), empty))
        val fresh = fact(801, 3)
        assertEquals(listOf(fresh.uuid), history().completions.map { it.uuid })
        assertEquals(3L, history().todayQuantity); assertTrue(history().qualifiedDates.isEmpty())
        accept(http, fresh); storage.reopen()
        assertEquals(3L, history().todayQuantity); assertEquals(CountDayPolicy(10, false), history().todayPolicy)
        assertEquals(listOf(3, 10), db.completionDao().getByHabitOnce(habit.id).map { it.value }.sorted())
        assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, original.requestId))
        assertEquals(initial(habit.uuid).head, merger(http).challengeMetadata(access())!!.requireBirth("activity_event", old.uuid, habit.uuid).head)
        assertEquals(restart().head, merger(http).challengeMetadata(access())!!.requireBirth("activity_event", fresh.uuid, habit.uuid).head)
    }

    @Test fun pendingOldFactsKeepOriginalBirthAndDoNotReappearWhenTheirLateAckArrives() = runBlocking<Unit> {
        val http = initialize()
        val old = fact(810, 10, today.minusDays(2))
        val oldQueue = db.syncOutboxDao().getAll().single()
        val oldOrigin = db.nextRequestDao().origin(NEXT_OPERATION, oldQueue.operationId)!!
        advance(http)
        assertEquals(0L, history().todayQuantity); assertTrue(history().quantities.isEmpty())
        val fresh = fact(811, 10)
        assertEquals(setOf(today), history().qualifiedDates)
        accept(http, old)
        val after = history()
        assertEquals(setOf(today), after.qualifiedDates); assertEquals(listOf(fresh.uuid), after.completions.map { it.uuid })
        assertEquals(oldOrigin, db.nextRequestDao().origin(NEXT_OPERATION, oldQueue.operationId))
        val stats = calculator().calculate(current())
        assertTrue(stats.completedToday); assertEquals(1, stats.currentStreak); assertEquals(1, stats.bestStreak)
        assertEquals(10L, stats.actualTodayCount)
        assertEquals(10, repository().getTodayCompletionCount(habit.id))
        assertEquals(fresh.id, repository().getTodayCompletionId(habit.id))
        assertEquals(1, repository().getStreakStats(habit.id).first().bestStreak)
    }

    @Test fun hiddenOldFactCorruptionAndMissingBirthStillFailReadOnly() = runBlocking<Unit> {
        val http = initialize(); val old = fact(820, 10); accept(http, old); advance(http)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=11 WHERE id=?", arrayOf(old.id))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val before = durable()
        assertEquals("COUNT_FACT_INVALID", rejected { history() }.message)
        assertEquals(before, durable())
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.completionDao().upsert(old)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_challenge_births WHERE entityUuid=?", arrayOf(old.uuid))
        val missing = durable()
        assertEquals("SYNC_CHALLENGE_PROOF_CHANGED", rejected { history() }.message)
        assertEquals(missing, durable())
    }

    @Test fun pendingSourceDeletionWrongDeviceReplicaAndFrozenContextCannotBeAdopted() = runBlocking<Unit> {
        val http = initialize(); val localFact = fact(830, 3)
        val source = db.syncOutboxDao().getAll().single()
        val origin = db.nextRequestDao().origin(NEXT_OPERATION, source.operationId)!!
        val intent = requireNotNull(roundOperationIntent(origin.intentJson))
        db.syncOutboxDao().deleteById(source.id)
        val absent = durable()
        assertEquals("COUNT_ROUND_SOURCE_CHANGED", rejected { history() }.message)
        assertEquals(absent, durable())
        db.syncOutboxDao().insert(source)
        for (wrong in listOf(origin.copy(syncEpoch = id(99)), origin.copy(intentJson = json.encodeToString(intent.copy(capturedDeviceId = id(99)))))) {
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET syncEpoch=?,intentJson=? WHERE requestId=?",
                arrayOf(wrong.syncEpoch, wrong.intentJson, origin.requestId))
            val before = durable(); assertNotNull(runCatching { history() }.exceptionOrNull()); assertEquals(before, durable())
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET syncEpoch=?,intentJson=? WHERE requestId=?",
                arrayOf(origin.syncEpoch, origin.intentJson, origin.requestId))
        }
        assertEquals(3L, history().todayQuantity)
        sender(http).sendOperation(access(), source.operationId) // Actual frozen send, deliberately not local acceptance.
        advance(http)
        val changed = intent.copy(context = intent.context.copy(head = restart().head))
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?",
            arrayOf(json.encodeToString(changed), source.operationId))
        val before = durable(); assertNotNull(runCatching { history() }.exceptionOrNull()); assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?",
            arrayOf(origin.intentJson, source.operationId))
        assertTrue(history().completions.isEmpty())
        assertEquals(localFact.uuid, db.completionDao().getByHabitOnce(habit.id).single().uuid)
    }

    @Test fun newPendingCreationUsesOnlyOriginalInitialProofAndDoesNotWriteAcceptedBirth() = runBlocking<Unit> {
        initialize()
        val row = habit.copy(id = 0, uuid = id(840), name = "Still offline")
        val localId = producer().writeRounds(producer().captureRounds()) { db.habitDao().insert(row) }
        val pending = requireNotNull(db.habitDao().getHabitById(localId))
        val captured = CompletionEntity(habitId = localId, habitUuid = pending.uuid, uuid = id(841), value = 5,
            date = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            actualCompletedAt = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        producer().writeRounds(producer().captureRounds()) {
            NextCountDayStore(db).capture(pending, captured); db.completionDao().insert(captured)
        }
        assertEquals(5L, reader().read(pending, today).todayQuantity)
        assertEquals(initialChallengeRoundHead(pending.uuid), reader().read(pending, today).roundHead)
        assertTrue(db.nextChallengeDao().births().isEmpty())
        assertFalse(db.nextChallengeDao().rounds().any { it.activityUuid == pending.uuid })
        val original = db.syncOutboxDao().getAll().single { it.recordType == "habit" }
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE requestId=?", arrayOf(id(99), original.operationId))
        val before = durable(); assertNotNull(runCatching { reader().read(pending, today) }.exceptionOrNull()); assertEquals(before, durable())
    }

    @Test fun widgetDisplaysOnlyNewRoundAndOldIncrementClaimCannotMintNewRoundAuthority() = runBlocking<Unit> {
        val http = initialize(); val old = fact(850, 10)
        val oldClaim = widgets().read(current()).claim
        advance(http)
        val empty = widgets().read(current())
        assertNull(empty.claim.completionUuid); assertFalse(empty.completed); assertEquals(0, empty.targetProgress)
        assertEquals(restart().head, empty.claim.roundHead)
        assertFalse(oldClaim.actionIntent(app, "increment").filterEquals(empty.claim.actionIntent(app, "increment")))
        val before = durable()
        assertEquals("FACT_WIDGET_ROUND_CHANGED", rejected { repository().performWidgetFact(app,
            WidgetFactClaim.decode(oldClaim.encode()), "increment") }.message)
        assertEquals(before, durable())
        val actual = repository().performWidgetFact(app, WidgetFactClaim.decode(empty.claim.encode()), "increment")
        assertEquals(1L, actual.count!!.todayQuantity)
        val fresh = db.completionDao().getCompletionByUuid(actual.claim.completionUuid!!)!!
        assertEquals(restart().head, roundOperationIntent(db.nextRequestDao().origin(NEXT_OPERATION,
            db.syncOutboxDao().getAll().single { it.entityUuid == fresh.uuid }.operationId)!!.intentJson)!!.context.head)
        assertEquals(initial(habit.uuid).head, roundOperationIntent(db.nextRequestDao().origin(NEXT_OPERATION,
            db.syncOutboxDao().getAll().single { it.entityUuid == old.uuid }.operationId)!!.intentJson)!!.context.head)
        val undone = repository().performWidgetFact(app, actual.claim, "undo")
        assertEquals(0L, undone.count!!.todayQuantity); assertNull(undone.claim.completionUuid)
        assertEquals(listOf(old.uuid), db.completionDao().getByHabitOnce(habit.id).map { it.uuid })
    }

    @Test fun oldPlainWidgetClaimCannotUpgradeAndMalformedRoundDeclarationFailsDecode() = runBlocking<Unit> {
        register(); val old = widgets().read(current()).claim; initialize()
        assertEquals(0, old.challengeContract)
        val before = durable()
        assertEquals("FACT_WIDGET_ROUND_CHANGED", rejected { widgets().authorize(old, exactFact = false) }.message)
        assertEquals(before, durable())
        val fresh = widgets().read(current()).claim
        assertEquals(1, fresh.challengeContract)
        assertNotNull(runCatching { WidgetFactClaim.decode(fresh.encode().replace("\"challengeContract\":1", "\"challengeContract\":\"1\"")) }.exceptionOrNull())
        assertNotNull(runCatching { WidgetFactClaim.decode(fresh.copy(roundHead = initialChallengeRoundHead(timerHabit.uuid)).encode()) }.exceptionOrNull())
    }

    @Test fun roundOnlyChangeInvalidatesObserversAndLegacyClearCannotDeleteProfileHistory() = runBlocking<Unit> {
        val http = initialize(); fact(860, 10)
        val seen = MutableStateFlow(0)
        val observer = launch(Dispatchers.IO) { reader().changes.collect { seen.update { it + 1 } } }
        try {
            withTimeout(5000) { seen.first { it > 0 } }
            val before = seen.value
            val store = merger(http); val state = store.state(access(), true)!!
            restarted = true; val meta = metadata()
            store.page(access(), state, RoundSyncPullResponse(emptyList(), state.cursor + 1, false, time, 1, meta.checkpoints, meta.births))
            withTimeout(5000) { seen.first { it > before } }
            assertTrue(history().completions.isEmpty()) // No fact/habit/day row changed.
            val original = durable()
            assertEquals("SYNC_CHALLENGE_PROFILE_REQUIRED", rejected { repository().clearHabitHistory(current(), app) }.message)
            assertEquals(original, durable())
        } finally { observer.cancelAndJoin() }
    }

    @Test fun accountReplacementAndBrokenProfileDoNotFallBackToAllHistory() = runBlocking<Unit> {
        val http = initialize(); fact(870, 10); advance(http)
        db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET challengeContract=0 WHERE id=1")
        val before = durable(); assertNotNull(runCatching { history() }.exceptionOrNull()); assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET challengeContract=1 WHERE id=1")
        assertTrue(history().completions.isEmpty())
        tokens.saveLoginSession("synthetic-other", "synthetic-other-refresh", "other", id(99), false)
        val changed = durable(); assertNotNull(runCatching { history() }.exceptionOrNull()); assertEquals(changed, durable())
    }

    @Test fun unboundLegacyQuantityIsNotGuessedIntoEitherInitialOrCurrentRound() = runBlocking<Unit> {
        val http = initialize(); advance(http)
        val date = today.minusDays(1)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.completionDao().insertForSync(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(880),
                date = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), value = 50,
                recordedLocalDate = date.toString(), timeMetadataSource = "legacy_device_fallback"))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val before = durable()
        assertEquals("COUNT_ROUND_BIRTH_REQUIRED", rejected { history() }.message)
        assertEquals(before, durable())
    }

    @Test fun reminderRejectsOldRoundWakeAndPublishesOnlyCurrentRoundProgress() = runBlocking<Unit> {
        val http = initialize(); fact(890, 6)
        class Alarms : ReminderAlarms {
            var pending: Pair<ReminderReading, HabitReminderWake>? = null
            val shown = mutableListOf<ReminderReading>()
            override fun replace(reading: ReminderReading, wake: HabitReminderWake) {
                assertFalse(db.inTransaction()); pending = reading to wake
            }
            override fun cancel(habitId: Long) { assertFalse(db.inTransaction()); pending = null }
            override fun cancelAll() { assertFalse(db.inTransaction()); pending = null }
            override fun publish(reading: ReminderReading, wake: HabitReminderWake) { assertFalse(db.inTransaction()); shown += reading }
            override fun clearPublished(keepScope: String?) { assertFalse(db.inTransaction()) }
        }
        val alarms = Alarms()
        val controller = HabitReminderController(db, tokens, sessions, reader(), preferences, dataStore, alarms)
        controller.schedule(habit.id, today.atTime(8, 0).atZone(ZoneId.systemDefault()))
        val (old, wake) = requireNotNull(alarms.pending)
        assertEquals(6L, old.quantity); assertEquals(initial(habit.uuid).head, old.roundHead)
        val delivery = ReminderDelivery(habit.id, habit.uuid, old.scope, old.stamp, wake)
        advance(http)
        val before = durable()
        controller.deliver(delivery, wake.trigger.atZone(wake.zone))
        assertTrue(alarms.shown.isEmpty()); assertEquals(before, durable())
        controller.schedule(habit.id, today.atTime(8, 0).atZone(ZoneId.systemDefault()))
        val (fresh, next) = requireNotNull(alarms.pending)
        assertEquals(0L, fresh.quantity); assertEquals(restart().head, fresh.roundHead)
        assertNotEquals(old.stamp, fresh.stamp)
        controller.deliver(ReminderDelivery(habit.id, habit.uuid, fresh.scope, fresh.stamp, next), next.trigger.atZone(next.zone))
        assertEquals(listOf(0L), alarms.shown.map { it.quantity }); assertEquals(before, durable())
    }
}
