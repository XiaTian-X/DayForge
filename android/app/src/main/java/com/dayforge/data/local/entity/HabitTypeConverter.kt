package com.dayforge.data.local.entity

import androidx.room.TypeConverter
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HabitTypeConverter {

    private val json = Json { ignoreUnknownKeys = true }

    // New metadata uses the strict frozen contract; never normalize corruption to an icon fallback.
    @TypeConverter
    fun fromAppearance(value: com.dayforge.domain.model.ObjectAppearance?): String? =
        value?.let { Json.encodeToString(it) }

    @TypeConverter
    fun toAppearance(value: String?): com.dayforge.domain.model.ObjectAppearance? =
        value?.let { Json.decodeFromString<com.dayforge.domain.model.ObjectAppearance>(it) }

    @TypeConverter
    fun fromHabitType(type: HabitType): String = type.name

    @TypeConverter
    fun toHabitType(value: String): HabitType = HabitType.valueOf(value)

    @TypeConverter
    fun fromSchedule(schedule: HabitSchedule): String =
        json.encodeToString(schedule)

    @TypeConverter
    fun toSchedule(value: String): HabitSchedule {
        val schedule = json.decodeFromString<HabitSchedule>(value)
        // Keep legacy decoding intact, but do not silently discard a mistyped new due-date field.
        return if (schedule is HabitSchedule.Once) Json.decodeFromString<HabitSchedule>(value) else schedule
    }

    @TypeConverter
    fun fromFailMode(mode: FailMode): String = mode.name

    @TypeConverter
    fun toFailMode(value: String): FailMode = FailMode.valueOf(value)
}
