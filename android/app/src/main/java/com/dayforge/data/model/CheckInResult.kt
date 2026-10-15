package com.dayforge.data.model

/**
 * Result of a check-in operation.
 * Provides completion status, progress count, and goal detection.
 */
sealed class CheckInResult {
    /** Interaction was not admitted. No write, failure toast or success follow-up. */
    data object Ignored : CheckInResult()
    /**
     * Successful check-in operation.
     * @param completed Toggle result or today completion status
     * @param progress Distinct days with completions
     * @param goalReached true if targetCycles set and progress >= target
     */
    data class Success(
        val completed: Boolean,
        val progress: Int,
        val goalReached: Boolean,
        /** False when a queued countdown increment found that today's target was already met. */
        val recorded: Boolean = true,
        /** Same verified post-commit object, facts and scope; never a numeric-ID-only follow-up. */
        val followUp: com.dayforge.data.repository.WidgetFactSnapshot? = null
    ) : CheckInResult()

    /**
     * Check-in operation failed.
     * @param message Error message describing the failure
     */
    data class Error(val message: String) : CheckInResult()
}
