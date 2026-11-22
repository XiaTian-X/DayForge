package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.CountTrustRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The actual formal bootstrap/current COUNT source remains usable after optional maintenance. */
@RunWith(AndroidJUnit4::class)
class CountNodeGcFormalTest : CountTrustFormalProfileFixture() {
    @Test fun realFormalRootSurvivesGcAndItsOriginalTwoMetricProducerStillCommits() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs()
        val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
        val body = "formal-unreachable-node".toByteArray(); val digest = CountTrustIndex.hash(body)
        db.withTransaction { runtime.maintenance(db) {
            db.openHelper.writableDatabase.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf<Any>(digest, body))
        } }
        val assertions = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = assertions.capture(); val gc = CountTrustNodeCollector(db, tokens, sessions)
        var finished = false; var deleted = 0
        for (step in 0 until 10000) {
            val report = gc.collectSlice(); deleted += report.deletedNodes
            when (report.status) {
                CountTrustNodeCollector.Status.FINISHED -> { finished = true; break }
                CountTrustNodeCollector.Status.PROGRESS, CountTrustNodeCollector.Status.RESTARTED -> Unit
                CountTrustNodeCollector.Status.BUSY -> yield()
                CountTrustNodeCollector.Status.DEFERRED -> {
                    assertEquals("Only transient readers may defer this formal fixture", "READER_OR_OWNER_PRESENT", report.reason)
                    yield()
                }
                else -> fail("Formal GC must finish supported work: $report")
            }
        }
        assertTrue(finished); assertTrue(deleted > 0)
        val after = assertions.capture()
        assertEquals(before - "count_trust_nodes", after - "count_trust_nodes")
        assertFalse(after.getValue("count_trust_nodes").any { it.getValue("digest").value == digest })
        db.withTransaction { runtime.maintenance(db) {
            val sql = db.openHelper.writableDatabase; val header = requireNotNull(CountTrustSeal.read(sql))
            assertEquals(inputs.header, header)
            CountTrustIndex(sql, header.binding, header.root) {}.verifyContents()
        } }
        val writer = producer()
        val ids = writer.writeRounds(writer.captureRounds()) {
            val (ticket, inserted) = registerAndInsertMetricScope(inputs)
            CountTrustKernel.bindMetricBatch(db, ticket, inserted)
            inserted
        }
        assertMetricScopeCommitted(inputs, ids)
    }
}
