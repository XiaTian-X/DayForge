package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0DomainSqlBoundaryTest : G0SealedDomainFixture() {
    private fun denied(body: () -> Unit) { assertTrue(runCatching(body).exceptionOrNull() is G0DomainSqlGate.Denied) }
    private fun sql() = db.openHelper.writableDatabase
    private fun change(name: String) = sql().execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?", arrayOf<Any>(name, "", millis, metric.id))
    private fun value(row: List<G0DomainJournal.Cell>, table: String, field: String) = row[G0DomainSqlCatalog.columns.getValue(table).indexOf(field)].value

    @Test fun registeredMetricSqlCapturesNativeUpdateAndActualOutboxTrigger() = runBlocking<Unit> {
        sealDomain()
        domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) {
            change("Sealed metric")
            val changes = domainGate.changes()
            assertEquals(2, changes.size)
            val metricChange = changes.single { it.table == "metrics" }
            assertEquals("UPDATE", metricChange.operation)
            assertEquals(metric.name, value(requireNotNull(metricChange.old), "metrics", "name"))
            assertEquals("Sealed metric", value(requireNotNull(metricChange.new), "metrics", "name"))
            val outbox = changes.single { it.table == "sync_outbox" }
            assertEquals("INSERT", outbox.operation); assertNull(outbox.old)
            assertEquals(metric.uuid, value(requireNotNull(outbox.new), "sync_outbox", "entityUuid"))
            domainGate.certifyChanges { assertEquals(changes, it) }
            domainGate.finalRead { assertEquals("Sealed metric", db.metricDao().getMetricById(metric.id)!!.name) }
        }
        domainGate.transaction(db, G0DomainSqlGate.Phase.READ) {
            assertTrue(domainGate.changes().isEmpty())
            assertEquals("Sealed metric", db.metricDao().getMetricById(metric.id)!!.name)
        }
    }
    @Test fun rewriteAndRestoreIsNotInvisibleToMutationAudit() = runBlocking<Unit> {
        sealDomain()
        domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) {
            change("Temporary"); change(metric.name)
            assertEquals(metric.name, db.metricDao().getMetricById(metric.id)!!.name)
            val changes = domainGate.changes()
            assertEquals(4, changes.size)
            assertEquals(2, changes.count { it.table == "metrics" })
            assertEquals(2, changes.count { it.table == "sync_outbox" })
            domainGate.certifyChanges { assertEquals(changes, it) }
        }
    }
    @Test fun uncataloguedSqlAndHandlesCannotChangeCaptureOrEscapeFinal() = runBlocking<Unit> {
        sealDomain()
        for (handle in listOf(domainGate.sqlClient(), sql())) {
            for (text in listOf("PRAGMA recursive_triggers=OFF", "PRAGMA main.schema_version=1", "DELETE FROM g0_domain_events",
                "DROP TRIGGER g0_domain_metrics_UPDATE", "CREATE TEMP TABLE metrics(id INTEGER)", "ATTACH DATABASE ':memory:' AS bypass")) {
                denied { handle.execSQL(text) }; denied { handle.query(text).close() }
                handle.compileStatement(text).use { denied { it.execute() }; denied { it.simpleQueryForLong() } }
            }
        }
        lateinit var cursor: Cursor
        domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) {
            cursor = sql().query("SELECT count(*) FROM sync_outbox")
            assertTrue(cursor.moveToFirst())
            withContext(Dispatchers.Default) { denied { cursor.getLong(0) } }
            domainGate.finalRead { denied { change("Forbidden final") } }
            denied { change("Forbidden after final") }
        }
        cursor.use { denied { it.getLong(0) } }
    }
    @Test fun undeclaredWriteRollsBackAndCannotReuseCertificate() = runBlocking<Unit> {
        sealDomain()
        val failure = rejected { domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) { change("Unadmitted") } }
        assertEquals("G0_DOMAIN_UNVALIDATED_CHANGE", failure.message)
        denied { domainGate.requireSealedConnection() }
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT name FROM metrics WHERE id=?", arrayOf(metric.id.toString())).use { assertTrue(it.moveToFirst()); assertEquals(metric.name, it.getString(0)) }
            raw.rawQuery("SELECT count(*) FROM sync_outbox", null).use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
        }
    }
    @Test fun externalCommitInvalidatesBeforeReusingUnchangedLookingSnapshot() = runBlocking<Unit> {
        sealDomain()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("UPDATE metrics SET description=? WHERE id=?", arrayOf<Any>("Actual external mutation", metric.id))
        }
        val failure = rejected { domainGate.transaction(db, G0DomainSqlGate.Phase.READ) { error("Must not read") } }
        assertEquals("G0_DOMAIN_EXTERNAL_CHANGE", failure.message)
    }
    @Test fun unknownActualCommitClosesWarmReuseButPreservesDurableResult() = runBlocking<Unit> {
        sealDomain()
        val failure = rejected { domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) {
            change("Committed without result")
            domainGate.certifyChanges { assertEquals(2, it.size) }
            domainGate.finalRead { endFaults.failAfterNextEnd.set(true) }
        } }
        assertEquals("G0_COMMIT_RESULT_LOST", failure.message)
        denied { domainGate.requireSealedConnection() }
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT name FROM metrics WHERE id=?", arrayOf(metric.id.toString())).use { assertTrue(it.moveToFirst()); assertEquals("Committed without result", it.getString(0)) }
        }
    }
    @Test fun untrustedMainOrTempTriggerCannotBeAdoptedAsTrustedSchema() = runBlocking<Unit> {
        sql().execSQL("CREATE TEMP TRIGGER unexpected AFTER UPDATE ON main.metrics BEGIN UPDATE sync_control SET suppressOutbox=1; END")
        val failure = rejected { sealDomain() }
        assertEquals("G0_DOMAIN_SCHEMA_NOT_TRUSTED", failure.message)
    }
    @Test fun replaceProducesBothDeleteAndInsertIncludingCascadeInsteadOfOneNetRow() = runBlocking<Unit> {
        sealDomain()
        domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) {
            val statement = G0DomainSqlCatalog.writes.keys.single { it.startsWith("INSERT OR REPLACE INTO `metrics`") }
            // Actual stored primitive values, including the appearance JSON, without coercion.
            val values = sql().query("SELECT * FROM metrics WHERE id = ? LIMIT 1", arrayOf(metric.id)).use { c ->
                assertTrue(c.moveToFirst()); Array<Any?>(c.columnCount) { i -> when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i); Cursor.FIELD_TYPE_STRING -> c.getString(i)
                    else -> c.getBlob(i)
                } }
            }
            sql().execSQL(statement, values)
            val changes = domainGate.changes()
            assertEquals(setOf("DELETE", "INSERT"), changes.filter { it.table == "metrics" }.map { it.operation }.toSet())
            assertEquals(2, changes.count { it.table == "sync_outbox" })
            domainGate.certifyChanges { assertEquals(changes, it) }
        }
    }
    @Test fun externalRewriteAndRestoreStillInvalidatesWhenThereWasARealCommit() = runBlocking<Unit> {
        sealDomain()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { raw ->
            raw.beginTransaction()
            try {
                raw.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                raw.execSQL("UPDATE metrics SET description='transient' WHERE id=?", arrayOf(metric.id))
                raw.execSQL("UPDATE metrics SET description=? WHERE id=?", arrayOf<Any>(metric.description, metric.id))
                raw.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                raw.setTransactionSuccessful()
            } finally { raw.endTransaction() }
        }
        assertEquals("G0_DOMAIN_EXTERNAL_CHANGE", rejected {
            domainGate.transaction(db, G0DomainSqlGate.Phase.READ) { error("Must not reuse") }
        }.message)
    }
    @Test fun roomInvalidationSubscriptionAndResubscriptionPreserveTheSealedConnection() = runBlocking<Unit> {
        sealDomain()
        repeat(3) { index ->
            val ready = CompletableDeferred<Unit>()
            val observed = async(Dispatchers.IO) {
                withTimeout(5000) {
                    db.invalidationTracker.createFlow("metrics").onEach { ready.complete(Unit) }.drop(1).first()
                }
            }
            withTimeout(5000) { ready.await() }
            domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) {
                change("Observed $index")
                domainGate.certifyChanges { assertEquals(2, it.size) }
            }
            assertEquals(setOf("metrics"), observed.await())
            domainGate.transaction(db, G0DomainSqlGate.Phase.READ) { assertTrue(domainGate.changes().isEmpty()) }
        }
    }
    @Test fun externalSchemaChangeIsNeverTrustedEvenWithoutDomainRowsChanging() = runBlocking<Unit> {
        sealDomain()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { raw ->
            raw.execSQL("CREATE TRIGGER unknown_domain AFTER INSERT ON metrics BEGIN SELECT 1; END")
        }
        assertTrue(rejected { domainGate.transaction(db, G0DomainSqlGate.Phase.READ) { error("Must not reuse") } }.message in
            setOf("G0_DOMAIN_EXTERNAL_CHANGE", "G0_DOMAIN_SCHEMA_CHANGED"))
    }
    @Test fun concurrentAdmissionCannotEraseAnotherTransactionsCandidate() = runBlocking<Unit> {
        sealDomain()
        val staged = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async { domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) {
            change("Serialized first"); domainGate.certifyChanges { assertEquals(2, it.size) }
            staged.complete(Unit); release.await()
        } }
        staged.await()
        val second = async { domainGate.transaction(db, G0DomainSqlGate.Phase.READ) {
            assertEquals("Serialized first", db.metricDao().getMetricById(metric.id)!!.name)
        } }
        yield(); assertFalse(second.isCompleted); release.complete(Unit)
        first.await(); second.await()
    }

    @Test fun corruptionNotificationPreservesTheDatabaseInsteadOfCallingDestructiveDefault() = runBlocking<Unit> {
        val name = "g0-domain-corruption-${java.util.UUID.randomUUID()}"
        lateinit var callback: androidx.sqlite.db.SupportSQLiteOpenHelper.Callback
        val capturing = androidx.sqlite.db.SupportSQLiteOpenHelper.Factory { configuration ->
            callback = configuration.callback
            androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(configuration)
        }
        val gate = G0DomainSqlGate(capturing)
        val database = androidx.room.Room.databaseBuilder(app,com.dayforge.data.local.HabitDatabase::class.java,name)
            .addCallback(com.dayforge.data.local.SyncSchemaCallback).openHelperFactory(gate.factory).build()
        try {
            val handle = database.openHelper.writableDatabase
            val file = app.getDatabasePath(name)
            assertTrue(file.isFile)
            assertEquals("G0_DOMAIN_CORRUPTION_PRESERVED",runCatching { callback.onCorruption(handle) }.exceptionOrNull()?.message)
            assertTrue("The default callback must not delete evidence",file.isFile)
        } finally { database.close(); app.deleteDatabase(name) }
    }

}
