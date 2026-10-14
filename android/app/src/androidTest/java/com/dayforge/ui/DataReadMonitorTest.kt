package com.dayforge.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataReadMonitorTest {
    @Test fun sourceFailureClearsStaleDataAndExplicitRetryActuallyReopensTheSource() = runTest {
        val monitor = DataReadMonitor("ReadTest"); var opens = 0
        monitor.recover(emptyList<Int>()) { flow {
            opens++
            if (opens == 1) throw IllegalStateException("SYNTHETIC_SOURCE_FAILURE")
            emit(monitor.read(emptyList()) { listOf(7) })
        } }.test {
            assertEquals(emptyList<Int>(), awaitItem()); assertTrue(monitor.error.value)
            monitor.retry()
            assertEquals(listOf(7), awaitItem()); assertFalse(monitor.error.value); assertEquals(2, opens)
        }
    }

    @Test fun projectionFailureDoesNotEndObservationAndCancellationIsNeverConvertedToEmptyData() = runTest {
        val monitor = DataReadMonitor("ReadTest")
        assertEquals(emptyList<Int>(), monitor.read(emptyList<Int>()) { throw IllegalStateException("SYNTHETIC_PROJECTION_FAILURE") })
        assertTrue(monitor.error.value)
        assertEquals(listOf(9), monitor.read(emptyList()) { listOf(9) }); assertFalse(monitor.error.value)
        try {
            monitor.read(emptyList<Int>()) { throw CancellationException("cancelled") }; fail("Cancellation swallowed")
        } catch (expected: CancellationException) { assertFalse(monitor.error.value) }
        val entered = CompletableDeferred<Unit>()
        val subscription = launch { monitor.recover(emptyList<Int>()) { flow {
            entered.complete(Unit); awaitCancellation()
        } }.collect() }
        entered.await(); subscription.cancelAndJoin(); assertFalse(monitor.error.value)
    }
}
