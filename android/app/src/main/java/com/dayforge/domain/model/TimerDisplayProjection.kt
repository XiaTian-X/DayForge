package com.dayforge.domain.model

import com.dayforge.domain.service.StrictFailureState
import java.time.LocalDate

/** Finite completed-duration display, never a full history or timer-control authority.
 * firstDate includes unqualified completed-allocation dates. The active control's birth
 * remains in its independently captured TimerActionAuthority, not in this display head.
 */
@ConsistentCopyVisibility
data class TimerDisplayProjection internal constructor(
    val today: LocalDate,
    val zoneId: String,
    val roundHead: ChallengeRoundHead?,
    val targetSeconds: Long,
    val todayMillis: Long,
    val qualifiedDayCount: Int,
    val firstDate: LocalDate?,
    val currentStreak: Int,
    val bestStreak: Int,
    val activityRate: Int,
    val failure: StrictFailureState
) {
    val todaySeconds: Long get() = todayMillis / 1_000L
    val completedToday: Boolean get() = todaySeconds >= targetSeconds
    val hasFailed: Boolean get() = failure == StrictFailureState.FAILED
}
