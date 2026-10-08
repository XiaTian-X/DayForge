package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dayforge.domain.model.isContractUuid

/** Accepted history has no FK to the disposable habit/completion projections. */
@Entity(tableName = "next_challenge_rounds", indices = [
    Index(value = ["activityUuid", "generation"], unique = true),
    Index(value = ["sourceDeviceUuid", "operationUuid"], unique = true)
])
data class NextChallengeRoundEntity(
    @PrimaryKey val roundUuid: String,
    val activityUuid: String,
    val generation: Long,
    val accountId: String,
    val serverInstanceId: String,
    val syncEpoch: String,
    val sourceDeviceUuid: String?,
    val operationUuid: String?,
    val recordJson: String,
    val recordHash: String
) {
    init {
        require(listOf(roundUuid, activityUuid, accountId, serverInstanceId, syncEpoch).all(::isContractUuid))
        require(generation in 0..Int.MAX_VALUE.toLong())
        require(sourceDeviceUuid == null || isContractUuid(sourceDeviceUuid))
        require(operationUuid == null || isContractUuid(operationUuid))
    }
}

@Entity(tableName = "next_challenge_births", primaryKeys = ["entityType", "entityUuid"],
    foreignKeys = [ForeignKey(entity = NextChallengeRoundEntity::class,
        parentColumns = ["roundUuid"], childColumns = ["roundUuid"])],
    indices = [Index("roundUuid")])
data class NextChallengeBirthEntity(val entityType: String, val entityUuid: String, val roundUuid: String) {
    init {
        require(entityType in setOf("activity_event", "timer_session"))
        require(isContractUuid(entityUuid) && isContractUuid(roundUuid))
    }
}

/** Profile marker and cumulative history proof belong to the same ACTIVE cursor commit. */
@Entity(tableName = "next_challenge_state", foreignKeys = [ForeignKey(
    entity = NextSyncStateEntity::class, parentColumns = ["id"], childColumns = ["id"], deferred = true)])
data class NextChallengeStateEntity(
    val accountId: String, val serverInstanceId: String, val syncEpoch: String, val deviceId: String,
    val generation: Long, val cursor: Long, val batchHash: String, val metadataHash: String,
    @PrimaryKey val id: Int = 1
) {
    init {
        require(id == 1 && generation > 0 && cursor >= 0)
        require(listOf(accountId, serverInstanceId, syncEpoch, deviceId).all(::isContractUuid))
        require(listOf(batchHash, metadataHash).all { it.length == 64 && it.all { c -> c in "0123456789abcdef" } })
    }
}
