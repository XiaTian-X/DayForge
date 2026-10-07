package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.LinkedMetricSnapshot
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.service.StructuralEditGuard
import kotlinx.coroutines.flow.Flow
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
    private val structuralEditGuard: StructuralEditGuard? = null,
    private val nextObjectEditor: NextObjectEditor? = null,
    private val nextObjectCreator: NextObjectCreator? = null
) {
    fun observeActiveMetrics(): Flow<List<MetricEntity>> = metricDao.getAllActiveMetrics()

    fun observeLatestMetricLog(): Flow<MetricLogEntity?> = metricLogDao.getLatestLogFlow()

    suspend fun getLatestLog(metricId: Long): MetricLogEntity? =
        metricLogDao.getLatestLog(metricId)

    suspend fun getMetricForEditing(id: Long): ObjectEditSnapshot<MetricEntity> =
        nextObjectEditor?.metric(id) ?: ObjectEditSnapshot(metricDao.getMetricById(id), null)

    suspend fun getLogsInRange(
        metricId: Long,
        startInclusive: Long,
        endExclusive: Long
    ): List<MetricLogEntity> =
        metricLogDao.getLogsInRange(metricId, startInclusive, endExclusive)

    fun observeLinkedMetricSnapshots(): Flow<List<LinkedMetricSnapshot>> =
        linkDao.observeLinkedMetricSnapshots()

    suspend fun getLinkedMetricSnapshots(habitId: Long): List<LinkedMetricSnapshot> =
        linkDao.getLinkedMetricSnapshots(habitId)

    suspend fun createMetric(
        metric: MetricEntity,
        selectedHabitIds: Set<Long> = emptySet(),
        creationAuthority: ObjectCreationAuthority? = null
    ): Long {
        if (creationAuthority == null) structuralEditGuard?.requireAllowed()
        suspend fun commit(saved: MetricEntity): Long {
            validateTargets(saved)
            ensureNameAvailable(saved.name)
            require(metricDao.getMetricByUuid(saved.uuid) == null) { "OBJECT_CREATE_ID_REUSED" }
            val metricId = metricDao.insert(saved)
            selectedHabitIds.forEach { habitId ->
                val habit = requireNotNull(habitDao.getHabitById(habitId)) {
                    "Selected habit no longer exists: $habitId"
                }
                linkDao.insertOrIgnore(
                    HabitMetricLinkEntity(
                        habitId = habitId,
                        habitUuid = habit.uuid,
                        metricId = metricId,
                        metricUuid = saved.uuid,
                        coefficient = 1.0,
                        showInHabitDetail = true,
                        promptOnComplete = true
                    )
                )
            }
            return metricId
        }
        return if (creationAuthority != null) requireNotNull(nextObjectCreator).metric(metric, creationAuthority, ::commit)
            else {
                require(metric.appearance == null || nextObjectCreator == null) { "OBJECT_CREATE_TICKET_REQUIRED" }
                database.withTransaction { commit(metric) }
            }
    }

    suspend fun updateMetric(metric: MetricEntity, editAuthority: ObjectEditAuthority? = null) {
        structuralEditGuard?.requireAllowed()
        suspend fun commit(saved: MetricEntity) {
            validateTargets(saved)
            val previous = requireNotNull(metricDao.getMetricById(metric.id)) {
                "Metric no longer exists: ${metric.id}"
            }
            check((previous.appearance == null) == (saved.appearance == null)) { "OBJECT_EDIT_REQUIRES_COORDINATED_SWITCH" }
            val duplicate = metricDao.getMetricByName(saved.name)
            if (duplicate != null && duplicate.id != metric.id) {
                throw DuplicateMetricNameException(saved.name)
            }
            metricDao.update(saved)
        }
        if (metric.appearance != null && nextObjectEditor != null) nextObjectEditor
            .editMetric(metric, requireNotNull(editAuthority) { "OBJECT_EDIT_TICKET_REQUIRED" }, ::commit)
        else database.withTransaction { commit(metric.copy(updatedAt = System.currentTimeMillis())) }
    }

    suspend fun updateAggregationType(metricId: Long, aggregationType: String) {
        val metric = requireNotNull(metricDao.getMetricById(metricId))
        mutate(listOf(metric)) { metricDao.updateAggregationType(metricId, aggregationType) }
    }

    suspend fun deleteMetric(metric: MetricEntity, authority: ObjectEditAuthority? = null) {
        mutate(listOf(metric), authority) { metricDao.delete(metric) }
    }

    suspend fun unlinkHabit(linkId: Long, expectedLinkUuid: String? = null, authority: ObjectEditAuthority? = null) {
        val link = linkDao.getById(linkId) ?: return
        if (expectedLinkUuid != null) check(link.uuid == expectedLinkUuid) { "OBJECT_WRITE_LINK_CHANGED" }
        val metric = requireNotNull(metricDao.getMetricById(link.metricId))
        mutate(listOf(metric), authority) {
            check(linkDao.getById(linkId) == link) { "OBJECT_WRITE_LINK_CHANGED" }
            linkDao.delete(link)
        }
    }

    suspend fun linkHabits(metric: MetricEntity, habitIds: Set<Long>, authority: ObjectEditAuthority? = null) {
        mutate(listOf(metric), authority) {
            habitIds.forEach { habitId ->
                val habit = requireNotNull(habitDao.getHabitById(habitId)) {
                    "Selected habit no longer exists: $habitId"
                }
                check((habit.appearance == null) == (metric.appearance == null)) { "OBJECT_WRITE_MIXED_PROTOCOL" }
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
        recordedAt: Long = System.currentTimeMillis(),
        authority: ObjectEditAuthority? = null,
        expectedMetricUuid: String? = null
    ): Long = recordValues(
        values = listOf(MetricValueDraft(metricId, value, note)),
        recordedAt = recordedAt, authority = authority, expectedMetricUuid = expectedMetricUuid
    ).single()

    suspend fun recordValues(
        values: List<MetricValueDraft>,
        recordedAt: Long = System.currentTimeMillis(),
        authority: ObjectEditAuthority? = null,
        expectedMetricUuid: String? = null
    ): List<Long> {
        val capturedZone = java.time.ZoneId.systemDefault().id
        if (values.isEmpty()) return emptyList()
        val targets = values.map { requireNotNull(metricDao.getMetricById(it.metricId)) {
            "Metric no longer exists: ${it.metricId}"
        } }.distinctBy { it.id }
        if (expectedMetricUuid != null) check(targets.size == 1 && targets.single().uuid == expectedMetricUuid) {
            "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED"
        }
        return mutate(targets, authority, structural = false) {
            val logs = values.map { input ->
                require(input.value.isFinite()) { "Metric value must be finite" }
                val metric = requireNotNull(metricDao.getMetricById(input.metricId)) {
                    "Metric no longer exists: ${input.metricId}"
                }
                MetricLogEntity(
                    metricId = input.metricId,
                    date = recordedAt,
                    value = input.value,
                    unit = metric.unit,
                    note = input.note,
                    recordedTimezone = capturedZone
                )
            }
            if (logs.isEmpty()) emptyList() else metricLogDao.insertAll(logs)
        }
    }

    private suspend fun <T> mutate(metrics: List<MetricEntity>, authority: ObjectEditAuthority? = null,
        structural: Boolean = true, commit: suspend () -> T): T {
        if (metrics.any { it.appearance != null } && nextObjectEditor != null) {
            return nextObjectEditor.mutateMetrics(metrics, authority, commit)
        }
        check(authority == null) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        if (structural) structuralEditGuard?.requireAllowed()
        return database.withTransaction { commit() }
    }

    private fun validateTargets(metric: MetricEntity) {
        require(metric.targetValue?.isFinite() != false && metric.targetValueUpper?.isFinite() != false) {
            "Metric targets must be finite"
        }
        if (metric.targetDirection == "range") {
            val lower = requireNotNull(metric.targetValue) { "Range targets require a lower value" }
            val upper = requireNotNull(metric.targetValueUpper) { "Range targets require an upper value" }
            require(upper >= lower) { "Range upper target must not be less than lower target" }
        }
    }

    private suspend fun ensureNameAvailable(name: String) {
        if (metricDao.getMetricByName(name) != null) {
            throw DuplicateMetricNameException(name)
        }
    }
}
