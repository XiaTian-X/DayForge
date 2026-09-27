package com.dayforge.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.OneTimeIntent
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.OneTimeProjection
import com.dayforge.domain.model.PendingOneTimeIntent
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OneTimeAcceptedEventStoreTest {
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
    private fun local(coordinator: AccountSessionCoordinator = sessions) = OneTimeLocalIntentStore(db, tokens, coordinator, preferences)
    private fun sync(coordinator: AccountSessionCoordinator = sessions) = OneTimeAcceptedEventStore(db, tokens, coordinator, local(coordinator))
    private fun uuid() = UUID.randomUUID().toString()

    @Before fun setup() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "accepted-${uuid()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        tokens = TokenManager(store)
        preferences = PreferencesManager(store)
        tokens.saveLoginSession("synthetic-a", "synthetic-r", "member", "account-a", false)
        tokens.saveServerIdentity("server", "epoch")
        tokens.saveDeviceRegistration(device, setOf("sync.read", "facts.append"), false, 1)
        habit = db.withTransaction {
            val row = HabitEntity(name = "Once", uuid = uuid(), habitType = HabitType.CHECK_IN, iconResId = 0,
                colorHex = "#000000", schedule = HabitSchedule.Once(), failMode = FailMode.LOOSE,
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val id = db.habitDao().insert(row)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            row.copy(id = id)
        }
    }

    @After fun cleanup() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(file.delete() || !file.exists())
    }

    private fun command(state: OneTimeState = OneTimeState(0, null, null)) = OneTimeLocalCommand(habit.uuid,
        PendingOneTimeIntent(uuid(), OneTimeIntent(uuid(), if (state.completionEventUuid == null) "complete" else "undo",
            state.version, state.headEventUuid, state.completionEventUuid)), Instant.parse(timestamp).toEpochMilli(), "Asia/Shanghai")

    private suspend fun append(): OneTimeLocalCommand {
        val snapshot = local().read(habit.uuid)
        val command = command(snapshot.queue.optimisticState)
        local().append(snapshot.session, command)
        return command
    }

    // Independent server wire fixture; no production mapper/reducer generates expected fields.
    private fun entity(command: OneTimeLocalCommand, deviceId: String = device): JsonObject = buildJsonObject {
        val intent = command.pending.intent
        put("public_id", intent.eventUuid); put("revision", 1)
        put("created_at", timestamp); put("updated_at", timestamp); put("received_at", timestamp); put("deleted_at", JsonNull)
        put("activity_uuid", command.activityUuid); put("event_type", if (intent.action == "complete") "check_in" else "revert")
        put("value", if (intent.action == "complete") JsonPrimitive("1.0") else JsonNull)
        listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "external_event_id").forEach { put(it, JsonNull) }
        put("occurred_at", timestamp); put("local_date", "2026-09-28"); put("timezone", "Asia/Shanghai")
        put("source_type", "app"); put("source_device_id", deviceId); put("note", ""); put("metadata", buildJsonObject {})
        put("reverts_event_uuid", intent.revertsEventUuid?.let(::JsonPrimitive) ?: JsonNull)
        put("one_time", buildJsonObject {
            put("event_uuid", intent.eventUuid); put("action", intent.action); put("expected_version", intent.expectedVersion)
            put("expected_head_event_uuid", intent.expectedHeadEventUuid?.let(::JsonPrimitive) ?: JsonNull)
            put("reverts_event_uuid", intent.revertsEventUuid?.let(::JsonPrimitive) ?: JsonNull)
        })
        put("one_time_state_after", buildJsonObject {
            put("version", intent.expectedVersion + 1); put("head_event_uuid", intent.eventUuid)
            put("completion_event_uuid", if (intent.action == "complete") JsonPrimitive(intent.eventUuid) else JsonNull)
        })
    }

    private fun change(command: OneTimeLocalCommand, payload: JsonObject = entity(command)) = SyncV2Change(
        1, "activity_event", command.pending.intent.eventUuid, "upsert", 1, payload, timestamp)
    private fun result(command: OneTimeLocalCommand, payload: JsonObject = entity(command)) = NextSyncOperationResult(
        command.pending.operationId, "activity_event", command.pending.intent.eventUuid, "applied", 1, entity = payload)
    private fun JsonObject.changed(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private suspend fun rejected(block: suspend () -> Unit) { assertNotNull(runCatching { block() }.exceptionOrNull()) }
    private suspend fun queued() = db.syncOutboxDao().getActivityIntents(habit.uuid)

    @Test fun completeUndoCompleteAcknowledgementConsumesOnlyCausalHeadAndSurvivesRestart() = runBlocking {
        val commands = List(3) { append() }
        val original = queued()
        commands.forEachIndexed { index, command ->
            val prepared = sync().prepare(habit.uuid)!!
            assertEquals(command.pending.operationId, prepared.operation.operationId)
            rule.reopen()
            val state = sync().acknowledge(prepared, result(command))
            assertEquals(index + 1, state.confirmed.version)
            assertEquals(3, state.queue.optimisticState.version)
            assertEquals(original.drop(index + 1), queued())
            assertNotNull(db.completionFollowUpDao().submission(command.pending.operationId))
            assertEquals(3, db.completionDao().getByHabitOnce(habit.id).size)
        }
        rule.reopen()
        assertNull(sync().prepare(habit.uuid))
        assertEquals(commands.last().pending.intent.eventUuid, local().read(habit.uuid).confirmed.completionEventUuid)
    }

    @Test fun pullBeforeLostResponseKeepsOriginalOutboxAndReplayDoesNotRegressNewerState() = runBlocking {
        val commands = List(3) { append() }
        // The first two requests must be confirmed before the causal sender can send the third.
        commands.take(2).forEach { sync().acknowledge(sync().prepare(habit.uuid)!!, result(it)) }
        val prepared = sync().prepare(habit.uuid)!!
        val original = queued()
        val third = commands[2].pending.intent.eventUuid
        val fourth = command(OneTimeState(3, third, third))
        val fifth = command(OneTimeState(4, fourth.pending.intent.eventUuid, null))
        sync().apply(sync().context(), listOf(change(fifth), change(commands[2]), change(fourth), change(commands[0]), change(commands[1])))
        assertEquals(original, queued())
        assertEquals(5, local().read(habit.uuid).confirmed.version)
        assertEquals(listOf(commands[2].pending.operationId), local().read(habit.uuid).queue.awaitingReplayOperationIds)
        rule.reopen()
        assertEquals(prepared, sync().prepare(habit.uuid))
        sync().acknowledge(prepared, result(commands[2]).copy(status = "already_applied"))
        assertEquals(5, local().read(habit.uuid).confirmed.version)
        assertTrue(queued().isEmpty())
    }

    @Test fun duplicateSuccessAndLocalCommandRetryDoNotQueueAnotherFact() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        sync().acknowledge(request, result(command))
        val shadow = db.syncOutboxDao().getState("activity_event", command.pending.intent.eventUuid)
        rule.reopen()
        sync().acknowledge(request, result(command).copy(status = "already_applied"))
        assertEquals(shadow, db.syncOutboxDao().getState("activity_event", command.pending.intent.eventUuid))
        assertTrue(local().append(local().read(habit.uuid).session, command).alreadyStored)
        assertTrue(queued().isEmpty())
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun immutableBodyAndEnvelopeMismatchCannotAcknowledgeAnOperation() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        val original = queued()
        val body = entity(command)
        val invalid = listOf(
            body.changed("occurred_at", JsonPrimitive("2026-09-27T16:00:02Z")),
            body.changed("source_device_id", JsonPrimitive(uuid())), body.changed("source_type", JsonPrimitive("widget")),
            body.changed("note", JsonPrimitive("changed")), body.changed("metadata", buildJsonObject { put("changed", true) }),
            body.changed("external_event_id", JsonPrimitive("other")), body.changed("local_date", JsonPrimitive("2026-09-27")),
            body.changed("timezone", JsonPrimitive("UTC")), body.changed("revision", JsonPrimitive(2)),
            body.changed("value", JsonPrimitive(2)), body.changed("duration_seconds", JsonPrimitive(1)),
            body.changed("day_allocations", JsonArray(emptyList())),
            body.changed("occurred_at", JsonPrimitive("2026-09-27T16:00:01")),
            JsonObject(body - "received_at"), body.changed("deleted_at", JsonPrimitive(timestamp)))
        invalid.forEach { payload -> rejected { sync().acknowledge(request, result(command, payload)) } }
        rejected { sync().acknowledge(request, result(command).copy(operationId = uuid())) }
        rejected { sync().acknowledge(request, result(command).copy(revision = null)) }
        rejected { sync().acknowledge(request, result(command).copy(conflictingFields = listOf("occurred_at"))) }
        rejected { sync().acknowledge(request, result(command).copy(status = "rejected", errorCode = "INVALID_PAYLOAD", entity = null)) }
        rule.reopen()
        assertEquals(original, queued()); assertEquals(0, local().read(habit.uuid).confirmed.version)
        assertNull(db.syncOutboxDao().getState("activity_event", command.pending.intent.eventUuid))
    }

    @Test fun failedOutboxDeletionRollsBackProjectionShadowAndFlagThenRetrySucceeds() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        val original = queued()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_ack BEFORE DELETE ON sync_outbox BEGIN SELECT RAISE(ABORT, 'synthetic acknowledgement failure'); END")
        rejected { sync().acknowledge(request, result(command)) }
        rule.reopen()
        assertEquals(original, queued()); assertEquals(0, local().read(habit.uuid).confirmed.version)
        assertNull(db.syncOutboxDao().getState("activity_event", command.pending.intent.eventUuid))
        db.openHelper.readableDatabase.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_ack")
        assertEquals(1, sync().acknowledge(request, result(command)).confirmed.version)
    }

    @Test fun aLaterInvalidPageFactRollsBackEarlierValidFact() = runBlocking {
        val first = command()
        val next = command(OneTimeState(1, first.pending.intent.eventUuid, first.pending.intent.eventUuid))
        rejected { sync().apply(sync().context(), listOf(change(first), change(next, entity(next).changed("timezone", JsonPrimitive("not/a/zone"))))) }
        rule.reopen()
        assertEquals(0, local().read(habit.uuid).confirmed.version)
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
        assertNull(db.syncOutboxDao().getState("activity_event", first.pending.intent.eventUuid))
    }

    @Test fun remoteUndoIsStoredAsFactAndOutOfOrderReplayPreservesGlobalState() = runBlocking {
        val first = command()
        val second = command(OneTimeState(1, first.pending.intent.eventUuid, first.pending.intent.eventUuid))
        val context = sync().context()
        sync().apply(context, listOf(change(second)))
        rule.reopen()
        assertEquals(2, local().read(habit.uuid).confirmed.version)
        sync().apply(context, listOf(change(first), change(second)))
        assertEquals(2, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(2, local().read(habit.uuid).confirmed.version)
        assertNull(local().read(habit.uuid).confirmed.completionEventUuid)
        assertTrue(queued().isEmpty())
        assertNull(db.completionFollowUpDao().prompt(first.pending.intent.eventUuid))
    }

    @Test fun sameVersionForkAndMutatedPreviouslySeenFactAreRejectedAtomically() = runBlocking {
        val first = command()
        sync().apply(sync().context(), listOf(change(first)))
        rejected { sync().apply(sync().context(), listOf(change(command()))) }
        rejected { sync().apply(sync().context(), listOf(change(first, entity(first).changed("note", JsonPrimitive("mutated"))))) }
        rule.reopen()
        assertEquals(first.pending.intent.eventUuid, local().read(habit.uuid).confirmed.headEventUuid)
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun independentCoordinatorsConcurrentlyAcknowledgeExactlyOnce() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        val gate = CompletableDeferred<Unit>()
        val jobs = List(2) { async(Dispatchers.IO) { gate.await(); sync(AccountSessionCoordinator()).acknowledge(request, result(command)) } }
        gate.complete(Unit)
        assertEquals(listOf(1, 1), jobs.awaitAll().map { it.confirmed.version })
        rule.reopen()
        assertTrue(queued().isEmpty()); assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun accountEpochDeviceAndLoginChangesRejectStaleResponseWithoutConsumingQueue() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        val original = queued()
        tokens.saveServerIdentity("server", "new-epoch")
        rejected { sync().acknowledge(request, result(command)) }
        tokens.saveServerIdentity("server", "epoch")
        tokens.saveSyncDeviceId(uuid())
        rejected { sync().acknowledge(request, result(command)) }
        tokens.saveSyncDeviceId(device)
        tokens.saveLoginSession("synthetic-next", "synthetic-r", "member", "account-a", false)
        rejected { sync().acknowledge(request, result(command)) }
        tokens.saveLoginSession("synthetic-other", "synthetic-r", "member", "account-b", false)
        rejected { sync().acknowledge(request, result(command)) }
        rule.reopen(); assertEquals(original, queued())
    }

    @Test fun permissionRevocationBlocksNewSendButNotValidInFlightSuccess() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        tokens.saveDeviceRegistration(device, setOf("sync.read"), false, 2)
        rejected { sync().prepare(habit.uuid) }
        assertEquals(1, sync().acknowledge(request, result(command)).confirmed.version)
        assertTrue(queued().isEmpty())
    }

    @Test fun tombstonesMissingParentsAndRejectedPredecessorsCannotBeResurrected() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        val original = queued()
        for ((type, id) in listOf("activity_event" to command.pending.intent.eventUuid, "plan_node" to habit.uuid)) {
            db.syncOutboxDao().upsertState(SyncEntityStateEntity(type, id, 2, deleted = true))
            rejected { sync().acknowledge(request, result(command)) }
            rejected { sync().apply(request.context, listOf(change(command))) }
            assertEquals(original, queued())
            db.syncOutboxDao().deleteState(type, id)
        }
        rejected { sync().apply(request.context, listOf(change(command.copy(activityUuid = uuid())))) }
        db.syncOutboxDao().markDeadLetter(original.first().id, "TASK_STATE_CONFLICT", "rejected", 1)
        rejected { sync().prepare(habit.uuid) }
        rejected { sync().acknowledge(request, result(command)) }
        assertEquals(1, queued().size)
    }

    @Test fun receiptOrOutboxCorruptionCannotTurnLocalDurabilityIntoAcknowledgement() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        db.syncOutboxDao().deleteById(queued().first().id)
        rejected { sync().acknowledge(request, result(command)) }
        assertEquals(0, db.habitDao().getHabitByUuid(habit.uuid)!!.oneTimeConfirmedVersion)
        assertNull(db.syncOutboxDao().getState("activity_event", command.pending.intent.eventUuid))
    }

    @Test fun metricDraftsAndSavedObservationsSurviveCompletionAndUndoAcknowledgements() = runBlocking {
        val metric = MetricEntity(name = "Reading", unit = "kg", iconResId = 0, colorHex = "#000000")
        val metricId = db.metricDao().insert(metric)
        db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
            metricId = metricId, metricUuid = metric.uuid, promptOnComplete = true))
        val command = append()
        val prompts = CompletionMetricPromptStore(db, tokens, sessions)
        val draft = prompts.updateDraft(prompts.read(command.pending.intent.eventUuid),
            mapOf(metric.uuid to CompletionMetricInput("42", "retained")))
        val request = sync().prepare(habit.uuid)!!
        sync().acknowledge(request, result(command))
        assertEquals(draft.row, prompts.read(command.pending.intent.eventUuid).row)
        val saved = prompts.submit(draft)
        val log = db.metricLogDao().getLogByUuid(saved.observationUuids.single())
        val metricQueue = db.syncOutboxDao().getByOperationId(draft.entries.single().operationId)
        val undo = append()
        sync().acknowledge(sync().prepare(habit.uuid)!!, result(undo))
        rule.reopen()
        assertEquals(log, db.metricLogDao().getLogByUuid(saved.observationUuids.single()))
        assertEquals(metricQueue, db.syncOutboxDao().getByOperationId(draft.entries.single().operationId))
        val reopened = CompletionMetricPromptStore(db, tokens, sessions)
        assertEquals("saved", reopened.read(command.pending.intent.eventUuid).row.state)
        assertTrue(reopened.submit(draft).alreadyStored)
        assertEquals(2, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun replayPreparationRetainsFrozenPayloadAndEquivalentUtcInstantIsAccepted() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        val first = queued().single()
        rule.reopen()
        assertEquals(request, sync().prepare(habit.uuid))
        val again = queued().single()
        assertEquals(first.payloadJson, again.payloadJson)
        assertEquals(first.operationId, again.operationId)
        assertEquals(first.attemptCount + 1, again.attemptCount)
        sync().acknowledge(request, result(command, entity(command).changed("occurred_at", JsonPrimitive("2026-09-27T16:00:01.000000Z"))))
        assertEquals(Instant.parse(timestamp).toEpochMilli(), db.completionDao().getCompletionByUuid(command.pending.intent.eventUuid)!!.actualCompletedAt)
        assertEquals("2026-09-28", db.completionDao().getCompletionByUuid(command.pending.intent.eventUuid)!!.recordedLocalDate)
    }

    @Test fun oldAuthoritativeSlotsAndKnownEdgesCannotForkButUnconfirmedAlternativesRemain() = runBlocking {
        val pending = append()
        val first = command()
        val second = command(OneTimeState(1, first.pending.intent.eventUuid, first.pending.intent.eventUuid))
        val third = command(OneTimeState(2, second.pending.intent.eventUuid, null))
        // Local offline completion is an unknown result, not another authoritative slot.
        sync().apply(sync().context(), listOf(change(third), change(first)))
        assertEquals(listOf(pending.pending.operationId), local().read(habit.uuid).queue.awaitingReplayOperationIds)
        rejected { sync().apply(sync().context(), listOf(change(command()))) }
        val wrongSecond = command(OneTimeState(1, first.pending.intent.eventUuid, first.pending.intent.eventUuid))
        rejected { sync().apply(sync().context(), listOf(change(wrongSecond))) }
        assertNull(db.completionDao().getCompletionByUuid(wrongSecond.pending.intent.eventUuid))
        sync().apply(sync().context(), listOf(change(second)))
        rule.reopen()
        assertEquals(3, local().read(habit.uuid).confirmed.version)
        assertEquals(4, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(pending.pending.operationId, queued().single().operationId)
    }

    private fun conflict(command: OneTimeLocalCommand) = NextSyncOperationResult(
        command.pending.operationId, "activity_event", command.pending.intent.eventUuid, "conflict",
        errorCode = "TASK_STATE_CONFLICT", message = "synthetic conflict",
        oneTimeConflict = OneTimeProjection(habit.uuid, OneTimeState(1,
            "22222222-0000-4000-8000-000000000001", "22222222-0000-4000-8000-000000000001")))

    @Test fun firstPreparationFailureRollsBackBindingAndAttemptTogether() = runBlocking {
        val command = append()
        val before = queued()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_prepare BEFORE UPDATE ON sync_outbox BEGIN SELECT RAISE(ABORT, 'synthetic preparation failure'); END")
        rejected { sync().prepare(habit.uuid) }
        rule.reopen()
        assertEquals(before, queued())
        assertNull(db.completionFollowUpDao().transmission(command.pending.operationId))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_prepare")
        sync().prepare(habit.uuid)
        assertEquals(device, db.completionFollowUpDao().transmission(command.pending.operationId)!!.deviceId)
        assertEquals(1, queued().single().attemptCount)
    }

    @Test fun sameAccountReloginReusesDurableBindingButRejectsOldCallback() = runBlocking {
        val command = append()
        val first = sync().prepare(habit.uuid)!!
        val binding = db.completionFollowUpDao().transmission(command.pending.operationId)!!
        tokens.saveLoginSession("synthetic-new", "synthetic-r", "member", "account-a", false)
        rule.reopen()
        rejected { sync().acknowledge(first, result(command)) }
        val retry = sync().prepare(habit.uuid)!!
        assertNotEquals(first.context.session.authentication.generation, retry.context.session.authentication.generation)
        assertEquals(first.operation, retry.operation)
        assertEquals(binding, db.completionFollowUpDao().transmission(command.pending.operationId))
        sync().acknowledge(retry, result(command).copy(status = "already_applied"))
        assertEquals(binding, db.completionFollowUpDao().transmission(command.pending.operationId))
        assertTrue(queued().isEmpty())
    }

    @Test fun registrationChangeCannotResendButPullValidatesOriginalSourceDevice() = runBlocking {
        val command = append()
        val first = sync().prepare(habit.uuid)!!
        val before = queued()
        val other = uuid()
        tokens.saveSyncDeviceId(other)
        rule.reopen()
        val failure = runCatching { sync().prepare(habit.uuid) }.exceptionOrNull()
        assertEquals(OneTimeLocalException.Reason.TRANSMISSION_CONTEXT_CHANGED, (failure as OneTimeLocalException).reason)
        rejected { sync().acknowledge(first, result(command)) }
        rejected { sync().apply(sync().context(), listOf(change(command, entity(command, other)))) }
        sync().apply(sync().context(), listOf(change(command)))
        assertEquals(1, local().read(habit.uuid).confirmed.version)
        assertEquals(before, queued())
        rejected { sync().prepare(habit.uuid) }
        tokens.saveSyncDeviceId(device)
        sync().acknowledge(sync().prepare(habit.uuid)!!, result(command).copy(status = "already_applied"))
        assertTrue(queued().isEmpty())
    }

    @Test fun serverOrEpochChangeCannotRebindOldTransmissionEvenWithFreshContext() = runBlocking {
        append()
        sync().prepare(habit.uuid)
        val before = queued()
        listOf("server-2" to "epoch", "server" to "epoch-2").forEach { (server, epoch) ->
            tokens.saveServerIdentity(server, epoch)
            val failure = runCatching { sync().prepare(habit.uuid) }.exceptionOrNull()
            assertEquals(OneTimeLocalException.Reason.TRANSMISSION_CONTEXT_CHANGED, (failure as OneTimeLocalException).reason)
            assertEquals(before, queued())
        }
    }

    @Test fun missingOrMutatedTransmissionCannotBeGuessedFromLocalFactOrCurrentDevice() = runBlocking {
        val command = append()
        rejected { sync().apply(sync().context(), listOf(change(command))) }
        assertEquals(0, local().read(habit.uuid).confirmed.version)
        val request = sync().prepare(habit.uuid)!!
        val before = queued()
        db.openHelper.writableDatabase.execSQL("UPDATE one_time_transmissions SET operationJson='{}'")
        rejected { sync().prepare(habit.uuid) }
        rejected { sync().acknowledge(request, result(command)) }
        rejected { sync().apply(sync().context(), listOf(change(command))) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM one_time_transmissions")
        rule.reopen()
        rejected { sync().prepare(habit.uuid) }
        assertEquals(before, queued())
        assertNull(db.completionFollowUpDao().transmission(command.pending.operationId))
    }

    @Test fun anotherAuthenticatedOwnerCannotClaimTransmissionUsingTheSameReplicaAndDevice() = runBlocking {
        val command = append()
        sync().prepare(habit.uuid)
        val before = queued()
        val binding = db.completionFollowUpDao().transmission(command.pending.operationId)
        // Deliberately leave the old rows to test fail-closed defense beyond normal account cleanup.
        tokens.saveLoginSession("synthetic-other", "synthetic-r", "member", "account-b", false)
        tokens.saveServerIdentity("server", "epoch")
        tokens.saveDeviceRegistration(device, setOf("sync.read", "facts.append"), false, 1)
        val fresh = sync().context()
        val failure = runCatching { sync().prepare(habit.uuid) }.exceptionOrNull()
        assertEquals(OneTimeLocalException.Reason.TRANSMISSION_CONTEXT_CHANGED, (failure as OneTimeLocalException).reason)
        rejected { sync().apply(fresh, listOf(change(command))) }
        assertEquals(before, queued())
        assertEquals(binding, db.completionFollowUpDao().transmission(command.pending.operationId))
        assertEquals(0, db.habitDao().getHabitByUuid(habit.uuid)!!.oneTimeConfirmedVersion)
    }

    @Test fun explicitRejectionPersistsAndBlocksTheUnmodifiedCausalSuffixWithoutFabricatingFacts() = runBlocking {
        val commands = List(3) { append() }
        val request = sync().prepare(habit.uuid)!!
        val before = queued()
        val state = sync().reject(request, conflict(commands[0]))
        assertEquals(commands.map { it.pending.operationId }, state.queue.blockedOperationIds)
        assertEquals(0, state.confirmed.version)
        rule.reopen()
        assertEquals(before.drop(1), queued().drop(1))
        assertEquals(before.first().payloadJson, queued().first().payloadJson)
        assertEquals(before.first().operationId, queued().first().operationId)
        assertNotNull(db.completionFollowUpDao().transmission(request.operation.operationId)!!.rejectionJson)
        assertEquals(3, db.completionDao().getByHabitOnce(habit.id).size)
        assertNull(db.completionDao().getCompletionByUuid(conflict(commands[0]).oneTimeConflict!!.state.headEventUuid!!))
        val rejectedRows = queued()
        sync().reject(request, conflict(commands[0]))
        assertEquals(rejectedRows, queued())
        rejected { sync().prepare(habit.uuid) }
        rejected { append() }
    }

    @Test fun rejectionTransactionFailureDoesNotPersistPartialTerminalResult() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        val before = queued()
        val binding = db.completionFollowUpDao().transmission(command.pending.operationId)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_rejection BEFORE UPDATE ON sync_outbox BEGIN SELECT RAISE(ABORT, 'synthetic rejection failure'); END")
        rejected { sync().reject(request, conflict(command)) }
        rule.reopen()
        assertEquals(before, queued())
        assertEquals(binding, db.completionFollowUpDao().transmission(command.pending.operationId))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_rejection")
        assertEquals(listOf(command.pending.operationId), sync().reject(request, conflict(command)).queue.blockedOperationIds)
    }

    @Test fun wrongMissingOrContradictoryResultsCannotBecomePermanentRejections() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        val before = queued()
        val response = conflict(command)
        listOf(response.copy(operationId = uuid()), response.copy(entityUuid = uuid()),
            response.copy(oneTimeConflict = response.oneTimeConflict!!.copy(activityUuid = uuid())),
            response.copy(conflictingFields = listOf("unexpected")),
            response.copy(errorCode = null, oneTimeConflict = null), result(command)).forEach {
            rejected { sync().reject(request, it) }
        }
        rule.reopen()
        assertEquals(before, queued())
        assertNull(db.completionFollowUpDao().transmission(command.pending.operationId)!!.rejectionJson)
        sync().reject(request, response)
        val terminal = queued()
        rejected { sync().reject(request, response.copy(errorCode = "TASK_ALREADY_COMPLETED")) }
        rejected { sync().acknowledge(request, result(command)) }
        rejected { sync().apply(sync().context(), listOf(change(command))) }
        assertEquals(terminal, queued())
    }

    @Test fun acceptedFactCannotBeReclassifiedAsRejectedEvenBeforeOutboxAcknowledgement() = runBlocking {
        val command = append()
        val request = sync().prepare(habit.uuid)!!
        sync().apply(sync().context(), listOf(change(command)))
        val before = queued()
        rejected { sync().reject(request, conflict(command)) }
        assertEquals(before, queued())
        sync().acknowledge(request, result(command))
        rejected { sync().reject(request, conflict(command)) }
        assertEquals(1, local().read(habit.uuid).confirmed.version)
    }

    @Test fun rejectionPreservesSavedMetricFactsAndLaterDraftsEvenAfterPermissionRevocation() = runBlocking {
        val metric = MetricEntity(name = "Reading", unit = "kg", iconResId = 0, colorHex = "#000000")
        val metricId = db.metricDao().insert(metric)
        db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
            metricId = metricId, metricUuid = metric.uuid, promptOnComplete = true))
        val first = append()
        val prompts = CompletionMetricPromptStore(db, tokens, sessions)
        val input = prompts.updateDraft(prompts.read(first.pending.intent.eventUuid), mapOf(metric.uuid to CompletionMetricInput("42", "saved")))
        val saved = prompts.submit(input)
        val log = db.metricLogDao().getLogByUuid(saved.observationUuids.single())
        val metricQueue = db.syncOutboxDao().getByOperationId(input.entries.single().operationId)
        append() // Undo remains a separate fact, not a deletion of the metric observation.
        val third = append()
        val draft = prompts.updateDraft(prompts.read(third.pending.intent.eventUuid), mapOf(metric.uuid to CompletionMetricInput("43", "pending")))
        val request = sync().prepare(habit.uuid)!!
        tokens.saveDeviceRegistration(device, setOf("sync.read"), false, 2)
        sync().reject(request, conflict(first))
        rule.reopen()
        assertEquals(log, db.metricLogDao().getLogByUuid(saved.observationUuids.single()))
        assertEquals(metricQueue, db.syncOutboxDao().getByOperationId(input.entries.single().operationId))
        assertEquals("saved", db.completionFollowUpDao().prompt(first.pending.intent.eventUuid)!!.state)
        assertEquals(draft.row, db.completionFollowUpDao().prompt(third.pending.intent.eventUuid))
        assertEquals(3, local().read(habit.uuid).queue.blockedOperationIds.size)
    }
}
