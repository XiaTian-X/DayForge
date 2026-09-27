package com.dayforge.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class HabitSchedule {
    /** A date hint, not a recurring deadline, automatic failure or reminder. */
    @Serializable
    @SerialName("once")
    data class Once(val dueDate: String? = null) : HabitSchedule() {
        init {
            require(dueDate == null || (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(dueDate) &&
                runCatching { java.time.LocalDate.parse(dueDate).year in 1..9999 }.getOrDefault(false)))
        }
    }

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
