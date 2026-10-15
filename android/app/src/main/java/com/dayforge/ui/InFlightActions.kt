package com.dayforge.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal val LocalInFlightHabitIds = androidx.compose.runtime.compositionLocalOf<Set<Long>> { emptySet() }

/** Page-local feedback for non-count operations. Counts use the shared CountActionGate. */
internal class InFlightActions {
    private val jobs = mutableMapOf<Long, Job>()
    private val mutable = MutableStateFlow<Set<Long>>(emptySet())
    val active = mutable.asStateFlow()

    fun launch(scope: CoroutineScope, key: Long, block: suspend () -> Unit) {
        val started = android.os.SystemClock.elapsedRealtime()
        val job = synchronized(jobs) {
            if (key in jobs) return
            val next = scope.launch(start = CoroutineStart.LAZY) { block() }
            jobs[key] = next
            mutable.value = jobs.keys.toSet()
            next.invokeOnCompletion {
                synchronized(jobs) {
                    if (jobs[key] === next) jobs.remove(key)
                    mutable.value = jobs.keys.toSet()
                }
                if (com.dayforge.BuildConfig.DEBUG) {
                    // No IDs, names, values, credentials or endpoints in diagnostics.
                    android.util.Log.d("LocalActionTiming", "completion_ms=${android.os.SystemClock.elapsedRealtime() - started}")
                }
            }
            next
        }
        job.start()
    }
}
