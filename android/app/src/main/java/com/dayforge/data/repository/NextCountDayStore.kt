package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.isContractUuid
import java.time.LocalDate
import kotlinx.serialization.json.*

/** D-017 transaction-only day state. Neither undo nor configuration editing deletes it. */
internal class NextCountDayStore(private val database: HabitDatabase) {
    private val dao get() = database.countDayDao()
    private val sql get() = database.openHelper.writableDatabase

    suspend fun read(habit: HabitEntity, date: String, allowUnbound: Boolean = false): CountDayEntity? {
        check(database.inTransaction())
        require(LocalDate.parse(date).toString() == date)
        NextRequestSql.rowHash(sql, "count_days", "habitId=? AND localDate=?", arrayOf(habit.id, date))
        return dao.get(habit.id, date)?.also { row ->
            require(row.habitId == habit.id && row.habitUuid == habit.uuid && row.localDate == date &&
                isContractUuid(row.firstEventUuid) && row.originKind == NEXT_OPERATION)
            row.policy
            if (row.originRequestId == null) {
                require(row.originHash == null && row.planPredecessorId == null && row.planPredecessorOriginHash == null &&
                    row.planPredecessorDependencyHash == null && row.planQueueWatermark == null && row.capturedDeviceId == null)
                if (!allowUnbound) {
                    requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
                        arrayOf("activity_event", row.firstEventUuid)))
                    val state = requireNotNull(database.syncOutboxDao().getState("activity_event", row.firstEventUuid))
                    val body = Json.parseToJsonElement(requireNotNull(state.payloadJson)).jsonObject
                    require(!state.deleted && state.payloadHash == syncPayloadHash(state.payloadJson) &&
                        body["activity_uuid"] == JsonPrimitive(habit.uuid) && body["local_date"] == JsonPrimitive(date) &&
                        NextCommonFactMapper.countPolicy(body) == row.policy)
                }
            }
            else {
                require(isContractUuid(row.originRequestId) && row.planQueueWatermark != null && row.planQueueWatermark >= 0)
                row.capturedDeviceId?.let { require(isContractUuid(it)) }
                val origin = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, row.originRequestId))
                require(row.originHash == NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?",
                    arrayOf(NEXT_OPERATION, row.originRequestId)))
                require(origin.protocol == 5 && origin.queueId > row.planQueueWatermark)
                val operation = decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
                require(operation.operationId == row.originRequestId && operation.entityType == "activity_event" &&
                    operation.action == "upsert" && operation.entityUuid == row.firstEventUuid &&
                    operation.payload["activity_uuid"] == JsonPrimitive(habit.uuid) &&
                    operation.payload["local_date"] == JsonPrimitive(date) &&
                    NextCommonFactMapper.countPolicy(operation.payload) == row.policy)
                if (row.planPredecessorId == null) require(row.planPredecessorOriginHash == null && row.planPredecessorDependencyHash == null)
                else {
                    require(isContractUuid(row.planPredecessorId) &&
                        row.planPredecessorOriginHash == NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?",
                            arrayOf(NEXT_OPERATION, row.planPredecessorId)) &&
                        row.planPredecessorDependencyHash == NextRequestSql.rowHash(sql, "next_structural_dependencies", "operationId=?",
                            arrayOf(row.planPredecessorId)))
                    val predecessor = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, row.planPredecessorId))
                    require(predecessor.accountId == origin.accountId && predecessor.queueId <= row.planQueueWatermark)
                    val plan = decodeFrozenSyncRequest(predecessor.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
                    require(plan.entityType == "plan_node" && plan.entityUuid == habit.uuid && plan.action == "upsert" &&
                        policyFromPlan(plan.payload) == row.policy)
                }
            }
        }
    }

    suspend fun capture(habit: HabitEntity, fact: CompletionEntity): CountDayPolicy {
        check(database.inTransaction())
        require(habit.habitType == HabitType.COUNTING && habit.appearance != null && habit.completionPolicy == "recurring" &&
            fact.habitId == habit.id && fact.habitUuid == habit.uuid && fact.oneTimeAction == null)
        read(habit, fact.recordedLocalDate)?.let { return it.policy }
        require(!hasUnboundHistory(habit, fact.recordedLocalDate)) { "COUNT_DAY_POLICY_UNKNOWN" }
        val day = CountDayEntity(habit.id, habit.uuid, fact.recordedLocalDate, habit.targetValue, habit.isCountdown, fact.uuid)
        dao.insert(day)
        check(dao.get(habit.id, fact.recordedLocalDate) == day)
        return day.policy
    }

    /** Read-only evidence check. A lost known rule is corruption, not an unstarted date. */
    suspend fun hasUnboundHistory(habit: HabitEntity, date: String, accountId: String? = null): Boolean {
        check(database.inTransaction())
        require(LocalDate.parse(date).toString() == date)
        var unknown = false
        // Undo removes the effective projection, not its immutable local origin. A missing
        // day must not silently capture today's edited plan for an already-started day.
        // Whitespace-tolerant candidate filter, followed by exact decoded identity/date checks.
        // Lifetime history is not a limit on starting a new date.
        val dateProbe = "%\"local_date\"%:%\"$date\"%"
        val originals = sql.query("SELECT requestId FROM next_request_origins WHERE kind=? AND intentJson LIKE ? AND intentJson LIKE ? LIMIT 10001",
            arrayOf(NEXT_OPERATION, "%${habit.uuid}%", dateProbe)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        require(originals.size <= 10_000)
        for (id in originals) {
            requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
            val origin = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, id))
            require(origin.protocol == 5 && isContractUuid(origin.accountId) && isContractUuid(id))
            if (accountId != null) require(origin.accountId == accountId) { "COUNT_SESSION_CHANGED" }
            val operation = decodeFrozenSyncRequest(origin.intentJson.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())
            require(operation.operationId == id)
            if (operation.entityType == "activity_event" && operation.action == "upsert" &&
                operation.payload["activity_uuid"] == JsonPrimitive(habit.uuid) &&
                operation.payload["local_date"] == JsonPrimitive(date) &&
                operation.payload["event_type"] in setOf(JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))) {
                require(NextCommonFactMapper.countPolicy(operation.payload) == null) { "COUNT_DAY_INVALID" }
                unknown = true
            }
        }
        // Accepted undone facts still prove the day began. Bound only this activity's retained shadows.
        val states = sql.query("SELECT entityUuid FROM sync_entity_state WHERE entityType='activity_event' AND payloadJson LIKE ? AND payloadJson LIKE ? LIMIT 10001",
            arrayOf("%${habit.uuid}%", dateProbe)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        require(states.size <= 10_000)
        for (id in states) {
            requireNotNull(NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf("activity_event", id)))
            val state = requireNotNull(database.syncOutboxDao().getState("activity_event", id))
            val payload = Json.parseToJsonElement(requireNotNull(state.payloadJson)).jsonObject
            require(state.payloadHash == syncPayloadHash(state.payloadJson))
            if (payload["activity_uuid"] == JsonPrimitive(habit.uuid) && payload["local_date"] == JsonPrimitive(date) &&
                payload["event_type"] in setOf(JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))) {
                require(NextCommonFactMapper.countPolicy(payload) == null) { "COUNT_DAY_INVALID" }
                unknown = true
            }
        }
        return unknown || database.completionDao().getCompletionsInRange(habit.id, LocalDate.parse(date),
            LocalDate.parse(date).plusDays(1)).isNotEmpty()
    }

    /** Called only after the producer has persisted the first fact's immutable original operation. */
    suspend fun bind(origin: NextRequestOriginEntity, operation: SyncV2Operation, access: LocalCoreWriteAccess) {
        check(database.inTransaction())
        if (operation.entityType != "activity_event" || "count_policy" !in operation.payload) return
        val habit = requireNotNull(database.habitDao().getHabitByUuid(operation.payload.getValue("activity_uuid").jsonPrimitive.content))
        val date = operation.payload.getValue("local_date").jsonPrimitive.content
        val current = requireNotNull(read(habit, date, allowUnbound = true))
        require(current.policy == NextCommonFactMapper.countPolicy(operation.payload))
        if (current.firstEventUuid != operation.entityUuid || current.originRequestId != null) return
        val causal = NextStructuralCausalStore(database)
        val pending = database.syncOutboxDao().getEntityIntents("habit", habit.uuid).filter { it.id < origin.queueId }
        val predecessor = pending.map { it to causal.logicalOrder(it) }.maxByOrNull { it.second }?.first
        require(predecessor == null || predecessor.action == "upsert")
        val day = current.copy(originRequestId = origin.requestId,
            originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, origin.requestId))),
            planPredecessorId = predecessor?.operationId,
            planPredecessorOriginHash = predecessor?.let { requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, it.operationId))) },
            planPredecessorDependencyHash = predecessor?.let { requireNotNull(NextRequestSql.rowHash(sql, "next_structural_dependencies", "operationId=?", arrayOf(it.operationId))) },
            planQueueWatermark = origin.queueId - 1, capturedDeviceId = access.capturedDeviceId)
        check(dao.update(day) == 1 && read(habit, date) == day)
    }

    /** All immutable count evidence, including reverted facts, participates in one day rule. */
    suspend fun restore(events: Map<String, JsonObject>, planIds: Set<String>): List<Pair<CountDayEntity, String>> {
        check(database.inTransaction())
        val proofs = mutableListOf<Pair<CountDayEntity, String>>()
        val groups = events.filterValues { it["event_type"] in setOf(JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot")) }
            .entries.groupBy { it.value.getValue("activity_uuid").jsonPrimitive.content to it.value.getValue("local_date").jsonPrimitive.content }
        for ((key, facts) in groups) {
            if (key.first !in planIds) continue // A proven deleted parent remains shadow-only.
            val habit = requireNotNull(database.habitDao().getHabitByUuid(key.first))
            val policies = facts.map { NextCommonFactMapper.countPolicy(it.value) }.distinct()
            require(policies.size == 1) { "COUNT_DAY_POLICY_CONFLICT" }
            val policy = policies.single()
            val old = read(habit, key.second, allowUnbound = true)
            if (policy == null) require(old == null) { "COUNT_DAY_POLICY_UNKNOWN" }
            else if (old != null) require(old.policy == policy) { "COUNT_DAY_POLICY_CONFLICT" }
            else {
                val day = CountDayEntity(habit.id, habit.uuid, key.second, policy.targetValue, policy.isCountdown, facts.first().key)
                dao.insert(day)
                check(read(habit, key.second, allowUnbound = true) == day)
            }
            if (policy != null) proofs += requireNotNull(read(habit, key.second, allowUnbound = true)) to requireNotNull(
                NextRequestSql.rowHash(sql, "count_days", "habitId=? AND localDate=?", arrayOf(habit.id, key.second)))
        }
        return proofs
    }

    suspend fun verify(proofs: List<Pair<CountDayEntity, String>>) {
        check(database.inTransaction())
        for ((day, hash) in proofs) {
            check(NextRequestSql.rowHash(sql, "count_days", "habitId=? AND localDate=?", arrayOf(day.habitId, day.localDate)) == hash)
            check(read(requireNotNull(database.habitDao().getHabitById(day.habitId)), day.localDate) == day)
        }
    }

    /** Keep the validated day bound through later receipt/checkpoint writes in the same transaction. */
    suspend fun proofs(changes: List<SyncV2Change>, allowDeletedParents: Boolean = false): List<Pair<CountDayEntity, String>> {
        check(database.inTransaction())
        val captured = linkedMapOf<Pair<Long, String>, Pair<CountDayEntity, String>>()
        for (change in changes.filter { it.entityType == "activity_event" && it.operation == "upsert" && "count_policy" in it.payload }) {
            val activity = change.payload.getValue("activity_uuid").jsonPrimitive.content
            val habit = database.habitDao().getHabitByUuid(activity)
            if (habit == null) { require(allowDeletedParents); continue }
            val date = change.payload.getValue("local_date").jsonPrimitive.content
            val day = requireNotNull(read(habit, date))
            require(day.policy == NextCommonFactMapper.countPolicy(change.payload))
            captured[habit.id to date] = day to requireNotNull(NextRequestSql.rowHash(sql, "count_days",
                "habitId=? AND localDate=?", arrayOf(habit.id, date)))
        }
        return captured.values.toList()
    }

    companion object {
        fun policyFromPlan(plan: JsonObject): CountDayPolicy {
            val activity = plan.getValue("activity").jsonObject
            require(activity["tracking_mode"] == JsonPrimitive("count") && activity["completion_policy"] == JsonPrimitive("recurring"))
            val target = activity.getValue("target_value").jsonPrimitive.content.toBigDecimal().intValueExact()
            return CountDayPolicy.fromJson(buildJsonObject {
                put("target_value", target); put("is_countdown", activity.getValue("is_countdown"))
            })
        }
    }
}
