package com.dayforge.data.repository

import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.MetricDao

/** Original creation option reads from one already selected graph. No cache or authority. */
internal class CreationReadRepository(private val habits: HabitDao, private val metrics: MetricDao) {
    fun topLevelHabits() = habits.getTopLevelHabits()
    fun activeMetrics() = metrics.getAllActiveMetrics()
    fun habits() = habits.getAllHabits()
    suspend fun metricByName(name: String) = metrics.getMetricByName(name)
}
