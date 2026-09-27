package com.dayforge.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
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
class OneTimeHistoryStoreTest {
    @get:Rule val rule = PhysicalDatabaseRule()
    private val db get() = rule.database
    private val sessions = AccountSessionCoordinator()
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var habit: HabitEntity
    private val device = "11111111-0000-4000-8000-000000000001"
    private val timestamp = "2026-09-27T16:00:01Z"
    private fun uuid() = UUID.randomUUID().toString()
    private fun local() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun sync() = OneTimeAcceptedEventStore(db, tokens, sessions, local())
    private val parentTemplate by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open("next/api.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.getValue("task").jsonObject
    }

    @Before fun setup() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "history-${uuid()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        tokens = TokenManager(store)
        preferences = PreferencesManager(store)
        tokens.saveLoginSession("synthetic-a", "synthetic-r", "member", "account-a", false)
        tokens.saveServerIdentity("server", "epoch")
        tokens.saveDeviceRegistration(device, setOf("sync.read", "facts.append"), false, 1)
        habit = parent()
    }

    @After fun cleanup() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(file.delete() || !file.exists())
    }

    private suspend fun parent(): HabitEntity = db.withTransaction {
        val row = HabitEntity(name = uuid(), uuid = uuid(), habitType = HabitType.CHECK_IN, iconResId = 0,
            colorHex = "#000000", schedule = HabitSchedule.Once(), failMode = FailMode.LOOSE,
            completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0)
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        val id = db.habitDao().insert(row)
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        row.copy(id = id)
    }

    // Independent wire fixture: neither the reducer nor production mapper builds expected history.
    private fun history(parent: HabitEntity = habit, count: Int = 3, ids: List<String> = List(count) { uuid() }): List<SyncV2Change> =
        ids.mapIndexed { index, id ->
            val version = index + 1
            val complete = version % 2 == 1
            val previous = ids.getOrNull(index - 1)
            fun nullable(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull
            val payload = buildJsonObject {
                put("public_id", id); put("revision", 1); put("deleted_at", JsonNull)
                listOf("created_at", "updated_at", "received_at", "occurred_at").forEach { put(it, timestamp) }
                put("activity_uuid", parent.uuid); put("event_type", if (complete) "check_in" else "revert")
                put("value", if (complete) JsonPrimitive("1.0") else JsonNull)
                listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "external_event_id").forEach { put(it, JsonNull) }
                put("local_date", "2026-09-28"); put("timezone", "Asia/Shanghai")
                put("source_type", "app"); put("source_device_id", device); put("note", ""); put("metadata", buildJsonObject {})
                put("reverts_event_uuid", if (complete) JsonNull else nullable(previous))
                put("one_time", buildJsonObject {
                    put("event_uuid", id); put("action", if (complete) "complete" else "undo"); put("expected_version", index)
                    put("expected_head_event_uuid", nullable(previous)); put("reverts_event_uuid", if (complete) JsonNull else nullable(previous))
                })
                put("one_time_state_after", buildJsonObject {
                    put("version", version); put("head_event_uuid", id); put("completion_event_uuid", if (complete) JsonPrimitive(id) else JsonNull)
                })
            }
            SyncV2Change(0, "activity_event", id, "upsert", 1, payload, timestamp)
        }

    private fun snapshot(vararg histories: Pair<HabitEntity, List<SyncV2Change>>): NextSyncBootstrapResponse {
        val changes = histories.flatMap { (parent, events) ->
            listOf(SyncV2Change(0, "plan_node", parent.uuid, "upsert", 1,
                JsonObject(parentTemplate + ("public_id" to JsonPrimitive(parent.uuid))), timestamp)) + events
        }
        val checkpoints = histories.map { (parent, events) ->
            val last = events.maxByOrNull { it.payload.getValue("one_time_state_after").jsonObject.getValue("version").jsonPrimitive.int }
            OneTimeProjection(parent.uuid, last?.let {
                Json.decodeFromJsonElement<OneTimeState>(it.payload.getValue("one_time_state_after"))
            } ?: OneTimeState(0, null, null))
        }
        return NextSyncBootstrapResponse(changes, 100, timestamp, checkpoints)
    }

    private suspend fun rejected(block: suspend () -> Unit) = assertNotNull(runCatching { block() }.exceptionOrNull())
    private suspend fun empty(parent: HabitEntity = habit) {
        assertEquals(0, local().read(parent.uuid).confirmed.version)
        assertTrue(db.completionDao().getByHabitOnce(parent.id).isEmpty())
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }

    @Test fun unorderedCompleteHistoryRestoresAtomicallyAndRepeatsWithoutChangingStructureOrCursor() = runBlocking {
        val events = history()
        val context = sync().context()
        val cursor = tokens.syncCursor.first()
        val response = snapshot(habit to events.reversed())
        repeat(2) {
            assertEquals(3, sync().restoreHistories(context, response).single().confirmed.version)
            rule.reopen()
            val expected = habit.copy(oneTimeConfirmedVersion = 3, oneTimeConfirmedHeadEventUuid = events.last().entityUuid,
                oneTimeConfirmedCompletionEventUuid = events.last().entityUuid)
            assertEquals(expected, db.habitDao().getHabitByUuid(habit.uuid))
            assertEquals(3, db.completionDao().getByHabitOnce(habit.id).size)
            assertTrue(db.syncOutboxDao().getAll().isEmpty())
            assertEquals(cursor, tokens.syncCursor.first())
        }
        events.forEach { assertEquals(it.payload.toString(), db.syncOutboxDao().getState("activity_event", it.entityUuid)!!.payloadJson) }
        assertTrue(db.completionFollowUpDao().pendingPrompts().isEmpty())
    }

    @Test fun explicitEmptyCheckpointIsValidAndDoesNotDeleteAnUnpublishedLocalItem() = runBlocking {
        val unpublished = parent()
        sync().restoreHistories(sync().context(), snapshot(habit to emptyList()))
        rule.reopen(); empty()
        assertEquals(unpublished, db.habitDao().getHabitByUuid(unpublished.uuid))
        assertEquals(2, db.habitDao().getAllHabitsOnce().size)
    }

    @Test fun missingMiddleTailWholeHistoryOrCheckpointAndDuplicateEventsCannotActivate() = runBlocking {
        val events = history()
        val original = snapshot(habit to events)
        val mutations: List<(MutableList<SyncV2Change>) -> Unit> = listOf(
            { it.removeAt(2) }, { it.removeAt(3) }, { it.removeAll { row -> row.entityType == "activity_event" } },
            { it.add(it.last()) }, { it[1] = it[1].copy(entityUuid = uuid()) })
        for (mutate in mutations) {
            val changes = original.changes.toMutableList()
            val response = original.copy(changes = changes)
            mutate(changes) // The persistence entry must not trust construction-time validation alone.
            rejected { sync().restoreHistories(sync().context(), response) }
            empty()
        }
        for (duplicate in listOf(false, true)) {
            val checkpoints = original.oneTimeCheckpoints.toMutableList()
            val response = original.copy(oneTimeCheckpoints = checkpoints)
            if (duplicate) checkpoints += checkpoints.single() else checkpoints.clear()
            rejected { sync().restoreHistories(sync().context(), response) }
            empty()
        }
    }

    @Test fun fullFactMetadataAndParentIdentityAreCheckedBeyondSixFieldProof() = runBlocking {
        val original = snapshot(habit to history())
        listOf("occurred_at" to JsonPrimitive("2026-09-27T16:00:01"), "timezone" to JsonPrimitive("bad/zone"),
            "local_date" to JsonPrimitive("2026-09-27"), "revision" to JsonPrimitive(2),
            "source_device_id" to JsonPrimitive("invalid"), "duration_seconds" to JsonPrimitive(60)).forEach { (key, value) ->
            val changes = original.changes.toMutableList()
            changes[1] = changes[1].copy(payload = JsonObject(changes[1].payload + (key to value)))
            rejected { sync().restoreHistories(sync().context(), original.copy(changes = changes)) }
            empty()
        }
        val changes = original.changes.toMutableList()
        changes[0] = changes[0].copy(payload = JsonObject(changes[0].payload + ("public_id" to JsonPrimitive(uuid()))))
        rejected { sync().restoreHistories(sync().context(), original.copy(changes = changes)) }
        rejected { sync().restoreHistories(sync().context(), original.copy(nextCursor = -1)) }
        rejected { sync().restoreHistories(sync().context(), original.copy(serverTime = "not-an-instant")) }
        empty()
    }

    @Test fun secondItemInsertionFailureRollsBackFirstItemFactsProjectionShadowsAndSuppression() = runBlocking {
        val other = parent()
        val first = history()
        val response = snapshot(habit to first, other to history(other, 2))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_history BEFORE INSERT ON completions WHEN NEW.habitId=${other.id} BEGIN SELECT RAISE(ABORT, 'synthetic history failure'); END")
        rejected { sync().restoreHistories(sync().context(), response) }
        rule.reopen(); empty(); empty(other)
        assertNull(db.syncOutboxDao().getState("activity_event", first.first().entityUuid))
        db.openHelper.readableDatabase.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_history")
        assertEquals(listOf(3, 2), sync().restoreHistories(sync().context(), response).map { it.confirmed.version })
    }

    @Test fun silentlyIgnoredFactCannotBeReportedAsSuccessfulRestoration() = runBlocking {
        // Ignore only the first fact: the final head could otherwise exist while the chain is incomplete.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER ignore_history BEFORE INSERT ON completions WHEN NEW.oneTimeExpectedVersion=0 BEGIN SELECT RAISE(IGNORE); END")
        rejected { sync().restoreHistories(sync().context(), snapshot(habit to history())) }
        rule.reopen(); empty()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER ignore_history")
    }

    @Test fun completeHistoryFillsIncrementalGapsButNeverRegressesOrRewritesAcceptedHistory() = runBlocking {
        val events = history(count = 5)
        sync().apply(sync().context(), listOf(events.last()))
        val before = db.syncOutboxDao().getState("activity_event", events.last().entityUuid)
        sync().restoreHistories(sync().context(), snapshot(habit to events.reversed()))
        assertEquals(before, db.syncOutboxDao().getState("activity_event", events.last().entityUuid))
        val failure = runCatching { sync().restoreHistories(sync().context(), snapshot(habit to events.take(3))) }.exceptionOrNull()
        assertEquals(OneTimeLocalException.Reason.HISTORY_BEHIND_LOCAL, (failure as OneTimeLocalException).reason)
        val changed = events.toMutableList()
        changed[0] = changed[0].copy(payload = JsonObject(changed[0].payload + ("note" to JsonPrimitive("rewritten"))))
        rejected { sync().restoreHistories(sync().context(), snapshot(habit to changed)) }
        rejected { sync().restoreHistories(sync().context(), snapshot(habit to history(count = 5))) }
        rule.reopen()
        assertEquals(5, local().read(habit.uuid).confirmed.version)
        assertEquals(5, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals("", db.syncOutboxDao().getState("activity_event", events.first().entityUuid)!!.payloadJson.let {
            Json.parseToJsonElement(it!!).jsonObject.getValue("note").jsonPrimitive.content })
    }

    private suspend fun append(): OneTimeLocalCommand {
        val before = local().read(habit.uuid)
        val command = OneTimeLocalCommand(habit.uuid, PendingOneTimeIntent(uuid(), OneTimeIntent(uuid(), "complete", 0, null, null)),
            Instant.parse(timestamp).toEpochMilli(), "Asia/Shanghai")
        local().append(before.session, command)
        return command
    }

    @Test fun matchingBootstrapFactDoesNotAcknowledgeFrozenRequestOrDeleteMetricDraft() = runBlocking {
        val metric = MetricEntity(name = "Weight", unit = "kg", iconResId = 0, colorHex = "#000000")
        val metricId = db.metricDao().insert(metric)
        db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
            metricId = metricId, metricUuid = metric.uuid, promptOnComplete = true))
        val command = append()
        val prompts = CompletionMetricPromptStore(db, tokens, sessions)
        val draft = prompts.updateDraft(prompts.read(command.pending.intent.eventUuid), mapOf(metric.uuid to CompletionMetricInput("42", "preserved")))
        val request = sync().prepare(habit.uuid)!!
        val queue = db.syncOutboxDao().getActivityIntents(habit.uuid)
        val binding = db.completionFollowUpDao().transmission(command.pending.operationId)
        val events = history(ids = listOf(command.pending.intent.eventUuid, uuid(), uuid()))
        val restored = sync().restoreHistories(sync().context(), snapshot(habit to events)).single()
        assertEquals(listOf(command.pending.operationId), restored.queue.awaitingReplayOperationIds)
        rule.reopen()
        assertEquals(queue, db.syncOutboxDao().getActivityIntents(habit.uuid))
        assertEquals(binding, db.completionFollowUpDao().transmission(command.pending.operationId))
        assertEquals(draft.row, db.completionFollowUpDao().prompt(command.pending.intent.eventUuid))
        sync().acknowledge(request, NextSyncOperationResult(command.pending.operationId, "activity_event",
            command.pending.intent.eventUuid, "already_applied", 1, entity = events.first().payload))
        assertEquals(3, local().read(habit.uuid).confirmed.version)
        assertTrue(db.syncOutboxDao().getActivityIntents(habit.uuid).isEmpty())
    }

    @Test fun rejectedOfflineBranchRemainsQuarantinedAfterCompleteServerHistory() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        val events = history()
        val state = OneTimeState(3, events.last().entityUuid, events.last().entityUuid)
        sync().reject(request, NextSyncOperationResult(command.pending.operationId, "activity_event", command.pending.intent.eventUuid,
            "conflict", errorCode = "TASK_STATE_CONFLICT", oneTimeConflict = OneTimeProjection(habit.uuid, state)))
        val queue = db.syncOutboxDao().getActivityIntents(habit.uuid)
        val binding = db.completionFollowUpDao().transmission(command.pending.operationId)
        sync().restoreHistories(sync().context(), snapshot(habit to events))
        rule.reopen()
        assertEquals(state, local().read(habit.uuid).confirmed)
        assertEquals(listOf(command.pending.operationId), local().read(habit.uuid).queue.blockedOperationIds)
        assertEquals(queue, db.syncOutboxDao().getActivityIntents(habit.uuid))
        assertEquals(binding, db.completionFollowUpDao().transmission(command.pending.operationId))
        assertEquals(4, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun staleContextsAndDeletedOrUninitializedParentsCannotRestore() = runBlocking {
        val context = sync().context()
        val response = snapshot(habit to history())
        tokens.saveSyncDeviceId(uuid()); rejected { sync().restoreHistories(context, response) }
        tokens.saveSyncDeviceId(device)
        tokens.saveServerIdentity("server", "other"); rejected { sync().restoreHistories(context, response) }
        tokens.saveServerIdentity("server", "epoch")
        tokens.saveLoginSession("synthetic-next", "synthetic-r", "member", "account-a", false)
        rejected { sync().restoreHistories(context, response) }
        db.syncOutboxDao().upsertState(SyncEntityStateEntity("plan_node", habit.uuid, 2, deleted = true))
        rejected { sync().restoreHistories(sync().context(), response) }
        db.syncOutboxDao().deleteState("plan_node", habit.uuid)
        db.withTransaction { db.habitDao().updateForSync(habit.copy(completionPolicy = null)) }
        rejected { sync().restoreHistories(sync().context(), response) }
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
    }

    @Test fun hundredsOfTransitionsRestoreAsOneHistoryAndSurviveReopen() = runBlocking {
        val events = history(count = 300)
        val restored = sync().restoreHistories(sync().context(), snapshot(habit to events.reversed())).single()
        assertEquals(300, restored.confirmed.version)
        assertNull(restored.confirmed.completionEventUuid)
        assertEquals(events.last().entityUuid, restored.confirmed.headEventUuid)
        rule.reopen()
        assertEquals(300, db.completionDao().getByHabitOnce(habit.id).size)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        sync().restoreHistories(sync().context(), snapshot(habit to events))
        assertEquals(300, db.completionDao().getByHabitOnce(habit.id).size)
    }
}
