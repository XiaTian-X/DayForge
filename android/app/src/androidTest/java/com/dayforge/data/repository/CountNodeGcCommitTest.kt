package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class CountNodeGcCommitTest : CountNodeGcFixture() {
    private suspend fun prepared(): CountTrustNodeCollector {
        ready()
        orphan(201, -1000); orphan(202, -999); orphan(203, -998)
        return collector().also { untilSweep(it) }
    }

    @Test fun secondDeleteFullErrorRollsBackTheWholeBatchAndReturnsNoProgress() = runBlocking<Unit> {
        val gc = prepared(); val before = nativeAssertions().capture(); spy.resetCounts(); spy.failDeleteAt = 2
        val report = nextSlice(gc)
        assertEquals(CountTrustNodeCollector.Status.FAILED, report.status)
        assertEquals(0, report.deletedNodes); assertEquals(2, spy.deletes); assertTrue(spy.faultTouched)
        nativeAssertions().assertUnchanged(before)
        spy.failDeleteAt = null
        finish(gc)
    }

    @Test fun committedThenThrowDoesNotPublishCursorAndFreshOwnerRebuildsFromDurableRoot() = runBlocking<Unit> {
        val gc = prepared(); val previousOwner = owner; val before = nativeAssertions().capture(); val live = reachable()
        spy.resetCounts(); spy.endFault = CountNodeGcNativeSpy.EndFault.COMMITTED_THEN_THROW
        val report = nextSlice(gc)
        assertEquals(CountTrustNodeCollector.Status.FAILED, report.status)
        assertTrue(spy.faultTouched); assertTrue(spy.deletes > 0); assertEquals(0, report.deletedNodes)
        assertTrue(previousOwner.isRetired)
        val retiredRetry = gc.collectSlice()
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, retiredRetry.status)
        assertEquals("NO_OPEN_WORK", retiredRetry.reason); assertEquals(0, retiredRetry.deletedNodes)
        storage.reopen()
        val after = nativeAssertions().capture()
        assertTrue(after.getValue("count_trust_nodes").size < before.getValue("count_trust_nodes").size)
        assertEquals(before - "count_trust_nodes", after - "count_trust_nodes")
        assertEquals(live, reachable())
        finish(); assertOnlyUnreachableRemoved(before, live)
    }

    @Test fun rolledBackThenThrowKeepsEveryByteAndFreshOwnerCanRestart() = runBlocking<Unit> {
        val gc = prepared(); val previousOwner = owner; val before = nativeAssertions().capture()
        spy.resetCounts(); spy.endFault = CountNodeGcNativeSpy.EndFault.ROLLED_BACK_THEN_THROW
        val report = nextSlice(gc)
        assertEquals(CountTrustNodeCollector.Status.FAILED, report.status)
        assertTrue(spy.faultTouched); assertTrue(spy.deletes > 0); assertEquals(0, report.deletedNodes)
        assertTrue(previousOwner.isRetired)
        val retiredRetry = gc.collectSlice()
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, retiredRetry.status)
        assertEquals("NO_OPEN_WORK", retiredRetry.reason); assertEquals(0, retiredRetry.deletedNodes)
        storage.reopen(); nativeAssertions().assertUnchanged(before)
        finish()
    }

    @Test fun cancellationAfterActualDeleteRollsBackAndCannotReturnASuccessReport() = runBlocking<Unit> {
        val gc = prepared(); val before = nativeAssertions().capture(); spy.resetCounts(); var returned = false
        val child = launch {
            spy.cancelDelete = requireNotNull(currentCoroutineContext()[Job])
            nextSlice(gc); returned = true
        }
        child.join(); assertTrue(child.isCancelled); assertTrue(spy.faultTouched); assertFalse(returned)
        nativeAssertions().assertUnchanged(before)
        finish(gc)
    }

    @Test fun finalRealKeystoreFailureRollsBackAlreadyExecutedDeletes() = runBlocking<Unit> {
        val gc = prepared(); val before = nativeAssertions().capture(); spy.resetCounts(); var removed = false
        spy.afterDelete = {
            if (!removed) {
                removed = true
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(cipherAlias) }
            }
        }
        val report = nextSlice(gc)
        assertTrue(removed); assertTrue(spy.deletes > 0)
        assertEquals(CountTrustNodeCollector.Status.FAILED, report.status); assertEquals(0, report.deletedNodes)
        nativeAssertions().assertUnchanged(before)
        spy.afterDelete = null
        tokens.saveLoginSession("new-access", "new-refresh", "member", id(1), false); register()
        finish(gc)
    }

    @Test fun simultaneousCallCannotAdvanceTheSamePrivateCycleTwice() = runBlocking<Unit> {
        val gc = prepared(); val before = nativeAssertions().capture(); val live = reachable()
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        spy.resetCounts()
        spy.afterDelete = { reached.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val first = async(Dispatchers.IO) { nextSlice(gc) }
        val firstReport = try {
            assertTrue(withContext(Dispatchers.IO) { reached.await(5, TimeUnit.SECONDS) })
            val concurrent = gc.collectSlice()
            assertEquals(CountTrustNodeCollector.Status.BUSY, concurrent.status)
            assertEquals("ADMISSION", concurrent.phase); assertEquals(0, concurrent.deletedNodes)
            release.countDown()
            first.await()
        } finally {
            spy.afterDelete = null; release.countDown()
            withContext(NonCancellable) { first.join() }
        }
        assertEquals("SWEEP", firstReport.executedPhase)
        assertTrue(firstReport.deletedNodes > 0)
        val reports = when (firstReport.status) {
            CountTrustNodeCollector.Status.PROGRESS -> listOf(firstReport) + finish(gc)
            CountTrustNodeCollector.Status.FINISHED -> listOf(firstReport)
            else -> error("First actual sweep did not commit supported progress: $firstReport")
        }
        assertEquals(CountTrustNodeCollector.Status.FINISHED, reports.last().status)
        assertTrue(reports.all { it.catalogueRows <= 16384 && it.scannedRows <= 64 &&
            it.graphBytes <= 512 * 1024 && it.deletedNodes <= 32 && it.deletedBytes <= 512 * 1024 })
        assertEquals(before.getValue("count_trust_nodes").size - live.size, reports.sumOf { it.deletedNodes })
        assertEquals(reports.sumOf { it.deletedNodes }, spy.deletes)
        assertFalse(gc.requested)
        assertOnlyUnreachableRemoved(before, live)
    }
    @Test fun realNodeReadIoFailureKeepsTheWholePoolAndDiscardsTentativeMarks() = runBlocking<Unit> {
        ready(); orphan(204)
        val gc = collector(); untilMark(gc); val before = nativeAssertions().capture()
        spy.failNodeRead = true
        val result = nextSlice(gc)
        assertTrue(spy.faultTouched); assertEquals(CountTrustNodeCollector.Status.FAILED, result.status)
        assertEquals(0, result.deletedNodes); nativeAssertions().assertUnchanged(before)
        finish(gc)
    }

    @Test fun cancellationAtActualMarkBodyReadCannotLeakACompletedBitmap() = runBlocking<Unit> {
        ready(); orphan(205)
        val gc = collector(); untilMark(gc); val before = nativeAssertions().capture(); var returned = false
        val child = launch {
            spy.cancelNodeRead = requireNotNull(currentCoroutineContext()[Job])
            nextSlice(gc); returned = true
        }
        child.join(); assertTrue(child.isCancelled); assertTrue(spy.faultTouched); assertFalse(returned)
        nativeAssertions().assertUnchanged(before)
        finish(gc)
    }

}
