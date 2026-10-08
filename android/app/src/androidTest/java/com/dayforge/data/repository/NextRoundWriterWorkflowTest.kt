package com.dayforge.data.repository

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.dayforge.ui.screens.creategoal.CreateGoalViewModel

/** Real production creators/once/prompts/timer writer, file Room/auth/socket and cold retry. */
@RunWith(AndroidJUnit4::class)
class NextRoundWriterWorkflowTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private val records = mutableListOf<ChallengeRoundRecord>()
    private val births = mutableListOf<ChallengeBirth>()
    private val timerPolicies = mutableMapOf<String, TimerStartPolicy>()
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#123456", "theme")
    private fun initial(uuid: String) = ChallengeRoundRecord(initialChallengeRoundHead(uuid), null, null, null)
    private fun metadata() = ChallengeMetadata(1, records.groupBy { it.head.activityUuid }.map { (_, history) ->
        ChallengeCheckpoint(history.maxBy { it.head.generation }.head, history.toList()) }, births.toList())
    private fun timers(http: NextSyncHttp) = NextTimerRequestStore(db, tokens, sessions, sender(http))
    private fun onceStore(http: NextSyncHttp) = NextOneTimeRequestStore(db, tokens, sessions, http,
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)), sender(http))
    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions,
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers(http)), timers(http))
    private fun canonical(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to (body["created_at"] ?: JsonPrimitive(time)), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private suspend fun initialize(http: NextSyncHttp) {
        register()
        val rows = db.habitDao().getAllHabitsOnce()
        records += rows.filter { it.completionPolicy == "recurring" }.map { initial(it.uuid) }
        val meta = metadata()
        merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(rows.map {
            canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it)) } +
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)), 20, time,
            rows.filter { it.completionPolicy == "one_and_done" }.map { OneTimeProjection(it.uuid, OneTimeState(0, null, null)) },
            1, meta.checkpoints, meta.births))
    }
    private fun respond(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        if (input.path == "/api/v2/sync/push") return successReply(input)
        if (input.path.endsWith("/push")) {
            assertEquals("/api/v2/sync/rounds/push", input.path)
            val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
            val operation = request.operations.single()
            val context = request.contexts.single()
            if (operation.entityType == "plan_node" && context.head != null && records.none { it.head.activityUuid == operation.entityUuid })
                records += initial(operation.entityUuid)
            if (operation.entityType == "activity_event" && context.head != null)
                births += ChallengeBirth("activity_event", operation.entityUuid, context.head)
            val ordinary = json.decodeFromString<NextSyncPushResponse>(successReply(input, (operation.baseRevision ?: 0) + 1) { body ->
                when {
                    operation.entityType == "metric" && operation.entityUuid == metric.uuid -> JsonObject(body + ("created_at" to JsonPrimitive(time)))
                    body["one_time"] is JsonObject -> {
                        val intent = body.getValue("one_time").jsonObject
                        JsonObject(body + ("one_time_state_after" to buildJsonObject {
                            put("version", intent.getValue("expected_version").jsonPrimitive.int + 1)
                            put("head_event_uuid", intent.getValue("event_uuid"))
                            put("completion_event_uuid", if (intent["action"] == JsonPrimitive("complete")) intent.getValue("event_uuid") else JsonNull)
                        }))
                    }
                    else -> body
                }
            }.bytes.toString(Charsets.UTF_8))
            val meta = metadata()
            return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(ordinary.results, 1, meta.checkpoints, meta.births)).toByteArray())
        }
        assertEquals("/api/v2/timers/rounds/commands", input.path)
        val request = json.decodeFromString<RoundTimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8))
        val command = request.commands.single()
        val head = requireNotNull(request.contexts.single().head)
        command.startPolicy?.let { timerPolicies[command.sessionId] = it }
        val policy = timerPolicies.getValue(command.sessionId)
        if (births.none { it.entityType == "timer_session" && it.entityUuid == command.sessionId })
            births += ChallengeBirth("timer_session", command.sessionId, head)
        val state = if (command.commandType == "cancel") "cancelled" else if (command.commandType == "pause") "paused" else "running"
        val session = TimerSessionResponse(command.sessionId, head.activityUuid, state, id(4), 1, command.sequence,
            command.sequence + 1, time, command.occurredAt, endedAt = command.occurredAt.takeIf { state == "cancelled" },
            timezone = "Asia/Shanghai", isCountdown = policy.isCountdown, targetSeconds = policy.targetSeconds,
            maxDurationSeconds = policy.maxDurationSeconds, activeElapsedMs = command.activeElapsedMs ?:
                java.time.Duration.between(java.time.Instant.parse(time), java.time.Instant.parse(command.occurredAt)).toMillis())
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundTimerCommandBatchResponse(listOf(
            TimerCommandResult(command.commandId, command.sessionId, "applied", session = session)), time, 1, meta.checkpoints, meta.births)).toByteArray())
    }
    private suspend fun task(repo: HabitRepository = onceHabits(onceRepository())) = repo.createHabit(
        "Production once", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(), selectedMetricIds = setOf(metric.id),
        failMode = FailMode.LOOSE, appearance = appearance("task.reading"), completionPolicy = "one_and_done", creationAuthority = creator.capture())
    private suspend fun acceptStructures(http: NextSyncHttp) {
        for (row in db.syncOutboxDao().getAll().filter { it.recordType in setOf("habit", "metric", "link") })
            assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
    }
    private fun writer() = NextTimerWriter(db, tokens, sessions)
    private suspend fun start(row: HabitEntity, ticket: TimerActionAuthority?) = writer().write(row.id, ticket) {
        db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = row.id, uuid = id(820), startTime = millis, date = millis,
            endTime = null, durationSeconds = 0, timerNextCommandSequence = 2, timerControlGeneration = 1,
            timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
            TimerCommandEntity(commandId = id(821), sessionUuid = id(820), sequence = 1, commandType = "start",
                occurredAt = millis, expectedControlGeneration = 0, activityUuid = row.uuid, timezone = "Asia/Shanghai"),
            TimerSegmentEntity(sessionUuid = id(820), sequence = 1, startedAt = millis))
    }

    @Test fun actualNewGraphAndMetricFreezeOriginalProfileAndAcceptWithoutInventedOnceHead() = runBlocking<Unit> {
        val (http, _) = channel(::respond); initialize(http)
        val ticket = creator.capture()
        val goal = HabitDraft(id = id(801), name = "Original goal", habitType = HabitType.GOAL, appearance = appearance("goal.default"))
        val child = HabitDraft(id = id(802), name = "Original count", habitType = HabitType.COUNTING,
            completionPolicy = "recurring", appearance = appearance("habit.exercise"), selectedMetricIds = setOf(metric.id))
        creatingHabits().createGoal(goal, listOf(child), creationAuthority = ticket)
        val taskId = task()
        creatingMetrics().createMetric(MetricEntity(uuid = id(803), name = "Created metric", unit = "kg", iconResId = 0,
            colorHex = "#123456", appearance = appearance("metric.weight")), creationAuthority = ticket)
        val sources = db.syncOutboxDao().getAll().associate { it.operationId to originalIntent(it) }
        assertEquals(6, sources.size)
        for (source in sources.values) {
            val intent = roundOperationIntent(source.intentJson)!!
            assertEquals(id(4), intent.capturedDeviceId)
            assertEquals(if (intent.operation.entityUuid == child.id) initialChallengeRoundHead(child.id) else null, intent.context.head)
        }
        assertEquals(2, count("next_challenge_rounds")); assertEquals(0, count("next_challenge_births"))
        acceptStructures(http); storage.reopen()
        assertEquals(goal.id, db.habitDao().getHabitByUuid(child.id)!!.parentHabitId)
        assertEquals(0, db.habitDao().getHabitById(taskId)!!.oneTimeConfirmedVersion)
        for ((id, source) in sources) assertEquals(source, db.nextRequestDao().origin(NEXT_OPERATION, id))
        assertEquals(3, count("next_challenge_rounds")); assertEquals(20L, db.nextSyncStateDao().rows().single().cursor)
    }

    @Test fun creatorAndTimerPlainDisplayedTicketsCannotUpgradeToRestoredProfile() = runBlocking<Unit> {
        register()
        val creation = creator.capture(); val timer = writer().capture(timerHabit.id)
        val (http, _) = channel(::respond); initialize(http)
        rejected { creatingHabits().createHabit("Expired form", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Daily,
            appearance = appearance("habit.exercise"), completionPolicy = "recurring", creationAuthority = creation) }
        rejected { start(timerHabit, timer) }
        assertEquals(2, count("habits")); assertEquals(0, count("sync_outbox")); assertNull(db.timeLogDao().getActiveTimeLog())
        assertEquals(0, count("next_request_origins"))
    }

    @Test fun restoredGoalDraftCannotAdoptAProfileWithoutReauthentication() = runBlocking<Unit> {
        register()
        val handle = SavedStateHandle()
        val old = withContext(Dispatchers.Main) { own(CreateGoalViewModel(app, creatingHabits(), handle)) }
        val ticket = creator.capture()
        withContext(Dispatchers.Main) { old.beginCreation(ticket); old.updateName("Original saved draft") }
        val draft = old.uiState.value
        val (http, _) = channel(::respond); initialize(http)
        val restored = withContext(Dispatchers.Main) { own(CreateGoalViewModel(app, creatingHabits(), handle)) }
        val fresh = creator.capture()
        withContext(Dispatchers.Main) { restored.beginCreation(fresh) }
        assertNull(restored.uiState.value.creationAuthority)
        assertTrue(restored.uiState.value.errorMessage!!.contains("OBJECT_CREATE_DRAFT_EXPIRED"))
        assertEquals(draft.parentUuid, restored.uiState.value.parentUuid)
        assertEquals(draft.name, restored.uiState.value.name)
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
    }

    @Test fun productionOnceCompleteUndoAndPromptKeepIndependentHistoryAcrossActualAckAndColdRetry() = runBlocking<Unit> {
        val (http, _) = channel(::respond); initialize(http)
        val once = onceRepository(); val repo = onceHabits(once); val taskId = task(repo)
        acceptStructures(http)
        val first = repo.getOneTimeStatus(taskId)
        once.change(taskId, true, authority = first.authority)
        val complete = db.syncOutboxDao().getAll().single()
        assertNull(roundOperationIntent(originalIntent(complete).intentJson)!!.context.head)
        val prompt = once.saveDraft(once.prompt(taskId)!!, mapOf(metric.id to ("12.250" to "actual round prompt")))
        once.submit(prompt)
        val observation = db.syncOutboxDao().getAll().single { it.recordType == "metric_log" }
        val original = originalIntent(observation)
        assertNull(roundOperationIntent(original.intentJson)!!.context.head)
        val actualLog = db.metricLogDao().getLogByUuid(observation.entityUuid)!!
        assertEquals(12.25, actualLog.value, 0.0)
        assertEquals(NextOneTimeOutcome.Accepted(NextOperationAcceptance.COMMITTED), onceStore(http).sendAndAccept(access(), complete.operationId))
        sender(http).sendAndAcceptOperation(access(), observation.operationId)
        val done = repo.getOneTimeStatus(taskId)
        repo.undoCompletion(app, done.completionId!!, oneTimeAuthority = done.authority)
        val undo = db.syncOutboxDao().getAll().single()
        assertEquals(NextOneTimeOutcome.Accepted(NextOperationAcceptance.COMMITTED), onceStore(http).sendAndAccept(access(), undo.operationId))
        storage.reopen(); onceRepository().submit(prompt)
        assertFalse(onceHabits(onceRepository()).getOneTimeStatus(taskId).completed)
        assertEquals(2, db.completionDao().getByHabitOnce(taskId).size)
        val acceptedLog = db.metricLogDao().getLogByUuid(observation.entityUuid)!!
        assertEquals(actualLog.date, acceptedLog.date); assertEquals(actualLog.recordedTimezone, acceptedLog.recordedTimezone)
        assertEquals(actualLog.value, acceptedLog.value, 0.0); assertEquals(actualLog.note, acceptedLog.note)
        assertEquals(original, originalIntent(observation)); assertEquals(0, count("next_challenge_births"))
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertEquals(20L, db.nextSyncStateDao().rows().single().cursor)
    }

    @Test fun sourceInsertFaultRetainsPromptInputTimeAndIdsWithoutPartialObservationThenExactRetryWorks() = runBlocking<Unit> {
        val (http, _) = channel(::respond); initialize(http)
        val once = onceRepository(); val repo = onceHabits(once); val taskId = task(repo)
        acceptStructures(http); repo.logCompletion(app, taskId)
        val prompt = once.saveDraft(once.prompt(taskId)!!, mapOf(metric.id to ("42.125" to "retry")))
        val queues = db.syncOutboxDao().getAll()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER prompt_source_fault BEFORE INSERT ON next_request_origins WHEN NEW.intentJson LIKE '%metric_observation%' BEGIN SELECT RAISE(ABORT,'prompt source'); END")
        rejected { once.submit(prompt) }
        val frozen = db.completionFollowUpDao().prompt(prompt.eventUuid)!!
        assertNotNull(frozen.recordedAtMillis); assertEquals("pending", frozen.state)
        assertTrue(db.metricLogDao().getLogsByMetric(metric.id).first().isEmpty()); assertEquals(queues, db.syncOutboxDao().getAll())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER prompt_source_fault")
        storage.reopen(); onceRepository().submit(prompt)
        val observation = db.syncOutboxDao().getAll().single { it.recordType == "metric_log" }
        assertEquals(frozen.recordedAtMillis, db.metricLogDao().getLogByUuid(observation.entityUuid)!!.date)
        assertNotNull(roundOperationIntent(originalIntent(observation).intentJson))
        onceRepository().submit(prompt); assertEquals(1, db.metricLogDao().getLogsByMetric(metric.id).first().size)
    }

    @Test fun promptCannotDiscardDisplayedProfileOrAdoptAnotherDeviceOnSubmitOrDraft() = runBlocking<Unit> {
        val (http, _) = channel(::respond); initialize(http)
        val once = onceRepository(); val repo = onceHabits(once); val taskId = task(repo)
        repo.logCompletion(app, taskId)
        val store = CompletionMetricPromptStore(db, tokens, sessions)
        val original = store.read(once.prompt(taskId)!!.eventUuid)
        rejected { store.updateDraft(original.copy(rounds = null), mapOf(metric.uuid to CompletionMetricInput("12"))) }
        val entered = store.updateDraft(original, mapOf(metric.uuid to CompletionMetricInput("12")))
        val before = db.completionFollowUpDao().prompt(entered.row.eventUuid)
        tokens.saveDeviceRegistration(id(5), caps, true, 2)
        rejected { store.submit(entered) }; rejected { store.updateDraft(entered, mapOf(metric.uuid to CompletionMetricInput("13"))) }
        assertEquals(before, db.completionFollowUpDao().prompt(entered.row.eventUuid)); assertEquals(0, count("metric_logs"))
    }

    @Test fun actualTypedTimerStartAndColdCancelKeepStartPolicyProfileAndOriginalBirth() = runBlocking<Unit> {
        val (http, _) = channel(::respond); initialize(http)
        val ticket = writer().capture(timerHabit.id)!!
        assertEquals(1, ticket.challengeContract); assertEquals(initialChallengeRoundHead(timerHabit.uuid), ticket.challengeHead)
        start(timerHabit, ticket)
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).sendAndAccept(access(), id(821)))
        val active = writer().capture(timerHabit.id)!!
        val intent = Intent("original cancel"); active.attach(intent)
        storage.reopen(); val recovered = TimerActionAuthority.read(intent)!!
        writer().write(timerHabit.id, recovered) { db.timeLogDao().deleteTimerAndQueue(db.timeLogDao().getActiveTimeLog()!!,
            TimerCommandEntity(commandId = id(822), sessionUuid = id(820), sequence = 2, commandType = "cancel",
                occurredAt = millis + 1, expectedControlGeneration = 1)) }
        assertEquals(active.challengeHead, roundTimerIntent(db.nextRequestDao().origin(NEXT_TIMER, id(822))!!.intentJson)!!.context.head)
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).sendAndAccept(access(), id(822)))
        assertNull(db.timeLogDao().getActiveTimeLog()); assertTrue(db.timeLogDao().getPendingTimerCommands().isEmpty())
        assertEquals(0, count("completions")); assertEquals(20L, db.nextSyncStateDao().rows().single().cursor)
    }

    @Test fun newOfflineTimerUsesActualCreationSourceThenWaitsForConfigurationAck() = runBlocking<Unit> {
        val (http, _) = channel(::respond); initialize(http)
        val created = creatingHabits().createHabit("Offline timer", "", HabitType.TIMER, 0, "#123456", HabitSchedule.Daily,
            targetValue = 1, appearance = appearance("habit.exercise"), completionPolicy = "recurring", creationAuthority = creator.capture())
        val row = db.habitDao().getHabitById(created)!!
        val source = db.syncOutboxDao().getAll().single()
        val ticket = writer().capture(created)!!
        start(row, ticket)
        assertEquals(2, count("next_challenge_rounds")); assertEquals(0, count("next_challenge_births"))
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { timers(http).send(access(), id(821)) } as NextRequestException).reason)
        assertEquals(0, count("next_transmissions"))
        sender(http).sendAndAcceptOperation(access(), source.operationId)
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).sendAndAccept(access(), id(821)))
        assertEquals(initialChallengeRoundHead(row.uuid), roundTimerIntent(db.nextRequestDao().origin(NEXT_TIMER, id(821))!!.intentJson)!!.context.head)
        assertEquals(60, writer().policy(created, id(820))!!.targetSeconds)
    }

    @Test fun timerLateSourceFaultRollsBackSessionSegmentsAndOriginalQueue() = runBlocking<Unit> {
        val (http, _) = channel(::respond); initialize(http)
        val ticket = writer().capture(timerHabit.id)!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER profile_timer_fault AFTER INSERT ON next_request_origins BEGIN UPDATE next_challenge_state SET metadataHash='bad'; END")
        rejected { start(timerHabit, ticket) }
        assertNull(db.timeLogDao().getActiveTimeLog()); assertEquals(0, count("timer_segments")); assertEquals(0, count("next_request_origins"))
        assertEquals(0, count("timer_command_outbox")); assertEquals(2, count("next_challenge_rounds"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER profile_timer_fault"); start(timerHabit, ticket)
    }

    @Test fun timerStartDisplayedHeadCannotFollowARealNewRoundAndDeclarationsAreStrict() = runBlocking<Unit> {
        val (http, _) = channel(::respond); initialize(http)
        val ticket = writer().capture(timerHabit.id)!!
        val encoded = Intent("start"); ticket.attach(encoded)
        val field = "com.dayforge.timer.authority"
        val bad = JsonObject(Json.parseToJsonElement(encoded.getStringExtra(field)!!).jsonObject + ("challengeContract" to JsonPrimitive("1")))
        rejected { TimerActionAuthority.read(Intent().putExtra(field, bad.toString())) }
        val old = initialChallengeRoundHead(timerHabit.uuid)
        records += ChallengeRoundRecord(ChallengeRoundHead(timerHabit.uuid, id(830), 1), id(4), id(831),
            ChallengeRestartIntent(timerHabit.uuid, id(830), old.roundUuid, 0, 1))
        db.withTransaction { NextChallengeStore(db).acknowledgeInTransaction(access(), metadata()) }
        rejected { start(timerHabit, ticket) }
        assertNull(db.timeLogDao().getActiveTimeLog()); assertEquals(0, count("next_request_origins"))
        val fresh = writer().capture(timerHabit.id)!!
        val freshIntent = Intent("start"); fresh.attach(freshIntent)
        assertNotEquals(encoded.data, freshIntent.data); assertEquals(id(830), fresh.challengeHead!!.roundUuid)
    }

    @Test fun lostProfileStateCannotBecomePlainCreatorOncePromptOrTimerWork() = runBlocking<Unit> {
        val (http, _) = channel(::respond); initialize(http)
        val once = onceRepository(); val repo = onceHabits(once); val taskId = task(repo)
        repo.logCompletion(app, taskId)
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_challenge_state")
        rejected { creator.capture() }; rejected { writer().capture(timerHabit.id) }
        rejected { repo.getOneTimeStatus(taskId) }; rejected { once.prompt(taskId) }
        assertEquals(0, count("timer_command_outbox")); assertEquals(0, count("metric_logs"))
    }

    @Test fun oncePlainDisplayedActionCannotUpgradeEvenWhenItsIndependentStateDidNotChange() = runBlocking<Unit> {
        register()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1")
            habit = habit.copy(habitType = HabitType.CHECK_IN, targetValue = 1, schedule = HabitSchedule.Once(),
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0, appearance = appearance("task.reading"))
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0")
        }
        val once = onceRepository(); val old = once.read(habit.id)
        val (http, _) = channel(::respond); initialize(http)
        rejected { once.change(habit.id, true, authority = old.authority) }
        assertEquals(0, count("completions")); assertEquals(0, count("next_request_origins"))
        once.change(habit.id, true, authority = once.read(habit.id).authority)
        assertNull(roundOperationIntent(originalIntent(db.syncOutboxDao().getAll().single()).intentJson)!!.context.head)
    }

    @Test fun lateSecondObservationSourceFaultRollsBackWholePromptBatchAndRetainsDraft() = runBlocking<Unit> {
        val (http, _) = channel(::respond); initialize(http)
        val second = creatingMetrics().createMetric(MetricEntity(uuid = id(840), name = "Second metric", unit = "kg", iconResId = 0,
            colorHex = "#123456", appearance = appearance("metric.weight")), creationAuthority = creator.capture())
        val once = onceRepository(); val repo = onceHabits(once)
        val taskId = repo.createHabit("Two metric prompt", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
            selectedMetricIds = setOf(metric.id, second), failMode = FailMode.LOOSE, appearance = appearance("task.reading"),
            completionPolicy = "one_and_done", creationAuthority = creator.capture())
        repo.logCompletion(app, taskId)
        val prompt = once.saveDraft(once.prompt(taskId)!!, mapOf(metric.id to ("10" to "first"), second to ("20" to "second")))
        val before = db.syncOutboxDao().getAll()
        val originals = before.associate { it.operationId to originalIntent(it) }
        val last = prompt.snapshot.entries.last().operationId
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER late_prompt_fault AFTER INSERT ON next_request_origins WHEN NEW.requestId='$last' BEGIN UPDATE next_request_origins SET accountId='${id(9)}' WHERE intentJson LIKE '%metric_observation%' AND requestId!=NEW.requestId; END")
        rejected { once.submit(prompt) }
        assertEquals(0, count("metric_logs")); assertEquals(before, db.syncOutboxDao().getAll())
        for ((id, row) in originals) assertEquals(row, db.nextRequestDao().origin(NEXT_OPERATION, id))
        assertEquals("pending", db.completionFollowUpDao().prompt(prompt.eventUuid)!!.state)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER late_prompt_fault")
        val first = prompt.snapshot.entries.first()
        for (fault in listOf(
            "UPDATE metric_logs SET value=11 WHERE uuid='${first.observationUuid}';",
            "UPDATE local_fact_submissions SET payloadJson='{}' WHERE operationId='${first.operationId}';",
            "UPDATE completion_metric_prompts SET state='pending' WHERE eventUuid='${prompt.eventUuid}';"
        )) {
            val table = if (fault.contains("completion_metric_prompts")) "completion_metric_prompts" else "next_request_origins"
            val condition = if (table == "completion_metric_prompts") "AFTER UPDATE" else "AFTER INSERT"
            val guard = if (table == "completion_metric_prompts") "NEW.state='saved'" else "NEW.requestId='$last'"
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER late_prompt_fault $condition ON $table WHEN $guard BEGIN $fault END")
            rejected { once.submit(prompt) }
            assertEquals(0, count("metric_logs")); assertEquals(before, db.syncOutboxDao().getAll())
            for ((id, row) in originals) assertEquals(row, db.nextRequestDao().origin(NEXT_OPERATION, id))
            assertEquals("pending", db.completionFollowUpDao().prompt(prompt.eventUuid)!!.state)
            assertNull(db.completionFollowUpDao().submission(first.operationId))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER late_prompt_fault")
        }
        once.submit(prompt); assertEquals(2, count("metric_logs"))
        assertEquals(setOf(10.0, 20.0), db.metricLogDao().getLogByUuid(first.observationUuid)!!.let {
            prompt.snapshot.entries.map { entry -> db.metricLogDao().getLogByUuid(entry.observationUuid)!!.value }.toSet()
        })
    }
}
