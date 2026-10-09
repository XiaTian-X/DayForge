package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.NextRequestOriginEntity

/** Private NEW-source dependency. A local projection never grants server acceptance. */
internal class NextRestartBindingStore(private val database: HabitDatabase) {
    suspend fun captured(access: LocalSyncAccess, reference: NextRestartReference, syncWatermark: Long? = null) {
        val original = NextRestartStore(database).original(access, reference.operationId)
        require(original.reference == reference) { "SYNC_RESTART_SOURCE_CHANGED" }
        syncWatermark?.let { require(original.row.queueId <= it) { "SYNC_RESTART_SOURCE_CHANGED" } }
    }

    suspend fun ready(access: LocalSyncAccess, reference: NextRestartReference): Pair<kotlinx.serialization.json.JsonObject, String> {
        captured(access, reference)
        val sql = database.openHelper.writableDatabase
        if (NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, reference.operationId)) == null ||
            NextRequestSql.rowHash(sql, "next_restart_plan_proofs", "operationId=?", arrayOf(reference.operationId)) == null)
            rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        return NextRestartStore(database).acceptedPlan(access, reference)
    }

    suspend fun requireReady(access: LocalSyncAccess, origin: NextRequestOriginEntity): String? {
        var proof: String? = null
        if (origin.kind == NEXT_OPERATION) {
            val source = roundOperationIntent(origin.intentJson) ?: return null
            source.restartFrontier?.let { reference ->
                captured(access, reference, origin.queueId - 1)
                val accepted = ready(access, reference)
                source.restartPlanProofHash?.let { require(it == accepted.second) { "SYNC_RESTART_PLAN_PROOF_CHANGED" } }
                proof = accepted.second
            }
        } else if (origin.kind == NEXT_TIMER) {
            val source = roundTimerIntent(origin.intentJson) ?: return null
            source.restartFrontier?.let { reference ->
                captured(access, reference, source.timer.planQueueWatermark)
                proof = ready(access, reference).second
            }
        }
        return proof
    }
}
