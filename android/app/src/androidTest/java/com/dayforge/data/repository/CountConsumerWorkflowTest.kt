package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.FailMode
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.service.*
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Formal repository/service consumers, original quantities, physical Room and account barriers. */
@RunWith(AndroidJUnit4::class)
class CountConsumerWorkflowTest : NextObjectEditorFixture() {
    private suspend fun current() = requireNotNull(db.habitDao().getHabitById(habit.id))
    private fun reader() = CountHistoryReader(db, tokens, sessions)
    private fun calculator() = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao(), reader()),
        db.completionDao(), db.timeLogDao(), countHistoryReader = reader())
    private suspend fun edit(target: Int, countdown: Boolean) = producer().write(local()) {
        db.habitDao().update(current().copy(targetValue = target, isCountdown = countdown))
    }
    private suspend fun historical(date: LocalDate, quantity: Int) {
        val h = current()
        val fact = CompletionEntity(habitId = h.id, habitUuid = h.uuid, date = date.toDisplayMillis(), value = quantity,
            actualCompletedAt = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        producer().write(local()) {
            NextCountDayStore(db).capture(h, fact)
            db.completionDao().insertForSync(fact)
        }
    }

    @Test fun currentRuleDrivesCompletionFeedbackAndStreakAfterTargetAndDirectionEdits() = runBlocking<Unit> {
        val repo = creatingHabits()
        val service = CheckInService(repo, db.completionDao(), db.timeLogDao())
        repo.logCompletion(app, habit.id, 6)
        edit(3, true)
        val before = calculator().calculate(current())
        assertEquals(habit.uuid, before.habit.uuid)
        assertEquals(3, before.habit.targetValue) // Original editable plan, not a display copy.
        assertEquals(10, before.displayTargetValue); assertFalse(before.displayIsCountdown)
        assertEquals(6L, before.actualTodayCount); assertFalse(before.completedToday)
        assertFalse(service.isCompleted(habit.id, 3))
        assertEquals(0, before.currentStreak)
        repeat(4) { service.incrementCount(app, habit.id) }
        val met = calculator().calculate(current())
        assertTrue(met.completedToday); assertEquals(1, met.currentStreak); assertEquals(1, met.bestStreak)
        assertTrue((service.incrementCount(app, habit.id) as CheckInResult.Success).completed)
        assertEquals(11L, calculator().calculate(current()).actualTodayCount)
        assertFalse((service.decrementCount(app, habit.id) as CheckInResult.Success).goalReached)
        assertEquals(10L, reader().read(current()).todayQuantity)
    }

    @Test fun undoAllAndColdReadKeepOriginalRuleAndLongActualQuantities() = runBlocking<Unit> {
        creatingHabits().logCompletion(app, habit.id, 6)
        edit(5, true)
        for (fact in db.completionDao().getByHabitOnce(habit.id)) creatingHabits().undoCompletion(app, fact.id)
        assertEquals(CountDayPolicy(10, false), reader().read(current()).todayPolicy)
        assertEquals(0L, reader().read(current()).todayQuantity)
        // Recreate the reader on a cold physical DB; its dependency is never a cached day map.
        storage.reopen()
        val repo = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
            countHistoryReader = reader())
        // Use the original producer to retain full v5 capture semantics without an editor on a closed DB.
        historical(DateTimeUtils.today(), Int.MAX_VALUE)
        historical(DateTimeUtils.today(), Int.MAX_VALUE)
        val h = repo.getCountHistory(current())
        assertEquals(4_294_967_294L, h.todayQuantity)
        assertEquals(CountDayPolicy(10, false), h.todayPolicy)
        assertEquals(listOf(Int.MAX_VALUE, Int.MAX_VALUE), h.completions.map { it.value })
        assertTrue(h.completedToday)
    }

    @Test fun eachHistoricalDateUsesItsOwnTargetAndUnknownDaysNeverBecomeAchievementsOrFailures() = runBlocking<Unit> {
        val today = DateTimeUtils.today()
        historical(today.minusDays(2), 9)
        edit(5, true)
        historical(today.minusDays(1), 6)
        val h = reader().read(current())
        assertEquals(setOf(today.minusDays(1)), h.qualifiedDates)
        assertEquals(1, StreakCalculator.currentFromBusinessDates(h.qualifiedDates, today))
        assertEquals(1, StreakCalculator.bestFromBusinessDates(h.qualifiedDates))
        assertTrue(FailureCheckerUtils.countHasFailed(current().copy(targetCycles = 3, failMode = FailMode.STRICT), h))
        assertEquals(CountDayPolicy(5, true), h.todayPolicy)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.completionDao().insertForSync(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
                date = today.minusDays(3).toDisplayMillis(), value = 100))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val withUnknown = reader().read(current())
        assertEquals(100L, withUnknown.quantities[today.minusDays(3)])
        assertEquals(setOf(today.minusDays(3)), withUnknown.unknownDates)
        assertEquals(h.qualifiedDates, withUnknown.qualifiedDates)
    }

    @Test fun missingKnownRuleAndRawQuantityCorruptionFailClosedWithoutWrites() = runBlocking<Unit> {
        creatingHabits().logCompletion(app, habit.id, 2)
        val original = db.countDayDao().get(habit.id, DateTimeUtils.today().toString())!!
        val queued = db.syncOutboxDao().getAll()
        db.openHelper.writableDatabase.execSQL("DELETE FROM count_days WHERE habitId=?", arrayOf(habit.id))
        try { reader().read(current()); fail("Known original must not adopt today's plan") }
        catch (error: IllegalArgumentException) { assertEquals("COUNT_DAY_INVALID", error.message) }
        db.countDayDao().insert(original)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=2147483648 WHERE habitId=?", arrayOf(habit.id))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        try { reader().read(current()); fail("Room Int coercion must not hide a damaged quantity") }
        catch (error: IllegalStateException) { assertEquals("COUNT_FACT_INVALID", error.message) }
        assertEquals(queued, db.syncOutboxDao().getAll())
    }

    @Test fun sameLiteralDateKeepsRuleAndUnstartedDateUsesNewPlan() = runBlocking<Unit> {
        val date = DateTimeUtils.today()
        historical(date, 6)
        edit(20, true)
        val h = reader().read(current(), date)
        assertEquals(CountDayPolicy(10, false), h.todayPolicy)
        assertEquals(CountDayPolicy(20, true), reader().read(current(), date.plusDays(1)).todayPolicy)
        assertEquals(h.qualifiedDates, reader().read(current(), date.plusDays(1)).qualifiedDates)
        assertEquals(6L, h.todayQuantity)
    }

    @Test fun firstIncrementAndLastUndoReturnCommittedStatusDespiteActivityRateChanges() = runBlocking<Unit> {
        val service = CheckInService(creatingHabits(), db.completionDao(), db.timeLogDao())
        assertFalse((service.incrementCount(app, habit.id) as CheckInResult.Success).completed)
        assertEquals(1L, reader().read(current()).todayQuantity)
        assertFalse((service.decrementCount(app, habit.id) as CheckInResult.Success).completed)
        assertEquals(0L, reader().read(current()).todayQuantity)
        assertEquals(CountDayPolicy(10, false), reader().read(current()).todayPolicy)
    }

    @Test fun authenticationReplacementCannotReadTheOldOwnersDailyRuleOrQuantity() = runBlocking<Unit> {
        creatingHabits().logCompletion(app, habit.id, 6)
        val queued = db.syncOutboxDao().getAll()
        tokens.saveLoginSession("synthetic-other", "synthetic-other-refresh", "other", id(99), false)
        try { reader().read(current()); fail("Old account snapshot must not cross authentication replacement") }
        catch (error: IllegalStateException) { assertEquals("COUNT_SESSION_CHANGED", error.message) }
        assertEquals(queued, db.syncOutboxDao().getAll())
        assertEquals(6, db.completionDao().getByHabitOnce(habit.id).single().value)
    }
}
