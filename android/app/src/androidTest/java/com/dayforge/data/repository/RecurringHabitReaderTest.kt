package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecurringHabitReaderTest : NextObjectEditorFixture() {
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }

    @Test fun partialCountIsActivityButNotCompletionAndNeverChangesTheAuthorityBearingRow() = runBlocking<Unit> {
        register()
        val repo = creatingHabits()
        repo.logCompletion(app, habit.id, 6)
        val current = requireNotNull(repo.getHabitById(habit.id))
        val before = proof()
        val view = repo.getRecurringSnapshot(current)
        val history = requireNotNull(view.countHistory)
        val today = DateTimeUtils.today()
        assertEquals(6L, history.todayQuantity)
        assertFalse(history.completedToday); assertTrue(history.qualifiedDates.isEmpty())
        assertEquals(ActivityRateCalculator.calculate(current.schedule, current.createdAt,
            listOf(today), today.toDisplayMillis()), view.activityRate)
        assertEquals(current, view.habit)
        assertEquals(NextStructureMapper.writePlan(current), view.authority.original)
        assertNull(view.checkHistory); assertNull(view.timerHistory)
        val counts = CountHistoryReader(db, tokens, sessions)
        val stats = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao(), counts),
            db.completionDao(), db.timeLogDao(), countHistoryReader = counts).calculate(current)
        assertEquals(view.activityRate, stats.activityRate); assertFalse(stats.completedToday)
        assertEquals(current, requireNotNull(repo.getHabitById(current.id)))
        assertEquals(before, proof())
    }

    @Test fun stalePlanAndChangedAccountCannotReturnACombinedProjectionOrRepairOriginalFacts() = runBlocking<Unit> {
        register()
        val repo = creatingHabits()
        repo.logCompletion(app, habit.id, 3)
        val expected = requireNotNull(repo.getHabitById(habit.id))
        db.habitDao().updateActivityRate(habit.id, 7)
        val changed = proof()
        assertEquals("RECURRING_READ_ACTIVITY_CHANGED", rejected { repo.getRecurringSnapshot(expected) }.message)
        assertEquals(changed, proof())
        val current = requireNotNull(repo.getHabitById(habit.id))
        assertEquals(current, repo.getRecurringSnapshot(current).habit)
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
        rejected { repo.getRecurringSnapshot(current) }
        assertEquals(changed, proof())
        tokens.saveLoginSession("synthetic-return", "synthetic-refresh", "member", id(1), false)
        register()
        assertEquals(3L, requireNotNull(repo.getRecurringSnapshot(current).countHistory).todayQuantity)
        assertEquals(changed, proof())
    }
}
