package com.dayforge.domain.model

import kotlinx.serialization.json.*

/** D-017: the count rule captured when a business date begins, not a mutable plan. */
data class CountDayPolicy(val targetValue: Int, val isCountdown: Boolean) {
    init { require(targetValue > 0) }

    fun toJson(): JsonObject = buildJsonObject {
        put("target_value", targetValue); put("is_countdown", isCountdown)
    }

    companion object {
        fun fromJson(value: JsonElement): CountDayPolicy {
            val body = value as? JsonObject ?: error("COUNT_DAY_INVALID")
            require(body.keys == setOf("target_value", "is_countdown"))
            val target = requireNotNull(contractLongOrNull(body.getValue("target_value")))
            require(target in 1..Int.MAX_VALUE.toLong())
            val direction = body.getValue("is_countdown")
            require(direction is JsonPrimitive && !direction.isString)
            return CountDayPolicy(target.toInt(), requireNotNull(direction.booleanOrNull))
        }
    }
}
