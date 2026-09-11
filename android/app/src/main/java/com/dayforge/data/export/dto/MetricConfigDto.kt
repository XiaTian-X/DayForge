package com.dayforge.data.export.dto

import kotlinx.serialization.Serializable

/**
 * Metric configuration DTO for export/import.
 * Contains portable fields without local identifiers.
 *
 * Fields per EXPORT-10:
 * - Include: uuid, name, unit, icon (name), color, isActive,
 *   decimalPlaces, targetDirection, targetValue, targetValueUpper, aggregationType
 * - Exclude: id, createdAt, updatedAt
 *
 * @param uuid Cross-device unique identifier
 * @param name Metric display name
 * @param unit Unit of measurement (e.g., "kg", "steps", "CNY")
 * @param icon Icon name string
 * @param color Hex color string for UI
 * @param isActive Whether metric is active
 * @param decimalPlaces Number of decimal places to display (0 for integers)
 * @param targetDirection Optional goal direction ("increase", "decrease", "range")
 * @param targetValue Optional target value
 * @param targetValueUpper Optional upper bound for range targets
 * @param aggregationType How to aggregate values ("average", "sum", "by_time")
 */
@Serializable
data class MetricConfigDto(
    val uuid: String,
    val name: String,
    val description: String = "",
    val unit: String,
    val icon: String,
    val color: String,
    val isActive: Boolean,
    val decimalPlaces: Int = 0,
    val targetDirection: String? = null,
    val targetValue: Double? = null,
    val targetValueUpper: Double? = null,
    val aggregationType: String = "average"
)
