package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Device-local draft/receipt. No cascading FK may erase input when a synced link disappears. */
@Entity(tableName = "completion_metric_prompts", indices = [Index("activityUuid"), Index("state")])
data class CompletionMetricPromptEntity(
    @PrimaryKey val eventUuid: String,
    val activityUuid: String,
    val state: String = "pending",
    val revision: Long = 0,
    val entriesJson: String,
    val recordedAtMillis: Long? = null,
    val timezone: String? = null
)
