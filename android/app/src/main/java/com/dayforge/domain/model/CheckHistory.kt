package com.dayforge.domain.model

import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.CompletionEntity
import java.time.LocalDate

/** Proven current-round check-in projection; all retained facts were audited before selection. */
data class CheckHistory(val today: LocalDate, val completions: List<CompletionEntity>,
    val roundHead: ChallengeRoundHead?) {
    val qualifiedDates: Set<LocalDate> get() = completions.mapTo(linkedSetOf()) { it.businessDate }
    val todayCompletions: List<CompletionEntity> get() = completions.filter { it.businessDate == today }
    val todayQuantity: Long get() = todayCompletions.fold(0L) { sum, row -> Math.addExact(sum, row.value.toLong()) }
    val completedToday: Boolean get() = todayCompletions.isNotEmpty()
    val firstDate: LocalDate? get() = qualifiedDates.minOrNull()
}
