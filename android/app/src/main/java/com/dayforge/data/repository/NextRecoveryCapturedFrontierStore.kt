package com.dayforge.data.repository

import com.dayforge.data.api.dto.TimerStartPolicy
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.domain.model.CountDayPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.util.Collections

/** Original cross-queue birth order, never an inferred ACK or a new daily rule. */
internal data class NextRecoveryCountFrontier(val activityUuid: String, val localDate: String,
    val policy: CountDayPolicy, val first: NextRecoveryKey?, val planQueueWatermark: Long?, val dayHash: String)

/** Non-start sequence edges must be joined from the complete, separately audited session index. */
internal data class NextRecoveryTimerFrontier(val sessionUuid: String, val activityUuid: String,
    val sequence: Int, val commandType: String, val planQueueWatermark: Long?, val policy: TimerStartPolicy?)

internal class NextRecoveryCapturedFrontiers(val key: NextRecoveryKey, val state: NextRecoveryRequestState,
    val logicalQueueOrder: Long, dependencies: List<NextRecoveryDependency>,
    protections: Set<NextRecoveryProtection>, val count: NextRecoveryCountFrontier?,
    val timer: NextRecoveryTimerFrontier?, val evidenceHash: String) {
    val dependencies: List<NextRecoveryDependency> = Collections.unmodifiableList(dependencies.toList())
    val protections: Set<NextRecoveryProtection> = Collections.unmodifiableSet(protections.toSet())
}

/**
 * SELECT-only original captured frontiers. Caller owns account + Room snapshot. This does not
 * claim a complete graph: current-head barriers, later structure vs birth watermarks, timer
 * sequences, entity/revert references, once CAS and import phases still require the global join.
 * Referenced structural chains are fully audited; a captured restart is NOT its acceptance.
 */
internal class NextRecoveryCapturedFrontierStore(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase

    suspend fun pending(access: LocalSyncAccess, key: NextRecoveryKey): NextRecoveryCapturedFrontiers {
        check(database.inTransaction()); currentCoroutineContext().ensureActive()
        val rounds = NextChallengeStore(database).activeInTransaction(access)
        val checkpointHash = nextRequestHash(listOf(requireNotNull(hash("next_sync_state", "1=1", emptyArray())),
            requireNotNull(hash("next_challenge_state", "1=1", emptyArray()))).joinToString(";").toByteArray(Charsets.UTF_8))
        val pending = NextOriginalRequestProofStore(database).pending(access, key.kind, key.requestId)
        val source = pending.origin
        val edges = mutableListOf<NextRecoveryDependency>()
        val evidence = mutableListOf(checkpointHash, pending.evidenceHash)
        val protections = mutableSetOf<NextRecoveryProtection>()
        var count: NextRecoveryCountFrontier? = null
        var timer: NextRecoveryTimerFrontier? = null
        var order = source.queueId

        fun edge(id: String, kind: String, reason: NextRecoveryDependencyReason, proof: String) {
            edges += NextRecoveryDependency(NextRecoveryKey(kind, id), key, reason,
                nextRequestHash((pending.evidenceHash + ";" + proof).toByteArray(Charsets.UTF_8)))
        }
        suspend fun plan(id: String, activity: String, watermark: Long, reason: NextRecoveryDependencyReason) {
            val chain = NextStructuralCausalStore(database).recoveryLineage(id, access)
            val first = chain.links.first()
            require(first.entityType == "plan_node" && first.entityUuid == activity && first.logicalQueueId <= watermark)
            val original = original(access, NextRecoveryKey(NEXT_OPERATION, first.logicalKey.requestId))
            require(requireNotNull(roundOperationIntent(original.intentJson)).operation.action == "upsert")
            edge(id, NEXT_OPERATION, reason, chain.evidenceHash)
        }
        if (key.kind == NEXT_OPERATION) {
            val round = requireNotNull(roundOperationIntent(source.intentJson))
            val operation = round.operation
            if (operation.entityType in setOf("plan_node", "metric", "activity_metric_link")) {
                val lineage = NextStructuralCausalStore(database).recoveryLineage(key.requestId, access)
                require(lineage.actualRequestId == key.requestId)
                order = lineage.links.first().logicalQueueId
                evidence += lineage.evidenceHash
            } else {
                require(operation.entityType in setOf("activity_event", "metric_observation"))
                // The independent once journal/CAS is not an ordinary fact frontier.
                require(operation.payload["one_time"].let { it == null || it == JsonNull }) {
                    "SYNC_RECOVERY_ONCE_FRONTIER_REQUIRED"
                }
                protections += NextRecoveryProtection.REAL_FACT
            }
            round.goalChildFrontier?.let { frontier ->
                val store = NextGoalChildFrontierStore(database)
                for (child in frontier) {
                    currentCoroutineContext().ensureActive()
                    store.validateCaptured(child, operation, access)
                    val lineage = NextStructuralCausalStore(database).recoveryLineage(child.operationId, access)
                    require(lineage.links.first().entityUuid == child.childUuid &&
                        lineage.links.first().logicalQueueId < order)
                    edge(child.operationId, NEXT_OPERATION, NextRecoveryDependencyReason.GOAL_CHILD_FRONTIER,
                        child.originHash + ";" + child.dependencyHash + ";" + lineage.evidenceHash)
                }
            }
            if (operation.entityType == "activity_event" && "count_policy" in operation.payload) {
                val activity = operation.payload.getValue("activity_uuid").jsonPrimitive.content
                val date = operation.payload.getValue("local_date").jsonPrimitive.content
                val habit = requireNotNull(database.habitDao().getHabitByUuid(activity))
                val day = requireNotNull(NextCountDayStore(database).read(habit, date))
                require(day.policy == NextCommonFactMapper.countPolicy(operation.payload))
                val dayHash = requireNotNull(hash("count_days", "habitId=? AND localDate=?", arrayOf(habit.id, date)))
                evidence += dayHash; protections += NextRecoveryProtection.COUNT_DAY
                val first = day.originRequestId?.let { NextRecoveryKey(NEXT_OPERATION, it) }
                count = NextRecoveryCountFrontier(activity, date, day.policy, first, day.planQueueWatermark, dayHash)
                if (first != null) {
                    require(day.capturedDeviceId == access.deviceId)
                    val birth = original(access, first)
                    val birthRound = requireNotNull(roundOperationIntent(birth.intentJson))
                    require(birth.queueId > requireNotNull(day.planQueueWatermark) &&
                        birthRound.operation.operationId == first.requestId && birthRound.operation.entityUuid == day.firstEventUuid)
                    if (first != key) edge(first.requestId, NEXT_OPERATION, NextRecoveryDependencyReason.FIRST_COUNT_FACT, dayHash)
                    else day.planPredecessorId?.let { plan(it, activity, day.planQueueWatermark,
                        NextRecoveryDependencyReason.COUNT_POLICY) }
                } // A server-born day never acquires an invented local first-request identity.
            }
            round.restartFrontier?.let { ref ->
                NextRestartBindingStore(database).captured(access, ref, source.queueId - 1)
                edge(ref.operationId, NEXT_OPERATION, NextRecoveryDependencyReason.RESTART_FRONTIER, ref.originHash)
                protections += NextRecoveryProtection.ROUND_STATE
            }
        } else {
            require(key.kind == NEXT_TIMER)
            val round = requireNotNull(roundTimerIntent(source.intentJson)); val intent = round.timer
            val command = intent.command
            val activity = requireNotNull(round.context.head).activityUuid
            timer = NextRecoveryTimerFrontier(command.sessionId, activity, command.sequence, command.commandType,
                intent.planQueueWatermark, command.startPolicy)
            if (command.commandType == "start") {
                require(intent.capturedDeviceId == access.deviceId && intent.planQueueWatermark != null &&
                    intent.planQueueWatermark >= 0)
                intent.planPredecessorId?.let { plan(it, activity, intent.planQueueWatermark,
                    NextRecoveryDependencyReason.TIMER_POLICY) }
            }
            round.restartFrontier?.let { ref ->
                NextRestartBindingStore(database).captured(access, ref, intent.planQueueWatermark)
                edge(ref.operationId, NEXT_OPERATION, NextRecoveryDependencyReason.RESTART_FRONTIER, ref.originHash)
                protections += NextRecoveryProtection.ROUND_STATE
            }
        }
        for (dependency in edges) evidence += dependency.evidenceHash
        check(NextChallengeStore(database).activeInTransaction(access) == rounds)
        return NextRecoveryCapturedFrontiers(key, pending.state, order, edges, protections, count, timer,
            nextRequestHash(evidence.joinToString(";").toByteArray(Charsets.UTF_8)))
    }

    /** Captured reference only; the global node reader must still classify live/ACK/terminal state. */
    private suspend fun original(access: LocalSyncAccess, key: NextRecoveryKey): NextRequestOriginEntity {
        requireNotNull(hash("next_request_origins", "kind=? AND requestId=?", arrayOf(key.kind, key.requestId)))
        val row = requireNotNull(database.nextRequestDao().origin(key.kind, key.requestId))
        require(row.kind == key.kind && row.requestId == key.requestId && row.protocol == 5 && row.queueId > 0 &&
            row.accountId == access.session.authentication.userId && row.serverInstanceId == access.session.serverInstanceId &&
            row.syncEpoch == access.session.syncEpoch)
        require(requireNotNull(roundOperationIntent(row.intentJson)).capturedDeviceId == access.deviceId)
        return row
    }
    private suspend fun hash(table: String, where: String, args: Array<Any>) = NextRequestSql.rowHash(sql, table, where, args)
}
