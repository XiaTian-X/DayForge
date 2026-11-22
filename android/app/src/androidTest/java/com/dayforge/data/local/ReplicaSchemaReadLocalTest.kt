package com.dayforge.data.local

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.NextReplicaStorageProof
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real controlled Room transactions. The private native spy is BELOW the production
 * wrapper; the outer interface wrapper deliberately retains the original reader path.
 * Fixed malformed metadata is observation-only and never becomes a production hook. */
@RunWith(AndroidJUnit4::class)
class ReplicaSchemaReadLocalTest {
    private val spy = NativeSpy()
    @get:Rule val database = PhysicalDatabaseRule().apply {
        isolatedBuilder = { context ->
            val factory = CountTrustOpenHelperFactory(context, spy)
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .openHelperFactory(factory).addCallback(SyncSchemaCallback).build()
                .also { CountTrustRuntime.attach(it, factory) }
        }
    }
    private val db get() = database.database
    private val sql get() = db.openHelper.writableDatabase
    private fun fallback(): SupportSQLiteDatabase = object : SupportSQLiteDatabase by sql {}.also {
        assertFalse(CountTrustRuntime.isControlledDatabase(it))
    }
    private fun controlled() {
        assertTrue(CountTrustRuntime.isControlledDatabase(sql))
        assertTrue(db.inTransaction()); assertTrue(sql.inTransaction())
    }
    private suspend fun prepare() = db.withTransaction {
        controlled()
        // Private native fixture only; no schema capability is granted to application code.
        spy.exec("CREATE TABLE schema_probe(id INTEGER PRIMARY KEY, label TEXT NOT NULL, n INTEGER UNIQUE)")
        spy.exec("CREATE INDEX aaa_schema_a ON schema_probe(label COLLATE NOCASE DESC)")
        spy.exec("CREATE INDEX aaa_schema_z ON schema_probe(n)")
        spy.exec("CREATE TABLE schema_other(id INTEGER PRIMARY KEY, label TEXT)")
        spy.exec("CREATE INDEX aaa_schema_other ON schema_other(label)")
    }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private data class Read(val objects: Map<String, ReplicaSqlSchema.SchemaObject>, val queries: List<String>)
    private suspend fun observed(useFallback: Boolean): Read {
        controlled(); spy.arm()
        return try { Read(ReplicaSqlSchema.read(if (useFallback) fallback() else sql), spy.queries.toList()) }
        finally { spy.disarm() }
    }
    private fun checkQueries(read: Read, useFallback: Boolean) {
        val indices = read.objects.values.filter { it.type == "index" }
        assertTrue(indices.any { it.name == "aaa_schema_a" })
        assertTrue(indices.any { it.name.startsWith("sqlite_autoindex_schema_probe_") })
        assertEquals(if (useFallback) indices.size else 0, read.queries.count { it == OWNER })
        val owners = indices.groupingBy { it.owner }.eachCount()
        for ((owner, count) in owners) {
            assertEquals("Complete index_list for $owner", if (useFallback) count else 1,
                read.queries.count { it.lowercase(Locale.ROOT) == "pragma main.index_list(`$owner`)" })
        }
        for (index in indices) assertEquals("Every index_xinfo remains native: ${index.name}", 1,
            read.queries.count { it.lowercase(Locale.ROOT) == "pragma main.index_xinfo(`${index.name}`)" })
        assertEquals(1, read.queries.count { it == LENGTHS })
        assertEquals(1, read.queries.count { it == MEMBERS })
    }
    private data class Rejection(val type: Class<out Throwable>, val message: String?, val queries: List<String>, val touched: Boolean)
    private suspend fun reject(fault: Fault, useFallback: Boolean): Rejection {
        controlled(); spy.arm(fault)
        return try {
            var returned = false
            val failure = try { ReplicaSqlSchema.read(if (useFallback) fallback() else sql); returned = true; null }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { error }
            val error = requireNotNull(failure) { "Malformed metadata returned a schema" }
            assertFalse(returned)
            Rejection(error.javaClass, error.message, spy.queries.toList(), spy.touched)
        } finally { spy.disarm() }
    }
    private suspend fun sameRejection(fault: Fault): Pair<Rejection, Rejection> {
        val original = reject(fault, true)
        val local = reject(fault, false)
        assertEquals(original.type, local.type); assertEquals(original.message, local.message)
        if (fault != Fault.NONE) { assertTrue(original.touched); assertTrue(local.touched) }
        return original to local
    }

    @Test(timeout = 150_000) fun controlledReadsReuseOnlyCurrentHeadersAndKeepEveryIndexXinfoFresh() = runBlocking<Unit> {
        prepare()
        assertTrue(CountTrustRuntime.isControlledDatabase(sql))
        assertFalse(db.inTransaction()); assertFalse(sql.inTransaction())
        spy.arm()
        val outside = try { Read(ReplicaSqlSchema.read(sql), spy.queries.toList()) }
            finally { spy.disarm() }
        checkQueries(outside, true) // Controlled identity alone must not enable read-local reuse.
        lateinit var first: Map<String, ReplicaSqlSchema.SchemaObject>
        db.withTransaction {
            val original = observed(true); checkQueries(original, true)
            assertEquals(outside.objects, original.objects)
            val direct = observed(false); checkQueries(direct, false)
            assertEquals(original.objects, direct.objects); first = direct.objects
            val again = observed(false); checkQueries(again, false)
            assertEquals(direct.objects, again.objects); assertEquals(direct.queries, again.queries)
            // main qualification must survive a real same-named TEMP object on this connection.
            spy.exec("CREATE TEMP TABLE schema_probe(id INTEGER, label BLOB)")
            spy.exec("CREATE INDEX temp.aaa_schema_a ON schema_probe(label)")
            try {
                val shadow = observed(false); checkQueries(shadow, false)
                assertEquals(first, shadow.objects)
            } finally { spy.exec("DROP TABLE temp.schema_probe") }
        }
        db.withTransaction {
            val nextTransaction = observed(false); checkQueries(nextTransaction, false)
            assertEquals(first, nextTransaction.objects)
            spy.exec("DROP INDEX main.aaa_schema_a")
            spy.exec("CREATE INDEX aaa_schema_a ON schema_probe(label COLLATE BINARY ASC)")
            val changed = observed(false); checkQueries(changed, false)
            assertNotEquals(first.getValue("index.aaa_schema_a"), changed.objects.getValue("index.aaa_schema_a"))
            assertEquals(observed(true).objects, changed.objects)
        }
    }

    @Test(timeout = 150_000) fun partialExpressionOwnerAndMalformedHeadersRetainOriginalRefusal() = runBlocking<Unit> {
        prepare(); val before = image()
        db.withTransaction {
            for (fault in listOf(Fault.OWNER, Fault.DUPLICATE, Fault.MISSING,
                Fault.BAD_UNIQUE, Fault.BAD_ORIGIN, Fault.BAD_PARTIAL, Fault.BAD_NAME)) sameRejection(fault)
            for ((definition, reachesXinfo) in listOf(
                "CREATE INDEX aaa_schema_a ON schema_probe(label) WHERE n IS NOT NULL" to false,
                "CREATE INDEX aaa_schema_a ON schema_probe((lower(label)))" to true
            )) {
                spy.exec("DROP INDEX main.aaa_schema_a"); spy.exec(definition)
                try {
                    val (original, local) = sameRejection(Fault.NONE)
                    assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", original.message)
                    for (result in listOf(original, local)) {
                        if (reachesXinfo) assertTrue("Expression rejection must reach index_xinfo", result.queries.contains(XINFO))
                        else assertFalse("Partial index must reject before index_xinfo", result.queries.contains(XINFO))
                    }
                } finally {
                    spy.exec("DROP INDEX main.aaa_schema_a")
                    spy.exec("CREATE INDEX aaa_schema_a ON schema_probe(label COLLATE NOCASE DESC)")
                }
            }
        }
        // DDL replacement changes rootpage/schema cookie; native row values must still agree.
        val after = image()
        assertEquals(before.keys, after.keys)
        for (key in before.keys - setOf("schema")) assertEquals(key, before[key], after[key])
        db.withTransaction { assertEquals(observed(true).objects, observed(false).objects) }
    }

    @Test(timeout = 150_000) fun cachedRowsCannotMoveLaterSiblingRefusalAheadOfOriginalIndexChecks() = runBlocking<Unit> {
        prepare(); val before = image()
        db.withTransaction {
            for (fault in listOf(Fault.EARLY_LEXER_LATE_HEADER, Fault.EARLY_XINFO_LATE_HEADER,
                Fault.EARLY_PARTIAL_LATE_BAD_NAME)) {
                val (original, local) = sameRejection(fault)
                when (fault) {
                    Fault.EARLY_LEXER_LATE_HEADER -> {
                        assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", local.message)
                        for (result in listOf(original, local)) {
                            assertFalse(result.queries.contains(HEADERS)); assertFalse(result.queries.contains(XINFO))
                        }
                    }
                    Fault.EARLY_XINFO_LATE_HEADER -> {
                        assertEquals("SCHEMA_READ_XINFO_SENTINEL", local.message)
                        for (result in listOf(original, local)) assertTrue(result.queries.contains(XINFO))
                    }
                    Fault.EARLY_PARTIAL_LATE_BAD_NAME -> {
                        assertEquals("SYNC_RECOVERY_SCHEMA_INVALID", local.message)
                        for (result in listOf(original, local)) assertFalse(result.queries.contains(XINFO))
                    }
                    else -> error("Unexpected order case")
                }
            }
        }
        assertEquals(before, image())
    }

    @Test(timeout = 150_000) fun cancellationAndOwnerRetirementCannotPublishOrRetainLocalHeaders() = runBlocking<Unit> {
        prepare(); val before = image()
        var published = false
        supervisorScope {
            val task = async {
                db.withTransaction {
                    controlled(); val caller = requireNotNull(currentCoroutineContext()[Job])
                    spy.exec("INSERT INTO schema_probe(id,label,n) VALUES(1,'rollback-cancel',1)")
                    spy.arm(beforeXinfo = { caller.cancel(CancellationException("SCHEMA_READ_CANCEL")) })
                    ReplicaSqlSchema.read(sql); published = true
                }
            }
            val failure = runCatching { task.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        }
        assertTrue(spy.actionReached); spy.disarm(); assertFalse(published)
        assertEquals(before, image())
        db.withTransaction { checkQueries(observed(false), false) }

        val runtime = requireNotNull(CountTrustRuntime.forDatabase(db)); val generation = runtime.generation
        val failure = try {
            db.withTransaction {
                controlled()
                spy.exec("INSERT INTO schema_probe(id,label,n) VALUES(2,'rollback-retire',2)")
                spy.arm(beforeXinfo = { sql.execSQL("REINDEX aaa_schema_a") })
                ReplicaSqlSchema.read(sql); published = true
            }
            null
        } catch (cancel: CancellationException) { throw cancel }
            catch (error: IllegalStateException) { error }
        assertTrue(spy.actionReached); spy.disarm()
        assertNotNull(failure); assertFalse(published)
        assertTrue(requireNotNull(failure).message.orEmpty().startsWith("COUNT_TRUST_"))
        assertTrue(runtime.isRetired); assertTrue(runtime.generation > generation)
        assertEquals(before, image())
        db.withTransaction {
            val fresh = observed(false); checkQueries(fresh, false)
            assertEquals(observed(true).objects, fresh.objects)
        }
    }

    private enum class Fault { NONE, OWNER, DUPLICATE, MISSING, BAD_UNIQUE, BAD_ORIGIN, BAD_PARTIAL,
        BAD_NAME, EARLY_LEXER_LATE_HEADER, EARLY_XINFO_LATE_HEADER, EARLY_PARTIAL_LATE_BAD_NAME }
    private class NativeSpy : SupportSQLiteOpenHelper.Factory {
        private var original: SupportSQLiteDatabase? = null
        private var adapted: SupportSQLiteDatabase? = null
        @Volatile private var active = false
        private var fault = Fault.NONE
        private var beforeXinfo: (() -> Unit)? = null
        var actionReached = false
            private set
        var touched = false
            private set
        val queries = CopyOnWriteArrayList<String>()
        fun arm(fault: Fault = Fault.NONE, beforeXinfo: (() -> Unit)? = null) {
            queries.clear(); touched = false; actionReached = false
            this.fault = fault; this.beforeXinfo = beforeXinfo; active = true
        }
        fun disarm() { active = false; beforeXinfo = null; fault = Fault.NONE }
        fun exec(statement: String) = requireNotNull(original).execSQL(statement)
        private fun copy(cursor: Cursor, change: (MutableList<MutableList<Any?>>) -> Unit): Cursor = cursor.use { value ->
            val names = value.columnNames
            val rows = mutableListOf<MutableList<Any?>>()
            while (value.moveToNext()) {
                val row = mutableListOf<Any?>()
                for (column in 0 until value.columnCount) row.add(when (value.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> value.getLong(column)
                    Cursor.FIELD_TYPE_FLOAT -> value.getDouble(column)
                    Cursor.FIELD_TYPE_STRING -> value.getString(column)
                    Cursor.FIELD_TYPE_BLOB -> value.getBlob(column)
                    else -> error("Native SQLite type")
                })
                rows.add(row)
            }
            change(rows)
            MatrixCursor(names).apply { rows.forEach { addRow(it.toTypedArray()) } }
        }
        private fun query(text: String, read: () -> Cursor): Cursor {
            if (!active) return read()
            queries += text
            if (text == XINFO && !actionReached && beforeXinfo != null) {
                actionReached = true; requireNotNull(beforeXinfo).invoke()
            }
            if (text == XINFO && fault == Fault.EARLY_XINFO_LATE_HEADER) {
                touched = true; throw IllegalStateException("SCHEMA_READ_XINFO_SENTINEL")
            }
            val cursor = read()
            if (text == MEMBERS && fault in setOf(Fault.OWNER, Fault.EARLY_LEXER_LATE_HEADER)) {
                return copy(cursor) { rows ->
                    val row = rows.single { it[0] == "index" && it[1] == "aaa_schema_a" }
                    if (fault == Fault.OWNER) row[2] = "schema_other" else row[3] = "${row[3]} /* invalid */"
                    touched = true
                }
            }
            if (text == OWNER && fault == Fault.OWNER) {
                // aaa_schema_a is the first index in the ordered master enumeration.
                return copy(cursor) { rows -> rows.single()[0] = "schema_other"; touched = true }
            }
            if (text != HEADERS || fault == Fault.NONE || fault == Fault.OWNER) return cursor
            return copy(cursor) { rows ->
                val target = rows.single { it[1] == "aaa_schema_a" }
                val sibling = rows.single { it[1] == "aaa_schema_z" }
                when (fault) {
                    Fault.DUPLICATE -> rows.add(target.toMutableList())
                    Fault.MISSING -> rows.remove(target)
                    Fault.BAD_UNIQUE -> target[2] = "not-an-integer"
                    Fault.BAD_ORIGIN -> target[3] = 17L
                    Fault.BAD_PARTIAL -> target[4] = "not-an-integer"
                    Fault.BAD_NAME -> target[1] = 17L
                    Fault.EARLY_LEXER_LATE_HEADER, Fault.EARLY_XINFO_LATE_HEADER -> sibling[2] = "later-bad-integer"
                    Fault.EARLY_PARTIAL_LATE_BAD_NAME -> {
                        rows.remove(target); rows.add(0, target); target[4] = 1L; sibling[1] = 17L
                    }
                    else -> error("Unexpected fixed header fault")
                }
                touched = true
            }
        }
        private fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (original !== raw) {
                original = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    override fun query(query: String): Cursor = this@NativeSpy.query(query) { raw.query(query) }
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = this@NativeSpy.query(query) { raw.query(query, bindArgs) }
                    override fun query(query: SupportSQLiteQuery): Cursor = this@NativeSpy.query(query.sql) { raw.query(query) }
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                        this@NativeSpy.query(query.sql) { raw.query(query, cancellationSignal) }
                }
            }
            return requireNotNull(adapted)
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val native = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by native {
                override val writableDatabase get() = wrap(native.writableDatabase)
                override val readableDatabase get() = wrap(native.readableDatabase)
            }
        }
    }
    private companion object {
        const val LENGTHS = "SELECT COALESCE(length(CAST(sql AS BLOB)),0) FROM main.sqlite_master"
        const val MEMBERS = "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name"
        const val OWNER = "SELECT tbl_name FROM main.sqlite_master WHERE type='index' AND name=?"
        const val HEADERS = "PRAGMA main.index_list(`schema_probe`)"
        const val XINFO = "PRAGMA main.index_xinfo(`aaa_schema_a`)"
    }
}
