package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.model.contractLongOrNull
import java.time.Instant
import kotlinx.serialization.json.*

internal class NextFactMergeException(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason { FACT_DIVERGED, ENTITY_DELETED, LOCAL_FACT_PENDING, LOCAL_TIMER_PENDING, INVALID_LOCAL_STATE, TARGET_MISMATCH }
}

/** Accepted bootstrap data only. The caller owns authentication, transaction and cursor activation. */
internal class NextCommonFactStore(private val database: HabitDatabase) {
    private val outbox = database.syncOutboxDao()
    private val completions = database.completionDao()
    private val timers = database.timeLogDao()
    private val observations = database.metricLogDao()
    private val links = database.habitMetricLinkDao()

    suspend fun restoreInTransaction(changes: List<SyncV2Change>, deviceId: String,
        acceptedQueueId: Long? = null, acceptedOperation: SyncV2Operation? = null,
        acceptedTimerCompletion: (suspend (SyncV2Change) -> String?)? = null,
        incremental: Boolean = false,
        provenParentDeletion: (suspend (String, String) -> Boolean)? = null) {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        for (table in listOf("completions", "metric_logs", "habit_metric_links")) {
            if (sql.query("SELECT 1 FROM $table GROUP BY uuid HAVING COUNT(*) > 1 LIMIT 1").use { it.moveToFirst() })
                fail(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
        }
        if (sql.query("SELECT 1 FROM completions c JOIN timelogs t ON c.uuid=t.uuid LIMIT 1").use { it.moveToFirst() })
            fail(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
        require(changes.all { it.operation == "upsert" && if (incremental) it.sequence > 0 else it.sequence == 0L })
        require(!incremental || acceptedQueueId == null)
        require(changes.map { it.entityType to it.entityUuid }.distinct().size == changes.size)
        require((acceptedQueueId == null) == (acceptedOperation == null))
        require(acceptedQueueId == null || changes.size == 1)
        val planIds = if (acceptedQueueId != null || incremental) database.habitDao().getAllHabitsOnce().map { it.uuid }.toSet()
            else changes.filter { it.entityType == "plan_node" }.map { it.entityUuid }.toSet()
        val metricIds = if (acceptedQueueId != null || incremental) database.metricDao().getAllMetricsOnce().map { it.uuid }.toSet()
            else changes.filter { it.entityType == "metric" }.map { it.entityUuid }.toSet()
        val incoming = changes.filter { it.entityType in setOf("activity_event", "metric_observation", "activity_metric_link") &&
            (it.entityType != "activity_event" || it.payload["one_time"].let { value -> value == null || value == JsonNull }) }
        val pending = outbox.getAll() + outbox.getDeadLetters()
        val conflicts = database.syncConflictDao().getUnresolved()
        val timerCommands = timers.getPendingTimerCommands(Int.MAX_VALUE) + timers.getRejectedTimerCommands()
        val oldEvents = outbox.getStatesForType("activity_event").associateBy { it.entityUuid }
        val events = oldEvents.filterValues { !it.deleted }.mapValues { (id, state) -> body(state).also {
            require(state.revision > 0 && it["public_id"] == JsonPrimitive(id) &&
                contractLongOrNull(it["revision"]) == state.revision && isContractUuid(it.text("activity_uuid")))
            require(it.text("event_type") in setOf("check_in", "count_delta", "count_snapshot", "duration_session", "revert"))
            if (it.text("event_type") == "revert") require(isContractUuid(it.text("reverts_event_uuid")))
        } }.toMutableMap()
        incoming.filter { it.entityType == "activity_event" }.forEach { events[it.entityUuid] = it.payload }
        val countDays = NextCountDayStore(database).restore(events, planIds)
        val undoTargets = events.filterValues { it["event_type"] == JsonPrimitive("revert") &&
            it["one_time"].let { proof -> proof == null || proof == JsonNull } }
            .mapValues { (_, value) -> value.text("reverts_event_uuid") }
        val reverted = undoTargets.values.toSet()
        require(reverted.size == undoTargets.size)
        // Revert chains cannot contain cycles or cross activities, even if no UI row survives.
        val validated = mutableSetOf<String>()
        undoTargets.keys.forEach { event ->
            val seen = mutableSetOf<String>()
            var cursor: String? = event
            while (cursor != null && cursor !in validated) {
                require(seen.add(cursor))
                val target = undoTargets[cursor]
                if (target != null) events[target]?.let { require(it.text("activity_uuid") == events.getValue(cursor).text("activity_uuid")) }
                cursor = target
            }
            validated += seen
        }
        val writes = mutableListOf<suspend () -> Unit>()
        val shadows = mutableListOf<SyncEntityStateEntity>()
        val deletes = mutableListOf<suspend () -> Unit>()
        val timerProofs = mutableListOf<Pair<SyncV2Change, String>>()

        for (change in incoming) {
            val payload = change.payload
            val id = change.entityUuid
            val type = change.entityType
            val old = outbox.getState(type, id)
            if (old?.deleted == true) fail(NextFactMergeException.Reason.ENTITY_DELETED)
            val mutable = type == "activity_metric_link"
            val known = old != null && old.revision == change.revision && body(old) == payload
            if (old != null) {
                require(old.revision > 0)
                if ((!mutable || old.revision == change.revision) && !known) fail(NextFactMergeException.Reason.FACT_DIVERGED)
                require(Instant.parse(body(old).text("created_at")) == Instant.parse(payload.text("created_at")))
            }
            val recordType = when (type) { "activity_event" -> "completion"; "metric_observation" -> "metric_log"; else -> "link" }
            val local = pending.filter { it.recordType == recordType && (it.entityUuid == id || it.wireEntityUuid == id) }
            val own = acceptedOperation?.takeIf { it.entityType == type && it.entityUuid == id }
            if (conflicts.any { it.entityType == type && (it.wireEntityUuid == id || it.localEntityUuid == id) })
                fail(NextFactMergeException.Reason.LOCAL_FACT_PENDING)
            val deletedLocally = local.any { it.entityUuid == id && (it.action == "delete" || it.wireEntityUuid != id) }
            if (!known && local.isNotEmpty()) {
                if (own != null) {
                    if (!mutable) NextCommonFactProof.requireOriginalPayload(own.payload, change, deviceId)
                } else {
                    if (mutable) fail(NextFactMergeException.Reason.LOCAL_FACT_PENDING)
                    val original = local.singleOrNull { it.wireEntityUuid == id && it.action == "upsert" }
                        ?: fail(NextFactMergeException.Reason.LOCAL_FACT_PENDING)
                    requireOriginal(original, change, deviceId)
                }
            }
            // Undo wire identity is different from its source queue's target identity.
            if (own != null && !mutable) NextCommonFactProof.requireOriginalPayload(own.payload, change, deviceId)
            if (own != null) {
                // A parent can have been hard-deleted after this immutable request was captured.
                // Only bound local deletion evidence permits shadow-only acceptance; absence is not proof.
                if (mutable) NextCommonFactMapper.validateLinkSnapshot(change)
                else NextCommonFactMapper.validateOrdinaryFactSnapshot(change)
                val parents = if (type == "metric_observation") listOf("metric" to payload.text("metric_uuid")) else
                    listOf("plan_node" to payload.text("activity_uuid")) +
                        if (mutable) listOf("metric" to payload.text("metric_uuid")) else emptyList()
                val missing = parents.filter { (kind, uuid) -> uuid !in if (kind == "metric") metricIds else planIds }
                if (missing.isNotEmpty()) {
                    for ((kind, uuid) in missing) require(provenParentDeletion?.invoke(kind, uuid) == true)
                    require(completions.getCompletionByUuid(id) == null && timers.getTimeLogByUuid(id) == null &&
                        observations.getLogByUuid(id) == null && links.getLinkByUuid(id) == null)
                    if (mutable && old != null && old.revision > change.revision) continue
                    if (!known) shadows += SyncEntityStateEntity(type, id, change.revision, payloadJson = payload.toString(),
                        payloadHash = syncPayloadHash(payload.toString()))
                    continue
                }
            }
            when (type) {
                "activity_event" -> {
                    val activity = payload.text("activity_uuid")
                    require(activity in planIds)
                    val habit = requireNotNull(database.habitDao().getHabitByUuid(activity))
                    val completion = completions.getCompletionByUuid(id)
                    val timer = timers.getTimeLogByUuid(id)
                    when (payload.text("event_type")) {
                        "revert" -> {
                            val target = NextCommonFactMapper.revert(change, habit)
                            require(completion == null && timer == null)
                            val targetCompletion = completions.getCompletionByUuid(target)
                            val targetTimer = timers.getTimeLogByUuid(target)
                            if (targetCompletion != null && (targetCompletion.habitId != habit.id || targetCompletion.oneTimeAction != null) ||
                                targetTimer != null && targetTimer.habitId != habit.id) fail(NextFactMergeException.Reason.TARGET_MISMATCH)
                            oldEvents[target]?.let { require(body(it).text("activity_uuid") == activity) }
                            val protected = pending.any { it.id != acceptedQueueId && it.recordType == "completion" && it.entityUuid == target }
                            if (protected && (targetCompletion != null || targetTimer != null)) fail(NextFactMergeException.Reason.LOCAL_FACT_PENDING)
                            if (timerCommands.any { it.sessionUuid == target }) fail(NextFactMergeException.Reason.LOCAL_TIMER_PENDING)
                            deletes += {
                                completions.getCompletionByUuid(target)?.let { completions.delete(it) }
                                timers.getTimeLogByUuid(target)?.let {
                                    timers.deleteDayAllocations(target); timers.deleteTimerSegments(target); timers.delete(it)
                                }
                                check(completions.getCompletionByUuid(target) == null && timers.getTimeLogByUuid(target) == null)
                            }
                        }
                        "duration_session" -> {
                            require(completion == null)
                            val mapped = NextCommonFactMapper.duration(change, habit, timer)
                            if (timerCommands.any { it.sessionUuid == id } && !known) fail(NextFactMergeException.Reason.LOCAL_TIMER_PENDING)
                            if (timer?.endTime == null && timer != null) fail(NextFactMergeException.Reason.LOCAL_TIMER_PENDING)
                            val mismatched = timer != null && timer.copy(createdAt = mapped.session.createdAt, updatedAt = mapped.session.updatedAt) != mapped.session
                            if (timer != null && (mismatched ||
                                old == null && local.isEmpty())) {
                                val proof = acceptedTimerCompletion?.invoke(change)
                                    ?: fail(if (mismatched) NextFactMergeException.Reason.LOCAL_TIMER_PENDING else NextFactMergeException.Reason.INVALID_LOCAL_STATE)
                                require(timer.startTime == mapped.session.startTime && timer.endTime == mapped.session.endTime &&
                                    timer.timerTimezone == mapped.session.timerTimezone)
                                timerProofs += change to proof
                            }
                            if (id !in reverted && !deletedLocally) writes += {
                                val saved = timers.upsert(mapped.session)
                                check(saved > 0 && timers.getTimeLogByUuid(id) == mapped.session.copy(id = saved))
                                timers.replaceDayAllocations(id, mapped.allocations)
                                check(timers.getDayAllocations(id).sortedBy { it.localDate } == mapped.allocations)
                            }
                        }
                        else -> {
                            require(timer == null)
                            val mapped = NextCommonFactMapper.completion(change, habit, completion)
                            if (completion != null && (completion.copy(createdAt = mapped.createdAt) != mapped || old == null && local.isEmpty() && own == null))
                                fail(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
                            if (id !in reverted && !deletedLocally) writes += {
                                val saved = completions.upsert(mapped)
                                check(saved > 0 && completions.getCompletionByUuid(id) == mapped.copy(id = saved))
                            }
                        }
                    }
                }
                "metric_observation" -> {
                    val metricId = payload.text("metric_uuid")
                    require(metricId in metricIds)
                    val metric = requireNotNull(database.metricDao().getMetricByUuid(metricId))
                    val previous = observations.getLogByUuid(id)
                    val mapped = NextCommonFactMapper.observation(change, metric, previous)
                    if (previous != null && (previous.copy(createdAt = mapped.createdAt, updatedAt = mapped.updatedAt) != mapped || old == null && local.isEmpty() && own == null)) fail(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
                    if (!deletedLocally) writes += {
                        val saved = observations.upsert(mapped)
                        check(saved > 0 && observations.getLogByUuid(id) == mapped.copy(id = saved))
                    }
                }
                else -> {
                    val activity = payload.text("activity_uuid")
                    val metricId = payload.text("metric_uuid")
                    require(activity in planIds && metricId in metricIds)
                    val habit = requireNotNull(database.habitDao().getHabitByUuid(activity))
                    val metric = requireNotNull(database.metricDao().getMetricByUuid(metricId))
                    val previous = links.getLinkByUuid(id)
                    val mapped = NextCommonFactMapper.link(change, habit, metric, previous)
                    if (old != null && old.revision >= change.revision) {
                        if (previous == null && !deletedLocally) fail(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
                        continue // Do not overwrite newer unpublished flags or an old server revision.
                    }
                    if (previous != null && old == null && own == null) fail(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
                    if (local.none { it.id != acceptedQueueId } && !deletedLocally) writes += {
                        val saved = links.upsert(mapped)
                        check(saved > 0 && links.getLinkByUuid(id) == mapped.copy(id = saved))
                    }
                }
            }
            if (!known) shadows += SyncEntityStateEntity(type, id, change.revision, payloadJson = payload.toString(),
                payloadHash = syncPayloadHash(payload.toString()))
        }
        require(sql.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use { it.moveToFirst() && it.getInt(0) == 0 })
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        writes.forEach { it() }; deletes.forEach { it() }
        shadows.forEach { outbox.upsertState(it); check(outbox.getState(it.entityType, it.entityUuid) == it) }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        NextCountDayStore(database).verify(countDays)
        for ((change, proof) in timerProofs) check(acceptedTimerCompletion?.invoke(change) == proof)
    }

    /** A pulled fact is not an operation acknowledgement; match the first frozen request only. */
    private fun requireOriginal(row: SyncOutboxEntity, change: SyncV2Change, device: String) {
        if (row.deadLetteredAt != null || row.attemptedAt == null || row.attemptCount <= 0) fail(NextFactMergeException.Reason.LOCAL_FACT_PENDING)
        val request = Json.parseToJsonElement(requireNotNull(row.payloadJson)).jsonObject
        require(isContractUuid(row.operationId) && row.baseRevision in listOf(null, 0L) && row.basePayloadJson == null)
        require(row.referenceUuid == change.payload.text(if (change.entityType == "metric_observation") "metric_uuid" else "activity_uuid"))
        NextCommonFactProof.requireOriginalPayload(request, change, device, explicitDevice = true)
    }

    private fun body(state: SyncEntityStateEntity) = Json.parseToJsonElement(requireNotNull(state.payloadJson)).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).let { require(it is JsonPrimitive && it.isString); it.content }
    private fun fail(reason: NextFactMergeException.Reason): Nothing = throw NextFactMergeException(reason)
}

/** Same frozen immutable proof for first ACK, bootstrap reconciliation and cold receipt replay. */
internal object NextCommonFactProof {
    /** New provenance is verified by the coordinator, not inferred from attempt metadata. */
    internal fun requireOriginalPayload(request: JsonObject, change: SyncV2Change, device: String, explicitDevice: Boolean = false) {
        val actual = change.payload
        // No inference of a first sender from today's registration. Old unbound requests
        // must be replayed/handled by the coordinator, not claimed by a coincident UUID.
        if (explicitDevice) require(request["source_device_id"] == JsonPrimitive(device))
        val fields = if (change.entityType == "metric_observation") setOf("metric_uuid", "value", "unit") else
            setOf("activity_uuid", "event_type", "value", "reverts_event_uuid", "duration_seconds", "duration_milliseconds", "started_at", "ended_at") +
                if ("count_policy" in request || "count_policy" in actual) setOf("count_policy") else emptySet()
        val common = setOf("occurred_at", "local_date", "timezone", "note", "source_type", "source_device_id", "external_event_id", "metadata")
        require(request.keys.all { it in fields + common })
        val defaults = mapOf("note" to JsonPrimitive(""), "source_type" to JsonPrimitive("app"), "metadata" to buildJsonObject {},
            "source_device_id" to JsonPrimitive(device))
        for (key in fields + common) {
            val expected = request[key]?.takeUnless { it == JsonNull &&
                (key == "source_device_id" || key == "value" && request["event_type"] == JsonPrimitive("check_in")) }
                ?: defaults[key] ?: if (key == "value" && request["event_type"] == JsonPrimitive("check_in")) JsonPrimitive(1) else JsonNull
            val received = actual.getValue(key)
            val equal = when {
                expected == received -> true
                expected == JsonNull || received == JsonNull -> false
                key in setOf("occurred_at", "started_at", "ended_at") -> Instant.parse(expected.jsonPrimitive.content) == Instant.parse(received.jsonPrimitive.content)
                key in setOf("value", "duration_seconds", "duration_milliseconds") -> expected.jsonPrimitive.content.toBigDecimal().compareTo(received.jsonPrimitive.content.toBigDecimal()) == 0
                else -> false
            }
            if (!equal) throw NextFactMergeException(NextFactMergeException.Reason.FACT_DIVERGED)
        }
    }
}
