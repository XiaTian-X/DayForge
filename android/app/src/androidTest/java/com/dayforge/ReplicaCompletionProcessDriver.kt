package com.dayforge

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.repository.ReplicaCompletionHostScenario
import com.dayforge.data.repository.ReplicaCompletionProcessProtocol
import dagger.hilt.android.testing.HiltTestApplication
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixed replica-only entry. Checkpoints are closed-resource boundaries, never JUnit passes. */
class ReplicaCompletionProcessDriver : Instrumentation() {
    private lateinit var arguments: Bundle
    private var phaseDeadline = 0L

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        phaseDeadline = SystemClock.elapsedRealtime() + PHASE_MILLIS
        super.onCreate(arguments)
        this.arguments = Bundle(requireNotNull(arguments))
        require(this.arguments.getString("timeout_msec") == PHASE_MILLIS.toString())
        InstrumentationRegistry.registerInstance(this, Bundle(this.arguments))
        start()
    }

    override fun onStart() {
        val result = Bundle().apply {
            putString("df_replica_driver", ReplicaCompletionProcessProtocol.FAMILY)
            for (key in listOf("df_replica_run", "df_replica_case", "df_replica_phase", "df_replica_nonce"))
                putString(key, arguments.getString(key))
            putInt("df_replica_pid", Process.myPid())
            putLong("df_replica_phase_budget_ms", PHASE_MILLIS)
            putString("df_replica_result", "FAIL")
        }
        var protocol: ReplicaCompletionProcessProtocol? = null
        val executor = Executors.newSingleThreadExecutor { work -> Thread(work, "dayforge-replica-final-phase") }
        var phase: java.util.concurrent.Future<*>? = null
        var success = false
        try {
            check(targetContext.packageName == "com.dayforge.testbed")
            val active = ReplicaCompletionProcessProtocol(targetContext, phaseDeadline)
            protocol = active
            phase = executor.submit {
                waitForIdleSync()
                ReplicaCompletionHostScenario(active).runScenarioPhase()
            }
            phase.get(active.remainingMillis(), TimeUnit.MILLISECONDS)
            check(active.phase == ReplicaCompletionProcessProtocol.Phase.FRESH_COMPLETE) {
                "REPLICA_CHECKPOINT_PHASE_RETURNED_WITHOUT_PROCESS_DEATH"
            }
            active.recordDriverTerminal("PASS")
            active.remainingMillis() // Close, terminal write and driver evidence share the original deadline.
            result.putString("df_replica_result", "PASS")
            result.putInt("df_replica_assertion_groups", 8)
            success = true
        } catch (failure: Throwable) {
            // Record failure BEFORE cancellation can release a checkpoint. Neither a retained
            // checkpoint nor a late SIGKILL can turn this failed/expired phase into an expected kill.
            try { protocol?.recordDriverTerminal("FAIL") }
            catch (marker: Throwable) { result.putString("df_replica_terminal_marker_failure", marker.javaClass.name) }
            phase?.cancel(true)
            val cause = (failure as? ExecutionException)?.cause ?: failure
            result.putString("df_replica_failure_type", cause.javaClass.name)
            result.putString("df_replica_failure_code", cause.message?.takeIf { it.matches(Regex("[A-Z_]{1,120}")) }
                ?: "REPLICA_DRIVER_ASSERTION_OR_RUNTIME_FAILURE")
            // Best-effort source positions only. Never serialize messages, SQL, paths or values,
            // and never let observation replace the original failed instrumentation result.
            try { result.putString("df_replica_failure_locations", failureLocations(failure)) }
            catch (_: Throwable) { /* The original failure/result remains authoritative. */ }
        } finally {
            executor.shutdownNow()
            // A stuck operation is failure, not a claim that its resources exited. The host
            // preserves exact-PID evidence; it never retries, clears data or reports it as PASS.
            finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }

    /** Bounded ASCII metadata: <= 4096 bytes, 6 Throwables, 6 frames each, depth <= 3.
     * Stack getters may allocate their original arrays; this is an output bound, not a heap cap.
     */
    private fun failureLocations(root: Throwable): String {
        val text = StringBuilder(4096)
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var nodes = 0
        var truncated = false
        fun token(value: String?, limit: Int): String = value.orEmpty().take(limit).map { char ->
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "._$<>-") char else '?'
        }.joinToString("").ifEmpty { "?" }
        fun append(line: String) {
            if (text.length + line.length + 1 <= 4060) text.append(line).append('\n')
            else truncated = true
        }
        fun visit(error: Throwable, relation: String, depth: Int) {
            if (nodes == 6 || depth > 3) { truncated = true; return }
            if (seen.put(error, true) != null) { append("REPEATED_REFERENCE $relation"); return }
            val index = nodes++
            append("T$index $relation " + token(error.javaClass.name, 160))
            val frames = error.stackTrace
            for (frame in frames.take(6)) {
                val basename = frame.fileName?.takeLast(96)?.substringAfterLast('/')?.substringAfterLast('\\')
                append(" at " + token(frame.className, 160) + "." + token(frame.methodName, 96) +
                    "(" + token(basename, 96) + ":" + frame.lineNumber + ")")
            }
            if (frames.size > 6) truncated = true
            error.cause?.let { visit(it, "cause-of-T$index", depth + 1) }
            val suppressed = error.suppressed
            for ((position, item) in suppressed.take(2).withIndex()) visit(item, "suppressed-$position-of-T$index", depth + 1)
            if (suppressed.size > 2) truncated = true
        }
        try { visit(root, "root", 0) }
        catch (_: Throwable) { append("LOCATION_METADATA_UNAVAILABLE") }
        if (truncated) text.append("LOCATION_METADATA_TRUNCATED\n")
        return text.toString()
    }

    private companion object { const val PHASE_MILLIS = 150_000L }
}
