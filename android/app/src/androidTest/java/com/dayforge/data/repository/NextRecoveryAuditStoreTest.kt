package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.EndpointResolver
import com.dayforge.data.api.NetworkMonitor
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.appearance.ConfigBundleOutput
import com.dayforge.data.appearance.ConfigFileFixture
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.model.OneTimeIntent
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.OneTimeProjection
import com.dayforge.domain.model.PendingOneTimeIntent
import com.dayforge.domain.model.ChallengeRoundRecord
import com.dayforge.domain.model.initialChallengeRoundHead
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.coEvery
import io.mockk.mockk

/** Real producers/refusals and SQLite; audits are created by the actual atomic transaction participant. */
@RunWith(AndroidJUnit4::class)
class NextRecoveryAuditStoreTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private var failing = false
    private lateinit var recoveryHttp: NextSyncHttp
    private lateinit var rejectedDelivery: NextCoreDelivery<NextSyncPushResponse>
    private var pullFault: (() -> Unit)? = null
    private var registrationFault: (() -> Unit)? = null
    private val returnedBirths = linkedMapOf<Pair<String, String>, ChallengeBirth>()
    private val timerPolicies = mutableMapOf<String, TimerStartPolicy>()
    private lateinit var protectedOnce: HabitEntity
    private val audit get() = NextRecoveryAuditStore(db)
    private val payloads get() = NextRecoveryPayloadStore(db)
    private val projections get() = NextRecoveryProjectionStore(db)
    private val sql get() = db.openHelper.writableDatabase
    private fun metadata() = ChallengeMetadata(1, listOf(habit.uuid, timerHabit.uuid).map {
        val first = ChallengeRoundRecord(initialChallengeRoundHead(it), null, null, null)
        ChallengeCheckpoint(first.head, listOf(first))
    }, returnedBirths.values.toList())
    private fun canonical(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun respond(input: MaterialSocketServer.Input): MaterialSocketServer.Reply? {
        if (input.path.endsWith("/identity")) return reply(input)
        if (input.path == "/api/v2/devices/register") {
            val fault = registrationFault; registrationFault = null; fault?.invoke()
            val request = json.decodeFromString<DeviceRegisterRequest>(input.body.toString(Charsets.UTF_8))
            return MaterialSocketServer.Reply(json.encodeToString(DeviceResponse(id(4), request.installationId, "android",
                isPrimaryEditor = true, structuralEditEnabled = true, capabilityRevision = 1,
                capabilities = caps.toList(), lastSeenAt = time)).toByteArray())
        }
        if (input.path == "/api/v2/sync/rounds/changes") {
            val fault = pullFault; pullFault = null; fault?.invoke()
            val meta = metadata()
            val cursor = input.target.substringAfter("cursor=").substringBefore('&').toLong()
            return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(emptyList(), cursor,
                false, time, 1, meta.checkpoints, meta.births)).toByteArray())
        }
        if (input.path == "/api/v2/timers/rounds/commands") return timerResponse(input)
        assertEquals("/api/v2/sync/rounds/push", input.path)
        val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
        val operation = request.operations.single()
        if (!failing && operation.entityType == "activity_event") request.contextFor(operation.operationId).head?.let {
            returnedBirths["activity_event" to operation.entityUuid] = ChallengeBirth("activity_event", operation.entityUuid, it)
        }
        val results = if (failing) listOf(NextSyncOperationResult(operation.operationId, operation.entityType,
            operation.entityUuid, "rejected", errorCode = "INVALID_PAYLOAD")) else json.decodeFromString<NextSyncPushResponse>(
            successReply(input, (operation.baseRevision ?: 0) + 1) { body ->
                val once = body["one_time"] as? JsonObject
                when {
                    once != null -> JsonObject(body + ("one_time_state_after" to buildJsonObject {
                        put("version", once.getValue("expected_version").jsonPrimitive.int + 1)
                        put("head_event_uuid", once.getValue("event_uuid"))
                        put("completion_event_uuid", if (once.getValue("action") == JsonPrimitive("complete"))
                            once.getValue("event_uuid") else JsonNull)
                    }))
                    operation.entityUuid == metric.uuid -> JsonObject(body + ("created_at" to JsonPrimitive(time)))
                    else -> body
                }
            }.bytes.toString(Charsets.UTF_8)).results
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(results, 1, meta.checkpoints, meta.births)).toByteArray())
    }
    private suspend fun initialize(withOnce: Boolean = false): Pair<NextSyncHttp, MaterialSocketServer> {
        register(); val (http, server) = channel(::respond)
        recoveryHttp = http
        val merge = mergeStore()
        val meta = metadata()
        val extra = if (withOnce) {
            val plan = habit.copy(id = 0, uuid = id(700), name = "Protected one-time item", habitType = HabitType.CHECK_IN,
                schedule = HabitSchedule.Once(), targetValue = 1, completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
            listOf(canonical("plan_node", plan.uuid, NextStructureMapper.writePlan(plan)))
        } else emptyList()
        merge.bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))) + extra, 20, time,
            if (withOnce) listOf(OneTimeProjection(id(700), OneTimeState(0, null, null))) else emptyList(),
            1, meta.checkpoints, meta.births))
        if (withOnce) protectedOnce = requireNotNull(db.habitDao().getHabitByUuid(id(700)))
        return http to server
    }

    private fun timerResponse(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        val request = json.decodeFromString<RoundTimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8))
        val command = request.commands.single()
        val head = requireNotNull(request.contextFor(command.commandId).head)
        assertEquals(initialChallengeRoundHead(timerHabit.uuid), head)
        command.startPolicy?.let { timerPolicies[command.sessionId] = it }
        val policy = requireNotNull(timerPolicies[command.sessionId])
        val state = when (command.commandType) { "pause" -> "paused"; "stop" -> "completed"; "cancel" -> "cancelled"; else -> "running" }
        val elapsed = when (command.commandType) { "start" -> 0L; "resume" -> 30_000L; else -> command.activeElapsedMs ?: 0L }
        val session = TimerSessionResponse(command.sessionId, timerHabit.uuid, state, id(4), 1, command.sequence,
            command.sequence + 1, time, command.occurredAt,
            endedAt = command.occurredAt.takeIf { state in setOf("completed", "cancelled") }, timezone = "Asia/Shanghai",
            isCountdown = policy.isCountdown, targetSeconds = policy.targetSeconds, maxDurationSeconds = policy.maxDurationSeconds,
            activeElapsedMs = elapsed, completedEventId = command.sessionId.takeIf { state == "completed" })
        returnedBirths["timer_session" to command.sessionId] = ChallengeBirth("timer_session", command.sessionId, head)
        if (state == "completed") returnedBirths["activity_event" to command.sessionId] = ChallengeBirth("activity_event", command.sessionId, head)
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundTimerCommandBatchResponse(listOf(
            TimerCommandResult(command.commandId, command.sessionId, "applied", session = session)), time, 1,
            meta.checkpoints, meta.births)).toByteArray())
    }

    private fun timers() = NextTimerRequestStore(db, tokens, sessions, sender(recoveryHttp))
    private fun onceLocal() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun onceRequests(): NextOneTimeRequestStore {
        val timer = timers()
        val facts = OneTimeAcceptedEventStore(db, tokens, sessions, onceLocal(), timerRequests = timer)
        return NextOneTimeRequestStore(db, tokens, sessions, recoveryHttp, facts, sender(recoveryHttp))
    }
    private suspend fun protectedTimer(complete: Boolean = false): List<TimerCommandEntity> {
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, startTime = millis, endTime = null,
                durationSeconds = 0, date = millis, uuid = id(20), timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
        }
        if (complete) {
            val dao = db.timeLogDao(); val log = requireNotNull(dao.getTimeLogByUuid(id(20)))
            producer().writeRounds(producer().captureRounds()) {
                dao.updatePauseAndQueue(log.id, true, millis + 30_000, 0, 3, millis + 30_000, 30_000, null, null,
                    TimerCommandEntity(commandId = id(22), sessionUuid = id(20), sequence = 2, commandType = "pause",
                        occurredAt = millis + 30_000, expectedControlGeneration = 1, activeElapsedMillis = 30_000))
            }
            producer().writeRounds(producer().captureRounds()) {
                dao.updatePauseAndQueue(log.id, false, null, 10_000, 4, millis + 40_000, 30_000, null, null,
                    TimerCommandEntity(commandId = id(23), sessionUuid = id(20), sequence = 3, commandType = "resume",
                        occurredAt = millis + 40_000, expectedControlGeneration = 1),
                    TimerSegmentEntity(sessionUuid = id(20), sequence = 2, startedAt = millis + 40_000))
            }
            producer().writeRounds(producer().captureRounds()) {
                dao.finishTimerAndQueue(log.id, millis + 70_000, 60, 10_000, 5, 60_000,
                    TimerCommandEntity(commandId = id(24), sessionUuid = id(20), sequence = 4, commandType = "stop",
                        occurredAt = millis + 70_000, expectedControlGeneration = 1, activeElapsedMillis = 60_000), false)
            }
        }
        return db.timeLogDao().getPendingTimerCommands(Int.MAX_VALUE)
    }
    private suspend fun protectedOnce(n: Int): SyncOutboxEntity {
        val state = onceLocal().read(protectedOnce.uuid).queue.optimisticState
        val command = OneTimeLocalCommand(protectedOnce.uuid, PendingOneTimeIntent(id(n), OneTimeIntent(id(n + 1000),
            if (state.completionEventUuid == null) "complete" else "undo", state.version, state.headEventUuid,
            state.completionEventUuid)), millis + n, "Asia/Shanghai")
        onceLocal().appendRounds(producer().captureRounds(), command)
        return requireNotNull(db.syncOutboxDao().getByOperationId(id(n)))
    }
    private suspend fun recoverMetric(http: NextSyncHttp, prefix: String): Saved {
        val a = edit("$prefix A"); edit("$prefix B")
        return terminal(preview(http, refuse(http, a.operationId))).also { failing = false }
    }
    private suspend fun preservation(recoveryId: String = id(9000)) = db.withTransaction {
        NextRecoveryPreservationStore(db).snapshot(recoveryId)
    }
    private suspend fun sourceProofs() = db.withTransaction {
        NextRequestSql.sources(sql, "sync_outbox") to NextRequestSql.sources(sql, "timer_command_outbox")
    }
    private suspend fun acceptedHistory() {
        producer().writeRounds(producer().captureRounds()) { metrics().recordValue(metric.id, 21.125, "original observation", millis) }
        val observation = db.syncOutboxDao().getAll().single()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(recoveryHttp).sendAndAcceptOperation(access(), observation.operationId))
        producer().writeRounds(producer().captureRounds()) {
            val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(210), value = 3,
                date = millis, actualCompletedAt = millis, recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06")
            NextCountDayStore(db).capture(habit, fact)
            db.completionDao().insert(fact)
        }
        val countRow = db.syncOutboxDao().getAll().single()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(recoveryHttp).sendAndAcceptOperation(access(), countRow.operationId))
        assertEquals(1, count("metric_logs")); assertEquals(1, count("count_days")); assertEquals(1, count("completions"))
    }
    private fun mergeStore(): NextSyncMergeStore {
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(recoveryHttp))
        return NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
    }
    private fun business(http: NextSyncHttp): BusinessSyncRepository {
        val monitor = mockk<NetworkMonitor>()
        coEvery { monitor.localNetworks() } returns emptyList()
        return BusinessSyncRepository(mockk(relaxed = true),
            EndpointResolver(monitor, preferences, tokens, json, SelectedNetworkTransport()), db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
    }
    private suspend fun edit(name: String): SyncOutboxEntity {
        val ticket = editor.metric(metric.id)
        editingMetrics().updateMetric(requireNotNull(ticket.value).copy(name = name), ticket.authority)
        return db.syncOutboxDao().getAll().last()
    }
    private suspend fun refuse(http: NextSyncHttp, id: String): String {
        failing = true
        val delivery = requireNotNull(sender(http).sendOperation(access(), id))
        rejectedDelivery = delivery
        sender(http).recordRejection(access(), NEXT_OPERATION, delivery.requestId, delivery.transmissionProof,
            json.encodeToString(delivery.result.results.single()), delivery.challengeMetadata)
        return delivery.requestId
    }
    private suspend fun preview(http: NextSyncHttp, root: String) = NextSyncRuntime(db, tokens, sessions, http, preferences)
        .readRecoveryPreview(NextRecoveryKey(NEXT_OPERATION, root))
    private data class Saved(val ticket: NextRecoveryConfirmation, val manifest: NextRecoveryAuditManifest,
        val drafts: List<NextRecoveryProjectionDraft>) { val id get() = ticket.recoveryId }
    private fun confirmationStore(): NextRecoveryConfirmationStore {
        val core = sender(recoveryHttp)
        val timers = NextTimerRequestStore(db, tokens, sessions, core)
        val local = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        val facts = OneTimeAcceptedEventStore(db, tokens, sessions, local, timerRequests = timers)
        val once = NextOneTimeRequestStore(db, tokens, sessions, recoveryHttp, facts, core)
        return NextRecoveryConfirmationStore(db, NextRecoveryDependencyReader(db, tokens, sessions, core, timers, once,
            NextChallengeRestartRepository(db, tokens, sessions, recoveryHttp)))
    }
    private suspend fun prepare(preview: NextRecoveryDependencyPreview, selected: Set<NextRecoveryKey> = preview.affected.map { it.key }.toSet()) =
        NextSyncRuntime(db, tokens, sessions, recoveryHttp, preferences).prepareRecoveryConfirmation(preview, selected)
    private suspend fun confirm(ticket: NextRecoveryConfirmation) =
        NextSyncRuntime(db, tokens, sessions, recoveryHttp, preferences).confirmRecovery(ticket)
    private suspend fun terminal(preview: NextRecoveryDependencyPreview): Saved {
        val ticket = prepare(preview)
        val proof = confirm(ticket)
        return Saved(ticket, proof.manifest, ticket.drafts())
    }
    private suspend fun saved(): Pair<Saved, MaterialSocketServer> {
        val (http, server) = initialize(); val a = edit("A"); edit("B"); edit("C")
        return terminal(preview(http, refuse(http, a.operationId))) to server
    }
    private class Rollback : RuntimeException()
    private suspend fun rejectsMutation(saved: Saved, change: suspend () -> Unit) {
        var checked = false
        try { db.withTransaction { change(); rejected { audit.read(access(), saved.id) }; checked = true; throw Rollback() } }
        catch (_: Rollback) { }
        assertTrue(checked); assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
    }
    private suspend fun rewriteManifest(saved: Saved, value: NextRecoveryAuditManifest) {
        sql.execSQL("DELETE FROM next_recovery_audit_payloads WHERE recoveryId=? AND payloadKey='manifest'", arrayOf(saved.id))
        val ref = payloads.write(saved.id, "manifest", NextRecoveryAuditCodec.encode(value))
        sql.execSQL("UPDATE next_recovery_audits SET manifestHash=? WHERE recoveryId=?", arrayOf(ref.hash, saved.id))
    }

    @Test fun ordinaryRecoveryPreservesRunningTimerOnceAndAcceptedHistoryAlongsideEarlierAudit() = runBlocking<Unit> {
        val (http, server) = initialize(withOnce = true)
        val earlier = recoverMetric(http, "Earlier")
        acceptedHistory()
        val timer = protectedTimer().single(); val once = protectedOnce(710)
        val a = edit("Later A"); edit("Later B")
        val ticket = prepare(preview(http, refuse(http, a.operationId)))
        val preserved = preservation(ticket.recoveryId); val pending = sourceProofs(); val calls = server.requests.size
        val localTimer = db.timeLogDao().getTimeLogByUuid(timer.sessionUuid)
        val localOnce = onceLocal().read(protectedOnce.uuid)
        val result = confirm(ticket)
        assertEquals(preserved, preservation(ticket.recoveryId))
        assertEquals(mapOf(once.id to NextRequestSql.sourceHash(once)), sourceProofs().first)
        assertEquals(pending.second, sourceProofs().second)
        assertEquals(localTimer, db.timeLogDao().getTimeLogByUuid(timer.sessionUuid))
        assertEquals(localOnce, onceLocal().read(protectedOnce.uuid))
        assertEquals(2, count("next_acceptances")); assertEquals(2, count("completions"))
        assertEquals(earlier.manifest, db.withTransaction { audit.read(access(), earlier.id).manifest })
        val runtime = NextSyncRuntime(db, tokens, sessions, http, preferences)
        assertFalse(runtime.readRecoveryPreview(NextRecoveryKey(NEXT_TIMER, timer.commandId)).canConfirm)
        assertFalse(runtime.readRecoveryPreview(NextRecoveryKey(NEXT_OPERATION, once.operationId)).canConfirm)
        storage.reopen()
        assertEquals(result.manifest, confirm(ticket).manifest)
        assertEquals(preserved, preservation(ticket.recoveryId)); assertEquals(calls, server.requests.size)
    }

    @Test fun settingsRecoveryWorkflowUsesRealFacadeAndAtomicProofWithoutMarkingSyncSuccessful() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("Settings A"); edit("Settings B")
        val root = refuse(http, a.operationId); failing = false
        val manager = com.dayforge.domain.service.SyncManager(mockk(relaxed = true), business(http))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val workflow = com.dayforge.ui.screens.settings.SettingsRecoveryWorkflow(manager, scope)
        try {
            workflow.preview(NextRecoveryKey(NEXT_OPERATION, root))
            val preview = kotlinx.coroutines.withTimeout(60_000) {
                workflow.state.first { it != com.dayforge.ui.screens.settings.RecoveryUiState.Loading }
            } as com.dayforge.ui.screens.settings.RecoveryUiState.Review
            assertTrue(preview.data.graph.canConfirm); assertFalse(preview.approved)
            val change = preview.data.changes.single().fields.single { it.field == "name" }
            assertEquals("Settings B", change.before); assertEquals(metric.name, change.after)
            workflow.approve(true); workflow.confirm()
            val completed = kotlinx.coroutines.withTimeout(60_000) {
                workflow.state.first { it !is com.dayforge.ui.screens.settings.RecoveryUiState.Committing }
            } as com.dayforge.ui.screens.settings.RecoveryUiState.Completed
            assertEquals(2, completed.count)
            assertEquals(com.dayforge.data.model.SyncProgress.Idle, manager.syncProgress.first())
            assertNull(preferences.lastSyncTimestamp.first()); assertEquals(0, count("next_acceptances"))
            assertTrue(db.syncOutboxDao().getAll().isEmpty())
            assertEquals(completed.recoveryId, db.withTransaction { audit.read(access(), completed.recoveryId).recoveryId })
            assertEquals(0, (manager.readProblems() as com.dayforge.data.model.SyncProblems.Next).count)
            val later = edit("Settings later edit")
            assertTrue(manager.sync().isSuccess)
            assertEquals(com.dayforge.data.model.SyncProgress.Success, manager.syncProgress.first())
            assertNotNull(preferences.lastSyncTimestamp.first())
            assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, later.operationId))
            assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, root))
        } finally {
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        }
    }

    @Test fun nonemptyTimerAndOnceDrainsRejectDamagedAuditThenCompleteNormallyWithOriginalHistory() = runBlocking<Unit> {
        val (http, server) = initialize(withOnce = true)
        val timerRows = protectedTimer(complete = true); val onceRows = List(3) { protectedOnce(720 + it) }
        // Freeze the audit while these unrelated queues already exist, then let their actual
        // journal/ACK/birth states advance. The terminal must not freeze the whole account.
        val earlier = recoverMetric(http, "Earlier")
        val before = preservation(); val pending = sourceProofs(); val calls = server.requests.size
        val localTimer = db.timeLogDao().getTimeLogByUuid(id(20)); val segments = db.timeLogDao().getTimerSegments(id(20))
        sql.execSQL("UPDATE next_recovery_audits SET selectedCount=selectedCount+1 WHERE recoveryId=?", arrayOf<Any>(earlier.id))
        rejected { timers().pushPending(access()) }; rejected { onceRequests().pushPending(access()) }
        assertEquals(pending, sourceProofs()); assertEquals(calls, server.requests.size)
        assertEquals(localTimer, db.timeLogDao().getTimeLogByUuid(id(20))); assertEquals(3, count("completions"))
        sql.execSQL("UPDATE next_recovery_audits SET selectedCount=? WHERE recoveryId=?",
            arrayOf<Any>(earlier.manifest.requests.size, earlier.id))
        assertEquals(before, preservation())
        assertEquals(4, timers().pushPending(access())); assertEquals(3, onceRequests().pushPending(access()))
        assertEquals(localTimer, db.timeLogDao().getTimeLogByUuid(id(20))); assertEquals(segments, db.timeLogDao().getTimerSegments(id(20)))
        assertEquals(3, onceLocal().read(protectedOnce.uuid).confirmed.version)
        assertEquals(id(1722), onceLocal().read(protectedOnce.uuid).confirmed.completionEventUuid)
        assertEquals(3, count("completions")); assertEquals(7, count("next_acceptances"))
        assertTrue(sourceProofs().first.isEmpty()); assertTrue(sourceProofs().second.isEmpty())
        val afterCalls = server.requests.size
        storage.reopen()
        timerRows.forEach { assertEquals(NextOperationAcceptance.REPLAYED, timers().sendAndAccept(access(), it.commandId)) }
        onceRows.forEach { assertEquals(NextOneTimeOutcome.Accepted(NextOperationAcceptance.REPLAYED),
            onceRequests().sendAndAccept(access(), it.operationId)) }
        assertEquals(afterCalls, server.requests.size)
        assertEquals(earlier.manifest, db.withTransaction { audit.read(access(), earlier.id).manifest })
        assertEquals(earlier.manifest, confirm(earlier.ticket).manifest)
    }

    @Test fun unrelatedNeverSentStructureCanFormItsOwnReplacementAfterAnotherRecoveryWasCommitted() = runBlocking<Unit> {
        val (http, server) = initialize(withOnce = true)
        val timer = protectedTimer().single(); val once = protectedOnce(725)
        for (name in listOf("Unrelated count A", "Unrelated count B")) {
            val ticket = editor.habit(habit.id)
            editingHabits().updateHabit(requireNotNull(ticket.value).copy(name = name), editAuthority = ticket.authority)
        }
        val rows = db.syncOutboxDao().getEntityIntents("habit", habit.uuid)
        assertEquals(2, rows.size)
        val recovered = recoverMetric(http, "Independent metric")
        assertTrue(recovered.manifest.graph.nodes.any { it.key.requestId == rows.last().operationId })
        val localTimer = db.timeLogDao().getTimeLogByUuid(timer.sessionUuid)
        val localOnce = onceLocal().read(protectedOnce.uuid)
        rows.forEach { assertEquals(NextOperationAcceptance.COMMITTED,
            sender(http).sendAndAcceptOperation(access(), it.operationId)) }
        assertNotNull(db.nextStructuralCausalDao().supersession(rows.last().operationId))
        assertEquals(recovered.manifest, db.withTransaction { audit.read(access(), recovered.id).manifest })
        // Allowing NEW unrelated state must not weaken any immutable evidence actually saved.
        rejectsMutation(recovered) {
            sql.execSQL("UPDATE next_request_origins SET intentJson='{}' WHERE kind=? AND requestId=?",
                arrayOf<Any>(NEXT_OPERATION, rows.last().operationId))
        }
        rejectsMutation(recovered) {
            sql.execSQL("UPDATE next_structural_dependencies SET originHash='bad' WHERE operationId=?",
                arrayOf<Any>(rows.last().operationId))
        }
        assertEquals(localTimer, db.timeLogDao().getTimeLogByUuid(timer.sessionUuid))
        assertEquals(localOnce, onceLocal().read(protectedOnce.uuid))
        assertEquals(listOf(once), db.syncOutboxDao().getAll())
        assertEquals(2, count("next_acceptances")); assertEquals("Unrelated count B", db.habitDao().getHabitById(habit.id)!!.name)
        val calls = server.requests.size
        storage.reopen()
        assertEquals(recovered.manifest, confirm(recovered.ticket).manifest)
        rows.forEach { assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), it.operationId)) }
        assertEquals(calls, server.requests.size)
        var completed = false
        business(http).sync(afterSync = { completed = true })
        assertTrue(completed)
        assertNotNull(preferences.lastSyncTimestamp.first())
        assertTrue(sourceProofs().first.isEmpty()); assertTrue(sourceProofs().second.isEmpty())
        assertEquals(1, onceLocal().read(protectedOnce.uuid).confirmed.version)
        assertEquals(localTimer, db.timeLogDao().getTimeLogByUuid(timer.sessionUuid))
        assertEquals(4, count("next_acceptances"))
        recovered.manifest.requests.forEach { assertNull(db.nextRequestDao().acceptance(it.key.kind, it.key.requestId)) }
        assertEquals(recovered.manifest, db.withTransaction { audit.read(access(), recovered.id).manifest })
    }

    @Test fun lateTimerSegmentAndOnceFactMutationRollBackNewRecoveryWithoutDamagingEarlierAudit() = runBlocking<Unit> {
        val (http, server) = initialize(withOnce = true); val earlier = recoverMetric(http, "Earlier")
        protectedTimer(); protectedOnce(730)
        val a = edit("Later A"); edit("Later B")
        val ticket = prepare(preview(http, refuse(http, a.operationId)))
        val before = preservation(ticket.recoveryId); val pending = sourceProofs(); val calls = server.requests.size
        for ((mutation, reason) in listOf(
            "UPDATE timer_segments SET startedAt=startedAt+1" to "SYNC_RECOVERY_PROTECTED_STATE_CHANGED",
            // The real completion trigger first adds outbox work, so the exact queue guard
            // rejects this mutation before the wider preserved-state comparison.
            "UPDATE completions SET value=value+1" to "SYNC_RECOVERY_QUEUE_CHANGED",
            "UPDATE completions SET createdAt=createdAt+1" to "SYNC_RECOVERY_PROTECTED_STATE_CHANGED")) {
            sql.execSQL("CREATE TRIGGER recovery_protected_mixed AFTER INSERT ON next_abandoned_requests BEGIN $mutation; END")
            assertEquals(reason, rejected { confirm(ticket) }.message)
            storage.reopen()
            assertEquals(before, preservation(ticket.recoveryId)); assertEquals(pending, sourceProofs())
            assertEquals(earlier.manifest, db.withTransaction { audit.read(access(), earlier.id).manifest })
            assertEquals(1, count("next_recovery_audits")); assertEquals(2, count("next_abandoned_requests"))
            assertEquals(0, count("next_acceptances")); assertEquals(calls, server.requests.size)
            sql.execSQL("DROP TRIGGER recovery_protected_mixed")
        }
        assertEquals(ticket.recoveryId, confirm(ticket).recoveryId)
        assertEquals(before, preservation(ticket.recoveryId)); assertEquals(calls, server.requests.size)
    }

    @Test fun closedDatabaseBackupRestoresFullAuditPendingTimerOnceAndAcceptedHistoryWithoutRebinding() = runBlocking<Unit> {
        val (http, server) = initialize(withOnce = true); val earlier = recoverMetric(http, "Earlier")
        acceptedHistory(); protectedTimer(); protectedOnce(740)
        val before = preservation(); val pending = sourceProofs(); val credentials = dataStore.data.first()
        val captured = access(); val calls = server.requests.size
        val image = storage.snapshotClosedDatabase()
        sql.execSQL("UPDATE next_recovery_audits SET selectedCount=99 WHERE recoveryId=?", arrayOf<Any>(earlier.id))
        rejected { db.withTransaction { audit.read(captured, earlier.id) } }
        storage.restoreClosedDatabase(image)
        assertEquals(credentials, dataStore.data.first()); assertEquals(captured, access())
        assertEquals(before, preservation()); assertEquals(pending, sourceProofs())
        assertEquals(earlier.manifest, db.withTransaction { audit.read(captured, earlier.id).manifest })
        assertEquals(earlier.manifest, confirm(earlier.ticket).manifest)
        assertEquals(1, count("timelogs")); assertEquals(1, count("timer_segments")); assertEquals(2, count("completions"))
        assertEquals(1, count("metric_logs")); assertEquals(1, count("count_days")); assertEquals(2, count("next_acceptances"))
        assertEquals(calls, server.requests.size)
    }

    @Test fun restoredDatabaseWithMissingSourceChunkOrWrongScopeCannotProduceAnyRecoveryProof() = runBlocking<Unit> {
        val (http, server) = initialize(withOnce = true); val earlier = recoverMetric(http, "Earlier")
        protectedTimer(); protectedOnce(750)
        val intact = storage.snapshotClosedDatabase(); val pending = sourceProofs(); val calls = server.requests.size
        val source = earlier.manifest.requests.first().source
        sql.execSQL("DELETE FROM next_recovery_audit_payloads WHERE recoveryId=? AND payloadKey=? AND part=0",
            arrayOf<Any>(earlier.id, source.key))
        val damaged = storage.snapshotClosedDatabase()
        storage.restoreClosedDatabase(intact)
        assertEquals(earlier.manifest, db.withTransaction { audit.read(access(), earlier.id).manifest })
        val captured = access()
        val wrong = listOf(captured.copy(deviceId = id(999)),
            captured.copy(session = captured.session.copy(serverInstanceId = id(999))),
            captured.copy(session = captured.session.copy(syncEpoch = id(999))),
            captured.copy(session = captured.session.copy(authentication = captured.session.authentication.copy(userId = id(999)))))
        for (context in wrong) rejected { db.withTransaction { audit.read(context, earlier.id) } }
        assertEquals(pending, sourceProofs()); assertEquals(0, count("next_acceptances"))
        storage.restoreClosedDatabase(damaged)
        rejected { db.withTransaction { audit.read(access(), earlier.id) } }
        rejected { confirm(earlier.ticket) }
        rejected { timers().pushPending(access()) }; rejected { onceRequests().pushPending(access()) }
        assertEquals(pending, sourceProofs()); assertEquals(0, count("next_acceptances")); assertEquals(calls, server.requests.size)
        storage.restoreClosedDatabase(intact)
        assertEquals(earlier.manifest, confirm(earlier.ticket).manifest)
        assertEquals(pending, sourceProofs()); assertEquals(calls, server.requests.size)
    }

    @Test fun completeGroupColdReadKeepsOriginalSourcesWithoutAckOrCursorAdvance() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A"); val b = edit("B"); val c = edit("C")
        val root = refuse(http, a.operationId); val originalRows = db.syncOutboxDao().getAll(); val cursor = db.nextSyncStateDao().rows()
        val saved = terminal(preview(http, root)); val calls = server.requests.size
        storage.reopen()
        for (row in originalRows) db.withTransaction {
            val proof = requireNotNull(audit.source(access(), NextRecoveryKey(NEXT_OPERATION, row.operationId)))
            assertEquals(row, proof.second); assertEquals(saved.manifest, proof.first.manifest)
        }
        assertEquals(setOf(a.operationId, b.operationId, c.operationId), saved.manifest.requests.map { it.key.requestId }.toSet())
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(cursor, db.nextSyncStateDao().rows())
        assertEquals(0, count("next_acceptances")); assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertEquals(calls, server.requests.size)
    }
    @Test fun readingOldAuditAfterNewLocalEditDoesNotReapplyOldProjection() = runBlocking<Unit> {
        val (saved, server) = saved(); val calls = server.requests.size
        // Deliberately isolate a local display change: this reader/replay regression must prove
        // that inspecting a terminal does not apply its old projection again.
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().update(requireNotNull(db.metricDao().getMetricById(metric.id)).copy(name = "Later local display"))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        storage.reopen()
        assertEquals(saved.manifest, confirm(saved.ticket).manifest)
        assertEquals(1, count("next_recovery_audits")); assertEquals(3, count("next_abandoned_requests"))
        assertEquals("Later local display", db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(calls, server.requests.size); assertEquals(0, count("next_acceptances"))
    }
    @Test fun missingMemberExtraPayloadAndWrongCountCannotValidatePartialGroups() = runBlocking<Unit> {
        val (saved, _) = saved()
        rejectsMutation(saved) { sql.execSQL("DELETE FROM next_abandoned_requests WHERE requestId=?", arrayOf(saved.manifest.requests.last().key.requestId)) }
        rejectsMutation(saved) { payloads.write(saved.id, "source:sync_operation:${id(999)}", byteArrayOf(1)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_recovery_audits SET selectedCount=2 WHERE recoveryId=?", arrayOf(saved.id)) }
    }
    @Test fun rawTypesAndChangedOriginSourceAndRejectionCannotBecomeTerminalProof() = runBlocking<Unit> {
        val (saved, _) = saved(); val root = saved.manifest.root.requestId
        rejectsMutation(saved) { sql.execSQL("UPDATE next_recovery_audits SET selectedCount=1.5 WHERE recoveryId=?", arrayOf(saved.id)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_request_origins SET intentJson='{}' WHERE requestId=?", arrayOf(root)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_rejections SET resultHash=? WHERE requestId=?", arrayOf("d".repeat(64), root)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_abandoned_requests SET sourceSnapshotHash=? WHERE requestId=?", arrayOf("e".repeat(64), root)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_recovery_audit_payloads SET bytes=X'00' WHERE recoveryId=? AND payloadKey=? AND part=0",
            arrayOf(saved.id, saved.manifest.requests.first().source.key)) }
    }
    @Test fun remainingSourceWrongScopeOrDisabledOutboxCannotMasqueradeAsCommittedRecovery() = runBlocking<Unit> {
        val (saved, _) = saved()
        rejectsMutation(saved) {
            val request = saved.manifest.requests.first()
            db.syncOutboxDao().insert(NextRecoverySourceSnapshot.decode(payloads.read(saved.id, request.source), request.sourceHash))
        }
        rejectsMutation(saved) { sql.execSQL("UPDATE next_recovery_audits SET deviceId=? WHERE recoveryId=?", arrayOf(id(888), saved.id)) }
        rejectsMutation(saved) { sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1") }
    }
    @Test fun rehashedManifestCannotOmitKnownDependencyOrChangeSelectedSet() = runBlocking<Unit> {
        val (saved, _) = saved()
        rejectsMutation(saved) {
            val graph = saved.manifest.graph.copy(dependencies = emptyList())
            val hash = graph.preview(saved.manifest.root).graphHash
            rewriteManifest(saved, saved.manifest.copy(graph = graph))
            sql.execSQL("UPDATE next_recovery_audits SET graphHash=? WHERE recoveryId=?", arrayOf(hash, saved.id))
        }
        val omitted = saved.manifest.requests.first { it.key != saved.manifest.root }.key
        rejectsMutation(saved) { rewriteManifest(saved, saved.manifest.copy(requests = saved.manifest.requests.filter { it.key != omitted })) }
    }
    @Test fun rehashedAfterSnapshotMustStillMatchFullFrozenBaselineAndPreserveLocalIdentity() = runBlocking<Unit> {
        val (saved, _) = saved(); val descriptor = saved.manifest.projections.single(); val draft = saved.drafts.single()
        for (local in listOf(JsonObject(draft.after.local + ("name" to JsonPrimitive("Forged restore"))),
            JsonObject(draft.after.local + ("iconResId" to JsonPrimitive(555))))) rejectsMutation(saved) {
            val after = draft.after.copy(local = local, body = if (local["name"] != draft.after.local["name"])
                JsonObject(requireNotNull(draft.after.body) + ("name" to local.getValue("name"))) else draft.after.body)
            sql.execSQL("DELETE FROM next_recovery_audit_payloads WHERE recoveryId=? AND payloadKey=?", arrayOf(saved.id, descriptor.after.key))
            val reference = payloads.write(saved.id, descriptor.after.key, NextRecoveryAuditCodec.encode(after))
            rewriteManifest(saved, saved.manifest.copy(projections = listOf(descriptor.copy(after = reference))))
        }
    }
    @Test fun originalAliasAndAcceptedAncestorAreAuditedButNeverSelectedForRetirement() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val b = edit("B"); edit("C")
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), a.operationId))
        val root = refuse(http, b.operationId); assertNotEquals(b.operationId, root)
        val saved = terminal(preview(http, root))
        assertTrue(saved.manifest.requests.none { it.key.requestId in setOf(a.operationId, b.operationId) })
        assertEquals("A", db.metricDao().getMetricById(metric.id)!!.name); assertEquals(1, count("next_acceptances"))
        rejectsMutation(saved) { sql.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?", arrayOf("d".repeat(64), a.operationId)) }
        rejectsMutation(saved) {
            val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId))
            val json = Json.parseToJsonElement(receipt.resultJson).jsonObject
            val changed = JsonObject(json + ("operation_id" to JsonPrimitive(id(999)))).toString()
            sql.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
                arrayOf(changed, nextRequestHash(changed.toByteArray()), a.operationId))
            val hash = requireNotNull(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, a.operationId)))
            rewriteManifest(saved, saved.manifest.copy(rows = saved.manifest.rows.map {
                if (it.table == "next_acceptances" && it.key.requestId == a.operationId) it.copy(hash = hash) else it
            }))
        }
        rejectsMutation(saved) {
            sql.execSQL("UPDATE next_structural_supersessions SET sourceSnapshotHash=? WHERE originalId=?", arrayOf("c".repeat(64), b.operationId))
            val hash = requireNotNull(NextRequestSql.rowHash(sql, "next_structural_supersessions", "originalId=?", arrayOf(b.operationId)))
            rewriteManifest(saved, saved.manifest.copy(rows = saved.manifest.rows.map {
                if (it.table == "next_structural_supersessions" && it.key.requestId == b.operationId) it.copy(hash = hash) else it
            }))
        }
        storage.reopen(); assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        register(permissions = setOf("sync.read"), revision = 2)
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
    }
    @Test fun ordinaryPlannerIsReadOnlyAndPreservesFullLocalPreferenceIdentity() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("Changed metric"); val root = refuse(http, a.operationId)
        val queue = db.syncOutboxDao().getAll(); val calls = server.requests.size
        val before = requireNotNull(db.metricDao().getMetricById(metric.id))
        val frozen = preview(http, root)
        val draft = db.withTransaction { projections.plan(frozen) }.single()
        assertEquals(before.id, draft.before.localId); assertEquals(before.id, draft.after.localId)
        assertEquals(before.colorHex, draft.after.local.getValue("colorHex").jsonPrimitive.content)
        assertEquals(before.iconResId, draft.after.local.getValue("iconResId").jsonPrimitive.int)
        assertEquals(NextStructureMapper.writeMetric(metric), draft.after.body)
        assertEquals(queue, db.syncOutboxDao().getAll()); assertEquals(before, db.metricDao().getMetricById(metric.id)); assertEquals(calls, server.requests.size)
    }

    @Test fun rejectedRetainedLinkPlansSameLocalIdAndStillBlocksItsMetricDeletion() = runBlocking<Unit> {
        val (http, server) = initialize()
        creatingMetrics().linkHabits(metric, setOf(habit.id))
        val creation = db.syncOutboxDao().getAll().single()
        sender(http).sendAndAcceptOperation(access(), creation.operationId)
        val link = db.habitMetricLinkDao().getAllLinksForMetric(metric.id).single()
        editingMetrics().unlinkHabit(link.id, link.uuid)
        val deletion = db.syncOutboxDao().getAll().single()
        val root = refuse(http, deletion.operationId)
        val before = db.habitMetricLinkDao().getLinkByUuid(link.uuid)
        val frozen = preview(http, root)
        val draft = db.withTransaction { projections.plan(frozen) }.single()
        assertEquals(link.id, draft.before.localId); assertEquals(link.id, draft.after.localId)
        assertEquals(link.habitUuid, draft.after.local.getValue("habitUuid").jsonPrimitive.content)
        assertEquals(link.metricUuid, draft.after.local.getValue("metricUuid").jsonPrimitive.content)
        assertEquals(before, db.habitMetricLinkDao().getLinkByUuid(link.uuid))
        // Leave the rejected link intact; do not bypass that real dependency by deleting its metric.
        val metricTicket = editor.metric(metric.id)
        editingMetrics().deleteMetric(requireNotNull(metricTicket.value), metricTicket.authority)
        val metricDelete = db.syncOutboxDao().getAll().single { it.recordType == "metric" }
        val pushes = server.requests.count { it.path.endsWith("/push") }
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendOperation(access(), metricDelete.operationId) } as NextRequestException).reason)
        assertEquals(pushes, server.requests.count { it.path.endsWith("/push") })
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, metricDelete.operationId))
        assertNotNull(db.metricDao().getMetricById(metric.id))
        assertNull(db.metricDao().getVisibleMetricById(metric.id))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, deletion.operationId))
    }

    @Test fun rejectedRetainedMetricDeleteKeepsFullBaselineAndLocalIdentityForColdPlanning() = runBlocking<Unit> {
        val (http, server) = initialize(); val ticket = editor.metric(metric.id)
        editingMetrics().deleteMetric(requireNotNull(ticket.value), ticket.authority)
        val deletion = db.syncOutboxDao().getAll().single()
        val root = refuse(http, deletion.operationId); storage.reopen()
        val original = requireNotNull(db.metricDao().getMetricById(metric.id)); val calls = server.requests.size
        val frozen = preview(http, root)
        val drafts = db.withTransaction { NextRecoveryProjectionStore(db).plan(frozen) }
        val draft = drafts.single()
        assertEquals(metric.id, draft.before.localId); assertEquals(metric.id, draft.after.localId)
        assertEquals(NextStructureMapper.writeMetric(metric), draft.after.body)
        assertEquals(original.iconResId, draft.after.local.getValue("iconResId").jsonPrimitive.int)
        assertEquals(original, db.metricDao().getMetricById(metric.id)); assertEquals(calls, server.requests.size)
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, deletion.operationId))
        assertNull(db.metricDao().getVisibleMetricById(metric.id))
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
        creatingMetrics().createMetric(metric.copy(id = 0, uuid = id(881)), creationAuthority = creator.capture())
        val later = preview(http, root)
        assertEquals("RECOVERY_PROJECTION_NAME_CONFLICT",
            rejected { db.withTransaction { NextRecoveryProjectionStore(db).plan(later) } }.message)
        assertEquals(original, db.metricDao().getMetricById(metric.id))
    }
    @Test fun codecRejectsOmittedFieldsDuplicateKeysWrongTypesUnknownFieldsAndMalformedUtf8() = runBlocking<Unit> {
        val (saved, _) = saved()
        val checkpoint = db.withTransaction { audit.checkpoint(access()) }
        val text = NextRecoveryAuditCodec.encodeText(checkpoint)
        val invalid = listOf(text.replace("\"syncHash\":\"${checkpoint.syncHash}\",", ""),
            text.replace("\"accountId\":", "\"accountId\":\"${checkpoint.accountId}\",\"accountId\":"),
            text.replace("\"accountId\":\"${checkpoint.accountId}\"", "\"accountId\":1"), text.dropLast(1) + ",\"extra\":0}")
        for (value in invalid) rejected { NextRecoveryAuditCodec.decode<NextRecoveryAuditCheckpoint>(value.toByteArray(), 2_097_152) }
        rejected { NextRecoveryAuditCodec.decode<NextRecoveryAuditCheckpoint>(byteArrayOf(0xC3.toByte(), 0x28), 2_097_152) }
        assertEquals(saved.manifest, NextRecoveryAuditCodec.decode<NextRecoveryAuditManifest>(NextRecoveryAuditCodec.encode(saved.manifest), 67_108_864))
    }
    @Test fun threeStructureKindsCaptureAllRawCellsAndValidateAgainstRealDomainBaselines() = runBlocking<Unit> {
        initialize()
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val row = HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid, metricId = metric.id, metricUuid = metric.uuid,
                uuid = id(800), coefficient = 1.25, createdAt = millis, updatedAt = millis)
            db.habitMetricLinkDao().insertOrIgnore(row)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            val linkBody = buildJsonObject { put("activity_uuid", habit.uuid); put("metric_uuid", metric.uuid); put("coefficient", 1.25)
                put("show_in_activity_detail", true); put("prompt_on_complete", false); put("is_active", true) }
            for ((type, uuid, body) in listOf(Triple("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
                Triple("metric", metric.uuid, NextStructureMapper.writeMetric(metric)), Triple("activity_metric_link", row.uuid, linkBody))) {
                val snapshot = projections.capture(type, uuid)
                assertEquals(body, snapshot.body); assertNotNull(snapshot.localId)
                projections.validatePair(snapshot, snapshot)
                assertEquals(snapshot, NextRecoveryAuditCodec.decode<NextRecoveryProjectionSnapshot>(NextRecoveryAuditCodec.encode(snapshot), NEXT_RECOVERY_SOURCE_BYTES))
                rejected { projections.validatePair(snapshot, snapshot.copy(local = JsonObject(snapshot.local + ("id" to JsonPrimitive(1.5))))) }
                rejected { projections.validatePair(snapshot, snapshot.copy(localId = snapshot.localId!! + 1)) }
            }
        }
    }
    @Test fun neverAcceptedCreationPlansOnlyPureLocalRemovalWithoutInventingRemoteTombstone() = runBlocking<Unit> {
        val (http, server) = initialize()
        val fresh = metric.copy(id = 0, uuid = id(700), name = "Never accepted")
        val localId = creatingMetrics().createMetric(fresh, creationAuthority = creator.capture())
        val source = db.syncOutboxDao().getAll().single { it.entityUuid == fresh.uuid }
        val root = refuse(http, source.operationId); val frozen = preview(http, root); val calls = server.requests.size
        val draft = db.withTransaction { projections.plan(frozen) }.single()
        assertEquals(localId, draft.before.localId); assertNull(draft.after.localId); assertNull(draft.baseline.rowHash)
        assertNull(db.syncOutboxDao().getState("metric", fresh.uuid)); assertNotNull(db.metricDao().getMetricById(localId))
        assertEquals(listOf(source.operationId), db.syncOutboxDao().getAll().map { it.operationId })
        assertEquals(0, count("next_acceptances")); assertEquals(calls, server.requests.size)
        val saved = terminal(frozen)
        assertNull(db.metricDao().getMetricById(localId)); assertNull(db.syncOutboxDao().getState("metric", fresh.uuid))
        assertEquals(0, count("next_acceptances")); assertEquals(calls, server.requests.size)
        storage.reopen()
        assertEquals(source, db.withTransaction { audit.source(access(), saved.manifest.root) }!!.second)
        assertEquals(saved.manifest, confirm(saved.ticket).manifest)
    }
    @Test fun newUnselectedEditStopsOldPlanRatherThanDroppingLaterWork() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val root = refuse(http, a.operationId)
        val old = preview(http, root); val b = edit("Later edit")
        val rows = db.syncOutboxDao().getAll()
        rejected { db.withTransaction { projections.plan(old) } }
        assertEquals(rows, db.syncOutboxDao().getAll()); assertNotNull(db.syncOutboxDao().getById(b.id))
        assertEquals(0, count("next_recovery_audits"))
    }
    @Test fun coldAuditDoesNotGrantWritePermissionButLegitimatePermissionRefreshKeepsItsEvidence() = runBlocking<Unit> {
        val (saved, _) = saved()
        register(permissions = setOf("sync.read"), revision = 2)
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        assertEquals(setOf("sync.read"), access().capabilities)
        register(device = id(900), permissions = setOf("sync.read"), revision = 3)
        rejected { db.withTransaction { audit.read(access(), saved.id) } }
    }
    @Test fun cancellationAndMissingAuditCannotProducePartialTerminalResults() = runBlocking<Unit> {
        val (saved, _) = saved(); var entered = false
        val job = launch { db.withTransaction { entered = true; currentCoroutineContext().cancel(); audit.read(access(), saved.id); fail("Cancelled read returned") } }
        job.join(); assertTrue(entered); assertTrue(job.isCancelled)
        rejected { db.withTransaction { audit.read(access(), id(999)) } }
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
    }

    @Test fun selectionMustExplicitlyIncludeEveryNeverSentSuccessor() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); edit("B")
        val root = refuse(http, a.operationId); val frozen = preview(http, root)
        val rows = db.syncOutboxDao().getAll()
        rejected { prepare(frozen, setOf(frozen.root)) }
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(0, count("next_recovery_audits"))
    }

    @Test fun actualUnknownHttpResultCannotBeConfirmedAsNeverSentOrRejected() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A")
        assertNotNull(sender(http).sendOperation(access(), a.operationId))
        val rows = db.syncOutboxDao().getAll(); val calls = server.requests.size
        val unknown = preview(http, a.operationId)
        assertEquals(NextRecoveryRequestState.UNKNOWN_RESULT, unknown.affected.single().state)
        rejected { prepare(unknown) }
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(0, count("next_abandoned_requests"))
        assertEquals(0, count("next_acceptances")); assertEquals(calls, server.requests.size)
    }

    @Test fun changedGraphProjectionAndDevicePermissionsRejectTheWholeFrozenConfirmation() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val root = refuse(http, a.operationId)
        val ticket = prepare(preview(http, root)); edit("Later")
        val rows = db.syncOutboxDao().getAll(); val metricBefore = db.metricDao().getMetricById(metric.id)
        rejected { confirm(ticket) }
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(metricBefore, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("next_recovery_audits"))
        val newTicket = prepare(preview(http, root))
        register(permissions = setOf("sync.read"), revision = 2)
        assertEquals("SYNC_RECOVERY_WRITE_PERMISSION_REQUIRED", rejected { confirm(newTicket) }.message)
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(0, count("next_recovery_audits"))
        register(device = id(900), revision = 3)
        assertEquals("SYNC_CHALLENGE_CONTEXT_CHANGED", rejected { confirm(newTicket) }.message)
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(0, count("next_acceptances"))
    }

    @Test fun changedPrivateProjectionWithSameGraphHashRequiresNewPreviewInsteadOfOverwriting() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); val root = refuse(http, a.operationId)
        val ticket = prepare(preview(http, root)); val rows = db.syncOutboxDao().getAll()
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE metrics SET iconResId=777 WHERE id=?", arrayOf(metric.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals("SYNC_RECOVERY_PROJECTION_CHANGED", rejected { confirm(ticket) }.message)
        assertEquals(777, db.metricDao().getMetricById(metric.id)!!.iconResId)
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(0, count("next_recovery_audits"))
    }

    @Test fun lateProtectedStateMutationRollsBackAuditQueueAndProjectionThenSameTicketCanRetry() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A"); edit("B")
        val ticket = prepare(preview(http, refuse(http, a.operationId)))
        val rows = db.syncOutboxDao().getAll(); val before = db.metricDao().getMetricById(metric.id)
        val cursor = db.nextSyncStateDao().rows(); val calls = server.requests.size
        sql.execSQL("CREATE TRIGGER recovery_cursor_fault AFTER INSERT ON next_abandoned_requests BEGIN UPDATE next_sync_state SET cursor=cursor+1; END")
        assertEquals("SYNC_RECOVERY_PROTECTED_STATE_CHANGED", rejected { confirm(ticket) }.message)
        sql.execSQL("DROP TRIGGER recovery_cursor_fault")
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(before, db.metricDao().getMetricById(metric.id))
        assertEquals(cursor, db.nextSyncStateDao().rows()); assertEquals(0, count("next_recovery_audits"))
        assertEquals(0, count("next_abandoned_requests")); assertEquals(0, count("next_recovery_audit_payloads"))
        assertEquals(0, count("next_acceptances")); assertEquals(calls, server.requests.size)
        storage.reopen(); assertEquals(ticket.recoveryId, confirm(ticket).recoveryId)
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(cursor, db.nextSyncStateDao().rows())
    }

    @Test fun actualFinalCommitFailureAndCancellationPreserveExactSourcesForColdSameTicketRetry() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("A"); edit("B")
        val ticket = prepare(preview(http, refuse(http, a.operationId)))
        val rows = db.syncOutboxDao().getAll(); val before = db.metricDao().getMetricById(metric.id)
        sql.execSQL("CREATE TABLE recovery_commit_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE recovery_commit_guard(id INTEGER PRIMARY KEY,parent INTEGER,FOREIGN KEY(parent) REFERENCES recovery_commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        var applied = false
        val error = rejected { sessions.exclusive { db.withTransaction {
            sql.execSQL("INSERT INTO recovery_commit_guard VALUES(1,99)")
            val result = confirmationStore().confirm(access(), ticket)
            assertEquals(ticket.recoveryId, result.recoveryId); applied = true
        } } }
        assertTrue(applied); assertTrue(error is android.database.sqlite.SQLiteConstraintException)
        // A deferred COMMIT fault can leave the framework writer's native transaction open.
        // Close that actual writer before persistence assertions or another transaction; a WAL
        // reader on the old handle is not proof of rollback. This is the same cold-fault contract
        // used by the real config replacement and deletion ACK suites.
        storage.reopen()
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(before, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("next_recovery_audits")); assertEquals(0, count("next_abandoned_requests"))
        var entered = false
        val job = launch { sessions.exclusive { db.withTransaction {
            confirmationStore().confirm(access(), ticket); entered = true
            currentCoroutineContext().cancel(); currentCoroutineContext().ensureActive()
        } } }
        job.join(); assertTrue(entered); assertTrue(job.isCancelled)
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(before, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("next_recovery_audits")); assertEquals(0, count("next_recovery_audit_payloads"))
        storage.reopen(); assertEquals(ticket.recoveryId, confirm(ticket).recoveryId)
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("next_acceptances"))
    }

    @Test fun realRejectedRetainedLinkAndMetricDeletionRestoreOriginalVisibleIdentitiesTogether() = runBlocking<Unit> {
        val (http, server) = initialize()
        creatingMetrics().linkHabits(metric, setOf(habit.id))
        sender(http).sendAndAcceptOperation(access(), db.syncOutboxDao().getAll().single().operationId)
        val link = db.habitMetricLinkDao().getAllLinksForMetric(metric.id).single()
        editingMetrics().unlinkHabit(link.id, link.uuid)
        val root = refuse(http, db.syncOutboxDao().getAll().single().operationId)
        val metricTicket = editor.metric(metric.id)
        editingMetrics().deleteMetric(requireNotNull(metricTicket.value), metricTicket.authority)
        val frozen = preview(http, root); assertEquals(2, frozen.affected.size)
        val rows = db.syncOutboxDao().getAll(); val cursor = db.nextSyncStateDao().rows(); val calls = server.requests.size
        val saved = terminal(frozen)
        assertEquals(metric, db.metricDao().getVisibleMetricById(metric.id))
        assertEquals(link, db.habitMetricLinkDao().getLinkByUuid(link.uuid))
        assertEquals(listOf(link), db.habitMetricLinkDao().getVisibleLinksForHabit(habit.id))
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertEquals(1, count("next_acceptances"))
        assertEquals(cursor, db.nextSyncStateDao().rows()); assertEquals(calls, server.requests.size)
        storage.reopen(); assertEquals(saved.manifest, confirm(saved.ticket).manifest)
        for (row in rows) assertEquals(row, db.withTransaction { audit.source(access(), NextRecoveryKey(NEXT_OPERATION, row.operationId)) }!!.second)
    }

    @Test fun rejectedPlanEditWithNeverSentDeleteRestoresOriginalLocalIdAndUntouchedRoundPolicy() = runBlocking<Unit> {
        val (http, server) = initialize()
        val editTicket = editor.habit(habit.id)
        editingHabits().updateHabit(requireNotNull(editTicket.value).copy(name = "Edited count"), editAuthority = editTicket.authority)
        val edit = db.syncOutboxDao().getAll().single()
        val root = refuse(http, edit.operationId)
        val deleteTicket = editor.habit(habit.id)
        editingHabits().deleteHabit(requireNotNull(deleteTicket.value), authority = deleteTicket.authority)
        assertNull(db.habitDao().getVisibleHabitById(habit.id))
        val rows = db.syncOutboxDao().getAll()
        val rounds = Triple(db.nextChallengeDao().rounds(), db.nextChallengeDao().births(), db.nextChallengeDao().states())
        val cursor = db.nextSyncStateDao().rows(); val calls = server.requests.size
        val frozen = preview(http, root); assertEquals(2, frozen.affected.size)
        val saved = terminal(frozen)
        assertEquals(habit, db.habitDao().getVisibleHabitById(habit.id))
        assertEquals(timerHabit, db.habitDao().getHabitById(timerHabit.id))
        assertEquals(rounds, Triple(db.nextChallengeDao().rounds(), db.nextChallengeDao().births(), db.nextChallengeDao().states()))
        assertEquals(cursor, db.nextSyncStateDao().rows())
        assertEquals(0, count("next_acceptances")); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertEquals(calls, server.requests.size)
        storage.reopen(); assertEquals(saved.manifest, confirm(saved.ticket).manifest)
        for (row in rows) assertEquals(row, db.withTransaction { audit.source(access(), NextRecoveryKey(NEXT_OPERATION, row.operationId)) }!!.second)
    }

    @Test fun exactProjectionApplicationRestoresAValidNamePermutationWithoutChangingUnselectedRows() = runBlocking<Unit> {
        initialize()
        db.withTransaction {
            val originals = listOf(habit, timerHabit).map { projections.capture("plan_node", it.uuid) }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habits SET name='Temporary test name' WHERE id=?", arrayOf(habit.id))
            sql.execSQL("UPDATE habits SET name=? WHERE id=?", arrayOf<Any>(habit.name, timerHabit.id))
            sql.execSQL("UPDATE habits SET name=? WHERE id=?", arrayOf<Any>(timerHabit.name, habit.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            val queue = db.syncOutboxDao().getAll()
            val drafts = originals.map { after ->
                val state = requireNotNull(db.syncOutboxDao().getState("plan_node", after.entityUuid))
                val baseline = NextRecoveryProjectionBaseline("plan_node", after.entityUuid,
                    NextRequestSql.rowHash(sql, "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf("plan_node", after.entityUuid)),
                    state.revision, state.deleted, state.payloadJson, state.payloadHash, state.updatedAt)
                NextRecoveryProjectionDraft(projections.capture("plan_node", after.entityUuid), after, baseline)
            }
            projections.apply(drafts)
            assertEquals(habit, db.habitDao().getHabitById(habit.id)); assertEquals(timerHabit, db.habitDao().getHabitById(timerHabit.id))
            assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(queue, db.syncOutboxDao().getAll())
            NextRequestSql.requireOutboxEnabled(sql)
        }
    }

    @Test fun retiredSourcesStopDirectSendLateRejectionAndAcceptedFrontierWithoutAnyFakeAck() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A"); val b = edit("B")
        val root = refuse(http, a.operationId); val delivery = rejectedDelivery
        val saved = terminal(preview(http, root)); val cursor = db.nextSyncStateDao().rows()
        val calls = server.requests.count { it.path.endsWith("/push") }
        for (row in listOf(a, b)) {
            val error = rejected { sender(http).sendOperation(access(), row.operationId) }
            assertEquals(NextRequestException.Reason.LOCALLY_ABANDONED, (error as NextRequestException).reason)
            val frontier = rejected { db.withTransaction { NextStructuralCausalStore(db).acceptedOperationProof(row.operationId, access()) } }
            assertEquals(NextRequestException.Reason.LOCALLY_ABANDONED, (frontier as NextRequestException).reason)
        }
        val late = rejected { sender(http).recordRejection(access(), NEXT_OPERATION, delivery.requestId,
            delivery.transmissionProof, json.encodeToString(delivery.result.results.single()), delivery.challengeMetadata) }
        assertEquals(NextRequestException.Reason.LOCALLY_ABANDONED, (late as NextRequestException).reason)
        // The actual delayed result cannot enter acceptance either; never manufacture an ACK.
        val acceptance = rejected { sender(http).acceptOperation(delivery) }
        assertEquals(NextRequestException.Reason.LOCALLY_ABANDONED, (acceptance as NextRequestException).reason)
        assertEquals(calls, server.requests.count { it.path.endsWith("/push") })
        assertEquals(cursor, db.nextSyncStateDao().rows()); assertEquals(0, count("next_acceptances"))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
    }

    @Test fun coldRecoveredObjectCanBeEditedAndSyncedWithoutRetiredPredecessorOrLingeringProblem() = runBlocking<Unit> {
        val (http, server) = initialize(); val a = edit("A"); edit("B")
        val service = business(http)
        val preview = service.readRecoveryPreview(NextRecoveryKey(NEXT_OPERATION, refuse(http, a.operationId)))
        val ticket = service.prepareRecoveryConfirmation(preview, preview.affected.map { it.key }.toSet())
        val saved = Saved(ticket, service.confirmRecovery(ticket).manifest, ticket.drafts())
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons)
        val runtime = NextSyncRuntime(db, tokens, sessions, http, preferences)
        assertTrue((runtime.readProblems() as com.dayforge.data.model.SyncProblems.Next).items.isEmpty())
        val rejectedBefore = db.nextSyncStateDao().rejections()
        val later = edit("Later legitimate edit")
        assertNull(db.nextStructuralCausalDao().dependency(later.operationId)!!.predecessorId)
        assertEquals(1L, decodeNextOperationIntent(db.nextRequestDao().origin(NEXT_OPERATION, later.operationId)!!.intentJson).baseRevision)
        failing = false
        business(http).sync()
        assertEquals("Later legitimate edit", db.metricDao().getMetricById(metric.id)!!.name)
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, later.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId))
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertEquals(rejectedBefore, db.nextSyncStateDao().rejections())
        assertTrue((runtime.readProblems() as com.dayforge.data.model.SyncProblems.Next).items.isEmpty())
        assertNotNull(preferences.lastSyncTimestamp.first())
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        assertEquals(1, server.requests.count { it.path.endsWith("/push") &&
            json.decodeFromString<RoundSyncPushRequest>(it.body.toString(Charsets.UTF_8)).operations.single().operationId == later.operationId })
    }

    @Test fun missingMembershipAndResurrectedOriginalSourceCannotBypassDirectSendOrLateRejection() = runBlocking<Unit> {
        val (saved, server) = saved()
        val root = saved.manifest.root
        val source = db.withTransaction { requireNotNull(audit.source(access(), root)).second }
        val delivery = rejectedDelivery
        db.withTransaction {
            sql.execSQL("DELETE FROM next_abandoned_requests WHERE kind=? AND requestId=?", arrayOf(root.kind, root.requestId))
            assertEquals(source.id, db.syncOutboxDao().insert(source))
        }
        val queues = db.syncOutboxDao().getAll(); val cursor = db.nextSyncStateDao().rows()
        val refused = db.nextSyncStateDao().rejections()
        val journal = requireNotNull(NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(root.kind, root.requestId)))
        val calls = server.requests.count { it.path.endsWith("/push") }
        rejected { sender(recoveryHttp).sendOperation(access(), root.requestId) }
        rejected { sender(recoveryHttp).sendAndAcceptOperation(access(), root.requestId) }
        rejected { sender(recoveryHttp).recordRejection(access(), root.kind, root.requestId, delivery.transmissionProof,
            json.encodeToString(delivery.result.results.single()), delivery.challengeMetadata) }
        assertEquals(calls, server.requests.count { it.path.endsWith("/push") })
        assertEquals(queues, db.syncOutboxDao().getAll()); assertEquals(cursor, db.nextSyncStateDao().rows())
        assertEquals(refused, db.nextSyncStateDao().rejections())
        assertEquals(journal, NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(root.kind, root.requestId)))
        assertEquals(0, count("next_acceptances"))
    }

    @Test fun actualSuccessfulDeliveryCannotCommitWhileAnEarlierTerminalIsDamaged() = runBlocking<Unit> {
        val (saved, _) = saved(); val later = edit("Actual later delivery"); failing = false
        val delivery = requireNotNull(sender(recoveryHttp).sendOperation(access(), later.operationId))
        assertEquals("applied", delivery.result.results.single().status)
        val queue = db.syncOutboxDao().getAll(); val cursor = db.nextSyncStateDao().rows()
        val journal = requireNotNull(NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, delivery.requestId)))
        sql.execSQL("UPDATE next_recovery_audits SET selectedCount=1 WHERE recoveryId=?", arrayOf(saved.id))
        rejected { sender(recoveryHttp).acceptOperation(delivery) }
        assertEquals(queue, db.syncOutboxDao().getAll()); assertEquals(cursor, db.nextSyncStateDao().rows())
        assertEquals(journal, NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, delivery.requestId)))
        assertEquals(0, count("next_acceptances")); assertNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun terminalDamageDuringActualHttpIsRejectedBeforePublishingItsDelivery() = runBlocking<Unit> {
        val (saved, _) = saved(); val later = edit("Work during real HTTP"); failing = false
        var preparedQueue: List<SyncOutboxEntity>? = null
        val (http, server) = channel { input ->
            respond(input).also {
                if (input.path.endsWith("/push")) runBlocking { db.withTransaction {
                    preparedQueue = db.syncOutboxDao().getAll()
                    sql.execSQL("DELETE FROM next_abandoned_requests WHERE kind=? AND requestId=?",
                        arrayOf(saved.manifest.root.kind, saved.manifest.root.requestId))
                } }
            }
        }
        rejected { sender(http).sendOperation(access(), later.operationId) }
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        assertEquals(requireNotNull(preparedQueue), db.syncOutboxDao().getAll())
        assertNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, later.operationId))
        assertEquals(0, count("next_acceptances")); assertNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun lateAcceptanceTriggerDamagingEarlierTerminalRollsBackAndOriginalDeliveryCanRetry() = runBlocking<Unit> {
        val (saved, _) = saved(); val later = edit("Accepted after terminal"); failing = false
        val delivery = requireNotNull(sender(recoveryHttp).sendOperation(access(), later.operationId))
        val queue = db.syncOutboxDao().getAll(); val metricBefore = db.metricDao().getMetricById(metric.id)
        val cursor = db.nextSyncStateDao().rows()
        sql.execSQL("CREATE TRIGGER recovery_late_accept AFTER INSERT ON next_acceptances BEGIN " +
            "UPDATE next_recovery_audits SET selectedCount=1; END")
        rejected { sender(recoveryHttp).acceptOperation(delivery) }
        storage.reopen()
        assertEquals(queue, db.syncOutboxDao().getAll()); assertEquals(metricBefore, db.metricDao().getMetricById(metric.id))
        assertEquals(cursor, db.nextSyncStateDao().rows()); assertEquals(0, count("next_acceptances"))
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        sql.execSQL("DROP TRIGGER recovery_late_accept")
        assertEquals(NextOperationAcceptance.COMMITTED, sender(recoveryHttp).acceptOperation(delivery))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        assertNull(db.nextRequestDao().acceptance(saved.manifest.root.kind, saved.manifest.root.requestId))
    }

    @Test fun corruptedTerminalCannotLetEmptyTimerOrOneTimeDrainClaimSuccess() = runBlocking<Unit> {
        val (saved, server) = saved(); val calls = server.requests.size
        val core = sender(recoveryHttp); val timers = NextTimerRequestStore(db, tokens, sessions, core)
        val local = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        val facts = OneTimeAcceptedEventStore(db, tokens, sessions, local, timerRequests = timers)
        val once = NextOneTimeRequestStore(db, tokens, sessions, recoveryHttp, facts, core)
        assertEquals(0, timers.pushPending(access())); assertEquals(0, once.pushPending(access()))
        sql.execSQL("UPDATE next_recovery_audits SET selectedCount=1 WHERE recoveryId=?", arrayOf(saved.id))
        rejected { timers.pushPending(access()) }; rejected { once.pushPending(access()) }
        rejected { timers.sendAndAccept(access(), id(999)) }; rejected { once.sendAndAccept(access(), id(999)) }
        assertEquals(calls, server.requests.size); assertEquals(0, count("next_acceptances"))
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun damagedOrOrphanTerminalCannotHideProblemsOrLetUnrelatedSyncReportSuccess() = runBlocking<Unit> {
        val (saved, _) = saved()
        val runtime = NextSyncRuntime(db, tokens, sessions, recoveryHttp, preferences)
        val before = db.nextSyncStateDao().rows(); val last = preferences.lastSyncTimestamp.first()
        sql.execSQL("UPDATE next_recovery_audits SET selectedCount=1 WHERE recoveryId=?", arrayOf(saved.id))
        rejected { runtime.readProblems() }; rejected { runtime.syncRounds() }
        assertEquals(before, db.nextSyncStateDao().rows()); assertEquals(last, preferences.lastSyncTimestamp.first())
        sql.execSQL("UPDATE next_recovery_audits SET selectedCount=3 WHERE recoveryId=?", arrayOf(saved.id))
        sql.execSQL("INSERT INTO next_recovery_audits SELECT ?,accountId,serverInstanceId,syncEpoch,deviceId," +
            "rootKind,rootRequestId,graphHash,checkpointHash,manifestHash,selectedCount FROM next_recovery_audits WHERE recoveryId=?",
            arrayOf(id(999), saved.id))
        rejected { runtime.readProblems() }; rejected { runtime.syncRounds() }
        assertEquals(before, db.nextSyncStateDao().rows()); assertEquals(last, preferences.lastSyncTimestamp.first())
        assertEquals(0, count("next_acceptances"))
    }

    @Test fun auditDamagedDuringActualPullCannotAdvanceCursorOrLastSuccessfulSync() = runBlocking<Unit> {
        val (saved, _) = saved()
        val before = db.nextSyncStateDao().rows(); val last = preferences.lastSyncTimestamp.first()
        pullFault = { runBlocking { db.withTransaction {
            sql.execSQL("UPDATE next_recovery_audits SET selectedCount=1 WHERE recoveryId=?", arrayOf(saved.id))
        } } }
        rejected { NextSyncRuntime(db, tokens, sessions, recoveryHttp, preferences).syncRounds() }
        assertNull(pullFault)
        assertEquals(before, db.nextSyncStateDao().rows()); assertEquals(last, preferences.lastSyncTimestamp.first())
        assertEquals(0, count("next_acceptances")); assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }

    @Test fun validAuditSurvivesCacheBootstrapButDamagedAuditCannotReinitializeCache() = runBlocking<Unit> {
        val (saved, _) = saved(); val meta = metadata()
        val before = db.nextSyncStateDao().rows().single()
        val response = RoundSyncBootstrapResponse(listOf(canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), 20, time, emptyList(),
            1, meta.checkpoints, meta.births)
        mergeStore().bootstrap(access(), before, response, cacheOnly = true)
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        val state = db.nextSyncStateDao().rows().single(); val originalMetric = db.metricDao().getMetricById(metric.id)
        sql.execSQL("UPDATE next_recovery_audits SET selectedCount=1 WHERE recoveryId=?", arrayOf(saved.id))
        rejected { mergeStore().bootstrap(access(), state, response, cacheOnly = true) }
        assertEquals(state, db.nextSyncStateDao().rows().single())
        assertEquals(originalMetric, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("next_acceptances")); assertNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun lateCursorTriggerCannotCommitDamagedAuditOrSuccessfulSync() = runBlocking<Unit> {
        val (saved, _) = saved(); val before = db.nextSyncStateDao().rows()
        for (action in listOf("UPDATE next_recovery_audits SET selectedCount=1",
            "UPDATE next_abandoned_requests SET sourceHash='${"a".repeat(64)}'",
            "UPDATE next_recovery_audit_payloads SET bytes=X'00' WHERE part=0")) {
            sql.execSQL("CREATE TRIGGER recovery_late_cursor AFTER UPDATE ON next_sync_state BEGIN $action; END")
            rejected { NextSyncRuntime(db, tokens, sessions, recoveryHttp, preferences).syncRounds() }
            assertEquals(before, db.nextSyncStateDao().rows()); assertNull(preferences.lastSyncTimestamp.first())
            assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
            assertEquals(0, count("next_acceptances")); assertTrue(db.syncOutboxDao().getAll().isEmpty())
            sql.execSQL("DROP TRIGGER recovery_late_cursor")
        }
    }

    @Test fun nextRecoveryUsesOnlyItsLiveChainAndKeepsEarlierAuditAndConfirmationImmutable() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("First rejected edit"); edit("First successor")
        val first = terminal(preview(http, refuse(http, a.operationId)))
        val b = edit("Second rejected edit"); val c = edit("Second successor")
        val secondPreview = preview(http, refuse(http, b.operationId))
        assertTrue(secondPreview.canConfirm)
        assertEquals(setOf(b.operationId, c.operationId), secondPreview.affected.map { it.key.requestId }.toSet())
        val second = terminal(secondPreview)
        assertEquals(2, count("next_recovery_audits")); assertEquals(4, count("next_abandoned_requests"))
        val metricAfter = db.metricDao().getMetricById(metric.id)
        assertEquals(first.manifest, confirm(first.ticket).manifest)
        assertEquals(second.manifest, db.withTransaction { audit.read(access(), second.id).manifest })
        assertEquals(metricAfter, db.metricDao().getMetricById(metric.id))
        assertTrue((NextSyncRuntime(db, tokens, sessions, http, preferences).readProblems() as
            com.dayforge.data.model.SyncProblems.Next).items.isEmpty())
        assertEquals(0, count("next_acceptances")); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val retired = rejected { preview(http, a.operationId) }
        assertEquals(NextRequestException.Reason.LOCALLY_ABANDONED, (retired as NextRequestException).reason)
    }

    @Test fun configurationReplacementDoesNotTreatProvenRecoveryAsPendingButDamagedAuditStillRefuses() = runBlocking<Unit> {
        val (saved, _) = saved()
        val repository = NextConfigImportRepository(db, tokens, sessions, icons, creator, app)
        val source = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
        val value = repository.previewReplacement(source)
        assertTrue(value.eligible); repository.recheckReplacement(value)
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
        assertEquals(0, count("next_config_imports")); assertEquals(0, count("next_acceptances"))
        sql.execSQL("UPDATE next_recovery_audits SET selectedCount=1 WHERE recoveryId=?", arrayOf(saved.id))
        rejected { repository.previewReplacement(source) }; rejected { repository.recheckReplacement(value) }
        assertEquals(0, count("next_config_imports")); assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }

    @Test fun formalRegistrationSerializesConfirmationAndItsCompletedPullMakesTheOldPreviewStale() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit("Rejected edit"); edit("Successor")
        val service = business(http)
        val preview = service.readRecoveryPreview(NextRecoveryKey(NEXT_OPERATION, refuse(http, a.operationId)))
        val ticket = service.prepareRecoveryConfirmation(preview, preview.affected.map { it.key }.toSet())
        val sources = db.syncOutboxDao().getAll()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        registrationFault = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val syncing = async(Dispatchers.IO) { runCatching { service.sync() }.exceptionOrNull() }
        lateinit var confirming: kotlinx.coroutines.Deferred<Throwable?>
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val started = CompletableDeferred<Unit>()
            val pending = async(Dispatchers.IO) { started.complete(Unit); runCatching { service.confirmRecovery(ticket) }.exceptionOrNull() }
            confirming = pending
            started.await()
            assertNull(withTimeoutOrNull(250) { pending.await() })
            assertFalse(pending.isCompleted); assertEquals(0, count("next_recovery_audits"))
        } finally { release.countDown() }
        assertTrue(syncing.await() is NextSyncAttention)
        assertNotNull(confirming.await())
        assertEquals(sources, db.syncOutboxDao().getAll()); assertEquals(0, count("next_recovery_audits"))
        val fresh = service.readRecoveryPreview(preview.root)
        val newTicket = service.prepareRecoveryConfirmation(fresh, fresh.affected.map { it.key }.toSet())
        assertEquals(newTicket.recoveryId, service.confirmRecovery(newTicket).recoveryId)
        assertEquals(0, count("next_acceptances")); assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }

    @Test fun retiredInitialCreationCannotProvideBirthOrReviveAfterColdOpenWhileOtherWorkSyncs() = runBlocking<Unit> {
        val (http, _) = initialize()
        val localId = creatingHabits().createHabit("Rejected initial", "", com.dayforge.data.model.HabitType.CHECK_IN,
            0, "#123456", com.dayforge.data.model.HabitSchedule.Daily,
            appearance = com.dayforge.domain.model.ObjectAppearance(com.dayforge.domain.model.IconReference.Role("habit.exercise"),
                "#123456", "theme"), completionPolicy = "recurring", creationAuthority = creator.capture())
        val created = db.habitDao().getHabitById(localId)!!.uuid
        val source = db.syncOutboxDao().getEntityIntents("habit", created).single()
        val saved = terminal(preview(http, refuse(http, source.operationId)))
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons)
        assertNull(db.habitDao().getHabitByUuid(created))
        assertFalse(db.withTransaction { NextRoundPendingInitialStore(db).read(access(), metadata()) }.containsKey(created))
        assertFalse(producer().captureRounds().pendingInitials.containsKey(created))
        val later = edit("Independent work after initial recovery"); failing = false
        NextSyncRuntime(db, tokens, sessions, http, preferences).syncRounds()
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, later.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
        assertNull(db.habitDao().getHabitByUuid(created)); assertNotNull(preferences.lastSyncTimestamp.first())
        assertEquals(saved.manifest, db.withTransaction { audit.read(access(), saved.id).manifest })
    }
}
