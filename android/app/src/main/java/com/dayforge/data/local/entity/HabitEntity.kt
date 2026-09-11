package com.dayforge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import java.util.UUID

@Entity(
    tableName = "habits",
    indices = [Index("uuid"), Index(value = ["name"], unique = true)]
)
@TypeConverters(HabitTypeConverter::class)
data class HabitEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val description: String = "",
    val habitType: HabitType,
    val iconResId: Int,
    val colorHex: String,
    val schedule: HabitSchedule,
    val targetValue: Int = 1,
    val isCountdown: Boolean = false,  // false = countup mode, true = countdown mode
    val isActive: Boolean = true,
    val uuid: String = UUID.randomUUID().toString(),  // Unique identifier for sync
    val parentHabitId: String? = null,  // UUID reference to parent habit, null = top-level habit
    val targetCycles: Int? = null,  // Nullable: null = infinite tracking (no target)
    val failMode: FailMode = FailMode.STRICT,  // Failure mode for target-based habits
    val goalSuccess: Boolean? = null,  // null=未完成, true=成功, false=失败 (GOAL type only)
    val activityRate: Int = 100,  // 活跃度 0-100，满分100
    val activityRateUpdatedAt: Long = System.currentTimeMillis(),  // 上次更新活跃度的时间
    val bestTime: Long? = null,  // Nullable Long for best execution time (minutes since midnight)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
