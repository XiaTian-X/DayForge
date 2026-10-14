@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class)

package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.isContractUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Private original proof, never a wire head, inferred receipt or mutable child UUID list. */
@Serializable
internal data class NextGoalChildFrontier(
    @SerialName("child_uuid") val childUuid: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("origin_hash") val originHash: String,
    @SerialName("dependency_hash") val dependencyHash: String
) {
    init {
        require(isContractUuid(childUuid) && isContractUuid(operationId))
        require(listOf(originHash, dependencyHash).all { hash -> hash.length == 64 && hash.all { it in "0123456789abcdef" } })
    }
}

/** The parent waits for every ACTUAL child ACK; queue absence and local detachment prove nothing. */
internal class NextGoalChildFrontierStore(private val database: HabitDatabase) {
    private val sql get() = database.openHelper.writableDatabase
    private val requests get() = database.nextRequestDao()

    suspend fun capture(operation: SyncV2Operation, access: LocalSyncAccess,
        existing: List<HabitEntity>): List<NextGoalChildFrontier> {
        check(database.inTransaction())
        val parent = requireNotNull(database.syncOutboxDao().getByOperationId(operation.operationId))
        val children = NextPlanDeletionStore.children(parent)
        require(children.size <= 1000 && children.toSet() == existing.filter { it.parentHabitId == operation.entityUuid }.map { it.uuid }.toSet()) {
            "SYNC_CHALLENGE_GOAL_CHILDREN_CHANGED"
        }
        return children.sorted().map { child ->
            // The transaction captures child transitions before the original parent deletion.
            val source = database.syncOutboxDao().getEntityIntents("habit", child).filter { it.id < parent.id }.maxByOrNull { it.id }
                ?: error("SYNC_CHALLENGE_GOAL_FRONTIER_REQUIRED")
            requireNotNull(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(source.id)))
                .also { require(it == NextRequestSql.sourceHash(source)) }
            val supersession = database.nextStructuralCausalDao().supersessionByReplacement(source.operationId)
            val originalId = supersession?.originalId ?: source.operationId
            val item = NextGoalChildFrontier(child, originalId,
                requireNotNull(hash("next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, originalId))),
                requireNotNull(hash("next_structural_dependencies", "operationId=?", arrayOf(originalId))))
            validateCaptured(item, operation, access)
            val actual = requireNotNull(requests.origin(NEXT_OPERATION, source.operationId))
            require(actual.queueId == source.id && actual.sourceHash == NextRequestSql.sourceHash(source))
            require(NextStructuralCausalStore(database).resolve(source.operationId, access) == source.operationId)
            requireTransition(decodeNextOperationIntent(actual.intentJson), operation, child)
            item
        }
    }

    suspend fun validateCaptured(item: NextGoalChildFrontier, parent: SyncV2Operation, access: LocalSyncAccess) {
        check(database.inTransaction())
        require(hash("next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, item.operationId)) == item.originHash &&
            hash("next_structural_dependencies", "operationId=?", arrayOf(item.operationId)) == item.dependencyHash) {
            "SYNC_CHALLENGE_GOAL_FRONTIER_CHANGED"
        }
        val original = requireNotNull(requests.origin(NEXT_OPERATION, item.operationId))
        val source = requireNotNull(roundOperationIntent(original.intentJson)) { "SYNC_CHALLENGE_GOAL_FRONTIER_REQUIRED" }
        require(source.capturedDeviceId == access.deviceId && source.operation.operationId == item.operationId)
        NextStructuralCausalStore(database).auditCaptured(item.operationId,
            LocalCoreWriteAccess(access.session, access.capabilities, access.deviceId))
        requireTransition(source.operation, parent, item.childUuid)
    }

    private fun requireTransition(child: SyncV2Operation, parent: SyncV2Operation, identity: String) {
        require(child.entityType == "plan_node" && child.entityUuid == identity && child.entityUuid != parent.entityUuid)
        val policy = parent.payload.getValue("child_policy")
        require(child.action == "delete" || policy == JsonPrimitive("detach_children") && child.action == "upsert" &&
            child.payload["node_kind"] == JsonPrimitive("activity") && child.payload["parent_uuid"] == JsonNull) {
            "SYNC_CHALLENGE_GOAL_TRANSITION_REQUIRED"
        }
    }

    /** Re-audited before first freezing, after HTTP, and before/after the final ACK transaction. */
    suspend fun requireAccepted(source: NextRoundOperationIntent, access: LocalSyncAccess): String? {
        check(database.inTransaction())
        val frontier = source.goalChildFrontier ?: return null // Older original sources are never backfilled.
        val parent = source.operation
        val proofs = frontier.map { item ->
            validateCaptured(item, parent, access)
            val causal = NextStructuralCausalStore(database)
            val actualId = causal.resolve(item.operationId, access)
            val (operation, entity) = causal.acceptedPlanTransition(item.operationId, access)
            requireTransition(operation, parent, item.childUuid)
            require(entity["public_id"] == JsonPrimitive(item.childUuid) && entity["node_kind"] == JsonPrimitive("activity"))
            require(if (operation.action == "delete") entity["deleted_at"].let { it != null && it != JsonNull }
                else entity["deleted_at"] == JsonNull && entity["parent_uuid"] == JsonNull)
            listOf(item.originHash, item.dependencyHash, actualId,
                requireNotNull(hash("next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, actualId))),
                requireNotNull(hash("next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, actualId))),
                requireNotNull(hash("next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, actualId))))
        }
        return nextRequestHash(JsonArray(proofs.map { JsonArray(it.map(::JsonPrimitive)) }).toString().toByteArray(Charsets.UTF_8))
    }

    private suspend fun hash(table: String, where: String, args: Array<Any>) = NextRequestSql.rowHash(sql, table, where, args)
}
