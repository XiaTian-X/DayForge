package com.dayforge.domain.model

import java.time.LocalDate

/** Complete ordinary CHECK display/action input, not detail history or a credential.
 * Presence and maximum local ID preserve same-day multiple facts without borrowing COUNT targets.
 */
@ConsistentCopyVisibility
data class CheckDisplayProjection internal constructor(
    val today: LocalDate,
    val zoneId: String,
    val todayQuantity: Long,
    val todayLatestCompletionId: Long?,
    val qualifiedDayCount: Int,
    val hasFailed: Boolean,
    val roundHead: ChallengeRoundHead?,
    val actionIdentity: CheckActionIdentity?,
    val card: Card? = null
) {
    val completedToday: Boolean get() = todayLatestCompletionId != null
    data class Card(val currentStreak: Int, val bestStreak: Int, val activityRate: Int)
}
