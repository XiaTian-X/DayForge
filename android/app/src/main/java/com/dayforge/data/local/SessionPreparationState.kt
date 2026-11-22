package com.dayforge.data.local

import com.dayforge.data.repository.ReplicaPresentationCheck

/** In-process preparation input, never credentials, source alias or a business-write grant. */
internal class SessionPreparationAttempt internal constructor(
    internal val owner: DatabaseRuntimeHost,
    internal val graph: DatabaseEpochGraph,
    internal val stamp: PersonalDataDisplayMemory.Stamp,
    internal val snapshot: LegacySessionPreparationSnapshot?
)

internal enum class SessionPreparationDisposition { CREATED_GENERATION, CURRENT_GENERATION, NOT_APPLICABLE }
internal sealed interface SessionPreparationState {
    val attempt: SessionPreparationAttempt?
    data class Checking(override val attempt: SessionPreparationAttempt? = null) : SessionPreparationState
    data class Satisfied(override val attempt: SessionPreparationAttempt,
        val disposition: SessionPreparationDisposition) : SessionPreparationState
    data class DeferredFence(override val attempt: SessionPreparationAttempt,
        val problem: ReplicaPresentationCheck, val cause: Throwable? = null) : SessionPreparationState
    data class RetryableFailure(override val attempt: SessionPreparationAttempt,
        val cause: Throwable) : SessionPreparationState
    data class AuthenticationUnavailable(override val attempt: SessionPreparationAttempt) : SessionPreparationState
}
internal sealed interface SessionPreparationRetry {
    data class Completed(val state: SessionPreparationState) : SessionPreparationRetry
    data object Stale : SessionPreparationRetry
}

/** Factory result is distinct from physical validity. Original Room/END failures never get
 * wrapped here. A completed metadata check still does not grant original business authority. */
internal data class LegacySessionPreparationCheck(
    val snapshot: LegacySessionPreparationSnapshot? = null,
    val result: LegacySessionGenerationResult? = null,
    val fence: ReplicaPresentationCheck? = null,
    val cause: Throwable? = null
)
