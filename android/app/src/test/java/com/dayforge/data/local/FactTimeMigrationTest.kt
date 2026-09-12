package com.dayforge.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.repository.SyncV2Mapper
import com.dayforge.data.repository.SyncV2Merger
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class FactTimeMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val databaseName = "fact-time-${UUID.randomUUID()}"
    private val originalZone = TimeZone.getDefault()
    private val occurredAt = Instant.parse("2026-09-11T16:15:00Z").toEpochMilli()
    private val habitUuid = UUID.randomUUID().toString()
    private val metricUuid = UUID.randomUUID().toString()
    private val schema by lazy {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream(
            "com.dayforge.data.local.HabitDatabase/1.json")).bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }

    @After fun cleanup() {
        room?.close()
        context.deleteDatabase(databaseName)
        TimeZone.setDefault(originalZone)
    }

    private fun seed(block: (SupportSQLiteDatabase) -> Unit = {}) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName).callback(object : SupportSQLiteOpenHelper.Callback(1) {
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
            insert(db, "habits", mapOf("id" to 1, "uuid" to habitUuid, "name" to "Habit",
                "habitType" to "CHECK_IN", "schedule" to "{\"type\":\"daily\"}", "failMode" to "STRICT"))
            insert(db, "metrics", mapOf("id" to 1, "uuid" to metricUuid, "name" to "Metric", "unit" to "kg",
                "aggregationType" to "average"))
            insert(db, "sync_control", mapOf("id" to 1, "suppressOutbox" to 0))
            block(db)
        }
    }

    /** Populate required v1 fields from the committed schema, never the current Room entities. */
    private fun insert(db: SupportSQLiteDatabase, table: String, overrides: Map<String, Any?>) {
        val entity = schema.getValue("entities").jsonArray.first {
            it.jsonObject.getValue("tableName").jsonPrimitive.content == table
        }.jsonObject
        val values = ContentValues()
        entity.getValue("fields").jsonArray.forEach {
            val field = it.jsonObject
            val name = field.getValue("columnName").jsonPrimitive.content
            if (field.getValue("notNull").jsonPrimitive.boolean) {
                if (field.getValue("affinity").jsonPrimitive.content == "TEXT") values.put(name, "") else values.put(name, 0)
            }
        }
        overrides.forEach { (key, value) -> when (value) {
            null -> values.putNull(key)
            is String -> values.put(key, value)
            is Int -> values.put(key, value)
            is Long -> values.put(key, value)
            else -> error("Unsupported test value")
        } }
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun open(): HabitDatabase = Room.databaseBuilder(context, HabitDatabase::class.java, databaseName)
        .addMigrations(FactTimeMigration).addCallback(SyncSchemaCallback).build().also { room = it }

    private fun payload(zone: String, at: Long = occurredAt) = buildJsonObject {
        put("occurred_at", Instant.ofEpochMilli(at).toString())
        put("timezone", zone)
        put("local_date", Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).toLocalDate().toString())
    }

    @Test fun migrationRestoresKnownMetadataAndMarksFallbackWithoutChangingFactsOrQueue() = runBlocking {
        val uuids = List(5) { UUID.randomUUID().toString() }
        val prepared = payload("America/New_York").toString()
        seed { db ->
            uuids.forEachIndexed { index, uuid ->
                insert(db, "completions", mapOf("id" to index + 1, "habitId" to 1, "uuid" to uuid,
                    "habitUuid" to habitUuid, "date" to occurredAt, "value" to 4,
                    "actualCompletedAt" to if (index == 4) null else occurredAt))
            }
            for (index in 0..3) {
                val shadow = payload("Asia/Shanghai", if (index == 3) occurredAt + 1 else occurredAt)
                insert(db, "sync_entity_state", mapOf("entityType" to "activity_event", "entityUuid" to uuids[index],
                    "revision" to 7, "payloadJson" to shadow.toString()))
            }
            insert(db, "sync_outbox", mapOf("id" to 1, "operationId" to UUID.randomUUID().toString(),
                "recordType" to "completion", "entityUuid" to uuids[1], "wireEntityUuid" to uuids[1],
                "action" to "upsert", "payloadJson" to prepared, "attemptedAt" to occurredAt, "attemptCount" to 1))
            insert(db, "sync_outbox", mapOf("id" to 2, "operationId" to UUID.randomUUID().toString(),
                "recordType" to "completion", "entityUuid" to uuids[2], "wireEntityUuid" to uuids[2],
                "action" to "upsert", "payloadJson" to "invalid json"))
            insert(db, "metric_logs", mapOf("id" to 1, "metricId" to 1, "uuid" to "observation", "date" to occurredAt))
            insert(db, "sync_entity_state", mapOf("entityType" to "metric_observation", "entityUuid" to "observation",
                "payloadJson" to payload("Asia/Shanghai").toString()))
            // A v1 update trigger must not observe the metadata backfill.
            db.execSQL("CREATE TRIGGER sync_completions_update AFTER UPDATE ON completions BEGIN SELECT RAISE(ABORT, 'unexpected backfill event'); END")
        }
        val db = open() // Room verifies the migrated schema against generated v2 metadata.
        val rows = db.completionDao().getAllCompletionsOnce().associateBy { it.uuid }
        assertEquals(2, db.openHelper.writableDatabase.version)
        assertEquals("Asia/Shanghai", rows.getValue(uuids[0]).recordedTimezone)
        assertEquals("2026-09-12", rows.getValue(uuids[0]).recordedLocalDate)
        assertEquals("America/New_York", rows.getValue(uuids[1]).recordedTimezone)
        assertEquals("Asia/Shanghai", rows.getValue(uuids[2]).recordedTimezone)
        for (index in 3..4) {
            assertEquals("America/Los_Angeles", rows.getValue(uuids[index]).recordedTimezone)
            assertEquals("2026-09-11", rows.getValue(uuids[index]).recordedLocalDate)
            assertEquals("legacy_device_fallback", rows.getValue(uuids[index]).timeMetadataSource)
        }
        rows.values.forEach { assertEquals(occurredAt, it.date); assertEquals(4, it.value) }
        assertEquals(occurredAt, rows.getValue(uuids[0]).actualCompletedAt)
        assertNull(rows.getValue(uuids[4]).actualCompletedAt)
        assertEquals("legacy_sync", rows.getValue(uuids[0]).timeMetadataSource)
        assertEquals("Asia/Shanghai", db.metricLogDao().getById(1)!!.recordedTimezone)
        val pending = db.syncOutboxDao().getAll()
        assertEquals(2, pending.size)
        assertEquals(prepared, pending[0].payloadJson)
        assertEquals(1, pending[0].attemptCount)
        assertEquals(occurredAt, pending[0].attemptedAt)
        // The replacement update trigger works normally after migration.
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET value = 5 WHERE id = 1")
        assertEquals(3, db.syncOutboxDao().count())
        db.close()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("America/Los_Angeles", open().completionDao().getCompletionByUuid(uuids[4])!!.recordedTimezone)
    }

    @Test fun freshFactsKeepCaptureMetadataAfterDeviceZoneChangeAndServerRoundTrip() = runBlocking {
        seed()
        val db = open()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val completion = CompletionEntity(habitId = 1, habitUuid = habitUuid, date = occurredAt, actualCompletedAt = occurredAt)
        val observation = MetricLogEntity(metricId = 1, date = occurredAt, value = 12.5, unit = "kg")
        db.completionDao().insert(completion)
        db.metricLogDao().insert(observation)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val eventPayload = SyncV2Mapper.completion(db.completionDao().getCompletionByUuid(completion.uuid)!!,
            db.habitDao().getHabitById(1)!!)
        val observationPayload = SyncV2Mapper.metricObservation(db.metricLogDao().getLogByUuid(observation.uuid)!!, metricUuid)
        listOf(eventPayload, observationPayload).forEach {
            assertEquals("Asia/Shanghai", it.getValue("timezone").jsonPrimitive.content)
            assertEquals("2026-09-12", it.getValue("local_date").jsonPrimitive.content)
            assertEquals(Instant.ofEpochMilli(occurredAt).toString(), it.getValue("occurred_at").jsonPrimitive.content)
        }
        val merger = SyncV2Merger(db, db.habitDao(), db.completionDao(), db.timeLogDao(), db.metricDao(),
            db.metricLogDao(), db.habitMetricLinkDao(), db.syncOutboxDao(), db.syncConflictDao())
        db.syncOutboxDao().getAll().forEach { db.syncOutboxDao().deleteById(it.id) }
        merger.apply(listOf(
            SyncV2Change(1, "activity_event", completion.uuid, "upsert", 1, eventPayload, "2026-09-12T00:00:00Z"),
            SyncV2Change(2, "metric_observation", observation.uuid, "upsert", 1, observationPayload, "2026-09-12T00:00:00Z")
        ))
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        val pulledCompletion = db.completionDao().getCompletionByUuid(completion.uuid)!!
        val pulledObservation = db.metricLogDao().getLogByUuid(observation.uuid)!!
        assertEquals(eventPayload, SyncV2Mapper.completion(pulledCompletion, db.habitDao().getHabitById(1)!!))
        assertEquals(observationPayload, SyncV2Mapper.metricObservation(pulledObservation, metricUuid))
        assertEquals("captured", pulledCompletion.timeMetadataSource)
        assertEquals("captured", pulledObservation.timeMetadataSource)
        assertEquals(0, db.syncOutboxDao().count())
        // Rebuild facts from a remote replica without any previous local metadata.
        db.completionDao().delete(pulledCompletion)
        db.metricLogDao().delete(pulledObservation)
        db.syncOutboxDao().getAll().forEach { db.syncOutboxDao().deleteById(it.id) }
        merger.applyAuthoritativeEntity("activity_event", completion.uuid, 2, eventPayload)
        merger.applyAuthoritativeEntity("metric_observation", observation.uuid, 2, observationPayload)
        val restored = db.metricLogDao().getLogByUuid(observation.uuid)!!
        assertEquals("server", restored.timeMetadataSource)
        assertEquals(observationPayload, SyncV2Mapper.metricObservation(restored, metricUuid))
        assertEquals(eventPayload, SyncV2Mapper.completion(db.completionDao().getCompletionByUuid(completion.uuid)!!,
            db.habitDao().getHabitById(1)!!))
    }

    @Test fun unsupportedDowngradeFailsWithoutErasingRows() {
        seed { it.version = 3 }
        val db = open()
        assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure)
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT COUNT(*) FROM habits", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            assertEquals(3, raw.version)
        }
    }

    @Test fun malformedRemoteTimeRollsBackWithoutReplacingLocalFact() = runBlocking {
        seed()
        val db = open()
        val log = MetricLogEntity(metricId = 1, date = occurredAt, value = 1.0, unit = "kg",
            recordedTimezone = "Asia/Shanghai", timeMetadataSource = "legacy_device_fallback")
        db.metricLogDao().insert(log)
        val merger = SyncV2Merger(db, db.habitDao(), db.completionDao(), db.timeLogDao(), db.metricDao(),
            db.metricLogDao(), db.habitMetricLinkDao(), db.syncOutboxDao(), db.syncConflictDao())
        val original = SyncV2Mapper.metricObservation(log, metricUuid)
        val pending = db.syncOutboxDao().getAll()
        for (badFields in listOf(mapOf("timezone" to JsonPrimitive("Invalid/Zone")),
            mapOf("local_date" to JsonPrimitive("2026-09-10")), mapOf("occurred_at" to JsonNull))) {
            assertTrue(runCatching {
                merger.applyAuthoritativeEntity("metric_observation", log.uuid, 1, JsonObject(original + badFields))
            }.isFailure)
            assertEquals(log.copy(id = db.metricLogDao().getLogByUuid(log.uuid)!!.id), db.metricLogDao().getLogByUuid(log.uuid))
            assertEquals(pending, db.syncOutboxDao().getAll())
        }
        merger.applyAuthoritativeEntity("metric_observation", log.uuid, 1, original)
        assertEquals("legacy_device_fallback", db.metricLogDao().getLogByUuid(log.uuid)!!.timeMetadataSource)
    }
}
