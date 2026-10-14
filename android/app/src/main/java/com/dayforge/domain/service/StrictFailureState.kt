package com.dayforge.domain.service

/** Awaiting a normal timer terminal transition is neither failure nor completion. */
enum class StrictFailureState {
    NOT_FAILED,
    AWAITING_TIMER_SETTLEMENT,
    FAILED
}
