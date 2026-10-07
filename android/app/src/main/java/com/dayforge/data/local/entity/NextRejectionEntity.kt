package com.dayforge.data.local.entity

import androidx.room.Entity

/** Authentic non-success result; never usable as an acceptance or ancestry receipt. */
@Entity(tableName = "next_rejections", primaryKeys = ["kind", "requestId"])
data class NextRejectionEntity(
    val kind: String,
    val requestId: String,
    val originHash: String,
    val transmissionHash: String,
    val resultHash: String,
    val resultJson: String
)
