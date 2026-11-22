package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real isolated SQLite differential and actual controlled Room onOpen coverage.
 * Counts are actual fixed fence CREATE attempts, never substitutes for schema/row assertions.
 */
@RunWith(AndroidJUnit4::class)
class NextReplicaFenceInstallTest {
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    private val creates = AtomicInteger()
    private val app get() = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.dayforge.testbed")
    }
    private val db get() = storage.database

    private fun isFence(sql: String) = sql.trimStart().startsWith("CREATE TRIGGER IF NOT EXISTS replica_fence_")
    private inner class CountingFactory : SupportSQLiteOpenHelper.Factory {
        private var source: SupportSQLiteDatabase? = null
        private var safe: SupportSQLiteDatabase? = null
        private fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (source !== raw) {
                source = raw
                safe = object : SupportSQLiteDatabase by raw {
                    override fun execSQL(sql: String) {
                        if (isFence(sql)) creates.incrementAndGet()
                        raw.execSQL(sql)
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        if (isFence(sql)) creates.incrementAndGet()
                        raw.execSQL(sql, bindArgs)
                    }
                }
            }
            return requireNotNull(safe)
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
    init { storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, CountingFactory()) } }

    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private fun schemaName(row: JsonElement) = row.jsonObject.getValue("name").jsonObject.getValue("value").jsonPrimitive.content

    @Test fun actualControlledColdOpenSkipsExistingDdlAndPreservesEveryNativeValue() = runBlocking<Unit> {
        val before = image()
        val expected = before.contents.getValue("schema").jsonArray.count {
            schemaName(it).startsWith("replica_fence_") && it.jsonObject.getValue("type").jsonObject.getValue("value").jsonPrimitive.content == "trigger"
        }
        assertEquals(20, db.openHelper.writableDatabase.version)
        assertEquals(135, expected)
        // onCreate installs once; the immediate onOpen may not issue them a second time.
        assertEquals(expected, creates.get())
        creates.set(0)
        storage.reopen()
        val after = image()
        assertEquals(0, creates.get())
        assertEquals(before.hash, after.hash)
        assertEquals(before.contents, after.contents)
        android.util.Log.i("FenceInstall", "fresh_guards=$expected cold_existing_creates=${creates.get()}")
    }

    @Test fun actual20ColdOpenRefusesUnknownSchemaBeforeInstallingAnyGuardOrResigningRoot() = runBlocking<Unit> {
        image()
        db.openHelper.writableDatabase.execSQL("CREATE TABLE install_probe(id INTEGER PRIMARY KEY,payload BLOB)")
        val before = db.withTransaction { nativeImage(db.openHelper.writableDatabase) }
        creates.set(0); storage.reopen()
        val failure = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it.message == "COMMAND_PRIMARY_BINDING_CHANGED" })
        assertEquals(0, creates.get()); db.close()
        assertEquals(before, rawImage())
        assertTrue(before.schema.none { it[1].value == "replica_fence_install_probe_insert" })
    }

    @Test fun actual20ColdOpenRefusesWrongDefinitionWithoutRepairingOrDiscardingPrimaryState() = runBlocking<Unit> {
        image(); db.close()
        val altered = "CREATE TRIGGER replica_fence_completions_insert BEFORE INSERT ON completions BEGIN SELECT 1; END"
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TRIGGER replica_fence_completions_insert"); raw.execSQL(altered)
        }
        val before = rawImage()
        assertTrue(before.schema.any { it[1].value == "replica_fence_completions_insert" && it[4].value == altered })
        creates.set(0); storage.reopen()
        val failure = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it.message == "COMMAND_PRIMARY_BINDING_CHANGED" })
        assertEquals(0, creates.get()); db.close(); assertEquals(before, rawImage())
    }

    private fun rawImage(): NativeImage = SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,
        null, SQLiteDatabase.OPEN_READONLY).use { raw ->
        fun read(query: String): List<List<Cell>> = raw.rawQuery(query, null).use { c -> buildList {
            while (c.moveToNext()) add(List(c.columnCount) { at ->
                val type = c.getType(at)
                Cell(type, when (type) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(at)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(at).toRawBits()
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(at).toList()
                    Cursor.FIELD_TYPE_STRING -> c.getString(at)
                    else -> error("Native type")
                })
            })
        } }
        val schema = read("SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")
        val tables = schema.filter { it[0].value == "table" }.map { it[1].value as String }
        NativeImage(raw.version, schema, tables.associateWith {
            require(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))); read("SELECT rowid,* FROM main.`$it` ORDER BY rowid")
        })
    }

    private data class Cell(val type: Int, val value: Any?)
    private data class NativeImage(val version: Int, val schema: List<List<Cell>>, val rows: Map<String, List<List<Cell>>>)
    private fun rows(sql: SupportSQLiteDatabase, query: String): List<List<Cell>> = sql.query(query).use { c -> buildList {
        while (c.moveToNext()) add(List(c.columnCount) { index ->
            val type = c.getType(index)
            Cell(type, when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toRawBits()
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(index).toList()
                Cursor.FIELD_TYPE_STRING -> c.getString(index)
                else -> error("Unexpected native type")
            })
        })
    } }
    private fun nativeImage(sql: SupportSQLiteDatabase): NativeImage {
        val schema = rows(sql, "SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")
        val tables = schema.filter { it[0].value == "table" }.map { it[1].value as String }
        return NativeImage(sql.version, schema, tables.associateWith {
            check(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
            rows(sql, "SELECT rowid,* FROM main.`$it` ORDER BY rowid")
        })
    }
    private fun <T> raw(fence: Boolean = true, block: (SupportSQLiteDatabase) -> T): T {
        val name = "fence-install-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE alpha(id INTEGER PRIMARY KEY,payload BLOB,number REAL,note TEXT)")
                    db.execSQL("CREATE TABLE beta(id INTEGER PRIMARY KEY,payload TEXT)")
                    db.execSQL("CREATE TABLE count_trust_control(id INTEGER PRIMARY KEY,storageId TEXT,generation INTEGER,dirty INTEGER)")
                    db.execSQL("INSERT INTO alpha VALUES(1,X'007fff',-0.0,NULL)")
                    db.execSQL("INSERT INTO beta VALUES(1,'unchanged')")
                    db.execSQL("INSERT INTO count_trust_control VALUES(1,'aa310000-0000-4000-8000-000000000001',0,0)")
                    if (fence) db.execSQL("CREATE TABLE next_replica_fence (recoveryId TEXT NOT NULL, accountId TEXT NOT NULL, serverInstanceId TEXT NOT NULL, oldEpoch TEXT NOT NULL, newEpoch TEXT NOT NULL, authenticationGeneration TEXT NOT NULL, deviceId TEXT NOT NULL, capabilityRevision INTEGER NOT NULL, sourceHash TEXT NOT NULL, mode TEXT NOT NULL, id INTEGER NOT NULL, PRIMARY KEY(id))")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        return try { helper.use { block(it.writableDatabase) } } finally { check(app.deleteDatabase(name)) }
    }
    private class Trace(private val raw: SupportSQLiteDatabase) : SupportSQLiteDatabase by raw {
        val ddl = mutableListOf<String>()
        var begins = 0; var ends = 0; var successes = 0
        var failAt = Int.MAX_VALUE
        var loseEnd = false
        override fun execSQL(sql: String) {
            ddl += sql
            if (ddl.size == failAt) error("FIXED_FENCE_CREATE_FAILURE")
            raw.execSQL(sql)
        }
        override fun beginTransaction() { begins++; raw.beginTransaction() }
        override fun setTransactionSuccessful() { successes++; raw.setTransactionSuccessful() }
        override fun endTransaction() { ends++; raw.endTransaction(); if (loseEnd) error("FIXED_FENCE_END_LOST") }
    }
    private fun activate(sql: SupportSQLiteDatabase) {
        sql.execSQL("INSERT INTO next_replica_fence VALUES('r','a','s','old','new','auth','device',1,'source','frozen',1)")
    }
    private fun refused(sql: SupportSQLiteDatabase, statement: String): String {
        val before = nativeImage(sql)
        val failure = runCatching { sql.execSQL(statement) }.exceptionOrNull()
        assertNotNull("Actual SQL must fail: $statement", failure)
        assertTrue(failure!!.message.orEmpty().contains("SYNC_REPLICA_SWITCH_FENCED"))
        assertEquals(before, nativeImage(sql))
        return failure.javaClass.name
    }

    @Test fun freshAndRepeatedNativeInstallMatchFrozenOracleAndAllDmlGuards() {
        raw { original -> raw { candidate ->
            val old = Trace(original); val next = Trace(candidate)
            FrozenReplicaFenceInstall1487.install(old); NextReplicaFenceSql.install(next)
            assertEquals(12, old.ddl.size); assertEquals(old.ddl, next.ddl)
            assertEquals(nativeImage(original), nativeImage(candidate))
            old.ddl.clear(); next.ddl.clear()
            FrozenReplicaFenceInstall1487.install(old); NextReplicaFenceSql.install(next)
            assertEquals(12, old.ddl.size); assertTrue(next.ddl.isEmpty())
            assertEquals(nativeImage(original), nativeImage(candidate))
            activate(original); activate(candidate)
            for (statement in listOf("INSERT INTO alpha VALUES(2,NULL,1.0,'new')", "UPDATE alpha SET note='changed' WHERE id=1",
                "DELETE FROM alpha WHERE id=1", "UPDATE count_trust_control SET storageId='other' WHERE id=1",
                "DELETE FROM count_trust_control", "DELETE FROM next_replica_fence", "UPDATE next_replica_fence SET sourceHash='other'")) {
                assertEquals(refused(original, statement), refused(candidate, statement))
            }
            for (sql in listOf(original, candidate)) sql.execSQL("UPDATE count_trust_control SET generation=generation+1,dirty=1 WHERE id=1")
            assertEquals(nativeImage(original), nativeImage(candidate))
        } }
    }

    @Test fun missingMainGuardsAreRecreatedWithoutConfusingTempCaseOrOtherObjectKinds() {
        for (shape in listOf("missing", "temp", "uppercase", "table", "index", "wrong")) raw { original -> raw { candidate ->
            for (sql in listOf(original, candidate)) {
                FrozenReplicaFenceInstall1487.install(sql)
                sql.execSQL("DROP TRIGGER replica_fence_alpha_insert")
                when (shape) {
                    "temp" -> sql.execSQL("CREATE TEMP TRIGGER replica_fence_alpha_insert BEFORE INSERT ON main.alpha BEGIN SELECT 1; END")
                    "uppercase" -> sql.execSQL("CREATE TRIGGER REPLICA_FENCE_ALPHA_INSERT BEFORE INSERT ON alpha BEGIN SELECT 1; END")
                    "table" -> sql.execSQL("CREATE TABLE replica_fence_alpha_insert(id INTEGER)")
                    "index" -> sql.execSQL("CREATE INDEX replica_fence_alpha_insert ON alpha(note)")
                    "wrong" -> sql.execSQL("CREATE TRIGGER replica_fence_alpha_insert BEFORE INSERT ON beta BEGIN SELECT 1; END")
                }
            }
            val old = Trace(original); val next = Trace(candidate)
            val previous = runCatching { FrozenReplicaFenceInstall1487.install(old) }
            val proposed = runCatching { NextReplicaFenceSql.install(next) }
            assertEquals(shape, previous.isSuccess, proposed.isSuccess)
            if (previous.isFailure) assertEquals(previous.exceptionOrNull()!!.javaClass.name, proposed.exceptionOrNull()!!.javaClass.name)
            assertEquals(shape, nativeImage(original), nativeImage(candidate))
            // Exact main trigger presence alone may suppress CREATE. TEMP/case/other kinds cannot.
            if (shape == "wrong") assertTrue(next.ddl.isEmpty()) else assertTrue(next.ddl.any { it.contains("replica_fence_alpha_insert\n") })
            if (shape == "temp") assertEquals(rows(original, "SELECT type,name,tbl_name,sql FROM temp.sqlite_master ORDER BY type,name"),
                rows(candidate, "SELECT type,name,tbl_name,sql FROM temp.sqlite_master ORDER BY type,name"))
        } }
    }

    @Test fun missingDdlFailureRollsBackOwnedTransactionAndRetainsOldPartialCounterexample() {
        raw { original -> raw { candidate ->
            val before = nativeImage(candidate)
            val old = Trace(original).apply { failAt = 2 }
            val next = Trace(candidate).apply { failAt = 2 }
            assertEquals("FIXED_FENCE_CREATE_FAILURE", runCatching { FrozenReplicaFenceInstall1487.install(old) }.exceptionOrNull()?.message)
            assertEquals("FIXED_FENCE_CREATE_FAILURE", runCatching { NextReplicaFenceSql.install(next) }.exceptionOrNull()?.message)
            assertEquals(2, old.ddl.size); assertEquals(2, next.ddl.size)
            assertNotEquals(before, nativeImage(original)) // Preserve actual old autocommit-prefix behavior.
            assertEquals(before, nativeImage(candidate))
            assertFalse(candidate.inTransaction()); assertEquals(1, next.begins); assertEquals(1, next.ends); assertEquals(0, next.successes)
            NextReplicaFenceSql.install(candidate)
            FrozenReplicaFenceInstall1487.install(original)
            assertEquals(nativeImage(original), nativeImage(candidate))
        } }
    }

    @Test fun existingCallerTransactionIsNeverEndedAndItsRollbackRemainsComplete() {
        raw { sql ->
            val before = nativeImage(sql)
            sql.beginTransaction()
            val trace = Trace(sql)
            try {
                NextReplicaFenceSql.install(trace)
                assertTrue(sql.inTransaction()); assertEquals(0, trace.begins); assertEquals(0, trace.ends); assertEquals(0, trace.successes)
                assertEquals(12, trace.ddl.size)
            } finally { sql.endTransaction() }
            assertEquals(before, nativeImage(sql))
        }
        raw(fence = false) { sql ->
            val before = nativeImage(sql)
            val trace = Trace(sql); NextReplicaFenceSql.install(trace)
            assertTrue(trace.ddl.isEmpty()); assertEquals(before, nativeImage(sql)); assertFalse(sql.inTransaction())
        }
    }

    @Test fun actualEndFailureCannotReturnSuccessEvenWhenNativeCommitAlreadyHappened() {
        raw { sql ->
            val before = nativeImage(sql)
            val trace = Trace(sql).apply { loseEnd = true }
            val failure = runCatching { NextReplicaFenceSql.install(trace) }.exceptionOrNull()
            assertEquals("FIXED_FENCE_END_LOST", failure?.message)
            assertEquals(1, trace.ends); assertFalse(sql.inTransaction())
            assertNotEquals(before, nativeImage(sql))
            val committed = nativeImage(sql)
            NextReplicaFenceSql.install(sql)
            assertEquals(committed, nativeImage(sql))
        }
    }
}
