package com.dayforge.domain.model

import com.dayforge.data.local.entity.CompletionEntity
import java.time.LocalDate

/** Effective quantities and immutable day rules; unknown history is never requalified. */
data class CountHistory(
    val today: LocalDate,
    val policies: Map<LocalDate, CountDayPolicy>,
    val quantities: Map<LocalDate, Long>,
    val todayPolicy: CountDayPolicy?,
    val unknownDates: Set<LocalDate>,
    val completions: List<CompletionEntity>
) {
    val todayQuantity: Long get() = quantities[today] ?: 0L
    val completedToday: Boolean get() = todayPolicy?.let { todayQuantity >= it.targetValue } == true
    val qualifiedDates: Set<LocalDate> get() = quantities.keys.filterTo(linkedSetOf()) {
        policies[it]?.let { policy -> quantities.getValue(it) >= policy.targetValue } == true
    }
    val firstDate: LocalDate? get() = quantities.keys.minOrNull()
}
