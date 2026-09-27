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
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.domain.model.OneTimeIntent
import com.dayforge.domain.model.PendingOneTimeIntent
import com.dayforge.domain.model.OneTimeProjection
import com.dayforge.domain.model.OneTimeState
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
class NextStructureRestoreTest {
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
    private fun sync() = OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences))
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
        tokens.saveLoginSession("synthetic-a", "synthetic-r", "member", "account-a", false)
        tokens.saveServerIdentity("server", "epoch")
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
    private suspend fun restore(response: NextSyncBootstrapResponse) = sync().restoreStructuresAndHistories(sync().context(), response)
    private suspend fun empty() {
        assertTrue(db.habitDao().getAllHabitsOnce().isEmpty()); assertTrue(db.metricDao().getAllMetricsOnce().isEmpty())
        assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty()); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertTrue(db.syncOutboxDao().getStatesForType("plan_node").isEmpty())
    }
    private fun revise(row: SyncV2Change, revision: Long = row.revision + 1, vararg fields: Pair<String, JsonElement>) =
        change(row.entityType, row.entityUuid, JsonObject(row.payload + fields), revision)
    private fun recurring(row: SyncV2Change): SyncV2Change = revise(row, fields = arrayOf(
        "activity" to JsonObject(row.payload.getValue("activity").jsonObject + mapOf(
            "completion_policy" to JsonPrimitive("recurring"),
            "recurrence_rule" to Json.parseToJsonElement("""{"schema_version":1,"type":"daily","interval":1,"start_date":null}"""))),
        "appearance" to goal().payload.getValue("appearance")))

    @Test fun unorderedParentsMetricsAndHistoryRestoreInOneTransactionAndReopenIdempotently() = runBlocking {
        val response = snapshot(completed(), task(parent = goalId), metric(), goal())
        val cursor = tokens.syncCursor.first()
        repeat(2) {
            assertEquals(1, restore(response).single().confirmed.version)
            storage.reopen()
            val row = db.habitDao().getHabitByUuid(taskId)!!
            assertEquals(goalId, row.parentHabitId)
            assertEquals(eventId, row.oneTimeConfirmedCompletionEventUuid)
            assertNotNull(row.planMetadata); assertNotNull(row.appearance)
            assertEquals(2, db.habitDao().getAllHabitsOnce().size)
            assertEquals(1, db.metricDao().getAllMetricsOnce().size)
            assertEquals(1, db.completionDao().getAllCompletionsOnce().size)
            assertEquals(0, db.syncOutboxDao().count())
            assertEquals(cursor, tokens.syncCursor.first())
        }
        response.changes.forEach { assertEquals(it.payload.toString(), db.syncOutboxDao().getState(it.entityType, it.entityUuid)!!.payloadJson) }
    }

    @Test fun invalidFactAfterStructuresRollsBackEverythingAndRetryCanSucceed() = runBlocking {
        val bad = completed().copy(payload = JsonObject(completed().payload + ("local_date" to JsonPrimitive("2026-09-27"))))
        assertTrue(runCatching { restore(snapshot(task(parent = goalId), goal(), metric(), bad)) }.isFailure)
        storage.reopen(); empty()
        assertEquals(1, restore(snapshot(task(parent = goalId), goal(), metric(), completed())).single().confirmed.version)
    }

    @Test fun missingParentAndOrdinaryParentAreNotRepairedFromLocalCache() = runBlocking {
        assertTrue(runCatching { restore(snapshot(task(parent = goalId))) }.isFailure); empty()
        assertTrue(runCatching { restore(snapshot(task(parent = goalId), task(goalId, "ordinary parent"))) }.isFailure); empty()
        restore(snapshot(goal()))
        assertTrue(runCatching { restore(snapshot(task(parent = goalId))) }.isFailure)
        assertNull(db.habitDao().getHabitByUuid(taskId))
    }

    @Test fun sameRevisionDivergenceAndTombstonesCannotRewriteOrResurrect() = runBlocking {
        restore(snapshot(task()))
        val original = db.habitDao().getHabitByUuid(taskId)
        assertTrue(runCatching { restore(snapshot(task(name = "different"))) }.isFailure)
        assertEquals(original, db.habitDao().getHabitByUuid(taskId))
        val shadow = db.syncOutboxDao().getState("plan_node", taskId)!!
        db.syncOutboxDao().upsertState(shadow.copy(deleted = true, revision = 2))
        assertTrue(runCatching { restore(snapshot(task(name = "resurrect", revision = 3))) }.isFailure)
        assertEquals(original, db.habitDao().getHabitByUuid(taskId))
    }

    @Test fun frozenLocalStructureIsNeverOverwrittenOrAcknowledgedByBootstrap() = runBlocking {
        val first = task()
        restore(snapshot(first))
        val old = db.habitDao().getHabitByUuid(taskId)!!
        db.habitDao().update(old.copy(name = "local edit"))
        val pending = db.syncOutboxDao().getAll().single()
        db.syncOutboxDao().markPrepared(pending.id, taskId, "upsert", "{\"frozen\":true}", 1, first.payload.toString(), 42)
        val frozen = db.syncOutboxDao().getAll()
        restore(snapshot(first)) // Known server structure does not roll back the local edit.
        assertTrue(runCatching { restore(snapshot(task(name = "remote edit", revision = 2))) }.isFailure)
        storage.reopen()
        assertEquals("local edit", db.habitDao().getHabitByUuid(taskId)!!.name)
        assertEquals(frozen, db.syncOutboxDao().getAll())
        assertEquals(first.payload.toString(), db.syncOutboxDao().getState("plan_node", taskId)!!.payloadJson)
        db.syncOutboxDao().markDeadLetter(pending.id, "REVISION_CONFLICT", "synthetic", 43)
        assertTrue(runCatching { restore(snapshot(task(name = "remote edit", revision = 2))) }.isFailure)
        assertEquals(frozen.single().payloadJson, db.syncOutboxDao().getDeadLetters().single().payloadJson)
        db.syncOutboxDao().deleteById(pending.id)
        db.syncConflictDao().insert(SyncConflictEntity(operationId = pending.operationId, recordType = "habit",
            localEntityUuid = taskId, wireEntityUuid = taskId, entityType = "plan_node", action = "upsert",
            referenceUuid = null, baseRevision = 1, serverRevision = 2, basePayloadJson = first.payload.toString(),
            localPayloadJson = "{\"frozen\":true}", serverPayloadJson = task(name = "remote edit", revision = 2).payload.toString(),
            conflictingFieldsJson = "[\"title\"]", conflictKind = "field_conflict", errorCode = "REVISION_CONFLICT", message = "synthetic"))
        assertTrue(runCatching { restore(snapshot(task(name = "remote edit", revision = 2))) }.isFailure)
        assertEquals(1, db.syncConflictDao().countUnresolved())
        assertEquals("local edit", db.habitDao().getHabitByUuid(taskId)!!.name)
    }

    @Test fun olderStructureDoesNotUndoANewerNameOrItsShadow() = runBlocking {
        restore(snapshot(task(name = "new", revision = 2)))
        restore(snapshot(task(name = "old", revision = 1)))
        assertEquals("new", db.habitDao().getHabitByUuid(taskId)!!.name)
        assertEquals(2L, db.syncOutboxDao().getState("plan_node", taskId)!!.revision)
    }

    @Test fun noHistoryPolicyConversionWorksButConfirmedFactsAndStoredTimerLockIt() = runBlocking {
        val first = task()
        restore(snapshot(first)); restore(snapshot(recurring(first)))
        assertEquals("recurring", db.habitDao().getHabitByUuid(taskId)!!.completionPolicy)
        assertNull(db.habitDao().getHabitByUuid(taskId)!!.oneTimeConfirmedVersion)
        db.clearAllData()
        restore(snapshot(first, completed()))
        assertTrue(runCatching { restore(snapshot(recurring(first))) }.isFailure)
        assertEquals(1, db.habitDao().getHabitByUuid(taskId)!!.oneTimeConfirmedVersion)
        db.clearAllData()
        restore(snapshot(first))
        val row = db.habitDao().getHabitByUuid(taskId)!!
        db.timeLogDao().insert(TimeLogEntity(habitId = row.id, startTime = 1, endTime = 2, date = 0, durationSeconds = 0))
        assertTrue(runCatching { restore(snapshot(recurring(first))) }.isFailure)
    }

    @Test fun orphanedPendingAndRejectedTimerCommandsCannotBypassHistoryLock() = runBlocking {
        val first = task()
        restore(snapshot(first))
        val id = db.timeLogDao().insertTimerCommand(TimerCommandEntity(sessionUuid = UUID.randomUUID().toString(),
            sequence = 1, commandType = "start", occurredAt = 1, expectedControlGeneration = 0, activityUuid = taskId))
        assertTrue(runCatching { restore(snapshot(recurring(first))) }.isFailure)
        db.timeLogDao().deadLetterTimerCommand(id, "synthetic", "synthetic", 2)
        assertTrue(runCatching { restore(snapshot(recurring(first))) }.isFailure)
        assertEquals("one_and_done", db.habitDao().getHabitByUuid(taskId)!!.completionPolicy)
    }

    @Test fun deletedFactShadowStillLocksPolicyAfterLocalFactRemoval() = runBlocking {
        val first = task()
        restore(snapshot(first))
        db.syncOutboxDao().upsertState(SyncEntityStateEntity("activity_event", eventId, 2, deleted = true,
            payloadJson = completed().payload.toString()))
        assertTrue(runCatching { restore(snapshot(recurring(first))) }.isFailure)
        assertEquals("one_and_done", db.habitDao().getHabitByUuid(taskId)!!.completionPolicy)
    }

    @Test fun finalUniqueNameSwapsKeepLocalIdsAndDoNotCreateOutbox() = runBlocking {
        val other = "82000000-0000-4000-8000-000000000006"
        val a = task(name = "A"); val b = task(other, "B")
        val m = metric()
        val n = change("metric", other, JsonObject(m.payload + ("name" to JsonPrimitive("second metric"))))
        restore(snapshot(a, b, m, n))
        val ids = db.habitDao().getAllHabitsOnce().associate { it.uuid to it.id }
        val metricIds = db.metricDao().getAllMetricsOnce().associate { it.uuid to it.id }
        restore(snapshot(task(name = "B", revision = 2), task(other, "A", revision = 2),
            revise(m, fields = arrayOf("name" to JsonPrimitive("second metric"))),
            revise(n, fields = arrayOf("name" to JsonPrimitive("metric")))))
        assertEquals(ids, db.habitDao().getAllHabitsOnce().associate { it.uuid to it.id })
        assertEquals(metricIds, db.metricDao().getAllMetricsOnce().associate { it.uuid to it.id })
        assertEquals("second metric", db.metricDao().getMetricByUuid(metricId)!!.name)
        assertEquals("metric", db.metricDao().getMetricByUuid(other)!!.name)
        assertEquals("B", db.habitDao().getHabitByUuid(taskId)!!.name)
        assertEquals("A", db.habitDao().getHabitByUuid(other)!!.name)
        assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun missingLegacyMetadataAndChangingNodeKindCannotBeGuessedIntoNewStructure() = runBlocking {
        val first = task()
        restore(snapshot(first))
        val row = db.habitDao().getHabitByUuid(taskId)!!
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        db.habitDao().updateForSync(row.copy(planMetadata = null))
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        assertTrue(runCatching { restore(snapshot(first)) }.isFailure)
        assertTrue(runCatching { restore(snapshot(task(name = "new", revision = 2))) }.isFailure)
        assertNull(db.habitDao().getHabitByUuid(taskId)!!.planMetadata)
        db.clearAllData()
        restore(snapshot(goal()))
        assertTrue(runCatching { restore(snapshot(task(goalId, "convert goal", 2))) }.isFailure)
        assertEquals(com.dayforge.data.model.HabitType.GOAL, db.habitDao().getHabitByUuid(goalId)!!.habitType)
    }

    @Test fun silentlyIgnoredInsertCannotCommitOtherStructuresOrHistory() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER ignore_metric BEFORE INSERT ON metrics BEGIN SELECT RAISE(IGNORE); END")
        assertTrue(runCatching { restore(snapshot(task(), metric(), completed())) }.isFailure)
        storage.reopen(); empty()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER ignore_metric")
        assertEquals(1, restore(snapshot(task(), metric(), completed())).single().confirmed.version)
    }

    @Test fun structuralRenameKeepsAnUnconfirmedFactAndItsOriginalOperation() = runBlocking {
        val first = task()
        restore(snapshot(first))
        val context = sync().context()
        val pending = PendingOneTimeIntent(UUID.randomUUID().toString(), OneTimeIntent(eventId, "complete", 0, null, null))
        OneTimeLocalIntentStore(db, tokens, sessions, preferences).append(context.session,
            OneTimeLocalCommand(taskId, pending, Instant.parse(stamp).toEpochMilli(), "Asia/Shanghai"))
        val queue = db.syncOutboxDao().getAll()
        val facts = db.completionDao().getAllCompletionsOnce()
        val result = restore(snapshot(task(name = "renamed", revision = 2))).single()
        assertEquals(0, result.confirmed.version)
        assertEquals(1, result.queue.optimisticState.version)
        assertEquals(queue, db.syncOutboxDao().getAll())
        assertEquals(facts, db.completionDao().getAllCompletionsOnce())
        assertEquals("renamed", db.habitDao().getHabitByUuid(taskId)!!.name)
    }

    @Test fun lateShadowFailureRollsBackNameStagingAndHistoryAndCanRetry() = runBlocking {
        val original = task()
        restore(snapshot(original))
        val before = db.habitDao().getHabitByUuid(taskId)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_fact_shadow BEFORE INSERT ON sync_entity_state WHEN NEW.entityType='activity_event' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        val response = snapshot(task(name = "renamed", revision = 2), completed())
        assertTrue(runCatching { restore(response) }.isFailure)
        storage.reopen()
        assertEquals(before, db.habitDao().getHabitByUuid(taskId))
        assertEquals(original.payload.toString(), db.syncOutboxDao().getState("plan_node", taskId)!!.payloadJson)
        assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_fact_shadow")
        assertEquals(1, restore(response).single().confirmed.version)
        assertEquals("renamed", db.habitDao().getHabitByUuid(taskId)!!.name)
        assertEquals(0, db.syncOutboxDao().count())
    }

    @Test fun changedLoginGenerationOrReplicaCannotRestoreAnEarlierResponse() = runBlocking {
        val stale = sync().context()
        tokens.saveLoginSession("synthetic-b", "synthetic-rb", "member", "account-a", false)
        assertTrue(runCatching { sync().restoreStructuresAndHistories(stale, snapshot(task())) }.isFailure)
        empty()
        tokens.saveServerIdentity("server", "epoch")
        tokens.saveDeviceRegistration(device, setOf("sync.read", "facts.append"), false, 1)
        val oldReplica = sync().context()
        tokens.saveServerIdentity("server", "new-epoch")
        assertTrue(runCatching { sync().restoreStructuresAndHistories(oldReplica, snapshot(task())) }.isFailure)
        empty()
    }

    @Test fun metricCreationIdentityCannotChangeWithinTheSameStoredMillisecond() = runBlocking {
        val original = metric()
        restore(snapshot(original))
        val changed = revise(original).let { it.copy(payload = JsonObject(it.payload +
            ("created_at" to JsonPrimitive("2026-09-27T16:00:01.000001Z")))) }
        assertTrue(runCatching { restore(snapshot(changed)) }.isFailure)
        assertEquals(original.payload.toString(), db.syncOutboxDao().getState("metric", metricId)!!.payloadJson)
        assertEquals(0, db.syncOutboxDao().count())
    }
}
