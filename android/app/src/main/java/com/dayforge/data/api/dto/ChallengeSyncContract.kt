@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class)

package com.dayforge.data.api.dto

import com.dayforge.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Explicit profile on v5, not permission to activate or mutate an authenticated replica. */
@Serializable
data class ChallengeSourceContext(
    @SerialName("source_uuid") val sourceUuid: String,
    val head: ChallengeRoundHead?,
    @Serializable(with = ContractBooleanSerializer::class)
    @SerialName("legacy_initial") val legacyInitial: Boolean = false,
    @SerialName("affected_heads") val affectedHeads: List<ChallengeRoundHead> = emptyList()
) {
    init {
        require(isContractUuid(sourceUuid) && affectedHeads.size <= 1000)
        require(affectedHeads.map { it.activityUuid }.distinct().size == affectedHeads.size)
        require(!legacyInitial || (head == null || head.generation == 0) && affectedHeads.all { it.generation == 0 })
    }
}

private fun validateContexts(sources: List<String>, contexts: List<ChallengeSourceContext>) {
    require(sources.size in 1..100 && sources.all(::isContractUuid) && sources.distinct().size == sources.size)
    require(contexts.size == sources.size && contexts.map { it.sourceUuid }.toSet() == sources.toSet())
}

/** Keeps the original operation object; challenge attribution is an independent immutable sidecar. */
@Serializable
data class RoundSyncPushRequest(
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("challenge_contract") val challengeContract: Int,
    @SerialName("device_id") val deviceId: String,
    val operations: List<SyncV2Operation>,
    val contexts: List<ChallengeSourceContext>
) {
    init {
        require(challengeContract == 1 && isContractUuid(deviceId))
        validateContexts(operations.map { it.operationId }, contexts)
        operations.forEach { operation ->
            require(isContractUuid(operation.entityUuid) && operation.action in setOf("upsert", "delete"))
            require(operation.baseRevision == null || operation.baseRevision > 0)
            if (operation.entityType == "challenge_round") {
                val intent = Json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload)
                val context = contextFor(operation.operationId)
                require(operation.action == "upsert" && operation.baseRevision == null && operation.entityUuid == intent.roundUuid)
                require(context.head == null && !context.legacyInitial && context.affectedHeads.isEmpty())
            } else require(operation.entityType in ORDINARY_ROUND_ENTITIES)
        }
    }
    fun contextFor(sourceUuid: String) = contexts.single { it.sourceUuid == sourceUuid }
}

@Serializable
data class RoundTimerCommandBatchRequest(
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("challenge_contract") val challengeContract: Int,
    @SerialName("device_id") val deviceId: String,
    val commands: List<TimerCommandRequest>,
    val contexts: List<ChallengeSourceContext>
) {
    init {
        require(challengeContract == 1 && isContractUuid(deviceId))
        validateContexts(commands.map { it.commandId }, contexts)
        commands.forEach { require(isContractUuid(it.sessionId)) }
    }
    fun contextFor(sourceUuid: String) = contexts.single { it.sourceUuid == sourceUuid }
}

@Serializable
data class ChallengeCheckpoint(val head: ChallengeRoundHead, val records: List<ChallengeRoundRecord>) {
    init { require(rebuildChallengeHistory(head.activityUuid, records) == head) }
}

@Serializable
data class ChallengeBirth(
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_uuid") val entityUuid: String,
    val head: ChallengeRoundHead
) {
    init { require(entityType in setOf("activity_event", "timer_session") && isContractUuid(entityUuid)) }
}

@Serializable
data class ChallengeMetadata(
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("challenge_contract") val challengeContract: Int,
    val checkpoints: List<ChallengeCheckpoint>,
    val births: List<ChallengeBirth>
) {
    init {
        require(challengeContract == 1)
        val histories = checkpoints.associateBy { it.head.activityUuid }
        require(histories.size == checkpoints.size && births.map { it.entityType to it.entityUuid }.distinct().size == births.size)
        births.forEach { birth -> require(histories[birth.head.activityUuid]?.records?.any { it.head == birth.head } == true) }
    }
    fun requireBirth(kind: String, identity: String, activity: String?): ChallengeBirth =
        requireNotNull(births.singleOrNull { it.entityType == kind && it.entityUuid == identity && it.head.activityUuid == activity })
    fun requireRecord(identity: String): ChallengeRoundRecord =
        requireNotNull(checkpoints.flatMap { it.records }.singleOrNull { it.head.roundUuid == identity })
}

@Serializable
data class RoundSyncPushResponse(
    val results: List<NextSyncOperationResult>,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("challenge_contract") val challengeContract: Int,
    val checkpoints: List<ChallengeCheckpoint>,
    val births: List<ChallengeBirth>
) {
    init {
        val metadata = metadata()
        results.filter { it.status in setOf("applied", "already_applied") }.forEach { result ->
            if (result.entityType == "activity_event") validateRoundCountProof(requireNotNull(result.entity))
            if (result.entityType == "challenge_round") {
                val record = Json.decodeFromJsonElement<ChallengeRoundRecord>(requireNotNull(result.entity))
                require(metadata.requireRecord(result.entityUuid) == record && record.restartOperationUuid == result.operationId &&
                    result.revision == 1L && result.errorCode == null)
            } else if (result.entityType == "activity_event" && !result.entity.isOnceFact()) {
                metadata.requireBirth("activity_event", result.entityUuid, result.entity.activityUuid())
            }
        }
    }
    fun metadata() = ChallengeMetadata(challengeContract, checkpoints, births)
}

@Serializable
data class RoundSyncPullResponse(
    val changes: List<SyncV2Change>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("server_time") val serverTime: String,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("challenge_contract") val challengeContract: Int,
    val checkpoints: List<ChallengeCheckpoint>,
    val births: List<ChallengeBirth>
) {
    init {
        NextSyncPullResponse(changes, nextCursor, hasMore, serverTime)
        validateRoundChanges(changes, metadata())
    }
    fun metadata() = ChallengeMetadata(challengeContract, checkpoints, births)
}

@Serializable
data class RoundSyncBootstrapResponse(
    val changes: List<SyncV2Change>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("server_time") val serverTime: String,
    @SerialName("one_time_checkpoints") val oneTimeCheckpoints: List<OneTimeProjection>,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("challenge_contract") val challengeContract: Int,
    val checkpoints: List<ChallengeCheckpoint>,
    val births: List<ChallengeBirth>
) {
    init {
        // Reuse all existing appearance, once-history and shared count-day validation.
        NextSyncBootstrapResponse(changes, nextCursor, serverTime, oneTimeCheckpoints)
        val metadata = metadata()
        validateRoundChanges(changes, metadata)
        changes.filter { it.entityType == "plan_node" && it.payload["node_kind"]?.jsonPrimitive?.content == "activity" }
            .filter { it.payload["activity"]?.jsonObject?.get("completion_policy")?.jsonPrimitive?.content == "recurring" }
            .forEach { change -> require(metadata.checkpoints.any { it.head.activityUuid == change.entityUuid }) }
    }
    fun metadata() = ChallengeMetadata(challengeContract, checkpoints, births)
}

@Serializable
data class RoundTimerCommandBatchResponse(
    val results: List<TimerCommandResult>,
    @SerialName("server_time") val serverTime: String,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("challenge_contract") val challengeContract: Int,
    val checkpoints: List<ChallengeCheckpoint>,
    val births: List<ChallengeBirth>
) {
    init {
        val metadata = metadata()
        results.mapNotNull { it.session }.forEach { validateTimerBirth(metadata, it) }
    }
    fun metadata() = ChallengeMetadata(challengeContract, checkpoints, births)
}

@Serializable
data class RoundActiveTimerResponse(
    val session: TimerSessionResponse?,
    @SerialName("server_time") val serverTime: String,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("challenge_contract") val challengeContract: Int,
    val checkpoints: List<ChallengeCheckpoint>,
    val births: List<ChallengeBirth>
) {
    init { val metadata = metadata(); session?.let { validateTimerBirth(metadata, it) } }
    fun metadata() = ChallengeMetadata(challengeContract, checkpoints, births)
}

/** Validate original ACK identity and attribution, not merely a well-formed server head. */
fun validateRoundResultBinding(request: RoundSyncPushRequest, response: RoundSyncPushResponse) {
    val operations = request.operations.associateBy { it.operationId }
    require(response.results.size == operations.size && response.results.map { it.operationId }.distinct().size == operations.size)
    val metadata = response.metadata()
    response.results.forEach { result ->
        val operation = requireNotNull(operations[result.operationId])
        validateTaskResultBinding(operation, result)
        if (result.status !in setOf("applied", "already_applied")) return@forEach
        if (operation.entityType == "activity_event") require(operation.payload.isOnceFact() == result.entity.isOnceFact())
        if (operation.entityType == "challenge_round") {
            val record = metadata.requireRecord(operation.entityUuid)
            require(record.sourceDeviceUuid == request.deviceId && record.restartOperationUuid == operation.operationId &&
                record.restartIntent == Json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload))
        } else if (operation.entityType == "activity_event" && !result.entity.isOnceFact()) {
            val birth = metadata.requireBirth("activity_event", operation.entityUuid, operation.payload.activityUuid())
            require(birth.head == request.contextFor(operation.operationId).head)
            require(operation.payload["count_policy"]?.let(CountDayPolicy::fromJson) ==
                result.entity?.get("count_policy")?.let(CountDayPolicy::fromJson))
        }
    }
}

fun validateRoundTimerBinding(request: RoundTimerCommandBatchRequest, response: RoundTimerCommandBatchResponse) {
    val commands = request.commands.associateBy { it.commandId }
    require(response.results.size == commands.size && response.results.map { it.commandId }.distinct().size == commands.size)
    val metadata = response.metadata()
    response.results.forEach { result ->
        val command = requireNotNull(commands[result.commandId])
        require(result.sessionId == command.sessionId && result.status in setOf("applied", "already_applied", "conflict", "rejected"))
        if (result.status in setOf("applied", "already_applied")) require(result.session != null && result.errorCode == null)
        result.session?.let { timer ->
            require(timer.sessionId == command.sessionId)
            val birth = metadata.requireBirth("timer_session", timer.sessionId, timer.activityUuid)
            // A conflict may report a different actual birth; preserve it for conflict handling.
            if (result.status in setOf("applied", "already_applied")) {
                require(birth.head == request.contextFor(command.commandId).head)
                if (command.commandType == "start") {
                    require(timer.activityUuid == command.activityUuid)
                    command.startPolicy?.let { policy -> require(timer.targetSeconds == policy.targetSeconds &&
                        timer.isCountdown == policy.isCountdown && timer.maxDurationSeconds == policy.maxDurationSeconds) }
                }
            }
        }
    }
}

private fun validateTimerBirth(metadata: ChallengeMetadata, timer: TimerSessionResponse) {
    val birth = metadata.requireBirth("timer_session", timer.sessionId, timer.activityUuid)
    timer.completedEventId?.let {
        require(metadata.requireBirth("activity_event", it, timer.activityUuid).head == birth.head)
    }
}

private fun validateRoundChanges(changes: List<SyncV2Change>, metadata: ChallengeMetadata) {
    changes.forEach { change ->
        if (change.entityType == "activity_event") validateRoundCountProof(change.payload)
        if (change.entityType == "challenge_round") {
            val record = Json.decodeFromJsonElement<ChallengeRoundRecord>(change.payload)
            require(change.operation == "upsert" && change.revision == 1L && metadata.requireRecord(change.entityUuid) == record &&
                record.sourceDeviceUuid == change.originDeviceId)
        } else if (change.entityType == "activity_event" && !change.payload.isOnceFact()) {
            metadata.requireBirth("activity_event", change.entityUuid, change.payload.activityUuid())
        }
    }
}

private fun validateRoundCountProof(payload: JsonObject) {
    payload["count_policy"]?.let {
        require(payload["event_type"]?.jsonPrimitive?.content in setOf("count_delta", "count_snapshot") && !payload.isOnceFact())
        require(it is JsonObject)
        CountDayPolicy.fromJson(it)
    }
}

private fun JsonObject?.isOnceFact() = this?.get("one_time")?.let { it != JsonNull } == true
private fun JsonObject?.activityUuid() = this?.get("activity_uuid")?.jsonPrimitive?.contentOrNull
internal val ORDINARY_ROUND_ENTITIES = setOf("plan_node", "metric", "activity_event", "metric_observation", "activity_metric_link")
