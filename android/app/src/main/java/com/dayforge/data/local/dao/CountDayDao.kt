package com.dayforge.data.local.dao

import androidx.room.*
import com.dayforge.data.local.entity.CountDayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CountDayDao {
    @Query("SELECT * FROM count_days WHERE habitId=:habitId AND localDate=:localDate")
    suspend fun get(habitId: Long, localDate: String): CountDayEntity?

    @Query("SELECT * FROM count_days WHERE habitId=:habitId ORDER BY localDate")
    suspend fun forHabit(habitId: Long): List<CountDayEntity>

    @Query("SELECT * FROM count_days ORDER BY habitId,localDate")
    fun observeAll(): Flow<List<CountDayEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(day: CountDayEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(day: CountDayEntity): Int
}
