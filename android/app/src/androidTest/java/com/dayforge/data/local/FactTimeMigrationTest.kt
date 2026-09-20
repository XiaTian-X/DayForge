package com.dayforge.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

@RunWith(AndroidJUnit4::class)
class FactTimeMigrationTest {
    private lateinit var context: Context
    private var room: HabitDatabase? = null
    private val databaseName = "habit_database"
    private val originalZone = TimeZone.getDefault()
    private val occurredAt = Instant.parse("2026-09-11T16:15:00Z").toEpochMilli()
    private val habitUuid = UUID.randomUUID().toString()
    private val metricUuid = UUID.randomUUID().toString()
    private val schema by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/1.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        HabitDatabaseProvider.clearInstanceForTesting()
        context.deleteDatabase(databaseName)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }

    @After fun cleanup() {
        room?.close()
        if (::context.isInitialized && context.packageName == "com.dayforge.testbed") {
            HabitDatabaseProvider.clearInstanceForTesting()
            context.deleteDatabase(databaseName)
        }
        TimeZone.setDefault(originalZone)
    }

    private fun seed(block: (SupportSQLiteDatabase) -> Unit = {}) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }
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

    // Exercise the actual migration registration and fallback policy used by the app.
    private fun open(): HabitDatabase {
        room?.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        return HabitDatabaseProvider.getInstance(context).also { room = it }
    }

    private fun payload(zone: String, at: Long = occurredAt) = buildJsonObject {
        put("occurred_at", Instant.ofEpochMilli(at).toString())
        put("timezone", zone)
        put("local_date", Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).toLocalDate().toString())
    }

    @Test fun migrationRestoresKnownMetadataAndMarksFallbackWithoutChangingFactsOrQueue() = runBlocking {
        val uuids = List(5) { UUID.randomUUID().toString() }
        val prepared = payload("America/New_York").toString()
        var originalQueue = emptyList<List<String?>>()
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
            originalQueue = queueSnapshot(db)
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
        assertEquals(originalQueue, queueSnapshot(db.openHelper.readableDatabase))
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
        val failure = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("3 to 2"))
        db.close()
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

    private fun snapshot(cursor: Cursor): List<List<String?>> = cursor.use {
        buildList {
            while (it.moveToNext()) add(List(it.columnCount) { column ->
                if (it.isNull(column)) null else it.getString(column)
            })
        }
    }

    private fun queueSnapshot(db: SupportSQLiteDatabase) =
        snapshot(db.query("SELECT * FROM sync_outbox ORDER BY id"))

    private fun rawDatabase() = SQLiteDatabase.openDatabase(
        context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }

    private fun metadata(zone: String, localDate: String, at: Long = occurredAt) = buildJsonObject {
        put("occurred_at", Instant.ofEpochMilli(at).toString())
        put("timezone", zone)
        put("local_date", localDate)
    }

    @Test fun failedBackfillRollsBackSchemaFactsAndQueueThenCanRetry() = runBlocking {
        var originalQueue = emptyList<List<String?>>()
        seed { db ->
            insert(db, "completions", mapOf("id" to 1, "habitId" to 1, "habitUuid" to habitUuid,
                "uuid" to "completion", "date" to occurredAt, "actualCompletedAt" to occurredAt, "value" to 4))
            insert(db, "metric_logs", mapOf("id" to 1, "metricId" to 1, "uuid" to "observation",
                "date" to occurredAt, "value" to 12))
            insert(db, "sync_outbox", mapOf("id" to 1, "operationId" to UUID.randomUUID().toString(),
                "recordType" to "completion", "entityUuid" to "completion", "wireEntityUuid" to "completion",
                "action" to "upsert", "payloadJson" to metadata("Asia/Shanghai", "2026-09-12").toString(),
                "attemptedAt" to occurredAt, "attemptCount" to 2, "lastError" to "response lost"))
            originalQueue = queueSnapshot(db)
            db.execSQL("CREATE TRIGGER sync_completions_update AFTER UPDATE ON completions BEGIN SELECT RAISE(ABORT, 'unexpected backfill event'); END")
            db.execSQL("CREATE TRIGGER reject_metric_backfill BEFORE UPDATE ON metric_logs BEGIN SELECT RAISE(ABORT, 'injected migration failure'); END")
        }
        val failure = runCatching { open().openHelper.writableDatabase }.exceptionOrNull()
        assertTrue("Expected SQLite failure, got $failure", failure is SQLiteException)
        assertTrue(failure!!.message.orEmpty().contains("injected migration failure"))
        room!!.close()
        rawDatabase().use { raw ->
            assertEquals(1, raw.version)
            for (table in listOf("completions", "metric_logs")) {
                assertTrue(columns(raw, table).intersect(setOf("recordedTimezone", "recordedLocalDate", "timeMetadataSource")).isEmpty())
            }
            assertEquals(listOf(listOf(occurredAt.toString(), occurredAt.toString(), "4")),
                snapshot(raw.rawQuery("SELECT date, actualCompletedAt, value FROM completions", null)))
            raw.rawQuery("SELECT date, value FROM metric_logs", null).use {
                assertTrue(it.moveToFirst()); assertEquals(occurredAt, it.getLong(0)); assertEquals(12.0, it.getDouble(1), 0.0)
            }
            assertEquals(originalQueue, snapshot(raw.rawQuery("SELECT * FROM sync_outbox ORDER BY id", null)))
            assertEquals(listOf(listOf("sync_completions_update")), snapshot(raw.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = 'sync_completions_update'", null)))
            assertEquals(listOf(listOf(schema.getValue("identityHash").jsonPrimitive.content)),
                snapshot(raw.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null)))
            raw.execSQL("DROP TRIGGER reject_metric_backfill")
        }
        val retried = open()
        assertEquals(2, retried.openHelper.writableDatabase.version)
        assertEquals(originalQueue, queueSnapshot(retried.openHelper.readableDatabase))
        assertEquals("Asia/Shanghai", retried.completionDao().getCompletionByUuid("completion")!!.recordedTimezone)
        assertEquals("America/Los_Angeles", retried.metricLogDao().getById(1)!!.recordedTimezone)
        retried.openHelper.writableDatabase.execSQL("UPDATE metric_logs SET value = 13 WHERE id = 1")
        assertEquals(2, retried.syncOutboxDao().count())
    }

    @Test fun unknownDevelopmentSchemaFailsValidationWithoutErasingRows() {
        seed { db ->
            insert(db, "completions", mapOf("id" to 1, "habitId" to 1, "habitUuid" to habitUuid,
                "uuid" to "completion", "date" to occurredAt, "value" to 4))
            db.execSQL("ALTER TABLE habits ADD COLUMN unexpectedDeveloperColumn TEXT NOT NULL DEFAULT 'legacy'")
        }
        val failure = runCatching { open().openHelper.writableDatabase }.exceptionOrNull()
        assertTrue("Expected schema validation failure, got $failure", failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("Migration didn't properly handle: habits"))
        room!!.close()
        rawDatabase().use { raw ->
            assertEquals(1, raw.version)
            assertEquals(listOf(listOf("Habit", "legacy")),
                snapshot(raw.rawQuery("SELECT name, unexpectedDeveloperColumn FROM habits", null)))
            assertEquals(listOf(listOf("completion", occurredAt.toString(), "4")),
                snapshot(raw.rawQuery("SELECT uuid, date, value FROM completions", null)))
            assertFalse(columns(raw, "completions").contains("recordedTimezone"))
            assertFalse(columns(raw, "metric_logs").contains("recordedTimezone"))
        }
    }

    @Test fun migrationUsesNewestValidPreparedMetadataBeforeShadowAndRejectsInvalidCandidates() = runBlocking {
        data class Case(val queued: List<String>, val shadow: JsonObject?, val zone: String, val date: String,
                        val source: String = "legacy_sync")
        val ny = metadata("America/New_York", "2026-09-11")
        val tokyo = metadata("Asia/Tokyo", "2026-09-12")
        val shanghai = metadata("Asia/Shanghai", "2026-09-12")
        val cases = listOf(
            Case(listOf(ny.toString(), tokyo.toString()), shanghai, "Asia/Tokyo", "2026-09-12"),
            Case(listOf(ny.toString(), "invalid json"), shanghai, "America/New_York", "2026-09-11"),
            Case(listOf(JsonObject(tokyo + ("event_type" to JsonPrimitive("revert"))).toString()), ny, "America/New_York", "2026-09-11"),
            Case(listOf(metadata("Asia/Tokyo", "2026-09-10").toString()), null, "America/Los_Angeles", "2026-09-11", "legacy_device_fallback"),
            Case(listOf(metadata("Mars/Olympus", "2026-09-12").toString()), shanghai, "Asia/Shanghai", "2026-09-12"),
            Case(listOf(metadata("Asia/Tokyo", "2026-09-12", occurredAt + 1).toString()), null, "America/Los_Angeles", "2026-09-11", "legacy_device_fallback"),
            Case(listOf(metadata("+08:00", "2026-09-12").toString()), null, "America/Los_Angeles", "2026-09-11", "legacy_device_fallback")
        )
        var originalQueue = emptyList<List<String?>>()
        seed { db ->
            var operation = 0
            cases.forEachIndexed { index, case ->
                val uuid = "candidate-$index"
                insert(db, "completions", mapOf("id" to index + 1, "habitId" to 1, "habitUuid" to habitUuid,
                    "uuid" to uuid, "date" to occurredAt, "actualCompletedAt" to occurredAt, "value" to index + 1))
                case.queued.forEach { json ->
                    insert(db, "sync_outbox", mapOf("id" to ++operation, "operationId" to UUID.randomUUID().toString(),
                        "recordType" to "completion", "entityUuid" to uuid, "wireEntityUuid" to uuid,
                        "action" to "upsert", "payloadJson" to json, "attemptedAt" to occurredAt, "attemptCount" to 2,
                        "baseRevision" to 7, "basePayloadJson" to "{\"previous\":true}", "lastError" to "response lost"))
                }
                case.shadow?.let { insert(db, "sync_entity_state", mapOf("entityType" to "activity_event",
                    "entityUuid" to uuid, "revision" to 7, "payloadJson" to it.toString())) }
            }
            originalQueue = queueSnapshot(db)
        }
        val db = open()
        cases.forEachIndexed { index, case ->
            val row = db.completionDao().getCompletionByUuid("candidate-$index")!!
            assertEquals("case $index", case.zone, row.recordedTimezone)
            assertEquals("case $index", case.date, row.recordedLocalDate)
            assertEquals("case $index", case.source, row.timeMetadataSource)
            assertEquals(occurredAt, row.date)
            assertEquals(occurredAt, row.actualCompletedAt)
            assertEquals(index + 1, row.value)
        }
        assertEquals(originalQueue, queueSnapshot(db.openHelper.readableDatabase))
    }

    @Test fun migrationPreservesExactUtcInstantsAcrossMidnightAndDstBoundariesAfterReopen() = runBlocking {
        data class Boundary(val millis: Long, val zone: String, val date: String, val completionDate: Long = millis)
        // Literal UTC milliseconds and local dates: expected dates do not use the migration's conversion path.
        val cases = listOf(
            Boundary(1789085700123L, "America/Los_Angeles", "2026-09-10", 1788999300123L),
            Boundary(1789129800456L, "Pacific/Kiritimati", "2026-09-12"),
            Boundary(1772953199987L, "America/New_York", "2026-03-08"),
            Boundary(1772953200123L, "America/New_York", "2026-03-08"),
            Boundary(1793511000456L, "America/New_York", "2026-11-01"),
            Boundary(1793514600789L, "America/New_York", "2026-11-01")
        )
        seed { db ->
            cases.forEachIndexed { index, case ->
                for ((table, entityType) in listOf("completions" to "activity_event", "metric_logs" to "metric_observation")) {
                    val common = mapOf("id" to index + 1, "uuid" to "$table-$index", "date" to case.millis, "value" to index + 1)
                    val parent = if (table == "completions") mapOf("habitId" to 1, "habitUuid" to habitUuid,
                        "actualCompletedAt" to case.millis, "date" to case.completionDate) else mapOf("metricId" to 1)
                    insert(db, table, common + parent)
                    insert(db, "sync_entity_state", mapOf("entityType" to entityType, "entityUuid" to "$table-$index",
                        "payloadJson" to metadata(case.zone, case.date, case.millis).toString()))
                }
            }
        }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals(2, open().openHelper.writableDatabase.version)
        room!!.close()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val db = open()
        cases.forEachIndexed { index, case ->
            val completion = db.completionDao().getCompletionByUuid("completions-$index")!!
            val observation = db.metricLogDao().getLogByUuid("metric_logs-$index")!!
            assertEquals(case.completionDate, completion.date)
            assertEquals(case.millis, completion.actualCompletedAt)
            assertEquals(case.millis, observation.date)
            assertEquals(index + 1, completion.value)
            assertEquals((index + 1).toDouble(), observation.value, 0.0)
            assertEquals(case.zone, completion.recordedTimezone)
            assertEquals(case.zone, observation.recordedTimezone)
            assertEquals(case.date, completion.recordedLocalDate)
            assertEquals(case.date, observation.recordedLocalDate)
            assertEquals("legacy_sync", completion.timeMetadataSource)
            assertEquals("legacy_sync", observation.timeMetadataSource)
        }
        assertEquals(0, db.syncOutboxDao().count())
    }
}
