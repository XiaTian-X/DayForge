package com.dayforge.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanMetadataMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val name = "habit_database"
    private val schema by lazy {
        Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/6.json").bufferedReader().use { it.readText() })
            .jsonObject.getValue("database").jsonObject
    }
    private val entities get() = schema.getValue("entities").jsonArray.map { it.jsonObject }
    private val tables get() = entities.map { it.getValue("tableName").jsonPrimitive.content }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(name)
        assertEquals("57fe775bbdfa092a98a5e2f3a8db03d4", schema.getValue("identityHash").jsonPrimitive.content)
    }
    @After fun cleanup() {
        room?.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(name)
    }
    private fun open(): HabitDatabase {
        room?.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        return HabitDatabaseProvider.getInstance(context).also { room = it }
    }
    private fun rows(db: SupportSQLiteDatabase, query: String): List<List<String?>> = db.query(query).use { c ->
        buildList { while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) }) }
    }
    private fun snapshot(db: SupportSQLiteDatabase) = entities.associate { entity ->
        val table = entity.getValue("tableName").jsonPrimitive.content
        val columns = entity.getValue("fields").jsonArray.joinToString(",") { "`${it.jsonObject.getValue("columnName").jsonPrimitive.content}`" }
        table to rows(db, "SELECT $columns FROM `$table` ORDER BY rowid")
    }
    private fun structure(db: SupportSQLiteDatabase) = rows(db,
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
            "AND name NOT LIKE 'room_%' AND name NOT IN ('android_metadata','habits','sync_habits_update') AND tbl_name NOT IN ('next_restart_materializations','next_restart_plan_proofs','next_challenge_state','next_challenge_rounds','next_challenge_births','count_days','next_sync_state','next_rejections','next_structural_dependencies','next_structural_supersessions','next_recovery_state','next_request_origins','next_transmissions','next_acceptances') ORDER BY type,name")
    private fun insert(db: SupportSQLiteDatabase, table: String, values: Map<String, Any>) {
        val content = ContentValues()
        entities.first { it.getValue("tableName").jsonPrimitive.content == table }.getValue("fields").jsonArray.forEach {
            val field = it.jsonObject
            if (field["notNull"]?.jsonPrimitive?.boolean == true) {
                val key = field.getValue("columnName").jsonPrimitive.content
                if (field.getValue("affinity").jsonPrimitive.content == "TEXT") content.put(key, "") else content.put(key, 0)
            }
        }
        values.forEach { (key, value) -> when (value) {
            is String -> content.put(key, value)
            is Int -> content.put(key, value)
            else -> error("Unexpected fixture value")
        } }
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, content)
    }
    private fun seed(block: (SupportSQLiteDatabase) -> Unit = {}) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(6) {
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
            }).build())
        helper.use {
            val db = it.writableDatabase
            insert(db, "habits", mapOf("id" to 1, "name" to "old habit", "uuid" to "habit", "habitType" to "CHECK_IN",
                "iconResId" to 53, "colorHex" to "#123456", "schedule" to "{\"type\":\"daily\"}", "failMode" to "LOOSE", "targetValue" to 1,
                "appearance" to "{\"icon\":{\"kind\":\"role\",\"role\":\"habit.custom\"},\"accent_color\":\"#123456\",\"icon_tint\":\"theme\"}"))
            insert(db, "sync_outbox", mapOf("operationId" to "frozen", "recordType" to "habit", "entityUuid" to "habit", "action" to "upsert",
                "payloadJson" to "{\"untouched\": true}", "attemptedAt" to 42, "attemptCount" to 2, "deadLetteredAt" to 43))
            insert(db, "one_time_transmissions", mapOf("operationId" to "event-op", "accountId" to "account", "serverInstanceId" to "server",
                "syncEpoch" to "epoch", "deviceId" to "first-device", "operationJson" to "{\"intent\":true}", "rejectionJson" to "{\"rejected\":true}"))
            SyncSchemaCallback.onOpen(db)
            block(db)
        }
    }
    @Test fun preservesAllOldColumnsReferencesAndFrozenRequestsWithoutInferringPlanningFields() = runBlocking {
        var before = emptyMap<String, List<List<String?>>>()
        var ddl = emptyList<List<String?>>()
        var columns = emptyList<List<String?>>()
        var trigger = ""
        seed {
            before = snapshot(it); ddl = structure(it); columns = rows(it, "PRAGMA table_info(habits)")
            trigger = rows(it, "SELECT sql FROM sqlite_master WHERE name='sync_habits_update'").single().single()!!
        }
        assertEquals(16, tables.size)
        repeat(2) {
            val db = open()
            val sql = db.openHelper.writableDatabase
            assertEquals(15, sql.version)
            assertEquals(before, snapshot(sql))
            assertEquals(ddl, structure(sql))
            val current = rows(sql, "PRAGMA table_info(habits)")
            assertEquals(columns, current.dropLast(1))
            assertEquals(listOf(columns.size.toString(), "planMetadata", "TEXT", "0", null, "0"), current.last())
            assertEquals(trigger, rows(sql, "SELECT sql FROM sqlite_master WHERE name='sync_habits_update'")
                .single().single()!!.replace(" OR OLD.planMetadata IS NOT NEW.planMetadata", ""))
            assertNull(db.habitDao().getHabitById(1)!!.planMetadata)
            assertEquals(listOf(listOf("ok")), rows(sql, "PRAGMA integrity_check"))
            assertTrue(rows(sql, "PRAGMA foreign_key_check").isEmpty())
        }
    }
    @Test fun failedUpgradeRollsBackColumnAndTriggerAndCanRetry() {
        var trigger = emptyList<List<String?>>()
        seed {
            trigger = rows(it, "SELECT sql FROM sqlite_master WHERE name='sync_habits_update'")
            it.execSQL("ALTER TABLE one_time_transmissions RENAME TO unavailable_transmissions")
        }
        assertTrue(runCatching { open().openHelper.writableDatabase }.exceptionOrNull()?.message.orEmpty().contains("Migration didn't properly handle"))
        room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(6, raw.version)
            raw.rawQuery("PRAGMA table_info(habits)", null).use { c -> while (c.moveToNext()) assertNotEquals("planMetadata", c.getString(1)) }
            raw.rawQuery("SELECT sql FROM sqlite_master WHERE name='sync_habits_update'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(trigger.single().single(), it.getString(0))
            }
            raw.execSQL("ALTER TABLE unavailable_transmissions RENAME TO one_time_transmissions")
        }
        assertEquals(15, open().openHelper.writableDatabase.version)
    }
    @Test fun forgedVersionSixIdentityCannotEraseOrModifyFrozenRows() {
        seed { it.execSQL("UPDATE room_master_table SET identity_hash='unknown' WHERE id=42") }
        assertTrue(runCatching { open().openHelper.writableDatabase }.exceptionOrNull()?.message.orEmpty().contains("integrity"))
        room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(6, raw.version)
            raw.rawQuery("SELECT payloadJson FROM sync_outbox", null).use { assertTrue(it.moveToFirst()); assertEquals("{\"untouched\": true}", it.getString(0)) }
        }
    }
}
