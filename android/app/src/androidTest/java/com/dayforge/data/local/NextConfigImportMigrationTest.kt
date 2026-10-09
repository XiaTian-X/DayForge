package com.dayforge.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual frozen 15→16 production upgrade, old bytes/DDL, failure rollback and cold reopen. */
@RunWith(AndroidJUnit4::class)
class NextConfigImportMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val identity = "397822d3b6f86f9d2b73a636c05287d9"
    private val schema by lazy {
        Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/15.json").bufferedReader().use { it.readText() })
            .jsonObject.getValue("database").jsonObject
    }
    private val entities get() = schema.getValue("entities").jsonArray.map { it.jsonObject }
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting(); context.deleteDatabase("habit_database")
        assertEquals(identity, schema.getValue("identityHash").jsonPrimitive.content)
    }
    @After fun cleanup() {
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting(); context.deleteDatabase("habit_database")
    }
    private fun open(): SupportSQLiteDatabase {
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        room = HabitDatabaseProvider.getInstance(context)
        return room!!.openHelper.writableDatabase
    }
    private fun rows(db: SupportSQLiteDatabase, query: String) = db.query(query).use { c ->
        buildList { while (c.moveToNext()) add(List(c.columnCount) { i -> when (c.getType(i)) {
            android.database.Cursor.FIELD_TYPE_NULL -> null
            android.database.Cursor.FIELD_TYPE_BLOB -> "blob:" + c.getBlob(i).joinToString("") { "%02x".format(it) }
            else -> c.getType(i).toString() + ":" + c.getString(i)
        } }) }
    }
    private fun snapshot(db: SupportSQLiteDatabase) = entities.associate { entity ->
        val table = entity.getValue("tableName").jsonPrimitive.content
        val columns = entity.getValue("fields").jsonArray.joinToString(",") { "`${it.jsonObject.getValue("columnName").jsonPrimitive.content}`" }
        table to rows(db, "SELECT $columns FROM `$table` ORDER BY rowid")
    }
    private fun ddl(db: SupportSQLiteDatabase) = rows(db, "SELECT type,name,tbl_name,sql FROM sqlite_master " +
        "WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND tbl_name NOT IN ('next_config_imports','next_config_import_payloads') ORDER BY type,name")
    private fun seed(block: (SupportSQLiteDatabase) -> Unit = {}) {
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("habit_database").callback(object : SupportSQLiteOpenHelper.Callback(15) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    entities.forEach { entity ->
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        fun sql(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                        db.execSQL(sql(entity.getValue("createSql")))
                        entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(sql(it.jsonObject.getValue("createSql"))) }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build()).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO sync_control(id,suppressOutbox) VALUES(1,0)")
            // Deliberately opaque historical bytes must be preserved, never interpreted as authority.
            db.execSQL("""INSERT INTO next_request_origins(kind,requestId,queueId,protocol,accountId,serverInstanceId,syncEpoch,sourceHash,intentJson)
                VALUES('sync_operation','original',1,5,'account',NULL,NULL,'frozen-source','{"untouched":true}')""")
            db.execSQL("""INSERT INTO next_transmissions(kind,requestId,queueId,protocol,accountId,serverInstanceId,syncEpoch,deviceId,wireHash,wireBytes)
                VALUES('sync_operation','original',1,5,'account','server','epoch','device','frozen-wire',X'007FFF')""")
            SyncSchemaCallback.onOpen(db); block(db)
        }
    }
    @Test fun completeOldRowsDdlAndFrozenWireSurviveAndPrivateTablesStartEmpty() {
        var before = emptyMap<String, List<List<String?>>>()
        var structure = emptyList<List<String?>>()
        seed { before = snapshot(it); structure = ddl(it) }
        assertEquals(30, entities.size)
        repeat(2) {
            val db = open()
            assertEquals(16, db.version); assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db))
            for (table in listOf("next_config_imports", "next_config_import_payloads")) assertTrue(rows(db, "SELECT * FROM $table").isEmpty())
            assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
            assertEquals(listOf(listOf("3:ok")), rows(db, "PRAGMA integrity_check"))
        }
    }
    @Test fun occupiedSecondTableRollsBackFirstDdlWithoutOverwritingExistingRows() {
        seed { it.execSQL("CREATE TABLE next_config_import_payloads(unproven TEXT)"); it.execSQL("INSERT INTO next_config_import_payloads VALUES('keep')") }
        assertNotNull(runCatching { open() }.exceptionOrNull()); room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(15, raw.version)
            raw.rawQuery("SELECT unproven FROM next_config_import_payloads", null).use { assertTrue(it.moveToFirst()); assertEquals("keep", it.getString(0)) }
            raw.rawQuery("SELECT name FROM sqlite_master WHERE tbl_name='next_config_imports'", null).use { assertFalse(it.moveToFirst()) }
            raw.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { assertTrue(it.moveToFirst()); assertEquals(identity, it.getString(0)) }
        }
    }
    @Test fun finalRoomValidationFailureRollsBackBothTablesAndAllowsExactRetry() {
        seed { it.execSQL("ALTER TABLE one_time_transmissions RENAME TO unavailable_transmissions") }
        assertNotNull(runCatching { open() }.exceptionOrNull()); room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(15, raw.version)
            raw.rawQuery("SELECT name FROM sqlite_master WHERE tbl_name LIKE 'next_config_import_%'", null).use { assertFalse(it.moveToFirst()) }
            raw.execSQL("ALTER TABLE unavailable_transmissions RENAME TO one_time_transmissions")
        }
        assertEquals(16, open().version)
    }
    @Test fun fakeVersion15IdentityCannotAcquirePrivateProofTables() {
        seed { it.execSQL("UPDATE room_master_table SET identity_hash='unproven' WHERE id=42") }
        assertNotNull(runCatching { open() }.exceptionOrNull()); room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(15, raw.version)
            raw.rawQuery("SELECT name FROM sqlite_master WHERE tbl_name LIKE 'next_config_import_%'", null).use { assertFalse(it.moveToFirst()) }
        }
    }
}
