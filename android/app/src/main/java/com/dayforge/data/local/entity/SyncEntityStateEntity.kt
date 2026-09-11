package com.dayforge.data.local.entity

import androidx.room.Entity

/** Last server revision known for one account-local synchronized entity. */
@Entity(
    tableName = "sync_entity_state",
    primaryKeys = ["entityType", "entityUuid"]
)
data class SyncEntityStateEntity(
    val entityType: String,
    val entityUuid: String,
    val revision: Long,
    val deleted: Boolean = false,
    val payloadJson: String? = null,
    val payloadHash: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
