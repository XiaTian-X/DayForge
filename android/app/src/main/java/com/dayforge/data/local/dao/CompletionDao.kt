package com.dayforge.data.local.dao

import androidx.room.*
import com.dayforge.data.local.BusinessDateConverters
import com.dayforge.data.local.entity.CompletionEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
@TypeConverters(BusinessDateConverters::class)
interface CompletionDao {

    @Query("SELECT * FROM completions WHERE habitId = :habitId ORDER BY recordedLocalDate DESC, COALESCE(actualCompletedAt, date) DESC, id DESC")
    fun getCompletionsByHabit(habitId: Long): Flow<List<CompletionEntity>>

    @Query("SELECT * FROM completions ORDER BY recordedLocalDate DESC, COALESCE(actualCompletedAt, date) DESC, id DESC")
    fun getAllCompletions(): Flow<List<CompletionEntity>>

    @Query("SELECT * FROM completions WHERE id = :id")
    suspend fun getCompletionById(id: Long): CompletionEntity?

    @Query("SELECT * FROM completions WHERE habitId = :habitId AND recordedLocalDate >= :start AND recordedLocalDate < :end")
    suspend fun getCompletionsInRange(habitId: Long, start: LocalDate, end: LocalDate): List<CompletionEntity>

    @Query("SELECT * FROM completions WHERE habitId = :habitId AND recordedLocalDate >= :start AND recordedLocalDate < :end")
    fun getCompletionsInRangeSync(habitId: Long, start: LocalDate, end: LocalDate): List<CompletionEntity>

    @Query("SELECT id FROM completions WHERE habitId = :habitId AND recordedLocalDate >= :start AND recordedLocalDate < :end ORDER BY id DESC LIMIT 1")
    suspend fun getTodayCompletionId(habitId: Long, start: LocalDate, end: LocalDate): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(completion: CompletionEntity): Long

    @Delete
    suspend fun delete(completion: CompletionEntity)

    /**
     * Get all completions once (suspend version).
     */
    @Query("SELECT * FROM completions ORDER BY recordedLocalDate DESC, COALESCE(actualCompletedAt, date) DESC, id DESC")
    suspend fun getAllCompletionsOnce(): List<CompletionEntity>

    @Query("SELECT * FROM completions WHERE uuid = :uuid LIMIT 1")
    suspend fun getCompletionByUuid(uuid: String): CompletionEntity?

    /**
     * Check if a habit has any completion records.
     * Used to determine if target cycles modification should be blocked.
     * @param habitId The ID of the habit to check
     * @return true if habit has at least one completion, false otherwise
     */
    @Query("SELECT EXISTS(SELECT 1 FROM completions WHERE habitId = :habitId LIMIT 1)")
    suspend fun hasCompletions(habitId: Long): Boolean

    /**
     * Count distinct days with completions for a habit.
     * Used for progress tracking toward targetCycles.
     * Multiple completions on same day count as 1 progress day.
     * @param habitId The ID of the habit
     * @return Number of distinct days with at least one completion
     */
    @Query("SELECT COUNT(DISTINCT recordedLocalDate) FROM completions WHERE habitId = :habitId")
    suspend fun getDistinctDayCount(habitId: Long): Int

    /**
     * Count distinct days where target was met (sum of values >= targetValue).
     * Used for COUNTING habit progress tracking toward targetCycles.
     * Only counts days where the daily sum meets or exceeds the target.
     * @param habitId The ID of the habit
     * @param targetValue The minimum sum required for a day to count
     * @return Number of distinct days meeting the target
     */
    @Query("""
        SELECT COUNT(*) FROM (
            SELECT recordedLocalDate FROM completions
            WHERE habitId = :habitId
            GROUP BY recordedLocalDate
            HAVING SUM(value) >= :targetValue
        )
    """)
    suspend fun getTargetMetDayCount(habitId: Long, targetValue: Int): Int

    /**
     * Check if there's a completion on a specific date.
     * Used for STRICT failure mode checking.
     * @param habitId The ID of the habit
     * @param date The captured business date
     * @return true if there's at least one completion on that date
     */
    @Query("SELECT EXISTS(SELECT 1 FROM completions WHERE habitId = :habitId AND recordedLocalDate = :date LIMIT 1)")
    suspend fun hasCompletionOnDate(habitId: Long, date: LocalDate): Boolean

    /**
     * Get the first completion date for a habit.
     * Used as the cycle start date for failure checking.
     * @param habitId The ID of the habit
     * @return The earliest captured business date, or null if no completions
     */
    @Query("SELECT MIN(recordedLocalDate) FROM completions WHERE habitId = :habitId")
    suspend fun getFirstCompletionDate(habitId: Long): LocalDate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(completion: CompletionEntity): Long

    /**
     * Delete all completions for a habit.
     * Used when reactivating a completed habit with target cycles.
     * @param habitId The ID of the habit
     */
    @Query("DELETE FROM completions WHERE habitId = :habitId")
    suspend fun deleteByHabitId(habitId: Long)

    /**
     * Count all completions in the database.
     * Used for import confirmation dialog to show deletion count.
     */
    @Query("SELECT COUNT(*) FROM completions")
    suspend fun countAll(): Int
}
