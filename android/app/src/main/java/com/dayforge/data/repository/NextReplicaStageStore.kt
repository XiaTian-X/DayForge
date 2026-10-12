package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.ReplicaSwitchPhase
import com.dayforge.data.local.ReplicaSwitchStage
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.service.AccountSessionCoordinator

/** Pre-COMMIT journal participant, deliberately not connected to the production recovery UI.
 * The eventual owner must supply freshly verified full inputs, drain file/network work and bind
 * the durable archive before offering confirmation. FREEZING may persist before SQL acquisition;
 * that gap is blocked and explicit retry/cancel observes real state, never assumed atomicity.
 */
internal class NextReplicaStageStore(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    suspend fun active(): ReplicaSwitchStage? = tokens.activeReplicaStage()

    suspend fun begin(stage: ReplicaSwitchStage): ReplicaSwitchStage = sessions.exclusive { database.withTransaction {
        require(stage.phase == ReplicaSwitchPhase.FREEZING)
        val sql = database.nextReplicaFenceDao().state()
        check(sql == null || sql == stage.fence ||
            sql.mode == NextReplicaFenceEntity.CLOSED && sql.recoveryId != stage.recoveryId) { "SYNC_REPLICA_FENCE_CHANGED" }
        val actual = tokens.activeReplicaStage()
        if (actual == stage) {
            check(tokens.localSyncAccess() == stage.original && tokens.registeredInstallationId() == stage.installationId) {
                "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
            }
            actual
        } else {
            check(sql == null || sql.mode == NextReplicaFenceEntity.CLOSED) { "SYNC_REPLICA_FENCE_CHANGED" }
            tokens.persistReplicaStage(null, stage)
        }
    } }

    /** Does not acquire SQL from an unchecked input or assert archive verification. */
    suspend fun recordFrozen(expected: ReplicaSwitchStage): ReplicaSwitchStage = sessions.exclusive {
        database.withTransaction {
            check(database.nextReplicaFenceDao().state() == expected.fence) { "SYNC_REPLICA_FENCE_CHANGED" }
            val next = expected.transition(ReplicaSwitchPhase.FROZEN)
            if (tokens.activeReplicaStage() == next) {
                check(tokens.localSyncAccess() == expected.original &&
                    tokens.registeredInstallationId() == expected.installationId) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
                next
            } else tokens.persistReplicaStage(expected, next)
        }
    }

    /** Only pre-COMMIT phases exist here. Close SQL first, then release the persistent pointer.
     * If delivery/persistence is interrupted, ordinary work stays blocked until this exact cancel
     * observes the actual closed row and finishes forward. No data or credential restoration.
     */
    suspend fun cancel(expected: ReplicaSwitchStage): ReplicaSwitchStage = sessions.exclusive {
        val active = tokens.activeReplicaStage()
        val terminal = expected.transition(ReplicaSwitchPhase.ABORTED)
        val alreadyClosed = active == null && tokens.replicaStage(expected.recoveryId) == terminal
        check(active == expected || alreadyClosed) { "SYNC_REPLICA_STAGE_CHANGED" }
        val actualAccess = requireNotNull(tokens.localSyncAccess()) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
        check(actualAccess.session.authentication.userId == expected.accountId &&
            actualAccess == expected.original.copy(session = expected.original.session.copy(
            authentication = actualAccess.session.authentication)) &&
            tokens.registeredInstallationId() == expected.installationId) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
        database.withTransaction {
            val actual = database.nextReplicaFenceDao().state()
            when {
                actual == expected.fence && !alreadyClosed || actual == expected.fence.copy(mode = NextReplicaFenceEntity.CLOSED) ->
                    NextReplicaWriteFence(database).closeBeforeCommit(expected.fence)
                expected.phase == ReplicaSwitchPhase.FREEZING &&
                    (actual == null || actual.mode == NextReplicaFenceEntity.CLOSED && actual.recoveryId != expected.recoveryId) -> Unit
                else -> error("SYNC_REPLICA_FENCE_CHANGED")
            }
        }
        if (alreadyClosed) terminal else tokens.persistReplicaStage(expected, terminal)
    }
}
