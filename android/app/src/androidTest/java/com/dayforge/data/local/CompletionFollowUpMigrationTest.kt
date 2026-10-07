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
import com.dayforge.data.local.entity.CompletionMetricPromptEntity
import com.dayforge.data.local.entity.LocalFactSubmissionEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletionFollowUpMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val name = "habit_database"
    private val oldIdentity = "f44a5f0edbbc247af2f98079d03ecbc6"
    private val schema by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/3.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject
    }
    private val tables get() = schema.getValue("entities").jsonArray.map { it.jsonObject.getValue("tableName").jsonPrimitive.content }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(name)
        assertEquals(oldIdentity, schema.getValue("identityHash").jsonPrimitive.content)
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

    private fun rows(db: SupportSQLiteDatabase, query: String): List<List<String?>> = db.query(query).use { cursor ->
        buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) }) }
    }

    private fun snapshot(db: SupportSQLiteDatabase) = tables.associateWith { table ->
        val columns = schema.getValue("entities").jsonArray.first {
            it.jsonObject.getValue("tableName").jsonPrimitive.content == table
        }.jsonObject.getValue("fields").jsonArray.joinToString(",") {
            "`${it.jsonObject.getValue("columnName").jsonPrimitive.content}`"
        }
        rows(db, "SELECT $columns FROM `$table` ORDER BY rowid")
    }

    private fun oldStructure(db: SupportSQLiteDatabase) = rows(db,
        "SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
            "AND tbl_name NOT IN ('next_structural_dependencies','next_structural_supersessions','local_fact_submissions','completion_metric_prompts','one_time_transmissions','next_recovery_state','next_request_origins','next_transmissions', 'next_acceptances') " +
            "AND NOT (type='table' AND name IN ('habits','metrics')) " +
            "AND name NOT IN ('sync_habits_update','sync_metrics_update') ORDER BY type,name")

    private fun insert(db: SupportSQLiteDatabase, table: String, overrides: Map<String, Any>) {
        val entity = schema.getValue("entities").jsonArray.first { it.jsonObject.getValue("tableName").jsonPrimitive.content == table }.jsonObject
        val values = ContentValues()
        entity.getValue("fields").jsonArray.forEach {
            val field = it.jsonObject
            if (field["notNull"]?.jsonPrimitive?.boolean == true) {
                val column = field.getValue("columnName").jsonPrimitive.content
                if (field.getValue("affinity").jsonPrimitive.content == "TEXT") values.put(column, "") else values.put(column, 0)
            }
        }
        overrides.forEach { (key, value) -> when (value) {
            is String -> values.put(key, value)
            is Int -> values.put(key, value)
            else -> error("Unexpected fixture value")
        } }
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun seed(block: (SupportSQLiteDatabase) -> Unit = {}) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getValue("entities").jsonArray.forEach { element ->
                        val entity = element.jsonObject
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
            insert(db, "habits", mapOf("id" to 1, "uuid" to "activity", "name" to "Staged", "habitType" to "CHECK_IN",
                "schedule" to "{\"type\":\"daily\"}", "failMode" to "LOOSE", "completionPolicy" to "one_and_done", "oneTimeConfirmedVersion" to 0))
            insert(db, "completions", mapOf("id" to 1, "uuid" to "event", "habitId" to 1, "habitUuid" to "activity",
                "oneTimeAction" to "complete", "oneTimeExpectedVersion" to 0, "recordedTimezone" to "Asia/Shanghai",
                "recordedLocalDate" to "2026-09-27"))
            insert(db, "sync_outbox", mapOf("id" to 1, "operationId" to "operation", "recordType" to "one_time_completion",
                "entityUuid" to "event", "wireEntityUuid" to "event", "referenceUuid" to "activity", "action" to "upsert",
                "payloadJson" to "{\"preserve_even_unrecognized_bytes\":true}", "attemptedAt" to 123, "attemptCount" to 2))
            SyncSchemaCallback.onOpen(db)
            block(db)
        }
    }

    @Test fun versionThreeRowsAndFrozenOperationsRemainUnchangedWithoutInventingPrompts() = runBlocking {
        var before = emptyMap<String, List<List<String?>>>()
        var ddl = emptyList<List<String?>>()
        seed { before = snapshot(it); ddl = oldStructure(it) }
        repeat(2) {
            val db = open()
            val sql = db.openHelper.writableDatabase
            assertEquals(11, sql.version)
            assertEquals(before, snapshot(sql))
            assertEquals(ddl, oldStructure(sql))
            assertTrue(db.completionFollowUpDao().pendingPrompts().isEmpty())
            assertNull(db.completionFollowUpDao().submission("operation"))
            assertEquals(listOf(listOf("ok")), rows(sql, "PRAGMA integrity_check"))
            assertTrue(rows(sql, "PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun failureAfterReceiptTableRollsBackDdlAndCanRetryWithoutChangingOldQueue() {
        seed { it.execSQL("CREATE TABLE completion_metric_prompts (synthetic_collision TEXT)") }
        val failure = runCatching { open().openHelper.writableDatabase }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("already exists"))
        room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(3, raw.version)
            raw.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use {
                assertTrue(it.moveToFirst()); assertEquals(oldIdentity, it.getString(0))
            }
            raw.rawQuery("SELECT name FROM sqlite_master WHERE name='local_fact_submissions'", null).use { assertFalse(it.moveToFirst()) }
            raw.rawQuery("SELECT payloadJson,attemptedAt,attemptCount FROM sync_outbox", null).use {
                assertTrue(it.moveToFirst()); assertEquals("{\"preserve_even_unrecognized_bytes\":true}", it.getString(0))
                assertEquals(123, it.getInt(1)); assertEquals(2, it.getInt(2))
            }
            raw.execSQL("DROP TABLE completion_metric_prompts") // Only this test's injected collision.
        }
        assertEquals(11, open().openHelper.writableDatabase.version)
    }

    @Test fun receiptUniquenessAndAccountClearIncludeNewTables() = runBlocking {
        val db = open()
        val dao = db.completionFollowUpDao()
        val receipt = LocalFactSubmissionEntity("op", "activity_event", "event", "activity", "{}")
        dao.insertSubmission(receipt)
        assertTrue(runCatching { dao.insertSubmission(receipt.copy(operationId = "other")) }.isFailure)
        assertTrue(runCatching { dao.insertSubmission(receipt.copy(entityUuid = "different")) }.isFailure)
        dao.insertPrompt(CompletionMetricPromptEntity("event", "activity", entriesJson = "[]"))
        assertEquals(receipt, open().completionFollowUpDao().submission("op"))
        room!!.clearAllData()
        val reopened = open()
        assertNull(reopened.completionFollowUpDao().submission("op"))
        assertTrue(reopened.completionFollowUpDao().pendingPrompts().isEmpty())
        assertEquals(listOf(listOf("0")), rows(reopened.openHelper.readableDatabase, "SELECT suppressOutbox FROM sync_control WHERE id=1"))
    }
}
