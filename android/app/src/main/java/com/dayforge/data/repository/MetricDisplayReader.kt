package com.dayforge.data.repository

import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.LocalOpaqueMetricAccess
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.model.MetricWithLatestValue
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.coroutines.flow.first

/** Graph-owned finite metrics reader. Preparation is explicit; old complete APIs stay
 * available. All consumers receive values from one actual read phase and fresh footer. */
internal class MetricDisplayReader(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val editor: NextObjectEditor) {
    private suspend fun <T> read(detailMetricId: Long? = null, block: suspend (RecurringDisplayReadScope) -> T): T =
        RecurringDisplayReadScope.withSnapshot(database, tokens, sessions,
            work = AccountSessionCoordinator.Work.METRIC_EDIT_READ,
            prepareCountProof = false, prepareMetricProof = true,
            metricReadSelection = RecurringDisplayReadScope.MetricReadSelection(detailMetricId), block = block)

    private suspend fun window(scope: RecurringDisplayReadScope, metric: MetricEntity, now: Long): MetricWindowSnapshot {
        val start = now - WINDOW_MILLIS
        return scope.metricSnapshot(metric, start, now) ?: MetricWindowSnapshot(metric,
            database.metricLogDao().getLatestLog(metric.id), database.metricLogDao().getLogsInRange(metric.id, start, now), start, now, null)
    }

    suspend fun overview(): List<MetricWithLatestValue> {
        val opaque = tokens.localOpaqueMetricAccess()
        if (opaque != null) return readOpaque(opaque) {
            val now = System.currentTimeMillis(); val start = now - WINDOW_MILLIS
            database.metricDao().getVisibleMetricsOnce().filter { it.isActive }.map { metric ->
                val latest = database.metricLogDao().getLatestLog(metric.id)
                MetricWithLatestValue(metric, latest?.value, latest?.date,
                    database.metricLogDao().getLogsInRange(metric.id, start, now))
            }
        }
        return read { scope ->
            scope.requireMetricRoster()
            val now = System.currentTimeMillis()
            database.metricDao().getVisibleMetricsOnce().filter { it.isActive }.map { metric ->
                val current = window(scope, metric, now)
                MetricWithLatestValue(metric, current.latest?.value, current.latest?.date, current.logs)
            }
        }
    }

    suspend fun detail(selection: DatabaseObjectSelection): MetricDetailSourceReader.Snapshot {
        val opaque = tokens.localOpaqueMetricAccess()
        if (opaque != null) return readOpaque(opaque) {
            check(selection.kind == DatabaseObjectSelection.Kind.METRIC)
            val metric = database.metricDao().getVisibleMetricById(selection.id)
                ?: return@readOpaque MetricDetailSourceReader.Snapshot(null, null, null, emptyList(), emptyList())
            selection.requireMetric(metric)
            // Typed edits retain their original canonical authority requirement.
            check(metric.appearance == null) { "OBJECT_EDIT_ACCESS_DENIED" }
            val history = database.metricLogDao().getAllLogsForMetric(metric.id)
            val latest = database.metricLogDao().getLatestLog(metric.id)
            val links = database.habitMetricLinkDao().getLinksByMetric(metric.id).first().map {
                MetricDetailSourceReader.Link(it, database.habitDao().getHabitById(it.habitId)?.name)
            }
            MetricDetailSourceReader.Snapshot(metric, null, latest, history, links,
                history, history.size.toLong(), latest?.date)
        }
        return read(selection.id) { scope ->
            check(selection.kind == DatabaseObjectSelection.Kind.METRIC) { "DATABASE_EPOCH_SELECTION_CHANGED" }
            val metric = database.metricDao().getVisibleMetricById(selection.id)
                ?: return@read MetricDetailSourceReader.Snapshot(null, null, null, emptyList(), emptyList())
            selection.requireMetric(metric)
            val edit = if (metric.appearance == null) null else editor.metricsInTransaction(listOf(metric.id), scope).single().also {
                check(it.value == metric) { "METRIC_DISPLAY_DEFINITION_CHANGED" }
            }
            // Detail's original chart consumes DESC complete history before its own rolling
            // filter, including future observations and its original floating-point order.
            // Keep that full input until a separate clock/range contract proves an equivalent
            // replacement. The finite empty window requests only the original latest point.
            val current = scope.metricSnapshot(metric, 0L, 0L)
            val latest = if (current == null) database.metricLogDao().getLatestLog(metric.id) else current.latest
            val chartInput = scope.metricHistory(metric) ?: database.metricLogDao().getAllLogsForMetric(metric.id)
            val page = scope.metricPage(metric)
            check(chartInput.size.toLong() == page.totalCount) { "METRIC_DISPLAY_HISTORY_MEMBERS_CHANGED" }
            val links = database.habitMetricLinkDao().getLinksByMetric(metric.id).first().map {
                MetricDetailSourceReader.Link(it, database.habitDao().getHabitById(it.habitId)?.name)
            }
            MetricDetailSourceReader.Snapshot(metric, edit?.authority, latest, page.logs, links,
                trendLogs = chartInput, totalLogCount = page.totalCount, latestLogDate = latest?.date)
        }
    }

    private suspend fun <T> readOpaque(expected: LocalOpaqueMetricAccess, block: suspend () -> T): T {
        check(!database.inTransaction()) { "METRIC_OPAQUE_READ_NESTED" }
        return withContext(Dispatchers.IO) { sessions.exclusive(AccountSessionCoordinator.Work.METRIC_EDIT_READ) {
            val result = database.withTransaction {
                check(tokens.localOpaqueMetricAccess() == expected) { "METRIC_OPAQUE_AUTHORITY_CHANGED" }
                val replica = ReplicaPresentationRepository(database, tokens, sessions)
                check(replica.inspectInTransactionWhileAccountLocked().problem == null) { "COMMAND_REPLICA_NOT_OPEN" }
                val before = totalChanges()
                val value = block()
                check(totalChanges() == before) { "METRIC_OPAQUE_READ_WROTE" }
                check(replica.inspectInTransactionWhileAccountLocked().problem == null &&
                    tokens.localOpaqueMetricAccess() == expected) { "METRIC_OPAQUE_AUTHORITY_CHANGED" }
                currentCoroutineContext().ensureActive()
                value
            }
            currentCoroutineContext().ensureActive()
            result
        } }
    }
    private fun totalChanges(): Long = database.openHelper.writableDatabase.query("SELECT total_changes()").use {
        check(it.moveToFirst()); it.getLong(0)
    }

    companion object { private const val WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1_000 }
}
