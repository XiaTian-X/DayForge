package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.ReplicaSwitchPhase
import com.dayforge.data.local.ReplicaSwitchStage
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.service.AccountSessionCoordinator

/** Journal participant, deliberately not connected to the production recovery UI.
 * The eventual owner must supply freshly verified full inputs, drain file/network work and bind
 * the durable archive before offering confirmation. FREEZING may persist before SQL acquisition;
 * that gap is blocked and explicit retry/cancel observes real state, never assumed atomicity.
 */
internal class NextReplicaStageStore(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    suspend fun active(): ReplicaSwitchStage? = tokens.activeReplicaStage()

    /** DS phases must not report rows visible only inside a caller's uncommitted outer SQL txn. */
    private suspend fun <T> exclusive(block: suspend () -> T): T {
        check(!database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        return sessions.exclusive(block)
    }

    suspend fun begin(stage: ReplicaSwitchStage): ReplicaSwitchStage = exclusive { database.withTransaction {
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
    suspend fun recordFrozen(expected: ReplicaSwitchStage): ReplicaSwitchStage = exclusive {
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

    /** Owning workflow has verified the complete source closure and actual private archive bytes. */
    suspend fun recordArchive(expected: ReplicaSwitchStage,
        receipt: com.dayforge.data.local.ReplicaStageArchive): ReplicaSwitchStage = exclusive {
        database.withTransaction {
            check(database.nextReplicaFenceDao().state() == expected.fence) { "SYNC_REPLICA_FENCE_CHANGED" }
            check(NextReplicaSourceClosure.storage(NextReplicaStorageProof(database).readInTransaction(), expected.fence) ==
                expected.sourceHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            tokens.persistReplicaStage(expected, expected.transition(ReplicaSwitchPhase.ARCHIVE_VERIFIED, receipt))
        }
    }

    suspend fun recordCommitting(expected: ReplicaSwitchStage,
        target: com.dayforge.data.local.ReplicaStageTarget): ReplicaSwitchStage = exclusive {
        database.withTransaction {
            check(database.nextReplicaFenceDao().state() == expected.fence &&
                NextReplicaSourceClosure.storage(NextReplicaStorageProof(database).readInTransaction(), expected.fence) ==
                expected.sourceHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            tokens.persistReplicaStage(expected, expected.transition(ReplicaSwitchPhase.COMMITTING, destination = target))
        }
    }

    /** Caller has cold verified the retained archive; SQL fate is always checked here as well. */
    suspend fun recordSqlCommitted(expected: ReplicaSwitchStage): ReplicaSwitchStage = exclusive {
        database.withTransaction {
            check(NextReplicaCommitStore(database, tokens, sessions).fateInTransaction(expected) == ReplicaCommitFate.COMMITTED) {
                "SYNC_REPLICA_COMMIT_STATE_INVALID"
            }
            tokens.persistReplicaStage(expected, expected.transition(ReplicaSwitchPhase.SQL_COMMITTED))
        }
    }

    /** Owner has verified the actual private archive. Keep the SQL fence and DS pointer while
     * atomically publishing the complete target scope; this is NOT ordinary activation.
     */
    suspend fun bindDestination(expected: ReplicaSwitchStage): ReplicaSwitchStage = exclusive {
        require(expected.phase == ReplicaSwitchPhase.SQL_COMMITTED)
        database.withTransaction {
            val next = expected.transition(ReplicaSwitchPhase.DESTINATION_BOUND)
            check(NextReplicaCommitStore(database, tokens, sessions).fateInTransaction(expected) == ReplicaCommitFate.COMMITTED) {
                "SYNC_REPLICA_COMMIT_STATE_INVALID"
            }
            if (tokens.activeReplicaStage() == next) {
                tokens.replicaDestinationAccess(next)
                next
            } else tokens.persistReplicaStage(expected, next)
        }
    }

    /** Persist before any SQL catch-up. A refreshed pending intent leaves an immutable audit. */
    suspend fun recordCatchup(expected: ReplicaSwitchStage,
        intent: com.dayforge.data.local.ReplicaStageCatchup): ReplicaSwitchStage = exclusive {
        require(expected.destinationPending)
        database.withTransaction {
            val control = ReplicaPresentationRepository(database, tokens, sessions).inspectWhileAccountLocked()
            check(control.problem == ReplicaPresentationProblem.RECOVERY_PENDING && control.phase == expected.phase &&
                tokens.activeReplicaStage() == expected) { "SYNC_REPLICA_STAGE_CHANGED" }
            val commits = NextReplicaCommitStore(database, tokens, sessions)
            if (expected.phase == ReplicaSwitchPhase.CATCHUP_COMMITTING)
                check(commits.catchupFateInTransaction(expected) == ReplicaCatchupFate.PREVIOUS) { "SYNC_REPLICA_ALREADY_COMMITTED" }
            else check(commits.fateInTransaction(expected) == ReplicaCommitFate.COMMITTED) { "SYNC_REPLICA_COMMIT_STATE_INVALID" }
            tokens.replicaDestinationAccess(expected)
            tokens.persistReplicaStage(expected, expected.transition(ReplicaSwitchPhase.CATCHUP_COMMITTING, pursuit = intent))
        }
    }

    /** SQL committed first; this single DS CAS publishes only that proved current checkpoint. */
    suspend fun bindCatchup(expected: ReplicaSwitchStage): ReplicaSwitchStage = exclusive {
        require(expected.phase == ReplicaSwitchPhase.CATCHUP_COMMITTING)
        database.withTransaction {
            check(NextReplicaCommitStore(database, tokens, sessions).catchupFateInTransaction(expected) == ReplicaCatchupFate.COMMITTED) {
                "SYNC_REPLICA_CATCHUP_STATE_INVALID"
            }
            tokens.replicaDestinationAccess(expected)
            tokens.persistReplicaStage(expected, expected.transition(ReplicaSwitchPhase.CATCHUP_BOUND))
        }
    }

    /** Cancel only while complete actual SQL still proves the old replica. Close SQL first,
     * then release the persistent pointer.
     * If delivery/persistence is interrupted, ordinary work stays blocked until this exact cancel
     * observes the actual closed row and finishes forward. No data or credential restoration.
     */
    suspend fun cancel(expected: ReplicaSwitchStage): ReplicaSwitchStage = exclusive {
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
            if (expected.phase == ReplicaSwitchPhase.COMMITTING) check(
                NextReplicaCommitStore(database, tokens, sessions).fateInTransaction(expected, allowClosedOld = true) == ReplicaCommitFate.OLD) {
                "SYNC_REPLICA_ALREADY_COMMITTED"
            }
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
