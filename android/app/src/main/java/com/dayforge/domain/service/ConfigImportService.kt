package com.dayforge.domain.service

import androidx.room.withTransaction
import com.dayforge.data.export.ConfigMapper
import com.dayforge.data.export.dto.ConfigExportDto
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for importing habit configuration from a JSON file.
 *
 * Import flow:
 * 1. Read and validate JSON structure
 * 2. Clear existing data (in transaction)
 * 3. Insert in dependency order: metrics → habits → links
 * 4. Resolve UUID → local ID for links
 *
 * Import uses a Room database transaction for atomic rollback on failure.
 * Per IMPORT-05: Failed import leaves existing data unchanged.
 */
@Singleton
class ConfigImportService @Inject constructor(
    private val habitDao: HabitDao,
    private val metricDao: MetricDao,
    private val linkDao: HabitMetricLinkDao,
    private val database: HabitDatabase,
    private val structuralEditGuard: StructuralEditGuard? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Import configuration from a file path.
     *
     * @param filePath Absolute path to the JSON file
     * @return Result.success on successful import,
     *         Result.failure with exception on error
     */
    suspend fun importConfigFromFile(filePath: String): Result<Unit> {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return Result.failure(IOException("File not found: $filePath"))
            }
            val jsonString = file.readText()
            importConfig(jsonString)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Import configuration from a JSON string.
     *
     * @param jsonString JSON string containing ConfigExportDto
     * @return Result.success on successful import,
     *         Result.failure with exception on error
     */
    suspend fun importConfig(jsonString: String): Result<Unit> {
        return try {
            structuralEditGuard?.requireAllowed()
            // 1. Validate JSON structure before making any changes
            val config = json.decodeFromString<ConfigExportDto>(jsonString)

            // 2. Validate schema version
            if (config.schemaVersion != 1) {
                return Result.failure(
                    IllegalArgumentException("Unsupported schema version: ${config.schemaVersion}. Expected: 1")
                )
            }

            ConfigImportValidator.validate(config)

            // 3. Execute import in transaction
            database.withTransaction { executeImport(config) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            Result.failure(IllegalArgumentException("Invalid JSON format: ${e.message}", e))
        } catch (e: NullPointerException) {
            Result.failure(IllegalArgumentException("Import failed: entity not found after insert", e))
        } catch (e: Exception) {
            Result.failure(IOException("Import failed: ${e.message}", e))
        }
    }

    /**
     * Execute import within a transaction.
     * Per IMPORT-06: Insert in dependency order (metrics → habits → links).
     * The caller's Room database transaction ensures atomic rollback on failure.
     */
    private suspend fun executeImport(config: ConfigExportDto) {
        // 1. Clear existing data (in dependency reverse order)
        linkDao.deleteAll()
        habitDao.deleteAll()
        metricDao.deleteAll()

        // 2. Insert metrics first (no FK dependencies)
        for (dto in config.metrics) {
            metricDao.insert(ConfigMapper.dtoToMetricEntity(dto))
        }

        // 3. Insert habits second (sorted: parents first for self-reference)
        val sortedHabits = config.habits.sortedBy { it.parentHabitUuid != null }
        for (dto in sortedHabits) {
            habitDao.insert(ConfigMapper.dtoToHabitEntity(dto))
        }

        // 4. Resolve UUID → local ID for links
        val habitIdMap = config.habits.associate { dto ->
            dto.uuid to (habitDao.getHabitByUuid(dto.uuid)?.id
                ?: throw NullPointerException("Habit not found after insert: ${dto.uuid}"))
        }
        val metricIdMap = config.metrics.associate { dto ->
            dto.uuid to (metricDao.getMetricByUuid(dto.uuid)?.id
                ?: throw NullPointerException("Metric not found after insert: ${dto.uuid}"))
        }

        // 5. Insert links third (FK to both habits and metrics)
        for (dto in config.links) {
            val habitId = habitIdMap[dto.habitUuid]
                ?: throw NullPointerException("Habit UUID not resolved: ${dto.habitUuid}")
            val metricId = metricIdMap[dto.metricUuid]
                ?: throw NullPointerException("Metric UUID not resolved: ${dto.metricUuid}")

            val entity = ConfigMapper.dtoToLinkEntity(dto, habitId, metricId)
            linkDao.insert(entity)
        }
    }
}
