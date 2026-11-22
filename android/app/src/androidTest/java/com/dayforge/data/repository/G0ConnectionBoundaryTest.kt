package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** G0 capability probes on the actual disk Room/FrameworkSQLite stack, never production data.
 * A TEMP nonce is observational evidence of connection continuity, not a production trust root.
 * No incremental validator is enabled by passing this class.
 */
@RunWith(AndroidJUnit4::class)
class G0ConnectionBoundaryTest : NextCoreRequestFixture() {
    private val sql get() = db.openHelper.writableDatabase
    private fun number(database: SupportSQLiteDatabase, query: String): Long = database.query(query).use {
        check(it.moveToFirst()); it.getLong(0)
    }
    private fun text(database: SupportSQLiteDatabase, query: String): String = database.query(query).use {
        check(it.moveToFirst()); it.getString(0)
    }
    private fun external(): SQLiteDatabase {
        check(app.packageName == "com.dayforge.testbed")
        return SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
    }
    private suspend fun prepare(): String = db.withTransaction {
        assertEquals("wal", text(sql, "PRAGMA journal_mode"))
        sql.execSQL("CREATE TABLE g0_connection_probe (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
        sql.execSQL("INSERT INTO g0_connection_probe VALUES(1,0)")
        val token = UUID.randomUUID().toString()
        sql.execSQL("CREATE TEMP TABLE g0_connection_nonce (nonce TEXT NOT NULL)")
        sql.execSQL("INSERT INTO temp.g0_connection_nonce VALUES(?)", arrayOf(token))
        token
    }
    private fun nonce() = text(sql, "SELECT nonce FROM temp.g0_connection_nonce")
    private fun version() = number(sql, "PRAGMA main.data_version")

    @Test fun hundredWriterTransactionsKeepConnectionNonceAndDoNotInvalidateOwnVersion() = runBlocking<Unit> {
        val token = prepare()
        val baseline = db.withTransaction { version() }
        val times = mutableListOf<Long>()
        repeat(100) { n ->
            val started = SystemClock.elapsedRealtimeNanos()
            withContext(if (n % 2 == 0) Dispatchers.IO else Dispatchers.Default) {
                db.withTransaction {
                    assertEquals(token, nonce())
                    assertEquals("Own COMMIT must not force a cold audit", baseline, version())
                    sql.execSQL("UPDATE g0_connection_probe SET value=value+1 WHERE id=1")
                }
            }
            times += SystemClock.elapsedRealtimeNanos() - started
        }
        db.withTransaction {
            assertEquals(100L, number(sql, "SELECT value FROM g0_connection_probe"))
            assertEquals(token, nonce()); assertEquals(baseline, version())
            Log.i("G0Connection", "sqlite=${text(sql, "SELECT sqlite_version()")} samples=100 " +
                "transaction_ns=${times.joinToString(",")}")
        }
    }

    @Test fun externalCommitAfterReleaseIsSeenBeforeNextProtectedRead() = runBlocking<Unit> {
        val token = prepare()
        val trusted = db.withTransaction { version() }
        external().use { outside -> outside.execSQL("UPDATE g0_connection_probe SET value=7 WHERE id=1") }
        db.withTransaction {
            assertEquals(token, nonce())
            assertNotEquals("Never replace the trusted version after releasing the transaction", trusted, version())
            assertEquals(7L, number(sql, "SELECT value FROM g0_connection_probe"))
        }
    }

    @Test fun writerReservationExcludesExternalCommitUntilTheProtectedTransactionEnds() = runBlocking<Unit> {
        prepare()
        external().use { outside ->
            outside.rawQuery("PRAGMA busy_timeout=100", null).use { check(it.moveToFirst()) }
            val trusted = db.withTransaction {
                val initial = version()
                coroutineScope {
                    val result = async(Dispatchers.IO) {
                        try { outside.execSQL("UPDATE g0_connection_probe SET value=9 WHERE id=1"); false }
                        catch (failure: SQLiteException) {
                            check(failure.message.orEmpty().contains("locked", ignoreCase = true) ||
                                failure.message.orEmpty().contains("busy", ignoreCase = true))
                            true
                        }
                    }
                    assertTrue("An external writer committed inside the protected boundary", result.await())
                }
                assertEquals(initial, version())
                assertEquals(0L, number(sql, "SELECT value FROM g0_connection_probe"))
                initial
            }
            outside.execSQL("UPDATE g0_connection_probe SET value=9 WHERE id=1")
            db.withTransaction { assertNotEquals(trusted, version()) }
        }
    }

    @Test fun confirmedRollbackRetainsOldVersionAndData() = runBlocking<Unit> {
        val token = prepare()
        val initial = db.withTransaction { version() }
        val deliberate = IllegalArgumentException("G0_EXPECTED_ROLLBACK")
        val failure = runCatching {
            db.withTransaction {
                sql.execSQL("UPDATE g0_connection_probe SET value=10 WHERE id=1")
                throw deliberate
            }
        }.exceptionOrNull()
        assertSame(deliberate, failure)
        db.withTransaction {
            assertEquals(token, nonce()); assertEquals(initial, version())
            assertEquals(0L, number(sql, "SELECT value FROM g0_connection_probe"))
        }
    }

    @Test fun reopeningCannotReuseTheOldConnectionNonce() = runBlocking<Unit> {
        prepare()
        storage.reopen()
        db.withTransaction {
            assertEquals(0L, number(sql, "SELECT count(*) FROM sqlite_temp_master WHERE name='g0_connection_nonce'"))
            assertEquals(0L, number(sql, "SELECT value FROM g0_connection_probe"))
        }
    }

    @Test fun roomObserverMaintenanceDoesNotInvalidateTheWriterNonceOrOwnVersion() = runBlocking<Unit> {
        val token = prepare()
        val initial = db.withTransaction { version() }
        coroutineScope {
            val ready = CompletableDeferred<Unit>()
            val observed = async(Dispatchers.IO) {
                withTimeout(5000) { db.metricDao().observeMetricById(metric.id).onEach {
                    if (it?.name == metric.name) ready.complete(Unit)
                }.first { it?.name == "G0 updated" } }
            }
            withTimeout(5000) { ready.await() }
            db.withTransaction { db.metricDao().update(metric.copy(name = "G0 updated")) }
            assertEquals("G0 updated", observed.await()!!.name)
        }
        db.withTransaction {
            assertEquals(token, nonce()); assertEquals(initial, version())
            assertEquals("G0 updated", db.metricDao().getMetricById(metric.id)!!.name)
        }
    }
}
