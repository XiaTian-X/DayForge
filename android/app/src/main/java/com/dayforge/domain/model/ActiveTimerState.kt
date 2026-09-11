package com.dayforge.domain.model

/** Current timer data rendered by habit screens and cards. */
data class ActiveTimerState(
    val habitId: Long,
    val elapsedSeconds: Int,
    val isPaused: Boolean,
    val targetMinutes: Int
)
