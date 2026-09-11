package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.service.StructuralEditGuard
import javax.inject.Inject
import javax.inject.Singleton

class DuplicateMetricNameException(name: String) :
    IllegalArgumentException("Metric name already exists: $name")

data class MetricValueDraft(
    val metricId: Long,
    val value: Double,
    val note: String = ""
)

/**
 * Owns local metric mutations.
 *
 * Room triggers append sync operations for every syncable table mutation. Multi-row user
 * actions are wrapped here so their business rows and every generated outbox row either all
 * commit or all roll back.
 */
@Singleton
class MetricRepository @Inject constructor(
    private val database: HabitDatabase,
    private val metricDao: MetricDao,
    private val metricLogDao: MetricLogDao,
    private val habitDao: HabitDao,
    private val linkDao: HabitMetricLinkDao,
    private val structuralEditGuard: StructuralEditGuard? = null
) {
    suspend fun createMetric(
        metric: MetricEntity,
        selectedHabitIds: Set<Long> = emptySet()
    ): Long {
        structuralEditGuard?.requireAllowed()
        return database.withTransaction {
            ensureNameAvailable(metric.name)
            val metricId = metricDao.insert(metric)
            selectedHabitIds.forEach { habitId ->
                val habit = requireNotNull(habitDao.getHabitById(habitId)) {
                    "Selected habit no longer exists: $habitId"
                }
                linkDao.insertOrIgnore(
                    HabitMetricLinkEntity(
                        habitId = habitId,
                        habitUuid = habit.uuid,
                        metricId = metricId,
                        metricUuid = metric.uuid,
                        coefficient = 1.0,
                        showInHabitDetail = true,
                        promptOnComplete = true
                    )
                )
            }
            metricId
        }
    }

    suspend fun updateMetric(metric: MetricEntity) {
        structuralEditGuard?.requireAllowed()
        database.withTransaction {
            requireNotNull(metricDao.getMetricById(metric.id)) {
                "Metric no longer exists: ${metric.id}"
            }
            val duplicate = metricDao.getMetricByName(metric.name)
            if (duplicate != null && duplicate.id != metric.id) {
                throw DuplicateMetricNameException(metric.name)
            }
            metricDao.update(metric.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun updateAggregationType(metricId: Long, aggregationType: String) {
        structuralEditGuard?.requireAllowed()
        metricDao.updateAggregationType(metricId, aggregationType)
    }

    suspend fun deleteMetric(metric: MetricEntity) {
        structuralEditGuard?.requireAllowed()
        metricDao.delete(metric)
    }

    suspend fun unlinkHabit(linkId: Long) {
        structuralEditGuard?.requireAllowed()
        database.withTransaction {
            linkDao.getById(linkId)?.let { linkDao.delete(it) }
        }
    }

    suspend fun linkHabits(metric: MetricEntity, habitIds: Set<Long>) {
        structuralEditGuard?.requireAllowed()
        database.withTransaction {
            habitIds.forEach { habitId ->
                val habit = requireNotNull(habitDao.getHabitById(habitId)) {
                    "Selected habit no longer exists: $habitId"
                }
                linkDao.insertOrIgnore(
                    HabitMetricLinkEntity(
                        habitId = habitId,
                        habitUuid = habit.uuid,
                        metricId = metric.id,
                        metricUuid = metric.uuid,
                        showInHabitDetail = true,
                        promptOnComplete = true
                    )
                )
            }
        }
    }

    suspend fun recordValue(
        metricId: Long,
        value: Double,
        note: String,
        recordedAt: Long = System.currentTimeMillis()
    ): Long = recordValues(
        values = listOf(MetricValueDraft(metricId, value, note)),
        recordedAt = recordedAt
    ).single()

    suspend fun recordValues(
        values: List<MetricValueDraft>,
        recordedAt: Long = System.currentTimeMillis()
    ): List<Long> = database.withTransaction {
        val logs = values.map { input ->
            val metric = requireNotNull(metricDao.getMetricById(input.metricId)) {
                "Metric no longer exists: ${input.metricId}"
            }
            MetricLogEntity(
                metricId = input.metricId,
                date = recordedAt,
                value = input.value,
                unit = metric.unit,
                note = input.note
            )
        }
        if (logs.isEmpty()) emptyList() else metricLogDao.insertAll(logs)
    }

    private suspend fun ensureNameAvailable(name: String) {
        if (metricDao.getMetricByName(name) != null) {
            throw DuplicateMetricNameException(name)
        }
    }
}
