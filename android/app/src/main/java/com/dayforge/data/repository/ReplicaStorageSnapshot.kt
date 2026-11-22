package com.dayforge.data.repository

import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One recovery participant's complete native observation, never a reusable authority/proof input.
 * Only the controlled no-write scope can construct it; no caller can supply stored contents.
 * Independent entry points own new scopes. The local pipeline may explicitly share one
 * scope across its contiguous participants; hashes alone never enable this.
 */
internal class ReplicaStorageSnapshot private constructor(
    private val database: HabitDatabase,
    private val owner: Job
) {
    private var open = true
    private var captured: ReplicaStorageProof? = null
    private var capturedProjection: ReplicaProjection? = null

    internal suspend fun projection(database: HabitDatabase): ReplicaProjection {
        // Even a hit rechecks this exact DB/Job/active no-write scope through storage().
        // A derived immutable value is not permission and is never reused by a later scope.
        val source = storage(database)
        capturedProjection?.let { return it }
        return ReplicaProjection.fromStorage(source).also {
            currentCoroutineContext().ensureActive()
            capturedProjection = it
        }
    }

    /** Checks the opaque observation before a borrowing participant can perform work.
     * This never reads or authorizes a new transaction and cannot extend the native lease.
     */
    internal suspend fun requireCurrent(database: HabitDatabase) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        check(open && this.database === database && caller[Job] === owner && database.inTransaction()) {
            "SYNC_REPLICA_SNAPSHOT_EXPIRED"
        }
    }

    internal suspend fun storage(database: HabitDatabase): ReplicaStorageProof {
        requireCurrent(database)
        val caller = currentCoroutineContext()
        captured?.let { return it }
        // Keep the original full typed reader, bounds, membership and raw-byte hash. Only
        // repeated execution within this native no-write scope is removed.
        return NextReplicaStorageProof(database).readInTransaction().also {
            caller.ensureActive()
            captured = it
        }
    }

    companion object {
        internal suspend fun <T> inspect(database: HabitDatabase, block: suspend (ReplicaStorageSnapshot?) -> T): T {
            check(database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
            val runtime = CountTrustRuntime.forDatabase(database) ?: return block(null)
            return runtime.withReadSnapshot(database) {
                val snapshot = ReplicaStorageSnapshot(database, requireNotNull(currentCoroutineContext()[Job]))
                try { block(snapshot) }
                finally {
                    snapshot.open = false
                    snapshot.captured = null
                    snapshot.capturedProjection = null
                }
            }
        }
    }
}
