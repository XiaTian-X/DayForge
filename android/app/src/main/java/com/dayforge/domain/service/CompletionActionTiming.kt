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
import kotlinx.coroutines.ThreadContextElement

/** Opt-in wall-clock diagnostics, never an action identity, credential or commit receipt.
 * Start the operation outside any account/Room read scope. A stage does not change the Job,
 * dispatcher or transaction, so transaction-bound readers keep their original scope owner.
 */
internal object CompletionActionTiming {
    // One predicate for operation IDs and both collector entry guards. The release flag is
    // generated true only for explicitly requested isolated releaseCalibration diagnostics.
    internal val enabled: Boolean = BuildConfig.DEBUG || BuildConfig.RELEASE_TIMING_DIAGNOSTICS
    enum class Operation { CHECK_IN, COUNT_INCREMENT, COUNT_DECREMENT, LINKED_METRICS }
    enum class Stage { OPERATION, PREPARE, WRITE_CALL, AFTER_READ, PROMPT_PREPARE, COMMIT_OBSERVED, METRIC_DELIVERY_SOURCE_READ,
        METRIC_DELIVERY_ENTER, METRIC_DELIVERY_ACCOUNT_ACQUIRED,
        METRIC_DELIVERY_PREFERENCES_START, METRIC_DELIVERY_PREFERENCES_RETURNED }

    // Fixed call-site labels only. These do not authorize a read or prove its native END.
    internal enum class ReadCaller { RECURRING_OTHER, DASHBOARD_FULL, DASHBOARD_PROJECTION,
        FORMAL_METRIC_SOURCE, PENDING_LEGACY, PENDING_ONCE }
    private object ReadIds { val sequence = AtomicLong() }

    /** Call only at the first statement of the ORIGINAL Room transaction body. Background
     * reads without a Trace keep operation_id=null; inherited traces retain their real ID.
     * Each invocation has its own diagnostic ID,
     * including a repeated consumer; native BEGIN/END must be correlated independently.
     * The disabled path reads no context/clock/thread and allocates/increments nothing.
     */
    internal suspend fun markReadCaller(caller: ReadCaller?) {
        if (!enabled) return
        val label = caller ?: ReadCaller.RECURRING_OTHER
        val operation = currentCoroutineContext()[Trace]?.id
        val read = ReadIds.sequence.incrementAndGet()
        val now = SystemClock.elapsedRealtimeNanos()
        val thread = android.os.Process.myTid()
        try {
            Log.d("CompletionActionTiming", "operation_id=$operation kind=READ_CALLER caller=$label " +
                "caller_read_id=$read tid=$thread elapsed_realtime_ns=$now")
        } catch (_: Exception) { /* Logging cannot change a business read or its exception. */ }
    }

    internal enum class AckStage { PUSH_RESULT_AVAILABLE, ACCEPT_REQUESTED, ACCOUNT_ACQUIRED }

    /** Diagnostic correlation only: no raw UUID, credential, operation/attempt identity or
     * authority. Invalid/unbounded inputs omit the marker; original business validation runs.
     * No state/salt/map is retained. Shipping release returns before any encoding or lookup. */
    internal fun ackRequestKey(requestId: String): String? {
        if (!enabled) return null
        return try {
            if (requestId.length != 36 || !com.dayforge.domain.model.isContractUuid(requestId)) return null
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("dayforge/ack-timing/v1:" + requestId).toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            CharArray(bytes.size * 2).also { out -> bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 255
                out[index * 2] = hex[value ushr 4]; out[index * 2 + 1] = hex[value and 15]
            } }.concatToString()
        } catch (_: Exception) { null }
    }

    /** Intentionally does NOT inspect/inherit CompletionActionTiming.Trace: a sync request
     * must not become a foreground operation merely because a caller carries its context. */
    internal fun markAck(stage: AckStage, requestId: String) {
        if (!enabled) return
        try {
            if (requestId.length != 36 || !com.dayforge.domain.model.isContractUuid(requestId)) return
            val at = SystemClock.elapsedRealtimeNanos()
            val thread = android.os.Process.myTid()
            val key = ackRequestKey(requestId) ?: return
            Log.d("CompletionActionTiming", "kind=ACK_STAGE stage=$stage request_key=$key tid=$thread elapsed_realtime_ns=$at")
        } catch (_: Exception) { /* A diagnostic failure cannot replace the business result. */ }
    }

    private val sequence = AtomicLong()

    private class Trace(val id: Long, val operation: Operation, val started: Long, val startedNanos: Long) :
        AbstractCoroutineContextElement(Key), ThreadContextElement<CountCommandCostTiming.Trace?> {
        val costs = if (operation == Operation.COUNT_INCREMENT || operation == Operation.LINKED_METRICS)
            CountCommandCostTiming.Trace(id) else null
        override fun updateThreadContext(context: CoroutineContext) = CountCommandCostTiming.install(costs)
        override fun restoreThreadContext(context: CoroutineContext, oldState: CountCommandCostTiming.Trace?) =
            CountCommandCostTiming.restore(oldState)
        companion object Key : CoroutineContext.Key<Trace>
    }

    /** Establish once at the outer coordinator entry; nested callers reuse the same trace. */
    suspend fun <T> withOperation(operation: Operation, block: suspend () -> T): T {
        if (!enabled || currentCoroutineContext()[Trace] != null) return block()
        val trace = Trace(sequence.incrementAndGet(), operation, SystemClock.elapsedRealtime(), SystemClock.elapsedRealtimeNanos())
        try { return withContext(trace) { measure(Stage.OPERATION, block) } }
        finally { trace.costs?.flush() }
    }

    /** Read-only diagnostic link; not a command ID, permission or commit witness. */
    suspend fun currentOperationId(): Long? = if (enabled) currentCoroutineContext()[Trace]?.id else null

    /** WRITE_CALL returning is not the exact SQLite COMMIT instant and may represent a no-op. */
    suspend fun <T> measure(stage: Stage, block: suspend () -> T): T {
        if (!enabled) return block()
        val trace = currentCoroutineContext()[Trace] ?: return block()
        val started = SystemClock.elapsedRealtime()
        val startedNanos = SystemClock.elapsedRealtimeNanos()
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
            log(trace, stage, started, startedNanos, outcome)
        }
    }

    /** Only mark COMMIT_OBSERVED when the caller knows a write occurred and its call returned.
     * Absence of this mark does not prove rollback: cancellation can hide a completed COMMIT.
     */
    suspend fun mark(stage: Stage) {
        if (!enabled) return
        val trace = currentCoroutineContext()[Trace] ?: return
        log(trace, stage, SystemClock.elapsedRealtime(), SystemClock.elapsedRealtimeNanos(), "observed")
    }

    private fun log(trace: Trace, stage: Stage, started: Long, startedNanos: Long, outcome: String) {
        val now = SystemClock.elapsedRealtime()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        Log.d("CompletionActionTiming", "operation_id=${trace.id} operation=${trace.operation} stage=$stage " +
            "outcome=$outcome stage_ms=${now - started} operation_ms=${now - trace.started} " +
            "elapsed_realtime_ms=$now operation_start_ns=${trace.startedNanos} " +
            "stage_start_ns=$startedNanos stage_end_ns=$nowNanos")
    }
}
