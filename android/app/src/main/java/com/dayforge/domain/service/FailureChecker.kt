package com.dayforge.domain.service

import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for checking if a target-based habit has failed.
 *
 * Failure modes:
 * - STRICT: Fail immediately when a check-in day is missed (断签即失败)
 * - LOOSE: No failure check - complete target cycles at your own pace (宽松模式)
 *
 * Uses FailureCheckerUtils for shared logic with WidgetFailureChecker.
 */
@Singleton
class FailureChecker @Inject constructor(
    private val completionDao: CompletionDao,
    private val timeLogDao: TimeLogDao,
    private val countHistoryReader: com.dayforge.data.repository.CountHistoryReader? = null,
    private val checkHistoryReader: com.dayforge.data.repository.CheckHistoryReader? = null,
    private val timerHistoryReader: com.dayforge.data.repository.TimerHistoryReader? = null
) {

    /**
     * Checks if a habit has failed based on its fail mode.
     *
     * @param habit The habit to check
     * @param firstCompletionDate The date of first completion (used as cycle start)
     * @return true if the habit has failed, false otherwise
     */
    suspend fun hasFailed(habit: HabitEntity, firstCompletionDate: LocalDate?): Boolean {
        if (!FailureCheckerUtils.shouldCheckFailure(habit)) return false
        if (habit.habitType == com.dayforge.data.model.HabitType.COUNTING && habit.appearance != null)
            return FailureCheckerUtils.countHasFailed(habit, requireNotNull(countHistoryReader) { "COUNT_READER_REQUIRED" }.read(habit))
        if (habit.habitType == com.dayforge.data.model.HabitType.CHECK_IN && habit.appearance != null && habit.completionPolicy == "recurring")
            return FailureCheckerUtils.checkHasFailed(habit, requireNotNull(checkHistoryReader) { "CHECK_READER_REQUIRED" }.read(habit))
        if (habit.habitType == com.dayforge.data.model.HabitType.TIMER && habit.appearance != null)
            return FailureCheckerUtils.timerFailure(habit, requireNotNull(timerHistoryReader) { "TIMER_READER_REQUIRED" }.read(habit)) == StrictFailureState.FAILED

        // No completions yet = cycle hasn't started
        if (firstCompletionDate == null) return false

        return when (habit.failMode) {
            FailMode.STRICT -> FailureCheckerUtils.checkStrictFailure(
                habit, firstCompletionDate, completionDao, timeLogDao
            )
            FailMode.LOOSE -> false // LOOSE 模式不进行失败判定
        }
    }
}
