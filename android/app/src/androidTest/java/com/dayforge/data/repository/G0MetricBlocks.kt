package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.ObjectAppearance

/** STRICT G0 blocks for one metric definition. Every borrow captures a fresh Room snapshot.
 * These are dependency/compute units, NOT trusted runtime certificates: SQL is not sealed for
 * the domain and full structural reads deliberately remain. No schema or production cache.
 */
internal class G0MetricBlocks(private val database: HabitDatabase, val metricUuid: String) {
    data class Request(val queueId: Long, val operationId: String)
    data class Definition(val name: String, val description: String, val unit: String, val decimals: Int,
        val aggregation: String, val direction: String?, val lower: Double?, val upper: Double?,
        val active: Boolean, val appearance: ObjectAppearance?, val icon: Int, val color: String)
    data class SyncStatus(val pending: Int, val rejected: Int, val revision: Long?)
    data class Computed<T>(val revision: Long, val value: T)
    data class View(val definition: Computed<Definition>, val sync: Computed<SyncStatus>)
    data class Snapshot(val plans: List<HabitEntity>, val metrics: List<MetricEntity>,
        val pending: List<SyncOutboxEntity>, val conflicts: List<SyncConflictEntity>,
        val shadow: SyncEntityStateEntity?, val rawRequests: Map<String, String?>) {
        fun input(uuid: String, queueId: Long) = G0MetricMergePlan.Input(
            metrics.singleOrNull { it.uuid == uuid }, shadow,
            pending.any { it.id != queueId && it.recordType == "metric" && (it.entityUuid == uuid || it.wireEntityUuid == uuid) },
            conflicts.any { it.recordType == "metric" && (it.localEntityUuid == uuid || it.wireEntityUuid == uuid) },
            metrics.groupBy { it.name }.mapValues { (_, rows) -> rows.map { it.uuid }.toSet() })
    }
    class Prepared internal constructor(val request: Request, private val uuid: String,
        private val before: Snapshot,
        val plan: G0MetricMergePlan.Prepared) {
        fun revalidate(actual: Snapshot) {
            plan.revalidate(actual.input(uuid, request.queueId))
            fun pending(s: Snapshot) = s.pending.filter { it.recordType == "metric" && (it.entityUuid == uuid || it.wireEntityUuid == uuid) }
            fun conflicts(s: Snapshot) = s.conflicts.filter { it.recordType == "metric" && (it.localEntityUuid == uuid || it.wireEntityUuid == uuid) }
            require(pending(before) == pending(actual) && conflicts(before) == conflicts(actual) &&
                before.rawRequests == actual.rawRequests) { "G0_BLOCK_REQUEST_DEPENDENCIES_CHANGED" }

        }
    }

    var captures = 0L; private set
    var capturedRows = 0L; private set
    private var expectedMetric: MetricEntity? = null
    private var merged: Snapshot? = null
    private var acceptedQueue: Long? = null
    private var staged: View? = null
    private var stagedSnapshot: Snapshot? = null
    private var published: View? = null
    private var publishedSnapshot: Snapshot? = null
    private var poisoned = false
    private var busy = false

    fun begin() {
        check(!poisoned && !busy) { "G0_BLOCK_COLD_RECOVERY_REQUIRED" }
        busy = true; staged = null; stagedSnapshot = null; expectedMetric = null; merged = null; acceptedQueue = null
    }
    fun discard() { poisoned = true; busy = false; staged = null; stagedSnapshot = null; published = null; publishedSnapshot = null }
    fun committed() {
        check(busy && !poisoned && !database.inTransaction())
        published = requireNotNull(staged); publishedSnapshot = requireNotNull(stagedSnapshot)
        staged = null; stagedSnapshot = null; busy = false
    }
    fun publishedForTest(): View? = published

    suspend fun capture(request: Request? = null): Snapshot {
        val trace = G0PhaseTrace.current.get()
        return if (trace == null) captureFresh(request) else trace.stage("block_capture") { captureFresh(request) }
    }
    private suspend fun captureFresh(request: Request?): Snapshot {
        check(database.inTransaction())
        val plans = database.habitDao().getAllHabitsOnce().sortedBy { it.id }
        val metrics = database.metricDao().getAllMetricsOnce().sortedBy { it.id }
        val pending = (database.syncOutboxDao().getAll() + database.syncOutboxDao().getDeadLetters()).sortedBy { it.id }
        val conflicts = database.syncConflictDao().getUnresolved().sortedBy { it.id }
        check(plans.map { it.uuid }.distinct().size == plans.size && metrics.map { it.uuid }.distinct().size == metrics.size)
        check(plans.map { it.name }.distinct().size == plans.size && metrics.map { it.name }.distinct().size == metrics.size)
        val byId = plans.associateBy { it.uuid }
        for (child in plans) child.parentHabitId?.let { id ->
            val parent = byId[id]
            check(child.habitType != HabitType.GOAL && parent != null && parent.uuid != child.uuid &&
                parent.habitType == HabitType.GOAL && parent.parentHabitId == null &&
                database.syncOutboxDao().getState("plan_node", id)?.deleted != true)
        }
        val sql = database.openHelper.writableDatabase
        val raw = linkedMapOf<String, String?>()
        for (row in pending.filter { it.recordType == "metric" && (it.entityUuid == metricUuid || it.wireEntityUuid == metricUuid) })
            raw["queue:${row.id}"] = requireNotNull(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(row.id)))
        if (request != null) {
            for (table in listOf("next_request_origins", "next_transmissions", "next_acceptances"))
                raw[table] = NextRequestSql.rowHash(sql, table, "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, request.operationId))
            raw["accepted-queue"] = NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(request.queueId))
            raw["causal"] = NextRequestSql.rowHash(sql, "next_structural_dependencies", "operationId=?", arrayOf(request.operationId))
        }
        raw["shadow"] = NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf("metric", metricUuid))
        captures++; capturedRows += plans.size + metrics.size + pending.size + conflicts.size
        return Snapshot(plans, metrics, pending, conflicts, database.syncOutboxDao().getState("metric", metricUuid), raw.toMap())
    }

    suspend fun prepare(change: SyncV2Change, request: Request, receivedAt: Long): Prepared {
        require(change.entityUuid == metricUuid)
        val before = capture(request)
        val plan = G0MetricMergePlan.freeze(change, before.input(metricUuid, request.queueId), receivedAt)
        // The pure plan already records the name range of the actual output, if any.
        // Ignored old revisions must not depend on an unused incoming name.
        return Prepared(request, metricUuid, before, plan)
    }

    suspend fun merge(change: SyncV2Change, queueId: Long) {
        check(busy)
        val queue = requireNotNull(database.syncOutboxDao().getById(queueId))
        val prepared = prepare(change, Request(queueId, queue.operationId), System.currentTimeMillis())
        // STRICT revalidation: a trusted incremental capture is deliberately NOT assumed.
        val beforeApply = capture(prepared.request)
        prepared.revalidate(beforeApply)
        val sql = database.openHelper.writableDatabase
        NextRequestSql.requireOutboxEnabled(sql)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        prepared.plan.output.metric?.let { row ->
            val id = database.metricDao().upsert(row)
            check(id > 0 && database.metricDao().getMetricById(id) == row.copy(id = id))
        }
        prepared.plan.output.shadow?.let { row ->
            database.syncOutboxDao().upsertState(row)
            check(database.syncOutboxDao().getState("metric", metricUuid) == row)
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        expectedMetric = database.metricDao().getMetricByUuid(metricUuid)
        val after = capture()
        val update = prepared.plan.output.metric
        val expectedMetrics = if (update == null) beforeApply.metrics else
            (beforeApply.metrics.filterNot { it.uuid == metricUuid } + requireNotNull(expectedMetric)).sortedBy { it.id }
        check(expectedMetric == (update ?: beforeApply.metrics.singleOrNull { it.uuid == metricUuid }) &&
            after.metrics == expectedMetrics && after.plans == beforeApply.plans && after.pending == beforeApply.pending &&
            after.conflicts == beforeApply.conflicts && after.shadow == (prepared.plan.output.shadow ?: beforeApply.shadow)) {
            "G0_BLOCK_UNDECLARED_MERGE_EFFECT"
        }
        merged = after; acceptedQueue = queueId
    }

    suspend fun expectLocalMetric() { expectedMetric = requireNotNull(database.metricDao().getMetricByUuid(metricUuid)) }
    suspend fun stageLocal() {
        check(busy)
        val snapshot = capture()
        check(snapshot.metrics.singleOrNull { it.uuid == metricUuid } == requireNotNull(expectedMetric)) { "G0_BLOCK_LATE_METRIC_WRITE" }
        stagedSnapshot = snapshot
        staged = compute(snapshot)
    }
    suspend fun verifyStaged() {
        check(capture() == requireNotNull(stagedSnapshot)) { "G0_BLOCK_FINAL_DEPENDENCIES_CHANGED" }
    }
    suspend fun stageAck() {
        check(busy)
        val snapshot = capture()
        merged?.let { before ->
            check(snapshot.metrics == before.metrics && snapshot.plans == before.plans && snapshot.conflicts == before.conflicts &&
                snapshot.shadow == before.shadow && snapshot.pending == before.pending.filterNot { it.id == acceptedQueue }) {
                "G0_BLOCK_LATE_DEPENDENCY_WRITE"
            }
        }
        stagedSnapshot = snapshot
        staged = compute(snapshot)
    }
    suspend fun readStrict(): View {
        check(!busy && !poisoned) { "G0_BLOCK_COLD_RECOVERY_REQUIRED" }
        val before = requireNotNull(publishedSnapshot) { "G0_BLOCK_REBUILD_REQUIRED" }
        val actual = capture()
        fun related(s: Snapshot) = s.pending.filter { it.recordType == "metric" && (it.entityUuid == metricUuid || it.wireEntityUuid == metricUuid) }
        // Unobserved target mutations cannot silently become trusted computed results. Until
        // ALL writers are closed, even a warm read uses a fresh STRICT capture and fails closed.
        check(before.metrics.singleOrNull { it.uuid == metricUuid } == actual.metrics.singleOrNull { it.uuid == metricUuid } &&
            before.shadow == actual.shadow && related(before) == related(actual) && before.rawRequests == actual.rawRequests &&
            before.conflicts.filter { it.recordType == "metric" && (it.localEntityUuid == metricUuid || it.wireEntityUuid == metricUuid) } ==
            actual.conflicts.filter { it.recordType == "metric" && (it.localEntityUuid == metricUuid || it.wireEntityUuid == metricUuid) }) { "G0_BLOCK_REBUILD_REQUIRED" }
        return compute(actual)
    }
    private fun compute(snapshot: Snapshot): View {
        val row = requireNotNull(snapshot.metrics.singleOrNull { it.uuid == metricUuid })
        val definition = Definition(row.name, row.description, row.unit, row.decimalPlaces, row.aggregationType,
            row.targetDirection, row.targetValue, row.targetValueUpper, row.isActive, row.appearance, row.iconResId, row.colorHex)
        val related = snapshot.pending.filter { it.recordType == "metric" && (it.entityUuid == metricUuid || it.wireEntityUuid == metricUuid) }
        val status = SyncStatus(related.count { it.deadLetteredAt == null }, related.count { it.deadLetteredAt != null }, snapshot.shadow?.revision)
        fun <T> reuse(old: Computed<T>?, value: T): Computed<T> = if (old?.value == value) old else Computed((old?.revision ?: 0) + 1, value)
        return View(reuse(published?.definition, definition), reuse(published?.sync, status))
    }
}
