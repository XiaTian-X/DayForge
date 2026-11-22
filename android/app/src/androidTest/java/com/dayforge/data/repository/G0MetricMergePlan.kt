package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import java.time.Instant
import kotlinx.serialization.json.*

/** Independent pure metric ACK merge. This value is NOT a trusted runtime certificate.
 * Its complete input must be captured under a verified snapshot; applying its output still
 * requires closed SQL, dependency revalidation and exact mutation auditing by the owner.
 */
internal object G0MetricMergePlan {
    data class Input(
        val current: MetricEntity?,
        val shadow: SyncEntityStateEntity?,
        val otherPending: Boolean,
        val conflict: Boolean,
        val names: Map<String, Set<String>>
    )
    data class Output(val metric: MetricEntity?, val shadow: SyncEntityStateEntity?)

    class Prepared internal constructor(private val before: Input, val output: Output) {
        /** The caller must supply a freshly captured, trusted view. This is a dependency
         * predicate only; it does not authorize the caller or certify unread database regions.
         */
        fun revalidate(actual: Input) {
            require(before.current == actual.current && before.shadow == actual.shadow &&
                before.otherPending == actual.otherPending && before.conflict == actual.conflict) { "G0_METRIC_DEPENDENCIES_CHANGED" }
            output.metric?.let { update ->
                require(before.names[update.name].orEmpty() == actual.names[update.name].orEmpty()) { "G0_METRIC_NAME_RANGE_CHANGED" }
            }
        }
    }

    fun freeze(change: SyncV2Change, input: Input, receivedAt: Long): Prepared {
        val owned = input.copy(names = input.names.mapValues { (_, values) -> values.toSet() })
        return Prepared(owned, prepare(change, owned, receivedAt))
    }

    fun prepare(change: SyncV2Change, input: Input, receivedAt: Long): Output {
        require(change.entityType == "metric" && change.operation == "upsert" && change.sequence == 0L)
        // Decode even when the supplied revision is old. The shared strict wire parser is
        // not the merge validator: all decisions below are independent of NextStructureStore.
        val incoming = NextStructureMapper.readMetric(change.payload, change.entityUuid, change.revision, input.current)
        val current = input.current
        if (current != null) {
            if (current.appearance == null) reject(NextStructureMergeException.Reason.UNINITIALIZED)
            if (input.shadow != null) require(current.createdAt == incoming.createdAt)
        }
        val previous = input.shadow
        if (previous != null) {
            require(previous.revision > 0)
            if (!previous.deleted) {
                val oldBody = Json.parseToJsonElement(requireNotNull(previous.payloadJson)).jsonObject
                require(created(oldBody) == created(change.payload))
            }
            if (previous.revision > change.revision) return Output(null, null)
            if (previous.deleted) reject(NextStructureMergeException.Reason.ENTITY_DELETED)
            if (previous.revision == change.revision) {
                if (previous.payloadJson?.let { Json.parseToJsonElement(it) } != change.payload)
                    reject(NextStructureMergeException.Reason.STRUCTURE_DIVERGED)
                if (current == null) reject(NextStructureMergeException.Reason.INVALID_LOCAL_STATE)
                return Output(null, null)
            }
        }
        if (input.conflict) reject(NextStructureMergeException.Reason.LOCAL_STRUCTURE_PENDING)
        val update = if (input.otherPending) {
            if (current != null && current.createdAt != incoming.createdAt) current.copy(createdAt = incoming.createdAt) else null
        } else incoming
        if (update != null) require(input.names[update.name].orEmpty().all { it == update.uuid })
        return Output(update, SyncEntityStateEntity("metric", change.entityUuid, change.revision,
            payloadJson = change.payload.toString(), payloadHash = syncPayloadHash(change.payload.toString()), updatedAt = receivedAt))
    }

    private fun created(body: JsonObject): Instant {
        val value = body.getValue("created_at")
        require(value is JsonPrimitive && value.isString)
        return Instant.parse(value.content)
    }
    private fun reject(reason: NextStructureMergeException.Reason): Nothing = throw NextStructureMergeException(reason)
}
