package com.dayforge.domain.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Serializes sync, account switching, and logout so account-owned data cannot cross sessions. */
@Singleton
class AccountSessionCoordinator @Inject constructor() {
    private val mutex = Mutex()
    private val scheduling = Any()
    private val interactions = mutableSetOf<CompletableDeferred<Unit>>()

    private class Interaction(val coordinator: AccountSessionCoordinator, val finished: CompletableDeferred<Unit>) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Interaction>
    }

    /** Scheduling only, NEVER authority or a lock spanning repository calls. The caller must
     * finish preparing its follow-up before returning, not wait here for a user/network response.
     * Structured cancellation/failure always releases the hint; no delayed cleanup job.
     */
    internal suspend fun <T> withInteractiveReadPriority(block: suspend () -> T): T {
        val inherited = currentCoroutineContext()[Interaction]
        if (inherited?.coordinator === this && !inherited.finished.isCompleted) return block()
        val finished = CompletableDeferred<Unit>()
        synchronized(scheduling) { interactions.add(finished) }
        return try { withContext(Interaction(this, finished)) { block() } }
        finally {
            synchronized(scheduling) { interactions.remove(finished) }
            finished.complete(Unit)
        }
    }

    // Fixed categories only: diagnostics must never include IDs, names, tokens or payloads.
    internal enum class Work { GENERAL, CREATE_PREPARE, HABIT_EDIT_READ, METRIC_EDIT_READ, FACT_HISTORY_READ, NEXT_WRITE,
        DASHBOARD_READ, DASHBOARD_PUBLISH, TIMER_DISPLAY_READ, ONE_TIME_DISPLAY_READ, PROMPT_PREFERENCE_READ,
        COMPLETION_PROMPT_READ }

    suspend fun <T> exclusive(block: suspend () -> T): T = exclusive(Work.GENERAL, block)

    internal suspend fun <T> exclusive(work: Work, block: suspend () -> T): T {
        val requested = android.os.SystemClock.elapsedRealtime()
        var yieldedToInteraction = false
        while (true) {
            mutex.lock()
            // Recheck AFTER lock acquisition: a background refresh may already have queued
            // while the interactive write was in progress. Never wait while owning the lock.
            val own = currentCoroutineContext()[Interaction]
            val waitingFor = if (work == Work.DASHBOARD_READ && !yieldedToInteraction &&
                !(own?.coordinator === this && !own.finished.isCompleted))
                synchronized(scheduling) { interactions.toList() } else emptyList()
            if (waitingFor.isNotEmpty()) {
                mutex.unlock()
                yieldedToInteraction = true
                // Only this captured cohort, once. Later clicks cannot indefinitely move the
                // goalposts. Logout/sync, publication and all interactive reads stay FIFO.
                waitingFor.forEach { it.await() }
                continue
            }
            val entered = android.os.SystemClock.elapsedRealtime()
            try {
                currentCoroutineContext().ensureActive()
                return block()
            } finally {
                mutex.unlock()
                val held = android.os.SystemClock.elapsedRealtime() - entered
                if (com.dayforge.BuildConfig.DEBUG && (entered - requested >= 100 || held >= 100)) {
                    android.util.Log.d("AccountWorkTiming", "work=$work wait_ms=${entered - requested} held_ms=$held")
                }
            }
        }
    }
}
