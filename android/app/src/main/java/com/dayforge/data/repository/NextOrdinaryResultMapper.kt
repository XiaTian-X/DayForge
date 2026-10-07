package com.dayforge.data.repository

import com.dayforge.data.api.dto.*
import kotlinx.serialization.json.*

/** Complete ordinary result proof. Pure: replay never depends on today's editable parent. */
internal object NextOrdinaryResultMapper {
    fun validate(operation: SyncV2Operation, result: NextSyncOperationResult, device: String): SyncV2Change {
        validateTaskResultBinding(operation, result)
        val deleting = operation.action == "delete"
        if (operation.action !in setOf("upsert", "delete") ||
            operation.entityType !in setOf("plan_node", "metric", "activity_event", "metric_observation", "activity_metric_link") ||
            deleting && operation.entityType == "activity_event" ||
            operation.payload["one_time"].let { it != null && it != JsonNull } ||
            operation.payload["event_type"] == JsonPrimitive("duration_session"))
            rejectNextRequest(NextRequestException.Reason.UNSUPPORTED_ACCEPTANCE)
        if (deleting) require(operation.payload.isEmpty() || operation.entityType == "plan_node" &&
            operation.payload.keys == setOf("child_policy") && operation.payload["child_policy"] in
                setOf(JsonPrimitive("detach_children"), JsonPrimitive("cascade_children")))
        require(result.status in setOf("applied", "already_applied") && result.revision != null && result.revision > 0 &&
            result.revision >= (operation.baseRevision ?: 0L) && result.entity != null &&
            result.errorCode == null && result.message == null && result.baseEntity == null && result.localEntity == null &&
            result.conflictingFields.isEmpty() && result.conflictKind == null && result.oneTimeConflict == null)
        val body = result.entity
        require(listOf("one_time", "one_time_state_after").all { body[it].let { value -> value == null || value == JsonNull } })
        val changedAt = requireNotNull(body["updated_at"] as? JsonPrimitive).also { require(it.isString) }.content
        val change = SyncV2Change(0, operation.entityType, operation.entityUuid, operation.action, result.revision, body, changedAt)
        when (operation.entityType) {
            "plan_node", "metric" -> {
                if (deleting) {
                    NextStructureMapper.validateTombstone(body, operation.entityType, operation.entityUuid, result.revision)
                    if (operation.payload["child_policy"] != null) require(body["node_kind"] == JsonPrimitive("goal"))
                } else if (operation.entityType == "plan_node") {
                    NextStructureMapper.readPlan(body, operation.entityUuid, result.revision)
                    require(operation.payload["node_kind"] == body["node_kind"])
                } else NextStructureMapper.readMetric(body, operation.entityUuid, result.revision)
            }
            "activity_metric_link" -> NextCommonFactMapper.validateLinkSnapshot(change)
            else -> {
                NextCommonFactMapper.validateOrdinaryFactSnapshot(change)
                if (!deleting) {
                    require(operation.baseRevision in listOf(null, 0L))
                    NextCommonFactProof.requireOriginalPayload(operation.payload, change, device)
                }
            }
        }
        return change
    }
}
