package com.dayforge.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Unsaved form data. It must not enter Room/outbox until the enclosing goal is saved. */
@Serializable
data class HabitDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val habitType: HabitType,
    val iconResId: Int = 0,
    val colorHex: String = "#2196F3",
    val schedule: HabitSchedule = HabitSchedule.Daily,
    val targetValue: Int = 1,
    val isCountdown: Boolean = false,
    val targetCycles: Int? = null,
    val failMode: FailMode = FailMode.STRICT,
    val bestTime: Long? = null,
    val selectedMetricIds: Set<Long> = emptySet()
)
