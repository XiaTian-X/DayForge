package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Focused production-entry tests. Real Room, account state, outbox and runtime; synthetic TCP peer. */
@RunWith(AndroidJUnit4::class)
class HabitRestartEntryTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private val history = mutableListOf<ChallengeRoundRecord>()
    private val changes = mutableListOf<SyncV2Change>()
    private val births = mutableListOf<ChallengeBirth>()
    private var revision = 1L
    private var cursor = 20L
    private lateinit var serverPlan: HabitEntity

    private fun metadata() = ChallengeMetadata(1, listOf(
        ChallengeCheckpoint(history.last().head, history.toList()),
        ChallengeCheckpoint(initialChallengeRoundHead(timerHabit.uuid),
            listOf(ChallengeRoundRecord(initialChallengeRoundHead(timerHabit.uuid), null, null, null)))
    ), births.toList())

    private fun plan(row: HabitEntity, revision: Long) = SyncV2Change(0, "plan_node", row.uuid, "upsert", revision,
        JsonObject(NextStructureMapper.writePlan(row) + mapOf("public_id" to JsonPrimitive(row.uuid),
            "revision" to JsonPrimitive(revision), "created_at" to JsonPrimitive(time),
            "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time, id(4))

    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions,
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)),
        NextTimerRequestStore(db, tokens, sessions, sender(http)))
    private fun runtime(http: NextSyncHttp) = NextSyncRuntime(db, tokens, sessions, http, preferences)
    private suspend fun current() = requireNotNull(db.habitDao().getHabitById(habit.id))
    private suspend fun durable() = db.withTransaction { nextRestartDatabaseProof(db) }

    private fun restartingHabits(http: NextSyncHttp) = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
        nextObjectEditor = editor, nextObjectCreator = creator, countHistoryReader = CountHistoryReader(db, tokens, sessions),
        recurringHabitReader = RecurringHabitReader(db, tokens, sessions, editor),
        restartEntry = HabitRestartEntry(NextChallengeRestartRepository(db, tokens, sessions, http)))

    private suspend fun initialize(): NextSyncHttp {
        habit = habit.copy(targetCycles = 3, isActive = false)
        timerHabit = timerHabit.copy(targetCycles = 3)
        serverPlan = habit
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            db.habitDao().update(timerHabit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        history += ChallengeRoundRecord(initialChallengeRoundHead(habit.uuid), null, null, null)
        register()
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input, 5)
            else if (input.path.startsWith("/api/v2/sync/rounds/changes")) {
                val from = input.target.substringAfter("cursor=").substringBefore('&').toLong()
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(changes.filter { it.sequence > from },
                    cursor, false, time, 1, meta.checkpoints, meta.births)).toByteArray())
            } else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                val body = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
                val operation = body.operations.single()
                val result = if (operation.entityType == "activity_event") {
                    val head = requireNotNull(body.contexts.single().head)
                    assertTrue(history.any { it.head == head })
                    births += ChallengeBirth("activity_event", operation.entityUuid, head)
                    val accepted = json.decodeFromString<NextSyncPushResponse>(
                        successReply(input).bytes.toString(Charsets.UTF_8)).results.single()
                    val canonical = requireNotNull(accepted.entity)
                    changes += SyncV2Change(++cursor, "activity_event", operation.entityUuid, "upsert", 1,
                        canonical, canonical.getValue("updated_at").jsonPrimitive.content, id(4))
                    accepted
                } else {
                    assertEquals("challenge_round", operation.entityType)
                    assertNull(body.contexts.single().head)
                    val intent = json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload)
                    assertEquals(history.last().head.roundUuid, intent.expectedRoundUuid)
                    assertEquals(history.last().head.generation, intent.expectedGeneration)
                    assertEquals(revision, intent.expectedPlanRevision)
                    val record = ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, intent.roundUuid,
                        intent.expectedGeneration + 1), id(4), operation.operationId, intent)
                    history += record
                    serverPlan = serverPlan.copy(isActive = true)
                    revision++
                    changes += SyncV2Change(++cursor, "challenge_round", record.head.roundUuid, "upsert", 1,
                        json.encodeToJsonElement(record).jsonObject, time, id(4))
                    changes += plan(serverPlan, revision).copy(sequence = ++cursor)
                    NextSyncOperationResult(operation.operationId, operation.entityType, operation.entityUuid,
                        "applied", 1, entity = json.encodeToJsonElement(record).jsonObject)
                }
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1,
                    meta.checkpoints, meta.births)).toByteArray())
            }
        }
        val metricChange = SyncV2Change(0, "metric", metric.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writeMetric(metric) + mapOf("public_id" to JsonPrimitive(metric.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time),
                "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        val meta = metadata()
        merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(
            listOf(plan(habit, 1), plan(timerHabit, 1), metricChange), cursor, time, emptyList(),
            1, meta.checkpoints, meta.births))
        return http
    }

    private suspend fun fact(n: Int, quantity: Int): CompletionEntity {
        val date = java.time.LocalDate.parse("2026-10-06")
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val row = current()
        val original = CompletionEntity(habitId = row.id, habitUuid = row.uuid, uuid = id(n), value = quantity,
            date = date.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = millis,
            recordedTimezone = zone.id, recordedLocalDate = date.toString())
        val saved = producer().writeRounds(producer().captureRounds()) {
            NextCountDayStore(db).capture(row, original)
            db.completionDao().insert(original)
        }
        return original.copy(id = saved)
    }

    private suspend fun countHistory() = CountHistoryReader(db, tokens, sessions)
        .read(current(), java.time.LocalDate.parse("2026-10-06"))

    private fun widgetHabits(http: NextSyncHttp): HabitRepository {
        val guard = io.mockk.mockk<com.dayforge.domain.service.StructuralEditGuard>()
        io.mockk.coEvery { guard.requireAllowed() } returns Unit
        val facts = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
        return HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
            restartEntry = HabitRestartEntry(NextChallengeRestartRepository(db, tokens, sessions, http)),
            widgetRestartEntry = WidgetRestartEntry(db, tokens, sessions, editor, facts, guard))
    }

    private suspend fun widgetFact() = WidgetFactReader(db, tokens, sessions, preferences,
        CountHistoryReader(db, tokens, sessions)).read(current()).claim

    @Test fun widgetDisplayedCountIdentityRestartsOnceAndStaleRoundCannotRecapture() = runBlocking<Unit> {
        val http = initialize()
        fact(1250, 10)
        runtime(http).syncRounds()
        val retained = db.completionDao().getByHabitOnce(habit.id)
        val repo = widgetHabits(http)
        val claim = WidgetFactClaim.decode(widgetFact().encode())
        val before = durable()
        val request = repo.prepareWidgetReactivation(habit.id, claim, null, null)
        assertTrue(request.preservesHistory)
        assertEquals(before, durable())
        repo.reactivate(request, app)
        val committed = durable()
        repo.reactivate(request, app)
        assertEquals(committed, durable())
        rejected { repo.prepareWidgetReactivation(habit.id, claim, null, null) }
        assertEquals(committed, durable())
        assertEquals(retained, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(1, count("sync_outbox"))
        storage.reopen()
        runtime(http).syncRounds()
        assertEquals(0, count("sync_outbox"))
        assertEquals(0L, countHistory().todayQuantity)
        assertEquals(retained, db.completionDao().getByHabitOnce(habit.id))
    }

    @Test fun widgetMissingMixedStalePlanAndReauthenticatedProofsAreReadOnlyRejections() = runBlocking<Unit> {
        val http = initialize()
        val repo = widgetHabits(http)
        val claim = widgetFact()
        val timer = requireNotNull(NextTimerWriter(db, tokens, sessions).widgetSnapshot(timerHabit)).authority
        var before = durable()
        rejected { repo.prepareWidgetReactivation(habit.id, null, null, null) }
        rejected { repo.prepareWidgetReactivation(habit.id, claim, timer, null) }
        rejected { repo.prepareWidgetReactivation(timerHabit.id, claim, null, null) }
        rejected { repo.prepareWidgetReactivation(habit.id, claim.copy(plan = JsonObject(claim.plan +
            ("title" to JsonPrimitive("Not rendered")))), null, null) }
        assertEquals(before, durable())
        val pending = repo.prepareWidgetReactivation(habit.id, claim, null, null)
        tokens.saveLoginSession("synthetic-reauth", "synthetic-refresh", "member", id(1), false)
        before = durable()
        rejected { repo.prepareWidgetReactivation(habit.id, claim, null, null) }
        rejected { repo.reactivate(pending, app) }
        assertEquals(before, durable())
        assertEquals(0, count("sync_outbox"))
    }

    @Test fun widgetTimerProofRestartsAndUnfinishedTimerBlocksOpeningAndLateConfirmation() = runBlocking<Unit> {
        val http = initialize()
        val repo = widgetHabits(http)
        val writer = NextTimerWriter(db, tokens, sessions)
        val proof = requireNotNull(writer.widgetSnapshot(timerHabit)).authority
        val pending = repo.prepareWidgetReactivation(timerHabit.id, null, proof, null)
        val log = com.dayforge.data.local.entity.TimeLogEntity(habitId = timerHabit.id,
            uuid = id(1251), startTime = millis, endTime = null, durationSeconds = 0, date = millis)
        val logId = db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val saved = db.timeLogDao().insert(log)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            saved
        }
        val before = durable()
        assertEquals("CHALLENGE_TIMER_UNFINISHED", rejected {
            repo.prepareWidgetReactivation(timerHabit.id, null, proof, null) }.message)
        assertEquals("CHALLENGE_TIMER_UNFINISHED", rejected { repo.reactivate(pending, app) }.message)
        assertEquals(before, durable())
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.timeLogDao().delete(log.copy(id = logId))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val currentTimer = requireNotNull(db.habitDao().getHabitById(timerHabit.id))
        val refreshed = requireNotNull(writer.widgetSnapshot(currentTimer)).authority
        repo.reactivate(repo.prepareWidgetReactivation(timerHabit.id, null, refreshed, null), app)
        assertEquals(1, count("sync_outbox"))
        assertEquals(1, producer().captureRounds().pendingRestarts.getValue(timerHabit.uuid).head.generation)
        val committed = durable()
        rejected { repo.prepareWidgetReactivation(timerHabit.id, null, proof, null) }
        assertEquals(committed, durable())
    }

    private suspend fun legacy(): LegacyWidgetRestartClaim {
        habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null, targetCycles = 3, isActive = false)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        return requireNotNull(WidgetDisplayPublisher(tokens, sessions).capturePublication()).legacyRestartClaim(habit)!!
    }

    @Test fun legacyDisplayClaimPreservesOldClearBehaviorWithoutNumericIdOrAccountFallback() = runBlocking<Unit> {
        val claim = LegacyWidgetRestartClaim.decode(legacy().encode())
        val (http, _) = channel()
        val repo = widgetHabits(http)
        val saved = db.completionDao().insert(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
            uuid = id(1252), date = millis, value = 10))
        assertTrue(saved > 0)
        val before = durable()
        val request = repo.prepareWidgetReactivation(habit.id, null, null, claim)
        assertFalse(request.preservesHistory)
        assertEquals(before, durable())
        repo.reactivate(request, app)
        assertTrue(current().isActive)
        assertEquals(0, count("completions"))
        assertTrue(db.syncOutboxDao().getAll().isNotEmpty())
        val committed = durable()
        repo.reactivate(request, app)
        assertEquals(committed, durable())
        rejected { repo.prepareWidgetReactivation(habit.id, null, null, claim) }
        assertEquals(committed, durable())
    }

    @Test fun legacyLateAccountChangeAndReusedLocalIdCannotDeleteAnotherIdentity() = runBlocking<Unit> {
        val claim = legacy()
        val (http, _) = channel()
        val repo = widgetHabits(http)
        val request = repo.prepareWidgetReactivation(habit.id, null, null, claim)
        db.openHelper.writableDatabase.execSQL("UPDATE habits SET uuid=? WHERE id=?", arrayOf<Any>(id(1253), habit.id))
        var before = durable()
        rejected { repo.prepareWidgetReactivation(habit.id, null, null, claim) }
        rejected { repo.reactivate(request, app) }
        assertEquals(before, durable())
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "member", id(1254), false)
        before = durable()
        rejected { repo.prepareWidgetReactivation(habit.id, null, null, claim) }
        rejected { repo.reactivate(request, app) }
        assertEquals(before, durable())
    }

    @Test fun actualListConfirmationPreservesHistoryQueuesOneRoundAndSettlesThroughRuntime() = runBlocking<Unit> {
        val http = initialize()
        fact(1200, 10)
        runtime(http).syncRounds()
        val before = current()
        val retained = db.completionDao().getByHabitOnce(habit.id)
        val repo = restartingHabits(http)
        val coordinator = com.dayforge.domain.service.HabitLifecycleCoordinator(app, repo)
        coordinator.showReactivationDialog(before)
        assertTrue(coordinator.reactivationPreservesHistory.value)
        assertTrue(coordinator.showReactivationDialog.value)
        assertEquals(com.dayforge.domain.service.ReactivationResult.SUCCESS, coordinator.confirmReactivation(before))
        assertEquals(com.dayforge.domain.service.ReactivationResult.NO_HABIT, coordinator.confirmReactivation(current()))
        assertFalse(coordinator.showReactivationDialog.value)
        val head = producer().captureRounds().pendingRestarts.getValue(habit.uuid).head
        assertEquals(1, head.generation)
        assertEquals(1, count("sync_outbox"))
        assertEquals(RESTART_RECORD, db.syncOutboxDao().getAll().single().recordType)
        assertEquals(before.copy(isActive = true, updatedAt = current().updatedAt), current())
        assertEquals(retained, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(0L, countHistory().todayQuantity)
        assertEquals(head, countHistory().roundHead)
        storage.reopen()
        runtime(http).syncRounds()
        assertEquals(0, count("sync_outbox"))
        assertEquals(head, countHistory().roundHead)
        assertEquals(retained, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(0L, countHistory().todayQuantity)
    }

    @Test fun actualPreparedRequestIsSingleUseAndStaleAccountOrHeadCannotRecaptureAuthority() = runBlocking<Unit> {
        val http = initialize()
        val repo = restartingHabits(http)
        val request = repo.prepareReactivation(current())
        val stale = repo.prepareReactivation(current())
        repo.reactivate(request, app)
        val committed = durable()
        repo.reactivate(request, app)
        assertEquals(committed, durable())
        rejected { repo.reactivate(stale, app) }
        assertEquals(committed, durable())
        assertEquals(1, count("sync_outbox"))
        val otherAccount = repo.prepareReactivation(current())
        tokens.saveLoginSession("synthetic-other", "synthetic-other-refresh", "member", id(1201), false)
        val before = durable()
        rejected { repo.reactivate(otherAccount, app) }
        assertEquals(before, durable())
        assertEquals(1, count("sync_outbox"))
    }

    @Test fun actualDetailConfirmationRetainsOldFactsAndClearsOnlyCurrentRoundProgress() = runBlocking<Unit> {
        val http = initialize()
        fact(1202, 10)
        val retained = db.completionDao().getByHabitOnce(habit.id)
        val repo = restartingHabits(http)
        val detail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            own(com.dayforge.ui.screens.habitdetail.HabitDetailViewModel(app, repo, preferences, db.timeLogDao(),
                db.completionDao(), db.habitDao(), db.habitMetricLinkDao(), db.metricDao(), db.metricLogDao()))
                .also { it.loadHabit(habit.id) }
        }
        kotlinx.coroutines.withTimeout(5000) { detail.uiState.first { !it.isLoading && !it.readError && it.habit?.isActive == false } }
        val before = durable()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { detail.toggleActiveStatus() }
        kotlinx.coroutines.withTimeout(5000) { detail.uiState.first { it.showReactivationDialog && it.reactivationPreservesHistory } }
        assertEquals(1, count("completions"))
        assertEquals(before, durable()) // Merely opening the confirmation is read-only.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { detail.confirmReactivation() }
        kotlinx.coroutines.withTimeout(5000) { detail.uiState.first { !it.showReactivationDialog && !it.isLoading &&
            !it.readError && it.habit?.isActive == true && it.writeAuthority?.rounds?.pendingRestarts?.containsKey(habit.uuid) == true } }
        assertEquals(retained, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(1, count("sync_outbox") - retained.size) // Original fact plus one restart, no plan toggle.
        assertEquals(0L, countHistory().todayQuantity)
        runtime(http).syncRounds()
        val serverCreated = java.time.Instant.parse("2026-10-06T00:00:01.000123Z").toEpochMilli()
        assertEquals(retained.map { it.copy(createdAt = serverCreated) }, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(0, count("sync_outbox"))
    }
}
