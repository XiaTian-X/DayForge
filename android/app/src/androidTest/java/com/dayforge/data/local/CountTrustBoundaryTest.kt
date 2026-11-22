package com.dayforge.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.repository.CountCommandNativeRows
import com.dayforge.data.repository.NextRequestSql
import com.dayforge.data.repository.NextReplicaStorageProof
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLite connection boundary; these tests grant no business or account authority. */
@RunWith(AndroidJUnit4::class)
class CountTrustBoundaryTest {
    private val fault = EndFaultFactory()
    @get:Rule val database = PhysicalDatabaseRule().apply {
        isolatedBuilder = { context ->
            val factory = CountTrustOpenHelperFactory(context, fault)
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .openHelperFactory(factory).addCallback(SyncSchemaCallback).build()
                .also { CountTrustRuntime.attach(it, factory) }
        }
    }
    private val db get() = database.database
    private val sql get() = db.openHelper.writableDatabase
    private val runtime get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private fun scalar(query: String): Long = sql.query(query).use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private fun denied(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected controlled-boundary rejection, got $error",
            error is IllegalStateException && error.message.orEmpty().startsWith("COUNT_TRUST_"))
    }
    private suspend fun cleanMarker() = db.withTransaction {
        runtime.maintenance(db) { sql.execSQL("UPDATE count_trust_control SET dirty=0 WHERE id=1") }
    }

    @Test fun businessWritesRemainAvailableAndDirtyEvenWhenOutboxSuppressed() = runBlocking<Unit> {
        sql
        cleanMarker()
        val generation = scalar("SELECT generation FROM count_trust_control")
        db.withTransaction { sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
        assertEquals(generation + 1L, scalar("SELECT generation FROM count_trust_control"))
        assertEquals(1L, scalar("SELECT dirty FROM count_trust_control"))
        cleanMarker()
        db.withTransaction {
            sql.execSQL("INSERT INTO next_config_imports VALUES('import','account','server','epoch','device','archive','identities','replacement','prepared',NULL)")
            sql.execSQL("INSERT INTO next_config_import_payloads VALUES('import','network',0,X'00017FFF')")
        }
        assertEquals(1L, scalar("SELECT dirty FROM count_trust_control"))
        assertEquals(0L, scalar("SELECT count(*) FROM sync_outbox"))
        assertFalse(runtime.isRetired)
    }

    @Test fun everySqlEntrypointRejectsProofWritesSystemWritesAndConnectionControls() = runBlocking<Unit> {
        for (handle in listOf(sql, db.openHelper.readableDatabase)) {
            for (text in listOf(
                "UPDATE count_trust_control SET dirty=0", "DELETE FROM count_trust_nodes",
                "INSERT INTO count_command_results VALUES('c','i','f',NULL,'r','committed')",
                "UPDATE /* quoted */ [count_trust_root] SET revision=0",
                "DELETE FROM 'count_trust_routes'", "UPDATE main.room_master_table SET identity_hash='x'",
                "DELETE FROM sqlite_sequence", "UPDATE android_metadata SET locale='x'",
                "DROP TRIGGER `${CountTrustSchema.dirtyTriggers.keys.first()}`",
                "CREATE TEMP TABLE count_trust_fake(value)", "PRAGMA writable_schema=ON",
                "PRAGMA recursive_triggers(OFF)", "PRAGMA main.schema_version=1",
                "SELECT * FROM pragma_writable_schema(1)",
                "ATTACH DATABASE ':memory:' AS escape", "SAVEPOINT escape", "VACUUM",
                "SELECT 1; DELETE FROM count_trust_root"
            )) {
                denied { handle.execSQL(text) }
                denied { handle.query(text).close() }
                handle.compileStatement(text).use { statement ->
                    denied { statement.execute() }; denied { statement.executeUpdateDelete() }
                    denied { statement.executeInsert() }; denied { statement.simpleQueryForLong() }
                    denied { statement.simpleQueryForString() }
                }
            }
            denied { handle.insert("count_trust_nodes", 0, ContentValues().apply { put("digest", "bad"); put("body", byteArrayOf(1)) }) }
            denied { handle.update("count_trust_control", 0, ContentValues().apply { put("dirty", 0) }, null, null) }
            denied { handle.delete("count_command_results", null, null) }
            denied { handle.execPerConnectionSQL("PRAGMA recursive_triggers=OFF", null) }
            denied { handle.version = 1 }; denied { handle.pageSize = 8192 }
            denied { handle.setForeignKeyConstraintsEnabled(false) }
            denied { handle.disableWriteAheadLogging() }; denied { handle.enableWriteAheadLogging() }
            denied { handle.yieldIfContendedSafely() }
        }
        assertFalse(runtime.isRetired)
        assertEquals(0L, scalar("SELECT count(*) FROM count_trust_nodes"))
    }

    @Test fun explainCannotExecutePrepareTimePragmasButReadOnlyPlansRemainAvailable() = runBlocking<Unit> {
        sql
        // Keep every probe on this transaction's native primary connection: PRAGMA state is
        // connection-local, so checking an arbitrary WAL reader would miss the regression.
        db.withTransaction {
            val original = fault.connectionPragmas()
            for (prefix in listOf("EXPLAIN ", "EXPLAIN QUERY PLAN ", "EXPLAIN /* prefix */ ",
                "EXPLAIN QUERY /* prefix */ PLAN ")) {
                for (body in listOf("PRAGMA writable_schema=ON", "PRAGMA recursive_triggers=OFF",
                    "PRAGMA foreign_keys=OFF", "PRAGMA synchronous=OFF", "PRAGMA main.schema_version=1",
                    "PRAGMA user_version=1", "UPDATE count_trust_control SET dirty=0",
                    "CREATE TABLE explain_boundary_escape(value)", "DROP TABLE metrics",
                    "WITH input(x) AS (SELECT 1) UPDATE sync_control SET suppressOutbox=1",
                    "WITH input(x) AS (SELECT 1) DELETE FROM count_trust_root",
                    "WITH input(x) AS (SELECT 1) PRAGMA recursive_triggers=OFF")) {
                    val text = prefix + body
                    denied { sql.execSQL(text) }
                    denied { sql.query(text).close() }
                    sql.compileStatement(text).use { statement ->
                        assertEquals("compile must not prepare dangerous SQL", original, fault.connectionPragmas())
                        denied { statement.execute() }; denied { statement.executeUpdateDelete() }
                        denied { statement.executeInsert() }; denied { statement.simpleQueryForLong() }
                        denied { statement.simpleQueryForString() }
                    }
                    assertEquals("EXPLAIN must leave native PRAGMA state intact", original, fault.connectionPragmas())
                }
            }
            for (text in listOf("EXPLAIN SELECT 1", "EXPLAIN QUERY PLAN SELECT 1",
                "EXPLAIN WITH input(x) AS (SELECT 1) SELECT x FROM input",
                "EXPLAIN QUERY PLAN WITH input(x) AS (SELECT 1) SELECT x FROM input")) {
                sql.query(text).use { assertTrue("A read-only plan must remain available", it.moveToFirst()) }
                sql.compileStatement(text).use { assertTrue(it.simpleQueryForLong() >= 0L) }
            }
            assertEquals(original, fault.connectionPragmas())
            assertEquals(0L, scalar("SELECT suppressOutbox FROM sync_control"))
            assertEquals(0L, scalar("SELECT count(*) FROM sqlite_master WHERE name='explain_boundary_escape'"))
            assertFalse(runtime.isRetired)
        }
    }

    @Test fun maintenanceCannotEscapeThroughChildJobsStatementsCursorsOrQueryBinders() = runBlocking<Unit> {
        sql
        lateinit var escapedStatement: SupportSQLiteStatement
        lateinit var escapedCursor: Cursor
        db.withTransaction {
            runtime.maintenance(db) {
                sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES('owned',X'0102')")
                escapedStatement = sql.compileStatement("DELETE FROM count_trust_nodes")
                escapedCursor = sql.query("SELECT body FROM count_trust_nodes")
                assertFalse(escapedCursor is SQLiteCursor)
                assertFalse(escapedCursor is android.database.CursorWrapper)
                assertFalse(escapedCursor is android.database.CrossProcessCursor)
                assertTrue(escapedCursor.moveToFirst())
                assertArrayEquals(byteArrayOf(1, 2), escapedCursor.getBlob(0))
                withContext(Dispatchers.Default) { denied { sql.execSQL("DELETE FROM count_trust_nodes") } }
                denied { sql.endTransaction() }; denied { sql.setTransactionSuccessful() }; denied { sql.beginTransaction() }
            }
            denied { escapedStatement.executeUpdateDelete() }
            denied { escapedCursor.getBlob(0) }
            runtime.maintenance(db) { denied { escapedStatement.executeUpdateDelete() }; denied { escapedCursor.moveToFirst() } }
        }
        escapedStatement.close(); escapedCursor.close()
        var accesses = 0
        var binder: SupportSQLiteProgram? = null
        val changing = object : SupportSQLiteQuery {
            override val sql get() = if (accesses++ == 0) "SELECT ?" else "DELETE FROM count_trust_nodes"
            override val argCount get() = 1
            override fun bindTo(statement: SupportSQLiteProgram) { binder = statement; statement.bindLong(1, 7L) }
        }
        sql.query(changing).use { assertTrue(it.moveToFirst()); assertEquals(7L, it.getLong(0)) }
        assertEquals(1, accesses); assertFalse(binder is SupportSQLiteStatement)
        binder!!.bindLong(1, 99) // Mutates only a detached bindings buffer.
        assertEquals(1L, scalar("SELECT count(*) FROM count_trust_nodes"))
    }

    @Test fun fullTypedJournalCapturesActualOldNewReplaceAndSuppressedWritesAcrossCapabilities() = runBlocking<Unit> {
        sql
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val metricId = db.metricDao().insert(MetricEntity(name="metric", unit="u", iconResId=0,
                colorHex="#000000", targetValue=1.25, createdAt=1, updatedAt=1))
            sql.execSQL("INSERT INTO next_config_imports VALUES('import','account','server','epoch','device','archive','identities','replacement','prepared',NULL)")
            lateinit var boundary: CountTrustJournal.Boundary
            runtime.maintenance(db) { boundary = runtime.captureChangeBoundary() }
            sql.execSQL("UPDATE metrics SET targetValue=2.5,description='new' WHERE id=?", arrayOf(metricId))
            sql.execSQL("INSERT INTO next_config_import_payloads VALUES('import','network',0,?)", arrayOf(byteArrayOf(0, 1, -1)))
            sql.execSQL("INSERT OR REPLACE INTO next_config_import_payloads VALUES('import','network',0,?)", arrayOf(byteArrayOf(2, 3)))
            runtime.maintenance(db) {
                val changes = runtime.pendingChanges(boundary)
                val metric = changes.single { it.table == "metrics" }
                val index = CountTrustJournal.COLUMNS.getValue("metrics").indexOf("targetValue")
                assertEquals(CountTrustJournal.Cell(Cursor.FIELD_TYPE_FLOAT, java.lang.Double.doubleToRawLongBits(1.25)), metric.old!![index])
                assertEquals(CountTrustJournal.Cell(Cursor.FIELD_TYPE_FLOAT, java.lang.Double.doubleToRawLongBits(2.5)), metric.new!![index])
                val blobs = changes.filter { it.table == "next_config_import_payloads" }
                assertEquals(listOf("INSERT", "DELETE", "INSERT"), blobs.map { it.operation })
                val oldBytes = listOf<Byte>(0, 1, -1)
                assertEquals(CountTrustJournal.Cell(Cursor.FIELD_TYPE_BLOB, oldBytes), blobs[1].old!!.last())
                assertEquals(CountTrustJournal.Cell(Cursor.FIELD_TYPE_BLOB, listOf<Byte>(2, 3)), blobs[2].new!!.last())
                assertTrue(runCatching { (blobs[1].old!!.last().value as MutableList<*>).clear() }.exceptionOrNull() is UnsupportedOperationException)
                assertTrue(runCatching { (changes as MutableList<*>).clear() }.exceptionOrNull() is UnsupportedOperationException)
                assertEquals(36, CountTrustJournal.COLUMNS.size)
                assertEquals(108L, scalar("SELECT count(*) FROM sqlite_temp_master WHERE type='trigger' AND name LIKE 'count_trust_journal_%'"))
            }
        }
        assertFalse(runtime.isRetired)
    }

    @Test fun tempJournalSequenceNeverShadowsMainHistoricalWatermarksOrRecoveryRows() = runBlocking<Unit> {
        sql
        var previousJournalSequence = 0L
        var committedQueueWatermark = 0L
        db.withTransaction {
            db.metricDao().insert(MetricEntity(name="first",unit="u",iconResId=0,colorHex="#000000"))
            val oldQueue = db.syncOutboxDao().getAll().single()
            db.syncOutboxDao().deleteById(oldQueue.id)
            val historical = NextRequestSql.watermark(sql, "sync_outbox")
            assertTrue(historical > 0L); assertEquals(oldQueue.id, historical)
            assertEquals(0L, scalar("SELECT count(*) FROM sync_outbox"))
            lateinit var boundary: CountTrustJournal.Boundary
            runtime.maintenance(db) { boundary = runtime.captureChangeBoundary() }
            repeat(32) { sql.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1") }
            val temp = scalar("SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events'")
            assertTrue("Regression requires a real, larger TEMP sequence", temp > historical)
            assertEquals(historical, NextRequestSql.watermark(sql, "sync_outbox"))
            assertEquals(historical, scalar("SELECT seq FROM main.sqlite_sequence WHERE name='sync_outbox'"))
            assertEquals(0L, NextRequestSql.watermark(sql, "timer_command_outbox"))
            assertTrue(NextRequestSql.sources(sql, "sync_outbox").isEmpty())
            val mainRows = sql.query("SELECT name,seq FROM main.sqlite_sequence ORDER BY name").use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) }
            }
            val recovered = NextReplicaStorageProof(db).readInTransaction().contents.getValue("tables").jsonObject
                .getValue("sqlite_sequence").jsonArray.associate { item ->
                    val row = item.jsonObject
                    row.getValue("name").jsonObject.getValue("value").jsonPrimitive.content to
                        row.getValue("seq").jsonObject.getValue("value").jsonPrimitive.long
                }
            assertEquals("Recovery must bind main metadata, not the TEMP journal", mainRows, recovered)
            assertFalse(recovered.containsKey("count_trust_journal_events"))
            db.metricDao().insert(MetricEntity(name="second",unit="u",iconResId=0,colorHex="#000000"))
            val newQueue = db.syncOutboxDao().getAll().single()
            assertTrue(newQueue.id > historical)
            committedQueueWatermark = NextRequestSql.watermark(sql, "sync_outbox")
            assertEquals(newQueue.id, committedQueueWatermark)
            assertEquals(setOf(newQueue.id), NextRequestSql.sources(sql, "sync_outbox").keys)
            runtime.maintenance(db) {
                val changes = runtime.pendingChanges(boundary)
                assertTrue(changes.any { it.table == "sync_outbox" && it.operation == "INSERT" })
                previousJournalSequence = changes.last().sequence
                assertTrue(previousJournalSequence > temp)
            }
        }
        db.withTransaction {
            runtime.maintenance(db) {
                val boundary = runtime.captureChangeBoundary()
                sql.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
                assertTrue(runtime.pendingChanges(boundary).single().sequence > previousJournalSequence)
            }
            assertEquals(committedQueueWatermark, NextRequestSql.watermark(sql, "sync_outbox"))
        }
        assertFalse(runtime.isRetired)
    }

    @Test fun firstJournalInstallRollsBackThenReinstallsWithoutReusingOldBoundary() = runBlocking<Unit> {
        sql
        lateinit var abandoned: CountTrustJournal.Boundary
        val failed = runCatching {
            db.withTransaction {
                runtime.maintenance(db) { abandoned = runtime.captureChangeBoundary() }
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                error("COUNT_BOUNDARY_TEST_ABORT")
            }
        }.exceptionOrNull()
        assertEquals("COUNT_BOUNDARY_TEST_ABORT", failed?.message)
        assertFalse(runtime.isRetired)
        assertEquals(0L, scalar("SELECT suppressOutbox FROM sync_control"))
        db.withTransaction {
            runtime.maintenance(db) {
                val fresh = runtime.captureChangeBoundary()
                assertTrue(runtime.pendingChanges(fresh).isEmpty())
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                assertEquals("sync_control", runtime.pendingChanges(fresh).single().table)
            }
        }
        assertFalse(runtime.isRetired)
        val expired = runCatching {
            db.withTransaction { runtime.maintenance(db) { runtime.pendingChanges(abandoned) } }
        }.exceptionOrNull()
        assertEquals("COUNT_TRUST_BOUNDARY_EXPIRED", expired?.message)
        assertTrue(runtime.isRetired) // Attempted old capability never authorizes the new journal.
    }

    @Test fun boundaryFromPreviousTransactionCannotAuthorizeNewEvidence() = runBlocking<Unit> {
        sql
        lateinit var boundary: CountTrustJournal.Boundary
        db.withTransaction { runtime.maintenance(db) { boundary = runtime.captureChangeBoundary() } }
        val failure = runCatching { db.withTransaction { runtime.maintenance(db) { runtime.pendingChanges(boundary) } } }.exceptionOrNull()
        assertEquals("COUNT_TRUST_BOUNDARY_EXPIRED", failure?.message)
        assertTrue(runtime.isRetired)
    }

    @Test fun unknownBusinessDdlDurablyInvalidatesBeforeItRunsAndRetiresOldHandles() = runBlocking<Unit> {
        sql; cleanMarker()
        val before = scalar("SELECT generation FROM count_trust_control")
        val statement = sql.compileStatement("SELECT 1")
        val cursor = sql.query("SELECT 1").also { assertTrue(it.moveToFirst()) }
        sql.execSQL("CREATE INDEX count_boundary_probe ON metrics(name)")
        assertTrue(runtime.isRetired)
        assertEquals(1L, scalar("SELECT dirty FROM count_trust_control"))
        assertEquals(before + 1, scalar("SELECT generation FROM count_trust_control"))
        denied { statement.simpleQueryForLong() }; denied { cursor.getLong(0) }
        statement.close(); cursor.close()
        assertEquals(1L, scalar("SELECT count(*) FROM sqlite_master WHERE name='count_boundary_probe'"))
        // Even a failing schema edit cannot leave the old fast qualification usable.
        val generation = scalar("SELECT generation FROM count_trust_control")
        assertNotNull(runCatching { sql.execSQL("CREATE INDEX count_boundary_bad ON missing_table(value)") }.exceptionOrNull())
        assertEquals(generation + 1L, scalar("SELECT generation FROM count_trust_control"))
    }

    @Test fun recoveryRestoresEveryExactGuardFromSqliteStoredSqlWithoutRetiringHandles() = runBlocking<Unit> {
        sql; cleanMarker()
        val epoch = runtime.generation
        val originalGeneration = scalar("SELECT generation FROM count_trust_control")
        val expected = NextReplicaFenceSql.businessAndLocalDefinitions().keys
        val before = sql.query("SELECT name,sql FROM main.sqlite_master WHERE type='trigger' ORDER BY name").use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        val stored = before.filterKeys { it in expected }
        assertEquals(expected, stored.keys)
        assertTrue("The regression must use SQLite's stored CREATE spelling",
            stored.values.all { !it.startsWith("CREATE TRIGGER IF NOT EXISTS", ignoreCase = true) })
        val statement = sql.compileStatement("SELECT 7")
        try {
            db.withTransaction { runtime.maintenance(db) {
                stored.keys.forEach { sql.execSQL("DROP TRIGGER `$it`") }
                // This is the actual replacement path, not the installation generator.
                stored.values.forEach(sql::execSQL)
                assertEquals(7L, statement.simpleQueryForLong())
            } }
            val after = sql.query("SELECT name,sql FROM main.sqlite_master WHERE type='trigger' ORDER BY name").use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }
            assertEquals("Stored guards and all unrelated triggers must remain byte-identical", before, after)
            assertEquals(originalGeneration + stored.size * 2L, scalar("SELECT generation FROM count_trust_control"))
            assertEquals(1L, scalar("SELECT dirty FROM count_trust_control"))
            assertTrue(runtime.isRetired); assertEquals(epoch, runtime.generation)
            assertEquals(7L, statement.simpleQueryForLong())
        } finally { statement.close() }
    }

    @Test fun recoveryGuardWhitelistRejectsChangedPredicateTargetAndBodyEvenWithMaintenance() = runBlocking<Unit> {
        sql
        val name = "replica_fence_count_trust_root_update"
        val stored = sql.query("SELECT sql FROM main.sqlite_master WHERE type='trigger' AND name=?", arrayOf(name)).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        val before = scalar("SELECT generation FROM count_trust_control")
        // A known restoration statement still requires its own transaction-bound capability.
        denied { sql.execSQL(stored) }
        db.withTransaction { runtime.maintenance(db) {
            for (changed in listOf(
                stored.replace("WHEN EXISTS", "WHEN NOT EXISTS"),
                stored.replace("WHEN EXISTS", "\"WHEN EXISTS\""),
                stored.replace("'closed'", "'CLOSED'"),
                stored.replace("'closed'", "closed"),
                stored.replace("'closed'", "\"'closed'\""),
                stored.replace("'closed'", "`'closed'`"),
                stored.replace("'closed'", "['closed']"),
                stored.replace("id IS NOT 1", "id IS NOT \"1\""),
                stored.replace("`count_trust_root`", "`count_trust_nodes`"),
                stored.replace(name, "${name}_unexpected"),
                stored.replace("RAISE(ABORT,", "RAISE(IGNORE,"),
                stored.replace("BEGIN SELECT", "BEGIN UPDATE count_trust_control SET dirty=0; SELECT"),
                "$stored; DROP TABLE count_trust_root"
            )) {
                assertNotEquals("Each negative must change actual stored SQL", stored, changed)
                denied { sql.execSQL(changed) }
                denied { sql.query(changed).close() }
                sql.compileStatement(changed).use { statement ->
                    denied { statement.execute() }; denied { statement.executeUpdateDelete() }
                    denied { statement.executeInsert() }; denied { statement.simpleQueryForLong() }
                    denied { statement.simpleQueryForString() }
                }
            }
        } }
        assertEquals(before, scalar("SELECT generation FROM count_trust_control"))
        assertFalse(runtime.isRetired)
        sql.query("SELECT sql FROM main.sqlite_master WHERE type='trigger' AND name=?", arrayOf(name)).use {
            assertTrue(it.moveToFirst()); assertEquals(stored, it.getString(0))
        }
    }

    @Test fun recoveryRetirementStopsQualificationButPreservesDaoAndMaintenanceHandlesUntilReopen() = runBlocking<Unit> {
        sql
        val originalPrimary = CountCommandNativeRows(sql) {}.root()
        assertEquals("EMPTY", originalPrimary.mode)
        assertEquals(0L, scalar("SELECT count(*) FROM count_command_results"))
        val original = runtime
        val epoch = original.generation
        denied { original.retireFastPathForRecovery() }
        val statement = sql.compileStatement("SELECT 7")
        val cursor = sql.query("SELECT 9").also { assertTrue(it.moveToFirst()) }
        db.withTransaction {
            denied { original.retireFastPathForRecovery() }
            original.maintenance(db) {
                original.captureChangeBoundary()
                withContext(Dispatchers.Default) { denied { original.retireFastPathForRecovery() } }
                assertFalse(original.isRetired)
                val ownedStatement = sql.compileStatement("SELECT 11")
                val ownedCursor = sql.query("SELECT 13").also { assertTrue(it.moveToFirst()) }
                try {
                    original.retireFastPathForRecovery()
                    assertTrue(original.isRetired); assertEquals(epoch, original.generation)
                    assertEquals(11L, ownedStatement.simpleQueryForLong()); assertEquals(13L, ownedCursor.getLong(0))
                    denied { original.requireUsable() }; denied { original.captureChangeBoundary() }
                    // A COUNT cache node witnesses maintenance access, not a primary command.
                    sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES('recovery',X'0102')")
                    sql.query("SELECT body FROM count_trust_nodes WHERE digest='recovery'").use {
                        assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_BLOB, it.getType(0))
                        assertArrayEquals(byteArrayOf(1, 2), it.getBlob(0)); assertFalse(it.moveToNext())
                    }
                } finally { ownedStatement.close(); ownedCursor.close() }
            }
            assertEquals(7L, statement.simpleQueryForLong()); assertEquals(9L, cursor.getLong(0))
        }
        assertEquals(7L, statement.simpleQueryForLong()); assertEquals(9L, cursor.getLong(0))
        statement.close(); cursor.close()
        var previousSequence = 0L
        val metricId = db.withTransaction {
            val id = db.metricDao().insert(MetricEntity(name="after retirement", unit="u", iconResId=0, colorHex="#000000"))
            assertTrue(scalar("SELECT count(*) FROM temp.count_trust_journal_events") > 0L)
            previousSequence = scalar("SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events'")
            id
        }
        assertEquals("after retirement", requireNotNull(db.metricDao().getMetricById(metricId)).name)
        var committedSequence = 0L
        db.withTransaction {
            original.maintenance(db) {
                assertEquals(0L, scalar("SELECT count(*) FROM temp.count_trust_journal_events"))
                assertEquals(0L, scalar("SELECT count(*) FROM temp.count_trust_journal_cells"))
                assertEquals(previousSequence, scalar("SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events'"))
                sql.execSQL("UPDATE count_trust_nodes SET body=X'0304' WHERE digest='recovery'")
                // Real replica replacement uses these exact approved trigger definitions.
                // Its main schema_version change must not disable retired journal cleanup.
                val guard = NextReplicaFenceSql.businessAndLocalDefinitions().entries.first()
                sql.execSQL("DROP TRIGGER IF EXISTS `${guard.key}`")
                sql.execSQL(guard.value)
                sql.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
                committedSequence = scalar("SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events'")
                assertTrue(committedSequence > previousSequence)
                assertEquals(1L, scalar("SELECT count(*) FROM temp.count_trust_journal_events"))
                original.retireFastPathForRecovery() // Idempotent; restore may enter maintenance again.
                denied { original.captureChangeBoundary() }
            }
        }
        val failed = runCatching { db.withTransaction {
            assertEquals(0L, scalar("SELECT count(*) FROM temp.count_trust_journal_events"))
            assertEquals(0L, scalar("SELECT count(*) FROM temp.count_trust_journal_cells"))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
            assertTrue(scalar("SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events'") > committedSequence)
            error("COUNT_BOUNDARY_TEST_RETIRED_ROLLBACK")
        } }.exceptionOrNull()
        assertEquals("COUNT_BOUNDARY_TEST_RETIRED_ROLLBACK", failed?.message)
        db.withTransaction {
            assertEquals(0L, scalar("SELECT count(*) FROM temp.count_trust_journal_events"))
            assertEquals(0L, scalar("SELECT count(*) FROM temp.count_trust_journal_cells"))
            assertEquals("The failed outer transaction must restore the journal sequence", committedSequence,
                scalar("SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events'"))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
            assertTrue(scalar("SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events'") > committedSequence)
        }
        assertTrue(original.isRetired); assertEquals(epoch, original.generation)
        assertEquals(0L, scalar("SELECT count(*) FROM count_command_results"))
        assertTrue(CountCommandNativeRows.sameRoot(originalPrimary, CountCommandNativeRows(sql) {}.root()))
        database.reopen().openHelper.writableDatabase
        assertTrue(original.isRetired); assertFalse(runtime.isRetired)
        runtime.requireUsable()
        db.withTransaction { runtime.maintenance(db) { assertTrue(runtime.pendingChanges(runtime.captureChangeBoundary()).isEmpty()) } }
        assertEquals("after retirement", requireNotNull(db.metricDao().getMetricById(metricId)).name)
        sql.query("SELECT body FROM count_trust_nodes WHERE digest='recovery'").use {
            assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_BLOB, it.getType(0))
            assertArrayEquals(byteArrayOf(3, 4), it.getBlob(0)); assertFalse(it.moveToNext())
        }
        val reopenedPrimary = CountCommandNativeRows(sql) {}.root()
        assertEquals("EMPTY", reopenedPrimary.mode)
        assertTrue(CountCommandNativeRows.sameRoot(originalPrimary, reopenedPrimary))
        assertEquals(0L, scalar("SELECT count(*) FROM count_command_results"))
    }

    @Test fun externalConnectionChangeRetiresJournalInsteadOfAutoRecertifying() = runBlocking<Unit> {
        sql
        db.withTransaction { runtime.maintenance(db) { runtime.captureChangeBoundary() } }
        val context = ApplicationProvider.getApplicationContext<Context>()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
        }
        val failure = runCatching { db.withTransaction { runtime.maintenance(db) { runtime.captureChangeBoundary() } } }.exceptionOrNull()
        assertNotNull(failure); assertTrue(runtime.isRetired)
        assertEquals(1L, scalar("SELECT dirty FROM count_trust_control"))
    }

    @Test fun actualEndSuccessBindsCandidateAndUnknownEndNeverReturnsReceipt() = runBlocking<Unit> {
        sql
        val candidate = Any()
        val completed = runtime.transaction(db) { sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1"); candidate }
        assertSame(candidate, completed.value); completed.end.requireCandidate(candidate)
        denied { completed.end.requireCandidate(Any()) }
        val failure = runCatching {
            runtime.transaction(db) {
                sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                fault.afterEnd.set(true)
                Any()
            }
        }.exceptionOrNull()
        assertEquals("COUNT_BOUNDARY_TEST_UNKNOWN_END", failure?.message)
        assertTrue(runtime.isRetired)
        denied { completed.end.requireCandidate(candidate) }
        assertEquals(0L, scalar("SELECT suppressOutbox FROM sync_control")) // actual COMMIT happened
    }

    @Test fun roomObserversUseOnlyExactTempMaintenanceAndContinueAfterJournalInstallation() = runBlocking<Unit> {
        sql
        db.withTransaction { runtime.maintenance(db) { runtime.captureChangeBoundary() } }
        val metricIndex = CountTrustSchema.BUSINESS_TABLES.indexOf("metrics")
        assertTrue(metricIndex >= 0)
        val roomTrigger = "CREATE TEMP TRIGGER IF NOT EXISTS `room_table_modification_trigger_metrics_INSERT` " +
            "AFTER INSERT ON `metrics` BEGIN UPDATE room_table_modification_log SET invalidated = 1 " +
            "WHERE table_id = $metricIndex AND invalidated = 0; END"
        val generation = scalar("SELECT generation FROM count_trust_control")
        for (changed in listOf(
            roomTrigger.replace("AFTER INSERT", "\"AFTER INSERT\""),
            roomTrigger.replace("SET invalidated", "`SET invalidated`"),
            roomTrigger.replace("table_id = $metricIndex", "table_id = \"$metricIndex\""),
            roomTrigger.replace("room_table_modification_log", "'room_table_modification_log'")
        )) {
            assertNotEquals(roomTrigger, changed)
            denied { sql.execSQL(changed) }
            denied { sql.query(changed).close() }
            sql.compileStatement(changed).use { statement ->
                denied { statement.execute() }; denied { statement.executeUpdateDelete() }
                denied { statement.executeInsert() }; denied { statement.simpleQueryForLong() }
                denied { statement.simpleQueryForString() }
            }
        }
        assertEquals(generation, scalar("SELECT generation FROM count_trust_control"))
        assertEquals(0L, scalar("SELECT count(*) FROM temp.sqlite_master WHERE name='room_table_modification_trigger_metrics_INSERT'"))
        assertFalse(runtime.isRetired)
        repeat(2) {
            val ready = CompletableDeferred<Unit>()
            val observer = async(Dispatchers.IO) {
                withTimeout(10_000) { db.invalidationTracker.createFlow("sync_control", "next_config_imports")
                    .onEach { ready.complete(Unit) }.drop(1).first() }
            }
            withTimeout(10_000) { ready.await() }
            db.withTransaction { sql.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1") }
            assertEquals(setOf("sync_control"), observer.await())
            assertFalse(runtime.isRetired)
        }
        denied { sql.execSQL("CREATE TEMP TRIGGER room_table_modification_trigger_metrics_INSERT AFTER INSERT ON metrics BEGIN DELETE FROM count_trust_root; END") }
    }

    @Test fun closedConnectionAndItsCapabilitiesCannotReviveAfterReopen() = runBlocking<Unit> {
        val oldSql = sql
        val oldRuntime = runtime
        val statement = oldSql.compileStatement("SELECT 1")
        val cursor = oldSql.query("SELECT 1")
        database.reopen().openHelper.writableDatabase
        assertTrue(oldRuntime.isRetired)
        denied { oldSql.query("SELECT 1").close() }; denied { statement.simpleQueryForLong() }; denied { cursor.moveToFirst() }
        statement.close(); cursor.close()
        assertFalse(runtime.isRetired)
        assertEquals(1L, scalar("SELECT dirty FROM count_trust_control"))
    }

    /** Same wrapped identity for callbacks and helper getters; fault is below our boundary. */
    private class EndFaultFactory : SupportSQLiteOpenHelper.Factory {
        val afterEnd = AtomicBoolean()
        private var native: SupportSQLiteDatabase? = null
        fun connectionPragmas(): Map<String, String> =
            listOf("writable_schema", "recursive_triggers", "foreign_keys", "synchronous", "journal_mode",
                "schema_version", "user_version").associateWith { name ->
                requireNotNull(native).query("PRAGMA $name").use {
                    check(it.moveToFirst()); it.getString(0)
                }
            }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
                if (original !== raw) {
                    original = raw
                    native = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun endTransaction() {
                            raw.endTransaction()
                            if (!raw.inTransaction() && afterEnd.compareAndSet(true, false)) error("COUNT_BOUNDARY_TEST_UNKNOWN_END")
                        }
                    }
                }
                return checkNotNull(adapted)
            }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            }
            val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val writableDatabase get() = wrap(delegate.writableDatabase)
                override val readableDatabase get() = wrap(delegate.readableDatabase)
            }
        }
    }
}
