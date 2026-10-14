package com.dayforge.data.local.dao

import androidx.room.*
import com.dayforge.data.local.entity.HabitEntity
import kotlinx.coroutines.flow.Flow

// Raw one-shot queries remain available to authenticated sync/ACK and retained fact readers.
private const val VISIBLE_HABIT = " NOT EXISTS (SELECT 1 FROM sync_outbox q JOIN next_request_origins o ON o.kind='sync_operation' AND o.requestId=q.operationId AND o.queueId=q.id AND o.protocol=5 WHERE q.recordType='habit' AND q.action='delete' AND q.entityUuid=habits.uuid) "

@Dao
interface HabitDao {

    @Query("SELECT * FROM habits WHERE isActive = 1 AND" + VISIBLE_HABIT + "ORDER BY createdAt DESC")
    fun getActiveHabitsWithBestTime(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE" + VISIBLE_HABIT + "ORDER BY createdAt DESC")
    fun getAllHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE" + VISIBLE_HABIT + "ORDER BY createdAt DESC")
    suspend fun getVisibleHabitsOnce(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id=:id AND" + VISIBLE_HABIT)
    suspend fun getVisibleHabitById(id: Long): HabitEntity?

    @Query("SELECT * FROM habits WHERE id=:id AND" + VISIBLE_HABIT)
    fun getVisibleHabitByIdSync(id: Long): HabitEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox q JOIN next_request_origins o ON o.kind='sync_operation' AND o.requestId=q.operationId AND o.queueId=q.id AND o.protocol=5 WHERE q.recordType='habit' AND q.action='delete' AND q.entityUuid=:uuid)")
    suspend fun hasPendingNextDeletion(uuid: String): Boolean

    @Query("SELECT * FROM habits ORDER BY createdAt DESC")
    suspend fun getAllHabitsOnce(): List<HabitEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM habits WHERE planMetadata IS NOT NULL OR appearance IS NOT NULL OR schedule LIKE '%\"once\"%' OR completionPolicy IS NOT NULL OR oneTimeConfirmedVersion IS NOT NULL OR oneTimeConfirmedHeadEventUuid IS NOT NULL OR oneTimeConfirmedCompletionEventUuid IS NOT NULL)")
    suspend fun hasProtocolNextState(): Boolean

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun getHabitById(id: Long): HabitEntity?

    @Query("SELECT * FROM habits WHERE id = :id")
    fun getHabitByIdSync(id: Long): HabitEntity?

    @Query("SELECT * FROM habits WHERE id = :id AND" + VISIBLE_HABIT)
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

    @Query("SELECT * FROM habits WHERE parentHabitId = :parentUuid AND" + VISIBLE_HABIT + "ORDER BY createdAt DESC")
    fun getChildrenByParentUuid(parentUuid: String): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE parentHabitId = :parentUuid AND" + VISIBLE_HABIT + "ORDER BY createdAt DESC")
    suspend fun getVisibleChildrenByParentUuidOnce(parentUuid: String): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE parentHabitId = :parentUuid ORDER BY createdAt DESC")
    suspend fun getChildrenByParentUuidOnce(parentUuid: String): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE parentHabitId IS NULL AND" + VISIBLE_HABIT + "ORDER BY createdAt DESC")
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
