package com.dayforge.data.repository

import java.time.LocalDate
import kotlinx.serialization.json.*

/** Values returned at the two original Service selection points. These are not a current
 * display claim or credentials. Int summation deliberately retains the old legacy behavior;
 * latest selection is a separate original read and may observe a different day/state.
 */
internal data class LegacyCountQuantitySelection(val habitId: Long, val date: LocalDate, val quantity: Int)
internal data class LegacyCountLatestSelection(val habitId: Long, val date: LocalDate, val completionId: Long?)

internal class LegacyCountNoTarget private constructor(
    val quantity: LegacyCountQuantitySelection, val latest: LegacyCountLatestSelection?
) {
    fun body(): JsonObject = buildJsonObject {
        put("habit_id", quantity.habitId); put("quantity_date", quantity.date.toString()); put("quantity", quantity.quantity)
        put("latest_queried", latest != null); put("latest_date", latest?.date?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("latest_id", latest?.completionId?.let(::JsonPrimitive) ?: JsonNull)
    }
    companion object {
        fun from(quantity: LegacyCountQuantitySelection, latest: LegacyCountLatestSelection?): LegacyCountNoTarget {
            require(quantity.habitId > 0)
            if (quantity.quantity <= 0) require(latest == null)
            else require(latest != null && latest.habitId == quantity.habitId && latest.completionId == null)
            return LegacyCountNoTarget(quantity.copy(), latest?.copy())
        }
        fun validate(body: JsonObject) {
            CountCommandRecords.fields(body, "habit_id", "quantity_date", "quantity", "latest_queried", "latest_date", "latest_id")
            val count = CountCommandRecords.integer(body, "quantity").also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
            val id = CountCommandRecords.integer(body, "habit_id")
            val date = LocalDate.parse(CountCommandRecords.text(body, "quantity_date"))
            require(date.toString() == CountCommandRecords.text(body, "quantity_date"))
            val queried = body.getValue("latest_queried").let { require(it == JsonPrimitive(true) || it == JsonPrimitive(false)); it == JsonPrimitive(true) }
            val latest = if (queried) {
                val selectedDate = LocalDate.parse(CountCommandRecords.text(body, "latest_date"))
                require(selectedDate.toString() == CountCommandRecords.text(body, "latest_date"))
                val selectedId = body.getValue("latest_id").takeUnless { it == JsonNull }?.let { CountCommandRecords.integer(body, "latest_id") }
                LegacyCountLatestSelection(id, selectedDate, selectedId)
            } else {
                require(body.getValue("latest_date") == JsonNull && body.getValue("latest_id") == JsonNull); null
            }
            from(LegacyCountQuantitySelection(id, date, count), latest)
        }
    }
}
