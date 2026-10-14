package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.CheckInService
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.widget.checkin.actionIntent
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real file Room, account preferences, original requests and existing production writers. */
@RunWith(AndroidJUnit4::class)
class WidgetFactActionTest : NextObjectEditorFixture() {
    private fun reader() = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
    private fun repo() = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
        nextObjectEditor = editor, nextObjectCreator = creator, oneTimeRepository = onceRepository(),
        countHistoryReader = CountHistoryReader(db, tokens, sessions), widgetFactReader = reader())
    private fun service() = CheckInService(repo(), db.completionDao(), db.timeLogDao())
    private fun metricRepo() = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao(),
        nextObjectEditor = editor, nextObjectCreator = creator, widgetFactReader = reader())
    private fun coordinator() = LinkedMetricCoordinator(app, preferences, metricRepo(), repo(), onceRepository(), factReader = reader())
    private suspend fun claim(id: Long = habit.id) = reader().read(requireNotNull(repo().getHabitById(id))).claim
    private suspend fun checkHabit(targetCycles: Int? = null): HabitEntity {
        val row = habit.copy(id = 0, uuid = id(200), name = "Widget check", habitType = HabitType.CHECK_IN,
            targetValue = 1, targetCycles = targetCycles)
        return row.copy(id = producer().write(local()) { db.habitDao().insert(row) })
    }
    private suspend fun onceHabit(): Long = repo().createHabit("Widget once", "", HabitType.CHECK_IN, 0, "#123456",
        HabitSchedule.Once(), failMode = FailMode.LOOSE, selectedMetricIds = setOf(metric.id), completionPolicy = "one_and_done",
        appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"), creationAuthority = creator.capture())

    @Test fun checkInAndUndoAreExplicitCasAcrossColdReopenAndDoNotRetoggle() = runBlocking<Unit> {
        val row = checkHabit()
        val before = claim(row.id)
        assertTrue(service().widgetAction(app, before, "toggle").completed)
        val stored = db.completionDao().getByHabitOnce(row.id).single()
        val queues = db.syncOutboxDao().getAll()
        rejected { service().widgetAction(app, before, "toggle") }
        assertEquals(queues, db.syncOutboxDao().getAll())
        val undo = claim(row.id)
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons)
        assertFalse(service().widgetAction(app, WidgetFactClaim.decode(undo.encode()), "toggle").completed)
        rejected { service().widgetAction(app, undo, "toggle") }
        assertTrue(db.completionDao().getByHabitOnce(row.id).isEmpty())
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, queues.single { it.entityUuid == stored.uuid }.operationId))
        assertFalse(before.actionIntent(app, "toggle").filterEquals(undo.actionIntent(app, "toggle")))
    }

    @Test fun countClicksCommuteButCountdownBoundaryAndUndoAreCheckedInsideCommit() = runBlocking<Unit> {
        producer().write(local()) { db.habitDao().update(habit.copy(targetValue = 2, isCountdown = true)) }
        val original = claim()
        coroutineScope { listOf(async { service().widgetAction(app, original, "increment") },
            async { service().widgetAction(app, original, "increment") }).forEach { it.await() } }
        val rows = db.completionDao().getByHabitOnce(habit.id)
        assertEquals(2, rows.sumOf { it.value })
        val queued = db.syncOutboxDao().getAll()
        rejected { service().widgetAction(app, original, "increment") }
        assertEquals(queued, db.syncOutboxDao().getAll())
        val undo = claim()
        service().widgetAction(app, undo, "undo")
        rejected { service().widgetAction(app, undo, "undo") }
        service().widgetAction(app, original, "increment")
        assertEquals(2, db.completionDao().getByHabitOnce(habit.id).sumOf { it.value })
        assertEquals(CountDayPolicy(2, true).toJson(), claim().countPolicy)
    }

    @Test fun changedPlanRequiresRefreshAndFrozenDaySurvivesUndoAllAndNewClicks() = runBlocking<Unit> {
        producer().write(local()) { db.habitDao().update(habit.copy(targetValue = 1)) }
        val original = claim()
        service().widgetAction(app, original, "increment")
        service().widgetAction(app, original, "increment") // Positive count still continues past its target.
        val oldDisplay = claim()
        producer().write(local()) { db.habitDao().update(requireNotNull(repo().getHabitById(habit.id)).copy(targetValue = 20, isCountdown = true)) }
        rejected { service().widgetAction(app, oldDisplay, "increment") }
        assertEquals(CountDayPolicy(1, false).toJson(), claim().countPolicy)
        repeat(2) { service().widgetAction(app, claim(), "undo") }
        assertEquals(CountDayPolicy(1, false).toJson(), claim().countPolicy)
        assertTrue(service().widgetAction(app, claim(), "increment").completed)
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).sumOf { it.value })
    }

    @Test fun staleAccountReplicaDeviceDayAndSameIdReplacementNeverWrite() = runBlocking<Unit> {
        register()
        val before = claim()
        val initial = db.syncOutboxDao().getAll()
        for (stale in listOf(before.copy(date = LocalDate.parse(before.date).minusDays(1).toString()),
            before.copy(timezone = if (before.timezone == "UTC") "Asia/Shanghai" else "UTC"),
            before.copy(habitUuid = id(240)), before.copy(deviceId = id(241)), before.copy(syncEpoch = id(242)))) {
            rejected { service().widgetAction(app, stale, "increment") }
        }
        producer().write(local()) { db.habitDao().update(habit.copy(uuid = id(243))) }
        rejected { service().widgetAction(app, before, "increment") }
        val afterReplace = db.syncOutboxDao().getAll()
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(244), false)
        rejected { service().widgetAction(app, before, "increment") }
        assertTrue(initial.size <= afterReplace.size)
        assertEquals(afterReplace, db.syncOutboxDao().getAll())
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
    }

    @Test fun onceClaimDoesNotRestoreAnOpaqueAuthorityAndKeepsDurablePromptAndUndo() = runBlocking<Unit> {
        val task = onceHabit()
        val before = claim(task)
        assertFalse(before.encode().contains("ActionAuthority"))
        service().widgetAction(app, WidgetFactClaim.decode(before.encode()), "toggle")
        val completed = claim(task)
        assertEquals(1, completed.oneTimeState!!.version)
        rejected { service().widgetAction(app, before, "toggle") }
        val coordinator = coordinator()
        coordinator.showWidgetFactPrompt(completed)
        val prompt = coordinator.postCheckInState.value!!.oneTimePrompt!!
        assertEquals(completed.completionUuid, prompt.eventUuid)
        val queued = db.syncOutboxDao().getAll()
        service().widgetAction(app, completed, "toggle")
        rejected { service().widgetAction(app, completed, "toggle") }
        assertFalse(repo().getOneTimeStatus(task).completed)
        assertEquals(2, db.completionDao().getByHabitOnce(task).size)
        assertNotNull(repo().getHabitById(task))
        assertTrue(db.syncOutboxDao().getAll().containsAll(queued))
        assertEquals(2, claim(task).oneTimeState!!.version)
    }

    @Test fun ordinaryMetricPromptBindsOriginalMetadataLinksFactsAndExactRetry() = runBlocking<Unit> {
        val row = checkHabit()
        metricRepo().linkHabits(metric, setOf(row.id))
        service().widgetAction(app, claim(row.id), "toggle")
        val after = claim(row.id)
        val coordinator = coordinator()
        coordinator.showWidgetFactPrompt(after)
        val proof = coordinator.postCheckInState.value!!.factPrompt!!
        val values = listOf(MetricValueInput(metric.id, 12.25, "widget"))
        producer().write(local()) { db.metricDao().update(metric.copy(unit = "g")) }
        withContext(Dispatchers.Main) { assertFalse(coordinator.recordMetricValues(row.id, values, expectedFactPrompt = proof)) }
        assertEquals(0, db.metricLogDao().countAll())
        producer().write(local()) { db.metricDao().update(metric) }
        withContext(Dispatchers.Main) {
            assertTrue(coordinator.recordMetricValues(row.id, values, expectedFactPrompt = proof))
            assertTrue(coordinator.recordMetricValues(row.id, values, expectedFactPrompt = proof))
            assertFalse(coordinator.recordMetricValues(row.id, values.map { it.copy(value = 30.0) }, expectedFactPrompt = proof))
        }
        assertEquals(1, db.metricLogDao().countAll())
        val observation = db.syncOutboxDao().getAll().single { it.recordType == "metric_log" }
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, observation.operationId))
        coordinator.showWidgetFactPrompt(after)
        val second = coordinator.postCheckInState.value!!.factPrompt!!
        service().widgetAction(app, after, "toggle")
        withContext(Dispatchers.Main) { assertFalse(coordinator.recordMetricValues(row.id, values, expectedFactPrompt = second)) }
        assertEquals(1, db.metricLogDao().countAll())
    }

    @Test fun goalConfirmChecksOriginalFactAndPlanAndNeverArchivesAnotherAccount() = runBlocking<Unit> {
        val row = checkHabit(targetCycles = 1)
        assertTrue(service().widgetAction(app, claim(row.id), "toggle").goalReached)
        val before = claim(row.id)
        repo().applyWidgetGoal(app, before, true)
        assertFalse(repo().getHabitById(row.id)!!.isActive)
        rejected { repo().applyWidgetGoal(app, before, true) }
        val archived = claim(row.id)
        val queues = db.syncOutboxDao().getAll()
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(245), false)
        rejected { repo().applyWidgetGoal(app, archived, false) }
        assertEquals(queues, db.syncOutboxDao().getAll())
        assertFalse(repo().getHabitById(row.id)!!.isActive)
    }

    @Test fun producerFailureRollsBackFactDayAndOriginAndStrictIntentRejectsMalformedClaims() = runBlocking<Unit> {
        val before = claim()
        val queued = db.syncOutboxDao().getAll()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_widget_origin BEFORE INSERT ON next_request_origins BEGIN SELECT RAISE(ABORT,'widget origin'); END")
        try { rejected { service().widgetAction(app, before, "increment") } }
        finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_widget_origin") }
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
        assertTrue(db.countDayDao().forHabit(habit.id).isEmpty())
        assertEquals(queued, db.syncOutboxDao().getAll())
        assertEquals(before, WidgetFactClaim.read(before.actionIntent(app, "increment")))
        val json = before.encode()
        rejected { WidgetFactClaim.decode(json.replaceFirst("\"habitId\":${habit.id}", "\"habitId\":\"${habit.id}\"")) }
        rejected { WidgetFactClaim.decode(json.replaceFirst("\"habitId\":", "\"habitId\":1,\"habitId\":")) }
        rejected { WidgetFactClaim.decode(json.dropLast(1) + ",\"unknown\":true}") }
        rejected { WidgetFactClaim.decode(" ".repeat(65_537)) }
        service().widgetAction(app, before, "increment")
        val fact = db.completionDao().getByHabitOnce(habit.id).single()
        assertEquals(ZoneId.systemDefault().id, fact.recordedTimezone)
        val op = db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid }
        assertEquals(CountDayPolicy(10, false).toJson(), Json.parseToJsonElement(
            db.nextRequestDao().origin(NEXT_OPERATION, op.operationId)!!.intentJson).jsonObject.getValue("payload").jsonObject["count_policy"])
    }
}
