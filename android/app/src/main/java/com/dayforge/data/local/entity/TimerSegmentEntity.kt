package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ForeignKey

/** One locally observed running interval; paused time is intentionally absent. */
@Entity(
    tableName = "timer_segments",
    indices = [Index(value = ["sessionUuid", "sequence"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = TimeLogEntity::class,
        parentColumns = ["uuid"],
        childColumns = ["sessionUuid"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class TimerSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionUuid: String,
    val sequence: Int,
    val startedAt: Long,
    val endedAt: Long? = null
)
