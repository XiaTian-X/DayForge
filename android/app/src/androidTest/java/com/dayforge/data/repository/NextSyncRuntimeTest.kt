package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.NextRecoveryStateEntity
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime-level chains reuse the physical Room/DataStore/actual socket fixture. */
@RunWith(AndroidJUnit4::class)
class NextSyncRuntimeTest : NextCoreRequestFixture() {
    private val json = Json { encodeDefaults = true }
    private fun runtime(http: NextSyncHttp) = NextSyncRuntime(db, tokens, sessions, http, preferences)
    private fun merger(http: NextSyncHttp): NextSyncMergeStore {
        val core = sender(http)
        val timers = NextTimerRequestStore(db, tokens, sessions, core)
        val local = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        return NextSyncMergeStore(db, tokens, sessions,
            OneTimeAcceptedEventStore(db, tokens, sessions, local, timerRequests = timers), timers)
    }
    private fun canonical(type: String, id: String, body: JsonObject, revision: Long = 1, sequence: Long = 0,
        deleting: Boolean = false) = SyncV2Change(sequence, type, id, if (deleting) "delete" else "upsert", revision,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(id), "revision" to JsonPrimitive(revision),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
            "deleted_at" to if (deleting) JsonPrimitive(time) else JsonNull)), time)
    private fun baseline(cursor: Long = 20) = NextSyncBootstrapResponse(listOf(
        canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
        canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
        canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), cursor, time,
        if (habit.completionPolicy == "one_and_done") listOf(OneTimeProjection(habit.uuid, OneTimeState(0, null, null))) else emptyList())
    private fun bytes(value: NextSyncBootstrapResponse) = MaterialSocketServer.Reply(json.encodeToString(value).toByteArray())
    private fun bytes(value: NextSyncPullResponse) = MaterialSocketServer.Reply(json.encodeToString(value).toByteArray())
    private fun empty(cursor: Long) = NextSyncPullResponse(emptyList(), cursor, false, time)
    private suspend fun initial(http: NextSyncHttp) = merger(http).bootstrap(access(), null, baseline())
    private fun renamed(sequence: Long, revision: Long, name: String) = canonical("metric", metric.uuid,
        JsonObject(NextStructureMapper.writeMetric(metric) + ("name" to JsonPrimitive(name))), revision, sequence)

    @Test fun actualUploadBootstrapAndIncrementalPagesCommitOnlyRoomCursorAndColdResume() = runBlocking {
        register()
        val row = editMetric("Local edit")
        var snapshot = baseline()
        val pulls = mutableListOf<Long>()
        val (http, _) = channel { input -> when {
            input.path.endsWith("/identity") -> reply(input)
            input.path.endsWith("/push") -> successReply(input, revision = 2) { body ->
                val entity = JsonObject(body + ("created_at" to JsonPrimitive(time)))
                snapshot = snapshot.copy(changes = snapshot.changes.map { change ->
                    if (change.entityType == "metric") change.copy(revision = 2, payload = entity,
                        changedAt = entity.getValue("updated_at").jsonPrimitive.content) else change
                })
                entity
            }
            input.path.startsWith("/api/v2/sync/bootstrap") -> bytes(snapshot)
            else -> {
                val cursor = input.target.substringAfter("cursor=").substringBefore('&').toLong()
                pulls += cursor
                if (cursor == 20L) bytes(NextSyncPullResponse(listOf(renamed(21, 3, "Remote edit")), 21, true, time))
                else bytes(empty(cursor))
            }
        } }
        runtime(http).sync()
        assertEquals(listOf(20L, 21L), pulls)
        assertEquals("Remote edit", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertNull(db.syncOutboxDao().getById(row.id))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        val state = requireNotNull(merger(http).state(access()))
        assertEquals(21L, state.cursor); assertEquals(0L, tokens.syncCursor.first())
        assertNotNull(preferences.lastSyncTimestamp.first())
        storage.reopen()
        runtime(http).sync()
        assertEquals(21L, merger(http).state(access())!!.cursor)
        assertEquals("Remote edit", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
    }

    @Test fun completeSnapshotRemovesCleanStaleCacheButDoesNotDeleteFrozenLocalWork() = runBlocking {
        register()
        val (http, _) = channel()
        val store = merger(http)
        val state = initial(http)
        val pending = editMetric("Keep local work")
        val snapshot = baseline(30).copy(changes = baseline().changes.filter { it.entityUuid == habit.uuid })
        val next = store.bootstrap(access(), state, snapshot)
        assertEquals(30L, next.cursor)
        assertNull(db.habitDao().getHabitByUuid(timerHabit.uuid))
        assertEquals("Keep local work", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertEquals(pending, db.syncOutboxDao().getById(pending.id))
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, pending.operationId))
        assertEquals(next, store.bootstrap(access(), state, snapshot)) // Exact replay cannot overwrite newer work.
    }

    @Test fun multipleSameEntityRevisionsAndTypedRemoteDeleteAreAtomicAndNeverGenerateOutbox() = runBlocking {
        register(); val (http, _) = channel(); val store = merger(http); val state = initial(http)
        val tombstone = canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric), 4, 24, true)
        val next = store.page(access(), state, NextSyncPullResponse(listOf(
            renamed(21, 2, "First"), renamed(23, 3, "Second"), tombstone), 24, false, time))
        assertEquals(24L, next.cursor); assertNull(db.metricDao().getMetricByUuid(metric.uuid))
        assertTrue(db.syncOutboxDao().getState("metric", metric.uuid)!!.deleted)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        storage.reopen(); assertEquals(next, merger(http).state(access()))
    }

    @Test fun cursorAbortIgnoreAndLateCacheMutationRollBackWholePageThenExactRetryWorks() = runBlocking {
        register(); val (http, _) = channel(); val store = merger(http); val state = initial(http)
        val page = NextSyncPullResponse(listOf(renamed(21, 2, "Must commit together")), 21, false, time)
        for (body in listOf("SELECT RAISE(ABORT,'cursor failed');", "SELECT RAISE(IGNORE);",
            "UPDATE metrics SET name='late corruption' WHERE uuid='${metric.uuid}';")) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_cursor BEFORE UPDATE ON next_sync_state BEGIN $body END")
            rejected { store.page(access(), state, page) }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_cursor")
            assertEquals(state, store.state(access()))
            assertEquals(metric.name, db.metricDao().getMetricByUuid(metric.uuid)!!.name)
            assertEquals(1L, db.syncOutboxDao().getState("metric", metric.uuid)!!.revision)
        }
        val next = store.page(access(), state, page)
        assertEquals(21L, next.cursor); assertEquals("Must commit together", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertEquals(next, store.page(access(), state, page))
    }

    @Test fun malformedLatePageEntryCannotCommitEarlierValidEntryOrCursor() = runBlocking {
        register(); val (http, _) = channel(); val store = merger(http); val state = initial(http)
        val broken = renamed(22, 3, "Broken").let { it.copy(payload = JsonObject(it.payload + ("decimal_places" to JsonPrimitive("2")))) }
        rejected { store.page(access(), state, NextSyncPullResponse(listOf(renamed(21, 2, "First"), broken), 22, false, time)) }
        assertEquals(state, store.state(access())); assertEquals(metric.name, db.metricDao().getMetricByUuid(metric.uuid)!!.name)
    }

    @Test fun authenticRejectionIsDurablePreservesOriginalBytesAndDoesNotStopIndependentFacts() = runBlocking {
        register()
        val (baseHttp, _) = channel(); initial(baseHttp)
        val rejectedRow = editMetric("Rejected local edit")
        val fact = observation()
        var countRejected = 0
        var snapshot = baseline()
        val (http, _) = channel { input -> when {
            input.path.endsWith("/identity") -> reply(input)
            input.path.endsWith("/push") && wireOrIdentity(input) == rejectedRow.operationId -> {
                countRejected++; reply(input)
            }
            input.path.endsWith("/push") -> successReply(input) { entity ->
                snapshot = snapshot.copy(changes = snapshot.changes + SyncV2Change(0, "metric_observation", fact.entityUuid,
                    "upsert", 1, entity, entity.getValue("updated_at").jsonPrimitive.content))
                entity
            }
            input.path.startsWith("/api/v2/sync/bootstrap") -> bytes(snapshot)
            else -> bytes(empty(20))
        } }
        val failure = rejected { runtime(http).sync() }
        assertTrue(failure is NextSyncAttention)
        assertEquals(rejectedRow, db.syncOutboxDao().getById(rejectedRow.id))
        assertNull(db.syncOutboxDao().getById(fact.id))
        val rejectedResult = db.nextSyncStateDao().rejections().single()
        assertEquals(rejectedRow.operationId, rejectedResult.requestId)
        val original = transmission(NEXT_OPERATION, rejectedRow.operationId).wireBytes.copyOf()
        assertNull(preferences.lastSyncTimestamp.first())
        storage.reopen()
        assertTrue(rejected { runtime(http).sync() } is NextSyncAttention)
        assertEquals(1, countRejected)
        assertEquals(rejectedResult, db.nextSyncStateDao().rejections().single())
        assertArrayEquals(original, transmission(NEXT_OPERATION, rejectedRow.operationId).wireBytes)
    }

    @Test fun failedHttpKeepsOriginalRequestForExactColdReplayAndCannotMarkSyncSuccessful() = runBlocking {
        register(); val row = editMetric("Lost response"); val requests = mutableListOf<ByteArray>()
        var drop = true; var snapshot = baseline()
        val (http, _) = channel { input -> when {
            input.path.endsWith("/identity") -> reply(input)
            input.path.endsWith("/push") -> {
                requests += input.body.copyOf()
                if (drop) { drop = false; null } else successReply(input, revision = 2) { body ->
                    val entity = JsonObject(body + ("created_at" to JsonPrimitive(time)))
                    snapshot = snapshot.copy(changes = snapshot.changes.map {
                        if (it.entityUuid == metric.uuid) it.copy(revision = 2, payload = entity,
                            changedAt = entity.getValue("updated_at").jsonPrimitive.content) else it
                    }); entity
                }
            }
            input.path.startsWith("/api/v2/sync/bootstrap") -> bytes(snapshot)
            else -> bytes(empty(20))
        } }
        rejected { runtime(http).sync() }
        assertNull(merger(http).state(access())); assertNull(preferences.lastSyncTimestamp.first())
        assertEquals(row, db.syncOutboxDao().getById(row.id))
        storage.reopen(); runtime(http).sync()
        assertEquals(2, requests.size); assertArrayEquals(requests[0], requests[1])
        assertEquals(20L, merger(http).state(access())!!.cursor)
    }

    @Test fun candidateIsNotActiveAndAccountDeviceRawIntegerAndStaleGenerationCannotAdvanceIt() = runBlocking {
        register(); val (http, _) = channel(); val store = merger(http)
        val captured = access()
        db.nextRecoveryDao().insert(NextRecoveryStateEntity(id(1), id(2), id(3), id(4), 1,
            NextRecoveryStateEntity.ACCEPTED_DATA, 0, 19, "a".repeat(64)))
        assertNull(store.state(captured))
        val state = store.bootstrap(captured, null, baseline())
        assertEquals(19L, db.nextRecoveryDao().state()!!.candidateCursor)
        register(device = id(99)); rejected { store.page(captured, state, empty(20)) }
        register(); assertEquals(state, store.state(access()))
        db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET id=4294967297")
        rejected { store.state(access()) }
        db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET id=1")
        val next = store.page(access(), state, NextSyncPullResponse(listOf(renamed(21, 2, "new")), 21, false, time))
        rejected { store.page(access(), state, NextSyncPullResponse(listOf(renamed(22, 3, "stale")), 22, false, time)) }
        assertEquals(next, store.state(access()))
        sessions.exclusive {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { store.page(captured, next, empty(22)) }
            assertTrue(job.isActive); job.cancelAndJoin()
        }
        assertEquals(next, store.state(access()))
    }

    @Test fun oneRuntimeDrainsCompleteTimerAndOnceCompleteUndoThenRestoresAllFactsAndProjections() = runBlocking {
        register()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(habitType = HabitType.CHECK_IN, schedule = HabitSchedule.Once(), targetValue = 1,
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
            timerHabit = timerHabit.copy(targetValue = 60)
            db.habitDao().update(habit); db.habitDao().update(timerHabit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val localOnce = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        for (n in 0..1) {
            val before = localOnce.read(habit.uuid)
            val state = before.queue.optimisticState
            localOnce.append(before.session, OneTimeLocalCommand(habit.uuid,
                PendingOneTimeIntent(id(500 + n), OneTimeIntent(id(600 + n), if (n == 0) "complete" else "undo",
                    state.version, state.headEventUuid, state.completionEventUuid)), millis + n, "Asia/Shanghai"))
        }
        val first = start()
        val timer = db.timeLogDao().getTimeLogByUuid(first.sessionUuid)!!
        producer().write(local()) {
            db.timeLogDao().finishTimerAndQueue(timer.id, millis + 60_000, 60, 0, 3, 60_000,
                TimerCommandEntity(commandId = id(22), sessionUuid = first.sessionUuid, sequence = 2,
                    commandType = "stop", occurredAt = millis + 60_000, expectedControlGeneration = 1,
                    activeElapsedMillis = 60_000), false)
        }
        val facts = mutableListOf<SyncV2Change>()
        val duration = canonical("activity_event", timer.uuid, buildJsonObject {
            put("activity_uuid", timerHabit.uuid); put("event_type", "duration_session"); put("value", JsonNull)
            put("duration_seconds", 60); put("duration_milliseconds", 60_000); put("started_at", time)
            put("ended_at", Instant.ofEpochMilli(millis + 60_000).toString())
            put("occurred_at", Instant.ofEpochMilli(millis + 60_000).toString()); put("received_at", time)
            put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai"); put("source_type", "app")
            put("source_device_id", id(4)); put("note", "")
            put("metadata", buildJsonObject { put("timer_session_id", timer.uuid) })
            put("external_event_id", timer.uuid); put("reverts_event_uuid", JsonNull)
            put("day_allocations", buildJsonArray { add(buildJsonObject {
                put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai"); put("duration_milliseconds", 60_000)
            }) })
        })
        val (http, server) = channel { input -> when {
            input.path.endsWith("/identity") -> reply(input)
            input.path.endsWith("/commands") -> {
                val command = json.decodeFromString<TimerCommandBatchRequest>(input.body.toString(Charsets.UTF_8)).commands.single()
                val completed = command.commandType == "stop"
                val session = TimerSessionResponse(command.sessionId, timerHabit.uuid, if (completed) "completed" else "running",
                    id(4), 1, command.sequence, command.sequence + 1, time, command.occurredAt,
                    endedAt = command.occurredAt.takeIf { completed }, timezone = "Asia/Shanghai", isCountdown = false,
                    targetSeconds = 60, maxDurationSeconds = 180, activeElapsedMs = if (completed) 60_000 else 0,
                    completedEventId = timer.uuid.takeIf { completed })
                MaterialSocketServer.Reply(json.encodeToString(TimerCommandBatchResponse(listOf(
                    TimerCommandResult(command.commandId, command.sessionId, "applied", session = session)), time)).toByteArray())
            }
            input.path.endsWith("/push") -> successReply(input) { body ->
                val intent = body.getValue("one_time").jsonObject
                val entity = JsonObject(body + ("one_time_state_after" to buildJsonObject {
                    put("version", intent.getValue("expected_version").jsonPrimitive.int + 1)
                    put("head_event_uuid", intent.getValue("event_uuid"))
                    put("completion_event_uuid", if (intent["action"] == JsonPrimitive("complete")) intent.getValue("event_uuid") else JsonNull)
                }))
                facts += SyncV2Change(0, "activity_event", entity.getValue("public_id").jsonPrimitive.content,
                    "upsert", 1, entity, entity.getValue("updated_at").jsonPrimitive.content)
                entity
            }
            input.path.startsWith("/api/v2/sync/bootstrap") -> bytes(baseline().copy(changes = baseline().changes + facts + duration,
                oneTimeCheckpoints = listOf(OneTimeProjection(habit.uuid, OneTimeState(2, id(601), null)))))
            else -> bytes(empty(20))
        } }
        runtime(http).sync()
        assertEquals(20L, merger(http).state(access())!!.cursor)
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertTrue(db.timeLogDao().getPendingTimerCommands().isEmpty())
        assertEquals(2, localOnce.read(habit.uuid).confirmed.version)
        assertNull(localOnce.read(habit.uuid).confirmed.completionEventUuid)
        assertEquals(60_000L, db.timeLogDao().getDayAllocations(timer.uuid).sumOf { it.durationMillis })
        assertEquals(60, db.timeLogDao().getTimeLogByUuid(timer.uuid)!!.durationSeconds)
        assertEquals(listOf("/api/v2/timers/commands", "/api/v2/timers/commands", "/api/v2/sync/push", "/api/v2/sync/push"),
            server.requests.filter { it.method == "POST" }.map { it.path })
        val merge = merger(http); val cursor = merge.state(access())!!
        fun incoming(version: Int, event: String, head: String) = facts.first().let { old ->
            old.copy(sequence = 21, entityUuid = event, payload = JsonObject(old.payload + mapOf(
                "public_id" to JsonPrimitive(event), "one_time" to json.encodeToJsonElement(
                    OneTimeIntent(event, "complete", version, head, null)),
                "one_time_state_after" to json.encodeToJsonElement(OneTimeState(version + 1, event, event)))))
        }
        rejected { merge.page(access(), cursor, NextSyncPullResponse(listOf(incoming(4, id(604), id(603))), 21, false, time)) }
        assertEquals(cursor, merge.state(access())); assertEquals(2, localOnce.read(habit.uuid).confirmed.version)
        merge.page(access(), cursor, NextSyncPullResponse(listOf(incoming(2, id(602), id(601))), 21, false, time))
        assertEquals(id(602), localOnce.read(habit.uuid).confirmed.completionEventUuid)
    }

    @Test fun finalCommitFailureMustBeProvedByColdReopenNotByUncommittedWalVisibility() = runBlocking {
        register(); val (http, _) = channel(); val store = merger(http); val state = initial(http)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE cursor_commit_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE cursor_commit_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES cursor_commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER fail_cursor_commit AFTER UPDATE ON next_sync_state BEGIN INSERT INTO cursor_commit_child VALUES(1,99); END")
        val page = NextSyncPullResponse(listOf(renamed(21, 2, "Cannot commit")), 21, false, time)
        rejected { store.page(access(), state, page) }
        storage.reopen()
        assertEquals(state, merger(http).state(access()))
        assertEquals(metric.name, db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_cursor_commit")
        assertEquals(21L, merger(http).page(access(), state, page).cursor)
    }

    @Test fun transientTimerFailureIsNotPermanentRejectionAndColdRetryKeepsOriginalBytes() = runBlocking {
        register(); val command = start(); var transient = true
        val sent = mutableListOf<ByteArray>()
        val (http, _) = channel { input -> when {
            input.path.endsWith("/identity") -> reply(input)
            input.path.endsWith("/commands") -> {
                sent += input.body.copyOf()
                val result = if (transient) {
                    transient = false
                    TimerCommandResult(command.commandId, command.sessionUuid, "rejected", errorCode = "MISSING_PREDECESSOR")
                } else TimerCommandResult(command.commandId, command.sessionUuid, "applied", session = TimerSessionResponse(
                    command.sessionUuid, timerHabit.uuid, "running", id(4), 1, 1, 2, time, time,
                    timezone = "Asia/Shanghai", isCountdown = false, targetSeconds = 1,
                    maxDurationSeconds = 180, activeElapsedMs = 0))
                MaterialSocketServer.Reply(json.encodeToString(TimerCommandBatchResponse(listOf(result), time)).toByteArray())
            }
            input.path.startsWith("/api/v2/sync/bootstrap") -> bytes(baseline())
            else -> bytes(empty(20))
        } }
        assertTrue(rejected { runtime(http).sync() } is NextSyncRetryRequired)
        assertEquals(command, db.timeLogDao().getTimerCommand(command.id))
        assertTrue(db.nextSyncStateDao().rejections().isEmpty()); assertNull(preferences.lastSyncTimestamp.first())
        storage.reopen(); runtime(http).sync()
        assertEquals(2, sent.size); assertArrayEquals(sent[0], sent[1])
        assertTrue(db.timeLogDao().getPendingTimerCommands().isEmpty())
        assertNull(db.timeLogDao().getTimeLogByUuid(command.sessionUuid)!!.endTime)
        assertNotNull(preferences.lastSyncTimestamp.first())
    }
}
