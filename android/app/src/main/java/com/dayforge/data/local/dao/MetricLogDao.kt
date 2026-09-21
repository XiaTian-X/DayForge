package com.dayforge.data.local.dao

import androidx.room.*
import com.dayforge.data.local.entity.MetricLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for MetricLogEntity.
 *
 * Provides CRUD operations and sync queries for metric logs.
 */
@Dao
interface MetricLogDao {
    /**
     * Get all logs for a metric as a Flow, ordered by date descending.
     * Used for reactive UI updates.
     */
    @Query("SELECT * FROM metric_logs WHERE metricId = :metricId ORDER BY date DESC")
    fun getLogsByMetric(metricId: Long): Flow<List<MetricLogEntity>>

    /**
     * Get all logs for a metric (suspend version), ordered by date descending.
     * Used for one-shot queries.
     */
    @Query("SELECT * FROM metric_logs WHERE metricId = :metricId ORDER BY date DESC")
    suspend fun getAllLogsForMetric(metricId: Long): List<MetricLogEntity>

    /**
     * Get logs within a date range for trend chart rendering.
     * Ordered ASC for proper chart line rendering.
     */
    @Query("SELECT * FROM metric_logs WHERE metricId = :metricId AND date >= :start AND date < :end ORDER BY date ASC")
    suspend fun getLogsInRange(metricId: Long, start: Long, end: Long): List<MetricLogEntity>

    // Synchronous version for use in widget (non-coroutine context)
    @Query("SELECT * FROM metric_logs WHERE metricId = :metricId AND date >= :start AND date < :end ORDER BY date ASC")
    fun getLogsInRangeSync(metricId: Long, start: Long, end: Long): List<MetricLogEntity>

    /**
     * Get the most recent log for a metric.
     * Used for current value display on main screen.
     */
    @Query("SELECT * FROM metric_logs WHERE metricId = :metricId ORDER BY date DESC LIMIT 1")
    suspend fun getLatestLog(metricId: Long): MetricLogEntity?

    /**
     * Insert a new log. Returns the generated ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: MetricLogEntity): Long

    /** Inserts a dialog submission atomically so a partial metric set is never saved. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<MetricLogEntity>): List<Long>

    /**
     * Update an existing log.
     */
    @Update
    suspend fun update(log: MetricLogEntity)

    /**
     * Delete a log.
     */
    @Delete
    suspend fun delete(log: MetricLogEntity)

    /**
     * Get a log by its UUID.
     * Used to merge stable Sync V2 identities.
     */
    @Query("SELECT * FROM metric_logs WHERE uuid = :uuid LIMIT 1")
    suspend fun getLogByUuid(uuid: String): MetricLogEntity?

    /** Update the merger-resolved local ID; reject identity/constraint conflicts. */
    @Transaction
    suspend fun upsert(log: MetricLogEntity): Long {
        if (log.id == 0L) return insertForSync(log)
        check(updateForSync(log) == 1) { "Sync target disappeared before update" }
        return log.id
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertForSync(log: MetricLogEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateForSync(log: MetricLogEntity): Int

    /**
     * Get a log by its local ID.
     */
    @Query("SELECT * FROM metric_logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MetricLogEntity?

    /**
     * Get all metric logs as a Flow.
     * Used to trigger UI refresh when any log changes.
     */
    @Query("SELECT * FROM metric_logs ORDER BY date DESC LIMIT 1")
    fun getLatestLogFlow(): Flow<MetricLogEntity?>

    /**
     * Count all metric logs in the database.
     * Used for import confirmation dialog to show deletion count.
     */
    @Query("SELECT COUNT(*) FROM metric_logs")
    suspend fun countAll(): Int
}
