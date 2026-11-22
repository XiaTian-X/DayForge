package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.local.CountTrustRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandRetainedMaintenanceTest : CountCommandRetainedFixture() {
    @Test(timeout = 150_000) fun boundedCommandCollectorRemovesOnlyUnreachableNodesAndPreservesEveryOldRow() = runBlocking<Unit> {
        seed(); var database = openCurrent(); val value = metric(database); val repo = repository(database)
        val saved = repo.executeIndependentValue(repo.beginIndependentValue(value.id, 9.0, "GC", expectedMetricUuid = value.uuid))
        repo.acknowledgeIndependentValue(saved) { true }
        val bytes = "unreachable-retained-gc".toByteArray(); val digest = HistoryBlockCodec.hash(bytes)
        database.withTransaction { CountTrustRuntime.maintenance(database) {
            database.openHelper.writableDatabase.execSQL("INSERT INTO count_command_nodes(digest,body) VALUES(?,?)", arrayOf(digest, bytes))
        } }
        database = openCurrent()
        val before = image(database); val oldRoot = root(database)
        val collector = CountCommandNodeCollector(database, tokens, sessions)
        var finished = false
        for (index in 0 until 1024) {
            val report = collector.collectSlice()
            assertTrue(report.catalogueRows <= 16384 && report.scannedRows <= 64 && report.graphBytes <= 512 * 1024 &&
                report.deletedNodes <= 32 && report.deletedBytes <= 512 * 1024)
            when (report.status) {
                CountTrustNodeCollector.Status.FINISHED -> { finished = true; break }
                CountTrustNodeCollector.Status.PROGRESS, CountTrustNodeCollector.Status.RESTARTED, CountTrustNodeCollector.Status.BUSY -> yield()
                else -> fail("Retained command collection did not finish: $report")
            }
        }
        assertTrue(finished)
        val after = image(database)
        val beforeTables = before.getValue("tables").jsonObject; val afterTables = after.getValue("tables").jsonObject
        assertEquals(JsonObject(before - "tables"), JsonObject(after - "tables"))
        assertEquals(JsonObject(beforeTables - "count_command_nodes"), JsonObject(afterTables - "count_command_nodes"))
        assertTrue(CountCommandNativeRows.sameRoot(oldRoot, root(database))); assertLegacy(database)
        database.openHelper.writableDatabase.query("SELECT 1 FROM count_command_nodes WHERE digest=?", arrayOf(digest)).use { assertFalse(it.moveToFirst()) }
        assertTrue(repository(database).resolveIndependentValue(requireNotNull(saved.reference)) is IndependentMetricResolution.Saved)
    }
}
