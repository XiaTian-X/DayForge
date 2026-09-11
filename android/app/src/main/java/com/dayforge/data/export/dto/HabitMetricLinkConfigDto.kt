package com.dayforge.data.export.dto

import kotlinx.serialization.Serializable

/**
 * Habit-metric link configuration DTO for export/import.
 * Contains portable fields using UUID references.
 *
 * @param uuid Link unique identifier
 * @param habitUuid UUID reference to habit
 * @param metricUuid UUID reference to metric
 * @param coefficient Multiplier for value conversion
 * @param showInHabitDetail Whether to show metric in habit detail view
 * @param promptOnComplete Whether to prompt for metric input on habit completion
 * @param isActive Whether link is active
 */
@Serializable
data class HabitMetricLinkConfigDto(
    val uuid: String,
    val habitUuid: String,
    val metricUuid: String,
    val coefficient: Double = 1.0,
    val showInHabitDetail: Boolean = true,
    val promptOnComplete: Boolean = false,
    val isActive: Boolean = true
)