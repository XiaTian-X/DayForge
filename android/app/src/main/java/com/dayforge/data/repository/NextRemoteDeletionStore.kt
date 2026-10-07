package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.SyncEntityStateEntity
import java.time.Instant
import kotlinx.serialization.json.*

/** Remote tombstones are not local delete ACKs. Never consume or cascade pending intent. */
internal class NextRemoteDeletionStore(private val database: HabitDatabase) {
    suspend fun applyInTransaction(change: SyncV2Change) {
        check(database.inTransaction())
        require(change.sequence > 0 && change.operation == "delete")
        when (change.entityType) {
            "plan_node", "metric" -> NextStructureMapper.validateTombstone(change.payload,
                change.entityType, change.entityUuid, change.revision)
            "metric_observation" -> NextCommonFactMapper.validateOrdinaryFactSnapshot(change)
            "activity_metric_link" -> NextCommonFactMapper.validateLinkSnapshot(change)
            else -> error("Immutable events cannot be deleted")
        }
        val dao = database.syncOutboxDao()
        val old = dao.getState(change.entityType, change.entityUuid)
        if (old != null) {
            require(old.revision > 0 && old.payloadJson != null && old.payloadHash == syncPayloadHash(old.payloadJson))
            val previous = Json.parseToJsonElement(old.payloadJson).jsonObject
            require(Instant.parse(previous.getValue("created_at").jsonPrimitive.content) ==
                Instant.parse(change.payload.getValue("created_at").jsonPrimitive.content))
            if (change.entityType == "plan_node") require(previous["node_kind"] == change.payload["node_kind"])
            if (change.entityType == "metric_observation") {
                val header = setOf("revision", "updated_at", "deleted_at")
                require(previous.filterKeys { it !in header } == change.payload.filterKeys { it !in header })
            }
            if (old.revision > change.revision) return
            if (old.revision == change.revision) require(old.deleted && previous == change.payload)
        }
        val id = change.entityUuid
        val type = change.entityType
        val pending = dao.getAll() + dao.getDeadLetters()
        val record = when (type) { "plan_node" -> "habit"; "metric" -> "metric";
            "metric_observation" -> "metric_log"; else -> "link" }
        require(pending.none { it.recordType == record && (it.entityUuid == id || it.wireEntityUuid == id) }) {
            "SYNC_LOCAL_WORK_REQUIRES_RESOLUTION"
        }
        require(database.syncConflictDao().getUnresolved().none { it.entityType == type &&
            (it.localEntityUuid == id || it.wireEntityUuid == id) }) { "SYNC_LOCAL_WORK_REQUIRES_RESOLUTION" }
        if (type == "plan_node" || type == "metric") {
            require(pending.none { it.referenceUuid == id }) { "SYNC_LOCAL_WORK_REQUIRES_RESOLUTION" }
            val metricLinks = if (type == "metric") database.metricDao().getMetricByUuid(id)?.let {
                database.habitMetricLinkDao().getAllLinksForMetric(it.id)
            }.orEmpty() else emptyList()
            require(metricLinks.none { link -> pending.any { it.recordType == "link" && it.entityUuid == link.uuid } }) {
                "SYNC_LOCAL_WORK_REQUIRES_RESOLUTION"
            }
            require(database.completionFollowUpDao().pendingPrompts().none { prompt ->
                prompt.activityUuid == id || type == "metric" && metricLinks
                        .any { database.habitDao().getHabitById(it.habitId)?.uuid == prompt.activityUuid }
            }) { "SYNC_METRIC_PROMPT_REQUIRES_RESOLUTION" }
        }
        val sql = database.openHelper.writableDatabase
        NextRequestSql.requireOutboxEnabled(sql)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        when (type) {
            "plan_node" -> database.habitDao().getHabitByUuid(id)?.let { habit ->
                // The server logs each child cascade/detach before its parent tombstone.
                require(database.habitDao().getChildrenByParentUuidOnce(id).isEmpty()) { "SYNC_CHILD_WORK_REQUIRES_RESOLUTION" }
                require(database.timeLogDao().getAllTimeLogsForHabit(habit.id).none { it.endTime == null } &&
                    (database.timeLogDao().getPendingTimerCommands(Int.MAX_VALUE) +
                        database.timeLogDao().getRejectedTimerCommands()).none { command ->
                        command.activityUuid == id || database.timeLogDao().getTimeLogByUuid(command.sessionUuid)?.habitId == habit.id
                    }) { "SYNC_TIMER_WORK_REQUIRES_RESOLUTION" }
                database.habitDao().delete(habit)
                check(database.habitDao().getHabitByUuid(id) == null)
            }
            "metric" -> database.metricDao().getMetricByUuid(id)?.let {
                database.metricDao().delete(it); check(database.metricDao().getMetricByUuid(id) == null)
            }
            "metric_observation" -> database.metricLogDao().getLogByUuid(id)?.let {
                database.metricLogDao().delete(it); check(database.metricLogDao().getLogByUuid(id) == null)
            }
            else -> database.habitMetricLinkDao().getLinkByUuid(id)?.let {
                database.habitMetricLinkDao().delete(it); check(database.habitMetricLinkDao().getLinkByUuid(id) == null)
            }
        }
        val shadow = SyncEntityStateEntity(type, id, change.revision, deleted = true,
            payloadJson = change.payload.toString(), payloadHash = syncPayloadHash(change.payload.toString()))
        dao.upsertState(shadow)
        check(dao.getState(type, id) == shadow)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
    }
}
