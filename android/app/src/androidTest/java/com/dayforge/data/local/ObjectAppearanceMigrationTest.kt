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
import com.dayforge.data.model.HabitSchedule
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObjectAppearanceMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val name = "habit_database"
    private val oldIdentity = "74783c04d46cbc429e24583d7d075c18"
    private val schema by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/5.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject
    }
    private val entities get() = schema.getValue("entities").jsonArray.map { it.jsonObject }
    private val tables get() = entities.map { it.getValue("tableName").jsonPrimitive.content }

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
        val columns = entities.first { it.getValue("tableName").jsonPrimitive.content == table }
            .getValue("fields").jsonArray.joinToString(",") { "`${it.jsonObject.getValue("columnName").jsonPrimitive.content}`" }
        rows(db, "SELECT $columns FROM `$table` ORDER BY rowid")
    }

    private fun insert(db: SupportSQLiteDatabase, table: String, values: Map<String, Any>) {
        val entity = entities.first { it.getValue("tableName").jsonPrimitive.content == table }
        val content = ContentValues()
        entity.getValue("fields").jsonArray.forEach {
            val field = it.jsonObject
            if (field["notNull"]?.jsonPrimitive?.boolean == true) {
                val column = field.getValue("columnName").jsonPrimitive.content
                if (field.getValue("affinity").jsonPrimitive.content == "TEXT") content.put(column, "") else content.put(column, 0)
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
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
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
                "iconResId" to 53, "colorHex" to "#123456", "schedule" to "{\"type\":\"daily\"}", "failMode" to "LOOSE", "targetValue" to 1))
            insert(db, "metrics", mapOf("id" to 1, "name" to "old metric", "uuid" to "metric", "unit" to "kg", "iconResId" to 12, "colorHex" to "#654321"))
            insert(db, "sync_outbox", mapOf("operationId" to "frozen", "recordType" to "habit", "entityUuid" to "habit", "action" to "upsert",
                "payloadJson" to "{\"untouched\": true}", "attemptedAt" to 42, "attemptCount" to 2, "deadLetteredAt" to 43))
            insert(db, "local_fact_submissions", mapOf("operationId" to "event-op", "entityType" to "activity_event", "entityUuid" to "event",
                "referenceUuid" to "habit", "payloadJson" to "{\"intent\":true}"))
            insert(db, "completion_metric_prompts", mapOf("eventUuid" to "event", "activityUuid" to "habit", "state" to "pending", "entriesJson" to "[]"))
            insert(db, "one_time_transmissions", mapOf("operationId" to "event-op", "accountId" to "account", "serverInstanceId" to "server",
                "syncEpoch" to "epoch", "deviceId" to "first-device", "operationJson" to "{\"intent\":true}", "rejectionJson" to "{\"rejected\":true}"))
            SyncSchemaCallback.onOpen(db)
            block(db)
        }
    }

    @Test fun allOldColumnsConstraintsAndFrozenRequestsSurviveWithoutGuessingAppearance() = runBlocking {
        var before = emptyMap<String, List<List<String?>>>()
        var columns = emptyMap<String, List<List<String?>>>()
        var triggers = emptyList<List<String?>>()
        seed { db ->
            before = snapshot(db)
            columns = tables.associateWith { rows(db, "PRAGMA table_info(`$it`)") }
            triggers = rows(db, "SELECT name,sql FROM sqlite_master WHERE type='trigger' ORDER BY name")
        }
        assertEquals(16, tables.size)
        repeat(2) {
            val db = open()
            val sql = db.openHelper.writableDatabase
            assertEquals(13, sql.version)
            assertEquals(before, snapshot(sql))
            tables.forEach { table ->
                val current = rows(sql, "PRAGMA table_info(`$table`)")
                assertEquals(columns.getValue(table), current.filter { it[1] !in setOf("appearance", "planMetadata") })
                if (table in setOf("habits", "metrics")) assertEquals(
                    listOf(columns.getValue(table).size.toString(), "appearance", "TEXT", "0", null, "0"), current.single { it[1] == "appearance" })
            }
            val currentTriggers = rows(sql, "SELECT name,sql FROM sqlite_master WHERE type='trigger' ORDER BY name")
                .map { listOf(it[0], it[1]?.replace(" OR OLD.appearance IS NOT NEW.appearance OR OLD.completionPolicy IS NOT NEW.completionPolicy", "")
                    ?.replace(" OR OLD.appearance IS NOT NEW.appearance", "")
                    ?.replace(" OR OLD.planMetadata IS NOT NEW.planMetadata", "")) }
            assertEquals(triggers, currentTriggers)
            assertNull(db.habitDao().getHabitById(1)!!.appearance)
            assertNull(db.metricDao().getMetricById(1)!!.appearance)
            assertEquals(53, db.habitDao().getHabitById(1)!!.iconResId)
            assertEquals(listOf(listOf("ok")), rows(sql, "PRAGMA integrity_check"))
            assertTrue(rows(sql, "PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test fun failedMigrationRollsBackBothColumnsAndTriggerReplacementThenRetries() {
        var oldTriggers = emptyList<List<String?>>()
        seed {
            oldTriggers = rows(it, "SELECT name,sql FROM sqlite_master WHERE type='trigger' ORDER BY name")
            it.execSQL("ALTER TABLE one_time_transmissions RENAME TO unavailable_transmissions")
        }
        assertTrue(runCatching { open().openHelper.writableDatabase }.exceptionOrNull()?.message.orEmpty().contains("Migration didn't properly handle"))
        room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(5, raw.version)
            listOf("habits", "metrics").forEach { table ->
                raw.rawQuery("PRAGMA table_info($table)", null).use { cursor -> while (cursor.moveToNext()) assertNotEquals("appearance", cursor.getString(1)) }
            }
            raw.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='trigger' ORDER BY name", null).use { cursor ->
                val restored = buildList { while (cursor.moveToNext()) add(listOf(cursor.getString(0), cursor.getString(1))) }
                assertEquals(oldTriggers, restored)
            }
            raw.execSQL("ALTER TABLE unavailable_transmissions RENAME TO one_time_transmissions")
        }
        assertEquals(13, open().openHelper.writableDatabase.version)
    }

    @Test fun forgedVersionFiveIdentityFailsWithoutErasingRows() {
        seed { it.execSQL("UPDATE room_master_table SET identity_hash='unknown' WHERE id=42") }
        assertTrue(runCatching { open().openHelper.writableDatabase }.exceptionOrNull()?.message.orEmpty().contains("integrity"))
        room!!.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(5, raw.version)
            raw.rawQuery("SELECT payloadJson FROM sync_outbox", null).use { assertTrue(it.moveToFirst()); assertEquals("{\"untouched\": true}", it.getString(0)) }
        }
    }

    @Test fun typedReferencesAndOncePlanSurviveReopenWithAtomicStructuralOutbox() = runBlocking {
        seed()
        val db = open()
        db.syncOutboxDao().getAll().forEach { db.syncOutboxDao().deleteById(it.id) }
        db.syncOutboxDao().getDeadLetters().forEach { db.syncOutboxDao().deleteById(it.id) }
        val old = db.habitDao().getHabitById(1)!!
        val role = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "theme")
        val once = old.copy(appearance = role, schedule = HabitSchedule.Once("2028-02-29"), completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0)
        db.withTransaction { db.habitDao().updateForSync(once) }
        assertEquals(1, db.syncOutboxDao().count()) // Multiple structural fields still yield one operation.
        assertEquals(once, open().habitDao().getHabitById(1))
        val current = room!!
        current.habitDao().updateForSync(once)
        assertEquals(1, current.syncOutboxDao().count())
        current.habitDao().updateForSync(once.copy(oneTimeConfirmedVersion = 2))
        assertEquals(1, current.syncOutboxDao().count()) // Confirmation is not structural editing.
        val fixed = ObjectAppearance(IconReference.Asset("11111111-0000-4000-8000-000000000001"), "#654321", "object")
        val metric = current.metricDao().getMetricById(1)!!.copy(appearance = fixed)
        current.withTransaction { current.metricDao().updateForSync(metric) }
        assertEquals(2, current.syncOutboxDao().count())
        val before = current.syncOutboxDao().getAll()
        assertTrue(runCatching { current.withTransaction {
            current.metricDao().updateForSync(metric.copy(appearance = role)); error("synthetic rollback")
        } }.isFailure)
        assertEquals(before, current.syncOutboxDao().getAll())
        assertEquals(metric, open().metricDao().getMetricById(1))
        room!!.clearAllData()
        assertTrue(open().habitDao().getAllHabitsOnce().isEmpty())
        assertTrue(room!!.metricDao().getAllMetricsOnce().isEmpty())
    }
}
