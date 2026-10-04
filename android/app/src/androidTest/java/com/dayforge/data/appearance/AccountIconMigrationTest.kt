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
        "SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND tbl_name NOT IN ('icon_blob_ready','icon_pack_selection') ORDER BY type,name")
    private fun seed(forged: Boolean = false, version: Int = 1): Pair<Map<String, List<List<String?>>>, List<List<String?>>> {
        val old = schema(version)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
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
            if (version == 2) db.execSQL("INSERT INTO icon_blob_ready VALUES('owner','server','epoch','hash','original operation','png-v1')")
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

    private fun originalV2(db: SupportSQLiteDatabase) = snapshot(db) +
        mapOf("icon_blob_ready" to rows(db, "SELECT * FROM icon_blob_ready ORDER BY rowid"))
    private fun originalV2Ddl(db: SupportSQLiteDatabase) = rows(db,
        "SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND tbl_name<>'icon_pack_selection' ORDER BY type,name")

    @Test fun directV2UpgradePreservesAllFourTablesAndDdlWithEmptySelectionAndColdReopen() {
        seed(version = 2)
        val raw = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("Unexpected")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected")
            }).build())
        val (before, structure) = raw.use { originalV2(it.writableDatabase) to originalV2Ddl(it.writableDatabase) }
        var db = open().openHelper.writableDatabase
        assertEquals(3, db.version); assertEquals(before, originalV2(db)); assertEquals(structure, originalV2Ddl(db))
        assertTrue(rows(db, "SELECT * FROM icon_pack_selection").isEmpty())
        assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
        assertEquals(schema(3).getValue("identityHash").jsonPrimitive.content,
            rows(db, "SELECT identity_hash FROM room_master_table WHERE id=42").single().single())
        room!!.close(); db = open().openHelper.writableDatabase
        assertEquals(before, originalV2(db)); assertEquals(structure, originalV2Ddl(db))
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            db.execSQL("INSERT INTO icon_pack_selection VALUES('other','server','epoch',1,'pack',7)")
        }
        db.execSQL("INSERT INTO icon_pack_selection VALUES('owner','server','epoch',1,'pack',7)")
        assertEquals(listOf(listOf("1", "pack", "7")), rows(db, "SELECT generation,packId,revision FROM icon_pack_selection"))
    }

    @Test fun directV2BadIdentityFailsBeforeSelectionDdlAndExactRepairCanRetry() {
        val (before, _) = seed(version = 2)
        for (mutation in listOf("UPDATE room_master_table SET identity_hash='forged'",
            "DELETE FROM room_master_table WHERE id=42", "INSERT INTO room_master_table VALUES(17,'extra')",
            "UPDATE room_master_table SET identity_hash=CAST(identity_hash AS BLOB)")) {
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, 0).use { it.execSQL(mutation) }
            val failure = assertThrows(IllegalStateException::class.java) { open().openHelper.writableDatabase }
            assertEquals("ICON_SELECTION_SCHEMA_IDENTITY", failure.message); room!!.close()
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, 0).use {
                assertEquals(2, it.version)
                it.rawQuery("SELECT name FROM sqlite_master WHERE name='icon_pack_selection'", null).use { c -> assertFalse(c.moveToFirst()) }
                it.execSQL("DELETE FROM room_master_table WHERE id=17")
                it.execSQL("INSERT OR REPLACE INTO room_master_table VALUES(42,?)", arrayOf("04c8718e516aed6eaacbdc5777ea61af"))
            }
        }
        val db = open().openHelper.writableDatabase
        assertEquals(before, snapshot(db)); assertEquals(1, rows(db, "SELECT * FROM icon_blob_ready").size)
        assertEquals(3, db.version)
    }

    @Test fun directV2SelectionObstructionRollsBackAndCanRetryWithoutClearingReady() {
        val (before, _) = seed(version = 2)
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, 0).use {
            it.execSQL("CREATE VIEW icon_pack_selection AS SELECT accountId FROM icon_assets")
        }
        assertThrows(Exception::class.java) { open().openHelper.writableDatabase }; room!!.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, 0).use {
            assertEquals(2, it.version); it.execSQL("DROP VIEW icon_pack_selection")
            it.rawQuery("SELECT COUNT(*) FROM icon_blob_ready", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        }
        val db = open().openHelper.writableDatabase
        assertEquals(3, db.version); assertEquals(before, snapshot(db))
        assertEquals(1, rows(db, "SELECT * FROM icon_blob_ready").size)
        assertTrue(rows(db, "SELECT * FROM icon_pack_selection").isEmpty())
    }

    @Test fun directV2FinalSchemaFailureRollsBackNewTableAndIdentity() {
        seed(version = 2, forged = true)
        assertThrows(IllegalStateException::class.java) { open().openHelper.writableDatabase }; room!!.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2, it.version)
            it.rawQuery("SELECT name FROM sqlite_master WHERE name='icon_pack_selection'", null).use { c -> assertFalse(c.moveToFirst()) }
            it.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("04c8718e516aed6eaacbdc5777ea61af", c.getString(0))
            }
            it.rawQuery("SELECT COUNT(*) FROM icon_blob_ready", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test fun directV2CannotAdoptValidSelectionTableOrItsUnprovenChoiceRows() {
        val (before, _) = seed(version = 2)
        val target = schema(3).getValue("entities").jsonArray.map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == "icon_pack_selection" }
        for (table in listOf("icon_pack_selection", "ICON_PACK_SELECTION")) {
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, 0).use {
                it.execSQL(target.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                // Upper-case table alone must be rejected, not just its lower-case index name.
                if (table == "icon_pack_selection") target.getValue("indices").jsonArray.forEach { index ->
                    it.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
                it.execSQL("INSERT INTO $table VALUES('owner','server','epoch',42,'pack',7)")
            }
            val error = assertThrows(IllegalStateException::class.java) { open().openHelper.writableDatabase }
            assertEquals("ICON_SELECTION_SCHEMA_COLLISION", error.message); room!!.close()
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, 0).use {
                assertEquals(2, it.version)
                it.rawQuery("SELECT generation,packId,revision FROM $table", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(42L, c.getLong(0)); assertEquals("pack", c.getString(1))
                    assertEquals(7, c.getInt(2)); assertFalse(c.moveToNext())
                }
                // Remove only the deliberately injected test table, not any original v2 data.
                it.execSQL("DROP TABLE $table")
            }
        }
        val db = open().openHelper.writableDatabase
        assertEquals(3, db.version); assertEquals(before, snapshot(db))
        assertEquals(1, rows(db, "SELECT * FROM icon_blob_ready").size)
        assertTrue(rows(db, "SELECT * FROM icon_pack_selection").isEmpty())
    }

    @Test fun directV2CannotAdoptAnIndexNameReservedForSelection() {
        seed(version = 2)
        val lower = "index_icon_pack_selection_accountId_serverInstanceId_syncEpoch_packId_revision"
        for (index in listOf(lower, lower.uppercase(java.util.Locale.ROOT))) {
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, 0).use {
                it.execSQL("CREATE INDEX $index ON icon_assets(accountId)")
            }
            val error = assertThrows(IllegalStateException::class.java) { open().openHelper.writableDatabase }
            assertEquals("ICON_SELECTION_SCHEMA_COLLISION", error.message); room!!.close()
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, 0).use {
                assertEquals(2, it.version)
                it.rawQuery("SELECT tbl_name FROM sqlite_master WHERE name=?", arrayOf(index)).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals("icon_assets", c.getString(0))
                }
                it.execSQL("DROP INDEX $index")
            }
        }
        val db = open().openHelper.writableDatabase
        assertEquals(3, db.version); assertEquals(1, rows(db, "SELECT * FROM icon_blob_ready").size)
        assertTrue(rows(db, "SELECT * FROM icon_pack_selection").isEmpty())
    }

    @Test fun productionUpgradePreservesAllOldRowsDdlAndOperationIdentityAndCreatesNoReady() {
        val (before, structure) = seed()
        var db = open().openHelper.writableDatabase
        assertEquals(3, db.version); assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db))
        assertTrue(rows(db, "SELECT * FROM icon_blob_ready").isEmpty())
        assertTrue(rows(db, "SELECT * FROM icon_pack_selection").isEmpty())
        assertEquals(schema(3).getValue("identityHash").jsonPrimitive.content,
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
        assertEquals(3, db.version); assertEquals(before, snapshot(db)); assertTrue(rows(db, "SELECT * FROM icon_blob_ready").isEmpty())
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
        assertEquals(3, db.version); assertEquals(before, snapshot(db)); assertTrue(rows(db, "SELECT * FROM icon_blob_ready").isEmpty())
    }
}
