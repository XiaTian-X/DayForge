package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.dayforge.data.local.entity.NextRecoveryStateEntity

@Dao
interface NextRecoveryDao {
    @Query("SELECT * FROM next_recovery_state ORDER BY id")
    suspend fun rows(): List<NextRecoveryStateEntity>

    /** Do not hide damaged extra rows as an empty or valid singleton checkpoint. */
    suspend fun state(): NextRecoveryStateEntity? {
        val stored = rows()
        check(stored.size <= 1)
        return stored.singleOrNull()
    }

    @Insert
    suspend fun insert(state: NextRecoveryStateEntity): Long

    @Update
    suspend fun update(state: NextRecoveryStateEntity): Int
}
