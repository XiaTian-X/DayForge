package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandMigrationFixture
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.service.AccountIconController
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real KSP19 migration and original legal legacy repository. The unused icon service is
 * strict mocked; storage, keys, tokens, session, legacy writer and actual END are real. */
@RunWith(AndroidJUnit4::class)
class IndependentMetricUnenrolledTest : CountCommandMigrationFixture() {
    private fun repository(database: HabitDatabase): MetricRepository {
        val editor = NextObjectEditor(database, tokens, sessions, mockk<AccountIconController>())
        return MetricRepository(database, database.metricDao(), database.metricLogDao(), database.habitDao(),
            database.habitMetricLinkDao(), nextObjectEditor = editor)
    }
    private suspend fun seeded(): Pair<HabitDatabase, MetricEntity> {
        source(19) { old ->
            old.execSQL("DELETE FROM next_transmissions")
            old.execSQL("DELETE FROM next_rejections")
            old.execSQL("INSERT INTO count_command_results VALUES('legacy-native-id','old-input','old-fact',NULL,'retained old result','committed')")
        }
        val database = openCurrent()
        val row = MetricEntity(name = "Native legacy", unit = "kg", iconResId = 0,
            colorHex = "#123456", uuid = "legacy-metric")
        val stored = row.copy(id = database.metricDao().insert(row))
        assertEquals("LEGACY_UNENROLLED", CountCommandNativeRows(database.openHelper.writableDatabase) {}.root().mode)
        return database to stored
    }
    private fun count(database: HabitDatabase, table: String): Long {
        require(table in setOf("metric_logs", "sync_outbox", "count_command_intents", "count_command_status", "count_command_results"))
        return database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM " + table).use {
            check(it.moveToFirst()); it.getLong(0)
        }
    }

    @Test(timeout = 150_000) fun authenticatedUnenrolledRootKeepsOriginalLegalWriteWithoutInventingEnrolmentOrRecovery() = runBlocking<Unit> {
        val (database, metric) = seeded()
        val repo = repository(database)
        val old = repo.recordValue(metric.id, 7.5, "legacy", 1_800_000_000_000L, expectedMetricUuid = metric.uuid)
        val original = requireNotNull(database.metricLogDao().getById(old))
        val root = database.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(database.openHelper.writableDatabase).native() }
        val attempt = repo.beginIndependentValue(metric.id, 7.5, "legacy", 1_800_000_000_000L, expectedMetricUuid = metric.uuid)
        assertNotNull(attempt.reference)
        val saved = repo.executeIndependentValue(attempt)
        assertEquals(attempt.reference, saved.reference)
        val actual = requireNotNull(database.metricLogDao().getById(saved.factId))
        assertEquals(original.metricId, actual.metricId); assertEquals(original.date, actual.date)
        assertEquals(original.value, actual.value, 0.0); assertEquals(original.unit, actual.unit)
        assertNotEquals(original.uuid, actual.uuid)
        repo.acknowledgeIndependentValue(saved) { true } // T2 consumes only the new command, never the retained old row
        assertEquals(2L, count(database, "metric_logs"))
        assertEquals(1L, count(database, "count_command_intents")); assertEquals(1L, count(database, "count_command_status"))
        assertEquals(2L, count(database, "count_command_results"))
        val enrolled = database.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(database.openHelper.writableDatabase).native() }
        assertEquals("ACCOUNT_BOUND_RETAINED", enrolled.mode); assertEquals(3, enrolled.format)
        assertEquals(root.storageId, enrolled.storageId); assertEquals(root.datasetId, enrolled.datasetId)
        assertEquals(root.legacyCount, enrolled.legacyCount); assertEquals(root.legacyHash, enrolled.legacyHash)
        val page = CountCommandStore(database, tokens, sessions).discover(32)
        assertEquals(1L, page.retainedCount); assertTrue(page.complete); assertTrue(page.commands.isEmpty())
        val reopened = openCurrent()
        assertEquals(2L, count(reopened, "metric_logs")); assertEquals(2L, count(reopened, "count_command_results"))
        assertTrue(CountCommandNativeRows.sameRoot(enrolled,
            reopened.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(reopened.openHelper.writableDatabase).native() }))
        val resolved = repository(reopened).resolveIndependentValue(requireNotNull(saved.reference))
        assertTrue(resolved is IndependentMetricResolution.Saved)
        assertEquals(saved.factUuid, (resolved as IndependentMetricResolution.Saved).value.factUuid)
        assertEquals(2L, count(reopened, "metric_logs"))
        reopened.openHelper.writableDatabase.query("SELECT commandId,inputHash,factUuid,requestId,resultJson,state FROM count_command_results WHERE commandId='legacy-native-id'").use {
            assertTrue(it.moveToFirst())
            assertEquals("legacy-native-id", it.getString(0)); assertEquals("old-input", it.getString(1))
            assertEquals("old-fact", it.getString(2)); assertTrue(it.isNull(3))
            assertEquals("retained old result", it.getString(4)); assertEquals("committed", it.getString(5)); assertFalse(it.moveToNext())
        }
    }

    @Test(timeout = 150_000) fun rootUnenrolledChoiceCannotSurviveAnAuthorizedDatasetReplacement() = runBlocking<Unit> {
        val (database, metric) = seeded()
        val repo = repository(database)
        val attempt = repo.beginIndependentValue(metric.id, 7.5, "legacy", expectedMetricUuid = metric.uuid)
        assertNotNull(attempt.reference)
        val previous = CountCommandNativeRows(database.openHelper.writableDatabase) {}.root()
        database.clearAllData()
        val replaced = CountCommandNativeRows(database.openHelper.writableDatabase) {}.root()
        assertEquals("EMPTY", replaced.mode); assertNotEquals(previous.datasetId, replaced.datasetId)
        assertNotNull(runCatching { repo.executeIndependentValue(attempt) }.exceptionOrNull())
        assertEquals(0L, count(database, "metric_logs")); assertEquals(0L, count(database, "sync_outbox"))
        assertEquals(0L, count(database, "count_command_intents")); assertEquals(0L, count(database, "count_command_results"))
        assertTrue(CountCommandNativeRows.sameRoot(replaced, CountCommandNativeRows(database.openHelper.writableDatabase) {}.root()))
    }
}
