package com.dayforge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Device-local change marker. A row is not a certificate or a permission. */
@Entity(tableName = "count_trust_control")
data class CountTrustControlEntity(
    @PrimaryKey val id: Int,
    val storageId: String,
    val generation: Long,
    val dirty: Int,
    @ColumnInfo(defaultValue = "NULL") val commandRootHash: String? = null
)

/** Authenticated root bytes; interpretation and issuance belong to the closed count kernel. */
@Entity(tableName = "count_trust_root")
data class CountTrustRootEntity(
    @PrimaryKey val id: Int,
    val binding: String,
    val format: Int,
    val validator: String,
    val schemaHash: String,
    val rootHash: String,
    val rootHeight: Int,
    val rootCount: Long,
    val revision: Long,
    val generation: Long,
    val mac: ByteArray
)

@Entity(tableName = "count_trust_nodes")
data class CountTrustNodeEntity(@PrimaryKey val digest: String, val body: ByteArray)

/** Locators only: a missing route never proves that the authenticated source is absent. */
@Entity(tableName = "count_trust_routes", primaryKeys = ["sourceTable", "sourceKey"], indices = [
    Index(value = ["ownerUuid", "businessDate"]), Index(value = ["entityUuid"]), Index(value = ["requestId"])
])
data class CountTrustRouteEntity(
    val sourceTable: String,
    val sourceKey: String,
    val ownerUuid: String?,
    val entityUuid: String?,
    val requestId: String?,
    val businessDate: String?,
    val role: String,
    val payloadHash: String
)

/** Stable local command outcome, never imported as another replica's write permission. */
@Entity(tableName = "count_command_results")
data class CountCommandResultEntity(
    @PrimaryKey val commandId: String,
    val inputHash: String,
    val factUuid: String,
    val requestId: String?,
    val resultJson: String,
    val state: String
)
