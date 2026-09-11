package com.dayforge.data.local.dao

import androidx.room.*
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitMetricLinkDao {
    /**
     * Observe active links together with their metric metadata and latest recorded value.
     * Room invalidates this flow when any of the joined tables changes.
     */
    @Query(LINKED_METRIC_SNAPSHOT_QUERY)
    fun observeLinkedMetricSnapshots(): Flow<List<LinkedMetricSnapshot>>

    /** One-shot variant used when deciding whether a completed habit needs a metric prompt. */
    @Query("$LINKED_METRIC_SNAPSHOT_QUERY AND links.habitId = :habitId")
    suspend fun getLinkedMetricSnapshots(habitId: Long): List<LinkedMetricSnapshot>

    // Queries by habit
    @Query("SELECT * FROM habit_metric_links WHERE habitId = :habitId AND isActive = 1")
    fun getLinksByHabit(habitId: Long): Flow<List<HabitMetricLinkEntity>>

    @Query("SELECT * FROM habit_metric_links WHERE habitId = :habitId")
    suspend fun getAllLinksForHabit(habitId: Long): List<HabitMetricLinkEntity>

    // Synchronous version for use in widget (non-coroutine context)
    @Query("SELECT * FROM habit_metric_links WHERE habitId = :habitId")
    fun getLinksByHabitSync(habitId: Long): List<HabitMetricLinkEntity>

    // Queries by metric
    @Query("SELECT * FROM habit_metric_links WHERE metricId = :metricId AND isActive = 1")
    fun getLinksByMetric(metricId: Long): Flow<List<HabitMetricLinkEntity>>

    /**
     * Observe all active habit-metric links.
     * Used as a trigger for UI refresh when links change (e.g., during sync).
     * @return Flow that emits the list of all active links
     */
    @Query("SELECT * FROM habit_metric_links WHERE isActive = 1")
    fun getAllActiveLinksFlow(): Flow<List<HabitMetricLinkEntity>>

    /**
     * Get all active links once (suspend version for export).
     */
    @Query("SELECT * FROM habit_metric_links WHERE isActive = 1")
    suspend fun getAllActiveLinks(): List<HabitMetricLinkEntity>

    @Query("SELECT * FROM habit_metric_links WHERE metricId = :metricId")
    suspend fun getAllLinksForMetric(metricId: Long): List<HabitMetricLinkEntity>

    // Get specific link
    @Query("SELECT * FROM habit_metric_links WHERE habitId = :habitId AND metricId = :metricId LIMIT 1")
    suspend fun getLink(habitId: Long, metricId: Long): HabitMetricLinkEntity?

    @Query("SELECT * FROM habit_metric_links WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HabitMetricLinkEntity?

    // Insert with ignore on conflict (unique constraint)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(link: HabitMetricLinkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: HabitMetricLinkEntity): Long

    @Update
    suspend fun update(link: HabitMetricLinkEntity)

    @Delete
    suspend fun delete(link: HabitMetricLinkEntity)

    /**
     * Get all links once (suspend version).
     */
    @Query("SELECT * FROM habit_metric_links ORDER BY createdAt DESC")
    suspend fun getAllLinksOnce(): List<HabitMetricLinkEntity>

    @Query("SELECT * FROM habit_metric_links WHERE uuid = :uuid LIMIT 1")
    suspend fun getLinkByUuid(uuid: String): HabitMetricLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: HabitMetricLinkEntity): Long

    // Import queries

    @Query("DELETE FROM habit_metric_links")
    suspend fun deleteAll()

    companion object {
        const val LINKED_METRIC_SNAPSHOT_QUERY = """
            SELECT links.habitId AS habitId,
                   metrics.id AS metricId,
                   metrics.name AS metricName,
                   latest.value AS latestValue,
                   metrics.unit AS unit,
                   metrics.decimalPlaces AS decimalPlaces,
                   links.showInHabitDetail AS showInHabitDetail,
                   links.promptOnComplete AS promptOnComplete
            FROM habit_metric_links AS links
            INNER JOIN metrics ON metrics.id = links.metricId
            LEFT JOIN metric_logs AS latest ON latest.id = (
                SELECT logs.id
                FROM metric_logs AS logs
                WHERE logs.metricId = metrics.id
                ORDER BY logs.date DESC, logs.id DESC
                LIMIT 1
            )
            WHERE links.isActive = 1
        """
    }
}
