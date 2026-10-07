package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Source retirement proof, NOT a server acceptance; preserves the complete original source. */
@Entity(
    tableName = "next_structural_supersessions",
    indices = [Index(value = ["replacementId"], unique = true),
        Index(value = ["kind", "replacementId"], unique = true), Index(value = ["kind", "predecessorAcceptedRequestId"]),
        Index(value = ["originalQueueId"], unique = true), Index(value = ["replacementQueueId"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = NextStructuralDependencyEntity::class, parentColumns = ["operationId"], childColumns = ["originalId"]),
        ForeignKey(entity = NextRequestOriginEntity::class, parentColumns = ["kind", "requestId"], childColumns = ["kind", "replacementId"]),
        ForeignKey(entity = NextAcceptanceEntity::class, parentColumns = ["kind", "requestId"], childColumns = ["kind", "predecessorAcceptedRequestId"])
    ]
)
data class NextStructuralSupersessionEntity(
    @PrimaryKey val originalId: String,
    val replacementId: String,
    val originalQueueId: Long,
    val replacementQueueId: Long,
    val originalOriginHash: String,
    val originalDependencyHash: String,
    val originalSourceHash: String,
    val sourceSnapshotJson: String,
    val sourceSnapshotHash: String,
    val predecessorAcceptedRequestId: String,
    val predecessorAcceptanceHash: String,
    val replacementOriginHash: String,
    val accountId: String,
    val serverInstanceId: String,
    val syncEpoch: String,
    val deviceId: String,
    val kind: String = "sync_operation"
)
