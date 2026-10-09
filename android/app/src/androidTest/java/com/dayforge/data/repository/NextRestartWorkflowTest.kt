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
    private val eventResults = mutableMapOf<String, NextSyncOperationResult>()
    private val timerWires = mutableListOf<ByteArray>()
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
    private suspend fun initialize(timer: Boolean = false, check: Boolean = false): NextSyncHttp {
        if (timer || check) {
            habit = habit.copy(habitType = if (timer) com.dayforge.data.model.HabitType.TIMER else com.dayforge.data.model.HabitType.CHECK_IN,
                targetValue = 1, isActive = true,
                planMetadata = requireNotNull(habit.planMetadata).copy(targetUnit = if (timer) "second" else null))
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
                timerWires += input.body.copyOf()
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
                if (operation.entityType == "activity_event") {
                    val head = requireNotNull(body.contexts.single().head)
                    assertTrue(history.any { it.head == head })
                    val existing = eventResults[operation.operationId]
                    val result = existing?.copy(status = "already_applied") ?: run {
                        births += ChallengeBirth("activity_event", operation.entityUuid, head)
                        val ordinary = json.decodeFromString<NextSyncPushResponse>(successReply(input, 1).bytes.toString(Charsets.UTF_8)).results.single()
                        eventResults[operation.operationId] = ordinary
                        val canonical = requireNotNull(ordinary.entity)
                        changes += SyncV2Change(++cursor, "activity_event", operation.entityUuid, "upsert", 1,
                            canonical, canonical.getValue("updated_at").jsonPrimitive.content, id(4))
                        ordinary
                    }
                    val meta = metadata()
                    return@channel MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(listOf(result), 1,
                        meta.checkpoints, meta.births)).toByteArray())
                }
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

    private suspend fun fact(n: Int, quantity: Int): CompletionEntity {
        val date = java.time.LocalDate.parse("2026-10-06")
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val row = current()
        val original = CompletionEntity(habitId = row.id, habitUuid = row.uuid, uuid = id(n), value = quantity,
            date = date.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = millis,
            recordedTimezone = zone.id, recordedLocalDate = date.toString())
        val saved = producer().writeRounds(producer().captureRounds()) {
            NextCountDayStore(db).capture(row, original)
            db.completionDao().insert(original)
        }
        return original.copy(id = saved)
    }
    private suspend fun countHistory() = CountHistoryReader(db, tokens, sessions).read(current(), java.time.LocalDate.parse("2026-10-06"))
    private suspend fun edit(name: String) {
        val snapshot = editor.habit(habit.id)
        editingHabits().updateHabit(snapshot.value!!.copy(name = name), editAuthority = snapshot.authority)
    }
    private suspend fun localStartCancel() {
        val writer = NextTimerWriter(db, tokens, sessions)
        writer.write(habit.id, writer.capture(habit.id)) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = habit.id, uuid = id(870), startTime = millis, endTime = null,
                durationSeconds = 0, date = millis, timerNextCommandSequence = 2, timerControlGeneration = 1,
                timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(871), sessionUuid = id(870), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = habit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(870), sequence = 1, startedAt = millis))
        }
        writer.write(habit.id, writer.capture(habit.id)) {
            db.timeLogDao().deleteTimerAndQueue(db.timeLogDao().getTimeLogByUuid(id(870))!!,
                TimerCommandEntity(commandId = id(872), sessionUuid = id(870), sequence = 2, commandType = "cancel",
                    occurredAt = millis + 1000, expectedControlGeneration = 1, activeElapsedMillis = 1000))
        }
    }

    @Test fun twoPendingRoundsReadOnlyTheirOwnCountsWithoutResettingSharedDayPolicyThenColdSync() = runBlocking {
        val http = initialize(); val first = propose(http); val firstFact = fact(910, 3)
        assertEquals(first.head, countHistory().roundHead); assertEquals(3L, countHistory().todayQuantity)
        val day = db.countDayDao().get(habit.id, "2026-10-06")!!
        val second = propose(http)
        assertEquals(0L, countHistory().todayQuantity); val secondFact = fact(911, 4)
        assertEquals(second.head, countHistory().roundHead); assertEquals(4L, countHistory().todayQuantity)
        assertEquals(day, db.countDayDao().get(habit.id, "2026-10-06"))
        assertEquals(0, count("next_acceptances")); assertEquals(0, count("next_challenge_births")); assertEquals(0, count("next_transmissions"))
        storage.reopen(); assertEquals(4L, countHistory().todayQuantity)
        runtime(http).syncRounds()
        assertEquals(0, count("sync_outbox")); assertEquals(2, count("completions")); assertEquals(4L, countHistory().todayQuantity)
        assertEquals(first.head, births.single { it.entityUuid == firstFact.uuid }.head)
        assertEquals(second.head, births.single { it.entityUuid == secondFact.uuid }.head)
        storage.reopen(); val before = wires.size; runtime(http).syncRounds(); assertEquals(before, wires.size)
        assertEquals(4L, countHistory().todayQuantity)
    }
    @Test fun actualHabitRepositoryRecordsAndUndoesCheckInOfflineAfterRestartWithOriginalIdentity() = runBlocking {
        val http = initialize(check = true); val ref = propose(http)
        val snapshot = editor.habit(habit.id)
        val recorded = editingHabits().logCompletion(app, habit.id, authority = snapshot.authority, expectedHabitUuid = habit.uuid)
        val fact = db.completionDao().getCompletionById(recorded)!!
        val source = db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid }
        assertEquals(ref, roundOperationIntent(originalIntent(source).intentJson)!!.restartFrontier)
        assertEquals(ref.head, roundOperationIntent(originalIntent(source).intentJson)!!.context.head)
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("next_acceptances"))
        val undoTicket = editor.habit(habit.id)
        editingHabits().undoCompletion(app, recorded, authority = undoTicket.authority)
        assertEquals(0, count("completions")); storage.reopen(); runtime(http).syncRounds()
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("completions"))
        assertTrue(births.filter { it.entityType == "activity_event" }.all { it.head == ref.head })
        assertEquals(2, births.count { it.entityType == "activity_event" })
    }
    @Test fun pendingRoundCountCannotFreezeBeforeRealRestartReceiptAndContiguousPlanProof() = runBlocking {
        val http = initialize(); val ref = propose(http); val fact = fact(912, 2)
        val source = db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid }
        val before = durable()
        val error = rejected { sender(http).sendAndAcceptOperation(access(), source.operationId) }
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING, (error as NextRequestException).reason)
        assertEquals(before, durable()); assertTrue(wires.isEmpty())
        restart(http).sendAndAccept(access(), ref.operationId)
        val realAck = durable(); rejected { sender(http).sendAndAcceptOperation(access(), source.operationId) }
        assertEquals(realAck, durable()); assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, source.operationId))
        storage.reopen(); runtime(http).syncRounds()
        assertEquals(0, count("sync_outbox")); assertEquals(2L, countHistory().todayQuantity)
    }
    @Test fun twoOfflineEditsAfterRestartUseRealPlanRevisionAndOriginalSourceRetirement() = runBlocking {
        val http = initialize(); val ref = propose(http)
        edit("First new-round edit"); edit("Final new-round edit")
        val roots = db.syncOutboxDao().getAll().filter { it.recordType == "habit" }
        val originals = roots.map { originalIntent(it) }
        val firstDep = db.nextStructuralCausalDao().dependency(roots.first().operationId)!!
        assertNull(firstDep.predecessorId)
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("next_acceptances"))
        storage.reopen(); runtime(http).syncRounds()
        assertEquals(0, count("sync_outbox")); assertEquals("Final new-round edit", current().name)
        assertEquals(2L, acceptedPlan(ref).first.getValue("revision").jsonPrimitive.long)
        for (original in originals) {
            assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, original.requestId))
            assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, original.requestId))
        }
        assertEquals(2, count("next_structural_supersessions"))
        val rootReplacement = db.nextStructuralCausalDao().supersession(roots.first().operationId)!!
        assertEquals(ref.operationId, rootReplacement.predecessorAcceptedRequestId)
        val replacement = db.nextRequestDao().origin(NEXT_OPERATION, rootReplacement.replacementId)!!
        assertEquals(acceptedPlan(ref).second, roundOperationIntent(replacement.intentJson)!!.restartPlanProofHash)
        storage.reopen(); val size = wires.size
        for (source in roots) assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), source.operationId))
        assertEquals(size, wires.size)
    }
    @Test fun plainRoundStructuralReplacementRetainsPreBindingPrivateEncodingAndColdReplay() = runBlocking {
        val http = initialize(); edit("Original encoding"); edit("Second encoding")
        val rows = db.syncOutboxDao().getAll().filter { it.recordType == "habit" }
        for (row in rows) {
            val original = originalIntent(row)
            val body = json.parseToJsonElement(original.intentJson).jsonObject
            assertFalse(body.containsKey("restart_frontier")); assertFalse(body.containsKey("restart_plan_proof_hash"))
        }
        runtime(http).syncRounds(); val size = wires.size
        val replacement = db.nextStructuralCausalDao().supersession(rows.last().operationId)!!
        val bytes = db.nextRequestDao().origin(NEXT_OPERATION, replacement.replacementId)!!.intentJson
        assertFalse(json.parseToJsonElement(bytes).jsonObject.containsKey("restart_frontier"))
        storage.reopen(); assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), rows.last().operationId))
        assertEquals(size, wires.size); assertEquals(bytes, db.nextRequestDao().origin(NEXT_OPERATION, replacement.replacementId)!!.intentJson)
    }
    @Test fun preRestartEditDoesNotBecomeNewRoundOrdinaryParentAndLaterRestartWaitsActualEditAck() = runBlocking {
        val http = initialize(); edit("Old round edit"); val first = propose(http)
        edit("New round edit"); val second = propose(http)
        val rows = db.syncOutboxDao().getAll().filter { it.recordType == "habit" }
        assertNull(db.nextStructuralCausalDao().dependency(rows.last().operationId)!!.predecessorId)
        storage.reopen(); runtime(http).syncRounds()
        assertEquals(0, count("sync_outbox")); assertEquals("New round edit", current().name)
        assertEquals(3L, acceptedPlan(first).first.getValue("revision").jsonPrimitive.long)
        assertEquals(5L, acceptedPlan(second).first.getValue("revision").jsonPrimitive.long)
    }
    @Test fun pendingRoundTimerStartAndCancelRetainOriginalBirthBeforeNextRestart() = runBlocking {
        val http = initialize(timer = true); val first = propose(http); localStartCancel(); val second = propose(http)
        val start = db.nextRequestDao().origin(NEXT_TIMER, id(871))!!
        val cancel = db.nextRequestDao().origin(NEXT_TIMER, id(872))!!
        assertEquals(first, roundTimerIntent(start.intentJson)!!.restartFrontier)
        assertEquals(first, roundTimerIntent(cancel.intentJson)!!.restartFrontier)
        assertEquals(first.head, roundTimerIntent(cancel.intentJson)!!.context.head)
        assertEquals(60, roundTimerIntent(start.intentJson)!!.timer.command.startPolicy!!.targetSeconds)
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("next_acceptances"))
        storage.reopen(); runtime(http).syncRounds()
        assertEquals(0, count("timer_command_outbox")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("timelogs"))
        assertEquals(first.head, births.single { it.entityUuid == id(870) }.head)
        assertEquals(3L, acceptedPlan(second).first.getValue("revision").jsonPrimitive.long)
        assertEquals(start, db.nextRequestDao().origin(NEXT_TIMER, id(871)))
        assertEquals(cancel, db.nextRequestDao().origin(NEXT_TIMER, id(872)))
        val size = timerWires.size; storage.reopen(); runtime(http).syncRounds(); assertEquals(size, timerWires.size)
    }
    @Test fun damagedRestartOriginRejectsPendingCountReadAndNewWriteWithoutPartialBusiness() = runBlocking {
        val http = initialize(); val ref = propose(http); fact(913, 2)
        val original = db.nextRequestDao().origin(NEXT_OPERATION, ref.operationId)!!
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=CAST(intentJson AS BLOB) WHERE requestId=?", arrayOf(ref.operationId))
        storage.reopen(); val bad = durable()
        rejected { countHistory() }; rejected { fact(914, 3) }; assertEquals(bad, durable())
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE requestId=?", arrayOf(original.intentJson, ref.operationId))
        assertEquals(2L, countHistory().todayQuantity); runtime(http).syncRounds(); assertEquals(0, count("sync_outbox"))
    }
    @Test fun historicalUndoAfterSecondOfflineRestartKeepsOriginalBirthAndNewRoundQuantity() = runBlocking {
        val http = initialize(); val first = propose(http); val old = fact(915, 3)
        val second = propose(http); fact(916, 4)
        producer().writeRounds(producer().captureRounds()) { db.completionDao().delete(old) }
        val undo = db.syncOutboxDao().getAll().single { it.entityUuid == old.uuid && it.action == "delete" }
        val source = roundOperationIntent(originalIntent(undo).intentJson)!!
        assertEquals(first.head, source.context.head); assertEquals(second, source.restartFrontier)
        assertEquals(4L, countHistory().todayQuantity)
        storage.reopen(); runtime(http).syncRounds()
        assertEquals(0, count("sync_outbox")); assertEquals(1, count("completions")); assertEquals(4L, countHistory().todayQuantity)
        assertEquals(first.head, births.single { it.entityUuid == source.operation.entityUuid }.head)
    }
    @Test fun stalePreRestartActionCannotCreateAnyNewRecordOrSource() = runBlocking {
        val http = initialize(); val old = producer().captureRounds(); propose(http)
        val before = durable()
        val row = current()
        val valid = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(917), value = 1,
            date = java.time.LocalDate.parse("2026-10-06").atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
            actualCompletedAt = millis, recordedTimezone = "Asia/Shanghai", recordedLocalDate = "2026-10-06")
        val failure = rejected { producer().writeRounds(old) {
            NextCountDayStore(db).capture(row, valid)
            db.completionDao().insert(valid)
        } }
        assertEquals("SYNC_CHALLENGE_STALE_ACTION", failure.message)
        assertEquals(before, durable()); assertEquals(0, count("completions"))
        assertEquals(0, count("count_days")); fact(917, 1)
        assertEquals(1L, countHistory().todayQuantity); assertEquals(0, count("next_acceptances"))
    }
    @Test fun coldReplacementRejectsChangedRealPlanProofInsteadOfInventingNewRevisionOrAck() = runBlocking {
        val http = initialize(); val ref = propose(http); edit("Bound offline edit")
        runtime(http).syncRounds()
        val source = db.openHelper.writableDatabase.query("SELECT originalId FROM next_structural_supersessions").use { it.moveToFirst(); it.getString(0) }
        val proof = db.nextRestartDao().planProof(ref.operationId)!!
        val changed = JsonObject(json.parseToJsonElement(proof.planJson).jsonObject + ("title" to JsonPrimitive("Altered proof"))).toString()
        db.openHelper.writableDatabase.execSQL("UPDATE next_restart_plan_proofs SET planJson=?,planHash=? WHERE operationId=?",
            arrayOf(changed, syncPayloadHash(changed), ref.operationId))
        storage.reopen(); val bad = durable(); val size = wires.size
        rejected { sender(http).sendAndAcceptOperation(access(), source) }
        assertEquals(bad, durable()); assertEquals(size, wires.size)
        db.openHelper.writableDatabase.execSQL("UPDATE next_restart_plan_proofs SET planJson=?,planHash=? WHERE operationId=?",
            arrayOf(proof.planJson, proof.planHash, ref.operationId))
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), source))
        assertEquals(size, wires.size)
    }
    @Test fun replacementFinalDeferredCommitFailureKeepsOriginalSourceAndNoHalfReplacement() = runBlocking {
        val http = initialize(); val ref = propose(http)
        edit("Atomic new-round edit"); restart(http).sendAndAccept(access(), ref.operationId)
        val merge = merger(http); val state = merge.state(access(), true)!!; val meta = metadata()
        merge.page(access(), state, RoundSyncPullResponse(changes.toList(), cursor, false, time, 1, meta.checkpoints, meta.births))
        val source = db.syncOutboxDao().getAll().single()
        val before = durable(); val size = wires.size
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER restart_replacement_commit_fault AFTER INSERT ON next_transmissions " +
            "WHEN NEW.requestId!='${ref.operationId}' BEGIN INSERT INTO next_restart_plan_proofs " +
            "SELECT '${id(998)}',originHash,transmissionHash,revision,logSequence,deviceId,planJson,planHash FROM next_restart_plan_proofs " +
            "WHERE operationId='${ref.operationId}'; END")
        val failure = rejected { sender(http).sendAndAcceptOperation(access(), source.operationId) }
        assertTrue(failure.toString(), failure.toString().contains("FOREIGN KEY", ignoreCase = true))
        storage.reopen(); assertEquals(before, durable()); assertEquals(size, wires.size)
        assertEquals(0, count("next_structural_supersessions")); assertEquals(source, db.syncOutboxDao().getAll().single())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER restart_replacement_commit_fault")
        runtime(http).syncRounds(); assertEquals(0, count("sync_outbox")); assertEquals("Atomic new-round edit", current().name)
    }

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
