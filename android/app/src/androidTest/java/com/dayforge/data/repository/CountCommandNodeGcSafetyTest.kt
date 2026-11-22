package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandNodeGcSafetyTest : CountCommandNodeGcFixture() {
    @Test(timeout = 150_000) fun preparedAndDeliveredHistoryStayReadableWhileOnlyOldCowNodesDisappear() = runBlocking<Unit> {
        val completed = retainedNoEffect(); val pending = List(3) { prepared() }
        garbage(1); garbage(2)
        val preserved = commands().resolve(completed).historical
        val before = image(); val live = live()
        assertTrue(before.getValue("count_command_nodes").size > live.size)
        native.resetCounts(); val reports = finish()
        assertTrue(reports.sumOf { it.deletedNodes } > 0); assertTrue(native.deletes > 0)
        assertOnlyOrphansRemoved(before, live)
        val resolved = commands().resolve(completed).historical
        assertEquals(preserved.intent.text(), resolved.intent.text()); assertEquals(preserved.status, resolved.status)
        assertEquals(preserved.outcome?.text(), resolved.outcome?.text())
        assertEquals(pending.map { it.commandId }.toSet(), commands().discover(8).commands.map { it.reference.commandId }.toSet())
        for (ref in pending) assertEquals(CountCommandRecords.Execution.PREPARED, commands().resolve(ref.native()).historical.status.execution)
    }

    @Test(timeout = 150_000) fun badMandatoryMacNeverMeansAnEmptyLivePool() = runBlocking<Unit> {
        prepared(); garbage(3)
        db.withTransaction { owner.maintenance(db) { sql.execSQL("UPDATE count_command_recovery_roots SET mac=zeroblob(32)") } }
        val before = image(); native.resetCounts()
        val failed = next(collector())
        assertEquals(CountTrustNodeCollector.Status.FAILED, failed.status); assertEquals("COMMAND_PRIMARY_MAC_INVALID", failed.reason)
        assertEquals(0, native.deletes); assertEquals(before, image())
    }

    @Test(timeout = 150_000) fun corruptLiveNodeAbandonsMarkBeforeAnyDelete() = runBlocking<Unit> {
        prepared(); garbage(4)
        db.withTransaction { owner.maintenance(db) {
            val root = requireNotNull(CountCommandNativeRows(sql) {}.root().catalogHash)
            sql.execSQL("UPDATE count_command_nodes SET body=X'00' WHERE digest=?", arrayOf(root))
        } }
        val before = image(); val gc = collector(); native.resetCounts()
        var rejected = false
        repeat(32) {
            if (!rejected) { val report = next(gc); assertEquals(0, report.deletedNodes)
                rejected = report.status == CountTrustNodeCollector.Status.FAILED
                if (!rejected) assertEquals(CountTrustNodeCollector.Status.PROGRESS, report.status)
            }
        }
        assertTrue(rejected); assertEquals(0, native.deletes); assertEquals(before, image())
    }

    @Test(timeout = 150_000) fun changedCatalogAfterCompleteMarkRestartsBeforeSweep() = runBlocking<Unit> {
        val first = prepared(); garbage(5); val gc = collector(); untilSweep(gc)
        val second = prepared(); val before = image(); val live = live(); native.resetCounts()
        val restarted = next(gc)
        assertEquals(CountTrustNodeCollector.Status.RESTARTED, restarted.status); assertEquals(0, native.deletes); assertEquals(before, image())
        finish(gc); assertOnlyOrphansRemoved(before, live)
        assertEquals(CountCommandRecords.Execution.PREPARED, commands().resolve(first.native()).historical.status.execution)
        assertEquals(CountCommandRecords.Execution.PREPARED, commands().resolve(second.native()).historical.status.execution)
    }
    @Test(timeout = 150_000) fun corruptedUnreachableBytesAreNotRepairedByDeletingThem() = runBlocking<Unit> {
        prepared(); val orphan = garbage(6)
        db.withTransaction { owner.maintenance(db) {
            sql.execSQL("UPDATE count_command_nodes SET body=X'00' WHERE digest=?", arrayOf(orphan))
        } }
        val before = image(); val gc = collector(); native.resetCounts(); untilSweep(gc)
        val failed = next(gc)
        assertEquals(CountTrustNodeCollector.Status.FAILED, failed.status); assertEquals(0, failed.deletedNodes)
        assertEquals(before, image())
    }

    @Test(timeout = 150_000) fun missingMandatoryRootNeverTurnsTheCatalogIntoGarbage() = runBlocking<Unit> {
        prepared(); garbage(7)
        db.withTransaction { owner.maintenance(db) { sql.execSQL("DELETE FROM count_command_recovery_roots") } }
        val before = image(); native.resetCounts(); val failed = next(collector())
        assertEquals(CountTrustNodeCollector.Status.FAILED, failed.status); assertEquals("COMMAND_PRIMARY_ROOT_MISSING", failed.reason)
        assertEquals(0, native.deletes); assertEquals(before, image())
    }

}
