package com.dayforge.data.repository

import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.TimeLogDao

/** Exact original overview point/Flow reads, owned by one physical graph. No cache,
 * extra query, transaction, clock sample or authority. Optional DAOs exist only for
 * original fixed/manual constructors whose flows never request those tables. */
internal class OverviewReadRepository(private val times: TimeLogDao,
    private val habits: HabitDao? = null, private val metrics: MetricDao? = null) {
    fun activeTimerChanges() = times.getActiveTimeLogFlow()
    fun topLevelHabits() = requireNotNull(habits).getTopLevelHabits()
    suspend fun habit(id: Long) = requireNotNull(habits).getHabitById(id)
    suspend fun visibleHabit(id: Long) = requireNotNull(habits).getVisibleHabitById(id)
    suspend fun metric(id: Long) = requireNotNull(metrics).getMetricById(id)
    suspend fun completedSeconds(habitId: Long, localDate: String, legacyStart: Long, legacyEnd: Long) =
        times.getCompletedDurationSecondsForDate(habitId, localDate, legacyStart, legacyEnd)
}
