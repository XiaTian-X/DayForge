package com.dayforge.data.local.entity

import androidx.room.Entity

/** Durable proof of the exact local acceptance transaction, not merely HTTP delivery. */
@Entity(tableName = "next_acceptances", primaryKeys = ["kind", "requestId"])
data class NextAcceptanceEntity(
    val kind: String,
    val requestId: String,
    val originHash: String,
    val transmissionHash: String,
    val resultHash: String,
    val resultJson: String
)
