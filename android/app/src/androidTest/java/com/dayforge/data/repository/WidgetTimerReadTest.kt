package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.widget.timer.WidgetTimerPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real authenticated file Room, start origin and read projection; no launcher-rendering claim. */
@RunWith(AndroidJUnit4::class)
class WidgetTimerReadTest : NextCoreRequestFixture() {
    private fun writer() = NextTimerWriter(db, tokens, sessions)

    @Test fun activeWidgetUsesOriginalTargetDirectionAndLimitAfterPlanEditAndColdReopen() = runBlocking<Unit> {
        start()
        producer().write(local()) { db.habitDao().update(timerHabit.copy(targetValue = 3, isCountdown = true)) }
        val updated = requireNotNull(db.habitDao().getVisibleHabitById(timerHabit.id))
        assertNull(writer().widgetSnapshot(timerHabit)) // stale display cannot adopt the newly edited plan
        storage.reopen()
        val before = db.timeLogDao().getPendingTimerCommands()
        val origins = count("next_request_origins")
        val snapshot = requireNotNull(writer().widgetSnapshot(updated))
        assertEquals(3, snapshot.habit.targetValue)
        assertTrue(snapshot.habit.isCountdown)
        val policy = WidgetTimerPolicy.read(snapshot.habit, snapshot.activeLog, snapshot.policy)
        assertEquals(1, policy.targetMinutes)
        assertFalse(policy.isCountdown)
        assertEquals(180, policy.maxDurationSeconds)
        assertEquals(180, policy.elapsed(400))
        assertEquals(id(20), snapshot.authority.sessionUuid)
        assertEquals(before, db.timeLogDao().getPendingTimerCommands())
        assertEquals(origins, count("next_request_origins"))
    }

    @Test fun countdownRemainingUsesItsOwnSessionRatherThanEarlierCompletedSessions() = runBlocking<Unit> {
        producer().write(local()) { db.habitDao().update(timerHabit.copy(isCountdown = true)) }
        start()
        val updated = requireNotNull(db.habitDao().getVisibleHabitById(timerHabit.id))
        val snapshot = requireNotNull(writer().widgetSnapshot(updated))
        val policy = WidgetTimerPolicy.read(updated, snapshot.activeLog, snapshot.policy)
        assertTrue(policy.isCountdown)
        assertEquals(60, policy.maxDurationSeconds)
        assertEquals(40, policy.remaining(snapshot.activeLog, completedSeconds = 55, elapsedSeconds = 20))
        assertEquals(0, policy.remaining(snapshot.activeLog, completedSeconds = 55, elapsedSeconds = policy.elapsed(90)))
        assertEquals(5, policy.remaining(null, completedSeconds = 55, elapsedSeconds = 0))
    }

    @Test fun corruptOriginalAndDifferentAccountCannotFallBackToEditedPlan() = runBlocking<Unit> {
        start()
        val log = requireNotNull(db.timeLogDao().getActiveTimeLog())
        val commands = db.timeLogDao().getPendingTimerCommands()
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=occurredAt+1 WHERE commandId=?", arrayOf(id(21)))
        rejected { writer().widgetSnapshot(timerHabit) }
        assertEquals(log, db.timeLogDao().getActiveTimeLog())
        db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET occurredAt=? WHERE commandId=?", arrayOf<Any>(millis, id(21)))
        assertEquals(60, requireNotNull(writer().widgetSnapshot(timerHabit)).policy!!.targetSeconds)
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
        // Intentionally retain the old Room cache during an authentication transition. Its
        // original start belongs to the previous owner, so reject rather than return a display.
        rejected { writer().widgetSnapshot(timerHabit) }
        assertEquals(log, db.timeLogDao().getActiveTimeLog())
        assertEquals(commands, db.timeLogDao().getPendingTimerCommands())
        tokens.saveLoginSession("synthetic-return", "synthetic-refresh", "member", id(1), false)
        assertEquals(60, requireNotNull(writer().widgetSnapshot(timerHabit)).policy!!.targetSeconds)
    }

    @Test fun inactivePlanReadsCurrentPolicyButActiveTypedTimerRequiresOriginalProof() = runBlocking<Unit> {
        val snapshot = requireNotNull(writer().widgetSnapshot(timerHabit))
        assertNull(snapshot.activeLog)
        assertNull(snapshot.policy)
        val inactive = WidgetTimerPolicy.read(snapshot.habit, null, null)
        assertEquals(60, inactive.targetSeconds)
        assertEquals(180, inactive.maxDurationSeconds)
        val fakeActive = TimeLogEntity(habitId = timerHabit.id, uuid = id(99), startTime = millis,
            date = millis, endTime = null, durationSeconds = 0)
        rejected { WidgetTimerPolicy.read(snapshot.habit, fakeActive, null) }
        val legacy = timerHabit.copy(appearance = null)
        assertEquals(legacy.targetValue, WidgetTimerPolicy.read(legacy, fakeActive, null).targetMinutes)
        assertNull(writer().widgetSnapshot(timerHabit.copy(uuid = id(88))))
    }
}
