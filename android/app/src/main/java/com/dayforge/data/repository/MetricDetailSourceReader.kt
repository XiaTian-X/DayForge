package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/** One graph-owned detail reader. Caller holds the original source lease for collection.
 * Original edit-claim acquisition precedes the native read transaction; no Room -> account
 * lock inversion or reuse of a proof handle across END. Flow emissions only trigger reads.
 */
internal class MetricDetailSourceReader private constructor(
    private val database: HabitDatabase?, private val repository: MetricRepository?,
    private val metrics: MetricDao, private val logs: MetricLogDao?,
    private val links: HabitMetricLinkDao?, private val habits: HabitDao?,
    private val finite: MetricDisplayReader? = null
) {
    constructor(database: HabitDatabase, repository: MetricRepository, finite: MetricDisplayReader? = null) : this(database, repository,
        database.metricDao(), database.metricLogDao(), database.habitMetricLinkDao(), database.habitDao(), finite)

    /** Fixed/manual compatibility adapters retain the exact original DAO calls/Flow lifetimes.
     * They grant no source scope and cannot enter the graph-owned snapshot path. */
    constructor(metrics: MetricDao, logs: MetricLogDao, links: HabitMetricLinkDao, habits: HabitDao) :
        this(null, null, metrics, logs, links, habits)
    constructor(metrics: MetricDao) : this(null, null, metrics, null, null, null)

    suspend fun visibleMetric(id: Long) = metrics.getVisibleMetricById(id)
    suspend fun metricByName(name: String) = metrics.getMetricByName(name)
    fun observeMetric(id: Long) = metrics.observeMetricById(id)
    fun observeLogs(id: Long) = requireNotNull(logs).getLogsByMetric(id)
    fun observeLinks(id: Long) = requireNotNull(links).getLinksByMetric(id)
    suspend fun latestLog(id: Long) = requireNotNull(logs).getLatestLog(id)
    suspend fun habit(id: Long) = requireNotNull(habits).getHabitById(id)
    suspend fun visibleHabits() = requireNotNull(habits).getVisibleHabitsOnce()

    data class Link(val value: HabitMetricLinkEntity, val habitName: String?)
    data class Snapshot(val metric: MetricEntity?, val authority: ObjectEditAuthority?,
        val latest: MetricLogEntity?, val logs: List<MetricLogEntity>, val links: List<Link>,
        val trendLogs: List<MetricLogEntity>? = null, val totalLogCount: Long? = null, val latestLogDate: Long? = null)

    fun observe(selection: DatabaseObjectSelection): Flow<Snapshot> = requireNotNull(database).invalidationTracker
        .createFlow("metrics", "metric_logs", "habit_metric_links", "habits", "sync_outbox")
        .mapNotNull { finite?.detail(selection) ?: read(selection) }

    suspend fun availableHabits(selection: DatabaseObjectSelection) = requireNotNull(database).withTransaction {
        selection.requireMetric(requireNotNull(visibleMetric(selection.id)) { "DATABASE_EPOCH_SELECTION_CHANGED" })
        visibleHabits().toList()
    }

    private suspend fun read(selection: DatabaseObjectSelection): Snapshot? {
        check(selection.kind == DatabaseObjectSelection.Kind.METRIC) { "DATABASE_EPOCH_SELECTION_CHANGED" }
        val actualDatabase = requireNotNull(database)
        val first = visibleMetric(selection.id)
        first?.let(selection::requireMetric)
        val edit = if (first?.appearance != null) requireNotNull(repository).getMetricForEditing(selection.id) else null
        if (edit != null && edit.value != first) return null // Original stale edit snapshot behavior.
        return actualDatabase.withTransaction {
            val current = visibleMetric(selection.id)
            current?.let(selection::requireMetric)
            if (current != first) return@withTransaction null
            if (current == null) return@withTransaction Snapshot(null, null, null, emptyList(), emptyList())
            val logRows = requireNotNull(logs).getAllLogsForMetric(selection.id)
            val latest = latestLog(selection.id)
            // Same exact visible/active query as the original Flow, within this read Tx.
            val linkRows = observeLinks(selection.id).first().map { link -> Link(link, habit(link.habitId)?.name) }
            Snapshot(current, edit?.authority, latest, logRows.toList(), linkRows.toList())
        }
    }
}
