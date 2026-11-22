package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.ReplicaSwitchStage
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextReplicaFenceEntity

/** A recovery-owner refusal, never a general clear/reset permission. Unknown local command
 * obligations cannot be retired merely by preserving their bytes in a replica archive. */
internal class ReplicaCommandRecoveryGuard(private val database: HabitDatabase, private val tokens: TokenManager) {
    suspend fun requireNewStageClearInTransaction(expected: LocalSyncAccess) {
        check(database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        require(CountCommandRecoveryInventory(database, tokens).pendingInTransaction(expected) == 0L) {
            "SYNC_RECOVERY_REVIEW_REQUIRED"
        }
    }

    /** Current-source stages include all primary command truth and require no obligations.
     * A genuinely retained18/19 stage may continue ONLY through the original strict full
     * source projection. Root mode, old target schema or a stage name alone never exempts it.
     * Callers retain original fresh authority, exact fence, full input/archive and fate checks.
     */
    suspend fun requireRetainedSourceClearInTransaction(stage: ReplicaSwitchStage,
        source: ReplicaStorageProof, fence: NextReplicaFenceEntity?) {
        check(database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        val actual = NextReplicaSourceClosure.storage(source, fence)
        if (actual == stage.sourceHash) requireNewStageClearInTransaction(stage.original)
        else require(NextReplicaSourceClosure.matchesStorage(source, fence, stage.sourceHash)) {
            "SYNC_RECOVERY_PREVIEW_CHANGED"
        }
    }
}
