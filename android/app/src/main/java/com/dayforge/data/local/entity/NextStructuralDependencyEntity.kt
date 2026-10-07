package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable NEW local structural ancestry, never inferred for a migrated queue. */
@Entity(
    tableName = "next_structural_dependencies",
    indices = [Index(value = ["kind", "operationId"], unique = true), Index("predecessorId"),
        Index(value = ["logicalOrder"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = NextRequestOriginEntity::class, parentColumns = ["kind", "requestId"],
            childColumns = ["kind", "operationId"]),
        ForeignKey(entity = NextStructuralDependencyEntity::class, parentColumns = ["operationId"],
            childColumns = ["predecessorId"])
    ]
)
data class NextStructuralDependencyEntity(
    @PrimaryKey val operationId: String,
    val logicalOrder: Long,
    val originHash: String,
    val predecessorId: String?,
    val predecessorOriginHash: String?,
    val predecessorDependencyHash: String?,
    val capturedDeviceId: String?,
    val kind: String = "sync_operation"
)
