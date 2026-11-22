package com.dayforge.data.local

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.repository.CountDualMaintenanceNative
import com.dayforge.data.repository.CountNodeCollectionSchedule
import com.dayforge.data.repository.CountTrustNodeCollector
import com.dayforge.data.repository.CountTrustNodeMaintenance
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Actual Hilt Host/GraphFactory/Room; no supplied graph, provider reset or clock substitution. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CountDualNodeMaintenanceHostTest {
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 2) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    private val native = CountDualMaintenanceNative()
    init { epochs.nativeFactory = native }
    @Before fun connect() { hilt.inject(); epochs.connect() }

    private suspend fun authenticateWithoutOpeningGraph() = epochs.sessions.exclusive {
        epochs.tokens.clearTokens()
        epochs.tokens.saveLoginSession("synthetic-dual-gc-access", "synthetic-dual-gc-refresh", "member",
            "ac490000-0000-4000-8000-000000000091", false)
    }
    private suspend fun finishActiveGraph(): List<CountNodeCollectionSchedule.Slice> = withTimeout(45_000) {
        val slices = mutableListOf<CountNodeCollectionSchedule.Slice>()
        while (true) {
            val observation = requireNotNull(epochs.host.tryWithActiveGraph { graph ->
                val before = graph.countTrustNodeCollector.requested || graph.countCommandNodeCollector.requested
                val slice = graph.countNodeCollectionSchedule.next()
                Triple(before, slice, graph.countTrustNodeCollector.requested || graph.countCommandNodeCollector.requested)
            })
            if (!observation.first) break
            val slice = observation.second; slices += slice
            slice.report?.let { report ->
                assertTrue("Actual Host slice must make progress or meet a transient reader: $report",
                    report.status in setOf(CountTrustNodeCollector.Status.PROGRESS, CountTrustNodeCollector.Status.FINISHED,
                        CountTrustNodeCollector.Status.BUSY, CountTrustNodeCollector.Status.RESTARTED) ||
                        report.status == CountTrustNodeCollector.Status.DEFERRED && report.reason == "READER_OR_OWNER_PRESENT")
            }
            // The actual Host lease is gone before honoring the scheduler's real delay.
            if (observation.third) delay(slice.pauseMillis)
        }
        slices
    }

    @Test fun coldRealWorkerDoesNotOpenAndActualReplacementGetsFreshBothPoolState() = runBlocking<Unit> {
        authenticateWithoutOpeningGraph(); assertEquals(0, epochs.realRoomCreates)
        assertNull(epochs.host.tryWithActiveGraph { fail("Cold optional work opened a graph"); Unit })
        val parent = SupervisorJob(); val owned = CoroutineScope(parent + Dispatchers.IO)
        val worker = CountTrustNodeMaintenance(epochs.host)
        try {
            worker.start(owned); worker.start(owned); assertEquals(1, parent.children.count())
            // Preserve the actual30s startup yield. Direct optional admission is checked
            // before/after too; elapsed waiting alone is not proof of its private invocation.
            withTimeout(45_000) { delay(31_000) }
            assertEquals(0, epochs.realRoomCreates); assertTrue(native.cataloguePools.isEmpty())
            assertNull(epochs.host.tryWithActiveGraph { fail("Cold Host became active from maintenance"); Unit })
        } finally { parent.cancel(); withContext(NonCancellable) { withTimeout(5_000) { parent.join() } } }
        epochs.login()
        val old = epochs.current { it }; val created = epochs.realRoomCreates
        val same = requireNotNull(epochs.host.tryWithActiveGraph { it.countNodeCollectionSchedule })
        assertSame(old.countNodeCollectionSchedule, same)
        val finished = finishActiveGraph()
        for (pool in CountNodeCollectionSchedule.Pool.entries)
            assertTrue(finished.any { it.pool == pool && it.report?.status == CountTrustNodeCollector.Status.FINISHED })
        assertNull(old.countNodeCollectionSchedule.next().pool)
        epochs.clearAndAwaitReplacement()
        val fresh = epochs.current { it }
        assertEquals(created + 1, epochs.realRoomCreates)
        assertNotSame(old.countNodeCollectionSchedule, fresh.countNodeCollectionSchedule)
        assertNotSame(old.countTrustNodeCollector, fresh.countTrustNodeCollector)
        assertNotSame(old.countCommandNodeCollector, fresh.countCommandNodeCollector)
        assertFalse(old.database.isOpen); assertTrue(fresh.database.isOpen)
        // Both finished old pools have30s backoff. A newly owned graph starts with COUNT
        // immediately, without inheriting either old deadline or its rotation preference.
        val first = requireNotNull(epochs.host.tryWithActiveGraph { it.countNodeCollectionSchedule.next() })
        assertEquals(CountNodeCollectionSchedule.Pool.COUNT, first.pool); assertNotNull(first.report)
        val calls = native.cataloguePools.size
        assertNull(old.countNodeCollectionSchedule.next().pool)
        assertEquals(calls, native.cataloguePools.size)
        finishActiveGraph()
    }

    @Test fun cancelledHostWorkerKeepsItsRealLeaseUntilNativeFinallyAndThenAllowsClose() = runBlocking<Unit> {
        epochs.login()
        val old = epochs.current { graph ->
            val owner = requireNotNull(CountTrustRuntime.forDatabase(graph.database))
            graph.database.withTransaction { owner.maintenance(graph.database) {
                val bytes = "host-count-orphan".toByteArray()
                graph.database.openHelper.writableDatabase.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)",
                    arrayOf<Any?>(CountTrustIndex.hash(bytes), bytes))
            } }
            graph
        }
        val before = epochs.exactMain(); val created = epochs.realRoomCreates
        val gate = CountDualMaintenanceNative.EndBarrier(CountNodeCollectionSchedule.Pool.COUNT)
        native.barrier = gate
        val parent = SupervisorJob(); val owned = CoroutineScope(parent + Dispatchers.IO)
        val worker = CountTrustNodeMaintenance(epochs.host)
        try {
            worker.start(owned); val job = parent.children.single()
            assertTrue(withContext(Dispatchers.IO) { gate.entered.await(45, TimeUnit.SECONDS) })
            job.cancel(); assertTrue(job.isCancelled); assertFalse(job.isCompleted)
            worker.start(owned); assertEquals(listOf(job), parent.children.toList())
            val rejected = try { epochs.host.shutdownWhenIdle(); null } catch (error: IllegalStateException) { error }
            assertEquals("DATABASE_EPOCH_SHUTDOWN_BUSY", rejected?.message)
            assertFalse(epochs.host.lifecycleEnded); assertTrue(old.database.isOpen)
            assertEquals(created, epochs.realRoomCreates)
            gate.release.countDown(); withTimeout(5_000) { job.join() }
            assertEquals(0L, gate.exited.count)
            val owner = requireNotNull(CountTrustRuntime.forDatabase(old.database))
            assertFalse(owner.hasUnknownGraphBoundary()); assertFalse(owner.isRetired)
            assertEquals(before, epochs.exactMain())
            epochs.host.shutdownWhenIdle()
            assertTrue(epochs.host.lifecycleEnded); assertFalse(old.database.isOpen)
            assertEquals(created, epochs.realRoomCreates)
        } finally {
            gate.release.countDown(); native.barrier = null
            parent.cancel(); withContext(NonCancellable) { withTimeout(5_000) { parent.join() } }
        }
    }
}
