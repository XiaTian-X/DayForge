package com.dayforge.domain.model

import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.NextStructureMapper
import com.dayforge.data.repository.ObjectEditAuthority
import kotlinx.serialization.json.JsonObject

/** Ordinary check-in display provenance, not a credential or permission. Never persisted.
 * The writer still validates the original edit ticket and current account in its transaction.
 */
@ConsistentCopyVisibility
data class CheckActionIdentity internal constructor(
    internal val session: LocalDataSession,
    val habitId: Long,
    val habitUuid: String,
    internal val deviceId: String?,
    internal val plan: JsonObject,
    internal val roundHead: ChallengeRoundHead?
) {
    /** Only the activation performed by this check-in may extend its original display claim.
     * This is not authority and must not be used as a successful-write receipt by itself.
     */
    internal fun completionSuccessor(original: HabitEntity): CheckActionIdentity {
        check(original.id == habitId && original.uuid == habitUuid &&
            original.habitType == HabitType.CHECK_IN && original.appearance != null &&
            original.completionPolicy == "recurring" && NextStructureMapper.writePlan(original) == plan) {
            "CHECK_ACTION_EXPIRED"
        }
        return if (original.isActive) this else copy(plan = NextStructureMapper.writePlan(original.copy(isActive = true)))
    }

    internal fun requireAuthority(authority: ObjectEditAuthority) {
        val rounds = authority.rounds
        val head = rounds?.let {
            it.pendingRestarts[habitUuid]?.head ?: it.completionRounds.head(habitUuid)
                ?: it.pendingInitials[habitUuid]?.head
        }
        check(authority.session == session && authority.uuid == habitUuid && authority.type == "plan_node" &&
            authority.original == plan && authority.completionDeviceCaptured &&
            authority.capturedDeviceId == deviceId && (rounds == null || rounds.access.deviceId == deviceId) &&
            (rounds == null) == (roundHead == null) && head == roundHead) { "CHECK_ACTION_EXPIRED" }
    }
}
