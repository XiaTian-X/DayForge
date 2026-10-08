package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.isContractUuid
import java.util.UUID
import kotlinx.serialization.json.*

/** A NEW original delete is the durable marker; business/fact rows survive until its ACK. */
internal class NextPlanDeletionStore(private val database: HabitDatabase) {
    private val habits = database.habitDao()
    private val outbox = database.syncOutboxDao()

    suspend fun stage(habit: HabitEntity, childPolicy: String?, childUuids: List<String> = emptyList()) {
        check(database.inTransaction())
        require(habit.appearance != null && habit.planMetadata != null)
        require((habit.habitType == HabitType.GOAL) == (childPolicy != null))
        require(childPolicy == null || childPolicy in setOf("cascade_children", "detach_children"))
        require(childUuids.distinct() == childUuids && childUuids.all(::isContractUuid) &&
            (habit.habitType == HabitType.GOAL || childUuids.isEmpty()))
        requireWritable(habit.uuid)
        check(database.timeLogDao().getAllTimeLogsForHabit(habit.id).none { it.endTime == null }) {
            "请先结束或取消正在进行的计时，再删除习惯或目标"
        }
        val reference = PREFIX + buildJsonObject {
            put("node_kind", if (habit.habitType == HabitType.GOAL) "goal" else "activity")
            put("name", habit.name)
            put("child_policy", childPolicy?.let(::JsonPrimitive) ?: JsonNull)
            put("child_uuids", JsonArray(childUuids.map(::JsonPrimitive)))
        }
        val sql = database.openHelper.writableDatabase
        NextRequestSql.requireOutboxEnabled(sql)
        // Release the unique user-facing name without producing a fictitious structure edit.
        // The original name remains bound to the delete's immutable source, never on the wire.
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        habits.update(habit.copy(name = retainedName(habit.uuid)))
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        for (link in database.habitMetricLinkDao().getAllLinksForHabit(habit.id)) {
            if (outbox.getEntityIntents("link", link.uuid).none { it.action == "delete" }) {
                outbox.insert(SyncOutboxEntity(operationId = UUID.randomUUID().toString(), recordType = "link",
                    entityUuid = link.uuid, wireEntityUuid = link.uuid, action = "delete", referenceUuid = habit.uuid))
            }
        }
        outbox.insert(SyncOutboxEntity(operationId = UUID.randomUUID().toString(), recordType = "habit",
            entityUuid = habit.uuid, wireEntityUuid = habit.uuid, action = "delete", referenceUuid = reference))
    }

    suspend fun requireWritable(uuid: String) {
        check(!habits.hasPendingNextDeletion(uuid)) { "对象已待删除，请先处理同步结果" }
    }

    /** Also used by direct send/accept, not just runtime scheduling. Rejected work still blocks. */
    suspend fun requireReady(queue: SyncOutboxEntity) {
        check(database.inTransaction())
        if (queue.recordType == "link" && queue.action == "delete") {
            val link = database.habitMetricLinkDao().getLinkByUuid(queue.entityUuid) ?: return
            val activity = habits.getHabitById(link.habitId) ?: return
            if (habits.hasPendingNextDeletion(activity.uuid) && database.completionFollowUpDao().pendingPrompts()
                    .any { it.activityUuid == activity.uuid })
                rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
            return
        }
        val marker = metadata(queue) ?: return // Existing physical deletes keep their original semantics.
        val childUuids = marker.getValue("child_uuids").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val habit = habits.getHabitByUuid(queue.entityUuid) ?: return
        val pending = outbox.getAll() + outbox.getDeadLetters()
        val links = database.habitMetricLinkDao().getAllLinksForHabit(habit.id).map { it.uuid }.toSet()
        val commands = database.timeLogDao().getPendingTimerCommands(Int.MAX_VALUE) +
            database.timeLogDao().getRejectedTimerCommands()
        val childStructurePending = pending.filter { it.recordType == "habit" && it.action == "upsert" }.any {
            val origin = database.nextRequestDao().origin(NEXT_OPERATION, it.operationId)
            origin?.intentJson?.let { json -> decodeNextOperationIntent(json)
                .payload["parent_uuid"] == JsonPrimitive(habit.uuid) } == true
        }
        if (pending.any { it.id != queue.id && (it.referenceUuid == habit.uuid ||
                it.recordType == "habit" && it.entityUuid == habit.uuid || it.recordType == "link" && it.entityUuid in links) } ||
            pending.any { it.recordType == "habit" && it.entityUuid in childUuids } ||
            childStructurePending || habits.getChildrenByParentUuidOnce(habit.uuid).isNotEmpty() ||
            database.completionFollowUpDao().pendingPrompts().any { it.activityUuid == habit.uuid } ||
            commands.any { it.activityUuid == habit.uuid ||
                database.timeLogDao().getTimeLogByUuid(it.sessionUuid)?.habitId == habit.id }) {
            rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        }
        require(database.timeLogDao().getAllTimeLogsForHabit(habit.id).none { it.endTime == null })
    }

    suspend fun removeRetainedInTransaction(queue: SyncOutboxEntity): Boolean {
        if (queue.recordType == "link" && queue.action == "delete") {
            val link = database.habitMetricLinkDao().getLinkByUuid(queue.entityUuid) ?: return false
            val activity = habits.getHabitById(link.habitId) ?: return false
            if (!habits.hasPendingNextDeletion(activity.uuid)) return false
            requireReady(queue)
            require(outbox.getEntityIntents("link", link.uuid).none { it.id != queue.id })
            val sql = database.openHelper.writableDatabase
            val sources = listOf("sync_outbox", "timer_command_outbox").associateWith { NextRequestSql.sources(sql, it) }
            NextRequestSql.requireOutboxEnabled(sql)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            database.habitMetricLinkDao().delete(link)
            check(database.habitMetricLinkDao().getLinkByUuid(link.uuid) == null)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            check(listOf("sync_outbox", "timer_command_outbox").associateWith { NextRequestSql.sources(sql, it) } == sources)
            return true
        }
        if (metadata(queue) == null) return false
        requireReady(queue)
        val habit = habits.getHabitByUuid(queue.entityUuid) ?: return true
        check(habit.name == retainedName(habit.uuid) && habit.appearance != null)
        val sql = database.openHelper.writableDatabase
        val sources = listOf("sync_outbox", "timer_command_outbox").associateWith { NextRequestSql.sources(sql, it) }
        NextRequestSql.requireOutboxEnabled(sql)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        habits.delete(habit)
        check(habits.getHabitByUuid(habit.uuid) == null)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        check(listOf("sync_outbox", "timer_command_outbox").associateWith { NextRequestSql.sources(sql, it) } == sources)
        return true
    }

    suspend fun displayName(habit: HabitEntity): String {
        val marker = outbox.getEntityIntents("habit", habit.uuid).firstOrNull {
            it.action == "delete" && metadata(it) != null && database.nextRequestDao().origin(NEXT_OPERATION, it.operationId) != null
        }
        return marker?.let { metadata(it)!!.getValue("name").jsonPrimitive.content } ?: habit.name
    }

    companion object {
        private const val PREFIX = "next-plan-delete:"
        private fun retainedName(uuid: String) = "pending-delete-$uuid"

        private fun metadata(row: SyncOutboxEntity): JsonObject? {
            val reference = row.referenceUuid ?: return null
            if (!reference.startsWith(PREFIX)) return null
            require(row.recordType == "habit" && row.action == "delete")
            val value = Json.parseToJsonElement(reference.removePrefix(PREFIX)).jsonObject
            require(value.keys == setOf("node_kind", "name", "child_policy", "child_uuids"))
            val name = value.getValue("name").jsonPrimitive
            require(name.isString && name.content.isNotBlank() && name.content.codePointCount(0, name.content.length) <= 200)
            val kind = value.getValue("node_kind")
            require(kind in setOf(JsonPrimitive("goal"), JsonPrimitive("activity")))
            val policy = value.getValue("child_policy")
            require(if (kind == JsonPrimitive("goal")) policy in setOf(JsonPrimitive("cascade_children"), JsonPrimitive("detach_children"))
                else policy == JsonNull)
            val children = value.getValue("child_uuids").jsonArray
            require(children.distinct() == children && children.all {
                it is JsonPrimitive && it.isString && isContractUuid(it.content) && it.content != row.entityUuid
            } && (kind == JsonPrimitive("goal") || children.isEmpty()))
            return value
        }

        fun payload(row: SyncOutboxEntity): JsonObject? = metadata(row)?.let { metadata ->
            buildJsonObject { metadata.getValue("child_policy").takeUnless { it == JsonNull }?.let { put("child_policy", it) } }
        }

        fun children(row: SyncOutboxEntity): List<String> = requireNotNull(metadata(row))
            .getValue("child_uuids").jsonArray.map { it.jsonPrimitive.content }
    }
}
