package com.dayforge.data.local.entity

import androidx.room.TypeConverter
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HabitTypeConverter {

    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromHabitType(type: HabitType): String = type.name

    @TypeConverter
    fun toHabitType(value: String): HabitType = HabitType.valueOf(value)

    @TypeConverter
    fun fromSchedule(schedule: HabitSchedule): String =
        json.encodeToString(schedule)

    @TypeConverter
    fun toSchedule(value: String): HabitSchedule =
        json.decodeFromString(value)

    @TypeConverter
    fun fromFailMode(mode: FailMode): String = mode.name

    @TypeConverter
    fun toFailMode(value: String): FailMode = FailMode.valueOf(value)
}
