package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.SyncSchemaCallback
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** R0 capability gates, not a cold certificate implementation. The reference manifest is
 * produced from a SEPARATE newly-created database using the locked generated schema/callbacks.
 * It never comes from the database under examination. No production opening path is replaced.
 */
@RunWith(AndroidJUnit4::class)
class G0RoomColdBoundaryTest : NextCoreRequestFixture() {
    private fun external(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path, null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
    )

    private fun reference(): List<G0SchemaRow> {
        val name = "g0-reference-${UUID.randomUUID()}"
        val clean = Room.databaseBuilder(app, HabitDatabase::class.java, name)
            .addCallback(SyncSchemaCallback).build()
        return try { g0Schema(clean.openHelper.writableDatabase) }
        finally { clean.close(); check(app.deleteDatabase(name)) }
    }

    private fun reopen(factory: G0ColdOpenFactory): HabitDatabase {
        storage.isolatedBuilder = { context ->
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .addCallback(SyncSchemaCallback).openHelperFactory(factory).build()
        }
        return storage.reopen()
    }

    @Test fun coldOpenKeepsOneWriterReservationThroughRoomCallbacksAndBaseline() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val expected = reference()
            val worker = Executors.newSingleThreadExecutor()
            var blocked = 0
            fun attack() {
                val result = worker.submit<Boolean> {
                    external().use { outside ->
                        outside.rawQuery("PRAGMA busy_timeout=50", null).use { check(it.moveToFirst()) }
                        try {
                            outside.execSQL("CREATE TRIGGER g0_race AFTER INSERT ON sync_control BEGIN DELETE FROM metrics; END")
                            false
                        } catch (failure: SQLiteException) {
                            check(failure.message.orEmpty().contains("locked", true) ||
                                failure.message.orEmpty().contains("busy", true))
                            true
                        }
                    }
                }.get(5, TimeUnit.SECONDS)
                assertTrue("External schema mutation crossed the cold boundary", result)
                blocked++
            }
            try {
                val factory = G0ColdOpenFactory(expected, beforeRoomOpen = { attack() }, baseline = {
                    attack()
                    assertEquals(1L, g0Number(it, "SELECT count(*) FROM metrics"))
                })
                val reopened = reopen(factory)
                reopened.openHelper.writableDatabase
                assertTrue(factory.validated && factory.committed)
                assertEquals(1, factory.callbacks)
                assertEquals(2, blocked)
                reopened.withTransaction {
                    val sql = reopened.openHelper.writableDatabase
                    sql.query("SELECT value FROM temp.g0_open_nonce").use {
                        assertTrue(it.moveToFirst()); assertEquals(factory.nonce, it.getString(0))
                    }
                    yield()
                    assertEquals(metric, reopened.metricDao().getMetricById(metric.id))
                    // A nested suspend transaction must reuse the existing transaction context.
                    reopened.withTransaction { assertEquals(1L, g0Number(sql, "PRAGMA foreign_keys")) }
                }
            } finally { worker.shutdown(); check(worker.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }

    @Test fun unknownTriggerIsRejectedBeforeRoomCanExecuteItsOnOpenWrite() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val expected = reference()
            db.close()
            external().use {
                it.execSQL("DELETE FROM sync_control")
                it.execSQL("CREATE TRIGGER g0_poison BEFORE INSERT ON sync_control BEGIN DELETE FROM metrics; END")
            }
            val factory = G0ColdOpenFactory(expected)
            val reopened = reopen(factory)
            val failure = runCatching { reopened.openHelper.writableDatabase }.exceptionOrNull()
            assertNotNull("Unknown trigger must stop opening", failure)
            assertTrue(generateSequence(failure) { it.cause }.any { it.message == "G0_COLD_SCHEMA_REJECTED" })
            assertEquals(0, factory.callbacks)
            assertFalse(factory.committed)
            external().use {
                it.rawQuery("SELECT count(*) FROM metrics", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0)) }
                it.rawQuery("SELECT count(*) FROM sync_control", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(0L, c.getLong(0)) }
            }
        }
    }

    @Test fun rejectedBaselineRollsBackCallbackEffectsAndReleasesWriterReservation() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val expected = reference()
            db.close()
            external().use { it.execSQL("DELETE FROM sync_control") }
            val factory = G0ColdOpenFactory(expected, baseline = { error("G0_BASELINE_REJECTED") })
            val reopened = reopen(factory)
            val failure = runCatching { reopened.openHelper.writableDatabase }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it.message == "G0_BASELINE_REJECTED" })
            assertTrue(factory.callbacks > 0)
            assertFalse(factory.committed)
            external().use {
                it.rawQuery("SELECT count(*) FROM sync_control", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(0L, c.getLong(0)) }
                it.execSQL("INSERT INTO sync_control VALUES(1,0)")
            }
            val next = G0ColdOpenFactory(expected)
            reopen(next).openHelper.writableDatabase
            assertTrue(next.committed)
        }
    }

    @Test fun corruptionCallbackPreservesDatabaseInsteadOfDeletingEvidence() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val expected = reference()
            var invalidations = 0
            val factory = G0ColdOpenFactory(expected, onFailure = { invalidations++ })
            reopen(factory).openHelper.writableDatabase
            val failure = runCatching { factory.injectCorruptionSignalForTest() }.exceptionOrNull()
            assertEquals("G0_COLD_CORRUPTION", failure?.message)
            assertEquals(1, invalidations)
            assertTrue(app.getDatabasePath("habit_database").isFile)
            external().use {
                it.rawQuery("SELECT count(*) FROM metrics", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0))
                }
            }
            // Callback injection is not disk corruption. Confirm the intact evidence can
            // still be opened by a NEW owner, without pretending a damaged image was repaired.
            val next = G0ColdOpenFactory(expected)
            reopen(next).openHelper.writableDatabase
            assertTrue(next.committed)
        }
    }

    @Test fun cancelledOwnerClosesCachedHandleAndRejectsWaitingAndRepeatedGetters() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val expected = reference()
            val worker = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val failure = CancellationException("G0_BASELINE_CANCELLED")
            lateinit var cached: SupportSQLiteDatabase
            lateinit var reopened: HabitDatabase
            lateinit var waiter: Future<Throwable?>
            val factory = G0ColdOpenFactory(expected, baseline = { sql ->
                cached = sql
                assertTrue(sql.inTransaction())
                waiter = worker.submit<Throwable?> {
                    entered.countDown()
                    runCatching { reopened.openHelper.readableDatabase }.exceptionOrNull()
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                try {
                    waiter.get(50, TimeUnit.MILLISECONDS)
                    fail("Concurrent getter escaped before validation")
                } catch (_: TimeoutException) { /* Getter remains behind the lifecycle owner. */ }
                throw failure
            })
            try {
                reopened = reopen(factory)
                assertSame(failure, runCatching { reopened.openHelper.writableDatabase }.exceptionOrNull())
                assertSame(failure, waiter.get(5, TimeUnit.SECONDS))
                assertSame(failure, runCatching { reopened.openHelper.readableDatabase }.exceptionOrNull())
                assertFalse(factory.validated || factory.committed)
                assertFalse("Room's cached callback handle must actually be closed", cached.isOpen)
                assertTrue(runCatching { cached.query("SELECT count(*) FROM metrics").close() }.isFailure)
                assertTrue(runCatching { reopened.metricDao().getMetricById(metric.id) }.isFailure)
                external().use { it.execSQL("UPDATE metrics SET name=name") }
                val next = G0ColdOpenFactory(expected)
                reopen(next).openHelper.writableDatabase
                assertTrue(next.committed)
            } finally { worker.shutdown(); check(worker.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }

    @Test fun cancellationBeforeRoomOpenMustNotEnterFrameworkRetryOrRunCallbacks() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val expected = reference()
            val failure = CancellationException("G0_BEFORE_ROOM_CANCELLED")
            var cancelledAt = 0L
            lateinit var cached: SupportSQLiteDatabase
            val factory = G0ColdOpenFactory(expected, beforeRoomOpen = {
                cached = it
                cancelledAt = android.os.SystemClock.elapsedRealtimeNanos()
                throw failure
            })
            val reopened = reopen(factory)
            assertSame(failure, runCatching { reopened.openHelper.writableDatabase }.exceptionOrNull())
            val duration = android.os.SystemClock.elapsedRealtimeNanos() - cancelledAt
            assertEquals(0, factory.callbacks)
            assertFalse(factory.validated || factory.committed || cached.isOpen)
            assertSame(failure, runCatching { reopened.openHelper.writableDatabase }.exceptionOrNull())
            external().use { it.execSQL("UPDATE metrics SET name=name") }
            val next = G0ColdOpenFactory(expected)
            reopen(next).openHelper.writableDatabase
            assertTrue(next.committed)
            android.util.Log.i("G0ColdRecovery", "kind=before_room_cancel cancellation_ns=$duration")
            assertTrue("G0_COLD_CALLBACK_CANCEL_BUDGET: ${duration / 1_000_000}ms exceeds 100ms", duration <= 100_000_000L)
        }
    }
}
