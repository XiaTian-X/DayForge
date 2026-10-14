package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Private atomic recovery receipt. Never a remote ACK, cancellation, cursor or credential. */
@Entity(tableName = "next_recovery_audits")
data class NextRecoveryAuditEntity(
    @PrimaryKey val recoveryId: String,
    val accountId: String,
    val serverInstanceId: String,
    val syncEpoch: String,
    val deviceId: String,
    val rootKind: String,
    val rootRequestId: String,
    val graphHash: String,
    val checkpointHash: String,
    val manifestHash: String,
    val selectedCount: Int
)

/** Original origins and journals survive retirement; mere membership is not valid evidence. */
@Entity(tableName = "next_abandoned_requests", primaryKeys = ["kind", "requestId"],
    foreignKeys = [
        ForeignKey(entity = NextRequestOriginEntity::class, parentColumns = ["kind", "requestId"],
            childColumns = ["kind", "requestId"]),
        ForeignKey(entity = NextRecoveryAuditEntity::class, parentColumns = ["recoveryId"],
            childColumns = ["recoveryId"])
    ], indices = [Index("recoveryId")])
data class NextAbandonedRequestEntity(
    val kind: String,
    val requestId: String,
    val recoveryId: String,
    val disposition: String,
    val sourceHash: String,
    val sourceSnapshotHash: String,
    val originHash: String,
    val transmissionHash: String?,
    val rejectionHash: String?
)

/** Independently bounded payloads; no closure-sized TEXT/BLOB in an Android CursorWindow. */
@Entity(tableName = "next_recovery_audit_payloads", primaryKeys = ["recoveryId", "payloadKey", "part"],
    foreignKeys = [ForeignKey(entity = NextRecoveryAuditEntity::class, parentColumns = ["recoveryId"],
        childColumns = ["recoveryId"])])
data class NextRecoveryAuditPayloadEntity(
    val recoveryId: String,
    val payloadKey: String,
    val part: Int,
    val bytes: ByteArray
)
