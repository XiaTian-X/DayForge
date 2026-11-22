package com.dayforge

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.repository.CountCommandHostScenario
import dagger.hilt.android.testing.HiltTestApplication
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Separate fixed testbed instrumentation entry, not a JUnit runner. Ordinary discovery
 * remains complete and unchanged. Every host phase has its own explicit identity/result;
 * a deliberately killed prepare phase is NEVER returned as a passed test.
 */
class CountCommandProcessDriver : Instrumentation() {
    private lateinit var arguments: Bundle
    private var phaseDeadline = 0L
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = Bundle(requireNotNull(arguments))
        require(this.arguments.getString("timeout_msec") == "150000")
        phaseDeadline = SystemClock.elapsedRealtime() + PHASE_MILLIS
        InstrumentationRegistry.registerInstance(this, Bundle(this.arguments))
        start()
    }

    override fun onStart() {
        val result = Bundle().apply {
            putString("df_command_driver", "count-command-process/v1")
            for (key in listOf("df_command_run", "df_command_case", "df_command_phase", "df_command_nonce"))
                putString(key, requireNotNull(arguments.getString(key)))
            putInt("df_command_pid", Process.myPid())
            putLong("df_command_phase_budget_ms", PHASE_MILLIS)
            putString("df_command_result", "FAIL")
        }
        val executor = Executors.newSingleThreadExecutor { work -> Thread(work, "dayforge-command-process-phase") }
        var success = false
        val phase = executor.submit {
            check(targetContext.packageName == "com.dayforge.testbed")
            waitForIdleSync()
            if (arguments.getString("df_command_case") in com.dayforge.data.repository.CountCommandProcessProtocol.RETAINED_CASES.map { it.wire })
                com.dayforge.data.repository.CountCommandRetainedHostScenario().runScenarioPhase()
            else CountCommandHostScenario().runScenarioPhase()
        }
        try {
            val remaining = phaseDeadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "COMMAND_DRIVER_STARTUP_TIMEOUT" }
            phase.get(remaining, TimeUnit.MILLISECONDS)
            check(arguments.getString("df_command_phase") == "recover") { "COMMAND_PREPARE_RETURNED_WITHOUT_PROCESS_DEATH" }
            result.putString("df_command_result", "PASS")
            result.putInt("df_command_assertion_groups", 7)
            success = true
        } catch (failure: Throwable) {
            // Persist/report failure before cancellation can release a blocked native
            // checkpoint. The host checks this file as well as drained terminal output.
            try { com.dayforge.data.repository.CountCommandProcessProtocol(targetContext).recordDriverTerminal("FAIL") }
            catch (marker: Throwable) { result.putString("df_command_terminal_marker_failure", marker.javaClass.name) }
            phase.cancel(true)
            val cause = (failure as? java.util.concurrent.ExecutionException)?.cause ?: failure
            result.putString("df_command_failure_type", cause.javaClass.name)
            // Avoid serializing any potentially sensitive exception text/SQL arguments.
            result.putString("df_command_failure_code", cause.message?.takeIf { it.matches(Regex("[A-Z_]{1,120}")) }
                ?: "COMMAND_DRIVER_ASSERTION_OR_RUNTIME_FAILURE")
        } finally {
            if (success) com.dayforge.data.repository.CountCommandProcessProtocol(targetContext).recordDriverTerminal("PASS")
            executor.shutdownNow()
            // A hung native operation is a failed phase, never success. The host retains
            // evidence and handles only its exact isolated PID; no retry or clear occurs.
            finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }
    private companion object { const val PHASE_MILLIS = 150_000L }
}
