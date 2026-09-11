package com.dayforge.data.local.dao

/**
 * Read-only projection used by habit cards and post-check-in metric prompts.
 *
 * Keeping this projection at the Room boundary lets callers observe link, metric, and latest-log
 * changes with one query instead of issuing one query per habit.
 */
data class LinkedMetricSnapshot(
    val habitId: Long,
    val metricId: Long,
    val metricName: String,
    val latestValue: Double?,
    val unit: String,
    val decimalPlaces: Int,
    val showInHabitDetail: Boolean,
    val promptOnComplete: Boolean
)
