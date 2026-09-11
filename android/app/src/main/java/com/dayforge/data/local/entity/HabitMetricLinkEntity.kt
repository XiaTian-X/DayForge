package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "habit_metric_links",
    indices = [
        Index("habitId"),
        Index("metricId"),
        Index("uuid"),
        Index(value = ["habitId", "metricId"], unique = true)  // Prevents duplicate links
    ],
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MetricEntity::class,
            parentColumns = ["id"],
            childColumns = ["metricId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class HabitMetricLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val habitId: Long,
    val habitUuid: String,     // For sync: references HabitEntity.uuid
    val metricId: Long,
    val metricUuid: String,    // For sync: references MetricEntity.uuid
    val coefficient: Double = 1.0,  // Multiplier for value conversion
    val showInHabitDetail: Boolean = true,
    val promptOnComplete: Boolean = false,
    val isActive: Boolean = true,
    val uuid: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
