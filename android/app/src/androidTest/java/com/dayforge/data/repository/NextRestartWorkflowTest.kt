package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room, original offline producer, HTTP envelope, common receipt and contiguous Plan log. */
@RunWith(AndroidJUnit4::class)
class NextRestartWorkflowTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private val history = mutableListOf<ChallengeRoundRecord>()
    private val changes = mutableListOf<SyncV2Change>()
    private val wires = mutableListOf<ByteArray>()
    private var revision = 1L
    private var cursor = 20L
    private var dropReply = false
    private var rejectRestart = false
    private var rejectionCode = "CHALLENGE_STATE_CONFLICT"
    private var canonicalCreated = time
    private var protocol = 5
    private val births = mutableListOf<ChallengeBirth>()
    private val policies = mutableMapOf<String, TimerStartPolicy>()
    private lateinit var serverPlan: HabitEntity

    @Before fun finitePlan() = runBlocking<Unit> {
        habit = habit.copy(targetCycles = 3, isActive = false)
        timerHabit = timerHabit.copy(targetCycles = 3)
        serverPlan = habit
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            db.habitDao().update(timerHabit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        history += ChallengeRoundRecord(initialChallengeRoundHead(habit.uuid), null, null, null)
    }
    private fun metadata() = ChallengeMetadata(1, listOf(ChallengeCheckpoint(history.last().head, history.toList()),
        ChallengeCheckpoint(initialChallengeRoundHead(timerHabit.uuid), listOf(ChallengeRoundRecord(initialChallengeRoundHead(timerHabit.uuid), null, null, null)))), births.toList())
    private fun plan(row: HabitEntity, revision: Long) = SyncV2Change(0, "plan_node", row.uuid, "upsert", revision,
        JsonObject(NextStructureMapper.writePlan(row) + mapOf("public_id" to JsonPrimitive(row.uuid), "revision" to JsonPrimitive(revision),
            "created_at" to JsonPrimitive(canonicalCreated), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time, id(4))
    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions,
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)),
        NextTimerRequestStore(db, tokens, sessions, sender(http)))
    private fun restart(http: NextSyncHttp) = NextChallengeRestartRepository(db, tokens, sessions, http)
    private suspend fun current() = requireNotNull(db.habitDao().getHabitById(habit.id))
    private suspend fun propose(http: NextSyncHttp): NextRestartReference {
        val snapshot = editor.habit(habit.id)
        return restart(http).restart(requireNotNull(snapshot.value), requireNotNull(snapshot.authority))
    }
    private suspend fun initialize(timer: Boolean = false): NextSyncHttp {
        if (timer) {
            habit = habit.copy(habitType = com.dayforge.data.model.HabitType.TIMER, targetValue = 1, isActive = true,
                planMetadata = requireNotNull(habit.planMetadata).copy(targetUnit = "second"))
            serverPlan = habit
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                db.habitDao().update(habit)
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
        }
        register()
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input, protocol)
            else if (input.path.startsWith("/api/v2/sync/rounds/changes")) {
                val from = input.target.substringAfter("cursor=").substringBefore('&').toLong()
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(changes.filter { it.sequence > from }, cursor,
                    false, time, 1, meta.checkpoints, meta.births)).toByteArray())
            } else if (input.path == "/api/v2/timers/rounds/commands") {
                val body = json.decodeFromString<RoundTimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8))
                val command = body.commands.single()
                command.startPolicy?.let { policies[command.sessionId] = it }
                val policy = policies.getValue(command.sessionId)
                if (births.none { it.entityUuid == command.sessionId }) births += ChallengeBirth("timer_session", command.sessionId, body.contexts.single().head!!)
                val state = if (command.commandType == "cancel") "cancelled" else "running"
                val session = TimerSessionResponse(command.sessionId, habit.uuid, state, id(4), 1, command.sequence,
                    command.sequence + 1, time, command.occurredAt, endedAt = command.occurredAt.takeIf { state == "cancelled" },
                    timezone = "Asia/Shanghai", isCountdown = policy.isCountdown, targetSeconds = policy.targetSeconds,
                    maxDurationSeconds = policy.maxDurationSeconds, activeElapsedMs = command.activeElapsedMs ?: 0)
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundTimerCommandBatchResponse(listOf(TimerCommandResult(command.commandId,
                    command.sessionId, "applied", session = session)), time, 1, meta.checkpoints, meta.births)).toByteArray())
            } else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                wires += input.body.copyOf()
                val body = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
                val operation = body.operations.single()
                if (operation.entityType == "plan_node") {
                    assertEquals(revision, operation.baseRevision)
                    val canonical = JsonObject(operation.payload + mapOf("public_id" to JsonPrimitive(habit.uuid), "revision" to JsonPrimitive(++revision),
                        "created_at" to JsonPrimitive(canonicalCreated), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull))
                    serverPlan = NextStructureMapper.readPlan(canonical, habit.uuid, revision).copy(id = habit.id)
                    changes += SyncV2Change(++cursor, "plan_node", habit.uuid, "upsert", revision, canonical, time, id(4))
                    val meta = metadata()
                    return@channel MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(NextSyncOperationResult(
                        operation.operationId, "plan_node", habit.uuid, "applied", revision, entity = canonical)), 1, meta.checkpoints, meta.births)).toByteArray())
                }
                assertEquals("challenge_round", operation.entityType)
                assertNull(body.contexts.single().head)
                val intent = json.decodeFromJsonElement<ChallengeRestartIntent>(operation.payload)
                val existing = history.singleOrNull { it.restartOperationUuid == operation.operationId }
                val result = if (rejectRestart) NextSyncOperationResult(operation.operationId, operation.entityType,
                    operation.entityUuid, "conflict", errorCode = rejectionCode) else {
                    val record = existing ?: ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, intent.roundUuid,
                        intent.expectedGeneration + 1), id(4), operation.operationId, intent).also {
                        assertEquals(history.last().head.roundUuid, intent.expectedRoundUuid)
                        assertEquals(revision, intent.expectedPlanRevision)
                        history += it
                        serverPlan = serverPlan.copy(isActive = true)
                        revision++
                        changes += SyncV2Change(++cursor, "challenge_round", it.head.roundUuid, "upsert", 1,
                            json.encodeToJsonElement(it).jsonObject, time, id(4))
                        changes += plan(serverPlan, revision).copy(sequence = ++cursor)
                    }
                    NextSyncOperationResult(operation.operationId, operation.entityType, operation.entityUuid,
                        if (existing == null) "applied" else "already_applied", 1, entity = json.encodeToJsonElement(record).jsonObject)
                }
                val meta = metadata()
                if (dropReply) { dropReply = false; null } else MaterialSocketServer.Reply(json.encodeToString(
                    RoundSyncPushResponse(listOf(result), 1, meta.checkpoints, meta.births)).toByteArray())
            }
        }
        val metricChange = SyncV2Change(0, "metric", metric.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writeMetric(metric) + mapOf("public_id" to JsonPrimitive(metric.uuid), "revision" to JsonPrimitive(1),
                "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        val meta = metadata()
        merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(plan(habit, 1), plan(timerHabit, 1), metricChange),
            cursor, time, emptyList(), 1, meta.checkpoints, meta.births))
        return http
    }
    private suspend fun durable() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun acceptedPlan(reference: NextRestartReference) = db.withTransaction {
        NextRestartStore(db).acceptedPlan(access(), reference)
    }
    private fun runtime(http: NextSyncHttp) = NextSyncRuntime(db, tokens, sessions, http, preferences)

    @Test fun offlineProducerPreservesPlanIdentityFactsAndHasNoFakeAcceptanceOrRevision() = runBlocking {
        val http = initialize()
        val row = current(); val ref = propose(http)
        assertEquals(row.copy(isActive = true, updatedAt = current().updatedAt), current())
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("next_acceptances"))
        assertEquals(2, count("next_challenge_rounds")); assertEquals(0, count("next_restart_materializations"))
        val original = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, ref.operationId))
        assertEquals(original.intentJson, db.syncOutboxDao().getByOperationId(ref.operationId)!!.payloadJson)
        assertFalse(original.intentJson.contains("expected_plan_revision"))
        assertEquals(ref, producer().captureRounds().pendingRestarts[habit.uuid])
        storage.reopen()
        assertEquals(ref, producer().captureRounds().pendingRestarts[habit.uuid])
        assertEquals(1, count("sync_outbox")); assertEquals(1L, db.syncOutboxDao().getState("plan_node", habit.uuid)!!.revision)
    }
    @Test fun runtimeCompletesTwoOfflineRestartsWithRealIntermediatePlanProofs() = runBlocking {
        val http = initialize(); val first = propose(http); val second = propose(http)
        assertEquals(first.head.generation + 1, second.head.generation)
        assertEquals(0, count("next_challenge_births")); assertEquals(2, count("sync_outbox"))
        runtime(http).syncRounds()
        assertEquals(0, count("sync_outbox")); assertEquals(2, count("next_acceptances")); assertEquals(4, count("next_challenge_rounds"))
        assertEquals(2L, acceptedPlan(first).first.getValue("revision").jsonPrimitive.long)
        assertEquals(3L, acceptedPlan(second).first.getValue("revision").jsonPrimitive.long)
        val originalWires = wires.map { it.copyOf() }
        storage.reopen()
        assertEquals(NextRestartOutcome.REPLAYED, restart(http).sendAndAccept(access(), first.operationId))
        assertEquals(NextRestartOutcome.REPLAYED, restart(http).sendAndAccept(access(), second.operationId))
        assertEquals(originalWires.size, wires.size)
        assertEquals(second.head, producer().captureRounds().let { json.decodeFromString<ChallengeMetadata>(it.metadataJson).checkpoints.single { c -> c.head.activityUuid == habit.uuid }.head })
        assertTrue(producer().captureRounds().pendingRestarts.isEmpty())
    }
    @Test fun droppedReplyReplaysSameIdAndBytesAfterColdOpenWithoutSecondRound() = runBlocking {
        val http = initialize(); val ref = propose(http); dropReply = true
        rejected { restart(http).sendAndAccept(access(), ref.operationId) }
        assertEquals(1, count("next_transmissions")); assertEquals(0, count("next_acceptances")); assertEquals(1, count("sync_outbox"))
        assertEquals(2, count("next_challenge_rounds"))
        storage.reopen(); runtime(http).syncRounds()
        assertEquals(2, wires.size); assertArrayEquals(wires[0], wires[1])
        assertEquals(2, history.size); assertEquals(3, count("next_challenge_rounds"))
        assertEquals(2L, acceptedPlan(ref).first.getValue("revision").jsonPrimitive.long)
    }
    @Test fun actualOfflineConfigurationChainSettlesBeforeRestartRevisionIsMaterialized() = runBlocking {
        val http = initialize()
        canonicalCreated = "2026-10-06T00:00:00.000000Z"
        for (name in listOf("First offline edit", "Second offline edit")) {
            val snapshot = editor.habit(habit.id)
            editingHabits().updateHabit(snapshot.value!!.copy(name = name), editAuthority = snapshot.authority)
        }
        val ref = propose(http)
        assertEquals(3, count("sync_outbox")); assertEquals(0, count("next_restart_materializations"))
        runtime(http).syncRounds()
        assertEquals(0, count("sync_outbox")); assertEquals("Second offline edit", current().name)
        val restartWire = json.decodeFromString<RoundSyncPushRequest>(wires.last().toString(Charsets.UTF_8)).operations.single()
        assertEquals(3L, json.decodeFromJsonElement<ChallengeRestartIntent>(restartWire.payload).expectedPlanRevision)
        assertEquals(4L, acceptedPlan(ref).first.getValue("revision").jsonPrimitive.long)
        assertEquals(JsonPrimitive(canonicalCreated), acceptedPlan(ref).first["created_at"])
    }
    @Test fun realOfflineStartCancelRequiresFullTerminalAckBeforeRestartCanFreeze() = runBlocking {
        val http = initialize(timer = true)
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = habit.id, uuid = id(870), startTime = millis, endTime = null,
                durationSeconds = 0, date = millis, timerNextCommandSequence = 2, timerControlGeneration = 1,
                timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(871), sessionUuid = id(870), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = habit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(870), sequence = 1, startedAt = millis))
        }
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().deleteTimerAndQueue(db.timeLogDao().getTimeLogByUuid(id(870))!!,
                TimerCommandEntity(commandId = id(872), sessionUuid = id(870), sequence = 2, commandType = "cancel",
                    occurredAt = millis + 1000, expectedControlGeneration = 1, activeElapsedMillis = 1000))
        }
        val ref = propose(http)
        val before = durable()
        val error = rejected { restart(http).sendAndAccept(access(), ref.operationId) }
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING, (error as NextRequestException).reason)
        assertEquals(before, durable()); assertTrue(wires.isEmpty())
        runtime(http).syncRounds()
        assertEquals(0, count("timer_command_outbox")); assertEquals(0, count("timelogs"))
        assertEquals(3, count("next_acceptances")); assertEquals(1, count("next_challenge_births"))
        assertEquals(2L, acceptedPlan(ref).first.getValue("revision").jsonPrimitive.long)
    }
    @Test fun authenticPlanBeforeLocalAckIsNotAnAcceptanceAndStillAllowsExactReplay() = runBlocking {
        val http = initialize(); val ref = propose(http); dropReply = true
        rejected { restart(http).sendAndAccept(access(), ref.operationId) }
        val merge = merger(http); val state = requireNotNull(merge.state(access(), true)); val meta = metadata()
        merge.page(access(), state, RoundSyncPullResponse(changes.toList(), cursor, false, time, 1, meta.checkpoints, meta.births))
        assertEquals(0, count("next_acceptances")); assertEquals(1, count("sync_outbox")); assertEquals(1, count("next_restart_plan_proofs"))
        rejected { acceptedPlan(ref) }
        assertEquals(ref, producer().captureRounds().pendingRestarts[habit.uuid])
        storage.reopen(); runtime(http).syncRounds()
        assertArrayEquals(wires[0], wires[1]); assertEquals(2L, acceptedPlan(ref).first.getValue("revision").jsonPrimitive.long)
    }
    @Test fun permanentConflictKeepsOriginalAndLocalProjectionWithoutConsumingQueue() = runBlocking<Unit> {
        val http = initialize(); val ref = propose(http); rejectRestart = true
        assertEquals(NextRestartOutcome.REJECTED, restart(http).sendAndAccept(access(), ref.operationId))
        assertEquals(1, count("sync_outbox")); assertEquals(1, count("next_rejections")); assertEquals(0, count("next_acceptances"))
        assertEquals(2, count("next_challenge_rounds")); assertTrue(current().isActive)
        val original = db.nextRequestDao().origin(NEXT_OPERATION, ref.operationId)
        val wire = transmission(NEXT_OPERATION, ref.operationId).wireBytes.copyOf()
        val rejection = db.nextSyncStateDao().rejections().single()
        storage.reopen()
        assertTrue(rejected { runtime(http).syncRounds() } is NextSyncAttention)
        // Cursor display may advance, but source, wire and rejection are unchanged.
        assertEquals(1, count("sync_outbox")); assertEquals(1, count("next_rejections")); assertEquals(1, wires.size)
        assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, ref.operationId))
        assertArrayEquals(wire, transmission(NEXT_OPERATION, ref.operationId).wireBytes)
        assertEquals(rejection, db.nextSyncStateDao().rejections().single()); rejected { acceptedPlan(ref) }
    }
    @Test fun unsupportedDiscoveryDoesNotMaterializeOrSendProposal() = runBlocking {
        val http = initialize(); val ref = propose(http); protocol = 4
        val before = durable()
        assertNull(restart(http).sendAndAccept(access(), ref.operationId))
        assertEquals(before, durable()); assertTrue(wires.isEmpty())
    }
    @Test fun staleTicketAndRunningOrPausedTimerRejectWithoutChangingBusiness() = runBlocking {
        val http = initialize(); val ticket = editor.habit(habit.id)
        propose(http); val before = durable()
        rejected { restart(http).restart(ticket.value!!, ticket.authority!!) }
        assertEquals(before, durable())
        for (paused in listOf(false, true)) {
            val log = TimeLogEntity(habitId = timerHabit.id, uuid = id(if (paused) 841 else 840), startTime = millis,
                endTime = null, durationSeconds = 0, date = millis, isPaused = paused)
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                db.timeLogDao().insert(log)
                db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
            val snapshot = editor.habit(timerHabit.id); val original = durable()
            assertEquals("CHALLENGE_TIMER_UNFINISHED", rejected { restart(http).restart(snapshot.value!!, snapshot.authority!!) }.message)
            assertEquals(original, durable())
        }
    }
    @Test fun finalAcceptanceFaultRollsBackMetadataReceiptAndQueueThenColdRetryUsesOriginalBytes() = runBlocking {
        val http = initialize(); val ref = propose(http)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER restart_receipt_fault AFTER INSERT ON next_acceptances BEGIN SELECT RAISE(ABORT,'injected receipt fault'); END")
        rejected { restart(http).sendAndAccept(access(), ref.operationId) }
        storage.reopen()
        assertEquals(0, count("next_acceptances")); assertEquals(2, count("next_challenge_rounds")); assertEquals(1, count("sync_outbox"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER restart_receipt_fault")
        runtime(http).syncRounds()
        assertArrayEquals(wires[0], wires[1]); assertEquals(2L, acceptedPlan(ref).first.getValue("revision").jsonPrimitive.long)
    }
    @Test fun originSideEffectDuringFreezeRollsBackJournalAndMaterialization() = runBlocking {
        val http = initialize(); val ref = propose(http); val before = durable()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER restart_freeze_fault AFTER INSERT ON next_transmissions BEGIN UPDATE next_request_origins SET intentJson='{}'; END")
        rejected { restart(http).sendAndAccept(access(), ref.operationId) }
        assertEquals(before, durable()); assertEquals(0, count("next_restart_materializations")); assertTrue(wires.isEmpty())
    }
    @Test fun changedAccountOrOriginalProofCannotSendOrAcknowledge() = runBlocking {
        val http = initialize(); val ref = propose(http); val access = access()
        val original = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, ref.operationId))
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=CAST(intentJson AS BLOB)")
        val bad = durable(); rejected { restart(http).sendAndAccept(access, ref.operationId) }; assertEquals(bad, durable())
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=?", arrayOf(original.intentJson))
        tokens.saveLoginSession("other", "other-refresh", "other", id(900), false)
        val before = durable(); rejected { restart(http).sendAndAccept(access, ref.operationId) }
        assertEquals(before, durable()); assertTrue(wires.isEmpty())
    }
    @Test fun validJsonOriginChangeCannotReplaceOriginalPrivateBodyAfterColdOpen() = runBlocking {
        val http = initialize(); val ref = propose(http)
        val original = db.nextRequestDao().origin(NEXT_OPERATION, ref.operationId)!!
        val body = json.decodeFromString<NextRestartProposal>(original.intentJson)
        val altered = body.copy(basePlan = JsonObject(body.basePlan!! + ("revision" to JsonPrimitive(2))))
        val alteredJson = json.encodeToString(altered)
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?", arrayOf(alteredJson, ref.operationId))
        storage.reopen(); val bad = durable()
        rejected { restart(http).sendAndAccept(access(), ref.operationId) }
        assertEquals(bad, durable()); assertTrue(wires.isEmpty()); assertEquals(0, count("next_restart_materializations"))
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?", arrayOf(original.intentJson, ref.operationId))
        runtime(http).syncRounds()
        assertEquals(2L, acceptedPlan(ref).first.getValue("revision").jsonPrimitive.long)
    }
    @Test fun transientReplyKeepsOriginalWireAndIsNotDurableRejection() = runBlocking {
        val http = initialize(); val ref = propose(http); rejectRestart = true; rejectionCode = "MISSING_PREDECESSOR"
        assertEquals(NextRestartOutcome.RETRY_REQUIRED, restart(http).sendAndAccept(access(), ref.operationId))
        assertEquals(0, count("next_acceptances")); assertEquals(0, count("next_rejections")); assertEquals(1, count("sync_outbox"))
        storage.reopen(); rejectRestart = false
        runtime(http).syncRounds()
        assertArrayEquals(wires[0], wires[1]); assertEquals(2, history.size)
        assertEquals(2L, acceptedPlan(ref).first.getValue("revision").jsonPrimitive.long)
    }
    @Test fun authenticatedPlanProofFaultRollsBackPageButKeepsPriorRealRestartAck() = runBlocking {
        val http = initialize(); val ref = propose(http)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER restart_plan_fault AFTER INSERT ON next_restart_plan_proofs BEGIN UPDATE next_request_origins SET intentJson='{}'; END")
        rejected { runtime(http).syncRounds() }; storage.reopen()
        assertEquals(1, count("next_acceptances")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_restart_plan_proofs"))
        assertEquals(20L, merger(http).state(access(), true)!!.cursor)
        assertEquals(1L, db.syncOutboxDao().getState("plan_node", habit.uuid)!!.revision)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER restart_plan_fault")
        runtime(http).syncRounds()
        assertEquals(1, wires.size); assertEquals(2L, acceptedPlan(ref).first.getValue("revision").jsonPrimitive.long)
    }
    @Test fun remoteTombstoneCannotCascadeUnsynchronizedRestartProjectionOrSources() = runBlocking {
        val http = initialize(); val ref = propose(http)
        val change = plan(serverPlan, 2).let { it.copy(sequence = 21, operation = "delete",
            payload = JsonObject(it.payload + ("deleted_at" to JsonPrimitive(time)))) }
        val merge = merger(http); val state = merge.state(access(), true)!!; val before = durable(); val meta = metadata()
        assertEquals("SYNC_LOCAL_WORK_REQUIRES_RESOLUTION", rejected {
            merge.page(access(), state, RoundSyncPullResponse(listOf(change), 21, false, time, 1, meta.checkpoints, meta.births))
        }.message)
        assertEquals(before, durable()); assertTrue(current().isActive)
        storage.reopen(); runtime(http).syncRounds()
        assertEquals(2L, acceptedPlan(ref).first.getValue("revision").jsonPrimitive.long)
    }
}
