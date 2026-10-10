package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.initialChallengeRoundHead
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Display/action proof of an unaccepted NEW creation, never an accepted checkpoint or receipt. */
internal data class NextPendingInitial(val operationId: String, val originHash: String, val sourceHash: String,
    val head: ChallengeRoundHead)

internal class NextRoundPendingInitialStore(private val database: HabitDatabase) {
    suspend fun read(access: LocalSyncAccess, metadata: ChallengeMetadata): Map<String, NextPendingInitial> {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        require(NextChallengeStore(database).activeInTransaction(access).second == metadata) { "SYNC_CHALLENGE_INITIAL_METADATA_CHANGED" }
        val known = metadata.checkpoints.map { it.head.activityUuid }.toSet()
        val ids = sql.query("SELECT CASE WHEN typeof(id)='integer' THEN id ELSE NULL END FROM sync_outbox " +
            "WHERE recordType='habit' ORDER BY id LIMIT 10001").use { c -> buildList {
            while (c.moveToNext()) {
                require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) > 0)
                add(c.getLong(0))
            }
        } }
        require(ids.size <= 10_000) { "SYNC_CHALLENGE_PENDING_LIMIT" }
        val proofs = NextOriginalRequestProofStore(database)
        val result = linkedMapOf<String, NextPendingInitial>()
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            requireNotNull(NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(id)))
            val row = requireNotNull(database.syncOutboxDao().getById(id))
            if (row.entityUuid in known) continue
            if (NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?",
                    arrayOf(NEXT_OPERATION, row.operationId)) == null) continue
            val origin = database.nextRequestDao().origin(NEXT_OPERATION, row.operationId) ?: continue
            val source = roundOperationIntent(origin.intentJson) ?: continue
            if (!source.initialCreation) continue // Unknown/older writes are not relabelled as creations.
            val operation = source.operation
            require(proofs.inspectionOrigin(access, NEXT_OPERATION, row.operationId) == origin)
            val originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins",
                "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, row.operationId)))
            require(origin.kind == NEXT_OPERATION && origin.requestId == row.operationId && origin.queueId == row.id &&
                origin.protocol == 5 && origin.accountId == access.session.authentication.userId &&
                origin.serverInstanceId == access.session.serverInstanceId && origin.syncEpoch == access.session.syncEpoch &&
                source.capturedDeviceId == access.deviceId && operation.operationId == row.operationId &&
                operation.entityUuid == row.entityUuid && row.wireEntityUuid == row.entityUuid && row.action == "upsert" &&
                row.deadLetteredAt == null && NextRequestSql.sourceHash(row) == origin.sourceHash &&
                NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(row.id)) == origin.sourceHash)
            require(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?",
                    arrayOf(NEXT_OPERATION, row.operationId)) == null &&
                NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?",
                    arrayOf("plan_node", row.entityUuid)) == null) { "SYNC_CHALLENGE_INITIAL_NOT_PENDING" }
            val causal = NextStructuralCausalStore(database)
            causal.auditCaptured(row.operationId, LocalCoreWriteAccess(access.session, access.capabilities, access.deviceId))
            val dependency = requireNotNull(database.nextStructuralCausalDao().dependency(row.operationId))
            require(dependency.predecessorId == null) { "SYNC_CHALLENGE_INITIAL_NOT_ROOT" }
            val head = initialChallengeRoundHead(row.entityUuid)
            require(source.context.head == head)
            val classified = proofs.pending(access, NEXT_OPERATION, row.operationId)
            require(classified.origin == origin && classified.originHash == originHash)
            // A fully proven failed creation is not a birth/action ticket. Preserve ALL work and
            // the optimistic object; only its real dependants remain blocked. Transient replies
            // stay pending, and unknown/missing/damaged results are never discarded.
            if (classified.state == NextRecoveryRequestState.PERMANENT_REJECTION) continue
            require(result.put(row.entityUuid,
                NextPendingInitial(row.operationId, originHash, origin.sourceHash, head)) == null)
        }
        return result.toMap()
    }
}
