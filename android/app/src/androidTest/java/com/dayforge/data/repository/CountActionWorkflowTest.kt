package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.model.CheckInResult
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.CountActionGate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Shared app/widget admission plus original identity enforced by real Room writers. */
@RunWith(AndroidJUnit4::class)
class CountActionWorkflowTest : NextObjectEditorFixture() {
    private suspend fun current() = requireNotNull(db.habitDao().getHabitById(habit.id))
    private suspend fun claim() = requireNotNull(CountHistoryReader(db, tokens, sessions).read(current()).actionIdentity)
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Stale action must not be accepted") }
        catch (error: IllegalStateException) { assertNotNull(error.message) }
    }

    @Test fun dashboardNestedDetailAndWidgetShareLeaseWithoutDroppingLaterAscendingFacts() = runBlocking {
        val gate = CountActionGate(tokens, { 1000L }, { _, _ -> })
        val repo = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
            nextObjectEditor = editor, countHistoryReader = CountHistoryReader(db, tokens, sessions),
            recurringHabitReader = RecurringHabitReader(db, tokens, sessions, editor),
            widgetFactReader = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions)))
        val service = CheckInService(repo, db.completionDao(), db.timeLogDao(), gate)
        val displayed = claim()
        val widget = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions)).read(current()).claim
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val baseline = db.syncOutboxDao().getAll().size
        val accepted = gate.launch(this, displayed) {
            entered.complete(Unit); finish.await()
            service.incrementCount(app, habit.id, displayed)
        }!!
        entered.await()
        assertNull(gate.launch(this, displayed) { repo.logCompletion(app, habit.id, countIdentity = displayed) })
        assertNull(gate.launch(this, displayed) { service.decrementCount(app, habit.id, displayed) })
        assertEquals(CheckInResult.Ignored, service.widgetInteraction(app, widget, "increment"))
        assertEquals(baseline, db.syncOutboxDao().getAll().size)
        finish.complete(Unit); accepted.join()
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(baseline + 1, db.syncOutboxDao().getAll().size)
        // Admission never deduplicates legitimate domain commands or changes count rules.
        service.incrementCount(app, habit.id, claim())
        assertEquals(2, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun originalPlanAndDeviceAreRequiredAndInvalidCommandsLeaveFactsAndOutboxUntouched() = runBlocking {
        register(); val repo = creatingHabits(); val old = claim()
        register(device = id(444))
        val baseline = db.syncOutboxDao().getAll()
        reject { repo.logCompletion(app, habit.id, countIdentity = old) }
        assertEquals(baseline, db.syncOutboxDao().getAll()); assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
        val original = claim()
        producer().write(local()) { db.habitDao().update(current().copy(targetValue = 3)) }
        val afterEdit = db.syncOutboxDao().getAll()
        reject { repo.logCompletion(app, habit.id, countIdentity = original) }
        assertEquals(afterEdit, db.syncOutboxDao().getAll())
        repo.logCompletion(app, habit.id, countIdentity = claim())
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun accountChangeCannotAdoptSameLocalIdOrUndoItsFact() = runBlocking {
        val repo = creatingHabits(); val original = claim()
        val fact = repo.logCompletion(app, habit.id, countIdentity = original)
        tokens.saveLoginSession("synthetic-next", "synthetic-refresh", "member", id(888), false)
        val before = db.syncOutboxDao().getAll()
        reject { repo.logCompletion(app, habit.id, countIdentity = original) }
        reject { repo.undoCompletion(app, fact, countIdentity = original) }
        assertEquals(before, db.syncOutboxDao().getAll())
        assertEquals(fact, db.completionDao().getByHabitOnce(habit.id).single().id)
    }

    @Test fun legacyCountUsesOriginalAccountAndPlanWithoutChangingRecordOrUndoSemantics() = runBlocking {
        val repo = creatingHabits()
        val row = current().copy(appearance = null, completionPolicy = null, planMetadata = null)
        db.habitDao().update(row)
        val reader = CountActionReader(db, tokens, sessions)
        val first = reader.legacy(row)
        val fact = repo.logCompletion(app, row.id, countIdentity = first)
        val undo = reader.legacy(current())
        repo.undoCompletion(app, fact, countIdentity = undo)
        assertTrue(db.completionDao().getByHabitOnce(row.id).isEmpty())
        val expired = reader.legacy(current())
        tokens.saveLoginSession("synthetic-next", "synthetic-refresh", "member", id(888), false)
        val before = db.syncOutboxDao().getAll()
        reject { repo.logCompletion(app, row.id, countIdentity = expired) }
        assertEquals(before, db.syncOutboxDao().getAll())
    }

    @Test fun actualDetailEntrySharesExternalLeaseAndPreservesDirectRecordAndUndo() = runBlocking {
        var now = 0L
        val gate = CountActionGate(tokens, { now }, { _, _ -> })
        val repo = creatingHabits()
        val model = withContext(Dispatchers.Main) { own(com.dayforge.ui.screens.habitdetail.HabitDetailViewModel(
            app, repo, preferences, db.timeLogDao(), db.completionDao(), db.habitDao(),
            db.habitMetricLinkDao(), db.metricDao(), db.metricLogDao(), countActions = gate)) }
        withContext(Dispatchers.Main) { model.loadHabit(habit.id) }
        withTimeout(10_000) { while (model.uiState.value.countActionIdentity == null) delay(20) }
        val original = requireNotNull(model.uiState.value.countActionIdentity)
        val otherScreen = gate.tryAcquire(original)!!
        withContext(Dispatchers.Main) { repeat(3) { model.logCompletion() } }
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
        now = 300; gate.release(otherScreen)
        withContext(Dispatchers.Main) { repeat(3) { model.logCompletion() } }
        withTimeout(10_000) { while (model.uiState.value.lastCompletionId == null) delay(20) }
        val fact = db.completionDao().getByHabitOnce(habit.id).single()
        assertEquals(1, fact.value)
        // Finishing the write alone does not bypass the minimum interaction interval.
        withContext(Dispatchers.Main) { model.undoCompletion() }
        assertEquals(fact, db.completionDao().getByHabitOnce(habit.id).single())
        now = 600
        withContext(Dispatchers.Main) { model.undoCompletion() }
        withTimeout(10_000) { while (db.completionDao().getByHabitOnce(habit.id).isNotEmpty()) delay(20) }
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
        assertNull(model.uiState.value.errorMessage)

        now = 900
        withContext(Dispatchers.Main) { model.logCompletion() }
        withTimeout(10_000) { while (model.uiState.value.lastCompletionId == null) delay(20) }
        val previousGeneration = requireNotNull(model.uiState.value.countActionIdentity).session
        tokens.saveLoginSession("synthetic-reauth", "synthetic-refresh", "member", id(1), false)
        withTimeout(10_000) {
            while (model.uiState.value.countActionIdentity?.session.let { it == null || it == previousGeneration }) delay(20)
        }
        assertNull("Undo ID must not be rebound to a new authentication generation", model.uiState.value.lastCompletionId)
        val beforeLogout = db.syncOutboxDao().getAll()
        tokens.clearTokens()
        withTimeout(10_000) { while (!model.uiState.value.readError) delay(20) }
        withContext(Dispatchers.Main) { model.logCompletion(); model.undoCompletion() }
        assertEquals(beforeLogout, db.syncOutboxDao().getAll())
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
    }
}
