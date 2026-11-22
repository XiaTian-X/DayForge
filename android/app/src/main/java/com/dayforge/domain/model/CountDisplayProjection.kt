package com.dayforge.domain.model

import java.time.LocalDate

/** Complete finite input for a card or exact-source follow-up, never detail history or a
 * credential. Historical totals/daily maps remain on CountSummary's independent API. */
@ConsistentCopyVisibility
data class CountDisplayProjection internal constructor(
    val today: LocalDate,
    val zoneId: String,
    val todayQuantity: Long,
    val todayPolicy: CountDayPolicy?,
    val todayLatestCompletionId: Long?,
    val qualifiedDayCount: Int,
    val hasFailed: Boolean,
    val roundHead: ChallengeRoundHead?,
    val actionIdentity: CountActionIdentity?,
    val card: Card? = null
) {
    val completedToday: Boolean get() = todayPolicy?.let { todayQuantity >= it.targetValue } == true
    data class Card(val currentStreak: Int, val bestStreak: Int, val activityRate: Int)
}
