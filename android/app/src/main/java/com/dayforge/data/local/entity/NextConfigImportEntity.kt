package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Private local receipt, never an authentication credential or a remote whole-package ACK. */
@Entity(tableName = "next_config_imports")
data class NextConfigImportEntity(
    @PrimaryKey val importId: String,
    val accountId: String,
    val serverInstanceId: String,
    val syncEpoch: String,
    val deviceId: String,
    val archiveHash: String,
    val identitiesHash: String,
    val mode: String,
    val state: String,
    val receiptHash: String?
)

/** Bounded chunks avoid putting a maximum mapping and receipt in one Android CursorWindow row. */
@Entity(tableName = "next_config_import_payloads", primaryKeys = ["importId", "payloadKind", "part"],
    foreignKeys = [ForeignKey(entity = NextConfigImportEntity::class, parentColumns = ["importId"],
        childColumns = ["importId"])])
data class NextConfigImportPayloadEntity(val importId: String, val payloadKind: String, val part: Int, val bytes: ByteArray)
