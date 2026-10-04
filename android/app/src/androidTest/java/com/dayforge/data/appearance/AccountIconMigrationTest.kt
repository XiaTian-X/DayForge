package com.dayforge.data.appearance

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private var room: AccountIconDatabase? = null
    private val name = AccountIconDatabase.NAME
    private fun schema(version: Int) = Json.parseToJsonElement(instrumentation.context.assets
        .open("com.dayforge.data.appearance.AccountIconDatabase/$version.json").bufferedReader().use { it.readText() })
        .jsonObject.getValue("database").jsonObject
    private fun rows(db: SupportSQLiteDatabase, sql: String) = db.query(sql).use { c ->
        buildList { while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) }) }
    }
    private fun snapshot(db: SupportSQLiteDatabase) = listOf("icon_assets", "icon_packs", "icon_blob_reservations").associateWith {
        rows(db, "SELECT * FROM $it ORDER BY rowid")
    }
    private fun ddl(db: SupportSQLiteDatabase) = rows(db,
        "SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND tbl_name<>'icon_blob_ready' ORDER BY type,name")
    private fun seed(forged: Boolean = false): Pair<Map<String, List<List<String?>>>, List<List<String?>>> {
        val old = schema(1)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (entity in old.getValue("entities").jsonArray.map { it.jsonObject }) {
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        fun sql(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                        db.execSQL(sql(entity.getValue("createSql")))
                        entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(sql(it.jsonObject.getValue("createSql"))) }
                    }
                    old.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected")
            }).build())
        return helper.use {
            val db = it.writableDatabase
            // Opaque metadata is preserved byte-for-byte; migration must not infer file readiness.
            db.execSQL("INSERT INTO icon_assets VALUES('owner','server','epoch','asset',' {\"frozen\": true} ')")
            db.execSQL("INSERT INTO icon_packs VALUES('owner','server','epoch','pack',7,'original metadata')")
            db.execSQL("INSERT INTO icon_blob_reservations VALUES('owner','server','epoch','hash',10,'image/png',1,1,'original operation')")
            if (forged) db.execSQL("ALTER TABLE icon_assets ADD COLUMN unexpected TEXT")
            snapshot(db) to ddl(db)
        }
    }
    private fun open(): AccountIconDatabase = AccountIconDatabase.open(app).also { room = it }
    @Before fun setup() {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(name).exists())
    }
    @After fun cleanup() { room?.close(); assertTrue(app.deleteDatabase(name) || !app.getDatabasePath(name).exists()) }

    @Test fun productionUpgradePreservesAllOldRowsDdlAndOperationIdentityAndCreatesNoReady() {
        val (before, structure) = seed()
        var db = open().openHelper.writableDatabase
        assertEquals(2, db.version); assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db))
        assertTrue(rows(db, "SELECT * FROM icon_blob_ready").isEmpty())
        assertEquals(schema(2).getValue("identityHash").jsonPrimitive.content,
            rows(db, "SELECT identity_hash FROM room_master_table WHERE id=42").single().single())
        room!!.close(); db = open().openHelper.writableDatabase
        assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db))
        assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            db.execSQL("INSERT INTO icon_blob_ready VALUES('other','server','epoch','hash','op','png-v1')")
        }
    }

    @Test fun finalSchemaValidationFailureRollsBackDdlAndVersionWithoutDeletingOldRows() {
        val (before, _) = seed(forged = true)
        assertThrows(IllegalStateException::class.java) { open().openHelper.writableDatabase }
        room!!.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(1, raw.version)
            raw.rawQuery("SELECT name FROM sqlite_master WHERE name='icon_blob_ready'", null).use { assertFalse(it.moveToFirst()) }
            for ((table, expected) in before) raw.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { c ->
                val actual = buildList { while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) }) }
                assertEquals(expected, actual)
            }
        }
    }

    @Test fun migrationCreateFailureLeavesV1AndCanRetryAfterExactTestObstructionRemoval() {
        val (before, _) = seed()
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE VIEW icon_blob_ready AS SELECT accountId FROM icon_assets")
        }
        assertThrows(Exception::class.java) { open().openHelper.writableDatabase }; room!!.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            assertEquals(1, it.version); it.execSQL("DROP VIEW icon_blob_ready")
        }
        val db = open().openHelper.writableDatabase
        assertEquals(2, db.version); assertEquals(before, snapshot(db)); assertTrue(rows(db, "SELECT * FROM icon_blob_ready").isEmpty())
    }

    @Test fun forgedMissingExtraOrWrongTypeOldIdentityCannotBeReplacedByMigration() {
        val (before, _) = seed()
        val identity = schema(1).getValue("identityHash").jsonPrimitive.content
        assertEquals("897ac35249ffe16fe3d16ea58dac5b54", identity)
        for (mutation in listOf(
            "UPDATE room_master_table SET identity_hash='forged'",
            "DELETE FROM room_master_table WHERE id=42",
            "INSERT INTO room_master_table VALUES(17,'unexpected')",
            "UPDATE room_master_table SET identity_hash=CAST(identity_hash AS BLOB)"
        )) {
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(mutation) }
            val failure = assertThrows(IllegalStateException::class.java) { open().openHelper.writableDatabase }
            assertEquals("ICON_V1_SCHEMA_IDENTITY", failure.message)
            room!!.close()
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                assertEquals(1, raw.version)
                raw.rawQuery("SELECT name FROM sqlite_master WHERE name='icon_blob_ready'", null).use { assertFalse(it.moveToFirst()) }
                for ((table, expected) in before) raw.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { c ->
                    val actual = buildList { while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) }) }
                    assertEquals(expected, actual)
                }
                // Remove only the deliberately introduced test obstruction, never reset the data tables.
                raw.execSQL("DELETE FROM room_master_table WHERE id=17")
                raw.execSQL("INSERT OR REPLACE INTO room_master_table VALUES(42,?)", arrayOf(identity))
            }
        }
        val db = open().openHelper.writableDatabase
        assertEquals(2, db.version); assertEquals(before, snapshot(db)); assertTrue(rows(db, "SELECT * FROM icon_blob_ready").isEmpty())
    }
}
