package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ForeignKey

/** Active duration assigned to a local calendar day in the captured timezone. */
@Entity(
    tableName = "timelog_day_allocations",
    primaryKeys = ["sessionUuid", "localDate", "timezone"],
    indices = [Index("habitId"), Index("localDateEpoch")],
    foreignKeys = [ForeignKey(
        entity = TimeLogEntity::class,
        parentColumns = ["uuid"],
        childColumns = ["sessionUuid"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class TimeLogDayAllocationEntity(
    val sessionUuid: String,
    val habitId: Long,
    val localDate: String,
    val localDateEpoch: Long,
    val timezone: String,
    val durationMillis: Long
)
