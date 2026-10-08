package com.dayforge.data.repository

import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.initialChallengeRoundHead

/** Display/action proof of an unaccepted NEW creation, never an accepted checkpoint or receipt. */
internal data class NextPendingInitial(val operationId: String, val originHash: String, val sourceHash: String,
    val head: ChallengeRoundHead)

internal class NextRoundPendingInitialStore(private val database: HabitDatabase) {
    suspend fun read(access: LocalSyncAccess, metadata: ChallengeMetadata): Map<String, NextPendingInitial> {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        val known = metadata.checkpoints.map { it.head.activityUuid }.toSet()
        val rows = (database.syncOutboxDao().getAll() + database.syncOutboxDao().getDeadLetters()).filter {
            it.recordType == "habit" && it.entityUuid !in known
        }
        require(rows.size <= 10_000) { "SYNC_CHALLENGE_PENDING_LIMIT" }
        val result = linkedMapOf<String, NextPendingInitial>()
        for (row in rows) {
            val origin = database.nextRequestDao().origin(NEXT_OPERATION, row.operationId) ?: continue
            val source = roundOperationIntent(origin.intentJson) ?: continue
            if (!source.initialCreation) continue // Unknown/older writes are not relabelled as creations.
            val operation = source.operation
            val originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins",
                "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, row.operationId)))
            require(origin.kind == NEXT_OPERATION && origin.requestId == row.operationId && origin.queueId == row.id &&
                origin.protocol == 5 && origin.accountId == access.session.authentication.userId &&
                origin.serverInstanceId == access.session.serverInstanceId && origin.syncEpoch == access.session.syncEpoch &&
                source.capturedDeviceId == access.deviceId && operation.operationId == row.operationId &&
                operation.entityUuid == row.entityUuid && row.wireEntityUuid == row.entityUuid && row.action == "upsert" &&
                row.deadLetteredAt == null && NextRequestSql.sourceHash(row) == origin.sourceHash &&
                NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(row.id)) == origin.sourceHash)
            require(database.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId) == null &&
                database.nextSyncStateDao().rejections().none { it.kind == NEXT_OPERATION && it.requestId == row.operationId } &&
                NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
                    arrayOf("plan_node", row.entityUuid)) == null) { "SYNC_CHALLENGE_INITIAL_NOT_PENDING" }
            val causal = NextStructuralCausalStore(database)
            causal.auditCaptured(row.operationId, LocalCoreWriteAccess(access.session, access.capabilities, access.deviceId))
            val dependency = requireNotNull(database.nextStructuralCausalDao().dependency(row.operationId))
            require(dependency.predecessorId == null) { "SYNC_CHALLENGE_INITIAL_NOT_ROOT" }
            val head = initialChallengeRoundHead(row.entityUuid)
            require(source.context.head == head && result.put(row.entityUuid,
                NextPendingInitial(row.operationId, originHash, origin.sourceHash, head)) == null)
        }
        return result.toMap()
    }
}
