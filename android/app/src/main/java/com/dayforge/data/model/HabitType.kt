package com.dayforge.data.model

enum class HabitType {
    CHECK_IN,      // Boolean completion
    COUNTING,      // Numeric progress
    TIMER,         // Duration tracking
    GOAL           // Container for child habits, no check-in
}
