package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production round producers/editors and real Room, not hand-built day/frontier metadata. */
@RunWith(AndroidJUnit4::class)
class NextRecoveryCapturedFrontierTest : NextObjectEditorFixture() {
    private lateinit var goal: HabitEntity
    private val json = Json { encodeDefaults = true }
    private var allowOperationReplies = false
    private var normalizedTarget: Int? = null
    private val births = mutableListOf<ChallengeBirth>()
    private fun metadata() = ChallengeMetadata(1, listOf(habit, timerHabit).map {
        val first = ChallengeRoundRecord(initialChallengeRoundHead(it.uuid), null, null, null)
        ChallengeCheckpoint(first.head, listOf(first))
    }, births.toList())
    private fun respond(input: MaterialSocketServer.Input): MaterialSocketServer.Reply? {
        if (input.path.endsWith("/identity")) return reply(input)
        if (!allowOperationReplies) return null
        val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
        val operation = request.operations.single()
        if (operation.entityType == "activity_event" && births.none { it.entityUuid == operation.entityUuid })
            births += ChallengeBirth("activity_event", operation.entityUuid, requireNotNull(request.contexts.single().head))
        val result = json.decodeFromString<NextSyncPushResponse>(successReply(input, (operation.baseRevision ?: 0) + 1) { body ->
            val target = normalizedTarget
            if (target != null && operation.entityType == "plan_node" && operation.entityUuid == habit.uuid)
                JsonObject(body + ("activity" to JsonObject(body.getValue("activity").jsonObject + ("target_value" to JsonPrimitive(target)))))
            else body
        }.bytes.toString(Charsets.UTF_8))
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(result.results, 1, meta.checkpoints, meta.births)).toByteArray())
    }
    private suspend fun initialize(finite: Boolean = false): Pair<NextSyncHttp, MaterialSocketServer> {
        register()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1")
            if (finite) { habit = habit.copy(isActive = false, targetCycles = 3); db.habitDao().update(habit) }
            val root = habit.copy(id = 0, uuid = id(600), name = "Goal", habitType = HabitType.GOAL,
                isActive = true, targetValue = 1, targetCycles = null, completionPolicy = null,
                appearance = ObjectAppearance(IconReference.Role("goal.default"), "#123456", "theme"),
                planMetadata = requireNotNull(habit.planMetadata).copy(timezone = null))
            goal = root.copy(id = db.habitDao().insert(root))
            habit = habit.copy(parentHabitId = goal.uuid); db.habitDao().update(habit)
            timerHabit = timerHabit.copy(parentHabitId = goal.uuid); db.habitDao().update(timerHabit)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0")
        }
        val (http, server) = channel(::respond)
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        val merge = NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
        fun canonical(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
            JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
                "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        val meta = metadata()
        merge.bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(goal, habit, timerHabit).map {
            canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it)) } + canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)),
            20, time, emptyList(), 1, meta.checkpoints, meta.births))
        return http to server
    }
    private suspend fun read(key: NextRecoveryKey) = db.withTransaction { NextRecoveryCapturedFrontierStore(db).pending(access(), key) }
    private suspend fun read(row: SyncOutboxEntity) = read(NextRecoveryKey(NEXT_OPERATION, row.operationId))
    private suspend fun read(row: TimerCommandEntity) = read(NextRecoveryKey(NEXT_TIMER, row.commandId))
    private suspend fun edit(activity: HabitEntity, name: String): SyncOutboxEntity {
        val draft = editor.habit(activity.id)
        editingHabits().updateHabit(requireNotNull(draft.value).copy(name = name), editAuthority = draft.authority)
        return db.syncOutboxDao().getAll().last()
    }
    private suspend fun count(value: Int): SyncOutboxEntity {
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        producer().writeRounds(producer().captureRounds()) {
            val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, actualCompletedAt = millis,
                date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()
                    .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                value = value, recordedTimezone = "Asia/Shanghai")
            NextCountDayStore(db).capture(current, fact); db.completionDao().insertForSync(fact)
        }
        return db.syncOutboxDao().getAll().last()
    }
    private suspend fun start(): TimerCommandEntity {
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, startTime = millis, endTime = null,
                durationSeconds = 0, date = millis, uuid = id(20), timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
        }
        return db.timeLogDao().getPendingTimerCommands().single()
    }
    private suspend fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons)
    }
    @Test fun firstCountUsesOriginalPlanAndLaterCountUsesRealFirstRequestWithoutRewritingTheDay() = runBlocking<Unit> {
        val (_, server) = initialize(); val plan = edit(habit, "Before count"); val first = count(6)
        val initial = read(first); val frontier = requireNotNull(initial.count)
        assertEquals(CountDayPolicy(10, false), frontier.policy); assertEquals(first.operationId, frontier.first!!.requestId)
        assertEquals(first.id - 1, frontier.planQueueWatermark)
        assertEquals(listOf(NextRecoveryKey(NEXT_OPERATION, plan.operationId)), initial.dependencies.map { it.predecessor })
        assertEquals(NextRecoveryDependencyReason.COUNT_POLICY, initial.dependencies.single().reason)
        producer().writeRounds(producer().captureRounds()) { db.habitDao().update(requireNotNull(db.habitDao().getHabitById(habit.id)).copy(targetValue = 15)) }
        val second = count(1); val later = read(second)
        assertEquals(frontier, later.count); assertEquals(NextRecoveryKey(NEXT_OPERATION, first.operationId), later.dependencies.single().predecessor)
        assertEquals(NextRecoveryDependencyReason.FIRST_COUNT_FACT, later.dependencies.single().reason)
        assertEquals(setOf(NextRecoveryProtection.REAL_FACT, NextRecoveryProtection.COUNT_DAY), later.protections)
        val rows = db.syncOutboxDao().getAll(); val prefs = dataStore.data.first()
        reopen(); assertEquals(initial.evidenceHash, read(first).evidenceHash); assertEquals(later.evidenceHash, read(second).evidenceHash)
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(prefs, dataStore.data.first())
        assertEquals(0, server.requests.size); assertEquals(0, count("next_transmissions"))
    }
    @Test fun timerStartKeepsItsOriginalPolicyAndWatermarkDespiteLaterPlanEditAndColdReopen() = runBlocking<Unit> {
        val (_, server) = initialize(); val plan = edit(timerHabit, "Before start"); val command = start()
        val result = read(command); val timer = requireNotNull(result.timer)
        assertEquals(TimerStartPolicy(60, false, 180), timer.policy); assertEquals(plan.id, timer.planQueueWatermark)
        assertEquals(NextRecoveryKey(NEXT_OPERATION, plan.operationId), result.dependencies.single().predecessor)
        assertEquals(NextRecoveryDependencyReason.TIMER_POLICY, result.dependencies.single().reason)
        producer().writeRounds(producer().captureRounds()) { db.habitDao().update(requireNotNull(db.habitDao().getHabitById(timerHabit.id)).copy(targetValue = 2)) }
        val rows = db.syncOutboxDao().getAll(); val local = db.timeLogDao().getTimeLogByUuid(command.sessionUuid)
        reopen(); assertEquals(result.evidenceHash, read(command).evidenceHash); assertEquals(timer, read(command).timer)
        assertEquals(rows, db.syncOutboxDao().getAll()); assertEquals(local, db.timeLogDao().getTimeLogByUuid(command.sessionUuid))
        assertEquals(listOf(command), db.timeLogDao().getPendingTimerCommands()); assertEquals(0, server.requests.size)
    }
    @Test fun pauseRetainsOriginalSessionAndSequenceButDoesNotInventAStartOrPreviousAck() = runBlocking<Unit> {
        initialize(); val first = start(); val log = requireNotNull(db.timeLogDao().getTimeLogByUuid(first.sessionUuid))
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().updatePauseAndQueue(log.id, true, millis + 30_000, 0, 3, millis + 30_000, 30_000, null, null,
                TimerCommandEntity(commandId = id(22), sessionUuid = first.sessionUuid, sequence = 2, commandType = "pause",
                    occurredAt = millis + 30_000, expectedControlGeneration = 1, activeElapsedMillis = 30_000))
        }
        val paused = db.timeLogDao().getPendingTimerCommands().last(); val result = read(paused)
        assertEquals(NextRecoveryTimerFrontier(first.sessionUuid, timerHabit.uuid, 2, "pause", null, null), result.timer)
        assertTrue(result.dependencies.isEmpty()); assertEquals(NextRecoveryRequestState.NEVER_SENT, result.state)
        assertEquals(0, count("next_acceptances")); assertEquals(2, db.timeLogDao().getPendingTimerCommands().size)
    }
    @Test fun detachedGoalStillRetainsEveryOriginalChildFrontierWithoutParentHttpOrDeletion() = runBlocking<Unit> {
        val (_, server) = initialize(); val ticket = requireNotNull(editor.habit(goal.id).authority)
        editor.deleteHabit(requireNotNull(db.habitDao().getHabitById(goal.id)), "detach_children", ticket)
        val parent = db.syncOutboxDao().getAll().single { it.entityUuid == goal.uuid }; val rows = db.syncOutboxDao().getAll()
        assertTrue(db.habitDao().getChildrenByParentUuidOnce(goal.uuid).isEmpty())
        val result = read(parent)
        assertEquals(rows.filter { it.entityUuid != goal.uuid }.map { NextRecoveryKey(NEXT_OPERATION, it.operationId) }.toSet(),
            result.dependencies.map { it.predecessor }.toSet())
        assertTrue(result.dependencies.all { it.reason == NextRecoveryDependencyReason.GOAL_CHILD_FRONTIER && it.successor == result.key })
        assertNotNull(db.habitDao().getHabitById(goal.id)); reopen()
        assertEquals(result.evidenceHash, read(parent).evidenceHash); assertEquals(rows, db.syncOutboxDao().getAll())
        assertEquals(0, server.requests.size); assertEquals(0, count("next_recovery_audits"))
        assertTrue(rejected { (result.dependencies as MutableList<NextRecoveryDependency>).clear() } is UnsupportedOperationException)
    }
    @Test fun damagedDayAndReferencedPlanCannotBeSilentlyTreatedAsNoDependency() = runBlocking<Unit> {
        initialize(); val plan = edit(habit, "Before count"); val first = count(2); val proof = read(first)
        val sql = db.openHelper.writableDatabase; val source = originalIntent(plan)
        sql.execSQL("UPDATE count_days SET targetValue=11"); rejected { read(first) }
        sql.execSQL("UPDATE count_days SET targetValue=10")
        sql.execSQL("UPDATE next_request_origins SET protocol=5.5 WHERE requestId=?", arrayOf(plan.operationId)); rejected { read(first) }
        sql.execSQL("UPDATE next_request_origins SET protocol=? WHERE requestId=?", arrayOf<Any>(source.protocol, plan.operationId))
        assertEquals(proof.evidenceHash, read(first).evidenceHash); assertEquals(1, count("count_days")); assertEquals(1, count("completions"))
        assertEquals(2, count("sync_outbox")); assertEquals(0, count("next_acceptances"))
    }
    @Test fun accountEpochAndDeviceChangesRefuseButPermissionLossDoesNotEraseCapturedEvidence() = runBlocking<Unit> {
        initialize(); edit(timerHabit, "Before start"); val row = start(); val result = read(row); val current = access()
        for (changed in listOf(current.copy(deviceId = id(99)), current.copy(session = current.session.copy(syncEpoch = id(99))),
            current.copy(session = current.session.copy(authentication = current.session.authentication.copy(userId = id(99)))))) {
            rejected { db.withTransaction { NextRecoveryCapturedFrontierStore(db).pending(changed, result.key) } }
        }
        register(permissions = setOf("sync.read"), revision = 2)
        assertEquals(result.evidenceHash, read(row).evidenceHash); assertEquals(listOf(row), db.timeLogDao().getPendingTimerCommands())
    }
    @Test fun actualLostTimerResponseRemainsUnknownAndPreservesItsFrozenBytesDuringRead() = runBlocking<Unit> {
        val (http, server) = initialize(); val row = start()
        rejected { NextTimerRequestStore(db, tokens, sessions, sender(http)).send(access(), row.commandId) }
        val bytes = transmission(NEXT_TIMER, row.commandId).wireBytes.copyOf(); val calls = server.requests.size
        assertEquals(NextRecoveryRequestState.UNKNOWN_RESULT, read(row).state)
        reopen(); assertEquals(NextRecoveryRequestState.UNKNOWN_RESULT, read(row).state)
        assertArrayEquals(bytes, transmission(NEXT_TIMER, row.commandId).wireBytes)
        assertEquals(calls, server.requests.size); assertEquals(0, count("next_acceptances")); assertEquals(row, db.timeLogDao().getTimerCommand(row.id))
    }
    @Test fun newRoundEditKeepsActualPendingRestartFrontierWithoutGrantingItsAcceptance() = runBlocking<Unit> {
        val (http, server) = initialize(finite = true); val draft = editor.habit(habit.id)
        val ref = NextChallengeRestartRepository(db, tokens, sessions, http)
            .restart(requireNotNull(draft.value), requireNotNull(draft.authority))
        val row = edit(habit, "New-round edit"); val result = read(row)
        assertEquals(NextRecoveryKey(NEXT_OPERATION, ref.operationId), result.dependencies.single().predecessor)
        assertEquals(NextRecoveryDependencyReason.RESTART_FRONTIER, result.dependencies.single().reason)
        assertEquals(setOf(NextRecoveryProtection.ROUND_STATE), result.protections)
        assertEquals(NextRecoveryRequestState.NEVER_SENT, result.state)
        assertNotNull(db.syncOutboxDao().getByOperationId(ref.operationId)); assertEquals(0, count("next_acceptances"))
        val rows = db.syncOutboxDao().getAll(); reopen()
        assertEquals(result.evidenceHash, read(row).evidenceHash); assertEquals(rows, db.syncOutboxDao().getAll())
        assertEquals(0, server.requests.size)
    }
    private suspend fun firstCountAfterReplacement(target: Int?) {
        val (http, server) = initialize(); val a = edit(habit, "A"); val b = edit(habit, "B")
        allowOperationReplies = true; normalizedTarget = target
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), a.operationId))
        normalizedTarget = null; allowOperationReplies = false
        rejected { sender(http).sendOperation(access(), b.operationId) }
        val alias = requireNotNull(db.nextStructuralCausalDao().supersession(b.operationId))
        assertNotEquals(b.operationId, alias.replacementId)
        assertNull(db.nextStructuralCausalDao().dependency(alias.replacementId))
        val frozen = transmission(NEXT_OPERATION, alias.replacementId).wireBytes.copyOf()
        val first = count(2) // Must remain usable offline while the exact structural result is unknown.
        val original = originalIntent(first)
        val captured = requireNotNull(countPlanFrontier(original.intentJson))
        assertEquals(b.operationId, captured.logicalId); assertEquals(alias.replacementId, captured.actualId)
        assertEquals(if (target == null) alias.replacementId else b.operationId, captured.policySourceId)
        val proof = read(first)
        assertEquals(CountDayPolicy(10, false), requireNotNull(proof.count).policy)
        assertEquals(NextRecoveryKey(NEXT_OPERATION, b.operationId), proof.dependencies.single().predecessor)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendOperation(access(), first.operationId) } as NextRequestException).reason)
        reopen(); assertEquals(proof.evidenceHash, read(first).evidenceHash)
        assertArrayEquals(frozen, transmission(NEXT_OPERATION, alias.replacementId).wireBytes)
        if (target != null) {
            val sql = db.openHelper.writableDatabase
            for ((table, field, old) in listOf(Triple("next_structural_supersessions", "originalDependencyHash", alias.originalDependencyHash),
                Triple("next_request_origins", "accountId", original.accountId))) {
                val where = if (table == "next_request_origins") "kind=? AND requestId=?" else "replacementId=?"
                val args = if (table == "next_request_origins") arrayOf(NEXT_OPERATION, alias.replacementId) else arrayOf(alias.replacementId)
                sql.execSQL("UPDATE $table SET $field=? WHERE $where", arrayOf("damaged", *args))
                rejected { read(first) }; rejected { count(1) }
                assertEquals(1, count("completions")); assertEquals(1, count("count_days"))
                sql.execSQL("UPDATE $table SET $field=? WHERE $where", arrayOf(old, *args))
                assertEquals(proof.evidenceHash, read(first).evidenceHash)
            }
            val body = Json.parseToJsonElement(original.intentJson).jsonObject
            for (bad in listOf(JsonNull, JsonPrimitive(true), JsonObject(body.getValue("count_plan_frontier").jsonObject +
                ("requestId" to JsonPrimitive(id(998))))))
                rejected { decodeNextOperationIntent(JsonObject(body + ("count_plan_frontier" to bad)).toString()) }
        }
        allowOperationReplies = true
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), alias.replacementId))
        if (target == null) assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), first.operationId))
        else {
            assertEquals(NextRequestException.Reason.COUNT_START_CONFIG_CHANGED,
                (rejected { sender(http).sendAndAcceptOperation(access(), first.operationId) } as NextRequestException).reason)
            assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, first.operationId))
        }
        assertEquals(original, originalIntent(first))
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val history = CountHistoryReader(db, tokens, sessions).read(current, java.time.LocalDate.parse("2026-10-06"))
        assertEquals(CountDayPolicy(10, false), history.todayPolicy); assertEquals(2L, history.todayQuantity)
        assertTrue(server.requests.filter { it.path.endsWith("/push") }.all {
            "count_plan_frontier" !in it.body.toString(Charsets.UTF_8)
        })
        assertEquals(1, count("completions")); assertEquals(1, count("count_days"))
    }
    @Test fun firstOfflineCountAfterUnknownStructuralReplacementPreservesLogicalAndActualProof() = runBlocking<Unit> {
        firstCountAfterReplacement(null)
    }
    @Test fun firstOfflineCountAfterReplacementKeepsDisplayedRuleAndBlocksDifferentAcceptedTarget() = runBlocking<Unit> {
        firstCountAfterReplacement(8)
    }

    @Test fun firstCountBornBeforeReplacementNeverAdoptsLaterAliasOrChangedTarget() = runBlocking<Unit> {
        val (http, _) = initialize(); val a = edit(habit, "A"); val b = edit(habit, "B"); val first = count(2)
        val original = originalIntent(first); assertNull(countPlanFrontier(original.intentJson))
        val day = requireNotNull(read(first).count)
        allowOperationReplies = true; normalizedTarget = 8
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), a.operationId))
        normalizedTarget = null; allowOperationReplies = false
        rejected { sender(http).sendOperation(access(), b.operationId) }
        val alias = requireNotNull(db.nextStructuralCausalDao().supersession(b.operationId))
        assertEquals(day, read(first).count); assertEquals(original, originalIntent(first))
        reopen(); assertEquals(day, read(first).count); assertNull(countPlanFrontier(originalIntent(first).intentJson))
        allowOperationReplies = true
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), alias.replacementId))
        assertEquals(NextRequestException.Reason.COUNT_START_CONFIG_CHANGED,
            (rejected { sender(http).sendOperation(access(), first.operationId) } as NextRequestException).reason)
        assertEquals(day, read(first).count); assertEquals(original, originalIntent(first))
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, first.operationId))
    }
}
