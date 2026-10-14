@file:kotlinx.serialization.UseSerializers(
    com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractIntegerSerializer::class
)

package com.dayforge.data.repository

import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.isContractUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

internal const val RESTART_RECORD = "challenge_restart"

private fun hash(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" }

/** Original proposal identity, not an accepted head, API payload or caller permission. */
@Serializable
internal data class NextRestartReference(val operationId: String, val originHash: String, val head: ChallengeRoundHead) {
    init { require(isContractUuid(operationId) && hash(originHash) && head.generation > 0) }
}

@Serializable
internal data class NextRestartPlanFrontier(val operationId: String, val originHash: String, val dependencyHash: String) {
    init { require(isContractUuid(operationId) && hash(originHash) && hash(dependencyHash)) }
}

@Serializable
internal data class NextRestartTimerFrontier(val commandId: String, val originHash: String, val sessionId: String, val sequence: Int) {
    init { require(isContractUuid(commandId) && isContractUuid(sessionId) && hash(originHash) && sequence > 0) }
}

/**
 * NEW immutable local intention. There is deliberately NO expected_plan_revision here.
 * The canonical API intention is materialized only from full real predecessor acceptance.
 */
@Serializable
internal data class NextRestartProposal(
    @SerialName("restart_proposal") val version: Int,
    val operationId: String,
    val head: ChallengeRoundHead,
    val expectedHead: ChallengeRoundHead,
    val deviceId: String,
    val displayedPlan: JsonObject,
    val basePlan: JsonObject?,
    val planFrontier: NextRestartPlanFrontier?,
    val restartFrontier: NextRestartReference?,
    val terminals: List<NextRestartTimerFrontier>
) {
    init {
        require(version == 1 && isContractUuid(operationId) && isContractUuid(deviceId) &&
            head.activityUuid == expectedHead.activityUuid &&
            head.generation.toLong() == expectedHead.generation.toLong() + 1)
        require(restartFrontier == null || restartFrontier.head == expectedHead)
        require((basePlan != null) == (planFrontier == null && restartFrontier == null))
        require(terminals.size <= 10_000 && terminals.map { it.commandId }.distinct().size == terminals.size &&
            terminals.map { it.sessionId }.distinct().size == terminals.size)
        NextStructureMapper.validatePlanWrite(displayedPlan, head.activityUuid)
        val activity = requireNotNull(displayedPlan["activity"] as? JsonObject)
        require(activity["completion_policy"] == JsonPrimitive("recurring") &&
            requireNotNull(activity["target_cycles"] as? JsonPrimitive).let { !it.isString && requireNotNull(it.intOrNull) > 0 })
        basePlan?.let { NextStructureMapper.readPlan(it, head.activityUuid, requireNotNull(it["revision"]?.jsonPrimitive?.longOrNull)) }
    }
}

/** Private discriminator must be recognized BEFORE the ordinary operation decoder. No fallback. */
internal suspend fun restartProposal(body: String): NextRestartProposal? =
    if (Json.parseToJsonElement(body).jsonObject.containsKey("restart_proposal"))
        decodeFrozenSyncRequest(body.toByteArray(Charsets.UTF_8), NextRestartProposal.serializer()) else null
