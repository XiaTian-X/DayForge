package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "completions",
    indices = [Index("habitId"), Index("date"), Index("uuid")],
    foreignKeys = [ForeignKey(
        entity = HabitEntity::class,
        parentColumns = ["id"],
        childColumns = ["habitId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class CompletionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val habitId: Long,
    val date: Long,  // Midnight local timestamp for day grouping
    val value: Int = 1,
    // Actual timestamp for display purposes (real epoch millis)
    val actualCompletedAt: Long? = null,  // Real completion time for display/analysis
    val uuid: String = UUID.randomUUID().toString(),  // Unique identifier for sync
    val habitUuid: String? = null,  // Cross-reference for sync
    val createdAt: Long = System.currentTimeMillis()
)
