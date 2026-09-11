package com.dayforge.domain.service

import com.dayforge.data.export.ConfigMapper
import com.dayforge.data.export.dto.ConfigExportDto
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for serializing habit configuration as shareable JSON.
 *
 * Export includes:
 * - All habits (with parent-child hierarchy)
 * - All metrics
 * - All active habit-metric links
 *
 * Export excludes:
 * - Completion history
 * - Time logs
 * - Metric logs
 * - Local database IDs and timestamps
 *
 * The settings screen writes the returned JSON through Android's CreateDocument flow,
 * so the user chooses the destination.
 */
@Singleton
class ConfigExportService @Inject constructor(
    private val habitDao: HabitDao,
    private val metricDao: MetricDao,
    private val linkDao: HabitMetricLinkDao
) {
    /**
     * Export all habit configuration to a JSON string.
     * Used with CreateDocument to let user choose save location.
     *
     * @return Result.success with JSON string on success,
     *         Result.failure with Exception on error
     */
    suspend fun exportConfigToJson(): Result<String> {
        return try {
            // 1. Query all entities
            val habits = habitDao.getAllHabitsOnce()
            val metrics = metricDao.getAllMetricsOnce()
            val links = linkDao.getAllActiveLinks()

            // 2. Convert to DTOs (sorted: parents first for habit hierarchy)
            val sortedHabits = habits.sortedBy { it.parentHabitId != null }
            val habitDtos = sortedHabits.map { ConfigMapper.habitEntityToDto(it) }
            val metricDtos = metrics.map { ConfigMapper.metricEntityToDto(it) }
            val linkDtos = links.map { ConfigMapper.linkEntityToDto(it) }

            // 3. Build export DTO with schema version
            val config = ConfigExportDto(
                schemaVersion = 1,
                habits = habitDtos,
                metrics = metricDtos,
                links = linkDtos
            )

            // 4. Serialize to JSON (pretty print for readability)
            val json = Json { prettyPrint = true }
            val jsonString = json.encodeToString(config)

            Result.success(jsonString)
        } catch (e: Exception) {
            Result.failure(IOException("Export failed: ${e.message}", e))
        }
    }
}
