package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.local.entity.NextRejectionEntity

@Dao
interface NextSyncStateDao {
    @Query("SELECT * FROM next_sync_state ORDER BY id")
    suspend fun rows(): List<NextSyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: NextSyncStateEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(row: NextSyncStateEntity): Int

    @Query("SELECT * FROM next_rejections ORDER BY kind,requestId")
    suspend fun rejections(): List<NextRejectionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRejection(row: NextRejectionEntity)
}
