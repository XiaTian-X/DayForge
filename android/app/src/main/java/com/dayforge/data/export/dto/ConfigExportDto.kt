package com.dayforge.data.export.dto

import kotlinx.serialization.Serializable

/**
 * Root configuration export DTO containing all habits, metrics, and their links.
 * Designed for portability across devices without local identifiers.
 *
 * @param schemaVersion Format version for future compatibility (v1 = 1)
 * @param habits List of habit configurations
 * @param metrics List of metric configurations
 * @param links List of habit-metric associations
 */
@Serializable
data class ConfigExportDto(
    val schemaVersion: Int = 1,
    val habits: List<HabitConfigDto> = emptyList(),
    val metrics: List<MetricConfigDto> = emptyList(),
    val links: List<HabitMetricLinkConfigDto> = emptyList()
)