package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.SyncEntityStateEntity
import java.time.Instant
import kotlinx.serialization.json.*

/** Legacy physical deletes advance the shadow; staged v5 plan deletes release retained rows only here. */
internal class NextOrdinaryDeletionStore(private val database: HabitDatabase) {
    suspend fun acceptInTransaction(change: SyncV2Change, queueId: Long) {
        check(database.inTransaction())
        require(change.operation == "delete" && change.sequence == 0L && queueId > 0)
        val outbox = database.syncOutboxDao()
        val type = change.entityType
        val uuid = change.entityUuid
        val recordType = when (type) {
            "plan_node" -> "habit"; "metric" -> "metric"; "metric_observation" -> "metric_log"
            "activity_metric_link" -> "link"; else -> error("Not an ordinary delete")
        }
        val queue = requireNotNull(outbox.getById(queueId))
        require(queue.action == "delete" && queue.recordType == recordType && queue.entityUuid == uuid)
        val old = outbox.getState(type, uuid)
        if (old != null) {
            require(old.revision > 0 && old.revision <= change.revision && old.payloadJson != null &&
                old.payloadHash == syncPayloadHash(old.payloadJson))
            val previous = Json.parseToJsonElement(old.payloadJson).jsonObject
            require(Instant.parse(previous.getValue("created_at").jsonPrimitive.content) ==
                Instant.parse(change.payload.getValue("created_at").jsonPrimitive.content))
            if (old.revision == change.revision) require(old.deleted && previous == change.payload)
            if (type == "plan_node") require(previous["node_kind"] == change.payload["node_kind"])
            if (type == "metric_observation") {
                val mutableHeader = setOf("revision", "updated_at", "deleted_at")
                require(previous.filterKeys { it !in mutableHeader } == change.payload.filterKeys { it !in mutableHeader })
            }
        }
        val present = when (type) {
            "plan_node" -> database.habitDao().getHabitByUuid(uuid) != null
            "metric" -> database.metricDao().getMetricByUuid(uuid) != null
            "metric_observation" -> database.metricLogDao().getLogByUuid(uuid) != null
            else -> database.habitMetricLinkDao().getLinkByUuid(uuid) != null
        }
        val retained = type in setOf("plan_node", "activity_metric_link") &&
            NextPlanDeletionStore(database).removeRetainedInTransaction(queue)
        if (present && !retained) require((outbox.getAll() + outbox.getDeadLetters()).any { it.id != queueId &&
            it.recordType == recordType && it.entityUuid == uuid })
        // Do not erase newer unpublished work, cascade again, or recreate an already deleted parent.
        val shadow = SyncEntityStateEntity(type, uuid, change.revision, deleted = true,
            payloadJson = change.payload.toString(), payloadHash = syncPayloadHash(change.payload.toString()))
        outbox.upsertState(shadow)
        check(outbox.getState(type, uuid) == shadow)
    }
}
