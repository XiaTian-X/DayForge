package com.dayforge.data.local

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.runners.model.TestTimedOutException

/** Closed test classification: genuine retained/history input preparation, then one entire
 * business journey. It changes no runner timeout and never splits ACK replay from the next input. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class RetainedPreparedJourney

/** One evaluated resource/Host owner, with independently charged preparation and method.
 * Like JUnit Timeout, a watchdog waits for the evaluated Statement on an owned thread.
 * Unlike a larger method timeout, its deadline resets ONLY at the explicit phase boundary.
 * This object is per JUnit instance and supplies no cross-method or cross-process adoption. */
internal class RetainedJourneyStages private constructor(
    private val preserve: (Throwable) -> Unit,
    private val monotonicNanos: () -> Long,
    private val clockEvidence: String
) : TestRule {
    constructor(preserve: (Throwable) -> Unit) : this(preserve, SystemClock::elapsedRealtimeNanos, "elapsed_realtime")
    // Existing cleanup caps: method/activity/ViewModels/theme 4*5s, donor Host 30s,
    // peer acceptor/workers 2*5s, target Host 30s, preferences actor 5s = 95s.
    // Token/icon/file/Keystore cleanup shares that cap, with no free unmetered tail.
    // The one supervisor-owned Statement thread has the original 5s join cap: total 400s.
    private enum class Phase(val budgetMs: Long) { PREPARATION(150_000), BUSINESS(150_000), CLEANUP(95_000) }
    private val monitor = Object()
    private var phase = Phase.PREPARATION
    private var started = 0L
    private var phaseStarted = 0L
    private var preparationOwner: Job? = null
    private var expired: Throwable? = null
    private var enabled = false
    private val durations = linkedMapOf<Phase, Long>()

    internal val requiresPreparation: Boolean get() = synchronized(monitor) { enabled }

    internal fun <T> prepare(block: suspend () -> T): T {
        check(requiresPreparation)
        return try {
            runBlocking {
                synchronized(monitor) {
                    requireWithinDeadlineLocked(monotonicNanos())
                    check(phase == Phase.PREPARATION && preparationOwner == null)
                    preparationOwner = requireNotNull(coroutineContext[Job])
                }
                block()
            }
        } catch (error: Throwable) {
            // An external interrupt can return from runBlocking before NonCancellable
            // children finish. The method owner is still null; never let its normal drain
            // stand in for this separate original preparation Job.
            if (error is InterruptedException || error is TestTimedOutException) synchronized(monitor) {
                if (expired == null) { expired = error; preserve(error) }
            }
            throw error
        }
    }

    internal fun business(block: () -> Unit) {
        synchronized(monitor) {
            requireClearLocked()
            check(enabled && phase == Phase.PREPARATION && preparationOwner?.isCompleted == true)
            transitionLocked(Phase.BUSINESS)
        }
        var failure: Throwable? = null
        try { block() } catch (error: Throwable) {
            failure = error
            if (error is InterruptedException || error is TestTimedOutException) synchronized(monitor) {
                if (expired == null) { expired = error; preserve(error) }
            }
        }
        try { cleanup() } catch (error: Throwable) { failure = preserveLegacySessionMethodFailure(failure, error) }
        failure?.let { throw it }
    }

    /** Also reached after a preparation assertion fails and JUnit skips the method. */
    internal fun cleanup() = synchronized(monitor) {
        if (!enabled) return@synchronized
        requireClearLocked()
        if (phase == Phase.PREPARATION && preparationOwner?.isCompleted == false) {
            val error = IllegalStateException("PREPARED_JOURNEY_PREPARATION_OWNER_NOT_DRAINED")
            expired = error; preserve(error); throw error
        }
        if (phase != Phase.CLEANUP) transitionLocked(Phase.CLEANUP)
    }

    internal fun requireClear() = synchronized(monitor) { requireClearLocked() }

    private fun requireClearLocked() { expired?.let { throw it } }
    private fun transitionLocked(next: Phase) {
        val now = monotonicNanos()
        requireWithinDeadlineLocked(now)
        durations[phase] = now - phaseStarted
        Log.i(TAG, "phase=$phase event=end elapsed_ms=${(now - phaseStarted) / 1_000_000}")
        phase = next; phaseStarted = now
        Log.i(TAG, "phase=$phase event=start budget_ms=${phase.budgetMs}")
        monitor.notifyAll()
    }
    private fun requireWithinDeadlineLocked(now: Long) {
        requireClearLocked()
        if (now - phaseStarted >= TimeUnit.MILLISECONDS.toNanos(phase.budgetMs)) {
            val error = TestTimedOutException(phase.budgetMs, TimeUnit.MILLISECONDS)
            expired = error
            // Seal cleanup/admission BEFORE interrupting any original owner. There is no
            // retry/reset API: a late method cannot erase its unknown scene for a green test.
            preserve(error)
            throw error
        }
    }

    override fun apply(base: Statement, description: Description): Statement {
        if (description.getAnnotation(RetainedPreparedJourney::class.java) == null)
            return Timeout.seconds(150).apply(base, description)
        return object : Statement() {
            override fun evaluate() {
                synchronized(monitor) {
                    check(!enabled && started == 0L)
                    enabled = true; started = monotonicNanos(); phaseStarted = started
                }
                Log.i(TAG, "case=${description.methodName} pid=${Process.myPid()} clock=$clockEvidence phase=$phase event=start budget_ms=${phase.budgetMs} total_bound_ms=400000")
                val task = object : FutureTask<Unit>(Callable { base.evaluate(); Unit }) {
                    override fun done() = synchronized(monitor) { monitor.notifyAll() }
                }
                val thread = Thread(task, "retained-prepared-journey-owner").apply { isDaemon = true }
                var failure: Throwable? = null
                thread.start()
                try {
                    synchronized(monitor) {
                        while (true) {
                            val now = monotonicNanos()
                            requireWithinDeadlineLocked(now)
                            if (task.isDone) break
                            val left = TimeUnit.MILLISECONDS.toNanos(phase.budgetMs) - (now - phaseStarted)
                            monitor.wait(left / 1_000_000, (left % 1_000_000).toInt())
                        }
                    }
                    try { task.get() } catch (error: ExecutionException) { throw requireNotNull(error.cause) }
                    synchronized(monitor) {
                        check(phase == Phase.CLEANUP && durations.containsKey(Phase.BUSINESS)) { "PREPARED_JOURNEY_TAIL_NOT_FINISHED" }
                    }
                } catch (error: Throwable) {
                    failure = error
                    if (error is InterruptedException) synchronized(monitor) {
                        // Seal the phase as well as the fixture BEFORE cancelling its
                        // Statement. A late initializer that swallows interruption must
                        // not enter preparation/business after its supervisor has returned.
                        if (expired == null) { expired = error; preserve(error) }
                    }
                    if (!task.isDone) task.cancel(true)
                } finally {
                    // Original owner join budget; counted in cleanup/total, never hidden in
                    // another executor. A live original owner blocks all later fixture work.
                    try {
                        thread.join(5_000)
                        check(!thread.isAlive) { "PREPARED_JOURNEY_OWNER_NOT_JOINED" }
                    } catch (error: Throwable) {
                        preserve(error); failure = preserveLegacySessionMethodFailure(failure, error)
                    }
                    synchronized(monitor) {
                        val ended = monotonicNanos()
                        durations[phase] = ended - phaseStarted
                        val prep = durations[Phase.PREPARATION]?.div(1_000_000) ?: 0L
                        val business = durations[Phase.BUSINESS]?.div(1_000_000) ?: 0L
                        val cleanup = durations[Phase.CLEANUP]?.div(1_000_000) ?: 0L
                        Log.i(TAG, "case=${description.methodName} event=terminal passed=${failure == null} preparation_ms=$prep business_ms=$business cleanup_ms=$cleanup total_ms=${(ended - started) / 1_000_000}")
                    }
                }
                failure?.let { throw it }
            }
        }
    }

    companion object {
        private const val TAG = "RetainedJourneyStages"
        /** Deterministic watchdog negatives only; all phase budgets remain 150/150/95s.
         * The real journey constructor above always uses Android's monotonic clock. */
        internal fun counterexample(preserve: (Throwable) -> Unit, monotonicNanos: () -> Long) =
            RetainedJourneyStages(preserve, monotonicNanos, "counterexample")
    }
}
