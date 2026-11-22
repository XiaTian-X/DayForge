package com.dayforge.data.repository

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountDualNodeMaintenanceTest : CountDualNodeMaintenanceFixture() {
    @Test(timeout = 150_000) fun realBothPoolsAlternateAndOnlyUnreachableRowsDisappear() = runBlocking<Unit> {
        val reference = readyBoth()
        val preserved = commands().resolve(reference).historical
        assertEquals(CountCommandRecords.Execution.NO_EFFECT, preserved.status.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, preserved.status.delivery)
        assertEquals(CountCommandOutcome.Kind.NO_EFFECT, requireNotNull(preserved.outcome).kind)
        countGarbage(100); garbage(100)
        val count = CountTrustNodeCollector(db, tokens, sessions); val command = collector()
        val countLive = countLive(); val commandLive = live(); val before = image(); val schema = schemaImage()
        val schedule = CountNodeCollectionSchedule(count, command)
        assertTrue(count.requested); assertTrue(command.requested)
        val first = schedule.next(); val second = schedule.next()
        assertEquals(CountNodeCollectionSchedule.Pool.COUNT, first.pool)
        assertEquals(CountNodeCollectionSchedule.Pool.COMMAND, second.pool)
        assertNotNull(first.report); assertNotNull(second.report)
        val slices = listOf(first, second) + finishSchedule(schedule, count, command)
        for (pool in CountNodeCollectionSchedule.Pool.entries) {
            assertTrue(slices.any { it.pool == pool && it.report?.status == CountTrustNodeCollector.Status.PROGRESS })
            assertTrue(slices.any { it.pool == pool && it.report?.status == CountTrustNodeCollector.Status.FINISHED })
            assertTrue(slices.filter { it.pool == pool }.sumOf { it.report?.deletedNodes ?: 0 } >= 1)
        }
        assertTrue(dualNative.cataloguePools.containsAll(CountNodeCollectionSchedule.Pool.entries))
        assertBothOnlyOrphansRemoved(before, countLive, commandLive, schema)
        val resolved = commands().resolve(reference).historical
        assertEquals(preserved.intent.text(), resolved.intent.text())
        assertEquals(preserved.status, resolved.status)
        assertEquals(requireNotNull(preserved.outcome).text(), requireNotNull(resolved.outcome).text())
        assertEquals(CountCommandRecords.Execution.NO_EFFECT, resolved.status.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, resolved.status.delivery)
    }

    @Test(timeout = 150_000) fun actualCommandReadFailureBacksOffOnlyItsPoolAndCountStillProgresses() = runBlocking<Unit> {
        readyBoth(); countGarbage(101); garbage(101)
        val count = CountTrustNodeCollector(db, tokens, sessions); val command = collector()
        // Real completed MARK positions the command failure at its first actual sweep read;
        // COUNT is still untouched, so its progress after failure is not a timing accident.
        untilSweep(command)
        val schedule = CountNodeCollectionSchedule(count, command)
        val countLive = countLive(); val commandLive = live(); val before = image(); val schema = schemaImage()
        native.failNodeRead = true
        var failedStarted = 0L
        var failure: CountNodeCollectionSchedule.Slice? = null
        withTimeout(45_000) {
            while (failure == null) {
                val started = SystemClock.elapsedRealtime(); val slice = schedule.next()
                val report = slice.report
                if (report?.status == CountTrustNodeCollector.Status.FAILED) {
                    assertEquals(CountNodeCollectionSchedule.Pool.COMMAND, slice.pool)
                    assertEquals("SQLiteDiskIOException", report.reason)
                    assertTrue(native.faultTouched); failure = slice; failedStarted = started
                } else {
                    assertTrue(report == null || report.status in setOf(CountTrustNodeCollector.Status.PROGRESS,
                        CountTrustNodeCollector.Status.FINISHED, CountTrustNodeCollector.Status.BUSY) ||
                        report.status == CountTrustNodeCollector.Status.DEFERRED && report.reason == "READER_OR_OWNER_PRESENT")
                    delay(slice.pauseMillis)
                }
            }
        }
        // The exact command-node SELECT failed; COUNT never loads bodies via that SQL.
        assertFalse(native.failNodeRead)
        var countProgress = false; var commandResumed = false
        withTimeout(45_000) {
            while (!commandResumed) {
                val started = SystemClock.elapsedRealtime(); val slice = schedule.next()
                if (slice.pool == CountNodeCollectionSchedule.Pool.COUNT && slice.report?.status in setOf(
                        CountTrustNodeCollector.Status.PROGRESS, CountTrustNodeCollector.Status.FINISHED)) countProgress = true
                if (slice.pool == CountNodeCollectionSchedule.Pool.COMMAND) {
                    // A safe attempt-start lower bound, not a claim about a precisely
                    // measured30s from the later report-return instant.
                    assertTrue("No command reselection within30s of its failed attempt start", started - failedStarted >= 30_000)
                    assertTrue("A failed command pool must not stall ready COUNT work", countProgress)
                    assertTrue(slice.report?.status in setOf(CountTrustNodeCollector.Status.PROGRESS, CountTrustNodeCollector.Status.FINISHED))
                    commandResumed = true
                } else delay(slice.pauseMillis)
            }
        }
        finishSchedule(schedule, count, command)
        assertBothOnlyOrphansRemoved(before, countLive, commandLive, schema)
    }

    @Test(timeout = 150_000) fun queuedAndInteractiveForegroundWorkDeclinesBothRealPoolsWithoutChangingMain() = runBlocking<Unit> {
        readyBoth(); countGarbage(102); garbage(102)
        val count = CountTrustNodeCollector(db, tokens, sessions); val command = collector()
        val before = image(); val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val queuedEntered = CompletableDeferred<Unit>(); val queuedRelease = CompletableDeferred<Unit>()
        val holding = async(Dispatchers.IO) { sessions.exclusive { held.complete(Unit); release.await() } }
        var queued: Deferred<Unit>? = null
        try {
            withTimeout(5_000) { held.await() }
            queued = async(start = CoroutineStart.UNDISPATCHED) {
                sessions.exclusive { queuedEntered.complete(Unit); queuedRelease.await() }
            }
            assertFalse(requireNotNull(queued).isCompleted)
            val schedule = CountNodeCollectionSchedule(count, command)
            for (pool in CountNodeCollectionSchedule.Pool.entries) {
                val slice = withTimeout(5_000) { schedule.next() }
                assertEquals(pool, slice.pool); assertEquals(CountTrustNodeCollector.Status.BUSY, slice.report?.status)
                assertEquals("ACCOUNT", slice.report?.phase); assertEquals(0, slice.report?.deletedNodes)
            }
            assertEquals(before, image())
            release.complete(Unit); holding.await(); withTimeout(5_000) { queuedEntered.await() }
            assertFalse(requireNotNull(queued).isCompleted)
        } finally {
            release.complete(Unit); queuedRelease.complete(Unit)
            withContext(NonCancellable) { withTimeout(5_000) { holding.join(); queued?.join() } }
        }
        val schedule = CountNodeCollectionSchedule(count, command)
        sessions.withInteractiveReadPriority {
            for (pool in CountNodeCollectionSchedule.Pool.entries) {
                val slice = schedule.next(); assertEquals(pool, slice.pool)
                assertEquals(CountTrustNodeCollector.Status.BUSY, slice.report?.status)
                assertEquals("ACCOUNT", slice.report?.phase)
            }
            assertEquals(before, image())
        }
        val countLive = countLive(); val commandLive = live(); val schema = schemaImage()
        finishSchedule(schedule, count, command)
        assertBothOnlyOrphansRemoved(before, countLive, commandLive, schema)
    }

    @Test(timeout = 150_000) fun cancelledRealWorkerCannotRestartBeforeItsNativeEndAndActualJobExit() = runBlocking<Unit> {
        readyBoth(); countGarbage(103); garbage(103)
        val count = CountTrustNodeCollector(db, tokens, sessions); val command = collector()
        val countLive = countLive(); val commandLive = live(); val before = image(); val schema = schemaImage()
        val gate = CountDualMaintenanceNative.EndBarrier(CountNodeCollectionSchedule.Pool.COMMAND)
        dualNative.barrier = gate
        val parent = SupervisorJob(); val owned = CoroutineScope(parent + Dispatchers.IO)
        val worker = CountTrustNodeMaintenance(count, command)
        try {
            worker.start(owned); worker.start(owned)
            val first = parent.children.single()
            assertTrue(withContext(Dispatchers.IO) { gate.entered.await(45, TimeUnit.SECONDS) })
            first.cancel(); assertTrue(first.isCancelled); assertFalse(first.isCompleted)
            worker.start(owned)
            assertEquals("Cancelled but not completed native cleanup still owns the only worker", listOf(first), parent.children.toList())
            gate.release.countDown(); withTimeout(5_000) { first.join() }
            assertEquals(0L, gate.exited.count); assertTrue(first.isCompleted); assertTrue(parent.children.none())
            assertFalse("Normal returned native END must not become unknown", owner.hasUnknownGraphBoundary())
            assertFalse(owner.isRetired)
            // Actual restart keeps the real30s startup yield; no fake clock or slice callback.
            worker.start(owned); assertEquals(1, parent.children.count())
            withTimeout(45_000) { while (count.requested || command.requested) delay(250) }
            assertBothOnlyOrphansRemoved(before, countLive, commandLive, schema)
        } finally {
            gate.release.countDown(); dualNative.barrier = null
            parent.cancel(); withContext(NonCancellable) { withTimeout(5_000) { parent.join() } }
        }
    }
}
