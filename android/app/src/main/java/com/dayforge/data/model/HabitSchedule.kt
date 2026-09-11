package com.dayforge.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class HabitSchedule {
    @Serializable
    @SerialName("daily")
    object Daily : HabitSchedule()

    @Serializable
    @SerialName("weekly")
    data class Weekly(val daysOfWeek: List<Int>) : HabitSchedule()

    @Serializable
    @SerialName("monthly")
    data class Monthly(val dayOfMonth: Int) : HabitSchedule()

    @Serializable
    @SerialName("custom")
    data class Custom(val frequencyDays: Int) : HabitSchedule()
}
