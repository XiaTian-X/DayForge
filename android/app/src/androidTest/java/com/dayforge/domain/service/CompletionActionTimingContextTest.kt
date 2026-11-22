package com.dayforge.domain.service

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.BuildConfig
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-device collector checks only; synthetic spans are not business or commit proof.
 * Actual typed tickets and native END remain covered by the complete production UI journey. */
@RunWith(AndroidJUnit4::class)
class CompletionActionTimingContextTest {
    /** An independent runner expectation catches a wrong release ON/OFF APK. Ordinary
     * covered/debug callers retain the original no-argument contract. */
    private fun assertTimingMode(): Boolean {
        val expected = InstrumentationRegistry.getArguments().getString("dayforge.expectedReleaseTimingDiagnostics")
        if (expected == null) {
            assertNotEquals("Release Context checks require an independent ON/OFF expectation", "releaseCalibration", BuildConfig.BUILD_TYPE)
        } else {
            assertTrue("Expected timing mode must be exactly true or false", expected in setOf("true", "false"))
            assertEquals("releaseCalibration", BuildConfig.BUILD_TYPE)
            assertEquals("com.dayforge.testbed", BuildConfig.APPLICATION_ID)
            assertFalse(BuildConfig.DEBUG)
            assertEquals("Actual generated timing flag differs from the selected run", expected == "true", BuildConfig.RELEASE_TIMING_DIAGNOSTICS)
        }
        if (BuildConfig.BUILD_TYPE != "releaseCalibration") assertFalse(BuildConfig.RELEASE_TIMING_DIAGNOSTICS)
        val expectedEnabled = BuildConfig.DEBUG || BuildConfig.RELEASE_TIMING_DIAGNOSTICS
        assertEquals(expectedEnabled, CompletionActionTiming.enabled)
        return expectedEnabled
    }

    private suspend fun <T> withCallerCosts(block: suspend (CountCommandCostTiming.Trace) -> T): T {
        val caller = CountCommandCostTiming.Trace(Long.MAX_VALUE)
        val token = caller.begin(CountCommandCostTiming.Phase.A)
        val previous = CountCommandCostTiming.install(caller)
        try { return block(caller) }
        finally { caller.end(token); CountCommandCostTiming.restore(previous) }
    }

    private fun linesFor(id: Long): List<String> {
        val command = "logcat -d -v raw -t 200 --pid=${Process.myPid()} -s CountCommandCostTiming:D CompletionActionTiming:D"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            reader.readLines().filter { it.startsWith("operation_id=$id ") }
        }
    }

    private suspend fun emittedFor(id: Long): List<String> = withTimeout(5_000) {
        // Log.d returning does not establish logd visibility. Wait for both final markers,
        // then assert the complete payload; do not retry the synthetic operation itself.
        var lines: List<String>
        while (true) {
            lines = linesFor(id)
            if (lines.any { " phase=T1 " in it && " part=2 " in it && " FINAL_AUTHORITY=1," in it } &&
                lines.any { " operation=LINKED_METRICS stage=OPERATION " in it }) break
            delay(50)
        }
        lines
    }

    @Test(timeout = 150_000)
    fun linkedOperationKeepsItsAdmittedJobAndTraceThroughNestedStagesAndDispatcherReturn() = runBlocking<Unit> {
        val timingEnabled = assertTimingMode()
        withCallerCosts { caller ->
            var id: Long? = null
            CompletionActionTiming.withOperation(CompletionActionTiming.Operation.LINKED_METRICS) {
                id = CompletionActionTiming.currentOperationId()
                val admittedJob = currentCoroutineContext()[Job]
                val phase = CountCommandCostTiming.begin(CountCommandCostTiming.Phase.T1)
                try {
                    val frame = CountCommandCostTiming.capture()
                    if (timingEnabled) assertNotNull(phase) else assertNull(phase)
                    if (timingEnabled) {
                        assertNotNull(id); assertNotNull(frame); assertNotSame(caller, frame!!.trace)
                    } else { assertNull(id); assertNull(frame) }
                    CompletionActionTiming.measure(CompletionActionTiming.Stage.PREPARE) {
                        assertSame(admittedJob, currentCoroutineContext()[Job])
                        CompletionActionTiming.withOperation(CompletionActionTiming.Operation.COUNT_INCREMENT) {
                            assertSame(admittedJob, currentCoroutineContext()[Job])
                            assertEquals(id, CompletionActionTiming.currentOperationId())
                            assertSame(frame, CountCommandCostTiming.capture())
                        }
                        withContext(Dispatchers.IO) {
                            assertEquals(id, CompletionActionTiming.currentOperationId())
                            assertSame(frame, CountCommandCostTiming.capture())
                        }
                        assertSame(admittedJob, currentCoroutineContext()[Job])
                    }
                    CountCommandCostTiming.measure(CountCommandCostTiming.Step.TRUST_FINISH) { Unit }
                    CountCommandCostTiming.measure(CountCommandCostTiming.Step.COMMAND_FOOTER) { Unit }
                    CountCommandCostTiming.measure(CountCommandCostTiming.Step.FINAL_AUTHORITY) { Unit }
                } finally { CountCommandCostTiming.end(phase) }
            }
            assertNull(CompletionActionTiming.currentOperationId())
            if (timingEnabled) {
                assertSame(caller, requireNotNull(CountCommandCostTiming.capture()).trace)
                val lines = emittedFor(requireNotNull(id))
                val costs = lines.filter { " phase=T1 " in it }
                assertTrue(costs.all { "balanced_at_flush=true" in it && "columns=count,total_ns,failed,max_ns,first_start_ns,last_end_ns" in it })
                for (step in listOf("TRUST_FINISH", "COMMAND_FOOTER", "FINAL_AUTHORITY")) {
                    assertEquals("Expected exactly one recorded $step", 1, costs.count { " $step=1," in it })
                }
                assertTrue("The final field must not be dropped after the original two parts", costs.any { " part=2 " in it && " FINAL_AUTHORITY=1," in it })
                val operation = lines.single { " operation=LINKED_METRICS stage=OPERATION " in it }
                val fields = operation.split(' ').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                val start = fields.getValue("stage_start_ns").toLong()
                assertTrue(start > 0)
                assertTrue(fields.getValue("stage_end_ns").toLong() >= start)
                assertTrue(start >= fields.getValue("operation_start_ns").toLong())
                assertTrue(fields.getValue("stage_ms").toLong() >= 0)
            }
        }
    }

    /** Collector payload/lifetime only. These synthetic markers are NOT real SQL witnesses;
     * the complete original domain classes and trace/native pairing cover actual placement. */
    @Test(timeout = 150_000)
    fun readCallerMarksSeparateRepeatedBackgroundAndUnphasedForegroundWithoutChangingTheirJobs() = runBlocking<Unit> {
        val timingEnabled = assertTimingMode()
        val start = android.os.SystemClock.elapsedRealtimeNanos()
        val callerJob = currentCoroutineContext()[Job]
        val backgroundThread = Process.myTid()
        repeat(2) {
            CompletionActionTiming.markReadCaller(CompletionActionTiming.ReadCaller.PENDING_LEGACY)
            assertSame(callerJob, currentCoroutineContext()[Job])
            assertNull(CompletionActionTiming.currentOperationId())
        }
        var operation: Long? = null
        var foregroundThread = 0
        CompletionActionTiming.withOperation(CompletionActionTiming.Operation.LINKED_METRICS) {
            operation = CompletionActionTiming.currentOperationId()
            val job = currentCoroutineContext()[Job]
            foregroundThread = Process.myTid()
            // This is the actual diagnostic gap: no T0/A/T1/T2 phase is active.
            assertNull(CountCommandCostTiming.capture())
            CompletionActionTiming.mark(CompletionActionTiming.Stage.METRIC_DELIVERY_ENTER)
            CompletionActionTiming.markReadCaller(CompletionActionTiming.ReadCaller.FORMAL_METRIC_SOURCE)
            assertSame(job, currentCoroutineContext()[Job])
            assertEquals(operation, CompletionActionTiming.currentOperationId())
            assertNull(CountCommandCostTiming.capture())
            CompletionActionTiming.withOperation(CompletionActionTiming.Operation.COUNT_INCREMENT) {
                CompletionActionTiming.markReadCaller(CompletionActionTiming.ReadCaller.FORMAL_METRIC_SOURCE)
                assertSame(job, currentCoroutineContext()[Job])
                assertEquals(operation, CompletionActionTiming.currentOperationId())
                assertNull(CountCommandCostTiming.capture())
            }
            CompletionActionTiming.mark(CompletionActionTiming.Stage.METRIC_DELIVERY_ACCOUNT_ACQUIRED)
            CompletionActionTiming.mark(CompletionActionTiming.Stage.METRIC_DELIVERY_PREFERENCES_START)
            CompletionActionTiming.mark(CompletionActionTiming.Stage.METRIC_DELIVERY_PREFERENCES_RETURNED)
        }
        val end = android.os.SystemClock.elapsedRealtimeNanos()
        assertSame(callerJob, currentCoroutineContext()[Job])
        assertNull(CompletionActionTiming.currentOperationId())
        fun readCallerLines(): List<Map<String, String>> {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "logcat -d -v raw -t 200 --pid=${Process.myPid()} -s CompletionActionTiming:D")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().filter { " kind=READ_CALLER " in it }.map { line ->
                    line.split(' ').map { it.split('=', limit = 2) }.filter { it.size == 2 }.associate { it[0] to it[1] }
                }.filter { it.getValue("elapsed_realtime_ns").toLong() in start..end }
            }
        }
        if (!timingEnabled) {
            assertNull(operation)
            assertTrue("Disabled release emits no read identity or caller marker", readCallerLines().isEmpty())
        } else {
            val marks = withTimeout(5_000) {
                var found = readCallerLines()
                while (found.size < 4) { delay(50); found = readCallerLines() }
                found
            }
            assertEquals(4, marks.size)
            assertEquals(4, marks.map { it.getValue("caller_read_id").toLong() }.toSet().size)
            assertTrue(marks.all { it.getValue("caller_read_id").toLong() > 0 })
            val background = marks.filter { it.getValue("caller") == "PENDING_LEGACY" }
            val foreground = marks.filter { it.getValue("caller") == "FORMAL_METRIC_SOURCE" }
            assertEquals(2, background.size); assertEquals(2, foreground.size)
            assertTrue(background.all { it.getValue("operation_id") == "null" && it.getValue("tid").toInt() == backgroundThread })
            assertTrue(foreground.all { it.getValue("operation_id") == requireNotNull(operation).toString() &&
                it.getValue("tid").toInt() == foregroundThread })
            val boundaries = withTimeout(5_000) {
                var lines = linesFor(requireNotNull(operation))
                while (lines.none { " stage=METRIC_DELIVERY_PREFERENCES_RETURNED " in it }) {
                    delay(50); lines = linesFor(requireNotNull(operation))
                }
                lines
            }
            for (stage in listOf("METRIC_DELIVERY_ENTER", "METRIC_DELIVERY_ACCOUNT_ACQUIRED",
                "METRIC_DELIVERY_PREFERENCES_START", "METRIC_DELIVERY_PREFERENCES_RETURNED"))
                assertEquals(1, boundaries.count { " stage=$stage " in it })
        }
    }

    @Test(timeout = 150_000)
    fun failurePreservesTheOriginalThrowableAndRestoresTheCallerCollector() = runBlocking<Unit> {
        val timingEnabled = assertTimingMode()
        withCallerCosts { caller ->
            val original = IllegalStateException("synthetic-timing-failure")
            var caught: Throwable? = null
            try {
                CompletionActionTiming.withOperation(CompletionActionTiming.Operation.LINKED_METRICS) {
                    val phase = CountCommandCostTiming.begin(CountCommandCostTiming.Phase.T1)
                    try { CountCommandCostTiming.measure(CountCommandCostTiming.Step.FINAL_AUTHORITY) { throw original } }
                    finally { CountCommandCostTiming.end(phase) }
                }
            } catch (error: Throwable) { caught = error }
            assertSame(original, caught)
            assertNull(CompletionActionTiming.currentOperationId())
            if (timingEnabled) assertSame(caller, requireNotNull(CountCommandCostTiming.capture()).trace)
            else assertNull(CountCommandCostTiming.capture())
        }
    }

    @Test(timeout = 150_000)
    fun cancellationRestoresTheCallerAndTheNextOperationGetsANewIdentity() = runBlocking<Unit> {
        val timingEnabled = assertTimingMode()
        withCallerCosts { caller ->
            val started = CompletableDeferred<Long?>()
            val operation = launch {
                CompletionActionTiming.withOperation(CompletionActionTiming.Operation.LINKED_METRICS) {
                    val phase = CountCommandCostTiming.begin(CountCommandCostTiming.Phase.T0)
                    try { started.complete(CompletionActionTiming.currentOperationId()); awaitCancellation() }
                    finally { CountCommandCostTiming.end(phase) }
                }
            }
            val cancelledId = withTimeout(5_000) { started.await() }
            operation.cancel()
            withTimeout(5_000) { operation.join() }
            assertTrue(operation.isCancelled)
            assertNull(CompletionActionTiming.currentOperationId())
            if (timingEnabled) assertSame(caller, requireNotNull(CountCommandCostTiming.capture()).trace)
            else assertNull(CountCommandCostTiming.capture())
            CompletionActionTiming.withOperation(CompletionActionTiming.Operation.LINKED_METRICS) {
                val next = CompletionActionTiming.currentOperationId()
                if (timingEnabled) assertTrue(requireNotNull(next) > requireNotNull(cancelledId))
                else { assertNull(cancelledId); assertNull(next) }
            }
            assertNull(CompletionActionTiming.currentOperationId())
            if (timingEnabled) assertSame(caller, requireNotNull(CountCommandCostTiming.capture()).trace)
            else assertNull(CountCommandCostTiming.capture())
        }
    }
}
