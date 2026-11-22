package com.dayforge.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.drop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0SqlGateTest : G0GuardedFixture() {
    private fun denied(block: () -> Unit) {
        assertTrue("Expected the SQL gate, not an unrelated SQLite error", runCatching(block).exceptionOrNull() is G0SqlGate.Denied)
    }
    @Test fun allSqlEntrypointsRejectUnregisteredMutationAndNormalWorkContinues() = runBlocking<Unit> {
        val probe = guardedProbe()
        val before = probe.read()
        for (sql in listOf(sqlGate.sqlClient(), db.openHelper.readableDatabase, db.openHelper.writableDatabase)) {
            for (statement in listOf("DROP TRIGGER g0_capture_facts_UPDATE", "PRAGMA main.schema_version=1",
                "PRAGMA writable_schema=ON", "PRAGMA recursive_triggers=OFF", "ATTACH DATABASE ':memory:' AS escaped",
                "SAVEPOINT escape", "DELETE FROM g0_changes", "UPDATE g0_changes SET id=0",
                "CREATE TEMP TABLE g0_facts(seq INTEGER)",
                "CREATE TEMP TRIGGER g0_escape AFTER INSERT ON main.g0_facts BEGIN DELETE FROM g0_outbox; END", "SELECT 1; DROP TRIGGER g0_no_update")) {
                denied { sql.execSQL(statement) }
                denied { sql.query(statement).close() }
                sql.compileStatement(statement).use { compiled ->
                    denied { compiled.execute() }; denied { compiled.executeUpdateDelete() }
                    denied { compiled.executeInsert() }; denied { compiled.simpleQueryForLong() }
                    denied { compiled.simpleQueryForString() }
                }
            }
            denied { sql.insert("g0_facts", 5, ContentValues().apply { put("seq", 1); put("owner", id(999)); put("value", 1) }) }
            denied { sql.update("g0_facts", 0, ContentValues().apply { put("value", 0) }, null, null) }
            denied { sql.delete("g0_changes", null, null) }
            denied { sql.execPerConnectionSQL("PRAGMA writable_schema=ON", null) }
            denied { sql.version = 1 }; denied { sql.pageSize = 8192 }
            denied { sql.setForeignKeyConstraintsEnabled(false) }
            denied { sql.disableWriteAheadLogging() }; denied { sql.enableWriteAheadLogging() }
            denied { sql.yieldIfContendedSafely() }
        }
        assertEquals(before, probe.fullAudit())
        assertEquals(129L, probe.append("legal-after-attacks").count)
    }
    @Test fun sqlClientNeverReceivesUnrestrictedBootstrapAuthority() = runBlocking<Unit> {
        db.openHelper.writableDatabase // The trusted fixture opens Room, not the SQL consumer.
        val client = sqlGate.sqlClient()
        denied { client.execSQL("CREATE TABLE escaped(id INTEGER)") }
        denied { client.query("SELECT count(*) FROM metrics").close() }
        denied { client.insert("metrics", 0, ContentValues()) }
        denied { client.version = 1 }
        val probe = guardedProbe()
        denied { client.execSQL("DROP TRIGGER g0_no_update") }
        assertEquals(129L, probe.append("after-boot-client").count)
    }
    @Test fun preparedBeforeSealDoesNotCarryBootPrivilege() = runBlocking<Unit> {
        val pending = db.openHelper.writableDatabase.compileStatement("DROP TRIGGER g0_no_update")
        val probe = guardedProbe()
        pending.use { denied { it.execute() } }
        assertEquals(128L, probe.fullAudit().count)
        assertEquals(129L, probe.append("legal").count)
    }
    @Test fun preparedStatementCannotCrossPhaseOrTransactionAndChecksBindingTypes() = runBlocking<Unit> {
        val probe = guardedProbe()
        lateinit var statement: SupportSQLiteStatement
        sqlGate.transaction(db, G0SqlGate.Phase.BUSINESS) {
            statement = db.openHelper.writableDatabase.compileStatement("INSERT INTO g0_facts VALUES(?,?,1)")
            statement.bindLong(1, 129); statement.bindString(2, id(1))
            statement.bindDouble(1, 129.5); denied { statement.executeInsert() }
            statement.bindLong(1, 129)
            withContext(Dispatchers.Default) { denied { statement.executeInsert() } }
            sqlGate.finalRead { denied { statement.executeInsert() } }
            // Returning from FINAL must not resurrect the parent BUSINESS capability.
            denied { statement.executeInsert() }
        }
        statement.use { denied { it.executeInsert() } }
        assertEquals(128L, probe.read().count)
        assertEquals(129L, probe.append("legal").count)
    }
    @Test fun intentFinalCannotReopenIntentWritesAndNormalAppendStillWorks() = runBlocking<Unit> {
        val probe = guardedProbe()
        sqlGate.transaction(db, G0SqlGate.Phase.INTENT) {
            sqlGate.finalRead {
                db.openHelper.writableDatabase.query("SELECT count(*) FROM g0_facts").use {
                    assertTrue(it.moveToFirst()); assertEquals(128L, it.getLong(0))
                }
            }
            denied { db.openHelper.writableDatabase.execSQL("INSERT OR IGNORE INTO g0_intents VALUES(?,?)",
                arrayOf("late-intent", id(1))) }
        }
        assertEquals(128L, probe.fullAudit().count)
        assertEquals(129L, probe.append("after-intent-final").count)
    }
    @Test fun cursorsDoNotExposeNativeDatabaseOrOutliveTheirLease() = runBlocking<Unit> {
        val probe = guardedProbe()
        lateinit var cursor: Cursor
        sqlGate.transaction(db, G0SqlGate.Phase.READ) {
            cursor = db.openHelper.writableDatabase.query("SELECT count(*) FROM g0_facts")
            assertFalse(cursor is SQLiteCursor); assertFalse(cursor is android.database.CursorWrapper)
            assertTrue(cursor.moveToFirst()); assertEquals(128, cursor.getInt(0))
        }
        cursor.use {
            denied { it.moveToFirst() }; denied { it.getLong(0) }
            denied { it.copyStringToBuffer(0, android.database.CharArrayBuffer(32)) }
        }
        assertEquals(128L, probe.read().count)
    }
    @Test fun callerOwnedQueryIsFrozenAndCannotSubstituteSqlAfterValidation() = runBlocking<Unit> {
        val probe = guardedProbe()
        var accesses = 0
        val changing = object : SupportSQLiteQuery {
            override val sql get() = if (accesses++ == 0) "SELECT count(*) FROM g0_facts" else "PRAGMA writable_schema=ON"
            override val argCount get() = 0
            override fun bindTo(statement: SupportSQLiteProgram) = Unit
        }
        sqlGate.transaction(db, G0SqlGate.Phase.READ) {
            sqlGate.sqlClient().query(changing).use { assertTrue(it.moveToFirst()); assertEquals(128, it.getInt(0)) }
        }
        assertEquals(1, accesses)
        // Room's outer QueryInterceptor reads the query before our factory sees it. Its
        // diagnostic callback is not authorization; the substituted SQL must still be denied.
        accesses = 0
        sqlGate.transaction(db, G0SqlGate.Phase.READ) {
            denied { db.openHelper.writableDatabase.query(changing).close() }
        }
        assertEquals(2, accesses)
        assertTrue(guardedQueries.get() > 0)
        assertEquals(128L, probe.fullAudit().count)
        assertEquals(129L, probe.append("legal").count)
    }
    @Test fun validButUnexpectedLateWriteStillRollsBackInsteadOfBeingAuthorizedByTheGate() = runBlocking<Unit> {
        val probe = guardedProbe()
        val before = probe.read()
        assertEquals("G0_UNEXPECTED_WRITE", rejected {
            probe.append("late", beforeFinal = {
                db.openHelper.writableDatabase.execSQL("INSERT INTO g0_facts VALUES(?,?,1)", arrayOf<Any>(10000L, id(1)))
            })
        }.message)
        assertEquals(before, probe.fullAudit())
        assertEquals(129L, probe.append("late").count)
    }
    @Test fun businessCallbackCannotEndOrMarkTheOwningTransactionSuccessful() = runBlocking<Unit> {
        val probe = guardedProbe()
        val sql = db.openHelper.writableDatabase
        assertEquals(129L, probe.append("legal", beforeFinal = {
            denied { sql.endTransaction() }; denied { sql.setTransactionSuccessful() }
            denied { sql.beginTransaction() }
        }).count)
        assertEquals(129L, probe.fullAudit().count)
    }
    @Test fun roomSubscriptionRefreshCancellationAndResubscriptionKeepTheCertificateUsable() = runBlocking<Unit> {
        val probe = guardedProbe()
        repeat(3) {
            val ready = CompletableDeferred<Unit>()
            val change = async(Dispatchers.IO) {
                withTimeout(5000) {
                    db.invalidationTracker.createFlow("metrics").onEach { ready.complete(Unit) }.drop(1).first()
                }
            }
            withTimeout(5000) { ready.await() }
            sqlGate.transaction(db, G0SqlGate.Phase.BUSINESS) {
                db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id))
            }
            assertEquals(setOf("metrics"), change.await())
            assertEquals(128L + it, probe.read().count)
            assertEquals(129L + it, probe.append("after-refresh-$it").count)
        }
        assertEquals(131L, probe.fullAudit().count)
    }

    @Test fun cancellationRollsBackAndSameCommandCanRetry() = runBlocking<Unit> {
        val probe = guardedProbe()
        val before = probe.read()
        val cancelled = kotlinx.coroutines.CancellationException("G0_CANCELLED_BEFORE_FINAL")
        assertSame(cancelled, runCatching { probe.append("cancelled", beforeFinal = { throw cancelled }) }.exceptionOrNull())
        assertEquals(before, probe.read()); assertEquals(before, probe.fullAudit())
        assertEquals(129L, probe.append("cancelled").count)
        assertEquals(129L, probe.append("cancelled").count)
    }
    @Test fun externalCommitCannotReuseTheSealedCertificate() = runBlocking<Unit> {
        val probe = guardedProbe()
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use {
            it.execSQL("UPDATE g0_nodes SET digest=? WHERE level=0 AND position=0", arrayOf("0".repeat(64)))
        }
        assertEquals("G0_EXTERNAL_CHANGE", rejected { probe.read() }.message)
        assertEquals("G0_EXTERNAL_CHANGE", rejected { probe.append("after-external") }.message)
    }
    @Test fun accountChangeCannotUseSealedReadOrWriteAuthority() = runBlocking<Unit> {
        val probe = guardedProbe()
        tokens.saveLoginSession("other-synthetic", "other-refresh", "member", id(999), false)
        assertEquals("G0_AUTHORITY_CHANGED", rejected { probe.read() }.message)
        assertEquals("G0_AUTHORITY_CHANGED", rejected { probe.append("wrong-account") }.message)
    }
    @Test fun generatedDaoCannotBypassSqlRegistration() = runBlocking<Unit> {
        val probe = guardedProbe()
        assertTrue(runCatching { db.metricDao().update(metric.copy(name = "forbidden")) }.exceptionOrNull() is G0SqlGate.Denied)
        assertEquals(128L, probe.read().count)
        assertEquals(129L, probe.append("after-dao").count)
    }
    @Test fun ordinarySqlClientCannotImpersonateRoomMaintenanceOrControlTheHandle() = runBlocking<Unit> {
        val probe = guardedProbe()
        val client = sqlGate.sqlClient()
        denied { client.execSQL("UPDATE room_table_modification_log SET invalidated = 0 WHERE invalidated = 1") }
        denied { client.execSQL("DROP TRIGGER IF EXISTS `room_table_modification_trigger_metrics_UPDATE`") }
        denied { client.execSQL("INSERT OR IGNORE INTO room_table_modification_log VALUES(4, 0)") }
        denied { client.query("SELECT * FROM room_table_modification_log WHERE invalidated = 1").close() }
        denied { client.beginTransaction() }; denied { client.endTransaction() }
        denied { client.setTransactionSuccessful() }; denied { client.close() }
        sqlGate.transaction(db, G0SqlGate.Phase.READ) {
            client.query("SELECT count(*) FROM g0_facts").use { assertTrue(it.moveToFirst()); assertEquals(128, it.getInt(0)) }
        }
        assertEquals(129L, probe.append("after-client-attack").count)
    }
    @Test fun realCommitWithLostResultInvalidatesTheWarmCertificate() = runBlocking<Unit> {
        val probe = guardedProbe()
        val failure = runCatching {
            probe.append("lost-result", beforeFinal = { commitFaults.failAfterNextEnd.set(true) })
        }.exceptionOrNull()
        assertEquals("G0_COMMIT_RESULT_LOST", failure?.message)
        assertFalse(commitFaults.failAfterNextEnd.get())
        assertTrue(runCatching { probe.read() }.exceptionOrNull() is G0SqlGate.Denied)
        assertTrue(runCatching { probe.append("lost-result") }.exceptionOrNull() is G0SqlGate.Denied)
        // A separate real connection proves this was a committed write, not simulated rollback.
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { outside ->
            for ((table, count) in mapOf("g0_facts" to 129L, "g0_outbox" to 1L, "g0_results" to 1L)) {
                outside.rawQuery("SELECT count(*) FROM $table", null).use { assertTrue(it.moveToFirst()); assertEquals(count, it.getLong(0)) }
            }
        }
    }
    @Test fun closeInvalidatesOldStatementsAndCannotReenableBootstrap() = runBlocking<Unit> {
        val probe = guardedProbe()
        val statement = db.openHelper.writableDatabase.compileStatement("INSERT INTO g0_facts VALUES(?,?,1)")
        statement.bindLong(1, 129); statement.bindString(2, id(1))
        db.close()
        statement.use { denied { it.executeInsert() } }
        assertTrue(runCatching { probe.read() }.isFailure)
        assertTrue(runCatching { db.openHelper.writableDatabase.execSQL("DROP TRIGGER g0_no_update") }.isFailure)
    }
}
