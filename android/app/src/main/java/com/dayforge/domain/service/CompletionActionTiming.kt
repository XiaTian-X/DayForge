package com.dayforge.domain.service

import android.os.SystemClock
import android.util.Log
import com.dayforge.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Debug-only wall-clock diagnostics, never an action identity, credential or commit receipt.
 * Start the operation outside any account/Room read scope. A stage does not change the Job,
 * dispatcher or transaction, so transaction-bound readers keep their original scope owner.
 */
internal object CompletionActionTiming {
    enum class Operation { CHECK_IN, COUNT_INCREMENT, COUNT_DECREMENT }
    enum class Stage { OPERATION, PREPARE, WRITE_CALL, AFTER_READ, PROMPT_PREPARE, COMMIT_OBSERVED }

    private val sequence = AtomicLong()

    private class Trace(val id: Long, val operation: Operation, val started: Long) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Trace>
    }

    /** Establish once at the outer coordinator entry; nested callers reuse the same trace. */
    suspend fun <T> withOperation(operation: Operation, block: suspend () -> T): T {
        if (!BuildConfig.DEBUG || currentCoroutineContext()[Trace] != null) return block()
        val trace = Trace(sequence.incrementAndGet(), operation, SystemClock.elapsedRealtime())
        return withContext(trace) { measure(Stage.OPERATION, block) }
    }

    /** WRITE_CALL returning is not the exact SQLite COMMIT instant and may represent a no-op. */
    suspend fun <T> measure(stage: Stage, block: suspend () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val trace = currentCoroutineContext()[Trace] ?: return block()
        val started = SystemClock.elapsedRealtime()
        var outcome = "returned"
        try {
            return block()
        } catch (error: CancellationException) {
            outcome = "cancelled"
            throw error
        } catch (error: Throwable) {
            outcome = "failed"
            throw error
        } finally {
            log(trace, stage, started, outcome)
        }
    }

    /** Only mark COMMIT_OBSERVED when the caller knows a write occurred and its call returned.
     * Absence of this mark does not prove rollback: cancellation can hide a completed COMMIT.
     */
    suspend fun mark(stage: Stage) {
        if (!BuildConfig.DEBUG) return
        val trace = currentCoroutineContext()[Trace] ?: return
        log(trace, stage, SystemClock.elapsedRealtime(), "observed")
    }

    private fun log(trace: Trace, stage: Stage, started: Long, outcome: String) {
        val now = SystemClock.elapsedRealtime()
        Log.d("CompletionActionTiming", "operation_id=${trace.id} operation=${trace.operation} stage=$stage " +
            "outcome=$outcome stage_ms=${now - started} operation_ms=${now - trace.started} " +
            "elapsed_realtime_ms=$now")
    }
}
