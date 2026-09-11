package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entity representing a user-defined metric for quantitative tracking.
 *
 * Metrics are independent from habits - they track numeric values over time
 * (e.g., weight, savings, steps) without daily check-in semantics.
 *
 * @param id Local database primary key
 * @param name Display name of the metric
 * @param description Optional description
 * @param unit Unit of measurement (e.g., "kg", "steps", "CNY")
 * @param decimalPlaces Number of decimal places to display (0 for integers)
 * @param targetDirection Optional goal direction: "increase", "decrease", or "range"
 * @param targetValue Optional target value (lower bound for range)
 * @param targetValueUpper Optional upper bound for range target
 * @param iconResId Resource ID for metric icon
 * @param colorHex Hex color string for UI display
 * @param isActive Whether this metric is active (shown in main list)
 * @param uuid Unique identifier for sync
 * @param createdAt Creation timestamp
 * @param updatedAt Last update timestamp
 */
@Entity(
    tableName = "metrics",
    indices = [Index("uuid")]
)
data class MetricEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val unit: String,
    val decimalPlaces: Int = 0,
    val aggregationType: String = "average",  // "average" | "sum" | "by_time"
    val targetDirection: String? = null,  // "increase" | "decrease" | "range"
    val targetValue: Double? = null,
    val targetValueUpper: Double? = null,
    val iconResId: Int,
    val colorHex: String,
    val isActive: Boolean = true,
    val uuid: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
