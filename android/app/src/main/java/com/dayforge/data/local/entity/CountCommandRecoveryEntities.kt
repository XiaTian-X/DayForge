package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** New v20 tables only. Existing count_command_results remains the immutable Outcome store.
 * No row grants authority. Insertion/updates belong to the private command transaction owner.
 * Constructors alone do not validate native SQLite types or authenticate directory membership.
 */
@Entity(tableName = "count_command_intents")
data class CountCommandIntentEntity(
    @PrimaryKey val commandId: String,
    val namespaceHash: String,
    val inputHash: String,
    val format: Int,
    val action: String,
    val intentJson: String
)

@Entity(tableName = "count_command_status")
data class CountCommandStatusEntity(
    @PrimaryKey val commandId: String,
    val inputHash: String,
    val revision: Long,
    val execution: String,
    val delivery: String,
    val parentCommandId: String?,
    val childCommandId: String?,
    val previousPendingId: String?,
    val nextPendingId: String?,
    val stateJson: String,
    val businessGenerationAtAdmission: Long
)

/** Both catalog and pending-list integrity are authenticated by one exact tuple. Pending
 * head/tail/count plus catalog-authenticated status links avoid a history-sized tombstone scan.
 * They are not authenticated until the root MAC and exact native member evidence are checked.
 */
@Entity(tableName = "count_command_recovery_roots")
data class CountCommandRecoveryRootEntity(
    @PrimaryKey val id: Int,
    val mode: String,
    val storageId: String,
    val datasetId: String,
    val initializationSource: String,
    val namespaceHash: String?,
    val namespaceJson: String?,
    val accountBindingHash: String?,
    val registrationPreviousHash: String?,
    val registrationInstallationId: String?,
    val format: Int,
    val validator: String,
    val schemaHash: String,
    val catalogHash: String?,
    val catalogHeight: Int?,
    val catalogCount: Long?,
    val pendingHeadId: String?,
    val pendingTailId: String?,
    val pendingCount: Long,
    val legacyCount: Long?,
    val legacyHash: String?,
    val revision: Long,
    val mac: ByteArray
)

/** Dedicated durable BLOB storage. COUNT acceleration discard/GC must never delete this table. */
@Entity(tableName = "count_command_nodes")
data class CountCommandNodeEntity(@PrimaryKey val digest: String, val body: ByteArray)
