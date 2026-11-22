package com.dayforge.data.repository

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Eligibility only for a NEW explicit current-session recovery action. This does not
 * authenticate, transform or register an Intent. Embedded old Widget/metric authorities
 * and all registration namespace transitions remain outside this route. */
internal object CountCommandReauthentication {
    fun supportsCountInvalidation(intent: CountCommandRecords.Intent): Boolean =
        intent.action in setOf(CountCommandRecords.Action.COUNT_ADD, CountCommandRecords.Action.COUNT_UNDO) && supports(intent)

    fun supports(intent: CountCommandRecords.Intent): Boolean {
        if (intent.action == CountCommandRecords.Action.METRIC_APPEND) return true
        if (intent.action !in setOf(CountCommandRecords.Action.COUNT_ADD, CountCommandRecords.Action.COUNT_UNDO)) return false
        val input = intent.body().getValue("input").jsonObject
        val profile = input["local_profile"]
        if (profile != null && profile != JsonPrimitive(CountCommandLegacyInput.PROFILE)) return false
        return (input["widget_claim"] == null || input["widget_claim"] == JsonNull) && input["source_claim"] == null
    }
}
