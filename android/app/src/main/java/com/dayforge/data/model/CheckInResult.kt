package com.dayforge.data.model

/**
 * Result of a check-in operation.
 * Provides completion status, progress count, and goal detection.
 */
sealed class CheckInResult {
    /**
     * Successful check-in operation.
     * @param completed Toggle result or today completion status
     * @param progress Distinct days with completions
     * @param goalReached true if targetCycles set and progress >= target
     */
    data class Success(
        val completed: Boolean,
        val progress: Int,
        val goalReached: Boolean
    ) : CheckInResult()

    /**
     * Check-in operation failed.
     * @param message Error message describing the failure
     */
    data class Error(val message: String) : CheckInResult()
}