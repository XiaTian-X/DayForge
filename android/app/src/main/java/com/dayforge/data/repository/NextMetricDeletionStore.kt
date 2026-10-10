package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.domain.model.isContractUuid
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** New v5 metric/link deletes hide durable original rows, and release them only on an exact ACK. */
internal class NextMetricDeletionStore(private val database: HabitDatabase) {
    private val outbox get() = database.syncOutboxDao()
    private val sql get() = database.openHelper.writableDatabase

    suspend fun stageMetric(metric: MetricEntity) {
        check(database.inTransaction()); require(metric.appearance != null)
        require(database.metricDao().getMetricByUuid(metric.uuid)?.id == metric.id && metric.id > 0)
        val deletes = outbox.getEntityIntents("metric", metric.uuid).filter { it.action == "delete" }
        require(deletes.size <= 1)
        val old = deletes.singleOrNull()
        if (old != null) { require(marker(old)?.getValue("local_id")?.jsonPrimitive?.long == metric.id); return }
        NextRequestSql.requireOutboxEnabled(sql)
        for (link in database.habitMetricLinkDao().getAllLinksForMetric(metric.id)) {
            currentCoroutineContext().ensureActive()
            stageLink(link)
        }
        outbox.insert(SyncOutboxEntity(operationId = UUID.randomUUID().toString(), recordType = "metric", entityUuid = metric.uuid, wireEntityUuid = metric.uuid,
            action = "delete", referenceUuid = PREFIX + buildJsonObject { put("local_id", metric.id) }))
    }

    suspend fun stageLink(link: HabitMetricLinkEntity) {
        check(database.inTransaction()); require(link.id > 0 && listOf(link.uuid, link.habitUuid, link.metricUuid).all(::isContractUuid))
        require(database.habitMetricLinkDao().getLinkByUuid(link.uuid) == link)
        require(database.habitDao().getHabitById(link.habitId)?.let { it.uuid == link.habitUuid && it.appearance != null } == true)
        require(database.metricDao().getMetricById(link.metricId)?.let { it.uuid == link.metricUuid && it.appearance != null } == true)
        // A plan's already staged link delete is the same retained row; do not make a second ID.
        if (outbox.getEntityIntents("link", link.uuid).any { it.action == "delete" }) return
        NextRequestSql.requireOutboxEnabled(sql)
        outbox.insert(SyncOutboxEntity(operationId = UUID.randomUUID().toString(), recordType = "link", entityUuid = link.uuid,
            wireEntityUuid = link.uuid, action = "delete", referenceUuid = PREFIX + buildJsonObject {
                put("local_id", link.id); put("activity_uuid", link.habitUuid); put("metric_uuid", link.metricUuid)
            }))
    }

    suspend fun requireWritable(uuid: String) {
        // Display joins bound origins, but missing/corrupt provenance must never reopen writes.
        check(outbox.getEntityIntents("metric", uuid).none { it.action == "delete" }) { "指标已待删除，请先处理同步结果" }
    }

    suspend fun requireLinkWritable(link: HabitMetricLinkEntity) {
        check(outbox.getEntityIntents("link", link.uuid).none { it.action == "delete" }) { "关联已待删除，请先处理同步结果" }
        requireWritable(link.metricUuid)
        NextPlanDeletionStore(database).requireWritable(link.habitUuid)
    }

    /** A plan-owned retained prompt can still finish; an explicit unlink cannot accept new facts. */
    suspend fun hasExplicitLinkDeletion(uuid: String): Boolean =
        outbox.getEntityIntents("link", uuid).any { marker(it) != null }

    /** Existing facts/links must get their real result first; old prompt drafts remain dismissible. */
    suspend fun requireReady(queue: SyncOutboxEntity) {
        check(database.inTransaction())
        val marker = marker(queue) ?: return
        val pending = outbox.getAll() + outbox.getDeadLetters()
        val prompts = database.completionFollowUpDao().pendingPrompts()
        if (queue.recordType == "link") {
            val link = requireNotNull(database.habitMetricLinkDao().getLinkByUuid(queue.entityUuid))
            require(link.id == marker.getValue("local_id").jsonPrimitive.long &&
                marker.getValue("activity_uuid") == JsonPrimitive(link.habitUuid) && marker.getValue("metric_uuid") == JsonPrimitive(link.metricUuid))
            if (pending.any { it.id != queue.id && it.recordType == "link" && it.entityUuid == link.uuid } ||
                prompts.any { it.activityUuid == link.habitUuid }) rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        } else {
            val metric = requireNotNull(database.metricDao().getMetricByUuid(queue.entityUuid))
            require(metric.id == marker.getValue("local_id").jsonPrimitive.long && metric.appearance != null)
            val links = database.habitMetricLinkDao().getAllLinksForMetric(metric.id)
            val ids = links.map { it.uuid }.toSet()
            if (pending.any { it.id != queue.id && (it.recordType == "metric" && it.entityUuid == metric.uuid ||
                    it.recordType == "metric_log" && it.referenceUuid == metric.uuid || it.recordType == "link" && it.entityUuid in ids) } ||
                prompts.any { p -> links.any { it.habitUuid == p.activityUuid } })
                rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
            require(links.isEmpty()) { "METRIC_DELETE_RETAINED_LINK_MISSING_RESULT" }
        }
    }

    suspend fun removeRetained(queue: SyncOutboxEntity): Boolean {
        check(database.inTransaction()); if (marker(queue) == null) return false
        requireReady(queue)
        val tables = listOf("sync_outbox", "timer_command_outbox")
        val sources = tables.associateWith { NextRequestSql.sources(sql, it) }
        NextRequestSql.requireOutboxEnabled(sql)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        if (queue.recordType == "metric") {
            val row = requireNotNull(database.metricDao().getMetricByUuid(queue.entityUuid))
            database.metricDao().delete(row)
            check(database.metricDao().getMetricByUuid(row.uuid) == null)
        } else {
            val row = requireNotNull(database.habitMetricLinkDao().getLinkByUuid(queue.entityUuid))
            database.habitMetricLinkDao().delete(row)
            check(database.habitMetricLinkDao().getLinkByUuid(row.uuid) == null)
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        check(tables.associateWith { NextRequestSql.sources(sql, it) } == sources)
        return true
    }

    companion object {
        private const val PREFIX = "next-metric-delete:"
        private fun marker(row: SyncOutboxEntity): JsonObject? {
            val value = row.referenceUuid?.takeIf { it.startsWith(PREFIX) } ?: return null
            require(row.recordType in setOf("metric", "link") && row.action == "delete")
            val text = value.removePrefix(PREFIX)
            val body = Json.parseToJsonElement(strictAppearanceJson(text.toByteArray(Charsets.UTF_8), 4096,
                {}, { error("METRIC_DELETE_MARKER_$it") })).jsonObject
            require(body.keys == if (row.recordType == "metric") setOf("local_id") else setOf("local_id", "activity_uuid", "metric_uuid"))
            val id = body.getValue("local_id").jsonPrimitive
            require(!id.isString && id.longOrNull?.let { it > 0 } == true)
            if (row.recordType == "link") for (key in listOf("activity_uuid", "metric_uuid")) {
                val uuid = body.getValue(key).jsonPrimitive; require(uuid.isString && isContractUuid(uuid.content))
            }
            return body
        }
        fun payload(row: SyncOutboxEntity): JsonObject? = marker(row)?.let { buildJsonObject { } }
    }
}
