package com.dayforge.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Storage preparation only: these fixtures do not activate v5 or claim server acknowledgement. */
@RunWith(AndroidJUnit4::class)
class OneTimePreparationMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val databaseName = "habit_database"
    private val habitUuid = "11111111-1111-4111-8111-111111111111"
    private val eventUuid = "22222222-2222-4222-8222-222222222222"
    private val schema by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/2.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(databaseName)
        assertEquals("62d3fb801611543fbe1062b9713ffd2c", schema.getValue("identityHash").jsonPrimitive.content)
    }

    @After fun cleanup() {
        room?.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        if (::context.isInitialized && context.packageName == "com.dayforge.testbed") {
            context.deleteDatabase(databaseName)
        }
    }

    private fun open(): HabitDatabase {
        room?.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        return HabitDatabaseProvider.getInstance(context).also { room = it }
    }

    private fun seedV2(block: (SupportSQLiteDatabase) -> Unit = {}) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName).callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getValue("entities").jsonArray.forEach { entry ->
                        val entity = entry.jsonObject
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        fun sql(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                        db.execSQL(sql(entity.getValue("createSql")))
                        entity.getValue("indices").jsonArray.forEach { db.execSQL(sql(it.jsonObject.getValue("createSql"))) }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        helper.use {
            val db = it.writableDatabase
            insertV2(db, "habits", mapOf("id" to 1, "uuid" to habitUuid, "name" to "Legacy",
                "habitType" to "CHECK_IN", "schedule" to "{\"type\":\"daily\"}", "failMode" to "STRICT"))
            insertV2(db, "completions", mapOf("id" to 1, "habitId" to 1, "habitUuid" to habitUuid,
                "uuid" to eventUuid, "value" to 1, "recordedTimezone" to "Asia/Shanghai", "recordedLocalDate" to "2026-09-27"))
            insertV2(db, "sync_control", mapOf("id" to 1, "suppressOutbox" to 0))
            insertV2(db, "sync_outbox", mapOf("id" to 1, "operationId" to "frozen-operation", "recordType" to "completion",
                "entityUuid" to eventUuid, "wireEntityUuid" to eventUuid, "action" to "upsert",
                "payloadJson" to "{\"frozen\":true}", "attemptedAt" to 123, "attemptCount" to 2))
            SyncSchemaCallback.onOpen(db)
            block(db)
        }
    }

    private fun insertV2(db: SupportSQLiteDatabase, table: String, overrides: Map<String, Any>) {
        val entity = schema.getValue("entities").jsonArray.first {
            it.jsonObject.getValue("tableName").jsonPrimitive.content == table
        }.jsonObject
        val values = ContentValues()
        entity.getValue("fields").jsonArray.forEach {
            val field = it.jsonObject
            if (field.getValue("notNull").jsonPrimitive.boolean) {
                val name = field.getValue("columnName").jsonPrimitive.content
                if (field.getValue("affinity").jsonPrimitive.content == "TEXT") values.put(name, "") else values.put(name, 0)
            }
        }
        overrides.forEach { (name, value) -> when (value) {
            is String -> values.put(name, value)
            is Int -> values.put(name, value)
            else -> error("Unsupported test value")
        } }
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun raw() = SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null,
        SQLiteDatabase.OPEN_READWRITE)

    private fun assertOriginalRaw(db: SQLiteDatabase, completionTable: String = "completions") {
        assertEquals(2, db.version)
        db.rawQuery("SELECT name, uuid FROM habits", null).use {
            assertTrue(it.moveToFirst()); assertEquals("Legacy", it.getString(0)); assertEquals(habitUuid, it.getString(1))
            assertFalse(it.moveToNext())
        }
        db.rawQuery("SELECT uuid, value FROM $completionTable", null).use {
            assertTrue(it.moveToFirst()); assertEquals(eventUuid, it.getString(0)); assertEquals(1, it.getInt(1))
            assertFalse(it.moveToNext())
        }
        db.rawQuery("SELECT operationId, payloadJson, attemptedAt, attemptCount FROM sync_outbox", null).use {
            assertTrue(it.moveToFirst()); assertEquals("frozen-operation", it.getString(0))
            assertEquals("{\"frozen\":true}", it.getString(1)); assertEquals(123, it.getInt(2)); assertEquals(2, it.getInt(3))
            assertFalse(it.moveToNext())
        }
        db.rawQuery("PRAGMA table_info(habits)", null).use { cursor ->
            while (cursor.moveToNext()) assertFalse(cursor.getString(1).startsWith("oneTime") || cursor.getString(1) == "completionPolicy")
        }
        db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use {
            assertTrue(it.moveToFirst()); assertEquals("62d3fb801611543fbe1062b9713ffd2c", it.getString(0))
        }
    }

    @Test fun failureAfterHabitColumnsRollsBackAndCanRetryWithoutQueueChanges() = runBlocking {
        seedV2 { it.execSQL("ALTER TABLE completions RENAME TO unavailable_completions") }
        val failure = runCatching { open().openHelper.writableDatabase }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("no such table"))
        room!!.close()
        raw().use {
            assertOriginalRaw(it, "unavailable_completions")
            it.execSQL("ALTER TABLE unavailable_completions RENAME TO completions")
        }
        val db = open()
        assertNull(db.habitDao().getHabitById(1)!!.completionPolicy)
        assertNull(db.completionDao().getCompletionByUuid(eventUuid)!!.oneTimeAction)
        assertEquals(8, db.openHelper.writableDatabase.version)
        assertEquals(1, db.syncOutboxDao().count())
        assertEquals("{\"frozen\":true}", db.syncOutboxDao().getAll().single().payloadJson)
    }

    @Test fun overlappingLegacyVersionDoesNotBypassSchemaIdentityOrEraseData() {
        seedV2 { it.version = 3 }
        val failure = runCatching { open().openHelper.writableDatabase }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("integrity"))
        room!!.close()
        raw().use {
            assertEquals(3, it.version)
            it.version = 2 // Restore only the synthetic test header to inspect unchanged v2 rows.
            assertOriginalRaw(it)
        }
    }

    @Test fun policyEditQueuesOnceWhileConfirmedProjectionAndRollbackDoNotQueueStructure() = runBlocking {
        seedV2()
        val db = open()
        val original = db.habitDao().getHabitById(1)!!
        val initialized = original.copy(completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
            schedule = HabitSchedule.Once())
        db.habitDao().update(initialized)
        // Structure must be queued atomically; the separate v4 barrier prevents premature sending.
        assertEquals(2, db.syncOutboxDao().count())
        assertEquals("habit", db.syncOutboxDao().getAll().last().recordType)
        assertEquals(habitUuid, db.syncOutboxDao().getAll().last().entityUuid)
        val complete = CompletionEntity(habitId = 1, habitUuid = habitUuid, date = 1790438399000L,
            actualCompletedAt = 1790438399000L, recordedTimezone = "Asia/Shanghai",
            uuid = UUID.randomUUID().toString(), oneTimeAction = "complete", oneTimeExpectedVersion = 0)
        val injected = IllegalStateException("rollback after fact and trigger")
        val failure = runCatching {
            db.withTransaction {
                db.completionDao().insertForSync(complete)
                db.habitDao().update(initialized.copy(oneTimeConfirmedVersion = 1,
                    oneTimeConfirmedHeadEventUuid = complete.uuid, oneTimeConfirmedCompletionEventUuid = complete.uuid))
                assertEquals(3, db.syncOutboxDao().count())
                throw injected
            }
        }.exceptionOrNull()
        assertSame(injected, failure)
        assertNull(db.completionDao().getCompletionByUuid(complete.uuid))
        assertEquals(initialized, db.habitDao().getHabitById(1))
        assertEquals(2, db.syncOutboxDao().count())
        val undo = complete.copy(uuid = UUID.randomUUID().toString(), date = 1790524801000L,
            actualCompletedAt = 1790524801000L, recordedLocalDate = "2026-09-28", value = 0,
            oneTimeAction = "undo", oneTimeExpectedVersion = 1, oneTimeExpectedHeadEventUuid = complete.uuid,
            oneTimeRevertsEventUuid = complete.uuid)
        db.withTransaction {
            db.completionDao().insertForSync(complete)
            db.completionDao().insertForSync(undo)
        }
        val reopened = open()
        assertEquals(initialized, reopened.habitDao().getHabitById(1)) // Pending facts do not acknowledge baseline.
        val facts = reopened.completionDao().getAllCompletionsOnce().associateBy { it.uuid }
        assertEquals(complete.copy(id = facts.getValue(complete.uuid).id), facts.getValue(complete.uuid))
        assertEquals(undo.copy(id = facts.getValue(undo.uuid).id), facts.getValue(undo.uuid))
        assertEquals(4, reopened.syncOutboxDao().count())
        assertEquals("{\"frozen\":true}", reopened.syncOutboxDao().getAll().first().payloadJson)
    }

    @Test fun freshCreationAndAccountClearIncludeAllPreparationColumns() = runBlocking {
        val db = open()
        val habit = HabitEntity(name = "Fresh", habitType = HabitType.CHECK_IN, iconResId = 0,
            colorHex = "#000000", schedule = HabitSchedule.Once(), completionPolicy = "one_and_done",
            oneTimeConfirmedVersion = 0)
        val id = db.habitDao().insert(habit)
        db.completionDao().insertForSync(CompletionEntity(habitId = id, habitUuid = habit.uuid,
            date = 1790438399000L, oneTimeAction = "complete", oneTimeExpectedVersion = 0))
        assertEquals(habit.copy(id = id), open().habitDao().getHabitById(id))
        room!!.clearAllData()
        val reopened = open()
        assertNull(reopened.habitDao().getHabitById(id))
        assertEquals(0, reopened.completionDao().countAll())
        assertEquals(0, reopened.syncOutboxDao().count())
        reopened.openHelper.readableDatabase.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }
}
