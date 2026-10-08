package com.dayforge.domain.model

/** In-memory action proof. Never serialised, restored from UI state, or selected by a caller. */
abstract class OneTimeActionAuthority internal constructor()

/** Lifetime completion is independent of daily progress and the activity's archive flag. */
data class OneTimeStatus(
    val completed: Boolean,
    val completionId: Long?,
    val canChange: Boolean,
    val awaitingReplay: Boolean,
    val blocked: Boolean,
    val authority: OneTimeActionAuthority? = null
)
