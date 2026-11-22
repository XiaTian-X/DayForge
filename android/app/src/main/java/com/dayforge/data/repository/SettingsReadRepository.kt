package com.dayforge.data.repository

import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity

/** Original short settings warning reads, from one bound graph. */
internal class SettingsReadRepository(private val habits: HabitDao, private val times: TimeLogDao) {
    suspend fun activeTimer(): Pair<TimeLogEntity, HabitEntity?>? {
        val timer = times.getActiveTimeLog() ?: return null
        return timer to habits.getHabitById(timer.habitId)
    }
}
