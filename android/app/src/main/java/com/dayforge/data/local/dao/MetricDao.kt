package com.dayforge.data.local.dao

import androidx.room.*
import com.dayforge.data.local.entity.MetricEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for MetricEntity.
 *
 * Provides CRUD operations and sync queries for metrics.
 */
@Dao
interface MetricDao {
    @Query("SELECT EXISTS(SELECT 1 FROM metrics WHERE appearance IS NOT NULL)")
    suspend fun hasProtocolNextState(): Boolean

    /**
     * Get all active metrics, ordered by creation date (newest first).
     * Used for main screen display.
     */
    @Query("SELECT * FROM metrics WHERE isActive = 1 AND" + VISIBLE_METRIC_SQL + "ORDER BY createdAt DESC")
    fun getAllActiveMetrics(): Flow<List<MetricEntity>>

    /**
     * Get all metrics including inactive ones.
     * Used for settings/management screens.
     */
    @Query("SELECT * FROM metrics WHERE" + VISIBLE_METRIC_SQL + "ORDER BY createdAt DESC")
    fun getAllMetrics(): Flow<List<MetricEntity>>

    @Query("SELECT * FROM metrics WHERE" + VISIBLE_METRIC_SQL + "ORDER BY createdAt DESC")
    suspend fun getVisibleMetricsOnce(): List<MetricEntity>

    @Query("SELECT * FROM metrics WHERE id=:id AND" + VISIBLE_METRIC_SQL)
    suspend fun getVisibleMetricById(id: Long): MetricEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox q JOIN next_request_origins o ON o.kind='sync_operation' AND o.requestId=q.operationId AND o.queueId=q.id AND o.protocol=5 WHERE q.recordType='metric' AND q.action='delete' AND q.entityUuid=:uuid)")
    suspend fun hasPendingNextDeletion(uuid: String): Boolean

    /**
     * Raw retained rows for sync/receipt snapshots; display and export filter explicitly.
     */
    @Query("SELECT * FROM metrics ORDER BY createdAt DESC")
    suspend fun getAllMetricsOnce(): List<MetricEntity>

    /**
     * Get a single metric by its local ID.
     */
    @Query("SELECT * FROM metrics WHERE id = :id LIMIT 1")
    suspend fun getMetricById(id: Long): MetricEntity?

    /**
     * Get a metric by name (for duplicate check).
     */
    @Query("SELECT * FROM metrics WHERE name = :name AND" + VISIBLE_METRIC_SQL + "LIMIT 1")
    suspend fun getMetricByName(name: String): MetricEntity?

    /**
     * Observe a single metric by its local ID.
     * Returns a Flow that emits whenever the metric changes.
     */
    @Query("SELECT * FROM metrics WHERE id = :id AND" + VISIBLE_METRIC_SQL + "LIMIT 1")
    fun observeMetricById(id: Long): Flow<MetricEntity?>

    /**
     * Insert a new metric. Returns the generated ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metric: MetricEntity): Long

    /**
     * Update an existing metric.
     */
    @Update
    suspend fun update(metric: MetricEntity)

    /**
     * Update the aggregation type for a metric.
     * Called when user changes aggregation type in TrendChart.
     */
    @Query("UPDATE metrics SET aggregationType = :aggregationType WHERE id = :metricId")
    suspend fun updateAggregationType(metricId: Long, aggregationType: String)

    /**
     * Delete a metric.
     */
    @Delete
    suspend fun delete(metric: MetricEntity)

    /**
     * Get a metric by its UUID.
     * Used to merge stable Sync V2 identities.
     */
    @Query("SELECT * FROM metrics WHERE uuid = :uuid LIMIT 1")
    suspend fun getMetricByUuid(uuid: String): MetricEntity?

    /** The merger resolves UUID to local ID; REPLACE would delete dependent records. */
    @Transaction
    suspend fun upsert(metric: MetricEntity): Long {
        if (metric.id == 0L) return insertForSync(metric)
        check(updateForSync(metric) == 1) { "Sync target disappeared before update" }
        return metric.id
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertForSync(metric: MetricEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateForSync(metric: MetricEntity): Int

    // Import queries

    @Query("DELETE FROM metrics")
    suspend fun deleteAll()
}
