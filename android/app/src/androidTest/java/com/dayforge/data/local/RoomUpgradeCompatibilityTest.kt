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
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Library upgrade regression: fixtures use the committed old schema, not new Room-generated DDL. */
@RunWith(AndroidJUnit4::class)
class RoomUpgradeCompatibilityTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val databaseName = "habit_database"
    private val instant = 1789129800456L
    private val schema by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/2.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject
    }
    private val tables get() = schema.getValue("entities").jsonArray.map {
        it.jsonObject.getValue("tableName").jsonPrimitive.content
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(databaseName)
        // Deliberately pin the pre-upgrade identity: regenerating a changed schema cannot bless this fixture.
        assertEquals("62d3fb801611543fbe1062b9713ffd2c", schema.getValue("identityHash").jsonPrimitive.content)
    }

    @After fun cleanup() {
        room?.close()
        if (::context.isInitialized && context.packageName == "com.dayforge.testbed") {
            HabitDatabaseProvider.clearInstanceForTesting()
            context.deleteDatabase(databaseName)
        }
    }

    private fun open(): HabitDatabase {
        room?.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        return HabitDatabaseProvider.getInstance(context).also { room = it }
    }

    private fun rows(db: SupportSQLiteDatabase, query: String): List<List<String?>> = db.query(query).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(List(cursor.columnCount) { column ->
                if (cursor.isNull(column)) null else cursor.getString(column)
            })
        }
    }

    private fun snapshot(db: SupportSQLiteDatabase) = (tables + "room_master_table").associateWith {
        rows(db, "SELECT * FROM `$it` ORDER BY rowid")
    }

    private fun structure(db: SupportSQLiteDatabase) = rows(db,
        "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")

    private fun insert(db: SupportSQLiteDatabase, table: String, overrides: Map<String, Any?>) {
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
        overrides.forEach { (key, value) -> when (value) {
            null -> values.putNull(key)
            is String -> values.put(key, value)
            is Int -> values.put(key, value)
            is Long -> values.put(key, value)
            is Double -> values.put(key, value)
            else -> error("Unsupported fixture value")
        } }
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
    }

    /** Synthetic stored-state fixture; this does not claim that a timer ran for real time. */
    private fun seed(assertSeed: (SupportSQLiteDatabase) -> Unit = {}) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName).callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getValue("entities").jsonArray.forEach { element ->
                        val entity = element.jsonObject
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
            insert(db, "habits", mapOf("id" to 1, "uuid" to "habit", "name" to "Stored habit", "habitType" to "CHECK_IN",
                "schedule" to "{\"type\":\"daily\"}", "failMode" to "STRICT", "isActive" to 1))
            insert(db, "habits", mapOf("id" to 2, "uuid" to "timer", "name" to "Stored timer", "habitType" to "TIMER",
                "schedule" to "{\"type\":\"daily\"}", "failMode" to "STRICT", "isActive" to 1))
            insert(db, "metrics", mapOf("id" to 1, "uuid" to "metric", "name" to "Stored metric", "unit" to "kg", "aggregationType" to "average"))
            val fact = mapOf("id" to 1, "date" to instant, "createdAt" to instant, "recordedTimezone" to "Pacific/Kiritimati",
                "recordedLocalDate" to "2026-09-12", "timeMetadataSource" to "captured")
            insert(db, "completions", fact + mapOf("habitId" to 1, "habitUuid" to "habit", "uuid" to "completion", "actualCompletedAt" to instant, "value" to 3))
            insert(db, "metric_logs", fact + mapOf("metricId" to 1, "uuid" to "observation", "value" to 12.25, "unit" to "kg"))
            insert(db, "habit_metric_links", mapOf("id" to 1, "habitId" to 1, "habitUuid" to "habit", "metricId" to 1,
                "metricUuid" to "metric", "uuid" to "link", "coefficient" to 1.5, "isActive" to 1))
            insert(db, "timelogs", mapOf("id" to 1, "habitId" to 2, "uuid" to "session", "startTime" to instant,
                "endTime" to instant + 60_000, "durationSeconds" to 60, "timerActiveElapsedMillis" to 60_000,
                "timerNextCommandSequence" to 3, "timerTimezone" to "Pacific/Kiritimati"))
            insert(db, "timer_segments", mapOf("id" to 1, "sessionUuid" to "session", "sequence" to 1,
                "startedAt" to instant, "endedAt" to instant + 60_000))
            insert(db, "timelog_day_allocations", mapOf("sessionUuid" to "session", "habitId" to 2,
                "localDate" to "2026-09-12", "localDateEpoch" to 20708L, "timezone" to "Pacific/Kiritimati", "durationMillis" to 60_000))
            insert(db, "timer_command_outbox", mapOf("id" to 1, "commandId" to "finish-command", "sessionUuid" to "session",
                "sequence" to 2, "commandType" to "finish", "occurredAt" to instant + 60_000, "activeElapsedMillis" to 60_000,
                "expectedRevision" to 1, "attemptCount" to 2, "lastError" to "response lost"))
            val queued = mapOf("recordType" to "completion", "entityUuid" to "completion", "wireEntityUuid" to "completion",
                "action" to "upsert", "payloadJson" to "{\"value\":3}", "attemptedAt" to instant, "attemptCount" to 2,
                "baseRevision" to 7, "basePayloadJson" to "{\"value\":2}", "lastError" to "response lost")
            insert(db, "sync_outbox", queued + mapOf("id" to 1, "operationId" to "pending-operation"))
            insert(db, "sync_outbox", queued + mapOf("id" to 2, "operationId" to "rejected-operation", "deadLetteredAt" to instant, "errorCode" to "conflict"))
            insert(db, "sync_entity_state", mapOf("entityType" to "activity_event", "entityUuid" to "completion", "revision" to 7,
                "payloadJson" to "{\"value\":2}", "payloadHash" to "stored-hash"))
            insert(db, "sync_conflicts", mapOf("id" to 1, "operationId" to "rejected-operation", "recordType" to "completion",
                "localEntityUuid" to "completion", "wireEntityUuid" to "completion", "entityType" to "activity_event",
                "action" to "upsert", "serverRevision" to 8, "localPayloadJson" to "{\"value\":3}", "serverPayloadJson" to "{\"value\":4}",
                "conflictingFieldsJson" to "[\"value\"]", "status" to "pending"))
            SyncSchemaCallback.onOpen(db)
            assertSeed(db)
        }
    }

    @Test fun committedSchemaAndEveryStoredTableSurviveOpenAndReopen() = runBlocking {
        var before = emptyMap<String, List<List<String?>>>()
        var ddl = emptyList<List<String?>>()
        seed { db -> before = snapshot(db); ddl = structure(db) }
        assertEquals(13, tables.size)
        before.forEach { (table, rows) -> assertTrue("Missing fixture for $table", rows.isNotEmpty()) }
        repeat(2) {
            val db = open()
            val sql = db.openHelper.writableDatabase
            assertEquals(2, sql.version)
            assertEquals(before, snapshot(sql))
            assertEquals(ddl, structure(sql))
            assertEquals(listOf(listOf("ok")), rows(sql, "PRAGMA integrity_check"))
            assertTrue(rows(sql, "PRAGMA foreign_key_check").isEmpty())
            val completion = db.completionDao().getCompletionByUuid("completion")!!
            assertEquals(instant, completion.actualCompletedAt)
            assertEquals("Pacific/Kiritimati", completion.recordedTimezone)
            assertEquals("2026-09-12", completion.recordedLocalDate)
            assertEquals(12.25, db.metricLogDao().getLogByUuid("observation")!!.value, 0.0)
            assertEquals(1, db.syncOutboxDao().count())
            assertEquals(1, db.syncOutboxDao().countDeadLetters())
        }
    }

    @Test fun upgradedDatabaseRetainsRollbackTriggersAndFlowInvalidation() = runBlocking {
        seed()
        val db = open()
        val habit = db.habitDao().getHabitById(1)!!
        val before = snapshot(db.openHelper.writableDatabase)
        val injected = IllegalStateException("rollback after business and trigger writes")
        val failure = runCatching {
            db.withTransaction {
                db.habitDao().update(habit.copy(name = "Rolled back"))
                assertEquals(2, db.syncOutboxDao().count())
                throw injected
            }
        }.exceptionOrNull()
        assertSame(injected, failure)
        assertEquals(before, snapshot(db.openHelper.readableDatabase))
        db.habitDao().getHabitByIdFlow(1).test {
            assertEquals(habit, awaitItem())
            db.syncOutboxDao().observePendingCount().test {
                assertEquals(1, awaitItem())
                db.withTransaction { db.habitDao().update(habit.copy(name = "Committed")) }
                assertEquals(2, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("Committed", awaitItem()!!.name)
            cancelAndIgnoreRemainingEvents()
        }
        val afterCommit = snapshot(db.openHelper.readableDatabase)
        val reopened = open()
        assertEquals(afterCommit, snapshot(reopened.openHelper.readableDatabase))
        reopened.habitDao().update(habit.copy(name = "After reopen"))
        assertEquals(3, reopened.syncOutboxDao().count())
        assertEquals(listOf("pending-operation", "habit", "habit"), reopened.syncOutboxDao().getAll().map {
            if (it.id == 1L) it.operationId else it.entityUuid
        })
    }
}
