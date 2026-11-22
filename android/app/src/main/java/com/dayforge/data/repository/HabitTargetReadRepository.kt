package com.dayforge.data.repository

import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao

/** Exact original Detail/Edit reads from one pinned graph. No cache, authority, transaction
 * or handle switch is introduced here; the caller's source scope and original business
 * repositories still govern eligibility. Optional metric history is only for the fixed
 * editor construction, which never requests goal history. */
internal class HabitTargetReadRepository(
    private val habits: HabitDao,
    private val times: TimeLogDao,
    private val completions: CompletionDao,
    private val metrics: MetricDao,
    private val links: HabitMetricLinkDao,
    private val metricLogs: MetricLogDao? = null
) {
    suspend fun habit(id: Long) = habits.getHabitById(id)
    suspend fun habitByUuid(uuid: String) = habits.getHabitByUuid(uuid)
    fun topLevelHabits() = habits.getTopLevelHabits()
    fun children(parentUuid: String) = habits.getChildrenByParentUuid(parentUuid)
    fun activeMetrics() = metrics.getAllActiveMetrics()
    suspend fun visibleMetric(id: Long) = metrics.getVisibleMetricById(id)
    suspend fun visibleLinks(habitId: Long) = links.getVisibleLinksForHabit(habitId)
    suspend fun timeDayCount(habitId: Long) = times.getDistinctDayCount(habitId)
    suspend fun completionDayCount(habitId: Long) = completions.getDistinctDayCount(habitId)
    suspend fun timeHistory(habitId: Long) = times.getAllTimeLogsForHabit(habitId)
    suspend fun activeTimer(habitId: Long) = times.getActiveTimeLogForHabit(habitId)
    suspend fun hasTimeHistory(habitId: Long) = times.hasTimeLogs(habitId)
    suspend fun hasCompletions(habitId: Long) = completions.hasCompletions(habitId)
    fun metricRange(metricId: Long, start: Long, end: Long) = requireNotNull(metricLogs).getLogsInRangeSync(metricId, start, end)
    suspend fun metricHistory(metricId: Long) = requireNotNull(metricLogs).getAllLogsForMetric(metricId)
}
