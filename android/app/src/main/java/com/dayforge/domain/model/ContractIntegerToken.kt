package com.dayforge.domain.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private val contractIntegerToken = Regex("-?(0|[1-9][0-9]*)")

/** Numeric conversion is not proof of an original JSON integer (exponents may be rounded). */
internal fun isContractIntegerToken(value: String): Boolean = contractIntegerToken.matches(value)

/** The same exact check for opaque domain payloads and stored shadows, without coercion. */
internal fun contractLongOrNull(value: JsonElement?): Long? {
    val token = value as? JsonPrimitive ?: return null
    if (token.isString || !isContractIntegerToken(token.content)) return null
    return token.content.toLongOrNull()
}
