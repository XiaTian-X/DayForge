package com.dayforge.domain.service

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.widget.WidgetFailureChecker
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real file Room read/terminal boundaries; never changes the phone's clock or installs production APKs. */
@RunWith(AndroidJUnit4::class)
class StrictTimerFailureTest {
    @get:Rule val storage = PhysicalDatabaseRule()
    private val db: HabitDatabase get() = storage.database
    private val day = LocalDate.of(2026, 3, 8)

    private suspend fun habit(): HabitEntity {
        val draft = HabitEntity(name = "Strict timer", habitType = HabitType.TIMER, iconResId = 1,
            colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 1, targetCycles = 10,
            createdAt = day.minusDays(10).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        return draft.copy(id = db.habitDao().insert(draft))
    }

    private fun at(date: LocalDate, hour: Int, minute: Int, second: Int = 0, zone: String = "UTC") =
        date.atTime(hour, minute, second).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private suspend fun start(habit: HabitEntity, started: Long, zone: String = "UTC"): TimeLogEntity {
        val log = TimeLogEntity(habitId = habit.id, startTime = started, endTime = null, durationSeconds = 0,
            timerTimezone = zone, timerNextCommandSequence = 2, timerControlGeneration = 1,
            timerLastCommandAt = started, date = java.time.Instant.ofEpochMilli(started).atZone(ZoneId.of(zone))
                .toLocalDate().atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli())
        return log.copy(id = db.timeLogDao().insertSyncedTimer(log, command(log, 1, "start", started),
            TimerSegmentEntity(sessionUuid = log.uuid, sequence = 1, startedAt = started)))
    }

    private fun command(log: TimeLogEntity, sequence: Int, type: String, at: Long, elapsed: Long? = null) =
        TimerCommandEntity(sessionUuid = log.uuid, sequence = sequence, commandType = type, occurredAt = at,
            expectedControlGeneration = if (type == "start") 0 else 1,
            timezone = if (type == "start") log.timerTimezone else null, activeElapsedMillis = elapsed)

    private suspend fun finish(log: TimeLogEntity, ended: Long, elapsed: Long) = db.withTransaction {
        db.timeLogDao().finishTimerAndQueue(log.id, ended, (elapsed / 1000).toInt(), log.accumulatedPauseMillis,
            log.timerNextCommandSequence + 1, elapsed, command(log, log.timerNextCommandSequence, "stop", ended, elapsed), log.isPaused)
        db.timeLogDao().replaceDayAllocations(log.uuid, DurationDayAllocator.allocate(log.uuid, log.habitId,
            requireNotNull(log.timerTimezone), db.timeLogDao().getTimerSegments(log.uuid), elapsed))
    }

    private suspend fun evaluate(habit: HabitEntity, first: LocalDate = day, today: LocalDate = day.plusDays(1)) =
        FailureCheckerUtils.evaluateStrictFailure(habit, first, db.completionDao(), db.timeLogDao(), today)

    @Test fun runningAcrossMidnightAwaitsSettlementWithoutWritingCompletion() = runBlocking {
        val habit = habit()
        val log = start(habit, at(day, 23, 58))
        val commands = db.timeLogDao().getPendingTimerCommands()
        val outbox = db.syncOutboxDao().getAll()
        assertEquals(StrictFailureState.AWAITING_TIMER_SETTLEMENT, evaluate(habit))
        assertEquals(log, db.timeLogDao().getById(log.id))
        assertTrue(db.timeLogDao().getDayAllocations(log.uuid).isEmpty())
        assertEquals(commands, db.timeLogDao().getPendingTimerCommands())
        assertEquals(outbox, db.syncOutboxDao().getAll())
        assertEquals(0, db.timeLogDao().getTargetMetDayCount(habit.id, 60))
        assertNull(db.timeLogDao().getFirstTimeLogDate(habit.id))
    }

    @Test fun insufficientPastIntervalFailsEvenWhileTimerContinuesToday() = runBlocking {
        val habit = habit()
        start(habit, at(day, 23, 59, 31))
        assertEquals(StrictFailureState.FAILED, evaluate(habit))
    }

    @Test fun olderDefiniteMissWinsOverThePendingMidnightDate() = runBlocking {
        val habit = habit()
        start(habit, at(day, 23, 58))
        assertEquals(StrictFailureState.FAILED, evaluate(habit, first = day.minusDays(1)))
    }

    @Test fun laterDefiniteMissAlsoWinsRatherThanReturningAtFirstPendingDate() = runBlocking {
        val habit = habit()
        val log = start(habit, at(day, 23, 58))
        db.timeLogDao().closeOpenTimerSegment(log.uuid, at(day, 23, 59))
        db.timeLogDao().updateForSync(log.copy(isPaused = true, pausedAt = at(day, 23, 59), timerActiveElapsedMillis = 60_000))
        assertEquals(StrictFailureState.AWAITING_TIMER_SETTLEMENT, evaluate(habit))
        assertEquals(StrictFailureState.FAILED, evaluate(habit, today = day.plusDays(2)))
    }

    @Test fun pauseAcrossMidnightCannotContributeThePausedGap() = runBlocking {
        val habit = habit()
        val log = start(habit, at(day, 23, 59, 20))
        db.timeLogDao().closeOpenTimerSegment(log.uuid, at(day, 23, 59, 40))
        db.timeLogDao().updateForSync(log.copy(isPaused = true, pausedAt = at(day, 23, 59, 40), timerActiveElapsedMillis = 20_000))
        assertEquals(StrictFailureState.FAILED, evaluate(habit))
    }

    @Test fun resumedIntervalIsSeparateFromEarlierPauseAndUsesCapturedTimezone() = runBlocking {
        val habit = habit()
        val log = start(habit, at(day, 23, 57, zone = "Asia/Shanghai"), "Asia/Shanghai")
        db.timeLogDao().closeOpenTimerSegment(log.uuid, at(day, 23, 57, 30, "Asia/Shanghai"))
        db.timeLogDao().insertTimerSegment(TimerSegmentEntity(sessionUuid = log.uuid, sequence = 3,
            startedAt = at(day, 23, 59, 30, "Asia/Shanghai")))
        assertEquals(StrictFailureState.AWAITING_TIMER_SETTLEMENT, evaluate(habit))
        assertEquals(StrictFailureState.FAILED, evaluate(habit.copy(targetValue = 2)))
    }

    @Test fun normalStopSettlesTheSameDateAfterColdReopen() = runBlocking {
        val habit = habit()
        val log = start(habit, at(day, 23, 58))
        assertEquals(StrictFailureState.AWAITING_TIMER_SETTLEMENT, evaluate(habit))
        storage.reopen()
        assertEquals(StrictFailureState.AWAITING_TIMER_SETTLEMENT, evaluate(habit))
        finish(log, at(day.plusDays(1), 0, 1), 180_000)
        storage.reopen()
        assertEquals(StrictFailureState.NOT_FAILED, evaluate(habit))
        assertEquals(listOf(120_000L, 60_000L), db.timeLogDao().getDayAllocations(log.uuid).map { it.durationMillis })
        assertEquals(listOf("start", "stop"), db.timeLogDao().getPendingTimerCommands().map { it.commandType })
    }

    @Test fun normalCancelLeavesThePastDateFailedAndNoFabricatedResult() = runBlocking {
        val habit = habit()
        val log = start(habit, at(day, 23, 58))
        assertEquals(StrictFailureState.AWAITING_TIMER_SETTLEMENT, evaluate(habit))
        db.timeLogDao().deleteTimerAndQueue(log, command(log, 2, "cancel", at(day.plusDays(1), 0, 1)))
        storage.reopen()
        assertEquals(StrictFailureState.FAILED, evaluate(habit))
        assertNull(db.timeLogDao().getById(log.id))
        assertTrue(db.timeLogDao().getDayAllocations(log.uuid).isEmpty())
        assertEquals(listOf("start", "cancel"), db.timeLogDao().getPendingTimerCommands().map { it.commandType })
    }

    @Test fun dstBoundariesAndAbsoluteCeilingDoNotCreateUnlimitedPendingDays() = runBlocking {
        val habit = habit()
        val log = start(habit, at(day, 0, 0, zone = "America/New_York"), "America/New_York")
        assertEquals(StrictFailureState.AWAITING_TIMER_SETTLEMENT, evaluate(habit.copy(targetValue = 1380)))
        assertEquals(StrictFailureState.FAILED, evaluate(habit.copy(targetValue = 1381)))
        assertEquals(StrictFailureState.FAILED, evaluate(habit.copy(targetValue = 61), today = day.plusDays(2)))
        assertNull(db.timeLogDao().getById(log.id)?.endTime)
    }

    @Test fun todayAndNonExecutionDaysDoNotFail() = runBlocking {
        val habit = habit()
        assertEquals(StrictFailureState.NOT_FAILED, evaluate(habit, today = day))
        val scheduled = habit.copy(schedule = HabitSchedule.Weekly(listOf(day.plusDays(1).dayOfWeek.value)))
        assertEquals(StrictFailureState.NOT_FAILED, evaluate(scheduled))
    }

    @Test fun fractionalContributionsAreCombinedBeforeWholeSecondComparison() = runBlocking {
        val habit = habit()
        val earlier = start(habit, at(day.minusDays(1), 12, 0))
        finish(earlier, earlier.startTime + 60_000, 60_000)
        val baseline = start(habit, at(day.minusDays(1), 23, 59, 59))
        finish(baseline, at(day, 0, 0, 59) + 999, 60_999)
        val log = start(habit, at(day, 23, 59, 59) + 999)
        assertEquals(StrictFailureState.AWAITING_TIMER_SETTLEMENT, evaluate(habit, first = day.minusDays(1)))
        val startOfDay = at(day, 0, 0)
        assertEquals(59_999L, db.timeLogDao().getTimerFailureDaySnapshot(habit.id, day.toString(),
            startOfDay, at(day.plusDays(1), 0, 0)).completedMillis)
        assertEquals(1, db.timeLogDao().getTargetMetDayCount(habit.id, 60))
        finish(log, log.startTime + 60_000, 60_000)
        assertEquals(StrictFailureState.NOT_FAILED, evaluate(habit, first = day.minusDays(1)))
        assertEquals(2, db.timeLogDao().getTargetMetDayCount(habit.id, 60))
    }

    @Test fun multipleActiveSessionsAreRejectedAndMissingLegacySegmentsAreNotInvented() = runBlocking {
        val habit = habit()
        val log = start(habit, at(day, 23, 58))
        db.timeLogDao().deleteTimerSegments(log.uuid)
        assertEquals(StrictFailureState.FAILED, evaluate(habit))
        start(habit, at(day, 23, 59))
        val failure = runCatching { evaluate(habit) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("TIMER_FAILURE_MULTIPLE_ACTIVE_SESSIONS", failure?.message)
        assertEquals(2, db.timeLogDao().getUnfinishedTimeLogsForHabit(habit.id).size)
    }

    @Test fun malformedIntervalsAreReadErrorsRatherThanPermissionToHideFailure() = runBlocking {
        val habit = habit()
        val log = start(habit, at(day, 23, 58))
        db.timeLogDao().insertTimerSegment(TimerSegmentEntity(sessionUuid = log.uuid, sequence = 2, startedAt = log.startTime + 1))
        val failure = runCatching { evaluate(habit) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("TIMER_FAILURE_INVALID_SEGMENTS", failure?.message)
        assertNull(db.timeLogDao().getById(log.id)?.endTime)
    }

    @Test fun appAndWidgetSharePendingAndTerminalDecisionsButNotCompletion() = runBlocking {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val habit = habit()
            val baseline = start(habit, at(yesterday.minusDays(1), 12, 0))
            finish(baseline, baseline.startTime + 60_000, 60_000)
            val log = start(habit, at(yesterday, 23, 58))
            val checker = FailureChecker(db.completionDao(), db.timeLogDao())
            assertFalse(checker.hasFailed(habit, yesterday.minusDays(1)))
            assertFalse(WidgetFailureChecker.checkFailure(habit, db))
            assertFalse(HabitStatusCalculator(checker, db.completionDao(), db.timeLogDao()).calculate(habit).completedToday)
            db.timeLogDao().deleteTimerAndQueue(log, command(log, 2, "cancel", at(today, 0, 1)))
            assertTrue(checker.hasFailed(habit, yesterday.minusDays(1)))
            assertTrue(WidgetFailureChecker.checkFailure(habit, db))
            assertFalse(checker.hasFailed(habit.copy(failMode = FailMode.LOOSE), yesterday.minusDays(1)))
            assertFalse(WidgetFailureChecker.checkFailure(habit.copy(failMode = FailMode.LOOSE), db))
            assertFalse(checker.hasFailed(habit.copy(targetCycles = null), yesterday.minusDays(1)))
            assertFalse(checker.hasFailed(habit.copy(isActive = false), yesterday.minusDays(1)))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
