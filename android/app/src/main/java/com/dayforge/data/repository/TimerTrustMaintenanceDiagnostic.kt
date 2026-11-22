package com.dayforge.data.repository

import com.dayforge.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** DEBUG-only bounded observations, never permission or commit evidence. No SQL, values,
 * identifiers, exceptions, callbacks or owners are retained; another attempt replaces this one. */
internal object TimerTrustMaintenanceDiagnostic {
    enum class Kind { START, PAUSE, RESUME, STOP, CANCEL }
    enum class Reason {
        ENTERED, NO_LOCAL_PREPARATION, OWNER_OR_BOUNDARY_MISS, DUPLICATE_PREPARATION,
        PREPARED, PREPARE_NULL, NO_TIMER_COVERAGE, COMMAND_INPUT, SESSION_MISSING,
        HABIT_MISSING, NOT_ELIGIBLE, NEXT_SEQUENCE, CONTROL_GENERATION, COMMAND_TIME,
        COMMAND_TYPE, START_INPUT, RESUME_INPUT, UNSUPPORTED_TABLE, EVENT_COUNT,
        EFFECT_TABLE, EMPTY_EFFECTS, BLOCK_BUDGET, VALUE_BUDGET, SEALED_BEFORE_END
    }
    data class Snapshot(val attempt: Long, val kind: Kind, val reason: Reason,
        val table: String? = null, val actual: Int? = null, val expected: Int? = null)
    class Attempt internal constructor(private val id: Long, private val kind: Kind) {
        fun mark(reason: Reason, table: String? = null, actual: Int? = null, expected: Int? = null) {
            val old = latest.get() ?: return
            if (old.attempt != id) return
            val name = table?.let { if (it in tables) it else "OTHER" }
            latest.compareAndSet(old, Snapshot(id, kind, reason, name, actual, expected))
        }
    }
    private val sequence = AtomicLong()
    private val latest = AtomicReference<Snapshot?>()
    private val tables = setOf("timelogs", "timer_segments", "timelog_day_allocations", "timer_command_outbox",
        "next_request_origins", "next_transmissions", "next_acceptances", "next_challenge_rounds",
        "next_challenge_births", "next_challenge_state")
    fun kind(transition: TimerTransition): Kind = when (transition) {
        is TimerTransition.Start -> Kind.START
        is TimerTransition.Pause -> if (transition.isPaused) Kind.PAUSE else Kind.RESUME
        is TimerTransition.Stop -> Kind.STOP
        is TimerTransition.Cancel -> Kind.CANCEL
    }
    fun begin(transition: TimerTransition): Attempt? {
        if (!BuildConfig.DEBUG) return null
        val id = sequence.incrementAndGet(); val kind = kind(transition)
        latest.set(Snapshot(id, kind, Reason.ENTERED))
        return Attempt(id, kind)
    }
    fun snapshot(): Snapshot? = latest.get()
}
