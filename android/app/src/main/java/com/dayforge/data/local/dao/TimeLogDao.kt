package com.dayforge.data.local.dao

import androidx.room.*
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.data.local.entity.TimeLogDayAllocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimeLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimerCommand(command: TimerCommandEntity): Long

    @Query("SELECT * FROM timer_command_outbox WHERE deadLetteredAt IS NULL ORDER BY id LIMIT :limit")
    suspend fun getPendingTimerCommands(limit: Int = 100): List<TimerCommandEntity>

    @Query("SELECT COUNT(*) FROM timer_command_outbox WHERE deadLetteredAt IS NULL")
    fun observePendingTimerCommandCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM timer_command_outbox WHERE deadLetteredAt IS NULL")
    suspend fun countPendingTimerCommands(): Int

    @Query("SELECT COUNT(*) FROM timer_command_outbox WHERE deadLetteredAt IS NOT NULL")
    suspend fun countRejectedTimerCommands(): Int

    @Query("SELECT * FROM timer_command_outbox WHERE deadLetteredAt IS NOT NULL ORDER BY id")
    fun observeRejectedTimerCommands(): Flow<List<TimerCommandEntity>>

    @Query("SELECT * FROM timer_command_outbox WHERE deadLetteredAt IS NOT NULL ORDER BY id")
    suspend fun getRejectedTimerCommands(): List<TimerCommandEntity>

    @Query("SELECT * FROM timer_command_outbox WHERE id = :id AND deadLetteredAt IS NOT NULL LIMIT 1")
    suspend fun getRejectedTimerCommand(id: Long): TimerCommandEntity?

    @Query("""
        UPDATE timer_command_outbox
        SET commandId = :newCommandId,
            attemptCount = 0,
            lastError = NULL,
            errorCode = NULL,
            deadLetteredAt = NULL
        WHERE id = :id AND deadLetteredAt IS NOT NULL
    """)
    suspend fun retryRejectedTimerCommand(id: Long, newCommandId: String)

    @Query("DELETE FROM timer_command_outbox WHERE id = :id")
    suspend fun deleteTimerCommand(id: Long)

    @Query("DELETE FROM timer_command_outbox WHERE sessionUuid = :sessionUuid")
    suspend fun deleteTimerCommandsForSession(sessionUuid: String)

    @Query("DELETE FROM timelogs WHERE uuid = :sessionUuid")
    suspend fun deleteTimerSession(sessionUuid: String)

    @Transaction
    suspend fun resolveRejectedTimerCommand(
        sessionUuid: String,
        removeLocalSession: Boolean
    ) {
        // Choosing the server abandons the complete local command history for
        // this session, not only the row the user happened to select.
        deleteTimerCommandsForSession(sessionUuid)
        if (removeLocalSession) deleteTimerSession(sessionUuid)
    }

    @Query("UPDATE timer_command_outbox SET attemptCount = attemptCount + 1, lastError = :message, errorCode = :errorCode WHERE id = :id")
    suspend fun markTimerCommandAttempt(id: Long, errorCode: String?, message: String?)

    @Query("UPDATE timer_command_outbox SET attemptCount = attemptCount + 1, lastError = :message, errorCode = :errorCode, deadLetteredAt = :now WHERE id = :id")
    suspend fun deadLetterTimerCommand(id: Long, errorCode: String?, message: String?, now: Long)

    @Transaction
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTimerSegment(segment: TimerSegmentEntity): Long

    @Query("UPDATE timer_segments SET endedAt = :endedAt WHERE sessionUuid = :sessionUuid AND endedAt IS NULL")
    suspend fun closeOpenTimerSegment(sessionUuid: String, endedAt: Long)

    @Query("SELECT * FROM timer_segments WHERE sessionUuid = :sessionUuid ORDER BY sequence")
    suspend fun getTimerSegments(sessionUuid: String): List<TimerSegmentEntity>

    @Query("DELETE FROM timer_segments WHERE sessionUuid = :sessionUuid")
    suspend fun deleteTimerSegments(sessionUuid: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDayAllocations(allocations: List<TimeLogDayAllocationEntity>)

    @Query("DELETE FROM timelog_day_allocations WHERE sessionUuid = :sessionUuid")
    suspend fun deleteDayAllocations(sessionUuid: String)

    @Query("SELECT * FROM timelog_day_allocations WHERE sessionUuid = :sessionUuid ORDER BY localDate")
    suspend fun getDayAllocations(sessionUuid: String): List<TimeLogDayAllocationEntity>

    @Transaction
    suspend fun replaceDayAllocations(
        sessionUuid: String,
        allocations: List<TimeLogDayAllocationEntity>
    ) {
        deleteDayAllocations(sessionUuid)
        if (allocations.isNotEmpty()) upsertDayAllocations(allocations)
    }

    @Query("""
        SELECT CAST(
            COALESCE((SELECT SUM(durationMillis) FROM timelog_day_allocations
                WHERE habitId = :habitId AND localDate = :localDate), 0) / 1000 +
            COALESCE((SELECT SUM(durationSeconds) FROM timelogs t
                WHERE t.habitId = :habitId AND t.date >= :legacyStart AND t.date < :legacyEnd
                  AND t.endTime IS NOT NULL
                  AND NOT EXISTS(SELECT 1 FROM timelog_day_allocations a WHERE a.sessionUuid = t.uuid)), 0)
            AS INTEGER
        )
    """)
    suspend fun getCompletedDurationSecondsForDate(
        habitId: Long,
        localDate: String,
        legacyStart: Long,
        legacyEnd: Long
    ): Int

    @Transaction
    suspend fun insertSyncedTimer(
        log: TimeLogEntity,
        startCommand: TimerCommandEntity,
        firstSegment: TimerSegmentEntity
    ): Long {
        val id = insert(log)
        insertTimerCommand(startCommand)
        insertTimerSegment(firstSegment)
        return id
    }

    @Query("UPDATE timelogs SET isPaused = :isPaused, pausedAt = :pausedAt, accumulatedPauseMillis = :accumulatedPauseMillis, timerNextCommandSequence = :nextSequence, timerLastCommandAt = :commandAt, timerActiveElapsedMillis = :activeElapsedMillis, timerElapsedRealtimeAnchor = :elapsedRealtimeAnchor, timerBootCount = :bootCount, updatedAt = :commandAt WHERE id = :id AND endTime IS NULL")
    suspend fun updateSyncedPauseState(
        id: Long,
        isPaused: Boolean,
        pausedAt: Long?,
        accumulatedPauseMillis: Long,
        nextSequence: Int,
        commandAt: Long,
        activeElapsedMillis: Long,
        elapsedRealtimeAnchor: Long?,
        bootCount: Int?
    ): Int

    @Transaction
    suspend fun updatePauseAndQueue(
        id: Long,
        isPaused: Boolean,
        pausedAt: Long?,
        accumulatedPauseMillis: Long,
        nextSequence: Int,
        commandAt: Long,
        activeElapsedMillis: Long,
        elapsedRealtimeAnchor: Long?,
        bootCount: Int?,
        command: TimerCommandEntity,
        resumedSegment: TimerSegmentEntity? = null
    ) {
        val updated = updateSyncedPauseState(
            id, isPaused, pausedAt, accumulatedPauseMillis, nextSequence, commandAt,
            activeElapsedMillis, elapsedRealtimeAnchor, bootCount
        )
        check(updated == 1) { "Active timer changed before pause transition" }
        insertTimerCommand(command)
        if (isPaused) {
            closeOpenTimerSegment(command.sessionUuid, commandAt)
        } else {
            requireNotNull(resumedSegment)
            insertTimerSegment(resumedSegment)
        }
    }

    @Query("UPDATE timelogs SET endTime = :endTime, durationSeconds = :durationSeconds, isPaused = 0, pausedAt = NULL, accumulatedPauseMillis = :accumulatedPauseMillis, timerNextCommandSequence = :nextSequence, timerLastCommandAt = :endTime, timerActiveElapsedMillis = :activeElapsedMillis, timerElapsedRealtimeAnchor = NULL, updatedAt = :endTime WHERE id = :id AND endTime IS NULL")
    suspend fun finishSyncedTimer(
        id: Long,
        endTime: Long,
        durationSeconds: Int,
        accumulatedPauseMillis: Long,
        nextSequence: Int,
        activeElapsedMillis: Long
    ): Int

    @Transaction
    suspend fun finishTimerAndQueue(
        id: Long,
        endTime: Long,
        durationSeconds: Int,
        accumulatedPauseMillis: Long,
        nextSequence: Int,
        activeElapsedMillis: Long,
        command: TimerCommandEntity,
        wasPaused: Boolean
    ) {
        val updated = finishSyncedTimer(
            id, endTime, durationSeconds, accumulatedPauseMillis, nextSequence,
            activeElapsedMillis
        )
        check(updated == 1) { "Active timer changed before stop transition" }
        insertTimerCommand(command)
        if (!wasPaused) closeOpenTimerSegment(command.sessionUuid, endTime)
    }

    @Transaction
    suspend fun deleteTimerAndQueue(log: TimeLogEntity, command: TimerCommandEntity) {
        check(log.endTime == null) { "Only an active timer can be cancelled" }
        val deleted = delete(log)
        check(deleted == 1) { "Active timer changed before cancel transition" }
        deleteTimerSegments(log.uuid)
        deleteDayAllocations(log.uuid)
        insertTimerCommand(command)
    }
    @Query("SELECT * FROM timelogs WHERE habitId = :habitId ORDER BY startTime DESC")
    fun getTimeLogsByHabit(habitId: Long): Flow<List<TimeLogEntity>>

    @Query("SELECT * FROM timelogs WHERE habitId = :habitId ORDER BY startTime DESC")
    suspend fun getAllTimeLogsForHabit(habitId: Long): List<TimeLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(timeLog: TimeLogEntity): Long

    @Delete
    suspend fun delete(timeLog: TimeLogEntity): Int

    @Query("SELECT * FROM timelogs WHERE uuid = :uuid LIMIT 1")
    suspend fun getTimeLogByUuid(uuid: String): TimeLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(timeLog: TimeLogEntity): Long

    // Active timer query methods

    /**
     * Get the currently active timer (endTime IS NULL means still running).
     * Returns null if no timer is active.
     */
    @Query("SELECT * FROM timelogs WHERE endTime IS NULL LIMIT 1")
    suspend fun getActiveTimeLog(): TimeLogEntity?

    /**
     * Flow version for observing active timer state.
     * Used by ViewModel to reactively update UI.
     */
    @Query("SELECT * FROM timelogs WHERE endTime IS NULL LIMIT 1")
    fun getActiveTimeLogFlow(): Flow<TimeLogEntity?>

    /**
     * Get a TimeLog by ID - for debugging.
     */
    @Query("SELECT * FROM timelogs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TimeLogEntity?

    /**
     * Get active timer for a specific habit.
     * Used to check if a habit already has an active timer.
     */
    @Query("SELECT * FROM timelogs WHERE habitId = :habitId AND endTime IS NULL LIMIT 1")
    suspend fun getActiveTimeLogForHabit(habitId: Long): TimeLogEntity?

    /**
     * Delete all timelogs for a habit.
     * Used when reactivating a completed habit with target cycles.
     * @param habitId The ID of the habit
     */
    @Query("DELETE FROM timelogs WHERE habitId = :habitId")
    suspend fun deleteByHabitId(habitId: Long)

    /**
     * Count distinct days with timelogs for a habit.
     * Used for TIMER habit target progress calculation (TARGET-06).
     * @param habitId The ID of the habit
     * @return Number of distinct days with at least one timelog
     */
    @Query("""
        SELECT COUNT(*) FROM (
            SELECT localDate AS dayKey FROM timelog_day_allocations
            WHERE habitId = :habitId GROUP BY localDate
            UNION
            SELECT strftime('%Y-%m-%d', date / 1000, 'unixepoch', 'localtime') AS dayKey
            FROM timelogs t
            WHERE habitId = :habitId AND endTime IS NOT NULL
              AND NOT EXISTS(SELECT 1 FROM timelog_day_allocations a WHERE a.sessionUuid = t.uuid)
            GROUP BY date
        )
    """)
    suspend fun getDistinctDayCount(habitId: Long): Int

    /**
     * Count distinct days where timer target was met (sum of duration >= targetSeconds).
     * Used for TIMER habit progress tracking toward targetCycles.
     * Only counts days where the daily duration meets or exceeds the target.
     * @param habitId The ID of the habit
     * @param targetSeconds The minimum duration in seconds required
     * @return Number of distinct days meeting the target
     */
    @Query("""
        SELECT COUNT(*) FROM (
            SELECT dayKey FROM (
                SELECT localDate AS dayKey, durationMillis AS durationMs
                FROM timelog_day_allocations WHERE habitId = :habitId
                UNION ALL
                SELECT strftime('%Y-%m-%d', date / 1000, 'unixepoch', 'localtime') AS dayKey,
                    durationSeconds * 1000 AS durationMs
                FROM timelogs t
                WHERE habitId = :habitId AND endTime IS NOT NULL
                  AND NOT EXISTS(SELECT 1 FROM timelog_day_allocations a WHERE a.sessionUuid = t.uuid)
            )
            GROUP BY dayKey
            HAVING SUM(durationMs) >= :targetSeconds * 1000
        )
    """)
    suspend fun getTargetMetDayCount(habitId: Long, targetSeconds: Int): Int

    /**
     * Get the first time log date for a habit.
     * Used as the cycle start date for failure checking.
     * @param habitId The ID of the habit
     * @return The earliest time log date in millis, or null if no timelogs
     */
    @Query("""
        SELECT MIN(dayEpoch) FROM (
            SELECT localDateEpoch AS dayEpoch FROM timelog_day_allocations WHERE habitId = :habitId
            UNION ALL
            SELECT date AS dayEpoch FROM timelogs t
            WHERE habitId = :habitId AND endTime IS NOT NULL
              AND NOT EXISTS(SELECT 1 FROM timelog_day_allocations a WHERE a.sessionUuid = t.uuid)
        )
    """)
    suspend fun getFirstTimeLogDate(habitId: Long): Long?

    /**
     * Check if a habit has any time log records (including active timers).
     * Used to determine if target cycles modification should be blocked for TIMER habits.
     * @param habitId The ID of the habit to check
     * @return true if habit has at least one time log (completed or active), false otherwise
     */
    @Query("SELECT EXISTS(SELECT 1 FROM timelogs WHERE habitId = :habitId LIMIT 1)")
    suspend fun hasTimeLogs(habitId: Long): Boolean

    /**
     * Count all time logs in the database.
     * Used for import confirmation dialog to show deletion count.
     */
    @Query("SELECT COUNT(*) FROM timelogs")
    suspend fun countAll(): Int
}
