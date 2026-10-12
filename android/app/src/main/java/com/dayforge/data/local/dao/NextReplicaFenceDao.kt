package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.dayforge.data.local.entity.NextReplicaFenceEntity

@Dao
interface NextReplicaFenceDao {
    @Query("SELECT * FROM next_replica_fence ORDER BY id")
    suspend fun rows(): List<NextReplicaFenceEntity>

    /** Extra or malformed rows must fail, never look like an unlocked database. */
    suspend fun state(): NextReplicaFenceEntity? {
        val rows = rows()
        check(rows.size <= 1) { "SYNC_REPLICA_FENCE_INVALID" }
        return rows.singleOrNull()
    }

    @Insert
    suspend fun insert(state: NextReplicaFenceEntity): Long

    @Update
    suspend fun update(state: NextReplicaFenceEntity): Int
}
