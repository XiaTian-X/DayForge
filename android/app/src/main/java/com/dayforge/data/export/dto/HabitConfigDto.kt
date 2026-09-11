package com.dayforge.data.export.dto

import kotlinx.serialization.Serializable

/**
 * Habit configuration DTO for export/import.
 * Contains portable fields without local identifiers or runtime state.
 *
 * Fields per EXPORT-09:
 * - Include: uuid, name, description, type, icon (name), color, isActive, schedule,
 *   targetCycles, targetValue, isCountdown, failMode, parentHabitUuid
 * - Exclude: id, createdAt, updatedAt, goalSuccess, activityRate
 *
 * @param uuid Cross-device unique identifier
 * @param name Habit display name
 * @param description Habit description (optional)
 * @param type Habit type enum name (CHECK_IN, COUNTING, TIMER, GOAL)
 * @param icon Icon name string (water, exercise, sleep, food, book, meditation, work, health)
 * @param color Hex color string for UI
 * @param isActive Whether habit is active
 * @param schedule JSON serialized HabitSchedule per HabitTypeConverter format
 * @param targetCycles Optional goal cycles (null = infinite)
 * @param targetValue Target value for counting/timer habits
 * @param isCountdown false = countup mode, true = countdown mode
 * @param failMode Failure mode enum name (STRICT, LOOSE)
 * @param parentHabitUuid UUID reference to parent habit for hierarchy
 * @param bestTime Best execution time as minutes since midnight (null = no preference)
 */
@Serializable
data class HabitConfigDto(
    val uuid: String,
    val name: String,
    val description: String = "",
    val type: String,
    val icon: String,
    val color: String,
    val isActive: Boolean,
    val schedule: String,
    val targetCycles: Int?,
    val targetValue: Int,
    val isCountdown: Boolean,
    val failMode: String,
    val parentHabitUuid: String?,
    val bestTime: Long? = null  // v3.7: Best execution time (minutes since midnight)
)
