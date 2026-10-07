package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.ui.components.MetricInputState
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.dashboard.DashboardHabitListBuilder
import com.dayforge.ui.screens.habitdetail.HabitDetailViewModel
import com.dayforge.ui.screens.nested.NestedHabitTreeBuilder
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual App services/readers and durable follow-up, without activating the v4 navigator. */
@RunWith(AndroidJUnit4::class)
class OneTimeWorkflowTest : NextObjectEditorFixture() {
    private suspend fun create(repo: HabitRepository, parent: String? = null, clock: Instant? = null): Long = repo.createHabit(
        "Retained once", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
        parentHabitId = parent, failMode = FailMode.LOOSE, selectedMetricIds = setOf(metric.id),
        appearance = ObjectAppearance(IconReference.Role("task.reading"), "#123456", "object"),
        completionPolicy = "one_and_done", creationAuthority = if (clock == null) creator.capture()
            else ObjectCreationAuthority(local()) { clock to ZoneId.of("Asia/Shanghai") })

    private fun coordinator(repo: HabitRepository, once: OneTimeRepository) =
        LinkedMetricCoordinator(app, preferences, creatingMetrics(), repo, once)

    @Test fun completionCoordinatorRetainsTaskAndCrossDayUndoKeepsImmutableHistoryAndArchiveFlag() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once)
        val task = create(repo, clock = Instant.now().minusSeconds(172_801))
        val row = db.habitDao().getHabitById(task)!!
        val old = Instant.now().minusSeconds(172_800).toEpochMilli()
        val captured = OneTimeLocalIntentStore(db, tokens, sessions, preferences).read(row.uuid)
        OneTimeLocalIntentStore(db, tokens, sessions, preferences).append(captured.session,
            OneTimeLocalCommand(row.uuid, PendingOneTimeIntent(id(171), OneTimeIntent(id(170), "complete", 0, null, null)),
                old, "Asia/Shanghai"))
        val completion = db.completionDao().getCompletionByUuid(id(170))!!
        repo.updateIsActive(task, false, app)
        repo.undoCompletion(app, completion.id)
        assertFalse(repo.getOneTimeStatus(task).completed)
        assertEquals(completion, db.completionDao().getCompletionByUuid(completion.uuid))
        assertFalse(db.habitDao().getHabitById(task)!!.isActive)
        val undo = db.completionDao().getByHabitOnce(task).single { it.oneTimeAction == "undo" }
        assertEquals(completion.uuid, undo.oneTimeRevertsEventUuid)
        assertNotEquals(completion.recordedLocalDate, undo.recordedLocalDate)
        val service = HabitCompletionCoordinator(app, CheckInService(repo, db.completionDao(), db.timeLogDao()), repo, creatingMetrics())
        val outcome = service.checkIn(task, true) { row }
        assertFalse(outcome.shouldDeleteTemporaryTask); assertNull(outcome.goalProgress)
        assertEquals(task, outcome.metricPromptHabit!!.id)
        assertTrue(repo.getOneTimeStatus(task).completed)
        assertFalse(db.habitDao().getHabitById(task)!!.isActive)
        assertEquals(3, db.completionDao().getByHabitOnce(task).size)
        storage.reopen()
        assertTrue(onceHabits(onceRepository()).getOneTimeStatus(task).completed)
        assertEquals(3, db.completionDao().getByHabitOnce(task).size)
    }

    @Test fun dashboardNestedAndActualDetailReadLifetimeStateWithoutDailyProgressOrStreak() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once)
        val goal = HabitDraft(id = id(180), name = "Container", habitType = HabitType.GOAL,
            appearance = ObjectAppearance(IconReference.Role("goal.custom"), "#123456", "object"))
        repo.createGoal(goal, emptyList(), creationAuthority = creator.capture())
        val task = create(repo, goal.id)
        repo.logCompletion(app, task)
        val row = db.habitDao().getHabitById(task)!!
        val failure = FailureChecker(db.completionDao(), db.timeLogDao())
        val calculator = HabitStatusCalculator(failure, db.completionDao(), db.timeLogDao(), once)
        val stats = calculator.calculate(row)
        assertTrue(stats.completedForDisplay); assertFalse(stats.completedToday); assertFalse(stats.shouldCountToday)
        assertEquals(0, stats.todayCount); assertEquals(0, stats.currentStreak); assertEquals(0, stats.targetProgress)
        val builder = DashboardHabitListBuilder(calculator)
        assertTrue(builder.build(listOf(row), db.completionDao().getByHabitOnce(task), FilterMode.CHECKABLE,
            java.time.ZonedDateTime.now(), emptySet()).isEmpty())
        assertEquals(1, builder.build(listOf(row), emptyList(), FilterMode.CHECKABLE,
            java.time.ZonedDateTime.now(), setOf(task)).size)
        val tree = NestedHabitTreeBuilder(db.habitDao(), db.completionDao(), db.timeLogDao(), failure, once)
            .build(listOf(db.habitDao().getHabitByUuid(goal.id)!!), db.completionDao().getByHabitOnce(task)).single()
        assertTrue(tree.children.single().completedForDisplay); assertEquals(0, tree.totalChildren)
        val detail = withContext(Dispatchers.Main) { own(HabitDetailViewModel(app, repo, preferences,
            db.timeLogDao(), db.completionDao(), db.habitDao(), db.habitMetricLinkDao(), db.metricDao(), db.metricLogDao())) }
        withContext(Dispatchers.Main) { detail.loadHabit(task) }
        val loaded = withTimeout(5000) { detail.uiState.first { !it.isLoading && it.oneTimeStatus != null } }
        assertTrue(loaded.oneTimeStatus!!.completed); assertNull(loaded.streakStats); assertNotNull(loaded.lastCompletionId)
        withContext(Dispatchers.Main) { detail.undoCompletion() }
        withTimeout(5000) { detail.uiState.first { it.oneTimeStatus?.completed == false } }
        assertEquals(2, db.completionDao().getByHabitOnce(task).size)
    }

    @Test fun actualPromptPersistsTextAcrossCloseAndColdRestartAndCreatesOriginalMetricRequestAtBirth() = runBlocking<Unit> {
        register()
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        repo.logCompletion(app, task)
        val prompt = coordinator(repo, once)
        prompt.showPromptIfNeeded(task, "ignored live name")
        val event = prompt.postCheckInState.value!!.oneTimePrompt!!.eventUuid
        prompt.savePromptDraft(event, listOf(MetricInputState(metric.id, metric.name, "kg", 3, "12.250", "saved note")))
        prompt.closePrompt()
        assertEquals(setOf(task), once.pendingHabitIds.first())
        storage.reopen()
        val reopened = coordinator(onceHabits(onceRepository()), onceRepository())
        reopened.showPromptIfNeeded(task, "")
        assertEquals("12.250", reopened.postCheckInState.value!!.oneTimePrompt!!.entries.single().input)
        withContext(Dispatchers.Main) { assertTrue(reopened.recordMetricValues(task, listOf(MetricValueInput(metric.id, 12.25, "saved note")))) }
        val queue = db.syncOutboxDao().getAll().single { it.recordType == "metric_log" }
        val original = originalIntent(queue)
        assertEquals(5, original.protocol); assertEquals(id(1), original.accountId)
        val fact = db.metricLogDao().getLogByUuid(queue.entityUuid)!!
        assertEquals(12.25, fact.value, 0.0); assertEquals("saved note", fact.note)
        withContext(Dispatchers.Main) { assertTrue(reopened.recordMetricValues(task, listOf(MetricValueInput(metric.id, 12.25, "saved note")))) }
        assertEquals(1, db.metricLogDao().countAll())
        val (http, _) = channel { successReply(it) }
        assertNotNull(sender(http).sendAndAcceptOperation(access(), queue.operationId))
        assertEquals(1, db.metricLogDao().countAll()); assertNull(db.syncOutboxDao().getByOperationId(queue.operationId))
        assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, queue.operationId))
    }

    @Test fun originFailureRollsBackMetricFactAndRetryKeepsFrozenDraftInstantAndIdentities() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        repo.logCompletion(app, task)
        var prompt = once.prompt(task)!!
        prompt = once.saveDraft(prompt, mapOf(metric.id to ("3.5" to "same")))
        val before = db.syncOutboxDao().getAll()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_metric_origin BEFORE INSERT ON next_request_origins
            WHEN NEW.requestId = '${prompt.snapshot.entries.single().operationId}'
            BEGIN SELECT RAISE(ABORT, 'origin unavailable'); END""")
        assertTrue(rejected { once.submit(prompt) }.message.orEmpty().contains("origin unavailable"))
        val frozen = db.completionFollowUpDao().prompt(prompt.eventUuid)!!
        assertNotNull(frozen.recordedAtMillis); assertEquals("pending", frozen.state)
        assertEquals(0, db.metricLogDao().countAll()); assertEquals(before, db.syncOutboxDao().getAll())
        storage.reopen()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_metric_origin")
        val reopened = onceRepository()
        val retry = reopened.prompt(task)!!
        val noChange = reopened.saveDraft(retry, mapOf(metric.id to ("3.5" to "same")))
        assertEquals(frozen, noChange.snapshot.row)
        reopened.submit(noChange)
        val fact = db.metricLogDao().getLogByUuid(prompt.snapshot.entries.single().observationUuid)!!
        assertEquals(frozen.recordedAtMillis, fact.date)
        assertEquals(1, db.metricLogDao().countAll())
    }

    @Test fun reauthenticationClearsVisiblePromptAndStaleOwnerCannotWriteOrPublish() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        repo.logCompletion(app, task)
        val ui = coordinator(repo, once)
        ui.showPromptIfNeeded(task, "")
        val old = ui.postCheckInState.value!!.oneTimePrompt!!
        tokens.saveLoginSession("new-auth", "new-refresh", "member", id(1), false)
        assertNull(ui.postCheckInState.value)
        rejected { once.saveDraft(old, mapOf(metric.id to ("9" to ""))) }
        rejected { once.publish(old) { fail("Stale UI publication") } }
        assertEquals(0, db.metricLogDao().countAll())
    }

    @Test fun knownFactRevocationAndRejectedOnceSuffixCannotAppendAnotherCompletion() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        val complete = repo.logCompletion(app, task)
        val row = db.completionDao().getCompletionById(complete)!!
        val queue = db.syncOutboxDao().getAll().single { it.entityUuid == row.uuid }
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET deadLetteredAt=1 WHERE id=${queue.id}")
        assertTrue(repo.getOneTimeStatus(task).blocked)
        assertFalse(repo.getOneTimeStatus(task).canChange)
        rejected { repo.undoCompletion(app, complete) }
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
        register(permissions = setOf("sync.read"))
        rejected { repo.logCompletion(app, task) }
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
    }

    @Test fun staleDisplayedActionCannotUndoNewCompletionOrSurviveAuthenticationReplacement() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        val initial = repo.getOneTimeStatus(task)
        val service = CheckInService(repo, db.completionDao(), db.timeLogDao())
        assertTrue((service.toggleCheckIn(app, task, oneTimeAuthority = initial.authority) as CheckInResult.Success).completed)
        rejected { service.toggleCheckIn(app, task, oneTimeAuthority = initial.authority) }
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
        val completed = repo.getOneTimeStatus(task)
        tokens.saveLoginSession("new", "new-refresh", "member", id(1), false)
        rejected { repo.undoCompletion(app, completed.completionId!!, completed.authority) }
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
    }

    @Test fun missingMetricTargetStillAllowsExplicitPromptSkipWithoutForgingAnObservation() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        repo.logCompletion(app, task)
        var prompt = once.prompt(task)!!
        prompt = once.saveDraft(prompt, mapOf(metric.id to ("8" to "")))
        db.metricDao().delete(metric)
        val missing = once.prompt(task)!!
        assertFalse(missing.entries.single().available)
        assertTrue(missing.entries.single().metricId < 0)
        rejected { once.submit(missing) }
        once.dismiss(missing)
        assertNull(once.prompt(task)); assertEquals(0, db.metricLogDao().countAll())
        assertTrue(repo.getOneTimeStatus(task).completed)
    }
}
