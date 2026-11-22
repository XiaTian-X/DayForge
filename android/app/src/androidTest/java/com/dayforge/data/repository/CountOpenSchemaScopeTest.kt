package com.dayforge.data.repository

import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import java.util.Locale
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed native faults below the real controlled owner. No schema hash/root is fabricated;
 * the warm fixture comes from the real formal COUNT and its original qualification path. */
@RunWith(AndroidJUnit4::class)
class CountOpenSchemaScopeTest : CountMetricSourceReadFixture() {
    private enum class Fault { NONE, AFTER_SCHEMA_DDL, FINAL_COOKIE_DDL, FINAL_COOKIE_RETIRE, AFTER_SCHEMA_CANCEL }
    private class Frame(val fault: Fault = Fault.NONE, val cancel: Job? = null) {
        var schemas = 0; var cookies = 0; var fired = false
        var epochBefore: Long? = null; var epochAfter: Long? = null
        var cookieBefore: Long? = null; var cookieAfter: Long? = null
    }
    private var frame: Frame? = null
    private class Rollback : RuntimeException()
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private val sql get() = db.openHelper.writableDatabase
    init { storage.isolatedBuilder = { HabitDatabaseProvider.build(it, nativeFactory = NativeSpy()) } }

    private inner class NativeSpy : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var wrapped: SupportSQLiteDatabase? = null
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
                if (original !== raw) {
                    original = raw
                    fun read(text: String, query: () -> Cursor): Cursor {
                        val active = frame
                        val normalized = text.trim().replace("`", "").replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
                        val schema = normalized == "select type,name,tbl_name,sql from main.sqlite_master order by type,name"
                        val cookie = normalized == "pragma main.schema_version"
                        if (active != null) { if (schema) active.schemas++; if (cookie) active.cookies++ }
                        if (active != null && cookie && active.cookies == 4 && active.fault == Fault.FINAL_COOKIE_DDL) {
                            check(!active.fired && raw.inTransaction() && db.inTransaction())
                            active.epochBefore = owner.generation
                            active.cookieBefore = raw.query("PRAGMA main.schema_version").use { check(it.moveToFirst()); it.getLong(0) }
                            raw.execSQL("CREATE INDEX schema_scope_late ON metrics(name)")
                            active.cookieAfter = raw.query("PRAGMA main.schema_version").use { check(it.moveToFirst()); it.getLong(0) }
                            active.epochAfter = owner.generation; active.fired = true
                        }
                        val result = query()
                        if (active == null || (!schema && !cookie)) return result
                        return object : CursorWrapper(result) {
                            private var closedOnce = false
                            override fun close() {
                                super.close()
                                if (closedOnce) return
                                closedOnce = true
                                val fire = !active.fired && when (active.fault) {
                                    Fault.AFTER_SCHEMA_DDL, Fault.AFTER_SCHEMA_CANCEL -> schema
                                    Fault.FINAL_COOKIE_RETIRE -> cookie && active.cookies == 4
                                    Fault.FINAL_COOKIE_DDL -> false
                                    Fault.NONE -> false
                                }
                                if (!fire) return
                                check(raw.inTransaction() && db.inTransaction())
                                fun cookieValue() = raw.query("PRAGMA main.schema_version").use {
                                    check(it.moveToFirst()); it.getLong(0)
                                }
                                active.epochBefore = owner.generation; active.cookieBefore = cookieValue()
                                when (active.fault) {
                                    Fault.AFTER_SCHEMA_DDL, Fault.FINAL_COOKIE_DDL ->
                                        raw.execSQL("CREATE INDEX schema_scope_late ON metrics(name)")
                                    Fault.FINAL_COOKIE_RETIRE -> owner.retireFastPathForRecovery()
                                    Fault.AFTER_SCHEMA_CANCEL -> requireNotNull(active.cancel).cancel(CancellationException("SCHEMA_SCOPE_CANCEL"))
                                    Fault.NONE -> error("No fault")
                                }
                                active.epochAfter = owner.generation; active.cookieAfter = cookieValue(); active.fired = true
                            }
                        }
                    }
                    wrapped = object : SupportSQLiteDatabase by raw {
                        override fun query(query: String): Cursor = read(query) { raw.query(query) }
                        override fun query(query: String, bindArgs: Array<out Any?>): Cursor = read(query) { raw.query(query, bindArgs) }
                        override fun query(query: SupportSQLiteQuery): Cursor = read(query.sql) { raw.query(query) }
                        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                            read(query.sql) { raw.query(query, cancellationSignal) }
                    }
                }
                return requireNotNull(wrapped)
            }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val delegate = sourceSpy.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val writableDatabase get() = wrap(delegate.writableDatabase)
                override val readableDatabase get() = wrap(delegate.readableDatabase)
            }
        }
    }
    private suspend fun <T> observed(value: Frame, block: suspend () -> T): T {
        check(frame == null); frame = value
        try { return block() } finally { frame = null }
    }
    private suspend fun rollback(block: suspend () -> Unit) {
        try { db.withTransaction<Unit> { block(); throw Rollback() } } catch (_: Rollback) { }
    }

    @Test(timeout = 150_000) fun oneWarmSchemaReadKeepsRealIndexLiveOutsideMaintenanceAndExpiresAtEnd() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val access = requireNotNull(tokens.localCoreWriteAccess())
        val before = nativeImage().capture(); val sample = Frame()
        lateinit var held: CountTrustKernel.Context
        db.withTransaction { observed(sample) {
            CountTrustKernel(db, tokens, sessions).withOpen(access) { context ->
                held = requireNotNull(context)
                assertEquals("COUNT_TRUST_MAINTENANCE_REQUIRED", runCatching { owner.schemaSnapshot() }.exceptionOrNull()?.message)
                assertTrue(held.eligible(inputs.currentHabit))
                assertEquals(CountTrustValidationEnvironment.current(), CountTrustValidationEnvironment.Snapshot.decode(
                    requireNotNull(held.index.get(CountTrustValidationEnvironment.KEY))))
            }
        } }
        assertEquals(1, sample.schemas); assertEquals(4, sample.cookies)
        assertEquals("COUNT_TRUST_READ_TOKEN_EXPIRED", runCatching { held.eligible(inputs.currentHabit) }.exceptionOrNull()?.message)
        nativeImage().assertUnchanged(before)
    }

    @Test(timeout = 150_000) fun escapedChildJobAndRepeatedConsumptionCannotBorrowWarmHash() = runBlocking<Unit> {
        openMetricScopeInputs(); val access = requireNotNull(tokens.localCoreWriteAccess())
        lateinit var escaped: suspend () -> String?
        db.withTransaction {
            CountCommandSchemaBridge.withOpenSchema(db, access) { pending, current ->
                assertFalse(pending); escaped = current
                supervisorScope {
                    val result = async(start = CoroutineStart.UNDISPATCHED) { rejected { current() } }.await()
                    assertEquals("COUNT_OPEN_SCHEMA_EXPIRED", result.message)
                }
                assertEquals(CountTrustSeal.read(sql)?.schemaHash, current())
                assertEquals("COUNT_OPEN_SCHEMA_CONSUMED", rejected { current() }.message)
            }
            assertEquals("COUNT_OPEN_SCHEMA_EXPIRED", rejected { escaped() }.message)
            CountCommandSchemaBridge.withOpenSchema(db, access) { pending, current ->
                assertFalse(pending)
                assertEquals("COUNT_OPEN_SCHEMA_EXPIRED", rejected { escaped() }.message)
                assertEquals(CountTrustSeal.read(sql)?.schemaHash, current())
            }
        }
        assertEquals("COUNT_OPEN_SCHEMA_EXPIRED", rejected { escaped() }.message)
        db.withTransaction { assertEquals("COUNT_OPEN_SCHEMA_EXPIRED", rejected { escaped() }.message) }
    }

    @Test(timeout = 150_000) fun nativeDdlAfterMaterializationAndAtFinalCookieBothRejectAndRollback() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val access = requireNotNull(tokens.localCoreWriteAccess())
        val before = nativeImage().capture(); val schemaBefore = schema()
        for (fault in listOf(Fault.AFTER_SCHEMA_DDL, Fault.FINAL_COOKIE_DDL)) {
            val sample = Frame(fault); var published = false
            val error = rejected { db.withTransaction { observed(sample) {
                CountTrustKernel(db, tokens, sessions).withOpen(access) { published = true }
            } } }
            assertTrue(sample.fired); assertFalse(published); assertEquals(1, sample.schemas)
            assertEquals(if (fault == Fault.AFTER_SCHEMA_DDL) 2 else 4, sample.cookies)
            assertEquals("COUNT_OPEN_SCHEMA_CHANGED", error.message)
            assertEquals(sample.epochBefore, sample.epochAfter); assertNotEquals(sample.cookieBefore, sample.cookieAfter)
            nativeImage().assertUnchanged(before); assertEquals(schemaBefore, schema())
        }
        assertEquals(1, readSource(inputs, reuse = true).schemas)
        assertMetricScopeRolledBack(inputs)
    }

    @Test(timeout = 150_000) fun softRetirementInsideFinalCookieCannotPublishDespiteUnchangedEpoch() = runBlocking<Unit> {
        openMetricScopeInputs(); val access = requireNotNull(tokens.localCoreWriteAccess())
        val before = nativeImage().capture(); val sample = Frame(Fault.FINAL_COOKIE_RETIRE); var published = false
        val error = rejected { db.withTransaction { observed(sample) {
            CountTrustKernel(db, tokens, sessions).withOpen(access) { published = true }
        } } }
        assertTrue(sample.fired); assertFalse(published); assertTrue(owner.isRetired)
        assertEquals(1, sample.schemas); assertEquals(4, sample.cookies)
        assertEquals(sample.epochBefore, sample.epochAfter); assertEquals(sample.cookieBefore, sample.cookieAfter)
        assertEquals("COUNT_OPEN_SCHEMA_EXPIRED", error.message)
        nativeImage().assertUnchanged(before)
    }

    @Test(timeout = 150_000) fun nullDirtyForeignAndRetiredOpenRemainMissesWithoutAnotherFullSchemaRead() = runBlocking<Unit> {
        openMetricScopeInputs(); val access = requireNotNull(tokens.localCoreWriteAccess())
        val before = nativeImage().capture()
        for (kind in listOf("null", "dirty", "foreign")) {
            val sample = Frame()
            rollback {
                owner.maintenance(db) { when (kind) {
                    "null" -> sql.execSQL("DELETE FROM count_trust_root")
                    "dirty" -> sql.execSQL("UPDATE count_trust_control SET dirty=1 WHERE id=1")
                    else -> Unit
                } }
                val actual = if (kind == "foreign") access.copy(session = access.session.copy(
                    authentication = access.session.authentication.copy(userId = id(9921)))) else access
                observed(sample) { CountTrustKernel(db, tokens, sessions).withOpen(actual) { assertNull(it) } }
            }
            assertEquals(kind, 1, sample.schemas); assertEquals(kind, if (kind == "null") 1 else 2, sample.cookies)
            nativeImage().assertUnchanged(before)
        }
        db.withTransaction { owner.maintenance(db) { owner.retireFastPathForRecovery() } }
        val retired = Frame()
        db.withTransaction { observed(retired) { CountTrustKernel(db, tokens, sessions).withOpen(access) { assertNull(it) } } }
        assertEquals(1, retired.schemas); assertEquals(0, retired.cookies)
        nativeImage().assertUnchanged(before)
    }

    @Test(timeout = 150_000) fun currentBadMacAndGenerationMismatchStayHardCountFailuresAndRollback() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val access = requireNotNull(tokens.localCoreWriteAccess())
        val before = nativeImage().capture()
        for ((change, message) in listOf("UPDATE count_trust_root SET mac=zeroblob(32) WHERE id=1" to "COUNT_TRUST_SEAL_INVALID",
            "UPDATE count_trust_control SET generation=generation+1 WHERE id=1" to "COUNT_TRUST_GENERATION_CHANGED")) {
            val sample = Frame(); var published = false
            val error = rejected { db.withTransaction {
                owner.maintenance(db) { sql.execSQL(change) }
                observed(sample) { CountTrustKernel(db, tokens, sessions).withOpen(access) { published = true } }
            } }
            assertFalse(published); assertEquals(message, error.message)
            assertFalse(error is CountCommandSchemaBridge.Invalid); assertEquals(1, sample.schemas)
            nativeImage().assertUnchanged(before)
        }
        assertEquals(1, readSource(inputs, reuse = true).schemas)
    }

    @Test(timeout = 150_000) fun callbackErrorsAndInspectionCancellationKeepIdentityAndAllowFreshRetry() = runBlocking<Unit> {
        val inputs = openMetricScopeInputs(); val access = requireNotNull(tokens.localCoreWriteAccess())
        val before = nativeImage().capture()
        for (candidate in listOf(false, true)) {
            val noTransaction = rejected {
                if (candidate) CountCommandSchemaBridge.withOpenSchema(db, access) { _, _ -> fail("No transaction") }
                else CountCommandSchemaBridge.pending(db, access)
            }
            assertTrue(noTransaction is CountCommandSchemaBridge.Invalid)
            assertEquals("COMMAND_SCHEMA_TRANSACTION_REQUIRED", noTransaction.cause?.message)
            db.withTransaction { owner.withReadSnapshot(db) {
                val noMaintenance = rejected {
                    if (candidate) CountCommandSchemaBridge.withOpenSchema(db, access) { _, _ -> fail("Read-only lease") }
                    else CountCommandSchemaBridge.pending(db, access)
                }
                assertTrue(noMaintenance is CountCommandSchemaBridge.Invalid)
                assertEquals("COUNT_TRUST_READ_SNAPSHOT_WRITE_DENIED", noMaintenance.cause?.message)
            } }
        }
        val deliberate = IllegalStateException("ORIGINAL_COUNT_CALLBACK")
        assertSame(deliberate, rejected { db.withTransaction {
            CountCommandSchemaBridge.withOpenSchema(db, access) { _, current -> current(); throw deliberate }
        } })
        val cancellation = CancellationException("ORIGINAL_COUNT_CALLBACK_CANCEL")
        assertSame(cancellation, runCatching { db.withTransaction {
            CountCommandSchemaBridge.withOpenSchema(db, access) { _, _ -> throw cancellation }
        } }.exceptionOrNull())
        val sample = supervisorScope {
            var seen: Frame? = null
            val task = async {
                val value = Frame(Fault.AFTER_SCHEMA_CANCEL, requireNotNull(currentCoroutineContext()[Job])); seen = value
                db.withTransaction { observed(value) { CountTrustKernel(db, tokens, sessions).withOpen(access) { fail("Cancelled") } } }
            }
            assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
            requireNotNull(seen)
        }
        assertTrue(sample.fired); assertEquals(1, sample.schemas)
        currentCoroutineContext().ensureActive(); nativeImage().assertUnchanged(before)
        assertEquals(1, readSource(inputs, reuse = true).schemas)
        assertMetricScopeRolledBack(inputs)
    }
}
