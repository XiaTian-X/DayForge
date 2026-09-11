package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A durable, user-resolvable copy of every version involved in a true sync conflict. */
@Entity(
    tableName = "sync_conflicts",
    indices = [
        Index(value = ["operationId"], unique = true),
        Index("status"),
        Index(value = ["recordType", "localEntityUuid"])
    ]
)
data class SyncConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationId: String,
    val recordType: String,
    val localEntityUuid: String,
    val wireEntityUuid: String,
    val entityType: String,
    val action: String,
    val referenceUuid: String?,
    val baseRevision: Long?,
    val serverRevision: Long,
    val basePayloadJson: String?,
    val localPayloadJson: String,
    val serverPayloadJson: String,
    val conflictingFieldsJson: String,
    val conflictKind: String?,
    val errorCode: String?,
    val message: String?,
    val status: String = "unresolved",
    val resolution: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null
)
