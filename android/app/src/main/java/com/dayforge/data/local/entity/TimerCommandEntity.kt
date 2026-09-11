package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Immutable timer transition retained until the server acknowledges it. */
@Entity(
    tableName = "timer_command_outbox",
    indices = [
        Index(value = ["commandId"], unique = true),
        Index(value = ["sessionUuid", "sequence"], unique = true),
        Index("deadLetteredAt")
    ]
)
data class TimerCommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandId: String = UUID.randomUUID().toString(),
    val sessionUuid: String,
    val sequence: Int,
    val commandType: String,
    val occurredAt: Long,
    val expectedControlGeneration: Int,
    val expectedRevision: Int? = null,
    val activityUuid: String? = null,
    val timezone: String? = null,
    val activeElapsedMillis: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val errorCode: String? = null,
    val deadLetteredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
