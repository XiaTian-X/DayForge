@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class)

package com.dayforge.data.repository

import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/** NEW immutable local source only. Never added to an old origin or frozen envelope. */
@Serializable
internal data class NextRoundOperationIntent(
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("challenge_contract") val challengeContract: Int,
    val operation: SyncV2Operation,
    val context: ChallengeSourceContext,
    @SerialName("captured_device_id") val capturedDeviceId: String,
    @Serializable(with = ContractBooleanSerializer::class)
    @SerialName("initial_creation") val initialCreation: Boolean = false
) {
    init {
        require(challengeContract == 1 && isContractUuid(capturedDeviceId))
        require(context.sourceUuid == operation.operationId && !context.legacyInitial)
        RoundSyncPushRequest(1, capturedDeviceId, listOf(operation), listOf(context))
        validateNextSyncOperation(operation)
        if (initialCreation) require(operation.entityType == "plan_node" && operation.action == "upsert" &&
            operation.baseRevision == null && operation.payload["activity"]?.jsonObject?.get("completion_policy") == JsonPrimitive("recurring") &&
            context.head == initialChallengeRoundHead(operation.entityUuid) && context.affectedHeads.isEmpty())
    }
}

/** Same strict bounded codec as structural ancestry; do not add a dispatcher hop per old link. */
private suspend fun <T> decodeIntent(bytes: ByteArray, serializer: KSerializer<T>): T {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    if (bytes.size > 8192) return decodeFrozenSyncRequest(bytes, serializer)
    return try { decodeSyncReply(bytes, SYNC_REQUEST_LIMIT, serializer, { caller.ensureActive() }) }
    catch (_: NextSyncReplyInvalid) { throw InvalidFrozenSyncRequest() }
}

internal suspend fun roundOperationIntent(body: String): NextRoundOperationIntent? {
    // Discriminator selects a STRICT decoder. Unknown/wrong tags never fall back to an old intent.
    return if (Json.parseToJsonElement(body).jsonObject.containsKey("challenge_contract"))
        decodeIntent(body.toByteArray(Charsets.UTF_8), NextRoundOperationIntent.serializer()) else null
}

internal suspend fun decodeNextOperationIntent(body: String): SyncV2Operation =
    roundOperationIntent(body)?.operation ?: decodeIntent(body.toByteArray(Charsets.UTF_8), SyncV2Operation.serializer())

internal suspend fun decodeNextOperationEnvelope(bytes: ByteArray): NextSyncPushRequest {
    return if (Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.containsKey("challenge_contract")) {
        val round = decodeIntent(bytes, RoundSyncPushRequest.serializer())
        NextSyncPushRequest(round.deviceId, round.operations)
    } else decodeIntent(bytes, NextSyncPushRequest.serializer())
}

/** Original explicit source selects the profile, not current cache appearance or a server response. */
internal suspend fun validateNextOperationEnvelope(body: String, bytes: ByteArray, device: String): SyncV2Operation {
    val round = roundOperationIntent(body)
    if (round == null) {
        val request = decodeIntent(bytes, NextSyncPushRequest.serializer())
        require(request.deviceId == device && request.operations == listOf(decodeNextOperationIntent(body)))
        return request.operations.single()
    }
    val request = decodeIntent(bytes, RoundSyncPushRequest.serializer())
    require(round.capturedDeviceId == device && request.deviceId == device &&
        request.operations == listOf(round.operation) && request.contexts == listOf(round.context))
    return round.operation
}

internal suspend fun encodeNextOperationIntent(operation: SyncV2Operation, previous: String): ByteArray {
    val round = roundOperationIntent(previous)
    return if (round == null) encodeSyncRequest(SyncV2Operation.serializer(), operation)
    else encodeSyncRequest(NextRoundOperationIntent.serializer(), round.copy(operation = operation,
        context = round.context.copy(sourceUuid = operation.operationId)))
}
