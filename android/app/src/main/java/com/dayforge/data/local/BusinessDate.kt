package com.dayforge.data.local

import androidx.room.TypeConverter
import com.dayforge.data.local.entity.CompletionEntity
import java.time.LocalDate
import java.time.ZoneId

/** Captured civil date, never re-derived from the legacy midnight or occurrence instant. */
val CompletionEntity.businessDate: LocalDate
    get() = LocalDate.parse(recordedLocalDate)

/** View-only adapter for screens whose date navigation still accepts epoch millis. */
fun LocalDate.toDisplayMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atStartOfDay(zone).toInstant().toEpochMilli()

/** DAO day bounds are dates, not instants. ISO dates have chronological TEXT ordering. */
class BusinessDateConverters {
    @TypeConverter
    fun toDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun fromDate(value: LocalDate?): String? = value?.toString()
}
