package com.dayforge.data.repository

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabaseProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A genuine Room diagnostic observer exercises the controlled public handles. Fixed test
 * operations only; no raw/native handle or production observer callback is introduced. */
@RunWith(AndroidJUnit4::class)
class CountTrustObserverSqlBoundaryTest : NextCoreRequestFixture() {
    private val armed = AtomicBoolean()
    private val touched = AtomicBoolean()
    private val callbackFailure = AtomicReference<Throwable?>()
    private lateinit var cachedWrite: SupportSQLiteStatement
    private lateinit var cachedRead: SupportSQLiteStatement
    private val sql get() = db.openHelper.writableDatabase

    private fun denied(code: String = "COUNT_TRUST_OBSERVER_WRITE_DENIED", operation: () -> Unit) {
        val error = try { operation(); null } catch (failure: IllegalStateException) { failure }
        assertEquals("The controlled entry must reject this observer operation exactly", code, error?.message)
    }

    private val observer = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            if (sqlQuery != "SELECT 612385" || !armed.compareAndSet(true, false)) return
            touched.set(true)
            // Room launches observer work even with an inline executor. Record a test failure
            // and assert it after the marker; never rely on an uncaught observer exception.
            callbackFailure.set(runCatching {
                check(db.inTransaction())
                for (text in listOf("SELECT 7", "WITH input(value) AS (SELECT 7) SELECT value FROM input")) {
                    sql.query(text).use { assertTrue(it.moveToFirst()); assertEquals(7L, it.getLong(0)); assertFalse(it.moveToNext()) }
                }
                sql.query(SimpleSQLiteQuery("SELECT ?", arrayOf(7L))).use {
                    assertTrue(it.moveToFirst()); assertEquals(7L, it.getLong(0))
                }
                sql.query("PRAGMA main.user_version").use { assertTrue(it.moveToFirst()); assertEquals(20L, it.getLong(0)) }
                sql.query("PRAGMA table_info(metrics)").use { assertTrue(it.moveToFirst()) }
                sql.query("EXPLAIN SELECT 1").use { assertTrue(it.moveToFirst()) }
                assertEquals(7L, cachedRead.simpleQueryForLong())

                denied { sql.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1") }
                denied { cachedWrite.executeUpdateDelete() }
                sql.compileStatement("UPDATE sync_control SET suppressOutbox=0 WHERE id=1").use { prepared ->
                    denied { prepared.execute() }; denied { prepared.executeInsert() }
                    denied { prepared.executeUpdateDelete() }; denied { prepared.simpleQueryForLong() }
                }
                denied { sql.insert("sync_control", SQLiteDatabase.CONFLICT_IGNORE,
                    ContentValues().apply { put("id", 1); put("suppressOutbox", 0) }) }
                denied { sql.update("sync_control", SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply { put("suppressOutbox", 0) }, "id=?", arrayOf(1)) }
                denied { sql.delete("sync_control", "id=?", arrayOf(-1)) }
                denied { sql.execSQL("WITH chosen(id) AS (SELECT 1) UPDATE sync_control SET suppressOutbox=0 WHERE id IN chosen") }
                denied { sql.query("PRAGMA wal_checkpoint").close() }
                denied { sql.execSQL("UPDATE room_table_modification_log SET invalidated = 0 WHERE invalidated = 1") }
                denied { sql.query(object : SupportSQLiteQuery {
                    override val sql = "SELECT ?"
                    override val argCount = 1
                    override fun bindTo(statement: SupportSQLiteProgram) {
                        statement.bindLong(1, 7L)
                        this@CountTrustObserverSqlBoundaryTest.sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                    }
                }).close() }
                denied("COUNT_TRUST_OBSERVER_CONTROL_DENIED") { sql.beginTransaction() }
                denied("COUNT_TRUST_OBSERVER_CONTROL_DENIED") { sql.setTransactionSuccessful() }
                denied("COUNT_TRUST_OBSERVER_CONTROL_DENIED") { sql.endTransaction() }
                denied("COUNT_TRUST_OBSERVER_CONTROL_DENIED") { sql.close() }
            }.exceptionOrNull())
        }
    }
    init { storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, observer) } }

    @Test fun actualDiagnosticReadsRemainUsableWhileEveryWriteEntryAndCachedExecutionIsDenied() = runBlocking<Unit> {
        register()
        val before = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
        cachedWrite = sql.compileStatement("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        cachedRead = sql.compileStatement("SELECT ?").also { it.bindLong(1, 7L) }
        try {
            db.withTransaction {
                armed.set(true)
                sql.query("SELECT 612385").use { assertTrue(it.moveToFirst()); assertEquals(612385L, it.getLong(0)) }
                assertTrue(touched.get()); assertNull(callbackFailure.get())
            }
        } finally { cachedWrite.close(); cachedRead.close() }
        assertEquals(before, db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents })
        // The restriction ends with the observer, not with every ordinary valid writer.
        db.withTransaction {
            val generation = sql.query("SELECT generation FROM count_trust_control WHERE id=1").use { check(it.moveToFirst()); it.getLong(0) }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=suppressOutbox WHERE id=1")
            sql.query("SELECT generation FROM count_trust_control WHERE id=1").use {
                assertTrue(it.moveToFirst()); assertEquals(generation + 1L, it.getLong(0))
            }
        }
    }
}
