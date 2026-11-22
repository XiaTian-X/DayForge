package com.dayforge.data.repository

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.CountCommandMigrationFixture
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.SyncSchemaCallback
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** A newly constructed historical test input, never a downgrade/repair of a production DB.
 * Real KSP18 creates all old tables and indices. Copy only this small fixture's complete old
 * native rows/rowids, then install its original callbacks. Capture the actual18 image before
 * the normal production builder performs18->19->20. No current Root is re-signed or reused.
 */
internal object ReplicaV18SourceFixture {
    suspend fun <T> withDatabase(context: Context, storage: PhysicalDatabaseRule,
        beforeCurrentUpgrade: (suspend (SupportSQLiteDatabase) -> Unit)? = null,
        block: suspend (SupportSQLiteDatabase) -> T): T {
        check(context.packageName == "com.dayforge.testbed" && !storage.database.inTransaction())
        val asset = CountCommandMigrationFixture.asset(18)
        val entities = asset.getValue("entities").jsonArray.map { it.jsonObject }
        val names = entities.map { it.getValue("tableName").jsonPrimitive.content }
        require(names.all { it.matches(Regex("[a-z_]+")) } && names.distinct().size == names.size)
        val before = storage.database.withTransaction {
            CountCommandMigrationFixture.snapshot(storage.database.openHelper.writableDatabase)
        }
        val oldRows = before.contents.getValue("tables").jsonObject
        // This helper must not erase a real current command while constructing a historical case.
        for (table in listOf("count_command_intents", "count_command_status", "count_command_nodes", "count_command_results"))
            assertTrue("Historical input requires no retained command contents: $table", oldRows.getValue(table).jsonArray.isEmpty())
        storage.snapshotClosedDatabase() // Original real checkpoint/close; no live WAL copy.
        storage.database.close(); HabitDatabaseProvider.clearInstanceForTesting()
        val source = context.getDatabasePath("habit_database")
        val name = "replica-real-v18-${UUID.randomUUID()}"
        val file = context.getDatabasePath(name)
        try {
            FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(18) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (entity in entities) {
                            val table = entity.getValue("tableName").jsonPrimitive.content
                            fun ddl(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                            db.execSQL(ddl(entity.getValue("createSql")))
                            entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(ddl(it.jsonObject.getValue("createSql"))) }
                        }
                        asset.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("New historical18 input only")
                }).build()).use { helper ->
                val sql = helper.writableDatabase
                assertEquals(18, sql.version)
                sql.execSQL("ATTACH DATABASE ? AS fixture_source", arrayOf(source.absolutePath))
                try {
                    sql.beginTransaction()
                    try {
                        for (table in names) {
                            fun columns(schema: String) = sql.query("PRAGMA $schema.table_info(`$table`)").use { c ->
                                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
                            }
                            val target = columns("main"); assertEquals(target.toSet(), columns("fixture_source").toSet())
                            require(target.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) })
                            val fields = (listOf("rowid") + target).joinToString(",") { "`$it`" }
                            sql.execSQL("INSERT INTO main.`$table`($fields) SELECT $fields FROM fixture_source.`$table` ORDER BY rowid")
                        }
                        // Preserve every old sequence row and its native rowid, not just max(id).
                        val sequences = oldRows.getValue("sqlite_sequence").jsonArray
                        require(sequences.all { it.jsonObject.getValue("name").jsonObject.getValue("value").jsonPrimitive.content in names })
                        sql.execSQL("DELETE FROM main.sqlite_sequence")
                        sql.execSQL("INSERT INTO main.sqlite_sequence(rowid,name,seq) SELECT rowid,name,seq FROM fixture_source.sqlite_sequence ORDER BY rowid")
                        sql.execSQL("DELETE FROM main.android_metadata")
                        sql.execSQL("INSERT INTO main.android_metadata(rowid,locale) SELECT rowid,locale FROM fixture_source.android_metadata ORDER BY rowid")
                        // No triggers ran while rows were copied; install the real old callback now.
                        SyncSchemaCallback.onOpen(sql)
                        sql.query("PRAGMA main.foreign_key_check").use { assertFalse(it.moveToFirst()) }
                        val actual = CountCommandMigrationFixture.snapshot(sql).contents.getValue("tables").jsonObject
                        for (table in names + listOf("sqlite_sequence", "android_metadata"))
                            assertEquals("Complete historical cells and rowids: $table", oldRows.getValue(table), actual.getValue(table))
                        assertEquals((names + listOf("room_master_table", "sqlite_sequence", "android_metadata")).toSet(), actual.keys)
                        sql.query("SELECT id,identity_hash FROM main.room_master_table ORDER BY id").use {
                            assertTrue(it.moveToFirst()); assertEquals(42L, it.getLong(0))
                            assertEquals(asset.getValue("identityHash").jsonPrimitive.content, it.getString(1)); assertFalse(it.moveToNext())
                        }
                        sql.setTransactionSuccessful()
                    } finally { sql.endTransaction() }
                } finally { sql.execSQL("DETACH DATABASE fixture_source") }
            }
            check(file.isFile && file.length() in 1..67_108_864L)
            val wal = java.io.File(file.path + "-wal")
            check(!wal.exists() || wal.length() == 0L) { "Historical fixture must be fully checkpointed and closed" }
            storage.restoreClosedDatabase(file.readBytes())
            storage.database.close(); HabitDatabaseProvider.clearInstanceForTesting()
            val historical = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("habit_database").callback(object : SupportSQLiteOpenHelper.Callback(18) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("Existing real18 input required")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected historical oracle upgrade")
                }).build())
            val result = try { block(historical.writableDatabase) } finally { historical.close() }
            if (beforeCurrentUpgrade != null) {
                // An actual committed historical19 boundary, before the first20 signature.
                // Reuse the existing fixed18->19 migration/Room identity fixture, not PRAGMA spoofing.
                CountCommandMigrationFixture.upgradeFrozen18To19(context) {}
                FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name("habit_database").callback(object : SupportSQLiteOpenHelper.Callback(19) {
                        override fun onCreate(db: SupportSQLiteDatabase) = error("Existing historical19 required")
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("No20 enrollment in historical observer")
                    }).build()).use { previous ->
                    val sql = previous.writableDatabase
                    assertEquals(19, sql.version)
                    sql.query("SELECT identity_hash FROM main.room_master_table WHERE id=42").use {
                        assertTrue(it.moveToFirst())
                        assertEquals(CountCommandMigrationFixture.asset(19).getValue("identityHash").jsonPrimitive.content, it.getString(0))
                        assertFalse(it.moveToNext())
                    }
                    beforeCurrentUpgrade(sql)
                }
            }
            return result
        } finally {
            assertTrue(context.deleteDatabase(name) || !file.exists())
            // Merely builds the original controlled Room; the next actual open migrates it.
            storage.reopen()
        }
    }
}
