package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.NextRecoveryStateEntity
import com.dayforge.domain.model.OneTimeProjection
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.OneTimeIntent
import com.dayforge.domain.model.PendingOneTimeIntent
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private data class RestoreNativeCell(val type: Int, val value: Any?)
private typealias RestoreRawImage = Map<String, List<Map<String, RestoreNativeCell>>>

@RunWith(AndroidJUnit4::class)
class NextCommonRestoreTest {
    private fun countParent() = activity().let { row -> revise(row, fields = arrayOf("activity" to JsonObject(
        row.payload.getValue("activity").jsonObject + mapOf("tracking_mode" to JsonPrimitive("count"), "target_value" to JsonPrimitive(99))))) }
    private fun counted(id: String = checkId, value: Int = 5) = fact(id).let { row -> row.copy(payload = JsonObject(row.payload +
        mapOf("value" to JsonPrimitive(value), "count_policy" to buildJsonObject { put("target_value", 10); put("is_countdown", false) }))) }
    private suspend fun countRead() = CountHistoryReader(db, tokens, sessions).read(
        requireNotNull(db.habitDao().getHabitByUuid(activityId)), java.time.LocalDate.parse("2026-09-28"))
    private suspend fun countReadRejectsWithoutWrites() {
        val before = durable()
        assertNotNull("Changed or reverted effective fact must be rejected", runCatching { countRead() }.exceptionOrNull())
        assertEquals(before, durable())
    }

    @Test fun acceptedCountSnapshotsAndColdReopenValidateEveryQuantityWithoutLocalOrigins() = runBlocking<Unit> {
        restore(snapshot(countParent(), counted(), counted(durationId, 6)))
        val row = db.completionDao().getCompletionByUuid(durationId)!!
        assertEquals(11L, countRead().todayQuantity); assertTrue(countRead().completedToday)
        assertEquals(CountDayPolicy(10, false), countRead().todayPolicy)
        storage.reopen()
        assertEquals(11L, countRead().todayQuantity)
        for (assignment in listOf("value=7", "actualCompletedAt=actualCompletedAt+1", "createdAt=createdAt+1",
            "recordedLocalDate='2026-09-29'", "recordedTimezone='UTC'")) {
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                db.openHelper.writableDatabase.execSQL("UPDATE completions SET $assignment WHERE id=?", arrayOf(row.id))
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
            countReadRejectsWithoutWrites()
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                db.completionDao().upsert(row)
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
            assertEquals(11L, countRead().todayQuantity)
        }
        db.openHelper.writableDatabase.execSQL("UPDATE sync_entity_state SET payloadHash='damaged' WHERE entityUuid=?", arrayOf(durationId))
        countReadRejectsWithoutWrites()
    }

    @Test fun acceptedRevertPreventsAResurrectedProjectionFromCountingAgain() = runBlocking<Unit> {
        val event = counted()
        restore(snapshot(countParent(), event, fact(undoId, "revert", target = checkId)))
        assertEquals(0L, countRead().todayQuantity); assertEquals(CountDayPolicy(10, false), countRead().todayPolicy)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val h = requireNotNull(db.habitDao().getHabitByUuid(activityId))
            db.completionDao().insertForSync(NextCommonFactMapper.completion(event, h))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        countReadRejectsWithoutWrites()
    }

    @Test fun acceptedHistoricalCheckInUnderTodaysCountingPlanRetainsUnknownRuleAndActualQuantity() = runBlocking<Unit> {
        val old = fact(kind = "check_in").let { it.copy(payload = JsonObject(it.payload + ("value" to JsonPrimitive(1)))) }
        restore(snapshot(countParent(), old))
        val before = durable(); val history = countRead()
        assertEquals(1L, history.todayQuantity); assertNull(history.todayPolicy)
        assertEquals(setOf(java.time.LocalDate.parse("2026-09-28")), history.unknownDates)
        assertTrue(history.qualifiedDates.isEmpty()); assertFirstQualificationOnly(before, history)
    }

    @Test fun acceptedHistoricalCheckInCanCoexistWithCountFactsWithoutInventingItsCountPolicy() = runBlocking<Unit> {
        val old = fact(durationId, "check_in").let { it.copy(payload = JsonObject(it.payload + ("value" to JsonPrimitive(1)))) }
        restore(snapshot(countParent(), counted(), old))
        val before = durable(); val history = countRead()
        // Preserve the existing aggregation (5 + 1), not a tracking-mode conversion feature.
        assertEquals(6L, history.todayQuantity); assertEquals(CountDayPolicy(10, false), history.todayPolicy)
        assertFalse(history.completedToday); assertTrue(history.qualifiedDates.isEmpty())
        assertFalse(db.syncOutboxDao().getState("activity_event", durationId)!!.payloadJson!!.contains("count_policy"))
        assertFirstQualificationOnly(before, history)
    }

    @Test fun acceptedUndoneCountRestoresOriginalDayWithoutUsingTodaysMutableConfiguration() = runBlocking<Unit> {
        val parent = activity().let { row -> revise(row, fields = arrayOf("activity" to JsonObject(
            row.payload.getValue("activity").jsonObject + mapOf("tracking_mode" to JsonPrimitive("count"), "target_value" to JsonPrimitive(99))))) }
        val counted = fact().let { row -> row.copy(payload = JsonObject(row.payload + ("count_policy" to
            buildJsonObject { put("target_value", 10); put("is_countdown", true) }))) }
        val undo = fact(undoId, "revert", target = checkId)
        val response = snapshot(parent, counted, undo)
        restore(response)
        val habit = requireNotNull(db.habitDao().getHabitByUuid(activityId))
        assertEquals(99, habit.targetValue)
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
        assertEquals(10, db.countDayDao().get(habit.id, "2026-09-28")!!.targetValue)
        assertTrue(db.countDayDao().get(habit.id, "2026-09-28")!!.isCountdown)
        assertEquals(checkId, db.countDayDao().get(habit.id, "2026-09-28")!!.firstEventUuid)
        assertNull(db.countDayDao().get(habit.id, "2026-09-28")!!.originRequestId)
        val accepted = durable()
        storage.reopen()
        restore(response)
        assertRestoreGenerationOnly(accepted, 6L)
    }

    @Test fun mixedCountRulesAndLateDayMutationCannotPartiallyRestoreAcceptedSnapshot() = runBlocking<Unit> {
        val policy = buildJsonObject { put("target_value", 10); put("is_countdown", false) }
        val counted = fact().let { it.copy(payload = JsonObject(it.payload + ("count_policy" to policy))) }
        val parent = activity()
        val unknown = fact(linkId)
        val before = durable()
        assertTrue(runCatching { snapshot(parent, counted, unknown) }.isFailure)
        assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER damage_count_day AFTER INSERT ON sync_entity_state
            BEGIN UPDATE count_days SET targetValue=11; END""")
        val withFault = durable()
        assertTrue(runCatching { restore(snapshot(parent, counted)) }.isFailure)
        assertEquals(withFault, durable())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER damage_count_day")
        restore(snapshot(parent, counted))
        val habit = requireNotNull(db.habitDao().getHabitByUuid(activityId))
        assertEquals(10, db.countDayDao().get(habit.id, "2026-09-28")!!.targetValue)
        assertEquals(5, db.completionDao().getByHabitOnce(habit.id).single().value)
    }
    @Test fun lateRecoveryCheckpointCannotAcceptAChangedOrMissingOriginalCountDay() = runBlocking<Unit> {
        val counted = fact().let { it.copy(payload = JsonObject(it.payload + ("count_policy" to
            buildJsonObject { put("target_value", 10); put("is_countdown", false) }))) }
        val response = snapshot(activity(), counted)
        val context = sync().context()
        val stage = sync().beginRecovery(context, null)
        for (fault in listOf("UPDATE count_days SET targetValue=11;", "DELETE FROM count_days;")) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER damage_count_checkpoint AFTER UPDATE ON next_recovery_state BEGIN $fault END")
            val before = durable()
            assertTrue(runCatching { sync().acceptRecovery(context, stage, response) }.isFailure)
            assertEquals(before, durable())
            assertEquals(stage, sync().recoveryState(context))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER damage_count_checkpoint")
            storage.reopen()
            assertTrue(db.countDayDao().observeAll().first().isEmpty())
            assertEquals(stage, sync().recoveryState(context))
        }
        val accepted = sync().acceptRecovery(context, stage, response)
        val parent = requireNotNull(db.habitDao().getHabitByUuid(activityId))
        assertEquals(10, db.countDayDao().get(parent.id, "2026-09-28")!!.targetValue)
        storage.reopen()
        assertEquals(accepted, sync().acceptRecovery(context, stage, response))
    }

    @get:Rule val storage = PhysicalDatabaseRule()
    private val db get() = storage.database
    private val sessions = AccountSessionCoordinator()
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private val taskId = "82000000-0000-4000-8000-000000000001"
    private val goalId = "82000000-0000-4000-8000-000000000002"
    private val metricId = "82000000-0000-4000-8000-000000000003"
    private val device = "82000000-0000-4000-8000-000000000004"
    private val eventId = "82000000-0000-4000-8000-000000000005"
    private val stamp = "2026-09-27T16:00:01Z"
    private val account = "85000000-0000-4000-8000-000000000001"
    private val server = "85000000-0000-4000-8000-000000000002"
    private val epoch = "85000000-0000-4000-8000-000000000003"
    private fun sync(now: () -> Long = System::currentTimeMillis) =
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences), now)
    private val template by lazy {
        Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets.open("next/api.json")
            .bufferedReader().use { it.readText() }).jsonObject.getValue("task").jsonObject
    }
    @Before fun setup() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "structures-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        tokens = TokenManager(store); preferences = PreferencesManager(store, tokens = tokens)
        tokens.saveLoginSession("synthetic-a", "synthetic-r", "member", account, false)
        tokens.saveServerIdentity(server, epoch)
        tokens.saveDeviceRegistration(device, setOf("sync.read", "facts.append"), false, 1)
    }
    @After fun cleanup() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(file.delete() || !file.exists())
    }
    private fun change(type: String, id: String, body: JsonObject, revision: Long = 1) = SyncV2Change(0, type, id, "upsert", revision,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(id), "revision" to JsonPrimitive(revision),
            "created_at" to JsonPrimitive(stamp), "updated_at" to JsonPrimitive(stamp), "deleted_at" to JsonNull)), stamp)
    private fun task(id: String = taskId, name: String = "task", revision: Long = 1, parent: String? = null) = change("plan_node", id,
        JsonObject(template + mapOf("title" to JsonPrimitive(name), "parent_uuid" to (parent?.let(::JsonPrimitive) ?: JsonNull))), revision)
    private fun goal() = change("plan_node", goalId, JsonObject(template + mapOf(
        "title" to JsonPrimitive("goal"), "node_kind" to JsonPrimitive("goal"), "activity" to JsonNull,
        "appearance" to Json.parseToJsonElement("""{"icon":{"kind":"role","role":"goal.custom"},"accent_color":"#123456","icon_tint":"theme"}"""),
        "goal" to Json.parseToJsonElement("""{"start_date":"2026-01-01","due_date":"2026-12-31","target_cycles":null,
            "failure_policy":{"schema_version":1,"type":"strict"},"evaluation_policy":{"schema_version":1,"type":"manual"},"manual_result":null}"""))))
    private fun metric() = change("metric", metricId, Json.parseToJsonElement("""{"name":"metric","description":"","unit":"kg",
        "decimal_places":2,"aggregation_type":"average","target_direction":null,"target_value":null,"target_value_upper":null,
        "status":"active","appearance":{"icon":{"kind":"role","role":"metric.custom"},"accent_color":"#123456","icon_tint":"object"}}""").jsonObject)
    private fun completed() = change("activity_event", eventId, Json.parseToJsonElement("""{
        "activity_uuid":"$taskId","event_type":"check_in","value":"1","duration_seconds":null,"duration_milliseconds":null,
        "started_at":null,"ended_at":null,"external_event_id":null,"reverts_event_uuid":null,"occurred_at":"$stamp","received_at":"$stamp",
        "local_date":"2026-09-28","timezone":"Asia/Shanghai","source_type":"app","source_device_id":"$device","note":"","metadata":{},
        "one_time":{"event_uuid":"$eventId","action":"complete","expected_version":0,"expected_head_event_uuid":null,"reverts_event_uuid":null},
        "one_time_state_after":{"version":1,"head_event_uuid":"$eventId","completion_event_uuid":"$eventId"}}""").jsonObject)
    private fun snapshot(vararg changes: SyncV2Change): NextSyncBootstrapResponse {
        val checkpoints = changes.filter { it.entityType == "plan_node" &&
            (it.payload["activity"] as? JsonObject)?.get("completion_policy") == JsonPrimitive("one_and_done") }.map { parent ->
            val event = changes.singleOrNull { it.entityType == "activity_event" && it.payload["activity_uuid"] == JsonPrimitive(parent.entityUuid) }
            OneTimeProjection(parent.entityUuid, event?.let { Json.decodeFromJsonElement<OneTimeState>(it.payload.getValue("one_time_state_after")) }
                ?: OneTimeState(0, null, null))
        }
        return NextSyncBootstrapResponse(changes.toList(), 20, stamp, checkpoints)
    }
    private suspend fun restore(response: NextSyncBootstrapResponse) = sync().restoreAcceptedData(sync().context(), response)
    private fun revise(row: SyncV2Change, revision: Long = row.revision + 1, vararg fields: Pair<String, JsonElement>) =
        change(row.entityType, row.entityUuid, JsonObject(row.payload + fields), revision)
    private fun recurring(row: SyncV2Change): SyncV2Change = revise(row, fields = arrayOf(
        "activity" to JsonObject(row.payload.getValue("activity").jsonObject + mapOf(
            "completion_policy" to JsonPrimitive("recurring"),
            "recurrence_rule" to Json.parseToJsonElement("""{"schema_version":1,"type":"daily","interval":1,"start_date":null}"""))),
        "appearance" to goal().payload.getValue("appearance")))
    private val activityId = "84000000-0000-4000-8000-000000000001"
    private val checkId = "84000000-0000-4000-8000-000000000002"
    private val durationId = "84000000-0000-4000-8000-000000000003"
    private val observationId = "84000000-0000-4000-8000-000000000004"
    private val linkId = "84000000-0000-4000-8000-000000000005"
    private val undoId = "84000000-0000-4000-8000-000000000006"
    private fun activity(id: String = activityId) = recurring(task(id, id)).let { it.copy(payload = JsonObject(it.payload +
        ("appearance" to Json.parseToJsonElement("""{"icon":{"kind":"role","role":"habit.custom"},"accent_color":"#123456","icon_tint":"theme"}""")))) }
    private fun source(time: String = stamp, day: String = "2026-09-28") = buildJsonObject {
        put("occurred_at", time); put("received_at", stamp); put("local_date", day); put("timezone", "Asia/Shanghai")
        put("source_type", "app"); put("source_device_id", device); put("external_event_id", JsonNull)
        put("note", ""); put("metadata", buildJsonObject {})
    }
    private fun fact(id: String = checkId, kind: String = "count_snapshot", parent: String = activityId, target: String? = null) =
        change("activity_event", id, JsonObject(source() + buildJsonObject {
            put("activity_uuid", parent); put("event_type", kind); put("value", if (kind == "revert") JsonNull else JsonPrimitive("5"))
            put("reverts_event_uuid", target?.let(::JsonPrimitive) ?: JsonNull)
            listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at").forEach { put(it, JsonNull) }
        }))
    private fun duration() = change("activity_event", durationId, JsonObject(fact(durationId).payload + source("2026-09-27T16:00:30.000500Z", "2026-09-27") +
        mapOf("event_type" to JsonPrimitive("duration_session"), "value" to JsonNull,
            "started_at" to JsonPrimitive("2026-09-27T15:59:30.000500Z"), "ended_at" to JsonPrimitive("2026-09-27T16:00:30.000500Z"),
            "duration_seconds" to JsonPrimitive(60), "duration_milliseconds" to JsonPrimitive(60000),
            "day_allocations" to Json.parseToJsonElement("""[
                {"local_date":"2026-09-27","timezone":"Asia/Shanghai","duration_milliseconds":29999},
                {"local_date":"2026-09-28","timezone":"Asia/Shanghai","duration_milliseconds":30001}]"""))))
    private fun observation() = change("metric_observation", observationId, JsonObject(source() +
        mapOf("metric_uuid" to JsonPrimitive(metricId), "value" to JsonPrimitive("12.125"), "unit" to JsonPrimitive("kg"))))
    private fun link(revision: Long = 1, prompt: Boolean = true) = change("activity_metric_link", linkId, buildJsonObject {
        put("activity_uuid", activityId); put("metric_uuid", metricId); put("coefficient", "1.25")
        put("show_in_activity_detail", true); put("prompt_on_complete", prompt); put("is_active", true)
    }, revision)
    private fun all() = snapshot(duration(), completed(), observation(), fact(), link(), task(parent = goalId), goal(), activity(), metric())
    /** Complete main storage, including control/root/nodes/routes/command rows and SQLite rows.
     * A TEMP change journal must never shadow main.sqlite_sequence. Values retain native types.
     */
    private fun durable(): RestoreRawImage {
        val sql = db.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) {
                assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(0))
                add(cursor.getString(0).also { require(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) })
            } }
        }
        return tables.associateWith { table -> sql.query("SELECT * FROM main.`$table` ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add(buildMap {
                for (column in 0 until cursor.columnCount) {
                    val type = cursor.getType(column)
                    val value: Any? = when (type) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits()
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                        else -> error("Unexpected SQLite native type: $type")
                    }
                    put(cursor.getColumnName(column), RestoreNativeCell(type, value))
                }
            }) }
        } }
    }

    private fun control(image: RestoreRawImage): Map<String, RestoreNativeCell> =
        image.getValue("count_trust_control").single().also { row ->
            assertEquals(setOf("id", "storageId", "generation", "dirty"), row.keys)
            assertEquals(RestoreNativeCell(Cursor.FIELD_TYPE_INTEGER, 1L), row.getValue("id"))
            assertEquals(Cursor.FIELD_TYPE_STRING, row.getValue("storageId").type)
            assertTrue(com.dayforge.domain.model.isContractUuid(row.getValue("storageId").value as String))
            assertEquals(Cursor.FIELD_TYPE_INTEGER, row.getValue("generation").type)
            assertTrue((row.getValue("generation").value as Long) >= 0L)
            assertEquals(RestoreNativeCell(Cursor.FIELD_TYPE_INTEGER, 1L), row.getValue("dirty"))
        }

    private fun withGeneration(before: RestoreRawImage, delta: Long): RestoreRawImage {
        require(delta > 0L)
        val row = control(before)
        val expected = row + ("generation" to RestoreNativeCell(Cursor.FIELD_TYPE_INTEGER,
            Math.addExact(row.getValue("generation").value as Long, delta)))
        return before + ("count_trust_control" to listOf(expected))
    }

    /** Only named SUCCESSFUL direct restores use this helper. Rejections and acceptRecovery's
     * persisted-receipt fast return still compare the COMPLETE native image without allowance.
     * Known replay work: three suppressOutbox 0->1->0 pairs = 6; one completion UPDATE = +1;
     * all() adds completion + timer + metric UPDATEs and two allocation DELETE/INSERT pairs = +7.
     */
    private fun assertRestoreGenerationOnly(before: RestoreRawImage, expectedDelta: Long): RestoreRawImage {
        require(expectedDelta in setOf(6L, 7L, 13L))
        val expected = withGeneration(before, expectedDelta)
        val actual = durable()
        assertEquals(expected, actual)
        return actual
    }

    /** First legitimate COUNT read may issue only its complete, authenticated derived cache.
     * No rejected read is preheated. Every other table/column stays exact, then a same-root read
     * must preserve the entire native image, including every BLOB and the control row.
     */
    private suspend fun assertFirstQualificationOnly(before: RestoreRawImage,
        history: com.dayforge.domain.model.CountHistory) {
        val oldControl = control(before)
        assertTrue(before.getValue("count_trust_root").isEmpty())
        assertTrue(before.getValue("count_trust_nodes").isEmpty())
        val issued = durable()
        val cleared = oldControl + ("dirty" to RestoreNativeCell(Cursor.FIELD_TYPE_INTEGER, 0L))
        assertEquals(listOf(cleared), issued.getValue("count_trust_control"))
        assertEquals(before + mapOf(
            "count_trust_control" to listOf(cleared),
            "count_trust_root" to issued.getValue("count_trust_root"),
            "count_trust_nodes" to issued.getValue("count_trust_nodes")), issued)
        sessions.exclusive { db.withTransaction {
            val authority = requireNotNull(tokens.localCoreWriteAccess())
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            owner.maintenance(db) {
                val sql = db.openHelper.writableDatabase
                CountTrustSchema.validate(sql)
                val header = requireNotNull(CountTrustSeal.read(sql))
                assertEquals(CountTrustSeal.VALIDATOR, header.validator)
                assertEquals(1L, header.revision)
                assertEquals(oldControl.getValue("generation").value as Long, header.generation)
                val binding = CountTrustSeal.binding(authority, oldControl.getValue("storageId").value as String)
                val schemaHash = CountTrustIndex.hash(JsonArray(owner.schemaSnapshot().map { row ->
                    JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull })
                }).toString().toByteArray(Charsets.UTF_8))
                assertTrue("Issued root must authenticate the current scope and actual schema",
                    CountTrustSeal().matches(header, binding, schemaHash))
                val index = CountTrustIndex(sql, binding, header.root) {}
                index.verifyContents() // ALL reachable tree nodes and full value chunks, not one point.
                assertEquals(CountTrustValidationEnvironment.current(),
                    CountTrustValidationEnvironment.Snapshot.decode(requireNotNull(index.get(CountTrustValidationEnvironment.KEY))))
                // Authentication of one root must not excuse arbitrary orphan cache additions.
                val reachable = linkedSetOf<String>()
                val visitedTrees = hashSetOf<String>()
                val pending = ArrayDeque<HistoryBlockCodec.Ref>()
                pending.addLast(header.root)
                while (pending.isNotEmpty()) {
                    val ref = pending.removeLast()
                    if (!visitedTrees.add(ref.digest)) continue
                    reachable.add(ref.digest)
                    val bytes = sql.query("SELECT body FROM main.count_trust_nodes WHERE digest=?", arrayOf(ref.digest)).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(Cursor.FIELD_TYPE_BLOB, cursor.getType(0))
                        cursor.getBlob(0).also { assertFalse(cursor.moveToNext()) }
                    }
                    when (val node = HistoryBlockCodec.decode(ref, bytes)) {
                        is HistoryBlockCodec.Branch -> node.children.forEach(pending::addLast)
                        is HistoryBlockCodec.Leaf -> node.records.forEach { record ->
                            val pointer = Json.parseToJsonElement(record.payload.toByteArray().toString(Charsets.UTF_8)).jsonObject
                            pointer.getValue("chunks").jsonArray.forEach { reachable.add(it.jsonPrimitive.content) }
                        }
                    }
                }
                val retained = issued.getValue("count_trust_nodes").map { row ->
                    assertEquals(setOf("digest", "body"), row.keys)
                    assertEquals(Cursor.FIELD_TYPE_STRING, row.getValue("digest").type)
                    assertEquals(Cursor.FIELD_TYPE_BLOB, row.getValue("body").type)
                    row.getValue("digest").value as String
                }
                assertEquals(retained.size, retained.toSet().size)
                assertEquals(reachable, retained.toSet())
            }
            assertEquals(authority, tokens.localCoreWriteAccess())
        } }
        assertEquals(issued, durable())
        assertEquals(history, countRead())
        assertEquals(issued, durable())
    }
    private suspend fun frozenLocal(): com.dayforge.data.local.entity.SyncOutboxEntity {
        restore(snapshot(activity()))
        val habit = db.habitDao().getHabitByUuid(activityId)!!
        db.completionDao().insert(CompletionEntity(habitId = habit.id, habitUuid = activityId, uuid = checkId, value = 5,
            date = Instant.parse("2026-09-27T16:00:00Z").toEpochMilli(), actualCompletedAt = Instant.parse(stamp).toEpochMilli(),
            createdAt = 1, recordedTimezone = "Asia/Shanghai", recordedLocalDate = "2026-09-28"))
        val row = db.syncOutboxDao().getAll().single()
        val payload = JsonObject(fact().payload - setOf("public_id", "revision", "created_at", "updated_at", "deleted_at", "received_at"))
        db.syncOutboxDao().markPrepared(row.id, checkId, "upsert", payload.toString(), null, null, 42)
        return db.syncOutboxDao().getAll().single()
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun coercedRevisionsRollBackActualAllEntityAcceptanceAndKeepCursorAcrossReopen() = runBlocking {
        val original = all()
        val before = durable()
        val cursor = tokens.syncCursor.first()
        for (row in original.changes) {
            val payload = Json.parseToJsonElement(JsonObject(row.payload +
                ("revision" to JsonUnquotedLiteral("${row.revision}e0"))).toString()).jsonObject
            val changed = original.copy(changes = original.changes.map { if (it.entityUuid == row.entityUuid) it.copy(payload = payload) else it })
            assertTrue(row.entityUuid, runCatching { restore(changed) }.isFailure)
            assertEquals(before, durable()); assertEquals(cursor, tokens.syncCursor.first())
            storage.reopen(); assertEquals(before, durable())
        }
        restore(original)
        assertEquals(3, db.habitDao().getAllHabitsOnce().size)
        assertEquals(2, db.completionDao().countAll())
        assertEquals(listOf(29_999L, 30_001L), db.timeLogDao().getDayAllocations(durationId).map { it.durationMillis })
        assertEquals(cursor, tokens.syncCursor.first())
    }

    @Test fun cachedUndoRevisionCannotBeCoercedOrNormalizedDuringActualRestore() = runBlocking<Unit> {
        val undo = fact(undoId, "revert", target = checkId)
        val response = snapshot(activity(), fact())
        restore(snapshot(activity(), fact(), undo))
        val before = durable()
        restore(response); assertRestoreGenerationOnly(before, 6L)
        val shadow = db.syncOutboxDao().getState("activity_event", undoId)!!
        val text = requireNotNull(shadow.payloadJson)
        assertTrue(text.contains("\"revision\":1"))
        for (token in listOf("1e0", "1.0", "\"1\"", "true", "null", "9223372036854775808")) {
            db.syncOutboxDao().upsertState(shadow.copy(payloadJson = text.replace("\"revision\":1", "\"revision\":$token")))
            val corrupt = durable()
            assertTrue(token, runCatching { restore(response) }.isFailure)
            assertEquals(corrupt, durable())
            storage.reopen(); assertEquals(corrupt, durable())
            assertNull(db.completionDao().getCompletionByUuid(checkId))
        }
        // Only restore the exact synthetic corruption owned by this test; production must not rewrite it.
        db.syncOutboxDao().upsertState(shadow)
        val repaired = durable()
        // One successful replay (+6), six corrupt REPLACE writes and one exact repair.
        // recursive_triggers=ON makes each existing-shadow REPLACE DELETE + INSERT (+2).
        assertEquals(withGeneration(before, 6L + 2L * (6L + 1L)), repaired)
        restore(response); assertRestoreGenerationOnly(repaired, 6L)
    }

    @Test fun allAcceptedTypesAndOnceHistoryRestoreAtomicallyAndReopenWithoutOutboxOrCursorChanges() = runBlocking {
        val snapshot = all()
        val cursor = tokens.syncCursor.first()
        restore(snapshot)
        val before = durable()
        storage.reopen(); restore(snapshot)
        assertRestoreGenerationOnly(before, 13L)
        assertEquals(cursor, tokens.syncCursor.first()); assertEquals(0, db.syncOutboxDao().count())
        assertEquals(2, db.completionDao().countAll())
        assertEquals(5, db.completionDao().getCompletionByUuid(checkId)!!.value)
        assertEquals("2026-09-28", db.completionDao().getCompletionByUuid(checkId)!!.recordedLocalDate)
        assertEquals(12.125, db.metricLogDao().getLogByUuid(observationId)!!.value, 0.0)
        assertTrue(db.habitMetricLinkDao().getLinkByUuid(linkId)!!.promptOnComplete)
        assertEquals(60000L, db.timeLogDao().getTimeLogByUuid(durationId)!!.timerActiveElapsedMillis)
        assertEquals(listOf(29999L, 30001L), db.timeLogDao().getDayAllocations(durationId).map { it.durationMillis })
        snapshot.changes.forEach { assertEquals(it.payload.toString(), db.syncOutboxDao().getState(it.entityType, it.entityUuid)!!.payloadJson) }
    }

    @Test fun lateShadowFailureRollsBackStructuresOnceProjectionAllFactsAndFlagsThenRetries() = runBlocking {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_common_shadow BEFORE INSERT ON sync_entity_state WHEN NEW.entityUuid='$linkId' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        val before = durable()
        assertTrue(runCatching { restore(all()) }.isFailure)
        assertEquals(before, durable()); storage.reopen()
        assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_common_shadow")
        restore(all()); assertEquals(2, db.completionDao().countAll())
    }

    @Test fun silentAllocationOrFactInsertFailureIsDetectedAndFullyRolledBack() = runBlocking {
        for (table in listOf("timelog_day_allocations", "metric_logs", "habit_metric_links", "completions")) {
            val onlyOrdinary = if (table == "completions") "WHEN NEW.uuid='$checkId'" else ""
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER ignore_common BEFORE INSERT ON $table $onlyOrdinary BEGIN SELECT RAISE(IGNORE); END")
            val before = durable()
            assertTrue(table, runCatching { restore(all()) }.isFailure)
            assertEquals(before, durable())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER ignore_common")
        }
        restore(all())
        assertEquals(2, db.completionDao().countAll())
    }

    @Test fun immutableFactsCannotChangeBodyRevisionOrResurrectTombstones() = runBlocking {
        restore(all())
        for (original in listOf(fact(), duration(), observation())) {
            for (bad in listOf(revise(original), original.copy(payload = JsonObject(original.payload + ("note" to JsonPrimitive("changed")))))) {
                val before = durable()
                assertTrue(runCatching { restore(all().copy(changes = all().changes.map { if (it.entityUuid == original.entityUuid) bad else it })) }.isFailure)
                assertEquals(before, durable())
            }
        }
        val shadow = db.syncOutboxDao().getState("activity_event", checkId)!!
        db.syncOutboxDao().upsertState(shadow.copy(deleted = true, revision = 2))
        val before = durable()
        assertTrue(runCatching { restore(all()) }.isFailure); assertEquals(before, durable())
    }

    @Test fun frozenMatchingLocalFactIsNotAcknowledgedAndCaptureMetadataIsKept() = runBlocking<Unit> {
        val frozen = frozenLocal()
        val id = db.completionDao().getCompletionByUuid(checkId)!!.id
        restore(snapshot(activity(), fact()))
        storage.reopen()
        assertEquals(listOf(frozen), db.syncOutboxDao().getAll())
        assertEquals(id, db.completionDao().getCompletionByUuid(checkId)!!.id)
        assertEquals("captured", db.completionDao().getCompletionByUuid(checkId)!!.timeMetadataSource)
        assertNotNull(db.syncOutboxDao().getState("activity_event", checkId))
        val before = durable(); restore(snapshot(activity(), fact())); assertRestoreGenerationOnly(before, 7L)
    }

    @Test fun mismatchedFrozenContentAndUnpreparedOrRejectedIdentityCannotBeClaimedByPull() = runBlocking {
        val frozen = frozenLocal()
        for ((key, value) in listOf("value" to JsonPrimitive("6"), "source_device_id" to JsonPrimitive(undoId),
            "note" to JsonPrimitive("different"), "occurred_at" to JsonPrimitive("2026-09-27T16:00:02Z"))) {
            val bad = fact().copy(payload = JsonObject(fact().payload + (key to value)))
            val before = durable(); assertTrue(runCatching { restore(snapshot(activity(), bad)) }.isFailure); assertEquals(before, durable())
        }
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET attemptedAt=NULL,attemptCount=0 WHERE id=${frozen.id}")
        var before = durable(); assertTrue(runCatching { restore(snapshot(activity(), fact())) }.isFailure); assertEquals(before, durable())
        db.syncOutboxDao().markPrepared(frozen.id, checkId, "upsert", frozen.payloadJson!!, null, null, 42)
        db.syncOutboxDao().markDeadLetter(frozen.id, "REJECTED", "synthetic", 43)
        before = durable(); assertTrue(runCatching { restore(snapshot(activity(), fact())) }.isFailure); assertEquals(before, durable())
    }

    @Test fun aFrozenRequestWithoutAnExplicitSourceDeviceCannotBorrowTodaysRegistration() = runBlocking {
        val frozen = frozenLocal()
        val missing = JsonObject(Json.parseToJsonElement(frozen.payloadJson!!).jsonObject - "source_device_id").toString()
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET payloadJson=? WHERE id=?", arrayOf<Any>(missing, frozen.id))
        val before = durable()
        assertTrue(runCatching { restore(snapshot(activity(), fact())) }.isFailure)
        assertEquals(before, durable())
    }

    @Test fun localDeleteAndPreparedRevertNeverResurrectAConfirmedOriginal() = runBlocking {
        restore(snapshot(activity(), fact()))
        db.completionDao().delete(db.completionDao().getCompletionByUuid(checkId)!!)
        val deleted = db.syncOutboxDao().getAll().single()
        restore(snapshot(activity(), fact()))
        assertNull(db.completionDao().getCompletionByUuid(checkId)); assertEquals(listOf(deleted), db.syncOutboxDao().getAll())
        val undo = fact(undoId, "revert", target = checkId)
        val payload = JsonObject(undo.payload - setOf("public_id", "revision", "created_at", "updated_at", "deleted_at", "received_at"))
        db.syncOutboxDao().markPrepared(deleted.id, undoId, "upsert", payload.toString(), null, null, 99)
        val frozen = db.syncOutboxDao().getAll()
        restore(snapshot(undo, activity(), fact()))
        assertEquals(frozen, db.syncOutboxDao().getAll()); assertNull(db.completionDao().getCompletionByUuid(checkId))
        assertNotNull(db.syncOutboxDao().getState("activity_event", undoId))
    }

    @Test fun reverseOrderedRevertsRemoveOnlyTheirOwnProjectionsAndKeepOriginalAuditFacts() = runBlocking {
        val undo = fact(undoId, "revert", target = checkId)
        val undoTimer = fact(goalId, "revert", target = durationId)
        val response = snapshot(undo, undoTimer, duration(), fact(), observation(), activity(), metric(), link())
        restore(response); storage.reopen(); restore(response)
        assertEquals(0, db.completionDao().countAll()); assertNull(db.timeLogDao().getTimeLogByUuid(durationId))
        assertTrue(db.timeLogDao().getDayAllocations(durationId).isEmpty())
        assertNotNull(db.metricLogDao().getLogByUuid(observationId))
        assertEquals(4, db.syncOutboxDao().getStatesForType("activity_event").size)
        assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun crossActivityRevertsAndDuplicateTargetsFailEvenWithoutLocalProjection() = runBlocking {
        val before = durable()
        val wrong = fact(undoId, "revert", parent = goalId, target = checkId)
        assertTrue(runCatching { restore(snapshot(activity(), activity(goalId), fact(), wrong)) }.isFailure)
        assertEquals(before, durable())
        assertTrue(runCatching { restore(snapshot(activity(), fact(), fact(undoId, "revert", target = checkId), fact(goalId, "revert", target = checkId))) }.isFailure)
        assertEquals(before, durable())
    }

    @Test fun localLinkFlagsArePreservedAndNewRevisionWaitsForPendingOrRejectedEdits() = runBlocking<Unit> {
        restore(snapshot(activity(), metric(), link()))
        val row = db.habitMetricLinkDao().getLinkByUuid(linkId)!!
        db.habitMetricLinkDao().update(row.copy(promptOnComplete = false))
        val pending = db.syncOutboxDao().getAll().single()
        val before = durable(); restore(snapshot(activity(), metric(), link()))
        val replayed = assertRestoreGenerationOnly(before, 6L)
        assertTrue(runCatching { restore(snapshot(activity(), metric(), link(2, false))) }.isFailure); assertEquals(replayed, durable())
        db.syncOutboxDao().markDeadLetter(pending.id, "CONFLICT", "synthetic", 12)
        val rejected = durable(); assertTrue(runCatching { restore(snapshot(activity(), metric(), link(2, false))) }.isFailure); assertEquals(rejected, durable())
        db.syncOutboxDao().deleteById(pending.id)
        restore(snapshot(activity(), metric(), link(2, false)))
        assertEquals(row.id, db.habitMetricLinkDao().getLinkByUuid(linkId)!!.id)
        assertFalse(db.habitMetricLinkDao().getLinkByUuid(linkId)!!.promptOnComplete)
        val newer = durable(); restore(snapshot(activity(), metric(), link())); assertRestoreGenerationOnly(newer, 6L)
    }

    @Test fun pendingTimerCommandsAndLoginOrEpochChangesCannotBeConsumedByRestore() = runBlocking {
        restore(snapshot(activity()))
        db.timeLogDao().insertTimerCommand(TimerCommandEntity(sessionUuid = durationId, activityUuid = activityId,
            sequence = 1, commandType = "stop", occurredAt = 1, expectedControlGeneration = 1))
        val before = durable(); assertTrue(runCatching { restore(snapshot(activity(), duration())) }.isFailure); assertEquals(before, durable())
        val context = sync().context()
        tokens.saveServerIdentity(server, "85000000-0000-4000-8000-000000000004")
        assertTrue(runCatching { sync().restoreAcceptedData(context, snapshot(activity())) }.isFailure); assertEquals(before, durable())
    }

    @Test fun cachedUndoCannotBeLostByAnOlderSnapshotAndMalformedCacheCannotResurrectIt() = runBlocking {
        val undo = fact(undoId, "revert", target = checkId)
        restore(snapshot(activity(), fact(), undo))
        val before = durable()
        restore(snapshot(activity(), fact()))
        assertRestoreGenerationOnly(before, 6L); assertNull(db.completionDao().getCompletionByUuid(checkId))
        val shadow = db.syncOutboxDao().getState("activity_event", undoId)!!
        db.syncOutboxDao().upsertState(shadow.copy(payloadJson = JsonObject(undo.payload - "event_type").toString()))
        val corrupt = durable()
        assertTrue(runCatching { restore(snapshot(activity(), fact())) }.isFailure); assertEquals(corrupt, durable())
    }

    @Test fun linkPairCollisionAndMissingSnapshotDependenciesCannotLeavePartialObservations() = runBlocking {
        restore(snapshot(activity(), metric(), link()))
        val before = durable()
        val other = change("activity_metric_link", undoId, link().payload)
        assertTrue(runCatching { restore(snapshot(activity(), metric(), observation(), other)) }.isFailure)
        assertEquals(before, durable())
        assertTrue(runCatching { restore(snapshot(activity(), observation(), link())) }.isFailure)
        assertEquals(before, durable())
    }

    @Test fun allocationReplacementFailurePreservesExistingStructuresFactsAndDays() = runBlocking {
        restore(all())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_days BEFORE DELETE ON timelog_day_allocations BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        val before = durable()
        val changed = all().copy(changes = all().changes.map { if (it.entityUuid == activityId) revise(it, fields = arrayOf("title" to JsonPrimitive("renamed"))) else it })
        assertTrue(runCatching { restore(changed) }.isFailure); assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_days")
        restore(changed)
        assertEquals("renamed", db.habitDao().getHabitByUuid(activityId)!!.name)
        assertEquals(listOf(29999L, 30001L), db.timeLogDao().getDayAllocations(durationId).map { it.durationMillis })
    }

    @Test fun duplicateLocalFactIdentitiesAreNotSilentlyReducedToTheFirstRow() = runBlocking {
        restore(all())
        db.completionDao().insert(db.completionDao().getCompletionByUuid(checkId)!!.copy(id = 0))
        val before = durable()
        val failure = runCatching { restore(all()) }.exceptionOrNull()
        assertTrue(failure is NextFactMergeException)
        assertEquals(NextFactMergeException.Reason.INVALID_LOCAL_STATE, (failure as NextFactMergeException).reason)
        assertEquals(before, durable())
    }

    @Test fun recoveryCheckpointAndEveryAcceptedTypeCommitTogetherAndReplayAfterColdOpen() = runBlocking {
        val context = sync().context()
        assertNull(sync().recoveryState(context))
        tokens.saveSyncCursor(777)
        val stage = sync().beginRecovery(context, null)
        assertEquals(1L, stage.generation); assertEquals(NextRecoveryStateEntity.AWAITING_SNAPSHOT, stage.phase)
        assertNull(stage.candidateCursor); assertNull(stage.snapshotHash)
        assertEquals(stage, sync().beginRecovery(context, stage))
        val response = all()
        val accepted = sync().acceptRecovery(context, stage, response)
        assertEquals(NextRecoveryStateEntity.ACCEPTED_DATA, accepted.phase)
        assertEquals(20L, accepted.candidateCursor); assertEquals(64, accepted.snapshotHash!!.length)
        assertEquals(account, accepted.accountId); assertEquals(server, accepted.serverInstanceId)
        assertEquals(epoch, accepted.syncEpoch); assertEquals(device, accepted.deviceId)
        assertEquals(2, db.completionDao().countAll())
        assertNotNull(db.habitMetricLinkDao().getLinkByUuid(linkId))
        assertNotNull(db.metricLogDao().getLogByUuid(observationId))
        assertEquals(listOf(29999L, 30001L), db.timeLogDao().getDayAllocations(durationId).map { it.durationMillis })
        val before = durable(); storage.reopen()
        assertEquals(accepted, sync().recoveryState(context))
        assertEquals(accepted, sync().acceptRecovery(context, stage, response))
        assertEquals(before, durable()); assertEquals(777L, tokens.syncCursor.first())
        assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun checkpointAbortOrSilentIgnoreRollsBackAllBusinessDataAndRetriesTheSameStage() = runBlocking {
        val context = sync().context()
        var stage = sync().beginRecovery(context, null)
        for (failure in listOf("ABORT,'synthetic'", "IGNORE")) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_checkpoint BEFORE UPDATE ON next_recovery_state BEGIN SELECT RAISE($failure); END")
            val before = durable()
            assertTrue(runCatching { sync().acceptRecovery(context, stage, all()) }.isFailure)
            assertEquals(before, durable()); storage.reopen(); assertEquals(before, durable())
            assertEquals(stage, sync().recoveryState(context))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_checkpoint")
            val accepted = sync().acceptRecovery(context, stage, all())
            assertEquals(2, db.completionDao().countAll())
            stage = sync().beginRecovery(context, accepted)
        }
    }

    @Test fun lateEntityFailureCannotAdvanceTheRecoveryCandidateCursor() = runBlocking {
        val context = sync().context()
        val stage = sync().beginRecovery(context, null)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_recovery_link BEFORE INSERT ON sync_entity_state WHEN NEW.entityUuid='$linkId' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        val before = durable()
        assertTrue(runCatching { sync().acceptRecovery(context, stage, all()) }.isFailure)
        assertEquals(before, durable()); assertEquals(stage, sync().recoveryState(context))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_recovery_link")
        assertEquals(20L, sync().acceptRecovery(context, stage, all()).candidateCursor)
    }

    @Test fun changedResponseOldGenerationAndRegressingCursorCannotOverwriteAcceptedRecovery() = runBlocking {
        val context = sync().context()
        val stage = sync().beginRecovery(context, null)
        val accepted = sync().acceptRecovery(context, stage, all())
        val changed = all().copy(changes = all().changes.map {
            if (it.entityUuid == activityId) revise(it, fields = arrayOf("title" to JsonPrimitive("next name"))) else it
        }, nextCursor = 21)
        var before = durable()
        assertTrue(runCatching { sync().acceptRecovery(context, stage, changed) }.isFailure)
        assertTrue(runCatching { sync().beginRecovery(context, stage) }.isFailure)
        assertEquals(before, durable())
        val next = sync().beginRecovery(context, accepted)
        assertEquals(2L, next.generation); assertEquals(20L, next.minimumCursor)
        before = durable()
        assertTrue(runCatching { sync().acceptRecovery(context, stage, all()) }.isFailure)
        assertTrue(runCatching { sync().acceptRecovery(context, next, changed.copy(nextCursor = 19)) }.isFailure)
        assertEquals(before, durable())
        assertEquals(21L, sync().acceptRecovery(context, next, changed).candidateCursor)
        assertEquals("next name", db.habitDao().getHabitByUuid(activityId)!!.name)
    }

    @Test fun staleAuthenticationAndEachReplicaIdentityFailWithoutRebindingStoredRecovery() = runBlocking {
        val context = sync().context()
        val stage = sync().beginRecovery(context, null)
        val before = durable()
        tokens.saveLoginSession("synthetic-a", "synthetic-r", "member", account, false)
        assertTrue(runCatching { sync().acceptRecovery(context, stage, all()) }.isFailure)
        assertEquals(before, durable())
        val other = "85000000-0000-4000-8000-000000000005"
        for (field in listOf("account", "server", "epoch", "device")) {
            tokens.saveLoginSession("synthetic-a", "synthetic-r", "member", if (field == "account") other else account, false)
            tokens.saveServerIdentity(if (field == "server") other else server, if (field == "epoch") other else epoch)
            tokens.saveDeviceRegistration(if (field == "device") other else device, setOf("sync.read", "facts.append"), false, 1)
            val fresh = sync().context()
            assertTrue(field, runCatching { sync().recoveryState(fresh) }.isFailure)
            assertTrue(field, runCatching { sync().beginRecovery(fresh, stage) }.isFailure)
            assertTrue(field, runCatching { sync().acceptRecovery(fresh, stage, all()) }.isFailure)
            assertEquals(before, durable())
        }
        tokens.saveLoginSession("synthetic-a", "synthetic-r", "member", account, false)
        tokens.saveServerIdentity(server, epoch)
        tokens.saveDeviceRegistration(device, setOf("sync.read", "facts.append"), false, 1)
        // Same public replica may resume after reauthentication, but old callbacks stay invalid.
        assertEquals(20L, sync().acceptRecovery(sync().context(), stage, all()).candidateCursor)
    }

    @Test fun concurrentBeginUsesExactCasAndDuplicateAcceptanceIsOneDurableReceipt() = runBlocking {
        val context = sync().context()
        val attempts = List(2) { async(Dispatchers.IO) { runCatching { sync().beginRecovery(context, null) } } }.awaitAll()
        assertEquals(1, attempts.count { it.isSuccess }); assertEquals(1, attempts.count { it.isFailure })
        val stage = attempts.single { it.isSuccess }.getOrThrow()
        val receipts = List(2) { async(Dispatchers.IO) { sync().acceptRecovery(context, stage, all()) } }.awaitAll()
        assertEquals(receipts[0], receipts[1]); assertEquals(1L, receipts[0].generation)
        assertEquals(2, db.completionDao().countAll())
        assertEquals(0, db.syncOutboxDao().count())
        storage.reopen(); assertEquals(receipts[0], sync().recoveryState(context))
    }

    @Test fun cancellationDuringFactPersistenceRollsBackStructuresAndCheckpointThenCanRetry() = runBlocking {
        val context = sync().context()
        val stage = sync().beginRecovery(context, null)
        val before = durable()
        val failure = runCatching { sync { throw CancellationException("synthetic cancellation inside fact transaction") }
            .acceptRecovery(context, stage, all()) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(before, durable()); storage.reopen(); assertEquals(before, durable())
        assertEquals(20L, sync().acceptRecovery(context, stage, all()).candidateCursor)
    }

    @Test fun recoveryReceiptDoesNotConsumePendingFactsPromptsOrUnrelatedTimerCommands() = runBlocking {
        val frozen = frozenLocal()
        val taskLink = change("activity_metric_link", undoId, JsonObject(link().payload + ("activity_uuid" to JsonPrimitive(taskId))))
        restore(snapshot(activity(), metric(), task(parent = goalId), goal(), taskLink))
        val local = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        val command = OneTimeLocalCommand(taskId, PendingOneTimeIntent(UUID.randomUUID().toString(),
            OneTimeIntent(eventId, "complete", 0, null, null)), Instant.parse(stamp).toEpochMilli(), "Asia/Shanghai")
        local.append(local.read(taskId).session, command)
        assertNotNull(sync().prepare(taskId))
        val prompt = db.completionFollowUpDao().pendingPrompts().single()
        assertEquals(eventId, prompt.eventUuid)
        val queued = db.syncOutboxDao().getAll()
        assertTrue(queued.contains(frozen))
        val timer = TimerCommandEntity(sessionUuid = undoId, activityUuid = taskId,
            sequence = 1, commandType = "stop", occurredAt = 1, expectedControlGeneration = 1)
        db.timeLogDao().insertTimerCommand(timer)
        val context = sync().context()
        val stage = sync().beginRecovery(context, null)
        val response = all().copy(changes = all().changes + taskLink)
        val accepted = sync().acceptRecovery(context, stage, response)
        storage.reopen()
        assertEquals(accepted, sync().recoveryState(context))
        assertEquals(queued, db.syncOutboxDao().getAll())
        assertEquals(listOf(prompt), db.completionFollowUpDao().pendingPrompts())
        assertNotNull(db.completionFollowUpDao().submission(command.pending.operationId))
        assertEquals(listOf(command.pending.operationId),
            OneTimeLocalIntentStore(db, tokens, sessions, preferences).read(taskId).queue.awaitingReplayOperationIds)
        assertEquals(1, db.timeLogDao().getPendingTimerCommands().size)
        val before = durable(); sync().acceptRecovery(context, stage, response); assertEquals(before, durable())
    }

    @Test fun nonSingletonRecoveryRowsCannotLookEmptyOrHideBehindAValidCheckpoint() = runBlocking {
        val context = sync().context()
        val stage = sync().beginRecovery(context, null)
        db.openHelper.writableDatabase.execSQL("UPDATE next_recovery_state SET id=2")
        repeat(2) { index ->
            if (index == 1) db.nextRecoveryDao().insert(stage)
            val before = durable()
            assertTrue(runCatching { sync().recoveryState(context) }.isFailure)
            assertTrue(runCatching { sync().beginRecovery(context, null) }.isFailure)
            assertTrue(runCatching { sync().acceptRecovery(context, stage, all()) }.isFailure)
            assertEquals(before, durable())
            storage.reopen()
            assertEquals(before, durable())
            assertTrue(runCatching { sync().recoveryState(context) }.isFailure)
        }
        // Only remove the exact malformed row injected by this isolated test, never production data.
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_recovery_state WHERE id=2")
        assertEquals(stage, sync().recoveryState(context))
        assertEquals(20L, sync().acceptRecovery(context, stage, all()).candidateCursor)
    }

    @Test fun silentBeginFailureMalformedStateAndExhaustedGenerationCannotResetRecovery() = runBlocking {
        val context = sync().context()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER ignore_recovery_begin BEFORE INSERT ON next_recovery_state BEGIN SELECT RAISE(IGNORE); END")
        val empty = durable()
        assertTrue(runCatching { sync().beginRecovery(context, null) }.isFailure)
        assertEquals(empty, durable())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER ignore_recovery_begin")
        val stage = sync().beginRecovery(context, null)
        val accepted = sync().acceptRecovery(context, stage, all())
        val exhausted = accepted.copy(generation = Long.MAX_VALUE)
        assertEquals(1, db.nextRecoveryDao().update(exhausted))
        val before = durable()
        val failure = runCatching { sync().beginRecovery(context, exhausted) }.exceptionOrNull()
        assertTrue(failure is OneTimeLocalException)
        assertEquals(OneTimeLocalException.Reason.RECOVERY_EXHAUSTED, (failure as OneTimeLocalException).reason)
        assertEquals(before, durable())
        for (invalid in listOf<() -> NextRecoveryStateEntity>(
            { stage.copy(id = 2) }, { stage.copy(generation = 0) }, { stage.copy(accountId = "not-uuid") },
            { stage.copy(phase = "ready") }, { stage.copy(candidateCursor = 1) },
            { accepted.copy(snapshotHash = "invalid") }, { accepted.copy(minimumCursor = 21) })) {
            assertTrue(runCatching { invalid() }.isFailure)
        }
        db.openHelper.writableDatabase.execSQL("UPDATE next_recovery_state SET generation=0 WHERE id=1")
        assertTrue(runCatching { sync().recoveryState(context) }.isFailure)
        assertEquals(1, db.nextRecoveryDao().update(exhausted))
        assertEquals(exhausted, sync().recoveryState(context))
    }
}
