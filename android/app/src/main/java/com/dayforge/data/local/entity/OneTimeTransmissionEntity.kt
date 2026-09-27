package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** First-send binding, not credentials or a success receipt. Survives outbox consumption. */
@Entity(tableName = "one_time_transmissions")
data class OneTimeTransmissionEntity(
    @PrimaryKey val operationId: String,
    val accountId: String,
    val serverInstanceId: String,
    val syncEpoch: String,
    val deviceId: String,
    val operationJson: String,
    val rejectionJson: String? = null
)
