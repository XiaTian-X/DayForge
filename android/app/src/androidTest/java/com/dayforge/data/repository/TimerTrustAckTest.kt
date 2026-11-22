package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustRetirementReason
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual original send/ACK and cold END recovery. The complete TIMER reader is the oracle;
 * read counts separately prove that successful receipt maintenance preserves the issued root.
 */
@RunWith(AndroidJUnit4::class)
class TimerTrustAckTest : NextCoreRequestFixture() {
    private val completeReads = AtomicInteger()
    private val nativeFault = NextTimerRequestNativeFault()
    private val json = Json { encodeDefaults = true }
    private val responsesByCommand = ConcurrentHashMap<String, TimerSessionResponse>()
    private val responsesBySession = ConcurrentHashMap<String, TimerSessionResponse>()
    private val displayTime get() = LocalDate.parse("2026-10-06").atTime(12, 0).atZone(ZoneId.systemDefault())
    @Volatile private var extraSidecar = false
    @Volatile private var extraReplaySidecar = false
    @Volatile private var advanceHead = false
    init {
        storage.queryCallback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (sqlQuery.lowercase().replace("`", "").replace(Regex("\\s+"), "") ==
                    "select*fromtimelogswherehabitid=?orderbystarttimedesc") completeReads.incrementAndGet()
            }
        }
        storage.isolatedBuilder = { context -> buildDiagnosticFaultDatabase(context, nativeFault, storage.queryCallback) }
    }
    @After fun drainOwnedMethodBeforeParentResources() = runBlocking<Unit> { storage.recoveryCleanup { } }
    private fun initial(activity: String) = ChallengeRoundRecord(initialChallengeRoundHead(activity), null, null, null)
    private fun next() = ChallengeRoundRecord(ChallengeRoundHead(timerHabit.uuid, id(44_001), 1), id(4), id(44_002),
        ChallengeRestartIntent(timerHabit.uuid, id(44_001), initialChallengeRoundUuid(timerHabit.uuid), 0, 1))
    private fun metadata(session: String? = null, completed: Boolean = false): ChallengeMetadata {
        val original = initial(timerHabit.uuid)
        val timer = if (advanceHead) ChallengeCheckpoint(next().head, listOf(original, next()))
            else ChallengeCheckpoint(original.head, listOf(original))
        val points = listOf(ChallengeCheckpoint(initial(habit.uuid).head, listOf(initial(habit.uuid))), timer) +
            (if (extraSidecar) listOf(ChallengeCheckpoint(initial(id(44_010)).head, listOf(initial(id(44_010))))) else emptyList()) +
            (if (extraReplaySidecar) listOf(ChallengeCheckpoint(initial(id(44_020)).head, listOf(initial(id(44_020))))) else emptyList())
        val births = session?.let { listOf(ChallengeBirth("timer_session", it, original.head)) +
            (if (completed) listOf(ChallengeBirth("activity_event", it, original.head)) else emptyList()) }.orEmpty() +
            (if (extraSidecar) listOf(ChallengeBirth("timer_session", id(44_011), initial(id(44_010)).head)) else emptyList()) +
            (if (extraReplaySidecar) listOf(ChallengeBirth("activity_event", id(44_021), initial(id(44_020)).head)) else emptyList())
        return ChallengeMetadata(1, points, births)
    }
    private fun timers(http: NextSyncHttp) = NextTimerRequestStore(db, tokens, sessions, sender(http))
    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions,
        OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences),
            timerRequests = timers(http)), timers(http))
    private fun canonical(row: HabitEntity) = SyncV2Change(0, "plan_node", row.uuid, "upsert", 1,
        JsonObject(NextStructureMapper.writePlan(row) + mapOf("public_id" to JsonPrimitive(row.uuid),
            "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
            "deleted_at" to JsonNull)), time)
    private suspend fun initialize(http: NextSyncHttp) {
        register()
        val metricChange = SyncV2Change(0, "metric", metric.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writeMetric(metric) + mapOf("public_id" to JsonPrimitive(metric.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
                "deleted_at" to JsonNull)), time)
        val meta = metadata()
        merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(canonical(habit), canonical(timerHabit), metricChange),
            20, time, emptyList(), 1, meta.checkpoints, meta.births))
        timerHabit = requireNotNull(db.habitDao().getHabitByUuid(timerHabit.uuid))
    }
    private fun respond(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        val formal = input.path == "/api/v2/timers/rounds/commands"
        val command = if (formal) {
            val request = json.decodeFromString<RoundTimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8))
            assertEquals(initial(timerHabit.uuid).head, request.contexts.single().head)
            request.commands.single()
        } else {
            assertEquals("/api/v2/timers/commands", input.path)
            json.decodeFromString<TimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8)).commands.single()
        }
        val timer = responsesByCommand[command.commandId] ?: run {
            val previous = responsesBySession[command.sessionId]
            val policy = command.startPolicy ?: requireNotNull(previous).let {
                TimerStartPolicy(it.targetSeconds, it.isCountdown, it.maxDurationSeconds)
            }
            val state = when (command.commandType) {
                "start", "resume" -> "running"; "pause" -> "paused"; "stop" -> "completed"; "cancel" -> "cancelled"
                else -> error("Unexpected timer test command")
            }
            val elapsed = when (command.commandType) {
                "start" -> 0L
                "resume" -> requireNotNull(previous).activeElapsedMs
                else -> requireNotNull(command.activeElapsedMs)
            }
            TimerSessionResponse(command.sessionId, timerHabit.uuid, state, id(4), 1, command.sequence, command.sequence + 1,
                previous?.startedAt ?: command.occurredAt, command.occurredAt,
                endedAt = command.occurredAt.takeIf { state in setOf("completed", "cancelled") },
                timezone = "Asia/Shanghai", isCountdown = policy.isCountdown, targetSeconds = policy.targetSeconds,
                maxDurationSeconds = policy.maxDurationSeconds, activeElapsedMs = elapsed,
                completedEventId = command.sessionId.takeIf { state == "completed" }).also {
                responsesByCommand[command.commandId] = it
                responsesBySession[command.sessionId] = it
            }
        }
        val result = TimerCommandResult(command.commandId, command.sessionId, "applied", session = timer)
        if (!formal) return MaterialSocketServer.Reply(json.encodeToString(TimerCommandBatchResponse(listOf(result), time)).toByteArray())
        val meta = metadata(command.sessionId, timer.state == "completed")
        return MaterialSocketServer.Reply(json.encodeToString(RoundTimerCommandBatchResponse(listOf(result), time, 1,
            meta.checkpoints, meta.births)).toByteArray())
    }
    private suspend fun startRound(): TimerCommandEntity {
        producer().writeRounds(producer().captureRounds()) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, startTime = millis, endTime = null, durationSeconds = 0,
                date = millis, uuid = id(20), timerNextCommandSequence = 2, timerControlGeneration = 1,
                timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
        }
        return db.timeLogDao().getPendingTimerCommands().single()
    }
    private suspend fun projected() = TimerHistoryReader(db, tokens, sessions).readProjection(timerHabit, displayTime)
    private suspend fun calibrateCompleteReader() {
        completeReads.set(0)
        TimerHistoryReader(db, tokens, sessions).read(timerHabit, displayTime.toLocalDate())
        assertTrue("Counter must observe the original complete DAO history query", completeReads.get() > 0)
        completeReads.set(0)
    }
    private fun dirty() = db.openHelper.writableDatabase.query("SELECT dirty FROM count_trust_control WHERE id=1").use { cursor ->
        assertTrue(cursor.moveToFirst()); cursor.getLong(0).also { assertFalse(cursor.moveToNext()) }
    }
    private fun cleanRoot(diagnostic: String? = null): CountTrustSeal.Header {
        assertEquals(diagnostic, 0L, dirty())
        return requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase))
    }
    private suspend fun exactMain() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }

    @Test(timeout = 150_000) fun formalSendAckAndReplayKeepFullOtherActivitySidecarWithoutReauditingTimers() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        val (http, server) = channel(::respond); initialize(http); val command = startRound(); calibrateCompleteReader()
        val before = projected(); val qualified = cleanRoot(); val local = db.timeLogDao().getTimeLogByUuid(command.sessionUuid)
        val segments = db.timeLogDao().getTimerSegments(command.sessionUuid)
        val allocations = db.timeLogDao().getDayAllocations(command.sessionUuid)
        assertTrue(completeReads.get() > 0); completeReads.set(0); extraSidecar = true
        val store = timers(http); val delivery = requireNotNull(store.send(access(), command.commandId))
        assertEquals(qualified.revision + 1, cleanRoot().revision)
        val sent = cleanRoot()
        extraReplaySidecar = true
        val replayDelivery = requireNotNull(store.send(access(), command.commandId))
        assertEquals(sent, cleanRoot()); assertEquals(delivery.result, replayDelivery.result)
        assertEquals(NextOperationAcceptance.COMMITTED, store.accept(delivery))
        val accepted = cleanRoot(); assertEquals(sent.revision + 1, accepted.revision)
        assertEquals(before, projected()); assertEquals(0, completeReads.get())
        assertEquals(3, db.nextChallengeDao().rounds().size); assertEquals(2, db.nextChallengeDao().births().size)
        assertEquals(initial(id(44_010)).head, requireNotNull(merger(http).challengeMetadata(access()))
            .requireBirth("timer_session", id(44_011), id(44_010)).head)
        assertEquals(local, db.timeLogDao().getTimeLogByUuid(command.sessionUuid))
        assertEquals(segments, db.timeLogDao().getTimerSegments(command.sessionUuid))
        assertEquals(allocations, db.timeLogDao().getDayAllocations(command.sessionUuid)); assertEquals(0, count("timer_command_outbox"))
        val requestCount = server.requests.size
        assertEquals(NextOperationAcceptance.REPLAYED, store.accept(replayDelivery))
        assertEquals(accepted.revision + 1, cleanRoot().revision)
        assertEquals(4, db.nextChallengeDao().rounds().size); assertEquals(3, db.nextChallengeDao().births().size)
        assertEquals(before, projected()); assertEquals(0, completeReads.get()); assertEquals(requestCount, server.requests.size)
        val added = cleanRoot()
        assertEquals(NextOperationAcceptance.REPLAYED, store.accept(replayDelivery))
        assertEquals(added.revision + 1, cleanRoot().revision)
        val replayed = cleanRoot(); storage.reopen(); completeReads.set(0)
        assertEquals(before, projected()); assertEquals(replayed, cleanRoot()); assertEquals(0, completeReads.get())
        assertEquals(NextOperationAcceptance.REPLAYED, timers(http).sendAndAccept(access(), command.commandId))
        assertEquals(requestCount, server.requests.size); assertEquals(before, projected()); assertEquals(0, completeReads.get())
        assertEquals(local, TimerHistoryReader(db, tokens, sessions).read(timerHabit, displayTime.toLocalDate()).activeLog)
    }

    @Test(timeout = 150_000) fun plainSendAndAckPreserveIssuedTimerProofWithoutInventingChallengeMetadata() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        register(); val command = start(); val (http, server) = channel(::respond); calibrateCompleteReader()
        val view = projected(); val before = cleanRoot(); completeReads.set(0)
        val store = timers(http); val delivery = requireNotNull(store.send(access(), command.commandId))
        assertNull(delivery.challengeMetadata); assertEquals(before.revision + 1, cleanRoot().revision)
        assertEquals(NextOperationAcceptance.COMMITTED, store.accept(delivery)); val accepted = cleanRoot()
        assertEquals(view, projected()); assertEquals(0, completeReads.get()); assertEquals(0, count("next_challenge_state"))
        val requests = server.requests.size
        assertEquals(NextOperationAcceptance.REPLAYED, store.sendAndAccept(access(), command.commandId))
        assertEquals(accepted, cleanRoot()); assertEquals(requests, server.requests.size)
        assertEquals(view, projected()); assertEquals(0, completeReads.get()); assertEquals(0, count("timer_command_outbox"))
    }

    @Test(timeout = 150_000) fun repeatedSameValueMarkerRemainsWholeDirtyAndCannotPartiallyResealReplay() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        val (http, _) = channel(::respond); initialize(http); val command = startRound(); calibrateCompleteReader(); projected(); cleanRoot()
        val delivery = requireNotNull(timers(http).send(access(), command.commandId))
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).accept(delivery)); val before = cleanRoot()
        val oldReceipt = db.nextRequestDao().acceptance(NEXT_TIMER, command.commandId)
        val oldLog = db.timeLogDao().getTimeLogByUuid(command.sessionUuid)
        sessions.exclusive {
            db.withTransaction {
                val access = access(); val core = requireNotNull(tokens.localCoreWriteAccess())
                val kernel = CountTrustKernel(db, tokens, sessions)
                kernel.withCapture(core) { captured ->
                    assertNotNull(captured)
                    val origin = requireNotNull(db.nextRequestDao().origin(NEXT_TIMER, command.commandId))
                    val receipt = requireNotNull(oldReceipt)
                    val rounds = NextChallengeStore(db); val checkpoint = rounds.activeInTransaction(access)
                    val input = requireNotNull(delivery.challengeMetadata)
                    val proof = TimerTrustAck(db).prepareAck(captured, access, origin, receipt.originHash,
                        receipt.transmissionHash, delivery.result.results.single().copy(status = "applied"), checkpoint, input)
                    assertNotNull(proof)
                    rounds.acknowledgeInTransaction(access, input)
                    // The real original marker UPDATE already ran. This second same-value DML
                    // must remain a second journal event, although all native contents match.
                    db.openHelper.writableDatabase.execSQL("UPDATE next_challenge_state SET metadataHash=metadataHash WHERE id=1")
                    kernel.finishTimer(captured, proof)
                }
            }
        }
        assertEquals(1L, dirty()); assertEquals(before, CountTrustSeal.read(db.openHelper.writableDatabase))
        assertEquals(oldReceipt, db.nextRequestDao().acceptance(NEXT_TIMER, command.commandId))
        assertEquals(oldLog, db.timeLogDao().getTimeLogByUuid(command.sessionUuid)); assertEquals(0, count("timer_command_outbox"))
        completeReads.set(0); projected(); assertTrue(completeReads.get() > 0); cleanRoot()
        completeReads.set(0); assertEquals(NextOperationAcceptance.REPLAYED, timers(http).accept(delivery)); cleanRoot()
        projected(); assertEquals(0, completeReads.get())
    }

    @Test(timeout = 150_000) fun changedEligibleDisplayHeadUsesCompleteFallbackWithoutRebindingOldActiveControl() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        val (http, _) = channel(::respond); initialize(http); val command = startRound(); calibrateCompleteReader(); projected(); cleanRoot()
        advanceHead = true
        val delivery = requireNotNull(timers(http).send(access(), command.commandId)); val sent = cleanRoot()
        val active = db.timeLogDao().getTimeLogByUuid(command.sessionUuid)
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).accept(delivery))
        assertEquals(1L, dirty()); assertEquals(sent, CountTrustSeal.read(db.openHelper.writableDatabase))
        assertEquals(active, db.timeLogDao().getTimeLogByUuid(command.sessionUuid)); assertEquals(0, count("timer_command_outbox"))
        completeReads.set(0); val current = projected(); assertTrue(completeReads.get() > 0); cleanRoot()
        assertEquals(next().head, current.roundHead)
        val control = requireNotNull(NextTimerWriter(db, tokens, sessions).capture(timerHabit.id))
        assertEquals(initial(timerHabit.uuid).head, control.challengeHead); assertEquals(command.sessionUuid, control.sessionUuid)
        assertEquals(initial(timerHabit.uuid).head, requireNotNull(merger(http).challengeMetadata(access()))
            .requireBirth("timer_session", command.sessionUuid, timerHabit.uuid).head)
    }

    @Test(timeout = 150_000) fun sealedWarmTransitionsAndOrderedLateAcksKeepCompletedStateWhileCancelAckFallsBack() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        val (http, server) = channel(::respond); initialize(http); calibrateCompleteReader(); projected()
        val writer = NextTimerWriter(db, tokens, sessions); val store = timers(http)
        var root = cleanRoot(); completeReads.set(0)
        var localDiagnostic: TimerTrustMaintenanceDiagnostic.Snapshot? = null
        suspend fun maintained(label: String, local: Boolean = false) {
            val detail = if (local) localDiagnostic?.toString() ?: "UNAVAILABLE_OR_OTHER_ATTEMPT" else "NOT_A_LOCAL_TRANSITION"
            val current = cleanRoot("$label: $detail")
            assertEquals("$label must advance exactly the captured root", root.revision + 1, current.revision)
            projected()
            assertEquals("$label must not call the original complete history reader", 0, completeReads.get())
            assertEquals(current, cleanRoot()); root = current
        }
        suspend fun originalOracle() {
            assertEquals(0, completeReads.get())
            val full = TimerHistoryReader(db, tokens, sessions).read(timerHabit, displayTime.toLocalDate())
            assertTrue("Independent original oracle must calibrate the positive counter", completeReads.get() > 0)
            completeReads.set(0)
            assertEquals(projectTimerDisplay(timerHabit, full, displayTime.zone, displayTime.toInstant().toEpochMilli()), projected())
            assertEquals(root, cleanRoot()); assertEquals(0, completeReads.get())
        }
        suspend fun apply(transition: TimerTransition) {
            val ticket = requireNotNull(writer.capture(timerHabit.id))
            val before = TimerTrustMaintenanceDiagnostic.snapshot()?.attempt ?: 0L
            writer.write(timerHabit.id, ticket) { writer.applyTransition(transition) }
            localDiagnostic = TimerTrustMaintenanceDiagnostic.snapshot()?.takeIf {
                it.attempt == before + 1 && it.kind == TimerTrustMaintenanceDiagnostic.kind(transition)
            }
        }
        suspend fun startExact(session: String, command: String, at: Long) {
            apply(TimerTransition.Start(TimeLogEntity(habitId = timerHabit.id, uuid = session, startTime = at,
                date = millis, endTime = null, durationSeconds = 0, timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = at, timerTimezone = "Asia/Shanghai",
                createdAt = at, updatedAt = at),
                TimerCommandEntity(commandId = command, sessionUuid = session, sequence = 1, commandType = "start",
                    occurredAt = at, expectedControlGeneration = 0, activityUuid = timerHabit.uuid,
                    timezone = "Asia/Shanghai", createdAt = at),
                TimerSegmentEntity(sessionUuid = session, sequence = 1, startedAt = at)))
        }
        suspend fun nativeSession(session: String) = Triple(db.timeLogDao().getTimeLogByUuid(session),
            db.timeLogDao().getTimerSegments(session), db.timeLogDao().getDayAllocations(session))

        val session = id(45_100)
        startExact(session, id(45_101), millis); maintained("start", local = true)
        val first = requireNotNull(store.send(access(), id(45_101))); maintained("start send")
        assertEquals(NextOperationAcceptance.COMMITTED, store.accept(first)); maintained("start ACK")
        val nextPauseId = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            assertEquals(0, count("timer_command_outbox"))
            val sequence = sql.query("SELECT seq FROM main.sqlite_sequence WHERE name=?",
                arrayOf("timer_command_outbox")).use { cursor ->
                assertTrue(cursor.moveToFirst()); val value = cursor.getLong(0)
                assertFalse(cursor.moveToNext()); value
            }
            assertTrue(sequence > 0L)
            // The real controlled journal creates a separate TEMP sequence table. The
            // old unqualified query resolves there and cannot see the committed main ID.
            for (schema in listOf("temp.", "")) sql.query("SELECT seq FROM ${schema}sqlite_sequence WHERE name=?",
                arrayOf("timer_command_outbox")).use { assertFalse(it.moveToFirst()) }
            Math.addExact(sequence, 1L)
        }
        val log = requireNotNull(db.timeLogDao().getTimeLogByUuid(session))
        apply(TimerTransition.Pause(log.id, true, millis + 30_000, 0, 3, millis + 30_000, 30_000, null, null,
            TimerCommandEntity(commandId = id(45_102), sessionUuid = session, sequence = 2, commandType = "pause",
                occurredAt = millis + 30_000, expectedControlGeneration = 1, activeElapsedMillis = 30_000)))
        assertEquals(id(45_102), db.timeLogDao().getTimerCommand(nextPauseId)?.commandId)
        maintained("pause", local = true)
        val paused = requireNotNull(store.send(access(), id(45_102))); maintained("pause send")
        // The original real response is deliberately retained while later local transitions commit.
        // No send skips its accepted causal predecessor, and no ACK is made into a local transition.
        apply(TimerTransition.Pause(log.id, false, null, 10_000, 4, millis + 40_000, 30_000, null, null,
            TimerCommandEntity(commandId = id(45_103), sessionUuid = session, sequence = 3, commandType = "resume",
                occurredAt = millis + 40_000, expectedControlGeneration = 1),
            TimerSegmentEntity(sessionUuid = session, sequence = 3, startedAt = millis + 40_000)))
        maintained("resume before pause ACK", local = true)
        apply(TimerTransition.Stop(log.id, millis + 70_000, 60, 10_000, 5, 60_000,
            TimerCommandEntity(commandId = id(45_104), sessionUuid = session, sequence = 4, commandType = "stop",
                occurredAt = millis + 70_000, expectedControlGeneration = 1, activeElapsedMillis = 60_000), false))
        maintained("stop before pause ACK", local = true)
        val completed = nativeSession(session)
        assertEquals(millis + 70_000, requireNotNull(requireNotNull(completed.first).endTime))
        assertEquals(60_000L, completed.third.sumOf { it.durationMillis })
        assertEquals(60_000L, projected().todayMillis); originalOracle()

        assertEquals(NextOperationAcceptance.COMMITTED, store.accept(paused)); maintained("late pause ACK")
        assertEquals(completed, nativeSession(session))
        val resumed = requireNotNull(store.send(access(), id(45_103))); maintained("resume send after pause receipt")
        assertEquals(NextOperationAcceptance.COMMITTED, store.accept(resumed)); maintained("late resume ACK")
        assertEquals(completed, nativeSession(session))
        val stopped = requireNotNull(store.send(access(), id(45_104))); maintained("stop send after resume receipt")
        assertEquals(NextOperationAcceptance.COMMITTED, store.accept(stopped)); maintained("stop ACK")
        assertEquals(completed, nativeSession(session)); assertEquals(0, count("timer_command_outbox"))
        assertEquals(4, count("next_acceptances")); assertNull(db.syncOutboxDao().getState("activity_event", session))
        assertEquals(0, count("completions")); originalOracle()
        assertEquals(NextOperationAcceptance.REPLAYED, store.accept(stopped)); maintained("stop same-value replay")
        assertEquals(completed, nativeSession(session))

        // Local cancel is a supported exact transition. Once it removes its original log,
        // SEND/ACK intentionally have no current-session proof and must keep the whole root dirty.
        val cancelledSession = id(45_200)
        startExact(cancelledSession, id(45_201), millis + 90_000); maintained("second start", local = true)
        val second = requireNotNull(store.send(access(), id(45_201))); maintained("second start send")
        assertEquals(NextOperationAcceptance.COMMITTED, store.accept(second)); maintained("second start ACK")
        val active = requireNotNull(db.timeLogDao().getTimeLogByUuid(cancelledSession))
        apply(TimerTransition.Cancel(active, TimerCommandEntity(commandId = id(45_202), sessionUuid = cancelledSession,
            sequence = 2, commandType = "cancel", occurredAt = millis + 91_000, expectedControlGeneration = 1,
            activeElapsedMillis = 1_000)))
        maintained("local cancel", local = true)
        assertNull(db.timeLogDao().getTimeLogByUuid(cancelledSession))
        val cancelled = requireNotNull(store.send(access(), id(45_202)))
        assertEquals(1L, dirty()); assertEquals(root, CountTrustSeal.read(db.openHelper.writableDatabase))
        assertEquals(0, completeReads.get()); projected(); assertTrue(completeReads.get() > 0)
        root = cleanRoot(); completeReads.set(0)
        assertEquals(NextOperationAcceptance.COMMITTED, store.accept(cancelled))
        assertEquals(1L, dirty()); assertEquals(root, CountTrustSeal.read(db.openHelper.writableDatabase))
        assertEquals(0, completeReads.get()); projected(); assertTrue(completeReads.get() > 0)
        root = cleanRoot(); completeReads.set(0)
        assertEquals(completed, nativeSession(session)); assertNull(nativeSession(cancelledSession).first)
        assertTrue(nativeSession(cancelledSession).second.isEmpty()); assertTrue(nativeSession(cancelledSession).third.isEmpty())
        assertEquals(0, count("timer_command_outbox")); assertEquals(6, count("next_acceptances"))
        assertEquals(6, server.requests.count { it.path.endsWith("/commands") })
        assertEquals(60_000L, projected().todayMillis); originalOracle()
    }

    @Test(timeout = 150_000) fun actualDeferredEndRollsBackSidecarReceiptQueueAndNewRootBeforeColdExactRetry() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        val (http, _) = channel(::respond); initialize(http); val command = startRound(); calibrateCompleteReader(); projected(); cleanRoot()
        val delivery = requireNotNull(timers(http).send(access(), command.commandId)); val sent = cleanRoot()
        nativeFault.installOnNextOpen(); storage.reopen(); projected(); assertEquals(sent, cleanRoot())
        val before = exactMain(); val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        nativeFault.arm(NextTimerRequestNativeFault.Kind.DEFERRED_END,
            NextTimerRequestNativeFault.Targets(command.commandId, command.sessionUuid, command.id))
        val failure = rejected { timers(http).accept(delivery) }
        nativeFault.requireTriggered(failure)
        assertTrue(owner.isRetired); assertTrue(CountTrustRetirementReason.UNKNOWN_END in owner.retirementReasons)
        storage.reopen(); assertEquals(before, exactMain()) // Opens this new lazy Room before inspecting its native connection.
        nativeFault.requireColdFaultAbsent(); assertEquals(sent, cleanRoot())
        assertEquals(command, db.timeLogDao().getTimerCommand(command.id)); assertNull(db.nextRequestDao().acceptance(NEXT_TIMER, command.commandId))
        assertEquals(0, count("next_challenge_births")); assertNotSame(owner, CountTrustRuntime.forDatabase(db))
        completeReads.set(0)
        assertEquals(NextOperationAcceptance.COMMITTED, timers(http).accept(delivery)); cleanRoot()
        projected(); assertEquals(0, completeReads.get())
        assertEquals(0, count("timer_command_outbox")); assertEquals(1, count("next_challenge_births"))
        assertEquals(NextOperationAcceptance.REPLAYED, timers(http).accept(delivery)); cleanRoot()
    }
}
