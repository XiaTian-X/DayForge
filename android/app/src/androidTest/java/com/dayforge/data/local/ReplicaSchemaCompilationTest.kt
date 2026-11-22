package com.dayforge.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical instrumentation. Pure compilation cases are not native-proof/authorization tests;
 * actual SQLite cases keep the original source queries and mutate real isolated schema/indexes.
 * No caller-supplied source proof, permission, cached native handle or global loader hook.
 */
@RunWith(AndroidJUnit4::class)
class ReplicaSchemaCompilationTest {
    private val ddl = "CREATE TABLE alpha(id INTEGER PRIMARY KEY, label TEXT NOT NULL, n INTEGER UNIQUE)"
    private fun original(type: String, name: String, sql: String): List<List<String>> {
        val tokens = ReplicaSchemaSql.tokens(sql)
        return if (type == "table") ReplicaSchemaSql.table(tokens, name) else listOf(tokens)
    }
    private fun failure(block: () -> Unit): Throwable = runCatching(block).exceptionOrNull()
        ?: throw AssertionError("Expected original strict rejection")

    @Test(timeout = 150_000) fun exactFullInputsAndDeepImmutableDefinitionsDoNotSubstituteOtherNamesTypesOrSql() {
        val cache = ReplicaSchemaCompilationCache()
        val compiled = cache.compile("table", "alpha", ddl)
        assertEquals(original("table", "alpha", ddl), compiled)
        assertSame(compiled, cache.compile("table", "alpha", ddl))
        val spaced = cache.compile("table", "alpha", "$ddl ")
        assertNotSame("Complete SQL, not normalized text, is the key", compiled, spaced)
        assertEquals(compiled, spaced)
        val lexical = cache.compile("index", "alpha", ddl)
        assertNotSame(compiled, lexical); assertEquals(original("index", "alpha", ddl), lexical)
        val viewSql = "CREATE VIEW alpha AS SELECT 'Case-Sensitive; Literal'"
        val named = cache.compile("view", "alpha", viewSql)
        assertNotSame(named, cache.compile("view", "other", viewSql))
        assertTrue(failure { (compiled as MutableList<List<String>>).clear() } is UnsupportedOperationException)
        assertTrue(failure { (compiled.first() as MutableList<String>).add("changed") } is UnsupportedOperationException)
        assertEquals(original("table", "alpha", ddl), cache.compile("table", "alpha", ddl))
        val before = cache.usage()
        val expected = failure { original("table", "other", ddl) }
        val actual = failure { cache.compile("table", "other", ddl) }
        assertEquals(expected.javaClass, actual.javaClass); assertEquals(expected.message, actual.message)
        assertEquals(before, cache.usage())
    }

    @Test(timeout = 150_000) fun entryAndRetainedChargeBoundsEvictWithoutRejectingOversizedValidDefinitions() {
        val byCount = ReplicaSchemaCompilationCache(maxEntries = 2)
        val a = byCount.compile("table", "alpha", ddl)
        val bSql = ddl.replace("alpha", "bravo")
        val cSql = ddl.replace("alpha", "charlie")
        byCount.compile("table", "bravo", bSql); byCount.compile("table", "charlie", cSql)
        assertEquals(2, byCount.usage().entries)
        assertNotSame(a, byCount.compile("table", "alpha", ddl))
        val measure = ReplicaSchemaCompilationCache()
        measure.compile("table", "alpha", ddl)
        val one = measure.usage().retainedCharge
        val weighted = ReplicaSchemaCompilationCache(maxEntries = 8, maxRetainedCharge = one, maxEntryCharge = one)
        val first = weighted.compile("table", "alpha", ddl)
        weighted.compile("table", "bravo", bSql)
        assertEquals(1, weighted.usage().entries); assertTrue(weighted.usage().retainedCharge <= one)
        assertNotSame(first, weighted.compile("table", "alpha", ddl))
        val noRoom = ReplicaSchemaCompilationCache(maxRetainedCharge = 1, maxEntryCharge = 1)
        val valid = ddl + " ".repeat(80_000)
        val uncached = noRoom.compile("table", "alpha", valid)
        assertEquals(original("table", "alpha", valid), uncached)
        assertNotSame(uncached, noRoom.compile("table", "alpha", valid))
        assertEquals(ReplicaSchemaCompilationCache.Usage(0, 0), noRoom.usage())
        for (invalid in listOf("CREATE TABLE alpha(id INTEGER", "$ddl -- comment", "CREATE VIEW alpha AS SELECT 'open")) {
            val state = byCount.usage()
            val old = failure { original("table", "alpha", invalid) }
            val next = failure { byCount.compile("table", "alpha", invalid) }
            assertEquals(old.javaClass, next.javaClass); assertEquals(old.message, next.message)
            assertEquals(state, byCount.usage())
        }
    }

    @Test(timeout = 150_000) fun cancellationBeforePublicationAndOnWarmHitCannotDeliverOrRetainFailedWork() = runBlocking<Unit> {
        val cold = ReplicaSchemaCompilationCache()
        val delivered = java.util.concurrent.atomic.AtomicBoolean()
        supervisorScope {
            val job = async(Dispatchers.Default) {
                val caller = currentCoroutineContext(); var checkpoints = 0
                cold.compile("table", "alpha", ddl) {
                    if (++checkpoints == 2) caller.cancel(CancellationException("SCHEMA_COMPILE_CANCEL"))
                    caller.ensureActive()
                }.also { delivered.set(true) }
            }
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        }
        assertFalse(delivered.get()); assertEquals(ReplicaSchemaCompilationCache.Usage(0, 0), cold.usage())
        val good = cold.compile("table", "alpha", ddl)
        supervisorScope {
            val job = async(Dispatchers.Default) {
                val caller = currentCoroutineContext()
                caller.cancel(CancellationException("SCHEMA_CACHE_HIT_CANCEL"))
                cold.compile("table", "alpha", ddl) { caller.ensureActive() }.also { delivered.set(true) }
            }
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        }
        assertFalse(delivered.get()); assertSame(good, cold.compile("table", "alpha", ddl))
        val concurrent = ReplicaSchemaCompilationCache()
        val values = (1..8).map { async(Dispatchers.Default) { concurrent.compile("table", "alpha", ddl) } }.awaitAll()
        values.forEach { assertSame(values.first(), it) }
        assertEquals(1, concurrent.usage().entries)
    }

    private suspend fun <T> native(block: suspend (SupportSQLiteDatabase) -> T): T {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        check(app.packageName == "com.dayforge.testbed")
        val name = "schema-compile-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(ddl)
                    db.execSQL("CREATE INDEX alpha_label ON alpha(label COLLATE NOCASE DESC)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        return try {
            val sql = helper.writableDatabase
            sql.beginTransaction()
            try { block(sql) } finally { sql.endTransaction() } // Every injected DDL rolls back.
        } finally { helper.close(); assertTrue(app.deleteDatabase(name)) }
    }
    private class Observed(private val native: SupportSQLiteDatabase) : SupportSQLiteDatabase by native {
        val queries = mutableListOf<String>()
        override fun query(query: String): Cursor { queries += query; return native.query(query) }
        override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
            queries += query; return native.query(query, bindArgs)
        }
    }

    @Test(timeout = 150_000) fun repeatedRealSchemaReadsStillReadEveryMemberLengthTypeAndIndexPragma() = runBlocking<Unit> {
        native { sql ->
            val observed = Observed(sql)
            val before = ReplicaSqlSchema.read(observed); val first = observed.queries.toList()
            observed.queries.clear()
            val after = ReplicaSqlSchema.read(observed)
            assertEquals(before, after); assertEquals(first, observed.queries)
            assertTrue(first.any { it == "SELECT COALESCE(length(CAST(sql AS BLOB)),0) FROM main.sqlite_master" })
            assertTrue(first.any { it == "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name" })
            for (index in listOf("alpha_label", "sqlite_autoindex_alpha_1")) {
                assertTrue(first.any { it == "PRAGMA main.index_xinfo(`$index`)" })
            }
            assertEquals(2, first.count { it == "PRAGMA main.index_list(`alpha`)" })
            assertEquals(2, first.count { it == "SELECT tbl_name FROM main.sqlite_master WHERE type='index' AND name=?" })
        }
    }

    @Test(timeout = 150_000) fun actualWarmTableMutationMalformedNativeTypeAndOversizeDdlCannotReuseOldResult() = runBlocking<Unit> {
        native { sql ->
            val before = ReplicaSqlSchema.read(sql)
            sql.execSQL("ALTER TABLE alpha ADD COLUMN extra TEXT")
            val changed = ReplicaSqlSchema.read(sql)
            assertNotEquals(before.getValue("table.alpha"), changed.getValue("table.alpha"))
            val actualDdl = sql.query("SELECT sql FROM sqlite_master WHERE name='alpha'").use { assertTrue(it.moveToFirst()); it.getString(0) }
            sql.execSQL("PRAGMA writable_schema=ON")
            try {
                sql.execSQL("UPDATE sqlite_master SET sql=CAST(sql AS BLOB) WHERE name='alpha'")
                assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", runCatching { ReplicaSqlSchema.read(sql) }.exceptionOrNull()?.message)
                sql.execSQL("UPDATE sqlite_master SET sql=? WHERE name='alpha'", arrayOf(actualDdl))
                assertEquals(changed, ReplicaSqlSchema.read(sql))
                sql.execSQL("UPDATE sqlite_master SET sql=? WHERE name='alpha'", arrayOf(actualDdl + " ".repeat(262_145)))
                val observed = Observed(sql)
                assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", runCatching { ReplicaSqlSchema.read(observed) }.exceptionOrNull()?.message)
                assertFalse("Original length preflight must precede full schema text materialization",
                    observed.queries.any { it == "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name" })
                sql.execSQL("UPDATE sqlite_master SET sql=? WHERE name='alpha'", arrayOf(actualDdl))
            } finally { sql.execSQL("PRAGMA writable_schema=OFF") }
            assertEquals(changed, ReplicaSqlSchema.read(sql))
            sql.execSQL("CREATE VIEW new_member AS SELECT id FROM alpha")
            assertTrue(ReplicaSqlSchema.read(sql).containsKey("view.new_member"))
        }
    }

    @Test(timeout = 150_000) fun realIndexDirectionCollationAndInvalidIndexLexingRemainFreshAfterWarmCompile() = runBlocking<Unit> {
        native { sql ->
            val before = ReplicaSqlSchema.read(sql)
            sql.execSQL("DROP INDEX alpha_label")
            sql.execSQL("CREATE INDEX alpha_label ON alpha(label COLLATE BINARY ASC)")
            val observed = Observed(sql)
            val changed = ReplicaSqlSchema.read(observed)
            assertNotEquals(before.getValue("index.alpha_label"), changed.getValue("index.alpha_label"))
            assertTrue(observed.queries.contains("PRAGMA main.index_list(`alpha`)"))
            assertTrue(observed.queries.contains("PRAGMA main.index_xinfo(`alpha_label`)"))
            val ddl = sql.query("SELECT sql FROM sqlite_master WHERE name='alpha_label'").use { assertTrue(it.moveToFirst()); it.getString(0) }
            sql.execSQL("PRAGMA writable_schema=ON")
            try {
                sql.execSQL("UPDATE sqlite_master SET sql=? WHERE name='alpha_label'", arrayOf(ddl + " /* unsupported */"))
                observed.queries.clear()
                assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", runCatching { ReplicaSqlSchema.read(observed) }.exceptionOrNull()?.message)
                assertFalse("Index lexer still rejects before its original native PRAGMA", observed.queries.contains("PRAGMA main.index_xinfo(`alpha_label`)"))
                sql.execSQL("UPDATE sqlite_master SET sql=? WHERE name='alpha_label'", arrayOf(ddl))
            } finally { sql.execSQL("PRAGMA writable_schema=OFF") }
            assertEquals(changed, ReplicaSqlSchema.read(sql))
        }
    }
}
