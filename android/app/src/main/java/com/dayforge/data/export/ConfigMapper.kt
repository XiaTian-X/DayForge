package com.dayforge.data.export

import com.dayforge.data.export.dto.HabitConfigDto
import com.dayforge.data.export.dto.HabitMetricLinkConfigDto
import com.dayforge.data.export.dto.MetricConfigDto
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.HabitTypeConverter
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitType
import com.dayforge.domain.util.IconMapper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Bidirectional mapper for configuration export/import.
 * Converts between entities (with local IDs) and DTOs (with portable UUIDs).
 */
object ConfigMapper {

    private val json = Json { ignoreUnknownKeys = true }

    // ==================== EXPORT: Entity → DTO ====================

    /**
     * Convert HabitEntity to portable HabitConfigDto.
     * Uses IconMapper to convert iconResId to icon name string.
     */
    fun habitEntityToDto(entity: HabitEntity): HabitConfigDto {
        return HabitConfigDto(
            uuid = entity.uuid,
            name = entity.name,
            description = entity.description,
            type = entity.habitType.name,
            icon = IconMapper.toIconName(entity.iconResId),
            color = entity.colorHex,
            isActive = entity.isActive,
            schedule = json.encodeToString(entity.schedule),
            targetCycles = entity.targetCycles,
            targetValue = entity.targetValue,
            isCountdown = entity.isCountdown,
            failMode = entity.failMode.name,
            parentHabitUuid = entity.parentHabitId,
            bestTime = entity.bestTime  // v3.7: Export best execution time
        )
    }

    /**
     * Convert MetricEntity to portable MetricConfigDto.
     * Uses IconMapper to convert iconResId to icon name string.
     */
    fun metricEntityToDto(entity: MetricEntity): MetricConfigDto {
        return MetricConfigDto(
            uuid = entity.uuid,
            name = entity.name,
            description = entity.description,
            unit = entity.unit,
            icon = IconMapper.toIconName(entity.iconResId),
            color = entity.colorHex,
            isActive = entity.isActive,
            decimalPlaces = entity.decimalPlaces,
            targetDirection = entity.targetDirection,
            targetValue = entity.targetValue,
            targetValueUpper = entity.targetValueUpper,
            aggregationType = entity.aggregationType
        )
    }

    /**
     * Convert HabitMetricLinkEntity to portable HabitMetricLinkConfigDto.
     */
    fun linkEntityToDto(entity: HabitMetricLinkEntity): HabitMetricLinkConfigDto {
        return HabitMetricLinkConfigDto(
            uuid = entity.uuid,
            habitUuid = entity.habitUuid,
            metricUuid = entity.metricUuid,
            coefficient = entity.coefficient,
            showInHabitDetail = entity.showInHabitDetail,
            promptOnComplete = entity.promptOnComplete,
            isActive = entity.isActive
        )
    }

    // ==================== IMPORT: DTO → Entity ====================

    /**
     * Convert HabitConfigDto back to HabitEntity.
     * Uses IconMapper to convert icon name to iconResId.
     * Regenerates timestamps (per EXPORT-11).
     */
    fun dtoToHabitEntity(dto: HabitConfigDto): HabitEntity {
        val now = System.currentTimeMillis()
        return HabitEntity(
            id = 0,  // Will be auto-generated on insert
            name = dto.name,
            description = dto.description,
            habitType = HabitType.valueOf(dto.type),
            iconResId = IconMapper.toIconResId(dto.icon),
            colorHex = dto.color,
            schedule = HabitTypeConverter().toSchedule(dto.schedule),
            targetValue = dto.targetValue,
            isCountdown = dto.isCountdown,
            isActive = dto.isActive,
            uuid = dto.uuid,
            parentHabitId = dto.parentHabitUuid,
            targetCycles = dto.targetCycles,
            failMode = FailMode.valueOf(dto.failMode),
            goalSuccess = null,  // Not exported, runtime state
            activityRate = 100,  // Not exported, default
            activityRateUpdatedAt = now,
            bestTime = dto.bestTime,  // v3.7: Import best execution time (nullable, backward compatible)
            createdAt = now,  // Regenerated per EXPORT-11
            updatedAt = now
        )
    }

    /**
     * Convert MetricConfigDto back to MetricEntity.
     * Uses IconMapper to convert icon name to iconResId.
     * Regenerates timestamps (per EXPORT-11).
     */
    fun dtoToMetricEntity(dto: MetricConfigDto): MetricEntity {
        val now = System.currentTimeMillis()
        return MetricEntity(
            id = 0,  // Will be auto-generated on insert
            name = dto.name,
            description = dto.description,
            unit = dto.unit,
            decimalPlaces = dto.decimalPlaces,
            aggregationType = dto.aggregationType,
            targetDirection = dto.targetDirection,
            targetValue = dto.targetValue,
            targetValueUpper = dto.targetValueUpper,
            iconResId = IconMapper.toIconResId(dto.icon),
            colorHex = dto.color,
            isActive = dto.isActive,
            uuid = dto.uuid,
            createdAt = now,  // Regenerated per EXPORT-11
            updatedAt = now
        )
    }

    /**
     * Convert HabitMetricLinkConfigDto back to HabitMetricLinkEntity.
     * Requires resolved habitId and metricId (from UUID lookup after insert).
     * Regenerates timestamps (per EXPORT-11).
     */
    fun dtoToLinkEntity(
        dto: HabitMetricLinkConfigDto,
        habitId: Long,
        metricId: Long
    ): HabitMetricLinkEntity {
        val now = System.currentTimeMillis()
        return HabitMetricLinkEntity(
            id = 0,  // Will be auto-generated on insert
            habitId = habitId,
            habitUuid = dto.habitUuid,
            metricId = metricId,
            metricUuid = dto.metricUuid,
            coefficient = dto.coefficient,
            showInHabitDetail = dto.showInHabitDetail,
            promptOnComplete = dto.promptOnComplete,
            isActive = dto.isActive,
            uuid = dto.uuid,
            createdAt = now,  // Regenerated per EXPORT-11
            updatedAt = now
        )
    }
}
