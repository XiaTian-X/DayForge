package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.ChallengeCheckpoint
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.api.dto.RoundSyncBootstrapResponse
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.PersonalDataDisplayMemory
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.ChallengeRestartIntent
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.ChallengeRoundRecord
import com.dayforge.domain.model.initialChallengeRoundHead
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room and session guards; combined display reads never replace the original write proof. */
@RunWith(AndroidJUnit4::class)
class CompletionActionSnapshotTest : NextObjectEditorFixture() {
    private fun repository(): HabitRepository {
        val counts = CountHistoryReader(db, tokens, sessions)
        val checks = CheckHistoryReader(db, tokens, sessions)
        return HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
            nextObjectEditor = editor, countHistoryReader = counts, checkHistoryReader = checks,
            recurringHabitReader = RecurringHabitReader(db, tokens, sessions, editor, counts, checks),
            widgetFactReader = WidgetFactReader(db, tokens, sessions, preferences, counts, checks))
    }

    private suspend fun configure(check: Boolean = false, active: Boolean = true, finite: Boolean = false) {
        habit = habit.copy(habitType = if (check) HabitType.CHECK_IN else HabitType.COUNTING,
            targetValue = if (check) 1 else 10, targetCycles = if (finite) 1 else null,
            failMode = if (finite) FailMode.STRICT else FailMode.LOOSE, isActive = active)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }

    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }

    private suspend fun linkedMetrics(): MetricRepository {
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitMetricLinkDao().insert(com.dayforge.data.local.entity.HabitMetricLinkEntity(
                habitId = habit.id, habitUuid = habit.uuid, metricId = metric.id, metricUuid = metric.uuid,
                promptOnComplete = true))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        return MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao(),
            nextObjectEditor = editor, widgetFactReader = WidgetFactReader(db, tokens, sessions, preferences,
                CountHistoryReader(db, tokens, sessions)))
    }

    private suspend fun record(metrics: MetricRepository, view: WidgetFactSnapshot, source: Boolean = true) {
        val target = metrics.getMetricForEditing(metric.id)
        metrics.recordValues(listOf(MetricValueDraft(metric.id, 3.0)), authority = target.authority,
            expectedMetrics = listOf(requireNotNull(target.value)), promptHabit = view.habit, promptFact = view.claim,
            promptCompletionSource = source)
    }

    @Test fun checkAndUndoUseOneCurrentRowAndHistoryWithoutMutatingDisplayReads() = runBlocking {
        register(); configure(check = true)
        val repo = repository()
        val captured = repo.prepareCompletion(habit.id)
        assertNotNull(captured.evidence)
        assertFalse(captured.evidence!!.followUp.completed)
        val beforeRead = proof()
        assertEquals(captured.edit.value, repo.prepareCompletion(habit.id).evidence!!.habit)
        assertEquals(beforeRead, proof())
        repo.logCompletion(app, habit.id, authority = captured.edit.authority, expectedHabitUuid = habit.uuid)
        val after = repo.readCompletionAfter(captured, allowActivation = true)
        assertTrue(after.followUp.completed); assertEquals(1, after.followUp.targetProgress)
        assertEquals(after.habit, after.followUp.habit)
        assertEquals(NextStructureMapper.writePlan(after.habit), after.followUp.claim.plan)
        val undo = repo.prepareCompletion(habit.id)
        repo.undoCompletion(app, undo.evidence!!.checkHistory!!.todayCompletions.single().id,
            authority = undo.edit.authority)
        val undone = repo.readCompletionAfter(undo, allowActivation = false)
        assertFalse(undone.followUp.completed); assertEquals(0, undone.followUp.targetProgress)
        assertEquals(0, count("completions"))
    }

    @Test fun incrementDoesNotReadHistoryBeforeWriteAndOnlyOwnActivationIsAcceptedAfterwards() = runBlocking {
        register(); configure(active = false)
        val repo = repository()
        val captured = repo.prepareCompletion(habit.id, includeHistory = false)
        assertNull(captured.evidence)
        assertTrue(requireNotNull(captured.edit.authority).completionDeviceCaptured)
        repo.logCompletion(app, habit.id, value = 4, authority = captured.edit.authority, expectedHabitUuid = habit.uuid)
        rejected { repo.readCompletionAfter(captured, allowActivation = false) }
        val after = repo.readCompletionAfter(captured, allowActivation = true)
        assertTrue(after.habit.isActive); assertEquals(4L, after.countHistory!!.todayQuantity)
        assertFalse(after.followUp.completed)
        db.habitDao().update(requireNotNull(repo.getHabitById(habit.id)).copy(targetValue = 11))
        rejected { repo.readCompletionAfter(captured, allowActivation = true) }
        assertEquals(1, count("completions"))
    }

    @Test fun originalAccountReauthenticationDeviceAndUuidCannotBeReplaced() = runBlocking {
        register(); configure(check = true)
        val repo = repository()
        val original = repo.prepareCompletion(habit.id)
        register(device = id(40))
        assertEquals("COMPLETION_SCOPE_CHANGED", rejected { repo.readCompletionAfter(original, false) }.message)
        val device = repo.prepareCompletion(habit.id)
        tokens.saveLoginSession("synthetic-renewed", "synthetic-refresh", "member", id(1), false)
        register(device = id(40))
        assertEquals("COMPLETION_SCOPE_CHANGED", rejected { repo.readCompletionAfter(device, false) }.message)
        val sameId = repo.prepareCompletion(habit.id)
        db.habitDao().update(habit.copy(uuid = id(91)))
        assertEquals("COMPLETION_SCOPE_CHANGED", rejected { repo.readCompletionAfter(sameId, false) }.message)
        assertEquals(0, count("completions"))
    }

    @Test fun missingCapturedDeviceIsNotTreatedAsAnUnregisteredOriginalDevice() = runBlocking {
        configure(check = true)
        val repo = repository()
        val captured = repo.prepareCompletion(habit.id)
        assertNull(captured.edit.authority!!.capturedDeviceId)
        repo.readCompletionAfter(captured, false) // Explicitly captured null is legitimate offline identity.
        val old = captured.edit.authority!!
        val uncaptured = ObjectEditAuthority(old.session, old.uuid, old.type, old.original, old.rounds)
        val absent = CompletionActionSnapshot(ObjectEditSnapshot(captured.edit.value, uncaptured))
        assertEquals("COMPLETION_DEVICE_NOT_CAPTURED", rejected { repo.readCompletionAfter(absent, false) }.message)
    }

    @Test fun goalContinueAndCompleteReturnOnlyTheirOriginalFactSuccessorAndInvalidateOldPrompt() = runBlocking {
        register(); configure(check = true, finite = true)
        val repo = repository()
        val metrics = linkedMetrics()
        val captured = repo.prepareCompletion(habit.id)
        repo.logCompletion(app, habit.id, authority = captured.edit.authority, expectedHabitUuid = habit.uuid)
        val view = repo.readCompletionAfter(captured, true).followUp
        val continued = repo.applyCompletionGoal(app, view, complete = false)
        assertEquals(FailMode.LOOSE, continued.habit.failMode)
        assertEquals(view.claim.completionUuid, continued.claim.completionUuid)
        assertEquals(view.claim.roundHead, continued.claim.roundHead)
        var published = false
        rejected { repo.publishCompletion(view) { fail("Old plan cannot publish") } }
        repo.publishCompletion(continued) { published = true }
        assertTrue(published)
        record(metrics, continued)
        val completed = repo.applyCompletionGoal(app, continued, complete = true)
        assertFalse(completed.habit.isActive)
        assertEquals(view.claim.completionUuid, completed.claim.completionUuid)
        repo.publishCompletion(completed) { assertEquals(1, completed.targetProgress) }
        record(metrics, completed)
        assertEquals(1, count("completions"))
        assertEquals(2, count("metric_logs"))
        val before = proof()
        rejected { repo.applyCompletionGoal(app, view, complete = true) }
        assertEquals(before, proof())
    }

    @Test fun lateGoalSourceFaultRollsBackPlanAndOriginalQueueWhileConsumerClearsOnAccountChange() = runBlocking {
        register(); configure(check = true, finite = true)
        val repo = repository()
        val captured = repo.prepareCompletion(habit.id)
        repo.logCompletion(app, habit.id, authority = captured.edit.authority, expectedHabitUuid = habit.uuid)
        val view = repo.readCompletionAfter(captured, true).followUp
        val old = db.syncOutboxDao().getAll().single()
        val before = proof()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("""CREATE TRIGGER completion_goal_fault AFTER INSERT ON next_request_origins
            WHEN NEW.requestId <> '${old.operationId}' BEGIN UPDATE sync_outbox
            SET entityUuid='${id(991)}' WHERE id=${old.id}; END""")
        try { rejected { repo.applyCompletionGoal(app, view, complete = true) } }
        finally { sql.execSQL("DROP TRIGGER completion_goal_fault") }
        assertEquals(before, proof()); assertTrue(repo.getHabitById(habit.id)!!.isActive)
        var wasBlocked = false
        val listener = object : PersonalDataDisplayMemory.Listener {
            override fun transition(blocked: Boolean) { if (blocked) wasBlocked = true }
        }
        repo.registerCompletionConsumer(listener)
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(998), false)
        assertTrue(wasBlocked)
        rejected { repo.publishCompletion(view) { fail("Old account cannot publish") } }
        assertEquals(before, proof())
    }

    @Test fun newlyAcceptedRoundCannotReplaceTheOriginalPostCommitContext() = runBlocking {
        register(); configure(check = true, finite = true)
        val (http, _) = channel()
        val merger = NextSyncMergeStore(db, tokens, sessions,
            OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)),
            NextTimerRequestStore(db, tokens, sessions, sender(http)))
        val initial = ChallengeRoundRecord(initialChallengeRoundHead(habit.uuid), null, null, null)
        val timer = ChallengeRoundRecord(initialChallengeRoundHead(timerHabit.uuid), null, null, null)
        val points = listOf(ChallengeCheckpoint(initial.head, listOf(initial)), ChallengeCheckpoint(timer.head, listOf(timer)))
        fun canonical(row: com.dayforge.data.local.entity.HabitEntity) = SyncV2Change(0, "plan_node", row.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writePlan(row) + mapOf("public_id" to JsonPrimitive(row.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
                "deleted_at" to JsonNull)), time)
        val canonicalMetric = SyncV2Change(0, "metric", metric.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writeMetric(metric) + mapOf("public_id" to JsonPrimitive(metric.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
                "deleted_at" to JsonNull)), time)
        merger.bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(canonical(habit), canonical(timerHabit), canonicalMetric),
            20, time, emptyList(), 1, points, emptyList()))
        val repo = repository()
        val captured = repo.prepareCompletion(habit.id)
        assertEquals(initial.head, captured.evidence!!.followUp.claim.roundHead)
        val next = ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, id(301), 1), id(4), id(302),
            ChallengeRestartIntent(habit.uuid, id(301), initial.head.roundUuid, 0, 1))
        db.withTransaction {
            NextChallengeStore(db).acknowledgeInTransaction(access(), ChallengeMetadata(1,
                listOf(ChallengeCheckpoint(next.head, listOf(initial, next)), points[1]), emptyList()))
        }
        val before = proof()
        assertEquals("COMPLETION_ROUND_CHANGED", rejected { repo.readCompletionAfter(captured, false) }.message)
        rejected { repo.logCompletion(app, habit.id, authority = captured.edit.authority,
            expectedHabitUuid = habit.uuid, checkIdentity = requireNotNull(captured.evidence!!.checkHistory!!.actionIdentity)) }
        assertEquals(before, proof())
    }

    @Test fun displayedCheckIdentityIsRevalidatedInsideAppendAndUndoTransactions() = runBlocking {
        register(); configure(check = true)
        val repo = repository()
        val captured = repo.prepareCompletion(habit.id)
        val identity = requireNotNull(captured.evidence!!.checkHistory!!.actionIdentity)
        val fact = repo.logCompletion(app, habit.id, authority = captured.edit.authority,
            expectedHabitUuid = habit.uuid, checkIdentity = identity)
        assertTrue(fact > 0)
        val undo = repo.prepareCompletion(habit.id)
        val undoIdentity = requireNotNull(undo.evidence!!.checkHistory!!.actionIdentity)
        register(device = id(80))
        val beforeDevice = proof()
        assertEquals("CHECK_SESSION_CHANGED", rejected {
            repo.undoCompletion(app, fact, authority = undo.edit.authority, checkIdentity = undoIdentity)
        }.message)
        assertEquals(beforeDevice, proof())
        val original = repo.prepareCompletion(habit.id)
        val originalIdentity = requireNotNull(original.evidence!!.checkHistory!!.actionIdentity)
        db.habitDao().update(requireNotNull(repo.getHabitById(habit.id)).copy(targetCycles = 3))
        val beforePlan = proof()
        rejected { repo.logCompletion(app, habit.id, checkIdentity = originalIdentity) }
        assertEquals(beforePlan, proof())
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(81), false)
        rejected { repo.undoCompletion(app, fact, authority = original.edit.authority, checkIdentity = originalIdentity) }
        assertEquals(beforePlan, proof()); assertEquals(1, count("completions"))
    }

    @Test fun previousDayCountSourceSurvivesTodayReadGoalAndMetricWithoutMovingItsBusinessDate() = runBlocking {
        register(); configure(finite = true)
        val repo = repository()
        val metrics = linkedMetrics()
        val captured = repo.prepareCompletion(habit.id, includeHistory = false)
        val zone = java.time.ZoneId.systemDefault()
        val day = com.dayforge.util.DateTimeUtils.today().minusDays(1)
        val timestamp = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val fact = com.dayforge.data.local.entity.CompletionEntity(habitId = habit.id,
            habitUuid = habit.uuid, date = day.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = timestamp, value = 10,
            recordedTimezone = zone.id, recordedLocalDate = day.toString())
        val recorded = producer().write(local()) {
            NextCountDayStore(db).capture(habit, fact)
            db.completionDao().insert(fact)
        }
        val after = repo.readCompletionAfter(captured, allowActivation = false, recordedCompletionId = recorded)
        assertEquals(com.dayforge.util.DateTimeUtils.today(), after.countHistory!!.today)
        assertEquals(0L, after.countHistory!!.todayQuantity)
        assertFalse(after.countHistory!!.completedToday)
        val original = after.followUp
        assertEquals(fact.uuid, original.claim.completionUuid)
        assertEquals(day.toString(), original.claim.date); assertEquals(zone.id, original.claim.timezone)
        assertTrue(original.completed); assertEquals(10L, original.count!!.todayQuantity)
        val before = proof()
        rejected { repo.publishCompletion(original, requireDay = true) { fail("Current-day widget-style publication must reject") } }
        var published = false
        repo.publishCompletion(original, requireDay = false) { published = true }
        assertTrue(published); assertEquals(before, proof())
        val goal = repo.applyCompletionGoal(app, original, complete = false)
        record(metrics, goal)
        assertEquals(1, count("metric_logs"))
        assertEquals(day.toString(), db.completionDao().getCompletionById(recorded)!!.recordedLocalDate)
        assertEquals(timestamp, db.completionDao().getCompletionById(recorded)!!.actualCompletedAt)
    }

    @Test fun laterSameDayFactDoesNotReplaceOriginalSourceButOriginalSourceUndoRevokesIt() = runBlocking {
        register(); configure(finite = true)
        val repo = repository()
        val metrics = linkedMetrics()
        val captured = repo.prepareCompletion(habit.id, includeHistory = false)
        val first = repo.logCompletion(app, habit.id, value = 10, authority = captured.edit.authority)
        val original = repo.readCompletionAfter(captured, true, recordedCompletionId = first).followUp
        repo.logCompletion(app, habit.id, value = 1)
        val widget = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
        assertEquals("FACT_WIDGET_FACT_CHANGED", rejected { widget.requireCurrent(original.claim) }.message)
        rejected { record(metrics, original, source = false) } // The unchanged widget path still requires the latest fact.
        var published = false
        repo.publishCompletion(original) { published = true }
        assertTrue(published)
        val continued = repo.applyCompletionGoal(app, original, complete = false)
        record(metrics, continued)
        assertEquals(1, count("metric_logs"))
        repo.undoCompletion(app, first)
        val before = proof()
        rejected { repo.publishCompletion(continued) { fail("Undone source cannot publish") } }
        rejected { record(metrics, continued) }
        assertEquals(before, proof()); assertEquals(1, count("completions")); assertEquals(1, count("metric_logs"))
    }
}
