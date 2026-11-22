package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real committed KSP19 file and controlled version19 SupportSQLite lifecycle. This keeps
 * the historical123-guard repair/one-dirty-transition/cleanup-refusal contract independently
 * of new Room20 mandatory primary opening. It does not impersonate a Room20 database. */
@RunWith(AndroidJUnit4::class)
class NextReplicaFenceInstallV19Test : CountCommandMigrationFixture() {
    private val creates = AtomicInteger()
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
    private fun <T> opened(block: (SupportSQLiteDatabase) -> T): T {
        val owner = CountTrustOpenHelperFactory(context, CountingFactory())
        return owner.create(SupportSQLiteOpenHelper.Configuration.builder(context).name("habit_database")
            .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Committed real19 source required")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("No current20 migration in historical fixture")
                override fun onOpen(db: SupportSQLiteDatabase) = SyncSchemaCallback.onOpen(db)
            }).build()).use { helper ->
            val sql = helper.writableDatabase
            assertEquals(19, sql.version); CountTrustSchema.validate(sql, 19)
            block(sql)
        }
    }
    private fun schemaName(row: JsonElement) = row.jsonObject.getValue("name").jsonObject.getValue("value").jsonPrimitive.content
    private fun nativeInteger(value: Long) = buildJsonObject { put("sqlite_type", Cursor.FIELD_TYPE_INTEGER); put("value", value) }

    @Test(timeout = 150_000) fun actualControlled19ColdOpenSkips123ExistingGuardsAndPreservesEveryNativeValue() {
        val source = source(19)
        assertEquals(123, source.contents.getValue("schema").jsonArray.count { schemaName(it).startsWith("replica_fence_") })
        creates.set(0)
        val first = opened { snapshot(it) }; val second = opened { snapshot(it) }
        assertEquals(0, creates.get()); assertEquals(source.contents, first.contents); assertEquals(first.contents, second.contents)
        assertEquals(source.hash, first.hash); assertEquals(first.hash, second.hash)
    }

    @Test(timeout = 150_000) fun actual19ColdOpenAddsOnlyMissingFaultGuardsAndOneDirtyTransition() {
        source(19)
        val before = opened { sql ->
            sql.execSQL("CREATE TABLE install_probe(id INTEGER PRIMARY KEY,payload BLOB)")
            snapshot(sql)
        }
        creates.set(0)
        val after = opened { snapshot(it) }
        assertEquals(3, creates.get())
        val additions = setOf("replica_fence_install_probe_insert", "replica_fence_install_probe_update", "replica_fence_install_probe_delete")
        val oldSchema = before.contents.getValue("schema").jsonArray
        val newSchema = after.contents.getValue("schema").jsonArray
        assertEquals(additions, newSchema.map(::schemaName).toSet() - oldSchema.map(::schemaName).toSet())
        assertEquals(oldSchema, JsonArray(newSchema.filterNot { schemaName(it) in additions }))
        for (row in newSchema.filter { schemaName(it) in additions }) {
            val action = schemaName(row).substringAfterLast('_').uppercase(java.util.Locale.ROOT)
            assertEquals("CREATE TRIGGER ${schemaName(row)}\nBEFORE $action ON `install_probe` WHEN EXISTS(SELECT 1 FROM next_replica_fence WHERE id IS NOT 1 OR mode IS NOT 'closed')\nBEGIN SELECT RAISE(ABORT,'SYNC_REPLICA_SWITCH_FENCED'); END",
                row.jsonObject.getValue("sql").jsonObject.getValue("value").jsonPrimitive.content)
        }
        val tables = before.contents.getValue("tables").jsonObject
        val control = tables.getValue("count_trust_control").jsonArray.single().jsonObject
        val generation = control.getValue("generation").jsonObject.getValue("value").jsonPrimitive.long
        assertEquals(nativeInteger(generation), control.getValue("generation"))
        val expected = JsonObject(tables + ("count_trust_control" to JsonArray(listOf(JsonObject(control +
            mapOf("generation" to nativeInteger(Math.addExact(generation, 1L)), "dirty" to nativeInteger(1L)))))))
        assertEquals(expected, after.contents.getValue("tables"))
        assertEquals(before.contents - setOf("schema", "tables"), after.contents - setOf("schema", "tables"))
    }

    @Test(timeout = 150_000) fun existingWrong19DefinitionIsNeverRepairedOrGrantedCleanupAuthority() = runBlocking<Unit> {
        source(19)
        val expected = opened { sql -> runBlocking { ReplicaReferenceSchema(ReplicaSqlSchema.read(sql), 19, NextReplicaV19StorageView.PREVIOUS_ROOM_IDENTITY) } }
        val altered = "CREATE TRIGGER replica_fence_completions_insert BEFORE INSERT ON completions BEGIN SELECT 1; END"
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TRIGGER replica_fence_completions_insert"); raw.execSQL(altered)
        }
        creates.set(0)
        opened { sql ->
            val before = snapshot(sql); assertEquals(0, creates.get())
            val actual = before.contents.getValue("schema").jsonArray.single { schemaName(it) == "replica_fence_completions_insert" }
            assertEquals(altered, actual.jsonObject.getValue("sql").jsonObject.getValue("value").jsonPrimitive.content)
            sql.beginTransaction()
            val failure = try { runBlocking { ApprovedBusinessSchema.requireCleanupInTransaction(sql, expected) }; null }
                catch (error: IllegalStateException) { error }
                finally { sql.endTransaction() }
            assertEquals("COUNT_TRUST_CLEANUP_SCHEMA_CHANGED", failure?.message)
            val after = snapshot(sql); assertEquals(before.contents, after.contents); assertEquals(before.hash, after.hash)
        }
    }
}
