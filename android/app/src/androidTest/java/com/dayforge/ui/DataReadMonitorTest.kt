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
    @Test fun successfulProjectionsResetTheConsecutiveRaceBudget() = runTest {
        var opens = 0
        flow {
            opens++
            emit(opens)
            if (opens <= 6) throw com.dayforge.data.repository.HabitReadSnapshotChanged("COUNT_ACTIVITY_CHANGED")
        }.retryChangedHabitSnapshot().test {
            for (expected in 1..7) assertEquals(expected, awaitItem())
            awaitComplete()
        }
        assertEquals(7, opens)
    }

    @Test fun changedSnapshotReopensWholeReadWithoutPublishingFalseFailure() = runTest {
        val monitor = DataReadMonitor("ReadTest")
        var opens = 0
        monitor.recover(emptyList<Int>()) { flow {
            opens++
            emit(monitor.read(emptyList()) {
                if (opens < 3) throw com.dayforge.data.repository.HabitReadSnapshotChanged("COUNT_ACTIVITY_CHANGED")
                listOf(42)
            })
        } }.test {
            assertEquals(listOf(42), awaitItem())
            assertFalse(monitor.error.value)
            assertEquals(3, opens)
        }
    }

    @Test fun changedSnapshotRetryIsBoundedAndNeverRetriesAnOrdinarySecurityFailure() = runTest {
        for (snapshotChanged in listOf(true, false)) {
            val monitor = DataReadMonitor("ReadTest")
            var opens = 0
            monitor.recover(emptyList<Int>()) { flow {
                opens++
                emit(monitor.read(emptyList<Int>()) {
                    if (snapshotChanged) throw com.dayforge.data.repository.HabitReadSnapshotChanged("COUNT_ACTIVITY_CHANGED")
                    else throw IllegalStateException("COUNT_SESSION_CHANGED")
                })
            } }.test {
                assertEquals(emptyList<Int>(), awaitItem())
                assertTrue(monitor.error.value)
                assertEquals(if (snapshotChanged) 4 else 1, opens)
            }
        }
    }

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
