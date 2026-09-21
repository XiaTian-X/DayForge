package com.dayforge.data.local.dao

import androidx.room.*
import com.dayforge.data.local.entity.HabitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {

    @Query("SELECT * FROM habits WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getActiveHabitsWithBestTime(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY createdAt DESC")
    fun getAllHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY createdAt DESC")
    suspend fun getAllHabitsOnce(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun getHabitById(id: Long): HabitEntity?

    @Query("SELECT * FROM habits WHERE id = :id")
    fun getHabitByIdSync(id: Long): HabitEntity?

    @Query("SELECT * FROM habits WHERE id = :id")
    fun getHabitByIdFlow(id: Long): Flow<HabitEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(habit: HabitEntity): Long

    @Update
    suspend fun update(habit: HabitEntity)

    @Delete
    suspend fun delete(habit: HabitEntity)

    @Query("UPDATE habits SET isActive = :isActive, updatedAt = :updatedAt WHERE id = :habitId")
    suspend fun updateIsActive(habitId: Long, isActive: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE habits SET failMode = :failMode, updatedAt = :updatedAt WHERE id = :habitId")
    suspend fun updateFailMode(habitId: Long, failMode: com.dayforge.data.model.FailMode, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM habits WHERE uuid = :uuid LIMIT 1")
    suspend fun getHabitByUuid(uuid: String): HabitEntity?

    @Query("SELECT * FROM habits WHERE name = :name LIMIT 1")
    suspend fun getHabitByName(name: String): HabitEntity?

    /** The merger resolves UUID to local ID; REPLACE would delete dependent records. */
    @Transaction
    suspend fun upsert(habit: HabitEntity): Long {
        if (habit.id == 0L) return insertForSync(habit)
        check(updateForSync(habit) == 1) { "Sync target disappeared before update" }
        return habit.id
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertForSync(habit: HabitEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateForSync(habit: HabitEntity): Int

    // Parent-child relationship queries (Task 42-01)

    @Query("SELECT * FROM habits WHERE parentHabitId = :parentUuid ORDER BY createdAt DESC")
    fun getChildrenByParentUuid(parentUuid: String): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE parentHabitId = :parentUuid ORDER BY createdAt DESC")
    suspend fun getChildrenByParentUuidOnce(parentUuid: String): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE parentHabitId IS NULL ORDER BY createdAt DESC")
    fun getTopLevelHabits(): Flow<List<HabitEntity>>

    @Query("UPDATE habits SET parentHabitId = :parentHabitId, updatedAt = :updatedAt WHERE id = :habitId")
    suspend fun updateParentHabitId(habitId: Long, parentHabitId: String?, updatedAt: Long = System.currentTimeMillis())

    // Activity rate queries

    @Query("UPDATE habits SET activityRate = :activityRate, activityRateUpdatedAt = :updatedAt WHERE id = :habitId")
    suspend fun updateActivityRate(habitId: Long, activityRate: Int, updatedAt: Long = System.currentTimeMillis())

    // Import queries

    @Query("DELETE FROM habits")
    suspend fun deleteAll()
}
