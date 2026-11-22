package com.dayforge.data.repository

import androidx.room.RoomDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original socket SEND/ACK paths and both replay entries. Warm-read counters are distinct
 * from the retained formal full-MAIN protection: this does not claim all H work disappeared.
 */
@RunWith(AndroidJUnit4::class)
class OnceTrustAckTest : NextCoreRequestFixture() {
    private val historyReads = AtomicInteger()
    private val wholeMainReads = AtomicInteger()
    private val json = Json { encodeDefaults = true }
    private var formal = false
    private var extraMetadata = false
    private var rejectResponse = false
    init { storage.queryCallback = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            val text = sqlQuery.lowercase().replace("`", "").replace(Regex("\\s+"), "")
            if (text == "select*fromcompletionswherehabitid=?orderbyid") historyReads.incrementAndGet()
            if (text.startsWith("select*frommain.") && text.endsWith("orderbyrowid")) wholeMainReads.incrementAndGet()
        }
    }
        storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, storage.queryCallback) }
    }
    private fun localOnce() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun accepted() = OneTimeAcceptedEventStore(db, tokens, sessions, localOnce())
    private fun store(http: NextSyncHttp) = NextOneTimeRequestStore(db, tokens, sessions, http, accepted(), sender(http))
    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions, accepted(),
        NextTimerRequestStore(db, tokens, sessions, sender(http)))
    private fun initial(uuid: String) = ChallengeRoundRecord(initialChallengeRoundHead(uuid), null, null, null)
    private fun metadata(): ChallengeMetadata {
        val base = initial(timerHabit.uuid)
        val extra = initial(id(71_000))
        return ChallengeMetadata(1, listOf(ChallengeCheckpoint(base.head, listOf(base))) +
            if (extraMetadata) listOf(ChallengeCheckpoint(extra.head, listOf(extra))) else emptyList(), emptyList())
    }
    private fun canonical(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private suspend fun initialize(http: NextSyncHttp, rounds: Boolean) {
        formal = rounds
        register()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(habitType = HabitType.CHECK_IN, schedule = HabitSchedule.Once(), targetValue = 1,
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        if (rounds) {
            val plans = db.habitDao().getVisibleHabitsOnce()
            val meta = metadata()
            merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(plans.map {
                canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it))
            } + canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)), 20, time,
                listOf(OneTimeProjection(habit.uuid, OneTimeState(0, null, null))), 1, meta.checkpoints, meta.births))
        }
        sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOnceCapture(tokens.localCoreWriteAccess(), prepare = true) { capture ->
                assertNotNull("Explicit original writer preparation must issue ONCE coverage", capture)
            }
        } }
        cleanRoot()
    }
    private suspend fun append(n: Int): SyncOutboxEntity {
        val before = localOnce().read(habit.uuid)
        val state = before.queue.optimisticState
        val command = OneTimeLocalCommand(habit.uuid, PendingOneTimeIntent(id(n), OneTimeIntent(id(n + 1000),
            if (state.completionEventUuid == null) "complete" else "undo", state.version, state.headEventUuid,
            state.completionEventUuid)), millis + n, "Asia/Shanghai")
        if (formal) localOnce().appendRounds(producer().captureRounds(), command)
        else localOnce().append(before.session, command)
        return requireNotNull(db.syncOutboxDao().getByOperationId(id(n)))
    }
    private fun respond(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        val ordinary = if (rejectResponse) {
            val op = wireOperation(input)
            NextSyncPushResponse(listOf(NextSyncOperationResult(op.getValue("operation_id").jsonPrimitive.content,
                "activity_event", op.getValue("entity_uuid").jsonPrimitive.content, "rejected", errorCode = "INVALID_PAYLOAD")))
        } else json.decodeFromString<NextSyncPushResponse>(successReply(input) { fact ->
            val intent = fact.getValue("one_time").jsonObject
            JsonObject(fact + ("one_time_state_after" to buildJsonObject {
                put("version", intent.getValue("expected_version").jsonPrimitive.int + 1)
                put("head_event_uuid", intent.getValue("event_uuid"))
                put("completion_event_uuid", if (intent.getValue("action") == JsonPrimitive("complete")) intent.getValue("event_uuid") else JsonNull)
            }))
        }.bytes.toString(Charsets.UTF_8))
        return if (!formal) MaterialSocketServer.Reply(json.encodeToString(ordinary).toByteArray()) else {
            val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
            assertNull(request.contexts.single().head); assertTrue(request.contexts.single().affectedHeads.isEmpty())
            val meta = metadata()
            MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(ordinary.results, 1,
                meta.checkpoints, meta.births)).toByteArray())
        }
    }
    private fun dirty(): Long = db.openHelper.writableDatabase.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private fun cleanRoot(): CountTrustSeal.Header {
        assertEquals(0L, dirty())
        return requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase))
    }
    private suspend fun exactMain() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun warm(phase: String): OneTimeLocalSnapshot {
        val root = cleanRoot()
        historyReads.set(0)
        val actual = localOnce().read(habit.uuid)
        assertEquals("$phase current read must not rescan retained facts", 0, historyReads.get())
        assertEquals(root, cleanRoot())
        val expected = sessions.exclusive { db.withTransaction {
            OnceTrustEvidence.audit(db, habit.uuid, requireNotNull(tokens.localFactAccess()).session).snapshot
        } }
        assertTrue("Independent full oracle calibrates the history observer ($phase)", historyReads.get() > 0)
        assertEquals(expected, actual)
        return actual
    }
    private fun committed() = NextOneTimeOutcome.Accepted(NextOperationAcceptance.COMMITTED)
    private fun replayed() = NextOneTimeOutcome.Accepted(NextOperationAcceptance.REPLAYED)

    @Test(timeout = 150_000) fun completeUndoCompleteSendAndAckRetainWarmStateAndColdExactReplay() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        val (http, server) = channel(::respond); initialize(http, rounds = false)
        val rows = List(3) { append(70_100 + it).also { warm("append") } }
        rows.forEachIndexed { index, queue ->
            val source = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, queue.operationId))
            val birth = requireNotNull(db.completionFollowUpDao().submission(queue.operationId))
            val delivery = requireNotNull(store(http).send(access(), queue.operationId)); warm("send")
            assertEquals(queue, db.syncOutboxDao().getById(queue.id))
            assertEquals(source, db.nextRequestDao().origin(NEXT_OPERATION, queue.operationId))
            assertEquals(birth, db.completionFollowUpDao().submission(queue.operationId))
            assertEquals(committed(), store(http).accept(delivery))
            val snapshot = warm("ACK")
            assertEquals(index + 1, snapshot.confirmed.version)
            assertEquals(3, snapshot.queue.optimisticState.version)
            assertEquals(rows.drop(index + 1), db.syncOutboxDao().getActivityIntents(habit.uuid))
            assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, queue.operationId))
        }
        val root = cleanRoot(); val before = exactMain(); val requests = server.requests.size
        storage.reopen(); assertEquals(root, cleanRoot()); warm("cold current read")
        rows.forEach { assertEquals(replayed(), store(http).sendAndAccept(access(), it.operationId)) }
        assertEquals(requests, server.requests.size); assertEquals(before, exactMain())
        assertEquals(3, count("completions")); assertEquals(3, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
    }

    @Test(timeout = 150_000) fun formalDeliveryReplayKeepsMarkerButSavedSendEntryRemainsReadOnly() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        val (http, server) = channel(::respond); initialize(http, rounds = true)
        val queue = append(70_200); val delivery = requireNotNull(store(http).send(access(), queue.operationId))
        wholeMainReads.set(0); historyReads.set(0)
        val firstStarted = android.os.SystemClock.elapsedRealtimeNanos()
        assertEquals(committed(), store(http).accept(delivery))
        android.util.Log.i("OnceTrustAckCost", "phase=formal_first_ack full_main_queries=${wholeMainReads.get()} " +
            "full_history_queries=${historyReads.get()} elapsed_ns=${android.os.SystemClock.elapsedRealtimeNanos() - firstStarted}")
        warm("formal ACK")
        assertTrue("Original formal full-MAIN protection remains measured", wholeMainReads.get() > 0)
        val first = cleanRoot(); val facts = db.completionDao().getByHabitOnce(habit.id)
        wholeMainReads.set(0); historyReads.set(0)
        val replayStarted = android.os.SystemClock.elapsedRealtimeNanos()
        assertEquals(replayed(), store(http).accept(delivery))
        android.util.Log.i("OnceTrustAckCost", "phase=formal_saved_delivery full_main_queries=${wholeMainReads.get()} " +
            "full_history_queries=${historyReads.get()} elapsed_ns=${android.os.SystemClock.elapsedRealtimeNanos() - replayStarted}")
        warm("formal saved delivery")
        val replay = cleanRoot()
        assertEquals(first.generation + 1, replay.generation)
        assertEquals(facts, db.completionDao().getByHabitOnce(habit.id))
        assertTrue(wholeMainReads.get() > 0)
        val before = exactMain(); val requests = server.requests.size
        wholeMainReads.set(0)
        assertEquals(replayed(), store(http).sendAndAccept(access(), queue.operationId))
        assertEquals(requests, server.requests.size); assertEquals(0, wholeMainReads.get())
        assertEquals(replay, cleanRoot()); assertEquals(before, exactMain())
        assertEquals(0, count("next_challenge_births")); assertEquals(1, count("next_acceptances"))
    }

    @Test(timeout = 150_000) fun lateCompleteAckCannotOverwriteAlreadyCommittedUndoOrReplaceFrozenSources() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        val (http, _) = channel(::respond); initialize(http, rounds = true)
        val complete = append(70_300); val delivery = requireNotNull(store(http).send(access(), complete.operationId))
        val undo = append(70_301); val source = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, undo.operationId))
        assertEquals(committed(), store(http).accept(delivery))
        val after = warm("late complete ACK")
        assertEquals(1, after.confirmed.version); assertEquals(2, after.queue.optimisticState.version)
        assertNull(after.queue.optimisticState.completionEventUuid)
        assertEquals(undo, db.syncOutboxDao().getById(undo.id)); assertEquals(source, db.nextRequestDao().origin(NEXT_OPERATION, undo.operationId))
        assertEquals(committed(), store(http).sendAndAccept(access(), undo.operationId)); warm("undo ACK")
        assertEquals(2, count("completions")); assertEquals(2, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
    }

    @Test(timeout = 150_000) fun rejectedHeadMaintainsBlockedSuffixAndReadOnlySavedRejection() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        val (http, server) = channel(::respond); initialize(http, rounds = false)
        val first = append(70_400); val second = append(70_401)
        rejectResponse = true
        val result = store(http).sendAndAccept(access(), first.operationId)
        assertTrue(result is NextOneTimeOutcome.Rejected)
        val snapshot = warm("rejection")
        assertEquals(listOf(first.operationId, second.operationId), snapshot.queue.blockedOperationIds)
        assertTrue(snapshot.queue.awaitingReplayOperationIds.isEmpty())
        assertEquals(0, count("next_acceptances")); assertEquals(2, count("completions"))
        val before = exactMain(); val requests = server.requests.size
        assertEquals(result, store(http).sendAndAccept(access(), first.operationId))
        assertEquals(before, exactMain()); assertEquals(requests, server.requests.size)
        assertEquals(second, db.syncOutboxDao().getById(second.id)); warm("saved rejection")
    }

    @Test(timeout = 150_000) fun legitimateAdditionalFormalMetadataKeepsOriginalAckAndExplicitDirtyFallback() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        val (http, _) = channel(::respond); initialize(http, rounds = true)
        val queue = append(70_500); extraMetadata = true
        val delivery = requireNotNull(store(http).send(access(), queue.operationId)); val sent = cleanRoot()
        assertEquals(committed(), store(http).accept(delivery))
        assertEquals(1L, dirty()); assertEquals(sent, CountTrustSeal.read(db.openHelper.writableDatabase))
        assertNull(db.syncOutboxDao().getById(queue.id)); assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, queue.operationId))
        assertEquals(2, db.nextChallengeDao().rounds().size)
        historyReads.set(0); val actual = localOnce().read(habit.uuid)
        assertTrue(historyReads.get() > 0); assertEquals(1, actual.confirmed.version)
        assertEquals(1L, dirty()) // A read does not silently prepare/reseal the optional root.
    }
}
