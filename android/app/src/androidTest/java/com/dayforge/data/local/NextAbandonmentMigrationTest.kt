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

/** Frozen 16→17, whole old schema/bytes preserved; no inferred terminal or destructive fallback. */
@RunWith(AndroidJUnit4::class)
class NextAbandonmentMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val identity = "267d74a4ef2cc6e97696a0035789dc9d"
    private val newTables = listOf("next_recovery_audits", "next_abandoned_requests", "next_recovery_audit_payloads")
    private val schema by lazy {
        Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/16.json").bufferedReader().use { it.readText() })
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
        "WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'replica_fence_%' AND tbl_name NOT IN " +
        "('next_replica_fence','next_recovery_audits','next_abandoned_requests','next_recovery_audit_payloads') ORDER BY type,name")
    private fun seed(block: (SupportSQLiteDatabase) -> Unit = {}) {
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("habit_database").callback(object : SupportSQLiteOpenHelper.Callback(16) {
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
            db.execSQL("""INSERT INTO next_request_origins VALUES('sync_operation','original',1,5,'account',NULL,NULL,'frozen-source','{"untouched":true}')""")
            db.execSQL("INSERT INTO next_transmissions VALUES('sync_operation','original',1,5,'account','server','epoch','device','frozen-wire',X'007FFF')")
            db.execSQL("INSERT INTO next_rejections VALUES('sync_operation','original','origin','transmission','result','untouched')")
            db.execSQL("INSERT INTO next_config_imports VALUES('import','account','server','epoch','device','archive','identities','replacement','prepared','network')")
            db.execSQL("INSERT INTO next_config_import_payloads VALUES('import','network',0,X'007FFF')")
            SyncSchemaCallback.onOpen(db); block(db)
        }
    }
    @Test fun everyOldTableRowDdlAndFrozenRequestSurvivesAndNewTablesAreEmptyOnColdReopen() {
        var before = emptyMap<String, List<List<String?>>>()
        var structure = emptyList<List<String?>>()
        seed { before = snapshot(it); structure = ddl(it) }
        assertEquals(32, entities.size)
        repeat(2) {
            val db = open()
            assertEquals(18, db.version); assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db))
            for (table in newTables) assertTrue(rows(db, "SELECT * FROM $table").isEmpty())
            assertEquals(listOf(listOf("3:" + currentHabitIdentity())), rows(db, "SELECT identity_hash FROM room_master_table WHERE id=42"))
            assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
            assertEquals(listOf(listOf("3:ok")), rows(db, "PRAGMA integrity_check"))
        }
    }
    @Test fun occupiedSecondOrThirdTableRollsBackAllEarlierDdlWithoutOverwritingExistingBytes() {
        for (occupied in newTables.drop(1)) {
            room?.close(); context.deleteDatabase("habit_database")
            seed { it.execSQL("CREATE TABLE $occupied(unproven TEXT)"); it.execSQL("INSERT INTO $occupied VALUES('keep')") }
            assertNotNull(runCatching { open() }.exceptionOrNull()); room!!.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(16, raw.version)
                raw.rawQuery("SELECT unproven FROM $occupied", null).use { assertTrue(it.moveToFirst()); assertEquals("keep", it.getString(0)) }
                for (table in newTables.filterNot { it == occupied }) raw.rawQuery("SELECT name FROM sqlite_master WHERE tbl_name=?", arrayOf(table)).use { assertFalse(it.moveToFirst()) }
                raw.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { assertTrue(it.moveToFirst()); assertEquals(identity, it.getString(0)) }
            }
        }
    }
    @Test fun finalRoomValidationFailureRollsBackWholeMigrationAndAllowsExactRetry() {
        seed { it.execSQL("ALTER TABLE one_time_transmissions RENAME TO unavailable_transmissions") }
        assertNotNull(runCatching { open() }.exceptionOrNull()); room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(16, raw.version)
            for (table in newTables) raw.rawQuery("SELECT name FROM sqlite_master WHERE tbl_name=?", arrayOf(table)).use { assertFalse(it.moveToFirst()) }
            raw.execSQL("ALTER TABLE unavailable_transmissions RENAME TO one_time_transmissions")
        }
        assertEquals(18, open().version)
    }
    @Test fun fakeVersion16IdentityCannotAcquireProofTables() {
        seed { it.execSQL("UPDATE room_master_table SET identity_hash='unproven' WHERE id=42") }
        assertNotNull(runCatching { open() }.exceptionOrNull()); room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(16, raw.version)
            for (table in newTables) raw.rawQuery("SELECT name FROM sqlite_master WHERE tbl_name=?", arrayOf(table)).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun foreignKeysRetainOriginalProofAndAccountClearRemovesWholeAuditWithoutInventingAnAck() = kotlinx.coroutines.runBlocking<Unit> {
        seed(); val db = open()
        db.execSQL("INSERT INTO next_recovery_audits VALUES('recovery','account','server','epoch','device','sync_operation','original','graph','checkpoint','manifest',1)")
        db.execSQL("INSERT INTO next_abandoned_requests VALUES('sync_operation','original','recovery','permanent_rejection','source','snapshot','origin','transmission','rejection')")
        db.execSQL("INSERT INTO next_recovery_audit_payloads VALUES('recovery','manifest',0,X'007FFF')")
        assertNotNull(runCatching { db.execSQL("DELETE FROM next_request_origins") }.exceptionOrNull())
        assertNotNull(runCatching { db.execSQL("DELETE FROM next_recovery_audits") }.exceptionOrNull())
        assertTrue(rows(db, "SELECT * FROM next_acceptances").isEmpty())
        open(); room!!.clearAllData(); val cold = open()
        for (table in newTables + listOf("next_request_origins", "next_transmissions", "next_rejections")) assertTrue(rows(cold, "SELECT * FROM $table").isEmpty())
        assertEquals(listOf(listOf("1:1", "1:0")), rows(cold, "SELECT id,suppressOutbox FROM sync_control"))
        assertTrue(rows(cold, "PRAGMA foreign_key_check").isEmpty())
    }
}
