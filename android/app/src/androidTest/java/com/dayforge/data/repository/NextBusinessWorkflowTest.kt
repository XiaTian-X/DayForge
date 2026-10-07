package com.dayforge.data.repository

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.FailMode
import com.dayforge.ui.screens.habitdetail.HabitDetailViewModel
import com.dayforge.ui.screens.metricdetail.MetricDetailViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real formal repositories/ViewModels, file Room, DataStore and original HTTP acceptance. */
@RunWith(AndroidJUnit4::class)
class NextBusinessWorkflowTest : NextObjectEditorFixture() {
    private fun reopenEditors() {
        storage.reopen()
        // A real cold process reconstructs repositories; never keep an editor on the closed DB.
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
    }
    @Test fun actualHabitDetailRecordsAndUndoesWithOriginalFactRequestsAndColdReopen() = runBlocking<Unit> {
        register()
        val repo = creatingHabits()
        val vm = withContext(Dispatchers.Main) { own(HabitDetailViewModel(app, repo, preferences,
            db.timeLogDao(), db.completionDao(), db.habitDao(), db.habitMetricLinkDao(), db.metricDao(), db.metricLogDao())) }
        withContext(Dispatchers.Main) { vm.loadHabit(habit.id) }
        withTimeout(5000) { vm.uiState.first { !it.isLoading && it.writeAuthority != null } }
        withContext(Dispatchers.Main) { vm.logCompletion(3) }
        val state = withTimeout(5000) { vm.uiState.first { it.lastCompletionId != null } }
        val fact = db.completionDao().getCompletionById(state.lastCompletionId!!)!!
        assertEquals(3, fact.value)
        val queued = db.syncOutboxDao().getAll().single()
        val origin = originalIntent(queued)
        assertEquals("count_snapshot", Json.parseToJsonElement(origin.intentJson).jsonObject
            .getValue("payload").jsonObject.getValue("event_type").jsonPrimitive.content)
        val (http, _) = channel { successReply(it) }
        assertNotNull(sender(http).sendAndAcceptOperation(access(), queued.operationId))
        withContext(Dispatchers.Main) { vm.undoCompletion() }
        withTimeout(5000) { vm.uiState.first { it.lastCompletionId == null && it.completions.isEmpty() } }
        val undo = db.syncOutboxDao().getAll().single()
        val payload = Json.parseToJsonElement(originalIntent(undo).intentJson).jsonObject.getValue("payload").jsonObject
        assertEquals("revert", payload.getValue("event_type").jsonPrimitive.content)
        assertEquals(fact.uuid, payload.getValue("reverts_event_uuid").jsonPrimitive.content)
        assertNotNull(sender(http).sendAndAcceptOperation(access(), undo.operationId))
        withTimeout(5000) { withContext(Dispatchers.Main) { vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
        reopenEditors()
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, queued.operationId))
        assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun actualMetricDetailRecordsChangesAggregationAndLinksThroughTypedAuthority() = runBlocking<Unit> {
        register()
        val repo = creatingMetrics()
        val vm = withContext(Dispatchers.Main) { own(MetricDetailViewModel(app, db.metricDao(), db.metricLogDao(),
            db.habitMetricLinkDao(), db.habitDao(), repo, SavedStateHandle(mapOf("metricId" to metric.id)))) }
        withTimeout(5000) { vm.uiState.first { !it.isLoading && it.writeAuthority != null } }
        withContext(Dispatchers.Main) { vm.recordValue(4.25, "real entry") }
        withTimeout(5000) { vm.uiState.first { it.logs.size == 1 } }
        val queue = db.syncOutboxDao().getAll().single()
        assertEquals(5, originalIntent(queue).protocol)
        val (http, _) = channel { successReply(it) }
        assertNotNull(sender(http).sendAndAcceptOperation(access(), queue.operationId))
        withContext(Dispatchers.Main) { vm.updateAggregationType("sum") }
        withTimeout(5000) { vm.uiState.first { it.metric?.aggregationType == "sum" } }
        val changed = db.metricDao().getMetricById(metric.id)!!
        repo.linkHabits(changed, setOf(habit.id), repo.getMetricForEditing(metric.id).authority)
        withTimeout(5000) { vm.uiState.first { it.links.size == 1 } }
        val link = vm.uiState.value.links.single().link
        withContext(Dispatchers.Main) { vm.showUnlinkConfirm(link.id); vm.unlinkHabit() }
        withTimeout(5000) { vm.uiState.first { it.links.isEmpty() && it.showUnlinkConfirm == null } }
        assertTrue(db.syncOutboxDao().getAll().all { originalIntent(it).protocol == 5 })
        assertEquals(1, db.metricLogDao().countAll())
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test fun lateFactOriginFailureRollsBackCompletionReactivationAndAllNewQueueRows() = runBlocking<Unit> {
        val repo = creatingHabits()
        repo.updateIsActive(habit.id, false, app)
        val inactive = db.habitDao().getHabitById(habit.id)!!
        val before = db.syncOutboxDao().getAll()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_new_fact_origin BEFORE INSERT ON next_request_origins
            WHEN (SELECT recordType FROM sync_outbox WHERE id=NEW.queueId)='completion'
            BEGIN SELECT RAISE(ABORT, 'fact origin unavailable'); END""")
        rejected { repo.logCompletion(app, habit.id, 2) }
        assertEquals(inactive, db.habitDao().getHabitById(habit.id))
        assertEquals(before, db.syncOutboxDao().getAll())
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
        reopenEditors()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_new_fact_origin")
        creatingHabits().logCompletion(app, habit.id, 2)
        assertTrue(db.habitDao().getHabitById(habit.id)!!.isActive)
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
        assertTrue(db.syncOutboxDao().getAll().all { originalIntent(it).protocol == 5 })
    }

    @Test fun metricBatchRejectsMixedProtocolAndLateOriginFailureWithoutPartialObservations() = runBlocking<Unit> {
        val repo = creatingMetrics()
        val otherId = repo.createMetric(metric.copy(id = 0, uuid = id(211), name = "Second"), creationAuthority = creator.capture())
        val oldId = db.metricDao().insert(MetricEntity(name = "Legacy", unit = "kg", iconResId = 1, colorHex = "#000000"))
        val before = db.syncOutboxDao().getAll()
        rejected { repo.recordValues(listOf(MetricValueDraft(metric.id, 1.0), MetricValueDraft(oldId, 2.0))) }
        assertEquals(0, db.metricLogDao().countAll()); assertEquals(before, db.syncOutboxDao().getAll())
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_second_metric_origin BEFORE INSERT ON next_request_origins
            WHEN (SELECT referenceUuid FROM sync_outbox WHERE id=NEW.queueId)='${id(211)}'
                AND (SELECT recordType FROM sync_outbox WHERE id=NEW.queueId)='metric_log'
            BEGIN SELECT RAISE(ABORT, 'second metric origin unavailable'); END""")
        rejected { repo.recordValues(listOf(MetricValueDraft(metric.id, 1.0), MetricValueDraft(otherId, 2.0))) }
        assertEquals(0, db.metricLogDao().countAll()); assertEquals(before, db.syncOutboxDao().getAll())
        reopenEditors()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second_metric_origin")
        assertEquals(2, creatingMetrics().recordValues(listOf(MetricValueDraft(metric.id, 1.0), MetricValueDraft(otherId, 2.0))).size)
        assertEquals(2, db.metricLogDao().countAll())
    }

    @Test fun staleDisplayedMetricAuthorityCannotRecordAfterAuthenticationReplacement() = runBlocking<Unit> {
        val repo = creatingMetrics()
        val vm = withContext(Dispatchers.Main) { own(MetricDetailViewModel(app, db.metricDao(), db.metricLogDao(),
            db.habitMetricLinkDao(), db.habitDao(), repo, SavedStateHandle(mapOf("metricId" to metric.id)))) }
        withTimeout(5000) { vm.uiState.first { !it.isLoading && it.writeAuthority != null } }
        tokens.saveLoginSession("replacement", "replacement-refresh", "member", id(1), false)
        withContext(Dispatchers.Main) { vm.toggleValueInput(); vm.recordValue(9.0, "stale") }
        val failed = withTimeout(5000) { vm.uiState.first { it.errorMessage != null } }
        assertTrue(failed.showValueInput)
        assertEquals(0, db.metricLogDao().countAll()); assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun factOnlyPermissionWorksWhileStructuralChangesAndManualTimerResultsRollBack() = runBlocking<Unit> {
        register(permissions = setOf("sync.read", "facts.append"))
        creatingHabits().logCompletion(app, habit.id, 2)
        creatingMetrics().recordValue(metric.id, -1.25, "negative permitted")
        val before = db.syncOutboxDao().getAll()
        rejected { creatingMetrics().updateAggregationType(metric.id, "sum") }
        rejected { creatingHabits().updateFailMode(habit.id, FailMode.STRICT, app) }
        rejected { creatingHabits().logCompletion(app, timerHabit.id, 1) }
        assertEquals(metric, db.metricDao().getMetricById(metric.id))
        assertEquals(before, db.syncOutboxDao().getAll())
        assertTrue(db.completionDao().getByHabitOnce(timerHabit.id).isEmpty())
    }

    @Test fun metricDeleteCapturesItsCascadedObservationAndLinkTombstonesAtomically() = runBlocking<Unit> {
        val repo = creatingMetrics()
        repo.recordValue(metric.id, 2.0, "history")
        repo.linkHabits(metric, setOf(habit.id))
        val prior = db.syncOutboxDao().getAll()
        repo.deleteMetric(metric, repo.getMetricForEditing(metric.id).authority)
        assertNull(db.metricDao().getMetricById(metric.id)); assertEquals(0, db.metricLogDao().countAll())
        assertTrue(db.habitMetricLinkDao().getAllLinksForHabit(habit.id).isEmpty())
        val rows = db.syncOutboxDao().getAll()
        assertTrue(rows.containsAll(prior))
        assertEquals(setOf("metric", "metric_log", "link"), rows.filter { it.action == "delete" }.map { it.recordType }.toSet())
        assertTrue(rows.all { originalIntent(it).protocol == 5 })
        reopenEditors()
        assertNull(db.metricDao().getMetricById(metric.id)); assertEquals(rows, db.syncOutboxDao().getAll())
    }

    @Test fun racingOrdinaryUndoKeepsItsNoOpSemanticsAndDoesNotCreateASecondRevert() = runBlocking<Unit> {
        val repo = creatingHabits()
        val completion = repo.logCompletion(app, habit.id, 2)
        val actual = db.completionDao()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val captured = CompletableDeferred<Unit>()
        val gated = object : com.dayforge.data.local.dao.CompletionDao by actual {
            override suspend fun getCompletionById(id: Long): com.dayforge.data.local.entity.CompletionEntity? {
                val row = actual.getCompletionById(id)
                if (!db.inTransaction() && reads.incrementAndGet() <= 2) {
                    if (reads.get() == 2) captured.complete(Unit)
                    withTimeout(5000) { captured.await() }
                }
                return row
            }
        }
        val concurrent = HabitRepository(db.habitDao(), gated, db.timeLogDao(), db,
            nextObjectEditor = editor, nextObjectCreator = creator)
        withTimeout(5000) {
            listOf(async { concurrent.undoCompletion(app, completion) },
                async { concurrent.undoCompletion(app, completion) }).awaitAll()
        }
        assertNull(actual.getCompletionById(completion))
        val rows = db.syncOutboxDao().getAll()
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.action == "delete" })
        assertTrue(rows.all { originalIntent(it).protocol == 5 })
    }
}
