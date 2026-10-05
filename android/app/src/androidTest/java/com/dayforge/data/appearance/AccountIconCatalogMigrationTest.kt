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
class AccountIconCatalogMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val name = AccountIconDatabase.NAME
    private var room: AccountIconDatabase? = null
    private val tables = listOf("icon_assets", "icon_packs", "icon_blob_reservations", "icon_blob_ready", "icon_pack_selection", "icon_transfers")
    private val catalogIndex = "index_icon_catalog_entries_accountId_serverInstanceId_syncEpoch_kind_targetId_revision"
    private val transferIndex = "index_icon_transfers_accountId_serverInstanceId_syncEpoch_kind_targetId_revision_variant"
    private fun schema(version: Int) = instrumentation.context.assets
        .open("com.dayforge.data.appearance.AccountIconDatabase/$version.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
        }
    private fun rows(db: SupportSQLiteDatabase, sql: String) = db.query(sql).use { c -> buildList {
        while (c.moveToNext()) add(List(c.columnCount) { i ->
            if (c.isNull(i)) "null" else "${c.getType(i)}:" +
                if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) c.getBlob(i).joinToString("") { "%02x".format(it) }
                else c.getString(i)
        })
    } }
    private fun snapshot(db: SupportSQLiteDatabase) = tables.associateWith { rows(db, "SELECT * FROM $it ORDER BY rowid") }
    private fun ddl(db: SupportSQLiteDatabase) = rows(db,
        "SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND tbl_name NOT IN ('icon_catalog_state','icon_catalog_entries') ORDER BY type,name")
    private fun seed(): Pair<Map<String, List<List<String>>>, List<List<String>>> {
        val old = schema(4)
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
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
            }).build()).use {
            val db = it.writableDatabase
            db.execSQL("INSERT INTO icon_assets VALUES('owner','server','epoch','asset',' é 原文 ')")
            db.execSQL("INSERT INTO icon_packs VALUES('owner','server','epoch','pack',7,' opaque pack ')")
            db.execSQL("INSERT INTO icon_blob_reservations VALUES('owner','server','epoch','hash',10,'image/png',1,1,'original operation')")
            db.execSQL("INSERT INTO icon_blob_ready VALUES('owner','server','epoch','hash','original operation','png-v1')")
            db.execSQL("INSERT INTO icon_pack_selection VALUES('owner','server','epoch',42,'pack',7)")
            db.execSQL("INSERT INTO icon_transfers VALUES('owner','server','epoch','opaque transfer','declare_asset','asset',0,'','opaque hash','complete',42,'device',NULL,'original ack',0)")
            snapshot(db) to ddl(db)
        }
    }
    private fun open() = AccountIconDatabase.open(app).also { room = it }.openHelper.writableDatabase
    private fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, 0).use(block)
    private fun absent(db: SQLiteDatabase) = db.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('icon_catalog_state','icon_catalog_entries',?)",
        arrayOf(catalogIndex)).use { c -> assertFalse(c.moveToFirst()) }
    @Before fun setup() { check(app.packageName == "com.dayforge.testbed"); assertFalse(app.getDatabasePath(name).exists()) }
    @After fun cleanup() { room?.close(); assertTrue(app.deleteDatabase(name) || !app.getDatabasePath(name).exists()) }

    @Test fun directV4PreservesAllSixTablesTypesDdlReadySelectionAndTransferProofsThroughColdReopen() {
        val (before, structure) = seed()
        var db = open()
        assertEquals(5, db.version); assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db))
        assertTrue(rows(db, "SELECT * FROM icon_catalog_state").isEmpty())
        assertTrue(rows(db, "SELECT * FROM icon_catalog_entries").isEmpty())
        assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
        assertEquals("3:" + schema(5).getValue("identityHash").jsonPrimitive.content,
            rows(db, "SELECT identity_hash FROM room_master_table WHERE id=42").single().single())
        room!!.close(); db = open()
        assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db))
        assertTrue(rows(db, "SELECT * FROM icon_catalog_entries").isEmpty())
    }

    @Test fun v4ForgedMissingExtraBlobOrWrongIdFailsBeforeDdlAndExactRepairRetriesWithoutPurge() {
        val (before, structure) = seed()
        for (mutation in listOf("UPDATE room_master_table SET identity_hash='forged'", "DELETE FROM room_master_table WHERE id=42",
            "INSERT INTO room_master_table VALUES(17,'extra')", "UPDATE room_master_table SET identity_hash=CAST(identity_hash AS BLOB)",
            "UPDATE room_master_table SET id=43")) {
            raw { it.execSQL(mutation) }
            assertEquals("ICON_CATALOG_SCHEMA_IDENTITY", assertThrows(IllegalStateException::class.java) { open() }.message)
            room!!.close()
            raw {
                assertEquals(4, it.version); absent(it)
                it.execSQL("DELETE FROM room_master_table WHERE id IN (17,43)")
                it.execSQL("INSERT OR REPLACE INTO room_master_table VALUES(42,'05aff81105a9e40dca1abe3a8357bd64')")
            }
        }
        val db = open(); assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db)); assertEquals(5, db.version)
    }

    @Test fun validForeignCatalogTablesViewsAndCaseVariantReservedIndexCannotBeAdopted() {
        val (before, _) = seed()
        val entities = schema(5).getValue("entities").jsonArray.map { it.jsonObject }
        val state = entities.single { it.getValue("tableName").jsonPrimitive.content == "icon_catalog_state" }
        val entries = entities.single { it.getValue("tableName").jsonPrimitive.content == "icon_catalog_entries" }
        val statements = listOf(
            "CREATE VIEW icon_catalog_state AS SELECT accountId FROM icon_assets" to "DROP VIEW icon_catalog_state",
            state.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", "ICON_CATALOG_STATE") to "DROP TABLE ICON_CATALOG_STATE",
            "CREATE VIEW ICON_CATALOG_ENTRIES AS SELECT accountId FROM icon_assets" to "DROP VIEW ICON_CATALOG_ENTRIES",
            entries.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", "icon_catalog_entries") to "DROP TABLE icon_catalog_entries",
            "CREATE INDEX $catalogIndex ON icon_assets(accountId)" to "DROP INDEX $catalogIndex",
            "CREATE INDEX ${catalogIndex.uppercase(java.util.Locale.ROOT)} ON icon_assets(accountId)" to "DROP INDEX ${catalogIndex.uppercase(java.util.Locale.ROOT)}"
        )
        for ((create, drop) in statements) {
            raw {
                it.execSQL(create)
                if (drop == "DROP TABLE ICON_CATALOG_STATE") it.execSQL("INSERT INTO ICON_CATALOG_STATE VALUES('owner','server','epoch',9,4,7)")
                if (drop == "DROP TABLE icon_catalog_entries") it.execSQL("INSERT INTO icon_catalog_entries VALUES('owner','server','epoch',4,'asset','foreign',0,'fake proof')")
            }
            assertEquals("ICON_CATALOG_SCHEMA_COLLISION", assertThrows(IllegalStateException::class.java) { open() }.message)
            room!!.close()
            raw {
                assertEquals(4, it.version)
                if (drop == "DROP TABLE ICON_CATALOG_STATE") it.rawQuery("SELECT generation,cursor,through FROM ICON_CATALOG_STATE", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(9L,c.getLong(0)); assertEquals(4L,c.getLong(1)); assertEquals(7L,c.getLong(2)); assertFalse(c.moveToNext())
                }
                if (drop == "DROP TABLE icon_catalog_entries") it.rawQuery("SELECT metadataHash FROM icon_catalog_entries", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals("fake proof", c.getString(0)); assertFalse(c.moveToNext())
                }
                it.execSQL(drop)
            }
        }
        val db = open(); assertEquals(before, snapshot(db)); assertEquals(5, db.version)
    }

    @Test fun finalSchemaFailureRollsBackBothCatalogTablesIndexIdentityAndAllSixOldTablesThenRepairsExactly() {
        val (before, structure) = seed()
        raw { it.execSQL("DROP INDEX $transferIndex") }
        assertThrows(IllegalStateException::class.java) { open() }; room!!.close()
        raw {
            assertEquals(4, it.version); absent(it)
            it.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("05aff81105a9e40dca1abe3a8357bd64", c.getString(0)); assertFalse(c.moveToNext())
            }
            val entity = schema(4).getValue("entities").jsonArray.map { e -> e.jsonObject }
                .single { e -> e.getValue("tableName").jsonPrimitive.content == "icon_transfers" }
            it.execSQL(entity.getValue("indices").jsonArray.single().jsonObject.getValue("createSql").jsonPrimitive.content
                .replace("\${TABLE_NAME}", "icon_transfers"))
        }
        val db = open(); assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db)); assertEquals(5, db.version)
        assertTrue(rows(db, "SELECT * FROM icon_catalog_state").isEmpty()); assertTrue(rows(db, "SELECT * FROM icon_catalog_entries").isEmpty())
    }
}
