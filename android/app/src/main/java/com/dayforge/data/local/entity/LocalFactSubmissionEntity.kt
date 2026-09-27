package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local durability receipt, not a server acknowledgement. Survives outbox consumption. */
@Entity(tableName = "local_fact_submissions", indices = [Index(value = ["entityType", "entityUuid"], unique = true)])
data class LocalFactSubmissionEntity(
    @PrimaryKey val operationId: String,
    val entityType: String,
    val entityUuid: String,
    val referenceUuid: String,
    val payloadJson: String
)
