package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dayforge.domain.model.isContractUuid

/** Durable old-replica SQL fence. This is NOT an archive receipt or permission to commit a new replica. */
@Entity(tableName = "next_replica_fence")
data class NextReplicaFenceEntity(
    val recoveryId: String,
    val accountId: String,
    val serverInstanceId: String,
    val oldEpoch: String,
    val newEpoch: String,
    val authenticationGeneration: String,
    val deviceId: String,
    val capabilityRevision: Long,
    val sourceHash: String,
    val mode: String = FROZEN,
    @PrimaryKey val id: Int = 1
) {
    init {
        require(id == 1 && capabilityRevision > 0 && oldEpoch != newEpoch)
        require(listOf(recoveryId, accountId, serverInstanceId, oldEpoch, newEpoch,
            authenticationGeneration, deviceId).all(::isContractUuid))
        require(sourceHash.matches(Regex("[0-9a-f]{64}")))
        require(mode == FROZEN || mode == CLOSED)
    }

    companion object {
        const val FROZEN = "frozen"
        const val CLOSED = "closed"
    }
}
