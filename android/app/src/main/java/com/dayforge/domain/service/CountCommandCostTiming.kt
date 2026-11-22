package com.dayforge.domain.service

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

/** Opt-in wall-time observations only. No SQL, authority, Job, lock, transaction or receipt.
 * Fixed counters retain no business values. Spans are inclusive: parent and child durations
 * overlap and must not be added. Native callbacks use a Frame captured by their original Tx.
 * This collector never authorizes execution or establishes a successful native END.
 * PHASE records duration only; its failed column is not an outcome. The original outer
 * CompletionActionTiming outcome and actual native evidence decide success/failure. */
internal object CountCommandCostTiming {
    enum class Phase { T0, A, T1, T2 }
    enum class Step {
        PHASE, APPROVED_SCHEMA, SCHEMA_BRIDGE, OPEN_SCHEMA_NATIVE, OPEN_SCHEMA_SHAPE,
        OPEN_SCHEMA_HASH, OPEN_ROOT_NATIVE, OPEN_ROOT_AUTH, OPEN_ANCHOR_NATIVE, MEMBER,
        A_PLAN, EFFECTS_BEFORE_COMMAND, EFFECTS_AFTER_COMMAND, FINAL_CONTENTS,
        EFFECTS_JOURNAL, EFFECTS_COMPARE, COUNT_INDEX_PROMOTION, COUNT_SIGN, COUNT_SEAL_STORE,
        COMMAND_DIRECTORY_PATCH, COMMAND_SIGN, COMMAND_ANCHOR_DIGEST, COMMAND_ROOT_CAS,
        SEAL, SET_SUCCESS_CHECK, NATIVE_FINAL_SOURCES, NATIVE_FINAL_JOURNAL,
        NATIVE_SCHEMA, NATIVE_SCHEMA_SHAPE, NATIVE_ROOT, NATIVE_SCHEMA_HASH,
        NATIVE_ROOT_AUTH, NATIVE_ANCHOR, NATIVE_END_CALL,
        TRUST_FINISH, COMMAND_FOOTER, FINAL_AUTHORITY,
        T2_PLAN_PREPARE, T2_PLAN_PUBLISH,
        T2_BASELINE_READ, T2_BASELINE_AGGREGATE, T2_CHILD_STEP, T2_PARENT_STEP,
        METRIC_INPUT_ISSUED, METRIC_DEFINITION_REUSED, METRIC_OBSERVATION_BUILT, METRIC_OBSERVATION_REUSED
    }
    private val local = ThreadLocal<Trace?>()
    private const val LIMIT = 4096L
    internal class Trace(private val operation: Long) {
        private val phase = AtomicInteger(-1)
        private val values = AtomicLongArray(Phase.entries.size * Step.entries.size * 6)
        private val invalid = AtomicBoolean()
        private val flushed = AtomicBoolean()
        private val frames = Phase.entries.map { Frame(this, it) }
        fun begin(next: Phase): PhaseToken {
            val owns = phase.compareAndSet(-1, next.ordinal)
            if (!owns) invalid.set(true)
            return PhaseToken(this, next, SystemClock.elapsedRealtimeNanos(), owns)
        }
        fun capture(): Frame? = phase.get().takeIf { it >= 0 }?.let(frames::get)
        fun end(token: PhaseToken) {
            record(token.phase, Step.PHASE, token.start, SystemClock.elapsedRealtimeNanos(), false)
            if (token.owns && !phase.compareAndSet(token.phase.ordinal, -1)) invalid.set(true)
        }
        fun record(phase: Phase, step: Step, started: Long, ended: Long, failed: Boolean) {
            val nanos = ended - started
            if (flushed.get()) { invalid.set(true); return }
            val slot = (phase.ordinal * Step.entries.size + step.ordinal) * 6
            var count = values.get(slot)
            while (count < LIMIT && !values.compareAndSet(slot, count, count + 1)) count = values.get(slot)
            if (count >= LIMIT || nanos < 0) { invalid.set(true); return }
            values.addAndGet(slot + 1, nanos)
            if (failed) values.incrementAndGet(slot + 2)
            var maximum = values.get(slot + 3)
            while (nanos > maximum && !values.compareAndSet(slot + 3, maximum, nanos)) maximum = values.get(slot + 3)
            var first = values.get(slot + 4)
            while ((first == 0L || started < first) && !values.compareAndSet(slot + 4, first, started)) first = values.get(slot + 4)
            var last = values.get(slot + 5)
            while (ended > last && !values.compareAndSet(slot + 5, last, ended)) last = values.get(slot + 5)
        }
        /** Only called after the original outer withContext returned/unwound. Logging failure
         * must not replace the business result/exception; no logging occurs inside a span. */
        fun flush() {
            if (!flushed.compareAndSet(false, true)) return
            try {
                for (p in Phase.entries) for (part in 0 until (Step.entries.size + 17) / 18) {
                    val text = StringBuilder(3072)
                    text.append("operation_id=").append(operation).append(" phase=").append(p.name)
                        .append(" part=").append(part).append(" balanced_at_flush=").append(!invalid.get() && phase.get() == -1)
                        .append(" inclusive=true columns=count,total_ns,failed,max_ns,first_start_ns,last_end_ns")
                    var present = false
                    for (ordinal in part * 18 until minOf((part + 1) * 18, Step.entries.size)) {
                        val s = Step.entries[ordinal]
                        val i = (p.ordinal * Step.entries.size + s.ordinal) * 6
                        val n = values.get(i)
                        if (n != 0L) {
                            present = true
                            text.append(' ').append(s.name).append('=').append(n)
                            for (offset in 1..5) text.append(',').append(values.get(i + offset))
                        }
                    }
                    if (present) Log.d("CountCommandCostTiming", text.toString())
                }
            } catch (_: Exception) { /* Diagnostics cannot replace the original business outcome. */ }
        }
    }
    internal class PhaseToken(val trace: Trace, val phase: Phase, val start: Long, val owns: Boolean)
    internal class Frame(val trace: Trace, val phase: Phase)
    fun install(trace: Trace?): Trace? = local.get().also { local.set(trace) }
    fun restore(trace: Trace?) { if (trace == null) local.remove() else local.set(trace) }
    fun begin(phase: Phase): PhaseToken? = if (CompletionActionTiming.enabled) local.get()?.begin(phase) else null
    fun end(token: PhaseToken?) { token?.trace?.end(token) }
    fun capture(): Frame? = if (CompletionActionTiming.enabled) local.get()?.capture() else null
    inline fun <T> measure(step: Step, block: () -> T): T = measure(capture(), step, block)
    inline fun <T> measure(frame: Frame?, step: Step, block: () -> T): T {
        if (frame == null) return block()
        val started = SystemClock.elapsedRealtimeNanos()
        var failed = true
        try { return block().also { failed = false } }
        finally { frame.trace.record(frame.phase, step, started, SystemClock.elapsedRealtimeNanos(), failed) }
    }
}
