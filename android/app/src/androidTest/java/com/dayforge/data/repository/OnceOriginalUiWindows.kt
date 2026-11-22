package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.withTimeout

/** One functional wait per original window; the original 5 s cost still fails at the end.
 * The caller must first reach every original business assertion and native COMMIT calibration.
 * This changes when test cleanup begins, not what counts as a timely user-visible result.
 */
internal class OnceOriginalUiWindows(private val expected: List<String>) {
    private data class Sample(val phase: String, val elapsedNanos: Long, val satisfied: Boolean)
    private val samples = ArrayList<Sample>()

    suspend fun <T> await(phase: String, condition: suspend () -> T): T {
        check(samples.size < expected.size && expected[samples.size] == phase)
        // This is the old await entry, after load/undo or ActivityScenario.launch returns.
        val started = SystemClock.elapsedRealtimeNanos()
        var satisfied = false
        var ended: Long? = null
        try {
            return withTimeout(45_000L) {
                val result = condition()
                // Same predicate endpoint, before logging, use.close or unrelated work.
                ended = SystemClock.elapsedRealtimeNanos()
                result
            }.also { satisfied = true }
        } finally {
            val elapsed = (ended ?: SystemClock.elapsedRealtimeNanos()) - started
            samples += Sample(phase, elapsed, satisfied)
            Log.i(TAG, "phase=$phase elapsed_ns=$elapsed satisfied=$satisfied original_budget_ns=$ORIGINAL_BUDGET_NS")
        }
    }

    fun assertOriginalBudgetsAfterFunctionalTail() {
        check(samples.map { it.phase } == expected && samples.all { it.satisfied }) {
            "ONCE_UI_FUNCTIONAL_WINDOWS_INCOMPLETE"
        }
        Log.i(TAG, "functional_tail_and_native_commit_calibration=complete windows=${samples.size}")
        val slow = samples.filter { it.elapsedNanos > ORIGINAL_BUDGET_NS }
        if (slow.isNotEmpty()) throw AssertionError("Original 5000 ms UI budget exceeded after full functional tail: " +
            slow.joinToString { "${it.phase}=${it.elapsedNanos}ns" })
    }

    private companion object {
        const val TAG = "OnceOriginalUiWindows"
        const val ORIGINAL_BUDGET_NS = 5_000_000_000L
    }
}
