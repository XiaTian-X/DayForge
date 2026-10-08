package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.isContractUuid
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class NextStructureMergeException(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason {
        ENTITY_DELETED, STRUCTURE_DIVERGED, LOCAL_STRUCTURE_PENDING, UNINITIALIZED,
        INVALID_PARENT, IMMUTABLE_NODE_KIND, COMPLETION_POLICY_LOCKED, INVALID_LOCAL_STATE
    }
}

/**
 * Structural bootstrap stage, called only inside the authenticated owner's transaction.
 * No tombstone deletion, cursor advancement, operation acknowledgement or asset authorization.
 */
internal class NextStructureStore(private val database: HabitDatabase) {
    private val habits = database.habitDao()
    private val metrics = database.metricDao()
    private val outbox = database.syncOutboxDao()

    internal suspend fun restoreInTransaction(changes: List<SyncV2Change>, acceptedQueueId: Long? = null,
        incremental: Boolean = false) {
        check(database.inTransaction())
        require(changes.all { it.entityType in setOf("plan_node", "metric") && it.operation == "upsert" &&
            if (incremental) it.sequence > 0 else it.sequence == 0L })
        require(!incremental || acceptedQueueId == null)
        require(changes.map { it.entityType to it.entityUuid }.distinct().size == changes.size)
        require(acceptedQueueId == null || changes.size == 1)
        val oldPlans = habits.getAllHabitsOnce()
        val oldMetrics = metrics.getAllMetricsOnce()
        check(oldPlans.map { it.uuid }.distinct().size == oldPlans.size)
        check(oldMetrics.map { it.uuid }.distinct().size == oldMetrics.size)
        val plansById = oldPlans.associateBy { it.uuid }
        val metricsById = oldMetrics.associateBy { it.uuid }
        val pending = outbox.getAll() + outbox.getDeadLetters()
        val conflicts = database.syncConflictDao().getUnresolved()
        val planUpdates = linkedMapOf<String, HabitEntity>()
        val metricUpdates = linkedMapOf<String, MetricEntity>()
        val incomingPlans = linkedMapOf<String, HabitEntity>()
        val shadows = mutableListOf<SyncEntityStateEntity>()

        for (change in changes) {
            val isPlan = change.entityType == "plan_node"
            val oldPlan = plansById[change.entityUuid]
            val oldMetric = metricsById[change.entityUuid]
            // Decode the entire payload even when its revision will be ignored.
            val plan = if (isPlan) NextStructureMapper.readPlan(change.payload, change.entityUuid, change.revision, oldPlan) else null
            if (plan != null) incomingPlans[plan.uuid] = plan
            val metric = if (!isPlan) NextStructureMapper.readMetric(change.payload, change.entityUuid, change.revision, oldMetric) else null
            if (plan != null && oldPlan != null) {
                if (oldPlan.appearance == null || oldPlan.planMetadata == null ||
                    (oldPlan.habitType != HabitType.GOAL && oldPlan.completionPolicy == null))
                    reject(NextStructureMergeException.Reason.UNINITIALIZED)
                if ((oldPlan.habitType == HabitType.GOAL) != (plan.habitType == HabitType.GOAL))
                    reject(NextStructureMergeException.Reason.IMMUTABLE_NODE_KIND)
                require(Instant.parse(oldPlan.planMetadata.creationTimestamp) == Instant.parse(requireNotNull(plan.planMetadata).creationTimestamp))
            }
            if (metric != null && oldMetric != null) {
                if (oldMetric.appearance == null) reject(NextStructureMergeException.Reason.UNINITIALIZED)
                // A first metric creation timestamp is server-owned, unlike a plan's explicit timestamp.
                if (acceptedQueueId == null || outbox.getState("metric", metric.uuid) != null)
                    require(oldMetric.createdAt == metric.createdAt)
            }
            val shadow = outbox.getState(change.entityType, change.entityUuid)
            if (shadow != null) {
                require(shadow.revision > 0)
                if (!shadow.deleted) {
                    val previousBody = Json.parseToJsonElement(requireNotNull(shadow.payloadJson)).jsonObject
                    fun created(body: JsonObject): Instant {
                        val value = body.getValue("created_at")
                        require(value is JsonPrimitive && value.isString)
                        return Instant.parse(value.content)
                    }
                    // Metric rows store milliseconds; the immutable shadow retains finer precision.
                    require(created(previousBody) == created(change.payload))
                }
                if (shadow.revision > change.revision) continue
                if (shadow.deleted) reject(NextStructureMergeException.Reason.ENTITY_DELETED)
                if (shadow.revision == change.revision) {
                    if (shadow.payloadJson?.let { Json.parseToJsonElement(it) } != change.payload)
                        reject(NextStructureMergeException.Reason.STRUCTURE_DIVERGED)
                    if ((isPlan && oldPlan == null) || (!isPlan && oldMetric == null))
                        reject(NextStructureMergeException.Reason.INVALID_LOCAL_STATE)
                    continue // Known structure must not overwrite newer unpublished local edits.
                }
            }
            val recordType = if (isPlan) "habit" else "metric"
            val othersPending = pending.any { it.id != acceptedQueueId && (it.recordType == recordType || isPlan && it.recordType == RESTART_RECORD) &&
                (it.entityUuid == change.entityUuid || it.wireEntityUuid == change.entityUuid) }
            if (conflicts.any { it.recordType == recordType && (it.localEntityUuid == change.entityUuid || it.wireEntityUuid == change.entityUuid) } ||
                acceptedQueueId == null && !incremental && othersPending) {
                reject(NextStructureMergeException.Reason.LOCAL_STRUCTURE_PENDING)
            }
            if ((acceptedQueueId != null || incremental) && othersPending) {
                // Advance only the authoritative base. Never replay an older edit over a local suffix.
                if (metric != null && oldMetric != null && oldMetric.createdAt != metric.createdAt)
                    metricUpdates[metric.uuid] = oldMetric.copy(createdAt = metric.createdAt)
            } else if (plan != null) {
                if (oldPlan != null) {
                    if (oldPlan.completionPolicy != plan.completionPolicy && hasHistory(oldPlan))
                        reject(NextStructureMergeException.Reason.COMPLETION_POLICY_LOCKED)
                }
                planUpdates[plan.uuid] = plan
            } else {
                requireNotNull(metric)
                metricUpdates[metric.uuid] = metric
            }
            shadows += SyncEntityStateEntity(change.entityType, change.entityUuid, change.revision,
                payloadJson = change.payload.toString(), payloadHash = syncPayloadHash(change.payload.toString()))
        }

        incomingPlans.values.forEach { child ->
            child.parentHabitId?.let { parent ->
                if ((incomingPlans[parent] ?: if (acceptedQueueId != null || incremental) plansById[parent] else null)?.habitType != HabitType.GOAL)
                    reject(NextStructureMergeException.Reason.INVALID_PARENT)
            }
        }
        val resultingPlans = plansById + planUpdates
        resultingPlans.values.forEach { child ->
            child.parentHabitId?.let { id ->
                val parent = resultingPlans[id]
                if (child.habitType == HabitType.GOAL || parent == null || parent.uuid == child.uuid ||
                    parent.habitType != HabitType.GOAL || parent.parentHabitId != null ||
                    outbox.getState("plan_node", id)?.deleted == true)
                    reject(NextStructureMergeException.Reason.INVALID_PARENT)
            }
        }
        require(resultingPlans.values.map { it.name }.distinct().size == resultingPlans.size)
        val resultingMetrics = metricsById + metricUpdates
        require(resultingMetrics.values.map { it.name }.distinct().size == resultingMetrics.size)

        val sql = database.openHelper.writableDatabase
        require(sql.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use { it.moveToFirst() && it.getInt(0) == 0 })
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        // A valid final snapshot can swap unique names. Free only names being changed, within this transaction.
        val usedNames = (oldPlans.map { it.name } + resultingPlans.values.map { it.name } +
            oldMetrics.map { it.name } + resultingMetrics.values.map { it.name }).toMutableSet()
        fun temporaryName(): String {
            var value: String
            do { value = "restore-${UUID.randomUUID()}" } while (!usedNames.add(value))
            return value
        }
        planUpdates.values.filter { it.id != 0L && plansById.getValue(it.uuid).name != it.name }.forEach {
            check(habits.updateForSync(plansById.getValue(it.uuid).copy(name = temporaryName())) == 1)
        }
        metricUpdates.values.filter { it.id != 0L && metricsById.getValue(it.uuid).name != it.name }.forEach {
            check(metrics.updateForSync(metricsById.getValue(it.uuid).copy(name = temporaryName())) == 1)
        }
        planUpdates.values.sortedBy { it.habitType != HabitType.GOAL }.forEach { row ->
            val id = habits.upsert(row)
            check(id > 0 && habits.getHabitById(id) == row.copy(id = id))
        }
        metricUpdates.values.forEach { row ->
            val id = metrics.upsert(row)
            check(id > 0 && metrics.getMetricById(id) == row.copy(id = id))
        }
        shadows.forEach { shadow ->
            outbox.upsertState(shadow)
            check(outbox.getState(shadow.entityType, shadow.entityUuid) == shadow)
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
    }

    private suspend fun hasHistory(habit: HabitEntity): Boolean {
        if ((habit.oneTimeConfirmedVersion ?: 0) > 0 ||
            database.completionDao().getByHabitOnce(habit.id).isNotEmpty() ||
            database.timeLogDao().getAllTimeLogsForHabit(habit.id).isNotEmpty() ||
            outbox.getActivityIntents(habit.uuid).isNotEmpty() ||
            database.completionFollowUpDao().hasActivityHistory(habit.uuid)) return true
        val timers = database.timeLogDao()
        for (command in timers.getPendingTimerCommands(Int.MAX_VALUE) + timers.getRejectedTimerCommands()) {
            val activity = command.activityUuid ?: timers.getTimeLogByUuid(command.sessionUuid)?.let {
                habits.getHabitById(it.habitId)?.uuid
            } ?: reject(NextStructureMergeException.Reason.INVALID_LOCAL_STATE)
            if (activity == habit.uuid) return true
        }
        return outbox.getStatesForType("activity_event").any { shadow ->
            // Deleted facts still lock conversion; a missing historical body cannot prove there was no history.
            val body = shadow.payloadJson?.let { Json.parseToJsonElement(it) } as? JsonObject
            val activity = body?.get("activity_uuid")
            if (activity !is JsonPrimitive || !activity.isString || !isContractUuid(activity.content))
                reject(NextStructureMergeException.Reason.INVALID_LOCAL_STATE)
            activity == JsonPrimitive(habit.uuid)
        }
    }

    private fun reject(reason: NextStructureMergeException.Reason): Nothing = throw NextStructureMergeException(reason)
}
