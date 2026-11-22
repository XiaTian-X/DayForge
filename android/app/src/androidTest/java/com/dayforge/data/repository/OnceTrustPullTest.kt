package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.NextSyncStateEntity
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

/** Real page owners and one actual upload -> PULL runtime. Existing cache/shadow scans remain
 * measured protection; warm ONCE reads are checked independently against the complete oracle.
 */
@RunWith(AndroidJUnit4::class)
class OnceTrustPullTest : NextCoreRequestFixture() {
    private val historyReads = AtomicInteger()
    private val protectedTableScans = AtomicInteger()
    private val json = Json { encodeDefaults = true }
    @Volatile private var formal = false
    init { storage.queryCallback = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            val text = sqlQuery.lowercase().replace("`", "").replace(Regex("\\s+"), "")
            if (text == "select*fromcompletionswherehabitid=?orderbyid") historyReads.incrementAndGet()
            if (text.startsWith("select*from") && text.endsWith("orderbyrowid")) protectedTableScans.incrementAndGet()
        }
    } }
    private fun localOnce() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun accepted() = OneTimeAcceptedEventStore(db, tokens, sessions, localOnce())
    private fun senderOnce(http: NextSyncHttp) = NextOneTimeRequestStore(db, tokens, sessions, http, accepted(), sender(http))
    private fun merger(http: NextSyncHttp) = NextSyncMergeStore(db, tokens, sessions, accepted(),
        NextTimerRequestStore(db, tokens, sessions, sender(http)))
    private fun initial(uuid: String) = ChallengeRoundRecord(initialChallengeRoundHead(uuid), null, null, null)
    private fun metadata(extra: Boolean = false): ChallengeMetadata {
        val original = initial(timerHabit.uuid); val added = initial(id(72_999))
        return ChallengeMetadata(1, listOf(ChallengeCheckpoint(original.head, listOf(original))) +
            (if (extra) listOf(ChallengeCheckpoint(added.head, listOf(added))) else emptyList()), emptyList())
    }
    private fun canonical(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private suspend fun initialize(http: NextSyncHttp, rounds: Boolean): NextSyncStateEntity {
        formal = rounds; register()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(habitType = HabitType.CHECK_IN, schedule = HabitSchedule.Once(), targetValue = 1,
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val plans = db.habitDao().getVisibleHabitsOnce()
        val changes = plans.map { canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it)) } +
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))
        val checkpoints = listOf(OneTimeProjection(habit.uuid, OneTimeState(0, null, null)))
        val state = if (rounds) {
            val meta = metadata()
            merger(http).bootstrap(access(), null, RoundSyncBootstrapResponse(changes, 20, time,
                checkpoints, 1, meta.checkpoints, meta.births))
        } else merger(http).bootstrap(access(), null, NextSyncBootstrapResponse(changes, 20, time, checkpoints))
        sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOnceCapture(tokens.localCoreWriteAccess(), prepare = true) {
                assertNotNull("Explicit writer preparation must issue ONCE coverage", it)
            }
        } }
        cleanRoot(); return state
    }
    private suspend fun append(n: Int): SyncOutboxEntity {
        val before = localOnce().read(habit.uuid); val state = before.queue.optimisticState
        val command = OneTimeLocalCommand(habit.uuid, PendingOneTimeIntent(id(n), OneTimeIntent(id(n + 1000),
            if (state.completionEventUuid == null) "complete" else "undo", state.version, state.headEventUuid,
            state.completionEventUuid)), millis + n, "Asia/Shanghai")
        if (formal) localOnce().appendRounds(producer().captureRounds(), command) else localOnce().append(before.session, command)
        return requireNotNull(db.syncOutboxDao().getByOperationId(id(n)))
    }
    private fun response(input: MaterialSocketServer.Input): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        if (input.path.endsWith("/changes")) {
            val cursor = input.target.substringAfter("cursor=").substringBefore('&').toLong()
            return if (formal) {
                val meta = metadata()
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(emptyList(), cursor,
                    false, time, 1, meta.checkpoints, meta.births)).toByteArray())
            } else MaterialSocketServer.Reply(json.encodeToString(NextSyncPullResponse(emptyList(), cursor, false, time)).toByteArray())
        }
        assertTrue(input.path.endsWith("/push"))
        val ordinary = json.decodeFromString<NextSyncPushResponse>(successReply(input) { fact ->
            val intent = fact.getValue("one_time").jsonObject
            JsonObject(fact + ("one_time_state_after" to buildJsonObject {
                put("version", intent.getValue("expected_version").jsonPrimitive.int + 1)
                put("head_event_uuid", intent.getValue("event_uuid"))
                put("completion_event_uuid", if (intent.getValue("action") == JsonPrimitive("complete")) intent.getValue("event_uuid") else JsonNull)
            }))
        }.bytes.toString(Charsets.UTF_8))
        if (!formal) return MaterialSocketServer.Reply(json.encodeToString(ordinary).toByteArray())
        val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
        assertNull(request.contexts.single().head); assertTrue(request.contexts.single().affectedHeads.isEmpty())
        val meta = metadata()
        return MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(ordinary.results,
            1, meta.checkpoints, meta.births)).toByteArray())
    }
    private fun dirty(): Long = db.openHelper.writableDatabase.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private fun cleanRoot(): CountTrustSeal.Header {
        assertEquals(0L, dirty()); return requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase))
    }
    private suspend fun exactMain() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun warm(phase: String): OneTimeLocalSnapshot {
        val before = cleanRoot(); historyReads.set(0)
        val actual = localOnce().read(habit.uuid)
        assertEquals("$phase must read current ONCE state without retained-history rebuild", 0, historyReads.get())
        assertEquals(before, cleanRoot())
        val expected = sessions.exclusive { db.withTransaction {
            OnceTrustEvidence.audit(db, habit.uuid, requireNotNull(tokens.localFactAccess()).session).snapshot
        } }
        assertTrue("Full oracle calibrates the history observer", historyReads.get() > 0)
        assertEquals(expected, actual); return actual
    }
    private fun cost(phase: String, started: Long) {
        android.util.Log.i("OnceTrustPullCost", "phase=$phase full_table_queries=${protectedTableScans.get()} " +
            "full_history_queries=${historyReads.get()} elapsed_ns=${android.os.SystemClock.elapsedRealtimeNanos() - started}")
    }
    private fun emptyFormal(cursor: Long, extra: Boolean = false): RoundSyncPullResponse {
        val meta = metadata(extra)
        return RoundSyncPullResponse(emptyList(), cursor, false, time, 1, meta.checkpoints, meta.births)
    }

    @Test(timeout = 150_000) fun plainEmptySameCursorIsReadOnlyButAdvancedCursorWritesOnceAndStaysWarm() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        val (http, _) = channel(::response); val state = initialize(http, rounds = false)
        val before = exactMain(); val root = cleanRoot()
        assertEquals(state, merger(http).page(access(), state, NextSyncPullResponse(emptyList(), state.cursor, false, time)))
        assertEquals(before, exactMain()); assertEquals(root, cleanRoot()); warm("plain same cursor")
        protectedTableScans.set(0); historyReads.set(0); val started = android.os.SystemClock.elapsedRealtimeNanos()
        val next = merger(http).page(access(), state, NextSyncPullResponse(emptyList(), state.cursor + 1, false, time))
        cost("plain_advanced_cursor", started)
        assertEquals(state.generation + 1, next.generation); assertEquals(state.cursor + 1, next.cursor)
        assertEquals(state.bootstrapHash, next.bootstrapHash); assertEquals(0, next.challengeContract)
        assertEquals(root.generation + 1, cleanRoot().generation)
        assertTrue("Original commitState cacheProof remains measured", protectedTableScans.get() > 0)
        assertEquals(next, merger(http).state(access())); warm("plain advanced cursor")
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
    }

    @Test(timeout = 150_000) fun formalEmptyPagePreservesTwoRealUpdatesAndExtraMetadataIsExplicitFallback() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        val (http, _) = channel(::response); val state = initialize(http, rounds = true)
        val root = cleanRoot(); val originalMarker = db.nextChallengeDao().states().single()
        protectedTableScans.set(0); historyReads.set(0); val started = android.os.SystemClock.elapsedRealtimeNanos()
        val next = merger(http).page(access(), state, emptyFormal(state.cursor))
        cost("formal_empty_page", started)
        assertEquals(state.generation + 1, next.generation); assertEquals(state.cursor, next.cursor)
        val marker = db.nextChallengeDao().states().single()
        assertEquals(next.generation, marker.generation); assertEquals(next.cursor, marker.cursor)
        assertEquals(next.batchHash, marker.batchHash); assertEquals(originalMarker.metadataHash, marker.metadataHash)
        assertEquals(root.generation + 2, cleanRoot().generation)
        assertTrue("Original round and cursor cacheProof scans remain measured", protectedTableScans.get() > 0)
        warm("formal empty page")
        val maintained = cleanRoot()
        val changed = merger(http).page(access(), next, emptyFormal(next.cursor + 1, extra = true))
        assertEquals(next.cursor + 1, changed.cursor); assertEquals(2, db.nextChallengeDao().rounds().size)
        assertEquals(1L, dirty()); assertEquals(maintained, CountTrustSeal.read(db.openHelper.writableDatabase))
        historyReads.set(0); assertEquals(0, localOnce().read(habit.uuid).confirmed.version)
        assertTrue(historyReads.get() > 0); assertEquals(1L, dirty())
    }

    @Test(timeout = 150_000) fun actualFormalRuntimeUploadThenPullKeepsOriginalReceiptAndWarmCurrentStatus() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        val (http, server) = channel(::response); val initialState = initialize(http, rounds = true)
        val queue = append(72_100); val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, queue.operationId))
        val submission = requireNotNull(db.completionFollowUpDao().submission(queue.operationId))
        protectedTableScans.set(0); historyReads.set(0); val started = android.os.SystemClock.elapsedRealtimeNanos()
        NextSyncRuntime(db, tokens, sessions, http, preferences).syncRoundsCaptured(access())
        cost("runtime_upload_pull", started)
        val paths = server.requests.filterNot { it.path.endsWith("/identity") }.map { it.path }
        assertEquals(listOf("/api/v2/sync/rounds/push", "/api/v2/sync/rounds/changes"), paths)
        assertNull(db.syncOutboxDao().getById(queue.id)); assertEquals(0, count("sync_outbox"))
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, queue.operationId))
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, queue.operationId))
        assertEquals(submission, db.completionFollowUpDao().submission(queue.operationId))
        assertEquals(1, count("completions")); assertEquals(1, count("next_acceptances")); assertEquals(0, count("next_challenge_births"))
        val final = db.nextSyncStateDao().rows().single()
        assertEquals(initialState.cursor, final.cursor); assertEquals(initialState.generation + 1, final.generation)
        assertEquals(1, warm("real runtime returned").confirmed.version)
        val requests = server.requests.size; val main = exactMain()
        assertEquals(NextOneTimeOutcome.Accepted(NextOperationAcceptance.REPLAYED), senderOnce(http).sendAndAccept(access(), queue.operationId))
        assertEquals(requests, server.requests.size); assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, queue.operationId))
        assertEquals(main, exactMain())
    }

    @Test(timeout = 150_000) fun matchingPulledFactKeepsUnknownResultQueueAndExactLaterReplay() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        val (http, _) = channel(::response); val state = initialize(http, rounds = false)
        val queue = append(72_200)
        val delivery = requireNotNull(senderOnce(http).send(access(), queue.operationId)) // Original response deliberately remains unaccepted.
        val transmission = requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, queue.operationId))
        val result = delivery.result.results.single()
        val change = SyncV2Change(state.cursor + 1, "activity_event", result.entityUuid, "upsert",
            requireNotNull(result.revision), requireNotNull(result.entity), time)
        val next = merger(http).page(access(), state, NextSyncPullResponse(listOf(change), state.cursor + 1, false, time))
        assertEquals(state.cursor + 1, next.cursor)
        val pulled = warm("matching pulled unknown result")
        assertEquals(1, pulled.confirmed.version); assertEquals(listOf(queue.operationId), pulled.queue.awaitingReplayOperationIds)
        assertTrue(pulled.queue.blockedOperationIds.isEmpty())
        assertEquals(queue, db.syncOutboxDao().getById(queue.id)); assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, queue.operationId))
        val stillFrozen = requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, queue.operationId))
        assertArrayEquals(transmission.wireBytes, stillFrozen.wireBytes); assertEquals(transmission.wireHash, stillFrozen.wireHash)
        assertEquals(NextOneTimeOutcome.Accepted(NextOperationAcceptance.COMMITTED), senderOnce(http).accept(delivery))
        val acknowledged = warm("exact later ACK")
        assertTrue(acknowledged.queue.awaitingReplayOperationIds.isEmpty()); assertEquals(1, acknowledged.confirmed.version)
        assertEquals(1, count("completions")); assertEquals(1, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
    }

    /** Independent legal remote wire; deliberately does not call production ONCE reducers. */
    private fun remote(version: Int, event: String, previous: String?, sequence: Long): SyncV2Change {
        val complete = version % 2 == 1
        fun nullable(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull
        val body = buildJsonObject {
            put("public_id", event); put("revision", 1); put("deleted_at", JsonNull)
            listOf("created_at", "updated_at", "received_at", "occurred_at").forEach { put(it, time) }
            put("activity_uuid", habit.uuid); put("event_type", if (complete) "check_in" else "revert")
            put("value", if (complete) JsonPrimitive(1) else JsonNull)
            listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "external_event_id").forEach { put(it, JsonNull) }
            put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
            put("source_type", "app"); put("source_device_id", id(4)); put("note", ""); put("metadata", buildJsonObject {})
            put("reverts_event_uuid", if (complete) JsonNull else nullable(previous))
            put("one_time", buildJsonObject {
                put("event_uuid", event); put("action", if (complete) "complete" else "undo"); put("expected_version", version - 1)
                put("expected_head_event_uuid", nullable(previous)); put("reverts_event_uuid", if (complete) JsonNull else nullable(previous))
            })
            put("one_time_state_after", buildJsonObject {
                put("version", version); put("head_event_uuid", event); put("completion_event_uuid", if (complete) JsonPrimitive(event) else JsonNull)
            })
        }
        return SyncV2Change(sequence, "activity_event", event, "upsert", 1, body, time)
    }

    @Test(timeout = 150_000) fun retainedHeadAllowsMissingEarlierHistoryButAcceptedNeighborForkRollsBackPage() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[kotlinx.coroutines.Job]))
        val (http, _) = channel(::response); val initialState = initialize(http, rounds = false)
        val firstId = id(72_300); val secondId = id(72_301); val thirdId = id(72_302)
        // Existing non-contiguous accepted cache entry permits old history gaps. PULL must not
        // turn this legal baseline into a complete-bootstrap requirement.
        accepted().apply(accepted().context(), listOf(remote(3, thirdId, secondId, 0)))
        assertEquals(3, warm("legal sparse confirmed head").confirmed.version)
        assertEquals(listOf(thirdId), db.completionDao().getByHabitOnce(habit.id).map { it.uuid })
        val first = merger(http).page(access(), initialState,
            NextSyncPullResponse(listOf(remote(1, firstId, null, 21)), 21, false, time))
        assertEquals(3, warm("older confirmed slot filled").confirmed.version)
        assertNull(db.completionDao().getCompletionByUuid(secondId))
        val before = exactMain(); val root = cleanRoot()
        val fork = remote(2, id(72_303), firstId, 22)
        assertEquals("TASK_STATE_DIVERGED", rejected {
            merger(http).page(access(), first, NextSyncPullResponse(listOf(fork), 22, false, time))
        }.message)
        assertEquals(before, exactMain()); assertEquals(root, cleanRoot())
        assertNull(db.completionDao().getCompletionByUuid(fork.entityUuid))
        val final = merger(http).page(access(), first,
            NextSyncPullResponse(listOf(remote(2, secondId, firstId, 22)), 22, false, time))
        assertEquals(22L, final.cursor); assertEquals(3, warm("legal missing middle filled").confirmed.version)
        assertEquals(setOf(firstId, secondId, thirdId), db.completionDao().getByHabitOnce(habit.id).map { it.uuid }.toSet())
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_acceptances"))
    }
}
