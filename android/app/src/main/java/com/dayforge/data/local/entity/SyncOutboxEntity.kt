package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable local mutation waiting for an explicit v2 server acknowledgement. */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["operationId"], unique = true),
        Index(value = ["recordType", "entityUuid"]),
        Index("attemptedAt"),
        Index("deadLetteredAt")
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationId: String,
    val recordType: String,
    val entityUuid: String,
    val wireEntityUuid: String,
    val action: String,
    val referenceUuid: String? = null,
    val payloadJson: String? = null,
    val baseRevision: Long? = null,
    val basePayloadJson: String? = null,
    val attemptedAt: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val errorCode: String? = null,
    val deadLetteredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
