@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class)

package com.dayforge.data.api.dto

import com.dayforge.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigDecimal

private val taskStateErrors = setOf("TASK_STATE_CONFLICT", "TASK_ALREADY_COMPLETED", "TASK_COMPLETION_MISMATCH", "TASK_STATE_EXHAUSTED")
private val proofFields = listOf("public_id", "activity_uuid", "event_type", "reverts_event_uuid", "one_time", "one_time_state_after")

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.hasValue(key: String) = this[key] != null && this[key] != JsonNull

/** New policy checks; other business fields still go through the existing domain mapper/validators. */
private fun oneTimePolicy(activity: JsonObject): Boolean {
    val policy = activity.text("completion_policy")
    require(policy in setOf("recurring", "one_and_done"))
    val once = policy == "one_and_done"
    val schedule = (activity["recurrence_rule"] as? JsonObject)?.text("type") ?: "daily"
    require(once == (schedule == "once"))
    if (once) {
        require(activity.text("tracking_mode") == "check")
        val target = if ("target_value" in activity) activity.text("target_value") else "1"
        require(target?.toBigDecimalOrNull()?.compareTo(BigDecimal.ONE) == 0)
        require(!activity.hasValue("target_unit") && !activity.hasValue("target_cycles") &&
            !activity.hasValue("preferred_local_time") && !activity.hasValue("origin_assignment_id"))
        require((activity["failure_policy"] as? JsonObject)?.text("type") == "loose")
        val countdown = activity["is_countdown"]
        require(countdown == null || (countdown is JsonPrimitive && !countdown.isString && countdown.booleanOrNull == false))
    }
    return once
}

fun validateNextAppearancePayload(payload: JsonObject, metric: Boolean = false) {
    require("icon" !in payload && "color_hex" !in payload)
    val appearance = Json.decodeFromJsonElement<ObjectAppearance>(payload.getValue("appearance"))
    val once = !metric && payload.text("node_kind") == "activity" &&
        oneTimePolicy(payload.getValue("activity").jsonObject)
    if (appearance.icon is IconReference.Role) require(iconAllowed(appearance.icon, once))
}

private fun eventProof(payload: JsonObject, identity: String): OneTimeEventProof? {
    if (!payload.hasValue("one_time") && !payload.hasValue("one_time_state_after")) return null
    val proof = Json.decodeFromJsonElement<OneTimeEventProof>(JsonObject(proofFields.associateWith { payload[it] ?: JsonNull }))
    require(proof.publicId == identity)
    return proof
}

@Serializable
data class NextSyncPushRequest(
    @SerialName("device_id") val deviceId: String,
    val operations: List<SyncV2Operation>
) {
    init { require(isContractUuid(deviceId) && operations.size in 1..100) }
}

/** Separate from envelope decoding: a bad domain operation must not reject an entire batch. */
fun validateNextSyncOperation(operation: SyncV2Operation) {
    if (operation.action != "upsert") return
    when (operation.entityType) {
        "plan_node" -> validateNextAppearancePayload(operation.payload)
        "metric" -> validateNextAppearancePayload(operation.payload, metric = true)
        "activity_event" -> {
            require("one_time_state_after" !in operation.payload)
            if (operation.payload.hasValue("one_time")) {
                val intent = Json.decodeFromJsonElement<OneTimeIntent>(operation.payload.getValue("one_time"))
                validateOneTimeBinding(operation.entityUuid, operation.payload.text("event_type") ?: "",
                    operation.payload.text("reverts_event_uuid"), intent, "one_and_done")
            }
        }
    }
}

@Serializable
data class NextSyncOperationResult(
    @SerialName("operation_id") val operationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_uuid") val entityUuid: String,
    val status: String,
    val revision: Long? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null,
    val entity: JsonObject? = null,
    @SerialName("base_entity") val baseEntity: JsonObject? = null,
    @SerialName("local_entity") val localEntity: JsonObject? = null,
    @SerialName("conflicting_fields") val conflictingFields: List<String> = emptyList(),
    @SerialName("conflict_kind") val conflictKind: String? = null,
    @SerialName("one_time_conflict") val oneTimeConflict: OneTimeProjection? = null
) {
    init {
        require(isContractUuid(operationId) && isContractUuid(entityUuid))
        require(status in setOf("applied", "already_applied", "conflict", "rejected"))
        require((entityType == "activity_event" && errorCode in taskStateErrors) == (oneTimeConflict != null))
        if (oneTimeConflict != null) require(status in setOf("conflict", "rejected") &&
            entity == null && revision == null && baseEntity == null && localEntity == null)
        if (entityType == "activity_event" && entity != null) eventProof(entity, entityUuid)
    }
}

fun validateTaskResultBinding(operation: SyncV2Operation, result: NextSyncOperationResult) {
    require(operation.operationId == result.operationId && operation.entityType == result.entityType && operation.entityUuid == result.entityUuid)
    result.oneTimeConflict?.let {
        require(it.activityUuid == operation.payload.text("activity_uuid") && operation.payload.hasValue("one_time"))
    }
    if (operation.payload.hasValue("one_time") && result.status in setOf("applied", "already_applied")) {
        val proof = result.entity?.let { eventProof(it, operation.entityUuid) }
        val submitted = Json.decodeFromJsonElement<OneTimeIntent>(operation.payload.getValue("one_time"))
        require(proof != null && proof.activityUuid == operation.payload.text("activity_uuid") &&
            proof.oneTime == submitted && result.errorCode == null)
    }
}

@Serializable
data class NextSyncPushResponse(val results: List<NextSyncOperationResult>)

@Serializable
data class NextSyncPullResponse(
    val changes: List<SyncV2Change>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("server_time") val serverTime: String
) {
    init { changes.filter { it.entityType == "activity_event" && it.operation == "upsert" }.forEach { eventProof(it.payload, it.entityUuid) } }
}

@Serializable
data class NextSyncBootstrapResponse(
    val changes: List<SyncV2Change>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("server_time") val serverTime: String,
    @SerialName("one_time_checkpoints") val oneTimeCheckpoints: List<OneTimeProjection>
) {
    init {
        val once = mutableSetOf<String>()
        val activities = mutableSetOf<String>()
        val seen = mutableSetOf<Pair<String, String>>()
        changes.forEach { change ->
            require(seen.add(change.entityType to change.entityUuid) && change.operation == "upsert" &&
                change.sequence == 0L && !change.payload.hasValue("deleted_at"))
            if (change.entityType == "plan_node" || change.entityType == "metric")
                validateNextAppearancePayload(change.payload, change.entityType == "metric")
            if (change.entityType == "plan_node" && change.payload.text("node_kind") == "activity") {
                activities.add(change.entityUuid)
                if (oneTimePolicy(change.payload.getValue("activity").jsonObject)) once.add(change.entityUuid)
            }
        }
        val checkpoints = oneTimeCheckpoints.associateBy { it.activityUuid }
        require(checkpoints.size == oneTimeCheckpoints.size && checkpoints.keys == once)
        val histories = once.associateWith { mutableListOf<OneTimeEventProof>() }
        changes.filter { it.entityType == "activity_event" }.forEach { change ->
            val activity = change.payload.text("activity_uuid")
            require(activity in activities)
            val proof = eventProof(change.payload, change.entityUuid)
            require((activity in once) == (proof != null))
            if (proof != null) histories.getValue(proof.activityUuid).add(proof)
        }
        checkpoints.forEach { (id, checkpoint) -> rebuildOneTimeHistory(checkpoint, histories.getValue(id)) }
    }
}
