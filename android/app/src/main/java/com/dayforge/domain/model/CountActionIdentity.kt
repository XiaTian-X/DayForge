package com.dayforge.domain.model

import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.entity.HabitEntity
import kotlinx.serialization.json.JsonObject

/** Display provenance, NOT a credential or a reusable permission. Never persisted. */
@ConsistentCopyVisibility
data class CountActionIdentity internal constructor(
    internal val session: LocalDataSession,
    val habitId: Long,
    val habitUuid: String,
    internal val deviceId: String?,
    internal val plan: JsonObject?,
    internal val roundHead: ChallengeRoundHead?,
    internal val legacy: HabitEntity? = null
) {
    internal fun requireAuthority(authority: com.dayforge.data.repository.ObjectEditAuthority) {
        val rounds = authority.rounds
        val head = rounds?.let {
            it.pendingRestarts[habitUuid]?.head ?: kotlinx.serialization.json.Json.decodeFromString<
                com.dayforge.data.api.dto.ChallengeMetadata>(it.metadataJson).checkpoints
                .singleOrNull { point -> point.head.activityUuid == habitUuid }?.head ?: it.pendingInitials[habitUuid]?.head
        }
        check(legacy == null && authority.session == session && authority.uuid == habitUuid &&
            authority.type == "plan_node" && authority.original == plan && head == roundHead &&
            (rounds == null || rounds.access.deviceId == deviceId)) { "COUNT_ACTION_EXPIRED" }
    }
}
