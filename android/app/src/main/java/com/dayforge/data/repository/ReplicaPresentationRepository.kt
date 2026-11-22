package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal enum class ReplicaPresentationProblem { RECOVERY_PENDING, ORPHAN_STAGE, DAMAGED_STATE }
internal data class ReplicaPublicationSource(val storageId: String?)
internal data class ReplicaPresentationCheck(val problem: ReplicaPresentationProblem? = null,
    val phase: ReplicaSwitchPhase? = null)

/** Decoded SQL content only. Query/schema/native END failures are never represented here. */
internal data class LegacySessionFenceInspection(
    val presentation: ReplicaPresentationCheck, val cause: Throwable? = null
)

/** Read barrier, NOT an archive/COMMIT/material/activation proof. No business rows or files are read. */
@Singleton
class ReplicaPresentationRepository @Inject constructor(private val database: HabitDatabase,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator) {
    internal val changes get() = merge(tokens.replicaPresentationChanges,
        database.nextReplicaFenceDao().changes().map { Unit }).retryWhen { error, _ ->
        if (error is CancellationException) throw error
        // Observer failures must revoke an existing Open state as well as cold-start admission.
        emit(Unit); delay(1_000); true
    }

    internal suspend fun inspect(): ReplicaPresentationCheck = sessions.exclusive { inspectWhileAccountLocked() }

    /** Only callers already owning the shared account lock may enter; Room nesting is read-only. */
    internal suspend fun inspectWhileAccountLocked(): ReplicaPresentationCheck = database.withTransaction {
        inspectInTransactionWhileAccountLocked()
    }

    /** The fate snapshot scope already owns the native transaction and forbids nested BEGIN. */
    internal suspend fun inspectInTransactionWhileAccountLocked(): ReplicaPresentationCheck {
        check(database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        val before = tokens.replicaPresentationWitness()
        val sql = readFenceInTransaction()
        check(before == tokens.replicaPresentationWitness()) { "SYNC_REPLICA_STAGE_CHANGED" }
        return inspectRecoveryFence(before, sql)
    }

    /** The explicit local-session upgrade supplies ONE private raw preferences witness.
     * SQL is read inside the caller's original validation transaction; the actor later CASes
     * every original Preferences field. No nested account acquisition or write occurs here. */
    internal fun inspectLegacySessionInTransactionWhileAccountLocked(
        before: ReplicaPresentationWitness
    ): LegacySessionFenceInspection {
        check(database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        return when (val content = readFenceContentInTransaction()) {
            is FenceContent.Valid -> LegacySessionFenceInspection(inspectRecoveryFence(before, content.value))
            is FenceContent.Damaged -> LegacySessionFenceInspection(
                ReplicaPresentationCheck(ReplicaPresentationProblem.DAMAGED_STATE), content.cause)
        }
    }

    private fun inspectRecoveryFence(before: ReplicaPresentationWitness,
        sql: NextReplicaFenceEntity?): ReplicaPresentationCheck = when {
            before.pointerPresent -> ReplicaPresentationCheck(ReplicaPresentationProblem.RECOVERY_PENDING,
                requireNotNull(before.stage).phase)
            sql?.mode == NextReplicaFenceEntity.FROZEN -> ReplicaPresentationCheck(ReplicaPresentationProblem.ORPHAN_STAGE)
            before.history.any { !it.terminal } ->
                ReplicaPresentationCheck(ReplicaPresentationProblem.ORPHAN_STAGE)
            sql != null && before.history.none { it.terminal &&
                it.fence.copy(mode = NextReplicaFenceEntity.CLOSED) == sql } ->
                ReplicaPresentationCheck(ReplicaPresentationProblem.ORPHAN_STAGE)
            else -> ReplicaPresentationCheck()
        }

    /** Captured by publication inside its original account boundary and one existing read TX.
     * A nullable object means presentation denied; its nullable field preserves fixed reference DBs.
     */
    internal suspend fun publicationSourceWhileAccountLocked(): ReplicaPublicationSource? = try {
        database.withTransaction {
            if (inspectInTransactionWhileAccountLocked().problem != null) null
            else ReplicaPublicationSource(LocalReplicaIdentity.readInTransaction(database))
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null // Same original display-denial rule as isOpenWhileAccountLocked, never authority.
    }

    internal suspend fun isOrdinaryOpen(): Boolean = sessions.exclusive { isOpenWhileAccountLocked() }

    internal suspend fun isOpenWhileAccountLocked(): Boolean = try { inspectWhileAccountLocked().problem == null }
    catch (error: Exception) {
        if (error is CancellationException) throw error
        false // Corrupt/failed control reads are attention, never permission or a network retry.
    }

    internal suspend fun requireOrdinaryAccess() {
        if (!isOrdinaryOpen()) throw SyncProtocolException(ReplicaSwitchGuard.PENDING)
    }

    private sealed interface FenceContent {
        data class Valid(val value: NextReplicaFenceEntity?) : FenceContent
        data class Damaged(val cause: RuntimeException) : FenceContent
    }

    /** Original readers retain their original refusal, including its cause/type. Only explicit
     * session preparation may represent semantic damaged content as a control-only graph. */
    internal fun readFenceInTransaction(): NextReplicaFenceEntity? = when (val content = readFenceContentInTransaction()) {
        is FenceContent.Valid -> content.value
        is FenceContent.Damaged -> throw content.cause
    }

    /** Room's typed conversion can coerce SQLite values: validate actual storage classes first.
     * Cursor/query/column-layout and close errors escape. Only fully read row content may be
     * Damaged, so a failed native operation can never become a successful readiness check. */
    private fun readFenceContentInTransaction(): FenceContent {
        check(database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        return database.openHelper.writableDatabase.query(
            "SELECT * FROM next_replica_fence ORDER BY id").use { cursor ->
            val text = listOf("recoveryId", "accountId", "serverInstanceId", "oldEpoch", "newEpoch",
                "authenticationGeneration", "deviceId", "sourceHash", "mode")
            check(cursor.columnNames.toSet() == (text + listOf("id", "capabilityRevision")).toSet()) {
                "SYNC_REPLICA_FENCE_INVALID"
            }
            fun damaged() = FenceContent.Damaged(IllegalStateException("SYNC_REPLICA_FENCE_INVALID"))
            if (cursor.count > 1) return@use damaged()
            if (!cursor.moveToFirst()) return@use FenceContent.Valid(null)
            if (!text.all { cursor.getType(cursor.getColumnIndexOrThrow(it)) == Cursor.FIELD_TYPE_STRING } ||
                !listOf("id", "capabilityRevision").all { cursor.getType(cursor.getColumnIndexOrThrow(it)) == Cursor.FIELD_TYPE_INTEGER }) {
                return@use damaged()
            }
            if (cursor.getLong(cursor.getColumnIndexOrThrow("id")) != 1L) return@use damaged()
            // Read native fields outside the pure entity-constructor catch.
            fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
            val recoveryId = string("recoveryId")
            val accountId = string("accountId")
            val serverInstanceId = string("serverInstanceId")
            val oldEpoch = string("oldEpoch")
            val newEpoch = string("newEpoch")
            val authenticationGeneration = string("authenticationGeneration")
            val deviceId = string("deviceId")
            val revision = cursor.getLong(cursor.getColumnIndexOrThrow("capabilityRevision"))
            val sourceHash = string("sourceHash")
            val mode = string("mode")
            try {
                FenceContent.Valid(NextReplicaFenceEntity(recoveryId, accountId, serverInstanceId,
                    oldEpoch, newEpoch, authenticationGeneration, deviceId, revision, sourceHash, mode))
            } catch (failure: IllegalArgumentException) { FenceContent.Damaged(failure) }
        }
    }
}
