package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** Original complete HTTP envelope and its public authority; contains no credentials. */
@Entity(tableName = "next_transmissions", primaryKeys = ["kind", "requestId"],
    indices = [Index(value = ["kind", "queueId"], unique = true)])
data class NextTransmissionEntity(
    val kind: String,
    val requestId: String,
    val queueId: Long,
    val protocol: Int,
    val accountId: String,
    val serverInstanceId: String,
    val syncEpoch: String,
    val deviceId: String,
    val wireHash: String,
    val wireBytes: ByteArray
)
