package com.dayforge.widget

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.FailureCheckerUtils
import java.time.Instant
import java.time.ZoneId

/**
 * Utility object for checking habit failure status in widgets.
 *
 * Widgets cannot use Hilt-injected services like FailureChecker,
 * so this object provides a standalone implementation that receives
 * the database as a parameter.
 *
 * Uses FailureCheckerUtils for shared logic with FailureChecker.
 */
object WidgetFailureChecker {

    /**
     * Checks if a habit has failed based on its fail mode.
     *
     * @param habit The habit to check
     * @param database The habit database instance
     * @return true if the habit has failed, false otherwise
     */
    suspend fun checkFailure(
        habit: HabitEntity,
        database: HabitDatabase
    ): Boolean {
        if (!FailureCheckerUtils.shouldCheckFailure(habit)) return false
        if (!FailureCheckerUtils.isStrictMode(habit)) return false

        // Get first completion date
        val firstDateMillis = getFirstCompletionDate(habit, database)
        if (firstDateMillis == null) return false  // Cycle hasn't started

        val firstDate = Instant.ofEpochMilli(firstDateMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

        return FailureCheckerUtils.checkStrictFailure(
            habit,
            firstDate,
            database.completionDao(),
            database.timeLogDao()
        )
    }

    /**
     * Gets the first completion/date for a habit.
     */
    private suspend fun getFirstCompletionDate(
        habit: HabitEntity,
        database: HabitDatabase
    ): Long? {
        return if (habit.habitType == HabitType.TIMER) {
            database.timeLogDao().getFirstTimeLogDate(habit.id)
        } else {
            database.completionDao().getFirstCompletionDate(habit.id)
        }
    }
}