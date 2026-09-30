package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.dayforge.data.local.entity.NextRecoveryStateEntity

@Dao
interface NextRecoveryDao {
    @Query("SELECT * FROM next_recovery_state WHERE id=1")
    suspend fun state(): NextRecoveryStateEntity?

    @Insert
    suspend fun insert(state: NextRecoveryStateEntity): Long

    @Update
    suspend fun update(state: NextRecoveryStateEntity): Int
}
