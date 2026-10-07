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
class NextRequestMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val name = "habit_database"
    private val schema by lazy {
        Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/8.json").bufferedReader().use { it.readText() })
            .jsonObject.getValue("database").jsonObject
    }
    private val entities get() = schema.getValue("entities").jsonArray.map { it.jsonObject }
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(name)
        assertEquals("58e389f6a5e03849fe4f8ab17808d266", schema.getValue("identityHash").jsonPrimitive.content)
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
            "AND name NOT LIKE 'room_%' AND tbl_name NOT IN ('next_sync_state','next_rejections','next_structural_dependencies','next_structural_supersessions','next_request_origins','next_transmissions','next_acceptances') ORDER BY type,name")
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
        assertTrue(db.insert(table, SQLiteDatabase.CONFLICT_ABORT, content) > 0)
    }
    private fun seed(block: (SupportSQLiteDatabase) -> Unit = {}) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(8) {
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
                "schedule" to "{\"type\":\"daily\"}", "failMode" to "LOOSE", "targetValue" to 1, "planMetadata" to "uninterpreted planning bytes"))
            insert(db, "sync_outbox", mapOf("id" to 1, "operationId" to "frozen", "recordType" to "habit", "entityUuid" to "habit", "action" to "upsert",
                "payloadJson" to "{\"untouched\": true}", "attemptedAt" to 42, "attemptCount" to 2, "deadLetteredAt" to 43))
            insert(db, "one_time_transmissions", mapOf("operationId" to "event-op", "accountId" to "account", "serverInstanceId" to "server",
                "syncEpoch" to "epoch", "deviceId" to "first-device", "operationJson" to "{\"intent\":true}", "rejectionJson" to "{\"rejected\":true}"))
            insert(db, "local_fact_submissions", mapOf("operationId" to "event-op", "entityType" to "activity_event", "entityUuid" to "event",
                "referenceUuid" to "habit", "payloadJson" to "{\"intent\":true}"))
            insert(db, "completion_metric_prompts", mapOf("eventUuid" to "event", "activityUuid" to "habit", "state" to "pending", "entriesJson" to "[]"))
            insert(db, "timer_command_outbox", mapOf("id" to 1, "commandId" to "old-lost-response",
                "sessionUuid" to "old-session", "sequence" to 2, "commandType" to "stop", "occurredAt" to 70000,
                "expectedControlGeneration" to 1, "activeElapsedMillis" to 60000, "attemptCount" to 0))
            insert(db, "next_recovery_state", mapOf("id" to 1, "accountId" to "account", "serverInstanceId" to "server",
                "syncEpoch" to "epoch", "deviceId" to "device", "generation" to 2, "phase" to "accepted_data",
                "minimumCursor" to 12, "candidateCursor" to 13, "snapshotHash" to "unaltered-hash"))
            SyncSchemaCallback.onOpen(db)
            block(db)
        }
    }
    @Test fun everyOldColumnAndDdlSurvivesWithoutGuessingOriginsOrFirstDevices() = runBlocking {
        var before = emptyMap<String, List<List<String?>>>()
        var ddl = emptyList<List<String?>>()
        seed { before = snapshot(it); ddl = structure(it) }
        assertEquals(17, entities.size)
        repeat(2) {
            val db = open(); val sql = db.openHelper.writableDatabase
            assertEquals(12, sql.version); assertEquals(before, snapshot(sql)); assertEquals(ddl, structure(sql))
            assertFalse(db.nextRequestDao().hasAny())
            assertEquals(listOf(listOf("ok")), rows(sql, "PRAGMA integrity_check"))
            assertTrue(rows(sql, "PRAGMA foreign_key_check").isEmpty())
        }
    }
    @Test fun validationFailureRollsBackNewTableAndOldRowsBeforeARealRetry() {
        seed { it.execSQL("ALTER TABLE one_time_transmissions RENAME TO unavailable_transmissions") }
        assertTrue(runCatching { open().openHelper.writableDatabase }.exceptionOrNull()?.message.orEmpty().contains("Migration didn't properly handle"))
        room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(8, raw.version)
            raw.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('next_request_origins','next_transmissions')", null).use { assertFalse(it.moveToFirst()) }
            raw.rawQuery("SELECT payloadJson FROM sync_outbox", null).use {
                assertTrue(it.moveToFirst()); assertEquals("{\"untouched\": true}", it.getString(0))
            }
            raw.execSQL("ALTER TABLE unavailable_transmissions RENAME TO one_time_transmissions")
        }
        assertEquals(12, open().openHelper.writableDatabase.version)
    }
    @Test fun forgedVersionEightIdentityCannotInitializeJournalsOrModifyFrozenRows() {
        seed { it.execSQL("UPDATE room_master_table SET identity_hash='unknown' WHERE id=42") }
        assertTrue(runCatching { open().openHelper.writableDatabase }.exceptionOrNull()?.message.orEmpty().contains("integrity"))
        room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(8, raw.version)
            raw.rawQuery("SELECT payloadJson FROM sync_outbox", null).use {
                assertTrue(it.moveToFirst()); assertEquals("{\"untouched\": true}", it.getString(0))
            }
            raw.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('next_request_origins','next_transmissions')", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun missingExtraOrWrongStorageTypeIdentityCannotInitializeJournals() {
        for (statement in listOf("DELETE FROM room_master_table WHERE id=42",
            "INSERT INTO room_master_table VALUES(17,'unexpected')",
            "UPDATE room_master_table SET identity_hash=CAST(identity_hash AS BLOB) WHERE id=42")) {
            room?.close(); HabitDatabaseProvider.clearInstanceForTesting(); context.deleteDatabase(name)
            seed { it.execSQL(statement) }
            assertTrue(runCatching { open().openHelper.writableDatabase }.exceptionOrNull()?.message.orEmpty().contains("integrity"))
            room!!.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(8, raw.version)
                raw.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('next_request_origins','next_transmissions')", null).use { assertFalse(it.moveToFirst()) }
                raw.rawQuery("SELECT payloadJson FROM sync_outbox", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("{\"untouched\": true}", it.getString(0))
                }
            }
        }
    }
}
