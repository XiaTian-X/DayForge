package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** Created with NEW business/outbox writes, never inferred from old attempts or appearance. */
@Entity(tableName = "next_request_origins", primaryKeys = ["kind", "requestId"],
    indices = [Index(value = ["kind", "queueId"], unique = true)])
data class NextRequestOriginEntity(
    val kind: String,
    val requestId: String,
    val queueId: Long,
    val protocol: Int,
    val accountId: String,
    val serverInstanceId: String?,
    val syncEpoch: String?,
    val sourceHash: String,
    val intentJson: String
)
