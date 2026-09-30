package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dayforge.domain.model.isContractUuid

/** A bound v5 recovery stage, not an active sync cursor or server acknowledgement. */
@Entity(tableName = "next_recovery_state")
data class NextRecoveryStateEntity(
    val accountId: String,
    val serverInstanceId: String,
    val syncEpoch: String,
    val deviceId: String,
    val generation: Long,
    val phase: String,
    val minimumCursor: Long = 0,
    val candidateCursor: Long? = null,
    val snapshotHash: String? = null,
    @PrimaryKey val id: Int = 1
) {
    init {
        require(id == 1 && generation > 0 && minimumCursor >= 0)
        require(listOf(accountId, serverInstanceId, syncEpoch, deviceId).all(::isContractUuid))
        require(phase == AWAITING_SNAPSHOT || phase == ACCEPTED_DATA)
        if (phase == AWAITING_SNAPSHOT) require(candidateCursor == null && snapshotHash == null)
        else require(candidateCursor != null && candidateCursor >= minimumCursor &&
            snapshotHash != null && snapshotHash.matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val AWAITING_SNAPSHOT = "awaiting_snapshot"
        const val ACCEPTED_DATA = "accepted_data"
    }
}
