package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.model.HabitType
import com.dayforge.ui.screens.habitdetail.HabitDetailViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real typed Room reads/writes; CHECK undo retains the source of its own successful append. */
@RunWith(AndroidJUnit4::class)
class CheckDetailActionTest : NextObjectEditorFixture() {
    private suspend fun configure(active: Boolean = true) {
        register()
        habit = habit.copy(habitType = HabitType.CHECK_IN, targetValue = 1, isActive = active)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }

    private suspend fun model(repo: HabitRepository): HabitDetailViewModel {
        val model = withContext(Dispatchers.Main) {
            own(HabitDetailViewModel(app, repo, preferences, db.timeLogDao(), db.completionDao(), db.habitDao(),
                db.habitMetricLinkDao(), db.metricDao(), db.metricLogDao()))
        }
        withContext(Dispatchers.Main) { model.loadHabit(habit.id) }
        await { model.uiState.value.checkActionIdentity != null }
        return model
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(20_000) {
        while (!condition()) delay(20)
    }

    /** Explicit delegation avoids suspend-spy/callOriginal continuation substitution. */
    private fun delegated(actual: HabitRepository,
        afterRead: suspend (RecurringHabitSnapshot) -> Unit = {},
        afterWrite: suspend (Long) -> Unit = {}): HabitRepository {
        val delayed = mockk<HabitRepository>()
        every { delayed.getHabit(any()) } answers { actual.getHabit(firstArg()) }
        every { delayed.getAllCompletions() } returns actual.getAllCompletions()
        every { delayed.oneTimeChanges } returns actual.oneTimeChanges
        every { delayed.countChanges } returns actual.countChanges
        coEvery { delayed.getRecurringSnapshot(any()) } coAnswers {
            actual.getRecurringSnapshot(firstArg()).also { afterRead(it) }
        }
        coEvery { delayed.getHabitForEditing(any()) } coAnswers { actual.getHabitForEditing(firstArg()) }
        coEvery { delayed.logCompletion(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val id = actual.logCompletion(arg(0), arg(1), arg(2), arg(3), arg(4), arg(5), arg(6), arg(7))
            afterWrite(id)
            id
        }
        coEvery { delayed.undoCompletion(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            actual.undoCompletion(arg(0), arg(1), arg(2), arg(3), arg(4), arg(5), arg(6))
        }
        return delayed
    }

    @Test fun ownActivationMayRefreshBeforeReturnAndUndoUsesOnlyKnownSuccessor() = runBlocking {
        configure(active = false)
        val actual = creatingHabits()
        val committed = CompletableDeferred<Long>(); val release = CompletableDeferred<Unit>()
        val delayed = delegated(actual, afterWrite = { committed.complete(it); release.await() })
        val model = model(delayed)
        val original = requireNotNull(model.uiState.value.checkActionIdentity)
        val successor = original.completionSuccessor(requireNotNull(model.uiState.value.habit))
        assertNotEquals(original, successor)
        val baseline = db.syncOutboxDao().getAll().size
        try {
            withContext(Dispatchers.Main) { model.logCompletion() }
            val id = withTimeout(20_000) { committed.await() }
            await { model.uiState.value.checkActionIdentity == successor }
            assertTrue(requireNotNull(model.uiState.value.habit).isActive)
            assertNull("A pending return is not a successful-write receipt", model.uiState.value.lastCompletionId)
            assertEquals(id, db.completionDao().getByHabitOnce(habit.id).single().id)
            assertEquals(baseline + 2, db.syncOutboxDao().getAll().size) // Activation + check fact.
            release.complete(Unit)
            await { model.uiState.value.lastCompletionId == id && model.busyHabitIds.value.isEmpty() }
            withContext(Dispatchers.Main) { model.undoCompletion() }
            await { db.completionDao().getByHabitOnce(habit.id).isEmpty() && model.busyHabitIds.value.isEmpty() }
            assertNull(model.uiState.value.lastCompletionId)
            assertNull(model.uiState.value.errorMessage)
            coVerify(exactly = 1) {
                delayed.undoCompletion(app, id, null, any(), null, null, successor)
            }
            assertTrue(requireNotNull(db.habitDao().getHabitById(habit.id)).isActive)
        } finally { release.complete(Unit) }
    }

    @Test fun ownActivationCanBeUndoneBeforeTheDisplayReceivesItsSuccessor() = runBlocking {
        configure(active = false)
        val readStarted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delayed = delegated(creatingHabits(), afterRead = {
            if (it.habit.isActive) { readStarted.complete(Unit); release.await() }
        })
        val model = model(delayed)
        val original = requireNotNull(model.uiState.value.checkActionIdentity)
        val successor = original.completionSuccessor(requireNotNull(model.uiState.value.habit))
        try {
            withContext(Dispatchers.Main) { model.logCompletion() }
            await { model.uiState.value.lastCompletionId != null && model.busyHabitIds.value.isEmpty() }
            withTimeout(20_000) { readStarted.await() }
            val id = requireNotNull(model.uiState.value.lastCompletionId)
            assertEquals(original, model.uiState.value.checkActionIdentity)
            assertFalse(requireNotNull(model.uiState.value.habit).isActive)
            withContext(Dispatchers.Main) { model.undoCompletion() }
            await { db.completionDao().getByHabitOnce(habit.id).isEmpty() && model.busyHabitIds.value.isEmpty() }
            assertNull(model.uiState.value.lastCompletionId)
            assertNull(model.uiState.value.errorMessage)
            coVerify(exactly = 1) {
                delayed.undoCompletion(app, id, null, null, null, null, successor)
            }
            release.complete(Unit)
            await { model.uiState.value.checkActionIdentity == successor }
            assertTrue(requireNotNull(db.habitDao().getHabitById(habit.id)).isActive)
        } finally { release.complete(Unit) }
    }

    @Test fun planChangesAndReauthenticationDiscardUndoRatherThanRebindingItsId() = runBlocking {
        configure()
        val model = model(creatingHabits())
        withContext(Dispatchers.Main) { model.logCompletion() }
        await { model.uiState.value.lastCompletionId != null && model.busyHabitIds.value.isEmpty() }
        val original = requireNotNull(model.uiState.value.checkActionIdentity)
        val fact = db.completionDao().getByHabitOnce(habit.id).single()
        val row = requireNotNull(db.habitDao().getHabitById(habit.id))
        producer().write(local()) { db.habitDao().update(row.copy(name = "Edited check plan")) }
        await { model.uiState.value.checkActionIdentity?.let { it.plan != original.plan } == true }
        assertNull(model.uiState.value.lastCompletionId)
        val edited = db.syncOutboxDao().getAll()
        withContext(Dispatchers.Main) { model.undoCompletion() }
        assertEquals(edited, db.syncOutboxDao().getAll())
        producer().write(local()) { db.habitDao().update(requireNotNull(db.habitDao().getHabitById(habit.id)).copy(name = row.name)) }
        await { model.uiState.value.checkActionIdentity == original }
        assertNull("Returning to an old plan must not resurrect a discarded undo", model.uiState.value.lastCompletionId)
        val restored = db.syncOutboxDao().getAll()
        withContext(Dispatchers.Main) { model.undoCompletion() }
        assertEquals(listOf(fact), db.completionDao().getByHabitOnce(habit.id))
        assertEquals(restored, db.syncOutboxDao().getAll())
        assertNull(model.uiState.value.errorMessage)

        withContext(Dispatchers.Main) { model.logCompletion() }
        await { model.uiState.value.lastCompletionId != null && model.busyHabitIds.value.isEmpty() }
        val facts = db.completionDao().getByHabitOnce(habit.id)
        assertEquals(2, facts.size)
        val beforeReauth = db.syncOutboxDao().getAll()
        tokens.saveLoginSession("synthetic-check-reauth", "synthetic-refresh", "member", id(1), false)
        await { model.uiState.value.checkActionIdentity?.session.let { it != null && it != original.session } }
        assertNull(model.uiState.value.lastCompletionId)
        withContext(Dispatchers.Main) { model.undoCompletion() }
        assertEquals(facts, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(beforeReauth, db.syncOutboxDao().getAll())
        assertNull(model.uiState.value.errorMessage)
    }
}
