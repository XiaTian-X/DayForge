package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.TokenManager

/** Reconcile retained historical commit evidence with the original current source/receipt/fate
 * rules. A missing outbox is never an absent command, and retired sources require their whole
 * original audit group. This reader cannot issue an execution ticket or mutate any record.
 */
internal class CountCommandCurrentFate(private val database: HabitDatabase, private val tokens: TokenManager) {
    suspend fun read(access: LocalCoreWriteAccess, intent: CountCommandRecords.Intent, result: CountCommandOutcome) {
        // v4 has no immutable retry lineage: its legitimate retry replaces operationId.
        // Verify the complete old T1 only; do not grant the replacement row any authority.
        if (IndependentMetricInput.isLegacy(intent)) IndependentMetricEvidence.requireLegacy(intent, result)
        else if (CountCommandLegacyInput.isLegacy(intent)) CountCommandLegacyEvidence.verify(intent, result)
        else formal(access, intent, result)
    }

    suspend fun formal(access: LocalCoreWriteAccess, intent: CountCommandRecords.Intent, result: CountCommandOutcome) {
        check(database.inTransaction())
        result.evidence.requireFormalOutcome(intent, result)
        if (result.kind == CountCommandOutcome.Kind.NO_EFFECT) return
        val sql = database.openHelper.writableDatabase
        val registered = tokens.localSyncAccess()
        if (registered != null) require(registered.session == access.session && registered.deviceId == access.capturedDeviceId)
        for (summary in result.origins) {
            val args = arrayOf<Any>(NEXT_OPERATION, summary.requestId)
            val origin = requireNotNull(database.nextRequestDao().origin(NEXT_OPERATION, summary.requestId)) { "COMMAND_ORIGINAL_ORIGIN_MISSING" }
            require(NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?", args) == summary.immutableOriginHash &&
                NextRequestSql.immutableOriginHash(origin) == summary.immutableOriginHash) { "COMMAND_ORIGINAL_ORIGIN_CHANGED" }
            val source = NextRequestSql.rowHash(database, "sync_outbox", "id=?", arrayOf(origin.queueId))
            val accepted = NextRequestSql.rowHash(database, "next_acceptances", "kind=? AND requestId=?", args)
            val sent = NextRequestSql.rowHash(database, "next_transmissions", "kind=? AND requestId=?", args)
            val rejected = NextRequestSql.rowHash(database, "next_rejections", "kind=? AND requestId=?", args)
            if (source != null) {
                require(source == origin.sourceHash && accepted == null) { "COMMAND_LIVE_SOURCE_CHANGED" }
                if (registered == null) {
                    require(origin.serverInstanceId == null && origin.syncEpoch == null && sent == null && rejected == null)
                    require(NextRequestSql.rowHash(database, "next_abandoned_requests", "kind=? AND requestId=?", args) == null)
                } else {
                    require(NextOriginalRequestProofStore(database).liveOrigin(registered, NEXT_OPERATION, summary.requestId, requirePermission = false) == origin)
                    if (sent != null) NextOriginalRequestProofStore(database).journal(origin,
                        requireNotNull(database.nextRequestDao().transmission(NEXT_OPERATION, summary.requestId)), registered)
                    if (rejected != null) require(NextOriginalRequestProofStore(database).rejection(registered, NEXT_OPERATION, summary.requestId).origin == origin)
                }
            } else if (accepted != null) {
                val sync = requireNotNull(registered)
                val proof = if (summary.entityType == "plan_node")
                    NextStructuralCausalStore(database).recoveryAcceptedOperationProof(summary.requestId, sync)
                else NextAcceptedFactProofStore(database).read(sync, summary.requestId)
                require(proof.first.operationId == summary.operationId && proof.first.entityUuid == summary.entityUuid)
            } else {
                val retired = NextRecoveryAuditStore(database).source(requireNotNull(registered), NextRecoveryKey(NEXT_OPERATION, summary.requestId))
                    ?: error("COMMAND_SOURCE_FATE_UNRESOLVED")
                require(retired.second.id == origin.queueId && NextRequestSql.sourceHash(retired.second) == origin.sourceHash)
            }
            // Source aliases cannot reappear after retirement and change the request's fate.
            if (source == null) require(database.syncOutboxDao().getByOperationId(summary.requestId) == null)
        }
    }
}
