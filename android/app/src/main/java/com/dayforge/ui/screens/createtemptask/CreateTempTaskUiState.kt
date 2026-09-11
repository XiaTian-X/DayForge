package com.dayforge.ui.screens.createtemptask

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity

/**
 * UiState for CreateTempTaskScreen.
 *
 * Contains only fields needed for simplified temp task creation:
 * - name (required)
 * - parentHabitUuid (optional)
 * - topLevelHabits for GOAL parent selection
 * - availableMetrics for optional linking
 *
 * Auto-configured values (hidden from UI per D-15):
 * - habitType = CHECK_IN
 * - targetCycles = 1
 * - failMode = LENIENT
 * - iconResId = 53 (task icon)
 * - schedule = Daily
 * - colorHex = "#2196F3"
 * - description = ""
 */
data class CreateTempTaskUiState(
    val name: String = "",
    val parentHabitUuid: String? = null,
    val topLevelHabits: List<HabitEntity> = emptyList(),
    val availableMetrics: List<MetricEntity> = emptyList(),
    val selectedMetricIds: Set<Long> = emptySet(),
    val isValid: Boolean = false,
    val isSaving: Boolean = false,
    val savedHabitId: Long? = null,
    val showDuplicateDialog: Boolean = false
)