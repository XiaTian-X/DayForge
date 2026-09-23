package com.dayforge.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Protocol-next projection, not yet wired into Room or the active v4 sync path. */
@Serializable
data class OneTimeState(
    @Serializable(with = ContractIntegerSerializer::class)
    val version: Int,
    @SerialName("head_event_uuid") val headEventUuid: String?,
    @SerialName("completion_event_uuid") val completionEventUuid: String?
) {
    init {
        require(version >= 0)
        require(headEventUuid == null || isContractUuid(headEventUuid))
        require(completionEventUuid == null || isContractUuid(completionEventUuid))
        require((version == 0) == (headEventUuid == null))
        require((completionEventUuid != null) == (version % 2 == 1))
        require(completionEventUuid == null || completionEventUuid == headEventUuid)
    }
}

@Serializable
data class OneTimeIntent(
    @SerialName("event_uuid") val eventUuid: String,
    val action: String,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("expected_version") val expectedVersion: Int,
    @SerialName("expected_head_event_uuid") val expectedHeadEventUuid: String?,
    @SerialName("reverts_event_uuid") val revertsEventUuid: String?
) {
    init {
        require(isContractUuid(eventUuid))
        require(expectedVersion >= 0)
        require(expectedHeadEventUuid == null || isContractUuid(expectedHeadEventUuid))
        require(revertsEventUuid == null || isContractUuid(revertsEventUuid))
        require(action == "complete" || action == "undo")
        require((expectedVersion == 0) == (expectedHeadEventUuid == null))
        require((action == "undo") == (revertsEventUuid != null))
        require(eventUuid != expectedHeadEventUuid && eventUuid != revertsEventUuid)
    }
}

class OneTimeTransitionException(val code: String) : IllegalArgumentException(code)

/** Caller owns account checks, operation replay and atomic persistence; no side effects here. */
fun advanceOneTime(
    state: OneTimeState,
    intent: OneTimeIntent,
    deleted: Boolean = false
): OneTimeState {
    fun reject(code: String): Nothing = throw OneTimeTransitionException(code)
    if (deleted) reject("ENTITY_DELETED")
    if (intent.expectedVersion != state.version || intent.expectedHeadEventUuid != state.headEventUuid) {
        reject("TASK_STATE_CONFLICT")
    }
    if (state.version == Int.MAX_VALUE) reject("TASK_STATE_EXHAUSTED")
    val completion = if (intent.action == "complete") {
        if (state.completionEventUuid != null) reject("TASK_ALREADY_COMPLETED")
        intent.eventUuid
    } else {
        if (state.completionEventUuid != intent.revertsEventUuid) reject("TASK_COMPLETION_MISMATCH")
        null
    }
    return OneTimeState(state.version + 1, intent.eventUuid, completion)
}

private val contractUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

internal fun isContractUuid(value: String): Boolean = contractUuid.matches(value)
