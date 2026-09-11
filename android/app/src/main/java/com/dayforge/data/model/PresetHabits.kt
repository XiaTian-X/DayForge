package com.dayforge.data.model

import com.dayforge.data.local.entity.HabitEntity

object PresetHabits {

    // Health & Wellness
    val DRINK_WATER = HabitEntity(
        name = "喝水",
        description = "每天喝 8 杯水 (约 2 升)",
        habitType = HabitType.COUNTING,
        iconResId = 0, // R.drawable.ic_water_drop
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily,
        targetValue = 8
    )

    val EXERCISE = HabitEntity(
        name = "运动",
        description = "每天锻炼 30 分钟",
        habitType = HabitType.TIMER,
        iconResId = 0, // R.drawable.ic_directions_run
        colorHex = "#4CAF50",
        schedule = HabitSchedule.Daily,
        targetValue = 30
    )

    val SLEEP = HabitEntity(
        name = "规律睡眠",
        description = "每天按时睡觉和起床",
        habitType = HabitType.CHECK_IN,
        iconResId = 0, // R.drawable.ic_bedtime
        colorHex = "#9C27B0",
        schedule = HabitSchedule.Daily,
        targetValue = 1
    )

    val EAT_HEALTHY = HabitEntity(
        name = "健康饮食",
        description = "每天吃 5 份蔬菜水果",
        habitType = HabitType.COUNTING,
        iconResId = 0, // R.drawable.ic_lunch_dining
        colorHex = "#8BC34A",
        schedule = HabitSchedule.Daily,
        targetValue = 5
    )

    val MEDITATION = HabitEntity(
        name = "冥想",
        description = "每天冥想 10 分钟",
        habitType = HabitType.TIMER,
        iconResId = 0, // R.drawable.ic_self_improvement
        colorHex = "#00BCD4",
        schedule = HabitSchedule.Daily,
        targetValue = 10
    )

    val READ = HabitEntity(
        name = "阅读",
        description = "每天阅读 30 分钟",
        habitType = HabitType.TIMER,
        iconResId = 0, // R.drawable.ic_auto_stories
        colorHex = "#FF9800",
        schedule = HabitSchedule.Daily,
        targetValue = 30
    )

    fun getAllPresets(): List<HabitEntity> =
        listOf(DRINK_WATER, EXERCISE, SLEEP, EAT_HEALTHY, MEDITATION, READ)

    fun getPresetByName(name: String): HabitEntity? =
        getAllPresets().find { it.name == name }

    fun getHealthPresets(): List<HabitEntity> =
        listOf(DRINK_WATER, EXERCISE, SLEEP, EAT_HEALTHY)

    fun getMindfulnessPresets(): List<HabitEntity> =
        listOf(MEDITATION, READ)
}
