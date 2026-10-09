package com.dayforge.data.repository

import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.validateTaskResultBinding
import com.dayforge.domain.model.isContractUuid
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class NextStructuralCausalConflict(val fields: List<String>) : IllegalStateException("Structural causal conflict")

/**
 * D-015's pure field merge, NOT authority to supersede or send an operation.
 * A repository must first prove the durable dependency, complete bound receipt, never-transmitted
 * source and current account/replica/device. This function does no IO, generates no identity,
 * changes no original, and never handles facts, timer commands or one-time completion states.
 */
internal object NextStructuralRebase {
    private val planPaths = listOf(
        "parent_uuid", "title", "description", "status", "visibility", "sort_order",
        "goal.start_date", "goal.due_date", "goal.target_cycles", "goal.failure_policy",
        "goal.evaluation_policy", "goal.manual_result", "activity.tracking_mode", "activity.is_countdown",
        "activity.recurrence_rule", "activity.completion_policy", "activity.target_value", "activity.target_unit",
        "activity.target_cycles", "activity.failure_policy", "activity.preferred_local_time", "activity.timezone",
        "activity.origin_assignment_id", "appearance.icon", "appearance.accent_color", "appearance.icon_tint"
    )
    private val metricPaths = listOf(
        "name", "description", "unit", "decimal_places", "aggregation_type", "target_direction",
        "target_value", "target_value_upper", "status", "appearance.icon", "appearance.accent_color", "appearance.icon_tint"
    )
    // Link endpoints are immutable, not fields that can be merged to a different identity.
    private val linkPaths = listOf("coefficient", "show_in_activity_detail", "prompt_on_complete", "is_active")
    private val decimals = setOf("activity.target_value", "target_value", "target_value_upper", "coefficient")

    /** A restart changes the Plan via its own contract and contiguous log, not a synthetic Plan ACK. */
    fun mergeRestartPlan(baseline: JsonObject, successor: SyncV2Operation, canonical: JsonObject,
        revision: Long, replacementId: String): SyncV2Operation {
        require(successor.entityType == "plan_node" && successor.action in setOf("upsert", "delete") &&
            revision > 0 && isContractUuid(replacementId) && replacementId != successor.operationId)
        NextStructureMapper.validatePlanWrite(baseline, successor.entityUuid)
        require(baseline["node_kind"] == JsonPrimitive("activity"))
        val actual = NextStructureMapper.readPlan(canonical, successor.entityUuid, revision)
        require(actual.completionPolicy == "recurring" && actual.isActive &&
            baseline["node_kind"] == canonical["node_kind"] &&
            Instant.parse(baseline.getValue("created_at").jsonPrimitive.content) ==
                Instant.parse(canonical.getValue("created_at").jsonPrimitive.content))
        if (successor.action == "delete") {
            require(successor.payload.isEmpty())
            return successor.copy(operationId = replacementId, baseRevision = revision)
        }
        NextStructureMapper.validatePlanWrite(successor.payload, successor.entityUuid)
        require(baseline.keys == successor.payload.keys && canonical.keys.containsAll(baseline.keys) &&
            baseline["node_kind"] == successor.payload["node_kind"] &&
            Instant.parse(baseline.getValue("created_at").jsonPrimitive.content) ==
                Instant.parse(successor.payload.getValue("created_at").jsonPrimitive.content))
        var merged = JsonObject(canonical.filterKeys { it in baseline.keys })
        NextStructureMapper.validatePlanWrite(merged, successor.entityUuid)
        val conflicts = planPaths.filter { path ->
            val before = value(baseline, path)
            val local = value(successor.payload, path)
            val server = value(merged, path)
            !same(path, before, local) && !same(path, before, server) && !same(path, local, server)
        }.sorted()
        if (conflicts.isNotEmpty()) throw NextStructuralCausalConflict(conflicts)
        for (path in planPaths) {
            val local = value(successor.payload, path)
            if (!same(path, value(baseline, path), local)) merged = set(merged, path.split('.'), requireNotNull(local))
        }
        NextStructureMapper.validatePlanWrite(merged, successor.entityUuid)
        return successor.copy(operationId = replacementId, baseRevision = revision, payload = merged)
    }

    fun merge(predecessor: SyncV2Operation, successor: SyncV2Operation,
        confirmed: NextSyncOperationResult, replacementId: String,
        submittedPredecessor: SyncV2Operation = predecessor): SyncV2Operation {
        require(isContractUuid(replacementId) && replacementId != predecessor.operationId && replacementId != successor.operationId)
        require(predecessor.operationId != successor.operationId && predecessor.entityType == successor.entityType &&
            predecessor.entityUuid == successor.entityUuid && predecessor.action == "upsert" &&
            (successor.action == "upsert" || successor.action == "delete" &&
                successor.entityType in setOf("plan_node", "activity_metric_link")))
        val paths = when (predecessor.entityType) {
            "plan_node" -> planPaths
            "metric" -> metricPaths
            "activity_metric_link" -> linkPaths
            else -> error("Only ordinary structural upserts can be rebased")
        }
        validateWrite(predecessor.entityType, predecessor.entityUuid, predecessor.payload)
        if (successor.action == "upsert") validateWrite(successor.entityType, successor.entityUuid, successor.payload)
        else require(if (predecessor.payload["node_kind"] == JsonPrimitive("goal"))
            successor.payload.keys == setOf("child_policy") && successor.payload["child_policy"] in
                setOf(JsonPrimitive("cascade_children"), JsonPrimitive("detach_children")) else successor.payload.isEmpty())
        // A preceding logical intent may itself have a replacement. Keep its ORIGINAL local
        // baseline, but bind the receipt to the actually submitted operation, never to a guessed ID.
        require(submittedPredecessor.entityType == predecessor.entityType &&
            submittedPredecessor.entityUuid == predecessor.entityUuid && submittedPredecessor.action == "upsert" &&
            replacementId != submittedPredecessor.operationId)
        validateWrite(submittedPredecessor.entityType, submittedPredecessor.entityUuid, submittedPredecessor.payload)
        validateTaskResultBinding(submittedPredecessor, confirmed)
        require(confirmed.status in setOf("applied", "already_applied") && confirmed.revision != null &&
            confirmed.revision > 0 && confirmed.revision >= (submittedPredecessor.baseRevision ?: 0) &&
            confirmed.errorCode == null && confirmed.message == null && confirmed.baseEntity == null &&
            confirmed.localEntity == null && confirmed.conflictingFields.isEmpty() &&
            confirmed.conflictKind == null && confirmed.oneTimeConflict == null)
        val canonical = requireNotNull(confirmed.entity)
        when (predecessor.entityType) {
            "plan_node" -> NextStructureMapper.readPlan(canonical, predecessor.entityUuid, confirmed.revision)
            "metric" -> NextStructureMapper.readMetric(canonical, predecessor.entityUuid, confirmed.revision)
            else -> {
                val updated = requireNotNull(canonical["updated_at"] as? JsonPrimitive).also { require(it.isString) }.content
                NextCommonFactMapper.validateLinkSnapshot(SyncV2Change(0, predecessor.entityType, predecessor.entityUuid,
                    "upsert", confirmed.revision, canonical, updated))
            }
        }
        if (successor.action == "delete") {
            // Only an explicit NEW, never-transmitted delete can inherit its own proven predecessor ACK.
            // Retain the exact child policy; do not infer a base from a mutable current shadow.
            if (predecessor.entityType == "plan_node") {
                require(predecessor.payload["node_kind"] == canonical["node_kind"] &&
                    predecessor.payload["node_kind"] == submittedPredecessor.payload["node_kind"] &&
                    Instant.parse(predecessor.payload.getValue("created_at").jsonPrimitive.content) ==
                        Instant.parse(canonical.getValue("created_at").jsonPrimitive.content) &&
                    Instant.parse(predecessor.payload.getValue("created_at").jsonPrimitive.content) ==
                        Instant.parse(submittedPredecessor.payload.getValue("created_at").jsonPrimitive.content))
            } else for (key in listOf("activity_uuid", "metric_uuid")) {
                require(predecessor.payload[key] == canonical[key] &&
                    predecessor.payload[key] == submittedPredecessor.payload[key])
            }
            return successor.copy(operationId = replacementId, baseRevision = confirmed.revision)
        }
        require(predecessor.payload.keys == successor.payload.keys && canonical.keys.containsAll(predecessor.payload.keys))
        var merged = JsonObject(canonical.filterKeys { it in predecessor.payload.keys })
        validateWrite(predecessor.entityType, predecessor.entityUuid, merged)
        when (predecessor.entityType) {
            "plan_node" -> {
                require(predecessor.payload["node_kind"] == successor.payload["node_kind"] &&
                    predecessor.payload["node_kind"] == merged["node_kind"] &&
                    predecessor.payload["node_kind"] == submittedPredecessor.payload["node_kind"])
                fun created(body: JsonObject): Instant = requireNotNull(body["created_at"] as? JsonPrimitive)
                    .also { require(it.isString) }.content.let(Instant::parse)
                require(created(predecessor.payload) == created(successor.payload) && created(predecessor.payload) == created(merged) &&
                    created(predecessor.payload) == created(submittedPredecessor.payload))
            }
            "activity_metric_link" -> for (key in listOf("activity_uuid", "metric_uuid")) {
                require(predecessor.payload[key] == successor.payload[key] && predecessor.payload[key] == merged[key] &&
                    predecessor.payload[key] == submittedPredecessor.payload[key])
            }
        }
        val conflicts = paths.filter { path ->
            val before = value(predecessor.payload, path)
            val local = value(successor.payload, path)
            val server = value(merged, path)
            !same(path, before, local) && !same(path, before, server) && !same(path, local, server)
        }.sorted()
        if (conflicts.isNotEmpty()) throw NextStructuralCausalConflict(conflicts)
        for (path in paths) {
            val local = value(successor.payload, path)
            if (!same(path, value(predecessor.payload, path), local))
                merged = set(merged, path.split('.'), requireNotNull(local))
        }
        // Independent valid inputs may have an invalid combined date/range/policy. Never repair or send it.
        validateWrite(predecessor.entityType, predecessor.entityUuid, merged)
        return successor.copy(operationId = replacementId, baseRevision = confirmed.revision, payload = merged)
    }

    private fun validateWrite(type: String, uuid: String, body: JsonObject) {
        when (type) {
            "plan_node" -> {
                NextStructureMapper.validatePlanWrite(body, uuid)
                // This is structure only: the confirmed one-time projection is never in a write payload.
            }
            "metric" -> NextStructureMapper.validateMetricWrite(body)
            "activity_metric_link" -> NextCommonFactMapper.validateLinkWrite(body)
            else -> error("Unsupported structure")
        }
    }

    private fun value(body: JsonObject, path: String): JsonElement? {
        var current: JsonElement? = body
        for (part in path.split('.')) current = (current as? JsonObject)?.get(part)
        return current
    }

    private fun same(path: String, left: JsonElement?, right: JsonElement?): Boolean {
        if (left == right) return true
        if (path !in decimals || left == null || right == null || left == JsonNull || right == JsonNull) return false
        val a = (left as? JsonPrimitive)?.content?.toBigDecimalOrNull() ?: return false
        val b = (right as? JsonPrimitive)?.content?.toBigDecimalOrNull() ?: return false
        return a.compareTo(b) == 0
    }

    private fun set(body: JsonObject, path: List<String>, value: JsonElement): JsonObject {
        val key = path.first()
        val next = if (path.size == 1) value else set(body.getValue(key).jsonObject, path.drop(1), value)
        return JsonObject(body + (key to next))
    }
}
