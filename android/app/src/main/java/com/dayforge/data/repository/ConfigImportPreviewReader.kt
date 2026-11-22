package com.dayforge.data.repository

import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao

/** Original legacy import preview reads, owned by the same database graph as its importer. */
internal class ConfigImportPreviewReader(private val habits: HabitDao, private val metrics: MetricDao,
    private val times: TimeLogDao, private val completions: CompletionDao, private val metricLogs: MetricLogDao) {
    internal data class Counts(val habits: Int, val metrics: Int, val completions: Int, val times: Int, val metricLogs: Int)
    suspend fun counts() = Counts(habits.getAllHabitsOnce().size, metrics.getAllMetricsOnce().size,
        completions.countAll(), times.countAll(), metricLogs.countAll())
}
