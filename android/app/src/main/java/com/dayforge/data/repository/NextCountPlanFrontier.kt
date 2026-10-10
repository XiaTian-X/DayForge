@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class)

package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.domain.model.isContractUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Private birth evidence, never a wire field, ACK, or a later alias inferred for an old day. */
@Serializable
internal data class NextCountPlanFrontier(
    val requestId: String,
    val logicalId: String,
    val actualId: String,
    val policySourceId: String,
    val logicalOriginHash: String,
    val dependencyHash: String,
    val actualOriginHash: String,
    val supersessionHash: String
) {
    init {
        require(listOf(requestId, logicalId, actualId, policySourceId).all(::isContractUuid) && logicalId != actualId &&
            policySourceId in setOf(logicalId, actualId))
        require(listOf(logicalOriginHash, dependencyHash, actualOriginHash, supersessionHash)
            .all { it.matches(Regex("[0-9a-f]{64}")) })
    }
    fun requireOperation(operation: SyncV2Operation) {
        require(requestId == operation.operationId && operation.entityType == "activity_event" &&
            operation.action == "upsert" && NextCommonFactMapper.countPolicy(operation.payload) != null)
    }
}

/** Plain v5 source has no challenge context. Only new aliased first counts use this wrapper. */
@Serializable
internal data class NextCountOperationIntent(
    val operation: SyncV2Operation,
    @SerialName("count_plan_frontier") val countPlanFrontier: NextCountPlanFrontier
) {
    init { validateNextSyncOperation(operation); countPlanFrontier.requireOperation(operation) }
}
