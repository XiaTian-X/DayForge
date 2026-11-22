package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Pure independent observation ACK orchestration.
 * Lists/DTOs are not certificates: the caller proves complete membership, native old values,
 * unchanged global common-fact health, source authority and final ordered actual effects.
 * The complete NextCommonFactStore remains the differential oracle.
 */
internal object MetricAckFactRules {
    data class Input(
        val change: SyncV2Change,
        val operation: SyncV2Operation,
        val deviceId: String,
        val acceptedQueueId: Long,
        val shadowUpdatedAt: Long,
        val metric: MetricEntity,
        val previous: MetricLogEntity?,
        val shadow: SyncEntityStateEntity?,
        val entityPending: List<SyncOutboxEntity>,
        val entityConflicts: List<SyncConflictEntity>
    )

    data class Plan(val observationUpsert: MetricLogEntity?, val shadowUpsert: SyncEntityStateEntity?)

    fun supports(operation: SyncV2Operation, change: SyncV2Change): Boolean =
        operation.entityType == "metric_observation" && operation.action == "upsert" &&
            change.entityType == "metric_observation" && change.operation == "upsert"

    fun prepare(input: Input): Plan {
        val change = input.change
        val operation = input.operation
        require(supports(operation, change)) { "METRIC_ACK_RULES_UNSUPPORTED" }
        require(change.sequence == 0L && input.acceptedQueueId > 0 && operation.entityUuid == change.entityUuid &&
            operation.baseRevision in listOf(null, 0L))
        val payload = change.payload
        val id = change.entityUuid
        require(payload.text("metric_uuid") == input.metric.uuid)

        val old = input.shadow
        if (old?.deleted == true) fail(NextFactMergeException.Reason.ENTITY_DELETED)
        val known = old != null && old.revision == change.revision && body(old) == payload
        if (old != null) {
            // Full request acceptance calls auditShadow before committing. These header/hash
            // requirements are also explicit at the scoped read boundary, not inferred from DTOs.
            require(old.entityType == "metric_observation" && old.entityUuid == id && old.revision > 0 &&
                old.payloadJson != null && old.payloadHash == syncPayloadHash(old.payloadJson))
            if (!known) fail(NextFactMergeException.Reason.FACT_DIVERGED)
            require(Instant.parse(body(old).text("created_at")) == Instant.parse(payload.text("created_at")))
        }
        val local = input.entityPending.filter { it.recordType == "metric_log" &&
            (it.entityUuid == id || it.wireEntityUuid == id) }
        if (input.entityConflicts.any { it.entityType == "metric_observation" &&
                (it.localEntityUuid == id || it.wireEntityUuid == id) })
            fail(NextFactMergeException.Reason.LOCAL_FACT_PENDING)
        val deletedLocally = local.any { it.entityUuid == id &&
            (it.action == "delete" || it.wireEntityUuid != id) }

        // The own-operation branch always performs this immutable request proof, even for known
        // shadows and late ACKs. It compares unit to the original request, never today's metric.unit.
        NextCommonFactProof.requireOriginalPayload(operation.payload, change, input.deviceId)
        NextCommonFactMapper.validateOrdinaryFactSnapshot(change)
        val mapped = NextCommonFactMapper.observation(change, input.metric, input.previous)
        if (input.previous != null && input.previous.copy(createdAt = mapped.createdAt, updatedAt = mapped.updatedAt) != mapped)
            fail(NextFactMergeException.Reason.INVALID_LOCAL_STATE)
        val shadow = if (known) null else SyncEntityStateEntity("metric_observation", id, change.revision,
            payloadJson = payload.toString(), payloadHash = syncPayloadHash(payload.toString()),
            updatedAt = input.shadowUpdatedAt)
        // A successor deletion suppresses the local row, not the immutable acceptance shadow.
        return Plan(if (deletedLocally) null else mapped, shadow)
    }

    private fun body(row: SyncEntityStateEntity) = Json.parseToJsonElement(requireNotNull(row.payloadJson)).jsonObject
    private fun JsonObject.text(key: String): String = getValue(key).let {
        require(it is JsonPrimitive && it.isString); it.content
    }
    private fun fail(reason: NextFactMergeException.Reason): Nothing = throw NextFactMergeException(reason)
}
