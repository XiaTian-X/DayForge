package com.dayforge.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual small SQLite snapshots; no restored-account/network fixture and no global hooks.
 * Native fault access is private to this test factory, never returned by a production handle.
 */
@RunWith(AndroidJUnit4::class)
class NextReplicaSchemaSnapshotTest : NextCoreRequestFixture() {
    private enum class Point { NONE, FINAL_COOKIE, BEFORE_RAW_PROOF, FINAL_GENERATION }
    private class Frame(val point: Point = Point.NONE) {
        val parsedReads = AtomicInteger()
        val rawProofReads = AtomicInteger()
        val cookieReads = AtomicInteger()
        val attempted = AtomicBoolean()
        val inTransaction = AtomicBoolean()
        val nativeReturned = AtomicBoolean()
        val cookieBefore = AtomicReference<Long?>()
        val cookieAfter = AtomicReference<Long?>()
        val generationBefore = AtomicReference<Long?>()
        val generationAfter = AtomicReference<Long?>()
        val changedIndex = AtomicReference<String?>()
        val failure = AtomicReference<Throwable?>()
    }
    private val active = AtomicReference<Frame?>()
    private val nativeFaults = NativeFaultFactory()
    private val observer = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            val frame = active.get() ?: return
            val text = sqlQuery.trim().replace("`", "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            if (text == "select type,name,tbl_name,sql from main.sqlite_master order by type,name") frame.parsedReads.incrementAndGet()
            if (text == "select type,name,tbl_name,rootpage,sql from main.sqlite_master order by type,name") frame.rawProofReads.incrementAndGet()
            if (text == "pragma main.schema_version") frame.cookieReads.incrementAndGet()
        }
    }
    init {
        storage.isolatedBuilder = { context ->
            val factory = CountTrustOpenHelperFactory(context, nativeFaults)
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .addMigrations(CountTrustMigration, CountCommandMigration).openHelperFactory(factory).addCallback(SyncSchemaCallback)
                .setQueryCallback(object : RoomDatabase.QueryCallback {
                    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) =
                        factory.observeWithoutCapabilities { observer.onQuery(sqlQuery, bindArgs) }
                }, java.util.concurrent.Executor { it.run() }).build().also { CountTrustRuntime.attach(it, factory) }
        }
    }

    /** Fixed native fault below the controlled wrapper; never returns its raw handle. */
    private inner class NativeFaultFactory : androidx.sqlite.db.SupportSQLiteOpenHelper.Factory {
        private var original: androidx.sqlite.db.SupportSQLiteDatabase? = null
        private var wrapped: androidx.sqlite.db.SupportSQLiteDatabase? = null
        private fun wrap(raw: androidx.sqlite.db.SupportSQLiteDatabase): androidx.sqlite.db.SupportSQLiteDatabase {
            if (original !== raw) {
                original = raw
                wrapped = object : androidx.sqlite.db.SupportSQLiteDatabase by raw {

                    private fun beforeQuery(text: String) {
                        val frame = active.get() ?: return
                        val query = text.trim().replace("`", "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                        val finalCookie = query == "pragma main.schema_version" && frame.cookieReads.get() == 2
                        val rawBoundary = query == "select coalesce(length(cast(sql as blob)),0) from main.sqlite_master" &&
                            frame.parsedReads.get() == 1
                        val inject = when (frame.point) {
                            Point.FINAL_COOKIE, Point.FINAL_GENERATION -> finalCookie
                            Point.BEFORE_RAW_PROOF -> rawBoundary
                            Point.NONE -> false
                        }
                        if (!inject || !frame.attempted.compareAndSet(false, true)) return
                        frame.failure.set(runCatching {
                            val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
                            frame.generationBefore.set(runtime.generation)
                            frame.inTransaction.set(db.inTransaction() && raw.inTransaction())
                            check(frame.inTransaction.get())
                            fun cookie() = raw.query("PRAGMA main.schema_version").use { check(it.moveToFirst()); it.getLong(0) }
                            frame.cookieBefore.set(cookie())
                            if (frame.point == Point.FINAL_GENERATION) {
                                // Ordinary controlled DDL is legal here, outside the observer. It must
                                // genuinely dirty/retire the owner while keeping the schema cookie.
                                db.openHelper.writableDatabase.execSQL("REINDEX index_metrics_uuid")
                            } else {
                                // Fixed native index damage isolates schema-cookie checking from owner retirement.
                                raw.execSQL("DROP INDEX index_metrics_uuid")
                                raw.execSQL("CREATE INDEX index_metrics_uuid ON metrics(uuid COLLATE NOCASE DESC)")
                            }
                            frame.cookieAfter.set(cookie())
                            raw.query("SELECT sql FROM main.sqlite_master WHERE type='index' AND name='index_metrics_uuid'").use {
                                check(it.moveToFirst()); frame.changedIndex.set(it.getString(0))
                            }
                            frame.nativeReturned.set(true)
                            frame.generationAfter.set(runtime.generation)
                        }.exceptionOrNull())
                    }
                    override fun query(query: String): android.database.Cursor {
                        beforeQuery(query); return raw.query(query)
                    }
                    override fun query(query: String, bindArgs: Array<out Any?>): android.database.Cursor {
                        beforeQuery(query); return raw.query(query, bindArgs)
                    }
                    override fun query(query: androidx.sqlite.db.SupportSQLiteQuery): android.database.Cursor {
                        beforeQuery(query.sql); return raw.query(query)
                    }
                    override fun query(query: androidx.sqlite.db.SupportSQLiteQuery,
                        cancellationSignal: android.os.CancellationSignal?): android.database.Cursor {
                        beforeQuery(query.sql); return raw.query(query, cancellationSignal)
                    }

                }
            }
            return requireNotNull(wrapped)
        }
        override fun create(configuration: androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration): androidx.sqlite.db.SupportSQLiteOpenHelper {
            val parent = configuration.callback
            val callbacks = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: androidx.sqlite.db.SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: androidx.sqlite.db.SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val native = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                    .name(configuration.name).callback(callbacks).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : androidx.sqlite.db.SupportSQLiteOpenHelper by native {
                override val writableDatabase get() = wrap(native.writableDatabase)
                override val readableDatabase get() = wrap(native.readableDatabase)
            }
        }
    }

    /** Independent previous hash framing, obtained from actual SQLite objects. Never use
     * the candidate's owned-object filter or new schemaHash(version, objects) overload.
     */
    private suspend fun observedHash(sql: SupportSQLiteDatabase): String {
        val version = sql.version
        val objects = ReplicaSqlSchema.read(sql)
        return syncPayloadHash(buildJsonObject {
            put("version", version)
            put("objects", buildJsonObject {
                for ((key, item) in objects.toSortedMap()) put(key, buildJsonObject {
                    put("type", item.type); put("name", item.name); put("owner", item.owner)
                    put("definition", JsonArray(item.definition.map { JsonArray(it.map(::JsonPrimitive)) }))
                })
            })
        }.toString())
    }
    private suspend fun currentHash() = db.withTransaction { observedHash(db.openHelper.writableDatabase) }

    /** Real KSP18 source, with all old native rows/rowids retained before production migration. */
    private suspend fun previousHash(): String = ReplicaV18SourceFixture.withDatabase(app, storage) { sql ->
        assertEquals(18, sql.version)
        sql.query("SELECT identity_hash FROM main.room_master_table WHERE id=42").use {
            assertTrue(it.moveToFirst()); assertEquals(NextReplicaV18StorageView.PREVIOUS_ROOM_IDENTITY, it.getString(0))
            assertFalse(it.moveToNext())
        }
        observedHash(sql)
    }.also { db.openHelper.writableDatabase }

    private suspend fun observedMatch(database: HabitDatabase, expected: String, frame: Frame): Boolean {
        assertTrue(active.compareAndSet(null, frame))
        return try { NextReplicaCompatibilityViews.matchesSchema(database, expected) }
        finally { active.set(null) }
    }
    private suspend fun rejectedChange(expected: String, point: Point): Frame {
        val before = currentHash(); val frame = Frame(point)
        val error = runCatching { observedMatch(db, expected, frame) }.exceptionOrNull()
        assertTrue("Reentrant DDL must actually be attempted", frame.attempted.get())
        assertNull("Native fault setup must not hide an early failure", frame.failure.get())
        assertTrue(frame.inTransaction.get()); assertTrue(frame.nativeReturned.get())
        assertTrue("A stable source parse must precede mutation", frame.parsedReads.get() == 1)
        assertEquals("The final stamp must be reached", 2, frame.cookieReads.get())
        assertEquals("SYNC_REPLICA_SCHEMA_CHANGED", error?.message)
        assertTrue(error is IllegalStateException)
        assertEquals("Rejected same-transaction DDL must roll back", before, currentHash())
        return frame
    }

    @Test fun currentEarlyMatchAndFalseResultBothRejectChangedNativeCookieAtFinalRead() = runBlocking<Unit> {
        val expected = currentHash()
        for (hash in listOf(expected, "0".repeat(64))) {
            val frame = rejectedChange(hash, Point.FINAL_COOKIE)
            assertEquals(frame.generationBefore.get(), frame.generationAfter.get())
            assertNotEquals(frame.cookieBefore.get(), frame.cookieAfter.get())
            assertTrue(requireNotNull(frame.changedIndex.get()).contains("NOCASE DESC"))
            assertEquals(if (hash == expected) 0 else 1, frame.rawProofReads.get())
        }
    }

    @Test fun v18FirstParsedSchemaCannotOutliveAChangedRetainedIndexBeforeRawProof() = runBlocking<Unit> {
        val expected = previousHash()
        val before = currentHash(); val frame = Frame(Point.BEFORE_RAW_PROOF)
        val error = runCatching { observedMatch(db, expected, frame) }.exceptionOrNull()
        assertTrue("The original native index fault must execute", frame.attempted.get())
        assertNull("Fault setup cannot be the rejection", frame.failure.get())
        assertTrue(frame.inTransaction.get()); assertTrue(frame.nativeReturned.get())
        assertEquals(1, frame.parsedReads.get()); assertEquals(1, frame.rawProofReads.get())
        // The real20->19 bridge authenticates this raw schema before its later cookie check.
        // Keep the stronger first rejection; the current-schema cases above still reach both cookies.
        assertTrue(error is IllegalArgumentException)
        assertEquals("COMMAND_PRIMARY_BINDING_CHANGED", error?.message)
        assertTrue(requireNotNull(error).stackTrace.any { it.className.startsWith("com.dayforge.data.repository.CountCommandSeal") })
        assertTrue(error.stackTrace.any { it.className == "com.dayforge.data.repository.NextReplicaV19StorageView" })
        assertEquals("Primary rejection precedes the final cookie", 1, frame.cookieReads.get())
        assertEquals("The real same-transaction index change must roll back", before, currentHash())
        assertEquals(frame.generationBefore.get(), frame.generationAfter.get())
        assertNotEquals(frame.cookieBefore.get(), frame.cookieAfter.get())
        assertTrue(requireNotNull(frame.changedIndex.get()).contains("NOCASE DESC"))
    }

    @Test fun runtimeRetirementAtFinalCookieRejectsEvenWhenNativeCookieDoesNotChange() = runBlocking<Unit> {
        val frame = rejectedChange(currentHash(), Point.FINAL_GENERATION)
        assertEquals(frame.cookieBefore.get(), frame.cookieAfter.get())
        assertTrue(requireNotNull(frame.generationAfter.get()) > requireNotNull(frame.generationBefore.get()))
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        assertEquals(0, frame.rawProofReads.get())
    }

    @Test fun queryWrappedSoftRetiredOwnerUsesOneActualParsedReadAndKeepsTheCompleteRawProof() = runBlocking<Unit> {
        val legacy = previousHash(); val current = currentHash()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        assertFalse("Room's observer wrapper is intentionally outside the native boundary",
            CountTrustRuntime.isControlledDatabase(db.openHelper.writableDatabase))
        db.withTransaction { owner.maintenance(db) { owner.retireFastPathForRecovery() } }
        for (expected in listOf(current, legacy)) {
            val frame = Frame()
            assertTrue(observedMatch(db, expected, frame))
            assertEquals(1, frame.parsedReads.get()); assertEquals(2, frame.cookieReads.get())
            assertEquals(if (expected == current) 0 else 1, frame.rawProofReads.get())
            assertTrue(owner.isRetired); assertSame(owner, CountTrustRuntime.forDatabase(db))
        }
    }

    @Test fun unownedQueryWrappedConnectionRetainsTheOriginalTwoIndependentParsedReads() = runBlocking<Unit> {
        val expected = previousHash()
        check(!db.inTransaction()); db.close(); HabitDatabaseProvider.clearInstanceForTesting()
        val unowned = Room.databaseBuilder(app, HabitDatabase::class.java, "habit_database")
            .addCallback(SyncSchemaCallback).setQueryCallback(observer, java.util.concurrent.Executor { it.run() }).build()
        try {
            unowned.openHelper.writableDatabase
            assertNull(CountTrustRuntime.forDatabase(unowned))
            val frame = Frame()
            assertTrue(observedMatch(unowned, expected, frame))
            assertEquals(2, frame.parsedReads.get()); assertEquals(1, frame.rawProofReads.get())
            assertEquals(0, frame.cookieReads.get())
        } finally { unowned.close(); storage.reopen() }
    }

    @Test fun changedCookieCannotBeResetByAssignmentOrParenthesizedCompiledPragma() = runBlocking<Unit> {
        val sql = db.openHelper.writableDatabase
        fun cookie() = sql.query("PRAGMA main.schema_version").use { assertTrue(it.moveToFirst()); it.getLong(0) }
        val before = cookie()
        sql.execSQL("CREATE INDEX snapshot_cookie_probe ON metrics(name)")
        val changed = cookie(); assertNotEquals(before, changed)
        // Existing CountTrustBoundaryTest retains the full EXPLAIN/all-entry matrix. This
        // regression covers resetting the particular cookie captured before a real DDL.
        for (reset in listOf("PRAGMA main.schema_version=$before", "PRAGMA main.schema_version($before)")) {
            val direct = runCatching { sql.execSQL(reset) }.exceptionOrNull()
            assertEquals("COUNT_TRUST_PRAGMA_DENIED", direct?.message)
            val compiled = sql.compileStatement(reset)
            try {
                val error = runCatching { compiled.execute() }.exceptionOrNull()
                assertEquals("COUNT_TRUST_PRAGMA_DENIED", error?.message)
            } finally { compiled.close() }
            assertEquals(changed, cookie())
        }
    }
}
