package com.dayforge.domain.service

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.BuildConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Collector-only physical checks. These messages are not real ACK/lock/COMMIT evidence;
 * the unchanged original Core/round/UI classes remain the actual-path oracles. */
@RunWith(AndroidJUnit4::class)
class AckTimingCorrelationTest {
    @Test(timeout = 150_000) fun boundedRequestKeyAndRepeatedMarkersNeverAdoptTheForegroundOperation() = runBlocking<Unit> {
        assertEquals("com.dayforge.testbed", BuildConfig.APPLICATION_ID)
        val enabled = BuildConfig.DEBUG || BuildConfig.RELEASE_TIMING_DIAGNOSTICS
        assertEquals(enabled, CompletionActionTiming.enabled)
        val request = "ac610000-0000-4000-8000-000000000001"
        // Independent fixed SHA-256 vector of UTF8("dayforge/ack-timing/v1:" + request).
        val expected = "36bc998b7c7a7fa87bb23e2376662cb5980beea850bd37533c0bd9c70ab3f4af"
        val start = SystemClock.elapsedRealtimeNanos()
        val caller = currentCoroutineContext()[Job]
        val thread = Process.myTid()
        assertEquals(if (enabled) expected else null, CompletionActionTiming.ackRequestKey(request))
        for (invalid in listOf("", "x".repeat(36), "x".repeat(4096))) {
            assertNull(CompletionActionTiming.ackRequestKey(invalid))
            CompletionActionTiming.markAck(CompletionActionTiming.AckStage.ACCEPT_REQUESTED, invalid)
        }
        CompletionActionTiming.markAck(CompletionActionTiming.AckStage.PUSH_RESULT_AVAILABLE, request)
        CompletionActionTiming.withOperation(CompletionActionTiming.Operation.LINKED_METRICS) {
            val originalJob = currentCoroutineContext()[Job]
            val operation = CompletionActionTiming.currentOperationId()
            if (enabled) assertNotNull(operation) else assertNull(operation)
            CompletionActionTiming.markAck(CompletionActionTiming.AckStage.ACCEPT_REQUESTED, request)
            CompletionActionTiming.markAck(CompletionActionTiming.AckStage.ACCOUNT_ACQUIRED, request)
            assertSame(originalJob, currentCoroutineContext()[Job])
            assertEquals(operation, CompletionActionTiming.currentOperationId())
        }
        // A repeat is the same request key, deliberately not a new attempt identity.
        CompletionActionTiming.markAck(CompletionActionTiming.AckStage.ACCEPT_REQUESTED, request)
        val end = SystemClock.elapsedRealtimeNanos()
        assertSame(caller, currentCoroutineContext()[Job])
        assertNull(CompletionActionTiming.currentOperationId())
        fun messages(): List<Map<String, String>> {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "logcat -d -v raw -t 200 --pid=${Process.myPid()} -s CompletionActionTiming:D")
            return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { reader ->
                reader.readLines().filter { it.startsWith("kind=ACK_STAGE ") }.map { line ->
                    assertFalse("No raw request identity belongs in this diagnostic", request in line)
                    line.split(' ').map { it.split('=', limit = 2) }.filter { it.size == 2 }.associate { it[0] to it[1] }
                }.filter { it.getValue("elapsed_realtime_ns").toLong() in start..end }
            }
        }
        if (!enabled) assertTrue("Disabled diagnostics emit no ACK marker", messages().isEmpty())
        else {
            val rows = withTimeout(5_000) {
                var found = messages()
                while (found.size < 4) { delay(50); found = messages() }
                found
            }
            assertEquals(4, rows.size)
            assertTrue(rows.all { it.keys == setOf("kind", "stage", "request_key", "tid", "elapsed_realtime_ns") })
            assertTrue(rows.all { it.getValue("request_key") == expected && it.getValue("tid").toInt() == thread })
            assertEquals(1, rows.count { it.getValue("stage") == "PUSH_RESULT_AVAILABLE" })
            assertEquals(2, rows.count { it.getValue("stage") == "ACCEPT_REQUESTED" })
            assertEquals(1, rows.count { it.getValue("stage") == "ACCOUNT_ACQUIRED" })
            assertTrue(rows.map { it.getValue("elapsed_realtime_ns").toLong() }.zipWithNext().all { (a, b) -> a <= b })
        }
    }
}
