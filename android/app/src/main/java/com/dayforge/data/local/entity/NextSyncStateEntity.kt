package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dayforge.domain.model.isContractUuid

/** An ACTIVE cursor. Neither a recovery candidate nor the legacy DataStore cursor. */
@Entity(tableName = "next_sync_state")
data class NextSyncStateEntity(
    val accountId: String,
    val serverInstanceId: String,
    val syncEpoch: String,
    val deviceId: String,
    val generation: Long,
    val cursor: Long,
    val bootstrapHash: String,
    val batchHash: String,
    @PrimaryKey val id: Int = 1
) {
    init {
        require(id == 1 && generation > 0 && cursor >= 0)
        require(listOf(accountId, serverInstanceId, syncEpoch, deviceId).all(::isContractUuid))
        require(listOf(bootstrapHash, batchHash).all { hash ->
            hash.length == 64 && hash.all { it in "0123456789abcdef" }
        })
    }
}
