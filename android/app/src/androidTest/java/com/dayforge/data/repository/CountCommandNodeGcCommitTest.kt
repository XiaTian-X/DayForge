package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandNodeGcCommitTest : CountCommandNodeGcFixture() {
    private suspend fun sweep(): CountCommandNodeCollector {
        prepared(); repeat(3) { garbage(10 + it) }; return collector().also { untilSweep(it) }
    }
    @Test(timeout = 150_000) fun actualSecondDeleteFailureRollsBackCompleteBatch() = runBlocking<Unit> {
        val gc = sweep(); val before = image(); native.resetCounts(); native.failDeleteAt = 2
        val failed = next(gc)
        assertEquals(CountTrustNodeCollector.Status.FAILED, failed.status); assertEquals(0, failed.deletedNodes)
        assertEquals(2, native.deletes); assertTrue(native.faultTouched); assertEquals(before, image())
        native.failDeleteAt = null; reopen(); val live = live(); finish(); assertOnlyOrphansRemoved(before, live)
    }
    @Test(timeout = 150_000) fun committedThenLostEndDoesNotPublishCursorAndNewOwnerUsesSameRoot() = runBlocking<Unit> {
        val gc = sweep(); val before = image(); val live = live(); native.resetCounts()
        native.endFault = CountCommandNodeGcNativeSpy.EndFault.COMMITTED_THEN_THROW
        val failed = next(gc)
        assertEquals(CountTrustNodeCollector.Status.FAILED, failed.status); assertEquals(0, failed.deletedNodes)
        assertTrue(native.faultTouched); assertTrue(native.deletes > 0); assertTrue(owner.isRetired)
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, gc.collectSlice().status)
        reopen(); val after = image()
        assertTrue(after.getValue("count_command_nodes").size < before.getValue("count_command_nodes").size)
        assertEquals(before - "count_command_nodes", after - "count_command_nodes")
        finish(); assertOnlyOrphansRemoved(before, live)
    }
    @Test(timeout = 150_000) fun cancellationAfterRealDeleteRollsBackAndCannotReturnSuccess() = runBlocking<Unit> {
        val gc = sweep(); val before = image(); native.resetCounts(); var returned = false
        var returnedReport: CountTrustNodeCollector.Report? = null
        val child = launch {
            native.cancelDelete = requireNotNull(currentCoroutineContext()[Job])
            // Retry only the existing bounded fixture's BUSY/reader-deferred admission.
            // Cancellation still comes solely from the actual native DELETE after count==1.
            returnedReport = next(gc); returned = true
        }
        child.join()
        val diagnostic = "report=$returnedReport deletes=${native.deletes} fault=${native.faultTouched} returned=$returned"
        assertTrue("Actual DELETE must cancel its owning child: $diagnostic", child.isCancelled)
        assertTrue("Native cancellation fault must be reached: $diagnostic", native.faultTouched)
        assertTrue("At least one real DELETE is required: $diagnostic", native.deletes > 0)
        assertFalse("Cancelled slice cannot return success: $diagnostic", returned)
        assertEquals(before, image()); reopen(); val live = live(); finish(); assertOnlyOrphansRemoved(before, live)
    }
    @Test(timeout = 150_000) fun sameValueExtraStatusWriteAfterRealDeleteIsNotHiddenByFinalRows() = runBlocking<Unit> {
        val gc = sweep(); val before = image(); native.resetCounts(); native.sameValueStatusAfterDelete = true
        val failed = next(gc)
        assertEquals(CountTrustNodeCollector.Status.FAILED, failed.status); assertEquals(0, failed.deletedNodes)
        assertTrue(native.faultTouched); assertEquals(1, native.statusFaultRows); assertTrue(native.deletes > 0)
        assertEquals(before, image()); reopen(); val live = live(); finish(); assertOnlyOrphansRemoved(before, live)
    }
    @Test(timeout = 150_000) fun rolledBackUnknownEndKeepsEveryByteAndDiscardsProgress() = runBlocking<Unit> {
        val gc = sweep(); val before = image(); native.resetCounts()
        native.endFault = CountCommandNodeGcNativeSpy.EndFault.ROLLED_BACK_THEN_THROW
        val failed = next(gc)
        assertEquals(CountTrustNodeCollector.Status.FAILED, failed.status); assertEquals(0, failed.deletedNodes)
        assertTrue(native.faultTouched); assertTrue(native.deletes > 0); assertTrue(owner.isRetired)
        reopen(); assertEquals(before, image()); val live = live(); finish(); assertOnlyOrphansRemoved(before, live)
    }

}
