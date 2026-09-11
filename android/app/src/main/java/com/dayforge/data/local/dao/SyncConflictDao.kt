package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dayforge.data.local.entity.SyncConflictEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncConflictDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(conflict: SyncConflictEntity): Long

    @Query("SELECT * FROM sync_conflicts WHERE status = 'unresolved' ORDER BY createdAt, id")
    suspend fun getUnresolved(): List<SyncConflictEntity>

    @Query("SELECT * FROM sync_conflicts WHERE status = 'unresolved' ORDER BY createdAt, id")
    fun observeUnresolved(): Flow<List<SyncConflictEntity>>

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE status = 'unresolved'")
    suspend fun countUnresolved(): Int

    @Query("SELECT * FROM sync_conflicts WHERE id = :id AND status = 'unresolved'")
    suspend fun getUnresolvedById(id: Long): SyncConflictEntity?

    @Query("""
        UPDATE sync_conflicts
        SET status = 'resolved', resolution = :resolution, resolvedAt = :resolvedAt
        WHERE id = :id AND status = 'unresolved'
    """)
    suspend fun markResolved(id: Long, resolution: String, resolvedAt: Long): Int
}
