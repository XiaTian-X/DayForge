package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.dayforge.domain.model.CountDayPolicy

/** Account database-local daily rule; retained when every effective completion is undone. */
@Entity(
    tableName = "count_days",
    primaryKeys = ["habitId", "localDate"],
    indices = [Index("habitId"), Index(value = ["originKind", "originRequestId"]), Index("planPredecessorId")],
    foreignKeys = [
        ForeignKey(entity = HabitEntity::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = NextRequestOriginEntity::class, parentColumns = ["kind", "requestId"], childColumns = ["originKind", "originRequestId"]),
        ForeignKey(entity = NextStructuralDependencyEntity::class, parentColumns = ["operationId"], childColumns = ["planPredecessorId"])
    ]
)
data class CountDayEntity(
    val habitId: Long,
    val habitUuid: String,
    val localDate: String,
    val targetValue: Int,
    val isCountdown: Boolean,
    val firstEventUuid: String,
    // Only a NEW local first count acquires birth order. Bootstrap cannot invent ancestry.
    val originRequestId: String? = null,
    val originHash: String? = null,
    val planPredecessorId: String? = null,
    val planPredecessorOriginHash: String? = null,
    val planPredecessorDependencyHash: String? = null,
    val planQueueWatermark: Long? = null,
    val capturedDeviceId: String? = null,
    val originKind: String = "sync_operation"
) {
    val policy: CountDayPolicy get() = CountDayPolicy(targetValue, isCountdown)
}
