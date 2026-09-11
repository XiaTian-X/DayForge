package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Singleton switch used to prevent downloaded/acknowledged rows from re-entering the outbox. */
@Entity(tableName = "sync_control")
data class SyncControlEntity(
    @PrimaryKey val id: Int = 1,
    val suppressOutbox: Boolean = false
)
