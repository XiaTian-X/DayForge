package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Materialized only after the proposal's real frontiers are accepted. Never a guessed API intent. */
@Entity(tableName = "next_restart_materializations", foreignKeys = [ForeignKey(
    entity = NextRequestOriginEntity::class, parentColumns = ["kind", "requestId"],
    childColumns = ["kind", "operationId"], deferred = true)],
    indices = [Index(value = ["kind", "operationId"])])
data class NextRestartMaterializationEntity(
    @PrimaryKey val operationId: String,
    val kind: String,
    val originHash: String,
    val frontierHash: String,
    val operationJson: String,
    val operationHash: String
)

/** The exact authenticated contiguous Plan log following a real restart, not a fabricated ACK. */
@Entity(tableName = "next_restart_plan_proofs", foreignKeys = [ForeignKey(
    entity = NextRestartMaterializationEntity::class, parentColumns = ["operationId"],
    childColumns = ["operationId"], deferred = true)])
data class NextRestartPlanProofEntity(
    @PrimaryKey val operationId: String,
    val originHash: String,
    val transmissionHash: String,
    val revision: Long,
    val logSequence: Long,
    val deviceId: String,
    val planJson: String,
    val planHash: String
)
