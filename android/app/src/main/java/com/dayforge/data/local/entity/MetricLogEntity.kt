package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entity representing a single log entry for a metric.
 *
 * MetricLogEntity stores historical values for metrics over time.
 * Each log is associated with a specific metric via foreign key.
 *
 * @param id Local database primary key
 * @param metricId Foreign key reference to the parent MetricEntity
 * @param date Exact observation timestamp in epoch milliseconds; callers normalize it when grouping by day
 * @param value The recorded numeric value
 * @param unit Unit of measurement (redundant storage for display convenience)
 * @param note Optional note for this log entry
 * @param uuid Unique identifier for sync
 * @param createdAt Creation timestamp
 * @param updatedAt Last update timestamp
 */
@Entity(
    tableName = "metric_logs",
    indices = [Index("metricId"), Index("date"), Index("uuid")],
    foreignKeys = [ForeignKey(
        entity = MetricEntity::class,
        parentColumns = ["id"],
        childColumns = ["metricId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class MetricLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val metricId: Long,
    val date: Long,          // Exact observation timestamp for display and day grouping
    val value: Double,
    val unit: String,        // Redundant storage for display convenience
    val note: String = "",
    val uuid: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
