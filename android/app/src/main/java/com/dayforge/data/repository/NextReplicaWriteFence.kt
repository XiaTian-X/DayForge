package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.NextReplicaFenceEntity

/** Internal SQL participant, not a user recovery entry. Its caller must own the authenticated
 * durable transition and all cross-store/network/publication gates before activating a fence.
 * No normal writer is exempt, and no new-replica COMMIT is implemented by this participant.
 */
internal class NextReplicaWriteFence(private val database: HabitDatabase) {
    suspend fun acquire(expected: NextReplicaFenceEntity): NextReplicaFenceEntity = database.withTransaction {
        require(expected.mode == NextReplicaFenceEntity.FROZEN)
        val dao = database.nextReplicaFenceDao()
        val actual = dao.state()
        when {
            actual == expected -> Unit // Cold retry of the very same durable stage.
            actual == null -> check(dao.insert(expected) == 1L)
            actual.mode == NextReplicaFenceEntity.CLOSED && actual.recoveryId != expected.recoveryId ->
                check(dao.update(expected) == 1)
            else -> error("SYNC_REPLICA_FENCE_CHANGED")
        }
        check(dao.state() == expected)
        expected
    }

    /** Only for a matching pre-COMMIT cancellation. A closed stage cannot be reactivated under
     * the same recovery ID, and another stage/identity cannot be silently released.
     */
    suspend fun closeBeforeCommit(expected: NextReplicaFenceEntity): NextReplicaFenceEntity = database.withTransaction {
        require(expected.mode == NextReplicaFenceEntity.FROZEN)
        val closed = expected.copy(mode = NextReplicaFenceEntity.CLOSED)
        val dao = database.nextReplicaFenceDao()
        when (dao.state()) {
            expected -> check(dao.update(closed) == 1)
            closed -> Unit
            else -> error("SYNC_REPLICA_FENCE_CHANGED")
        }
        check(dao.state() == closed)
        closed
    }
}
