@file:kotlinx.serialization.UseSerializers(ContractStringSerializer::class)

package com.dayforge.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Called with the persisted activity policy, never a caller-supplied substitute for authorization. */
fun validateOneTimeBinding(
    entityUuid: String,
    eventType: String,
    revertsEventUuid: String?,
    intent: OneTimeIntent?,
    completionPolicy: String
) {
    if (completionPolicy == "recurring") {
        if (intent != null) throw OneTimeTransitionException("INVALID_PAYLOAD")
        return
    }
    if (completionPolicy != "one_and_done" || intent == null || intent.eventUuid != entityUuid ||
        eventType != (if (intent.action == "complete") "check_in" else "revert") ||
        revertsEventUuid != intent.revertsEventUuid
    ) throw OneTimeTransitionException("INVALID_PAYLOAD")
}

private fun expectedState(intent: OneTimeIntent) = OneTimeState(
    intent.expectedVersion, intent.expectedHeadEventUuid,
    if (intent.expectedVersion % 2 == 1) intent.expectedHeadEventUuid else null
)

/** One-time fields of an immutable activity_event snapshot, not a separate HTTP endpoint. */
@Serializable
data class OneTimeEventProof(
    @SerialName("public_id") val publicId: String,
    @SerialName("activity_uuid") val activityUuid: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("reverts_event_uuid") val revertsEventUuid: String?,
    @SerialName("one_time") val oneTime: OneTimeIntent,
    @SerialName("one_time_state_after") val oneTimeStateAfter: OneTimeState
) {
    init {
        require(isContractUuid(publicId) && isContractUuid(activityUuid))
        require(revertsEventUuid == null || isContractUuid(revertsEventUuid))
        validateOneTimeBinding(publicId, eventType, revertsEventUuid, oneTime, "one_and_done")
        require(advanceOneTime(expectedState(oneTime), oneTime) == oneTimeStateAfter)
    }
}

/** Shape of result.one_time_conflict too; this must not masquerade as an activity_event entity. */
@Serializable
data class OneTimeProjection(
    @SerialName("activity_uuid") val activityUuid: String,
    val state: OneTimeState
) {
    init { require(isContractUuid(activityUuid)) }
}

/** Caller scopes both values to one authenticated account/epoch and persists inside a transaction. */
fun mergeOneTimeProjection(
    current: OneTimeProjection,
    incoming: OneTimeProjection,
    deleted: Boolean = false
): OneTimeProjection {
    if (deleted) throw OneTimeTransitionException("ENTITY_DELETED")
    if (current.activityUuid != incoming.activityUuid) throw OneTimeTransitionException("TASK_ACTIVITY_MISMATCH")
    if (incoming.state.version < current.state.version) return current
    if (incoming.state.version == current.state.version) {
        if (incoming.state != current.state) throw OneTimeTransitionException("TASK_STATE_DIVERGED")
        return current
    }
    return incoming
}

/** Validate the entire bootstrap history before replacing an active projection. */
fun rebuildOneTimeHistory(checkpoint: OneTimeProjection, events: List<OneTimeEventProof>): OneTimeProjection {
    val activityUuid = checkpoint.activityUuid
    var projection = OneTimeProjection(activityUuid, OneTimeState(0, null, null))
    val seen = mutableSetOf<String>()
    events.sortedBy { it.oneTimeStateAfter.version }.forEach { event ->
        if (event.activityUuid != activityUuid) throw OneTimeTransitionException("TASK_ACTIVITY_MISMATCH")
        if (!seen.add(event.publicId)) throw OneTimeTransitionException("TASK_EVENT_ID_REUSED")
        val expectedVersion = projection.state.version.toLong() + 1
        if (event.oneTimeStateAfter.version > expectedVersion) throw OneTimeTransitionException("TASK_HISTORY_INCOMPLETE")
        if (event.oneTimeStateAfter.version < expectedVersion) throw OneTimeTransitionException("TASK_STATE_DIVERGED")
        projection = OneTimeProjection(activityUuid, advanceOneTime(projection.state, event.oneTime))
    }
    if (projection.state.version < checkpoint.state.version) throw OneTimeTransitionException("TASK_HISTORY_INCOMPLETE")
    if (projection.state != checkpoint.state) throw OneTimeTransitionException("TASK_STATE_DIVERGED")
    return projection
}

@Serializable
data class PendingOneTimeIntent(
    @SerialName("operation_id") val operationId: String,
    val intent: OneTimeIntent
) {
    init { require(isContractUuid(operationId)) }
}

@Serializable
data class OneTimeQueueView(
    @SerialName("optimistic_state") val optimisticState: OneTimeState,
    @SerialName("awaiting_replay_operation_ids") val awaitingReplayOperationIds: List<String>,
    @SerialName("blocked_operation_ids") val blockedOperationIds: List<String>
)

/**
 * Never acknowledges, removes or rebases frozen operations. A newer confirmed base may contain
 * an operation whose response was lost: mismatch alone is awaiting replay, not rejection.
 * Run per authenticated account/epoch/activity. Only a recorded rejection blocks a causal suffix.
 */
fun projectPendingOneTime(
    confirmed: OneTimeState,
    pending: List<PendingOneTimeIntent>,
    rejectedOperationId: String? = null
): OneTimeQueueView {
    val operationIds = pending.map { it.operationId }
    val eventIds = pending.map { it.intent.eventUuid }
    if (operationIds.distinct().size != operationIds.size || eventIds.distinct().size != eventIds.size ||
        (rejectedOperationId != null && rejectedOperationId !in operationIds)
    ) throw OneTimeTransitionException("INVALID_PENDING_CHAIN")
    var previous: OneTimeState? = null
    pending.forEach { item ->
        val before = expectedState(item.intent)
        if (previous != null && before != previous) throw OneTimeTransitionException("INVALID_PENDING_CHAIN")
        previous = try {
            advanceOneTime(before, item.intent)
        } catch (_: OneTimeTransitionException) {
            throw OneTimeTransitionException("INVALID_PENDING_CHAIN")
        }
    }
    val rejectedIndex = rejectedOperationId?.let(operationIds::indexOf) ?: pending.size
    var state = confirmed
    pending.forEachIndexed { index, item ->
        if (item.operationId == rejectedOperationId) {
            return OneTimeQueueView(state, emptyList(), operationIds.drop(index))
        }
        if (expectedState(item.intent) != state) {
            return OneTimeQueueView(state, operationIds.subList(index, rejectedIndex), operationIds.drop(rejectedIndex))
        }
        state = advanceOneTime(state, item.intent)
    }
    return OneTimeQueueView(state, emptyList(), emptyList())
}
