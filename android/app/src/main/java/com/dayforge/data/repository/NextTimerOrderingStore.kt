package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.serialization.json.*

/** Cross-queue birth order. Wall clock and mutable config never decide whether an edit predates start. */
internal class NextTimerOrderingStore(
    private val database: HabitDatabase,
    tokens: TokenManager,
    sessions: AccountSessionCoordinator,
    private val requests: NextCoreRequestStore
) {
    private val timers = NextTimerRequestStore(database, tokens, sessions, requests)

    suspend fun requireStartReady(command: TimerCommandEntity, access: LocalSyncAccess) {
        check(database.inTransaction())
        val origin = requireNotNull(database.nextRequestDao().origin(NEXT_TIMER, command.commandId))
        val intent = decodeNextTimerIntent(origin.intentJson)
        val policy = intent.command.startPolicy ?: return // Pre-existing foundation proof, not new UI start.
        policy.validate()
        require(intent.capturedDeviceId == null || intent.capturedDeviceId == access.deviceId)
        val restart = roundTimerIntent(origin.intentJson)?.restartFrontier
        val plan = intent.planPredecessorId?.let { NextStructuralCausalStore(database).acceptedPlan(it, access) }
            ?: restart?.let { NextRestartBindingStore(database).ready(access, it).first }
        plan?.let {
            require(plan["public_id"] == JsonPrimitive(intent.command.activityUuid))
            val activity = plan.getValue("activity").jsonObject
            val target = activity.getValue("target_value").jsonPrimitive.content.toBigDecimal().intValueExact()
            if (target != policy.targetSeconds || activity.getValue("is_countdown") != JsonPrimitive(policy.isCountdown))
                rejectNextRequest(NextRequestException.Reason.TIMER_START_CONFIG_CHANGED)
        }
    }

    suspend fun requireStructureReady(row: SyncOutboxEntity, access: LocalSyncAccess) {
        check(database.inTransaction())
        if (row.recordType != "habit" || row.action != "upsert") return
        val order = NextStructuralCausalStore(database).logicalOrder(row)
        for ((origin, intent) in NextTimerPolicyStore(database).starts(activityUuid = row.entityUuid)) {
            if (intent.command.activityUuid != row.entityUuid || order <= requireNotNull(intent.planQueueWatermark)) continue
            require(origin.accountId == access.session.authentication.userId &&
                (origin.serverInstanceId == null || origin.serverInstanceId == access.session.serverInstanceId && origin.syncEpoch == access.session.syncEpoch) &&
                (intent.capturedDeviceId == null || intent.capturedDeviceId == access.deviceId))
            if (!timers.hasAcceptedStartInTransaction(access, origin.requestId))
                rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        }
    }
}
