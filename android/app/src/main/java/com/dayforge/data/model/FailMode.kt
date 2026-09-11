package com.dayforge.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Failure mode for habits with targetCycles.
 *
 * STRICT: Fail immediately when a check-in day is missed (断签即失败)
 * LOOSE: Only fail when cycle ends without reaching target (周期结束判定)
 */
@Serializable
enum class FailMode {
    @SerialName("strict")
    STRICT,

    @SerialName("loose")
    LOOSE
}