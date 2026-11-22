package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0WaitDiagnosticsTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
    private fun change(name:String) = db.openHelper.writableDatabase.execSQL(
        "UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?",arrayOf<Any>(name,"",millis,metric.id))
    private fun durableName():String = SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY).use { raw ->
        raw.rawQuery("SELECT name FROM metrics WHERE id=?",arrayOf(metric.id.toString())).use { assertTrue(it.moveToFirst());it.getString(0) }
    }
    private fun checkTrace(trace:G0PhaseTrace,outerOk:Boolean,ends:Int=1) {
        assertEquals(ends,trace.waitSamples.size)
        assertEquals(ends,trace.waitSamples.map { it.id }.distinct().size)
        for(sample in trace.waitSamples) {
            assertEquals("engine_end_call",sample.phase);assertTrue(sample.ok)
            assertEquals(sample.startTid,sample.endTid);assertTrue(sample.end>=sample.start)
            assertTrue(sample.counters.values.all { it>=0 })
        }
        assertEquals(ends,trace.count("engine_end_call"));assertEquals(ends,trace.count("engine_end_postcheck"))
        assertEquals(ends,trace.count("tx_end_boundary_probe"));assertEquals(1,trace.count("tx_end_outer"))
        val outer=trace.spans.single { it.phase=="tx_end_outer" };assertEquals(outerOk,outer.ok)
        val engine=trace.spans.filter { it.phase=="engine_end_call" }.last()
        val post=trace.spans.filter { it.phase=="engine_end_postcheck" }.last()
        val probe=trace.spans.filter { it.phase=="tx_end_boundary_probe" }.last()
        assertTrue(outer.start<=engine.start && engine.end<=post.start && post.end<=probe.start && probe.end<=outer.end)
        assertTrue(engine.ok);engine.cpuNanos?.let { assertTrue(it>=0) }
    }
    @Test(timeout=120_000) fun normalCommitRetainsDurableDataAndNestedTailIntervals()=runBlocking<Unit> {
        val trace=G0PhaseTrace(false,waitDiagnostics=true)
        trace.run { db.withTransaction { change("Durable traced") } }
        assertEquals("Durable traced",durableName());checkTrace(trace,true)
    }
    @Test(timeout=120_000) fun rollbackRetainsOriginalDataAndException()=runBlocking<Unit> {
        val trace=G0PhaseTrace(false,waitDiagnostics=true)
        val failure=runCatching { trace.run { db.withTransaction { change("Must roll back");error("ROLLBACK_SENTINEL") } } }.exceptionOrNull()
        assertEquals("ROLLBACK_SENTINEL",failure?.message);assertEquals(metric.name,durableName());checkTrace(trace,true)
    }
    @Test(timeout=120_000) fun nestedEndDoesNotConsumeUnknownCommitFault()=runBlocking<Unit> {
        val trace=G0PhaseTrace(false,waitDiagnostics=true)
        val failure=runCatching { trace.run { db.withTransaction {
            val sql=db.openHelper.writableDatabase
            endFaults.failAfterNextEnd.set(true)
            sql.beginTransaction()
            try { change("Unknown durable");sql.setTransactionSuccessful() } finally { sql.endTransaction() }
            assertTrue(endFaults.failAfterNextEnd.get())
        } } }.exceptionOrNull()
        assertEquals("G0_COMMIT_RESULT_LOST",failure?.message);assertFalse(endFaults.failAfterNextEnd.get())
        assertEquals("Unknown durable",durableName());checkTrace(trace,false,2)
        assertFalse(trace.spans.filter { it.phase=="engine_end_postcheck" }.last().ok)
    }
    @Test fun disabledProbeAndCancelledStagePreserveResults()=runBlocking<Unit> {
        val disabled=G0PhaseTrace(false)
        disabled.run { disabled.stage("cold_validation") { assertTrue(kotlinx.coroutines.currentCoroutineContext().isActive) } }
        assertTrue(disabled.waitSamples.isEmpty())
        val traced=G0PhaseTrace(false,waitDiagnostics=true)
        val sentinel=kotlinx.coroutines.CancellationException("PROBE_CANCEL")
        val failure=runCatching { traced.run { traced.stage("cold_validation") { throw sentinel } } }.exceptionOrNull()
        assertSame(sentinel,failure);assertFalse(traced.waitSamples.single().ok)
    }

}
