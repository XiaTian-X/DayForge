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
