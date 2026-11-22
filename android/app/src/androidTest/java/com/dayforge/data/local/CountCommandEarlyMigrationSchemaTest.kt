package com.dayforge.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.CountCommandRootAnchor
import com.dayforge.data.repository.ReplicaStorageProof
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual old KSP file -> production builder/migrations -> first primary signature.
 * The fixed lower-native observer neither changes Root signing nor supplies a schema. */
@RunWith(AndroidJUnit4::class)
class CountCommandEarlyMigrationSchemaTest : CountCommandMigrationFixture() {
    private data class OldSource(val version: Int, val proof: ReplicaStorageProof,
        val columns: Map<String, List<String>>)

    private fun seedOld(version: Int, originalCallbackObjects: Boolean): OldSource {
        val schema = asset(version)
        var result: OldSource? = null
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("habit_database").callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getValue("entities").jsonArray.forEach { value ->
                        val entity = value.jsonObject; val table = entity.getValue("tableName").jsonPrimitive.content
                        fun ddl(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                        db.execSQL(ddl(entity.getValue("createSql")))
                        entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(ddl(it.jsonObject.getValue("createSql"))) }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Original old source only")
            }).build()).use { helper ->
                val sql = helper.writableDatabase
                fun insert(table: String, overrides: Map<String, Any?>) {
                    val entity = schema.getValue("entities").jsonArray.map { it.jsonObject }
                        .single { it.getValue("tableName").jsonPrimitive.content == table }
                    val values = ContentValues()
                    entity.getValue("fields").jsonArray.forEach { fieldValue ->
                        val field = fieldValue.jsonObject
                        if (field["notNull"]?.jsonPrimitive?.boolean == true) {
                            val name = field.getValue("columnName").jsonPrimitive.content
                            if (field.getValue("affinity").jsonPrimitive.content == "TEXT") values.put(name, "") else values.put(name, 0)
                        }
                    }
                    overrides.forEach { (name, value) -> when (value) {
                        null -> values.putNull(name)
                        is String -> values.put(name, value)
                        is Int -> values.put(name, value)
                        is Long -> values.put(name, value)
                        else -> error("Fixed old seed type")
                    } }
                    sql.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
                }
                sql.beginTransaction()
                try {
                    val factMetadata: Map<String, Any?> = if (version >= 2) mapOf(
                        "recordedTimezone" to "UTC", "recordedLocalDate" to "2026-10-06", "timeMetadataSource" to "captured") else emptyMap()
                    insert("sync_control", mapOf<String, Any?>("id" to 1, "suppressOutbox" to 0))
                    insert("habits", mapOf<String, Any?>("id" to 1, "uuid" to id(101), "name" to "Original old habit",
                        "habitType" to "CHECK_IN", "schedule" to "{\"type\":\"daily\"}", "failMode" to "STRICT"))
                    insert("metrics", mapOf<String, Any?>("id" to 1, "uuid" to id(102), "name" to "Original old metric",
                        "unit" to "kg", "aggregationType" to "average"))
                    insert("completions", mapOf<String, Any?>("id" to 1, "uuid" to id(103), "habitId" to 1,
                        "habitUuid" to id(101), "date" to 1791244800000L, "actualCompletedAt" to 1791244800000L, "value" to 2) + factMetadata)
                    insert("metric_logs", mapOf<String, Any?>("id" to 1, "uuid" to id(104), "metricId" to 1,
                        "date" to 1791244800000L, "value" to 21, "unit" to "kg", "note" to "original bytes") + factMetadata)
                    insert("sync_outbox", mapOf<String, Any?>("id" to 1, "operationId" to id(105), "recordType" to "completion",
                        "entityUuid" to id(103), "wireEntityUuid" to id(103), "action" to "upsert",
                        "payloadJson" to "{\"untouched\":\"old queue\"}", "attemptedAt" to 1791244800000L, "attemptCount" to 1))
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
                if (originalCallbackObjects) SyncSchemaCallback.onOpen(sql)
                val names = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
                val columns = names.associateWith { table -> sql.query("PRAGMA main.table_info(`$table`)").use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(1)) }
                } }
                result = OldSource(version, snapshot(sql), columns)
            }
        return requireNotNull(result)
    }

    private fun openObserved(probe: MigrationProbe): HabitDatabase {
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        return HabitDatabaseProvider.build(context, nativeFactory = probe).also {
            room = it; assertTrue(it.openHelper.writableDatabase.isOpen)
        }
    }

    private fun originalAfterFailure(version: Int): ReplicaStorageProof {
        room?.close(); room = null; HabitDatabaseProvider.clearInstanceForTesting()
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("habit_database").callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Old source must survive")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Old version must survive")
            }).build()).use { helper -> snapshot(helper.writableDatabase) }
    }

    private fun assertOldCells(before: OldSource, after: ReplicaStorageProof) {
        val old = before.proof.contents.getValue("tables").jsonObject
        val current = after.contents.getValue("tables").jsonObject
        for ((table, columns) in before.columns) {
            if (table == "room_master_table") continue // The actual KSP identity is checked separately.
            val retained = current.getValue(table).jsonArray.map { row -> JsonObject(row.jsonObject.filterKeys {
                it == "__recovery_rowid" || it in columns
            }) }
            assertEquals("Every original typed cell/rowid remains: $table", old.getValue(table), JsonArray(retained))
        }
    }

    private fun verifyFirstSignature(before: OldSource, probe: MigrationProbe) {
        val current = openObserved(probe); val sql = current.openHelper.writableDatabase
        assertEquals(20, sql.version)
        assertEquals(1, probe.rootInsertions)
        assertTrue(probe.rootWasInUpgradeTransaction)
        assertEquals(MigrationProbe.SYNC_NAMES, requireNotNull(probe.syncBeforeRoot).keys)
        assertEquals(probe.syncBeforeRoot, probe.sync(sql))
        val root = CountCommandStorageInitialization.verifyRootAnchor(sql)
        assertEquals(CountCommandRootAnchor.digest(root.native()), CountCommandRootAnchor.read(sql).hash)
        sql.query("SELECT id,identity_hash FROM main.room_master_table ORDER BY id").use {
            assertTrue(it.moveToFirst()); assertEquals(42L, it.getLong(0))
            assertEquals(asset(20).getValue("identityHash").jsonPrimitive.content, it.getString(1)); assertFalse(it.moveToNext())
        }
        val after = snapshot(sql); assertOldCells(before, after)
        val reopened = openObserved(probe)
        assertEquals(1, probe.rootInsertions)
        assertEquals(after.contents, snapshot(reopened.openHelper.writableDatabase).contents)
        CountCommandStorageInitialization.verifyMandatory(reopened.openHelper.writableDatabase)
    }

    @Test(timeout = 150_000) fun originalVersionOneWithoutCallbackObjectsCompletesFinalSyncSchemaBeforeFirstRoot() = runBlocking<Unit> {
        val before = seedOld(1, false)
        assertFalse(before.proof.contents.getValue("schema").jsonArray.any {
            it.jsonObject.getValue("name").jsonObject.getValue("value").jsonPrimitive.content in MigrationProbe.SYNC_NAMES
        })
        verifyFirstSignature(before, MigrationProbe())
    }

    @Test(timeout = 150_000) fun originalVersionSixDroppedUpdateIsRestoredBeforeSignatureAndColdOpenDoesNotResign() = runBlocking<Unit> {
        val before = seedOld(6, true)
        verifyFirstSignature(before, MigrationProbe())
    }

    @Test(timeout = 150_000) fun actualFinalSyncDdlFailureRollsBackAllEarlierMigrationsBeforeAnyRootExists() = runBlocking<Unit> {
        val before = seedOld(1, false); val probe = MigrationProbe(failSyncDdl = true)
        val failure = try { openObserved(probe); null } catch (error: Exception) { error }
        assertNotNull(failure); assertTrue(probe.faults.isNotEmpty())
        assertTrue(generateSequence<Throwable>(requireNotNull(failure)) { it.cause }.any { observed -> probe.faults.any { it === observed } })
        assertEquals(probe.faults.size, probe.faultsInUpgradeTransaction.size)
        assertTrue(probe.faultsInUpgradeTransaction.all { it }); assertEquals(0, probe.rootInsertions)
        assertEquals(before.proof.contents, originalAfterFailure(before.version).contents)
        verifyFirstSignature(before, MigrationProbe())
    }

    @Test(timeout = 150_000) fun extraSchemaAfterCapturedLegacyImageCannotBeIncludedInTheFirstSignature() = runBlocking<Unit> {
        val before = seedOld(6, true); val probe = MigrationProbe(addUnexpectedDdl = true)
        val failure = try { openObserved(probe); null } catch (error: Exception) { error }
        assertNotNull(failure); assertTrue(probe.extraDdlHits > 0)
        assertTrue(generateSequence<Throwable>(requireNotNull(failure)) { it.cause }.any { it.message == "COMMAND_MIGRATION_CHANGED_OLD_SCHEMA" })
        assertEquals(0, probe.rootInsertions)
        assertEquals(before.proof.contents, originalAfterFailure(before.version).contents)
        verifyFirstSignature(before, MigrationProbe())
    }

    /** Every callback still delegates through the real controlled factory. Faults exist only
     * below it and only before the first Root, inside the original Framework upgrade TX. */
    private class MigrationProbe(private val failSyncDdl: Boolean = false,
        private val addUnexpectedDdl: Boolean = false) : SupportSQLiteOpenHelper.Factory {
        var rootInsertions = 0
        var rootWasInUpgradeTransaction = false
        var syncBeforeRoot: Map<String, String>? = null
        val faults = mutableListOf<Exception>()
        val faultsInUpgradeTransaction = mutableListOf<Boolean>()
        var extraDdlHits = 0
        fun sync(sql: SupportSQLiteDatabase): Map<String, String> = sql.query(
            "SELECT name,sql FROM main.sqlite_master WHERE type='trigger' ORDER BY name").use { c -> buildMap {
                while (c.moveToNext()) if (c.getString(0) in SYNC_NAMES) put(c.getString(0), c.getString(1))
            } }
        private fun exec(raw: SupportSQLiteDatabase, sql: String, action: () -> Unit) {
            if (sql.startsWith("INSERT INTO count_command_recovery_roots(")) {
                rootInsertions++; rootWasInUpgradeTransaction = raw.inTransaction()
                syncBeforeRoot = sync(raw)
            }
            action()
            if (failSyncDdl && sql.startsWith("CREATE TRIGGER IF NOT EXISTS sync_habits_update")) {
                assertTrue(sync(raw).containsKey("sync_habits_update"))
                faultsInUpgradeTransaction += raw.inTransaction()
                // Real SQLite DDL failure after the expected trigger was actually created.
                try { raw.execSQL("CREATE TABLE sync_control (id INTEGER)"); error("Existing table must reject") }
                catch (failure: android.database.SQLException) { faults += failure; throw failure }
            }
            if (addUnexpectedDdl && sql == "ALTER TABLE count_trust_control ADD COLUMN commandRootHash TEXT DEFAULT NULL") {
                assertTrue(raw.inTransaction()); extraDdlHits++
                raw.execSQL("CREATE TABLE unexpected_migration_after_capture (id INTEGER)")
            }
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            check(configuration.context.packageName == "com.dayforge.testbed")
            var native: SupportSQLiteDatabase? = null; var wrapped: SupportSQLiteDatabase? = null
            val monitor = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (native !== raw) {
                    native = raw
                    wrapped = object : SupportSQLiteDatabase by raw {
                        override fun execSQL(sql: String) = exec(raw, sql) { raw.execSQL(sql) }
                        override fun execSQL(sql: String, bindArgs: Array<out Any?>) = exec(raw, sql) { raw.execSQL(sql, bindArgs) }
                    }
                }
                requireNotNull(wrapped)
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
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase get() = wrap(helper.readableDatabase)
            }
        }
        companion object {
            val SYNC_NAMES = setOf("habits", "metrics", "completions", "metric_logs", "habit_metric_links")
                .flatMap { table -> listOf("insert", "update", "delete").map { "sync_${table}_$it" } }.toSet()
        }
    }
}
