package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandNodeGcLifecycleTest : CountCommandNodeGcFixture() {
    @Test(timeout = 150_000) fun frozenReplicaAndPreviewRetirementNeverSweepPrimaryEvidence() = runBlocking<Unit> {
        val reference = prepared(); garbage(20)
        val primaryTables = setOf("count_command_intents", "count_command_status", "count_command_results",
            "count_command_recovery_roots", "count_command_nodes")
        val beforeQualification = image()
        val current = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        val summary = CountHistoryReader(db, tokens, sessions).readSummary(current)
        assertEquals(habit.uuid, requireNotNull(summary.actionIdentity).habitUuid)
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) {
            assertNotNull("The original COUNT reader must build a real authenticated cache", it)
        } }
        val qualified = image()
        assertEquals(1, qualified.getValue("count_trust_root").size)
        assertTrue(qualified.getValue("count_trust_nodes").isNotEmpty())
        assertEquals(beforeQualification.filterKeys { it in primaryTables }, qualified.filterKeys { it in primaryTables })
        val access = access()
        val fence = NextReplicaFenceEntity(id(701), id(1), id(2), id(3), id(704), access.session.authentication.generation,
            requireNotNull(access.deviceId), requireNotNull(access.capabilityRevision).toLong(), "a".repeat(64))
        NextReplicaWriteFence(db).acquire(fence)
        val frozen = image(); native.resetCounts()
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, next(collector()).status)
        assertEquals(0, native.deletes); assertEquals(frozen, image())
        NextReplicaWriteFence(db).closeBeforeCommit(fence)
        val beforeDiscard = image()
        assertEquals(1, beforeDiscard.getValue("count_trust_root").size)
        assertTrue(beforeDiscard.getValue("count_trust_nodes").isNotEmpty())
        val schema = db.withTransaction { owner.maintenance(db) { owner.schemaSnapshot() } }
        val controlColumns = sql.query("PRAGMA main.table_info(count_trust_control)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
        assertTrue(CountTrustRecoveryPreparation(db, tokens, sessions).discardCache(access()))
        val retired = image(); assertTrue(retired.getValue("count_command_intents").isNotEmpty())
        assertTrue(owner.isRetired)
        assertTrue(CountTrustRetirementReason.RECOVERY_PREPARATION in owner.retirementReasons)
        val optional = setOf("count_trust_root", "count_trust_nodes", "count_trust_routes")
        optional.forEach { assertTrue(retired.getValue(it).isEmpty()) }
        assertEquals(beforeDiscard.filterKeys { it !in optional && it != "count_trust_control" },
            retired.filterKeys { it !in optional && it != "count_trust_control" })
        val generation = controlColumns.indexOf("generation").also { assertTrue(it >= 0) } + 1
        val dirty = controlColumns.indexOf("dirty").also { assertTrue(it >= 0) } + 1
        val expectedControl = beforeDiscard.getValue("count_trust_control").single().mapIndexed { index, cell -> when (index) {
            generation -> cell.first to Math.addExact(cell.second as Long, 1L)
            dirty -> cell.first to 1L
            else -> cell // Every other native type/value, storage, anchor and rowid is unchanged.
        } }
        assertEquals(listOf(expectedControl), retired.getValue("count_trust_control"))
        assertEquals(schema, db.withTransaction { owner.maintenance(db) { owner.schemaSnapshot() } })
        assertEquals(qualified.filterKeys { it in primaryTables }, retired.filterKeys { it in primaryTables })
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, collector().collectSlice().status)
        assertEquals(0, native.deletes); assertEquals(retired, image())
        assertEquals(CountCommandRecords.Execution.PREPARED, commands().resolve(reference.native()).historical.status.execution)
        assertEquals(retired, image())
    }

    @Test(timeout = 150_000) fun newOwnerDiscardsPrivateMarkAndDoesNotOpenColdDatabaseOnlyForMaintenance() = runBlocking<Unit> {
        val ref = prepared(); garbage(21); val old = collector(); untilSweep(old)
        val before = image(); val live = live(); reopen(); val fresh = collector()
        assertFalse(fresh.requested)
        val cold = fresh.collectSlice(); assertEquals(CountTrustNodeCollector.Status.DEFERRED, cold.status)
        assertEquals("NO_OPEN_WORK", cold.reason); assertFalse(fresh.requested)
        assertEquals(before, image()); assertTrue(fresh.requested)
        finish(fresh); assertOnlyOrphansRemoved(before, live)
        assertEquals(CountCommandRecords.Execution.PREPARED, commands().resolve(ref.native()).historical.status.execution)
        val stable = image(); assertEquals(CountTrustNodeCollector.Status.DEFERRED, old.collectSlice().status); assertEquals(stable, image())
    }

    @Test(timeout = 150_000) fun registeredNativeBeginDefersBeforeWaitingOnItsSqliteConnection() = runBlocking<Unit> {
        prepared(); garbage(22); val gc = collector(); val before = image()
        val reached = CountDownLatch(1); val release = CountDownLatch(1); val ended = CountDownLatch(1)
        native.pauseBegin = reached to release; native.pauseBeginEnded = ended
        val liveReader = async(Dispatchers.IO) { db.withTransaction { db.habitDao().getHabitById(habit.id) } }
        try {
            assertTrue(withContext(Dispatchers.IO) { reached.await(10, TimeUnit.SECONDS) })
            val report = withTimeout(5_000) { gc.collectSlice() }
            assertEquals(CountTrustNodeCollector.Status.DEFERRED, report.status)
            assertEquals("READER_OR_OWNER_PRESENT", report.reason); assertEquals(0, report.deletedNodes)
            release.countDown(); assertNotNull(liveReader.await()); assertTrue(withContext(Dispatchers.IO) { ended.await(10, TimeUnit.SECONDS) })
        } finally {
            release.countDown(); withContext(NonCancellable) { liveReader.join() }
        }
        assertEquals(before, image()); val live = live(); finish(gc); assertOnlyOrphansRemoved(before, live)
    }
}
