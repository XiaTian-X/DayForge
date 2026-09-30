package com.dayforge.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.NextRecoveryStateEntity
import com.dayforge.domain.model.OneTimeProjection
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

@RunWith(AndroidJUnit4::class)
class NextCommonRestoreTest {
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
        tokens = TokenManager(store); preferences = PreferencesManager(store)
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
    private fun durable(): Map<String, List<List<String?>>> {
        val sql = db.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table -> sql.query("SELECT * FROM \"$table\" ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
        } }
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

    @Test fun allAcceptedTypesAndOnceHistoryRestoreAtomicallyAndReopenWithoutOutboxOrCursorChanges() = runBlocking {
        val snapshot = all()
        val cursor = tokens.syncCursor.first()
        restore(snapshot)
        val before = durable()
        storage.reopen(); restore(snapshot)
        assertEquals(before, durable())
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

    @Test fun frozenMatchingLocalFactIsNotAcknowledgedAndCaptureMetadataIsKept() = runBlocking {
        val frozen = frozenLocal()
        val id = db.completionDao().getCompletionByUuid(checkId)!!.id
        restore(snapshot(activity(), fact()))
        storage.reopen()
        assertEquals(listOf(frozen), db.syncOutboxDao().getAll())
        assertEquals(id, db.completionDao().getCompletionByUuid(checkId)!!.id)
        assertEquals("captured", db.completionDao().getCompletionByUuid(checkId)!!.timeMetadataSource)
        assertNotNull(db.syncOutboxDao().getState("activity_event", checkId))
        val before = durable(); restore(snapshot(activity(), fact())); assertEquals(before, durable())
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

    @Test fun localLinkFlagsArePreservedAndNewRevisionWaitsForPendingOrRejectedEdits() = runBlocking {
        restore(snapshot(activity(), metric(), link()))
        val row = db.habitMetricLinkDao().getLinkByUuid(linkId)!!
        db.habitMetricLinkDao().update(row.copy(promptOnComplete = false))
        val pending = db.syncOutboxDao().getAll().single()
        val before = durable(); restore(snapshot(activity(), metric(), link())); assertEquals(before, durable())
        assertTrue(runCatching { restore(snapshot(activity(), metric(), link(2, false))) }.isFailure); assertEquals(before, durable())
        db.syncOutboxDao().markDeadLetter(pending.id, "CONFLICT", "synthetic", 12)
        val rejected = durable(); assertTrue(runCatching { restore(snapshot(activity(), metric(), link(2, false))) }.isFailure); assertEquals(rejected, durable())
        db.syncOutboxDao().deleteById(pending.id)
        restore(snapshot(activity(), metric(), link(2, false)))
        assertEquals(row.id, db.habitMetricLinkDao().getLinkByUuid(linkId)!!.id)
        assertFalse(db.habitMetricLinkDao().getLinkByUuid(linkId)!!.promptOnComplete)
        val newer = durable(); restore(snapshot(activity(), metric(), link())); assertEquals(newer, durable())
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
        assertEquals(before, durable()); assertNull(db.completionDao().getCompletionByUuid(checkId))
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
