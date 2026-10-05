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
class AccountIconTransferMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val name = AccountIconDatabase.NAME
    private var room: AccountIconDatabase? = null
    private val originalTables = listOf("icon_assets", "icon_packs", "icon_blob_reservations", "icon_blob_ready", "icon_pack_selection")
    private val index = "index_icon_transfers_accountId_serverInstanceId_syncEpoch_kind_targetId_revision_variant"
    private fun schema(version: Int) = instrumentation.context.assets
        .open("com.dayforge.data.appearance.AccountIconDatabase/$version.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
        }
    private fun rows(db: SupportSQLiteDatabase, sql: String) = db.query(sql).use { c ->
        buildList { while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) }) }
    }
    private fun snapshot(db: SupportSQLiteDatabase) = originalTables.associateWith { rows(db, "SELECT * FROM $it ORDER BY rowid") }
    private fun ddl(db: SupportSQLiteDatabase) = rows(db,
        "SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND tbl_name<>'icon_transfers' ORDER BY type,name")
    private fun seed(forged: Boolean = false): Pair<Map<String, List<List<String?>>>, List<List<String?>>> {
        val old = schema(3)
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
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
            if (forged) db.execSQL("ALTER TABLE icon_assets ADD COLUMN unexpected TEXT")
            snapshot(db) to ddl(db)
        }
    }
    private fun open() = AccountIconDatabase.open(app).also { room = it }.openHelper.writableDatabase
    private fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, 0).use(block)
    @Before fun setup() { check(app.packageName == "com.dayforge.testbed"); assertFalse(app.getDatabasePath(name).exists()) }
    @After fun cleanup() { room?.close(); assertTrue(app.deleteDatabase(name) || !app.getDatabasePath(name).exists()) }

    @Test fun directV3UpgradePreservesEveryOldColumnDdlReadyAndChoiceAndCreatesEmptyQueue() {
        val (before, structure) = seed()
        var db = open()
        assertEquals(4, db.version); assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db))
        assertTrue(rows(db, "SELECT * FROM icon_transfers").isEmpty())
        assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
        assertEquals(schema(4).getValue("identityHash").jsonPrimitive.content,
            rows(db, "SELECT identity_hash FROM room_master_table WHERE id=42").single().single())
        room!!.close(); db = open()
        assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db)); assertTrue(rows(db, "SELECT * FROM icon_transfers").isEmpty())
    }

    @Test fun forgedMissingExtraAndBlobIdentityRejectBeforeTransferDdlAndRepairCanRetry() {
        val (before, structure) = seed()
        for (mutation in listOf("UPDATE room_master_table SET identity_hash='forged'", "DELETE FROM room_master_table WHERE id=42",
            "INSERT INTO room_master_table VALUES(17,'extra')", "UPDATE room_master_table SET identity_hash=CAST(identity_hash AS BLOB)")) {
            raw { it.execSQL(mutation) }
            assertEquals("ICON_TRANSFER_SCHEMA_IDENTITY", assertThrows(IllegalStateException::class.java) { open() }.message)
            room!!.close()
            raw {
                assertEquals(3, it.version)
                it.rawQuery("SELECT name FROM sqlite_master WHERE name='icon_transfers'", null).use { c -> assertFalse(c.moveToFirst()) }
                it.execSQL("DELETE FROM room_master_table WHERE id=17")
                it.execSQL("INSERT OR REPLACE INTO room_master_table VALUES(42,'4e6a7afa36b3ad419626fcdfbe1e7817')")
            }
        }
        val db = open(); assertEquals(before, snapshot(db)); assertEquals(structure, ddl(db)); assertEquals(4, db.version)
    }

    @Test fun validForeignTableRowsViewAndReservedIndexCannotBeAdoptedAndExactRemovalAllowsRetry() {
        val (before, _) = seed()
        val entity = schema(4).getValue("entities").jsonArray.map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == "icon_transfers" }
        val statements = listOf(
            "CREATE VIEW icon_transfers AS SELECT accountId FROM icon_assets" to "DROP VIEW icon_transfers",
            entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", "ICON_TRANSFERS") to "DROP TABLE ICON_TRANSFERS",
            "CREATE INDEX $index ON icon_assets(accountId)" to "DROP INDEX $index",
            "CREATE INDEX ${index.uppercase()} ON icon_assets(accountId)" to "DROP INDEX ${index.uppercase()}"
        )
        for ((create, drop) in statements) {
            raw {
                it.execSQL(create)
                if (drop == "DROP TABLE ICON_TRANSFERS")
                    it.execSQL("INSERT INTO ICON_TRANSFERS VALUES('owner','server','epoch','opaque operation','declare_asset','asset',0,'','hash','complete',42,'device',NULL,'fake ack',0)")
            }
            assertEquals("ICON_TRANSFER_SCHEMA_COLLISION", assertThrows(IllegalStateException::class.java) { open() }.message)
            room!!.close()
            raw {
                assertEquals(3, it.version)
                if (drop == "DROP TABLE ICON_TRANSFERS") it.rawQuery("SELECT generation,confirmationHash FROM ICON_TRANSFERS", null).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(42L, c.getLong(0)); assertEquals("fake ack", c.getString(1)); assertFalse(c.moveToNext())
                }
                it.execSQL(drop)
            }
        }
        val db = open(); assertEquals(before, snapshot(db)); assertEquals(4, db.version)
        assertTrue(rows(db, "SELECT * FROM icon_transfers").isEmpty())
    }

    @Test fun finalSchemaFailureRollsBackBothNewObjectsIdentityAndAllOriginalRows() {
        val (before, _) = seed(forged = true)
        assertThrows(IllegalStateException::class.java) { open() }; room!!.close()
        raw {
            assertEquals(3, it.version)
            it.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('icon_transfers',?)", arrayOf(index)).use { c -> assertFalse(c.moveToFirst()) }
            it.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("4e6a7afa36b3ad419626fcdfbe1e7817", c.getString(0))
            }
            for ((table, expected) in before) it.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { c ->
                val actual = buildList { while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) }) }
                assertEquals(expected, actual)
            }
        }
    }
}
