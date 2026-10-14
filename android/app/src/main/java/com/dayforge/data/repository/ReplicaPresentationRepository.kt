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
internal data class ReplicaPresentationCheck(val problem: ReplicaPresentationProblem? = null,
    val phase: ReplicaSwitchPhase? = null)

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
        val before = tokens.replicaPresentationWitness()
        val sql = readFenceInTransaction()
        check(before == tokens.replicaPresentationWitness()) { "SYNC_REPLICA_STAGE_CHANGED" }
        when {
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

    /** Room's typed conversion can coerce SQLite values: validate actual storage classes first. */
    internal fun readFenceInTransaction(): NextReplicaFenceEntity? {
        check(database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
        return database.openHelper.writableDatabase.query(
            "SELECT * FROM next_replica_fence ORDER BY id").use { cursor ->
            val text = listOf("recoveryId", "accountId", "serverInstanceId", "oldEpoch", "newEpoch",
                "authenticationGeneration", "deviceId", "sourceHash", "mode")
            check(cursor.columnNames.toSet() == (text + listOf("id", "capabilityRevision")).toSet() &&
                cursor.count <= 1) { "SYNC_REPLICA_FENCE_INVALID" }
            if (!cursor.moveToFirst()) return@use null
            check(text.all { cursor.getType(cursor.getColumnIndexOrThrow(it)) == Cursor.FIELD_TYPE_STRING } &&
                listOf("id", "capabilityRevision").all { cursor.getType(cursor.getColumnIndexOrThrow(it)) == Cursor.FIELD_TYPE_INTEGER }) {
                "SYNC_REPLICA_FENCE_INVALID"
            }
            fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
            check(cursor.getLong(cursor.getColumnIndexOrThrow("id")) == 1L) { "SYNC_REPLICA_FENCE_INVALID" }
            NextReplicaFenceEntity(string("recoveryId"), string("accountId"), string("serverInstanceId"),
                string("oldEpoch"), string("newEpoch"), string("authenticationGeneration"), string("deviceId"),
                cursor.getLong(cursor.getColumnIndexOrThrow("capabilityRevision")), string("sourceHash"), string("mode"))
        }
    }
}
