package com.dayforge.data.model

data class StreakStats(
    val currentStreak: Int,
    val bestStreak: Int,
    val lastCompletionDate: Long?
)
