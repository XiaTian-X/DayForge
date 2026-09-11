package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "timelogs",
    indices = [Index("habitId"), Index("date"), Index(value = ["uuid"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = HabitEntity::class,
        parentColumns = ["id"],
        childColumns = ["habitId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class TimeLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val habitId: Long,
    val startTime: Long,              // Actual start time (epoch millis)
    val endTime: Long?,               // Actual end time (epoch millis), null if timer is still running
    val durationSeconds: Int,         // Accumulated duration at save time
    val isPaused: Boolean = false,
    val pausedAt: Long? = null,
    val accumulatedPauseMillis: Long = 0,
    val timerNextCommandSequence: Int = 1,
    val timerControlGeneration: Int = 0,
    val timerLastCommandAt: Long? = null,
    val timerTimezone: String? = null,
    val timerActiveElapsedMillis: Long = 0,
    val timerElapsedRealtimeAnchor: Long? = null,
    val timerBootCount: Int? = null,
    val date: Long,                   // Midnight local timestamp for day grouping
    val uuid: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
