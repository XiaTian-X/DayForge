package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextOneTimeRequestStoreTest : NextCoreRequestFixture() {
    private fun once() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun accepted() = OneTimeAcceptedEventStore(db, tokens, sessions, once())
    private fun store(http: NextSyncHttp) = NextOneTimeRequestStore(db, tokens, sessions, http, accepted(), sender(http))

    @Before fun onceSetup() = runBlocking<Unit> {
        register()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(habitType = HabitType.CHECK_IN, schedule = HabitSchedule.Once(), targetValue = 1,
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"))
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }

    private suspend fun appendOnce(n: Int): SyncOutboxEntity {
        val before = once().read(habit.uuid)
        val state = before.queue.optimisticState
        val command = OneTimeLocalCommand(habit.uuid, PendingOneTimeIntent(id(n), OneTimeIntent(id(n + 1000),
            if (state.completionEventUuid == null) "complete" else "undo", state.version, state.headEventUuid,
            state.completionEventUuid)), millis + n, "Asia/Shanghai")
        once().append(before.session, command)
        return requireNotNull(db.syncOutboxDao().getByOperationId(id(n)))
    }

    // Real wire shape, independent of production reducers, mappers and acceptance code.
    private fun onceReply(input: MaterialSocketServer.Input, edit: (JsonObject) -> JsonObject = { it }): MaterialSocketServer.Reply =
        successReply(input) { fact ->
            val intent = fact.getValue("one_time").jsonObject
            edit(JsonObject(fact + ("one_time_state_after" to buildJsonObject {
                put("version", intent.getValue("expected_version").jsonPrimitive.int + 1)
                put("head_event_uuid", intent.getValue("event_uuid"))
                put("completion_event_uuid", if (intent.getValue("action") == JsonPrimitive("complete"))
                    intent.getValue("event_uuid") else JsonNull)
            })))
        }

    private fun committed() = NextOneTimeOutcome.Accepted(NextOperationAcceptance.COMMITTED)
    private fun replayed() = NextOneTimeOutcome.Accepted(NextOperationAcceptance.REPLAYED)

    private suspend fun pending(row: SyncOutboxEntity) {
        assertEquals(row, db.syncOutboxDao().getById(row.id))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun offlineCompleteUndoCompleteDrainsCausalHeadsAndColdReceiptsNeverRewindOrUseHttp() = runBlocking<Unit> {
        val rows = List(3) { appendOnce(100 + it) }
        val (http, server) = channel { onceReply(it) }
        for ((index, row) in rows.withIndex()) {
            assertEquals(committed(), store(http).sendAndAccept(access(), row.operationId))
            val state = once().read(habit.uuid)
            assertEquals(index + 1, state.confirmed.version); assertEquals(3, state.queue.optimisticState.version)
            assertEquals(rows.drop(index + 1), db.syncOutboxDao().getActivityIntents(habit.uuid))
        }
        storage.reopen(); val calls = server.requests.size
        rows.forEach { assertEquals(replayed(), store(http).sendAndAccept(access(), it.operationId)) }
        assertEquals(calls, server.requests.size); assertEquals(3, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(rows.last().entityUuid, once().read(habit.uuid).confirmed.completionEventUuid)
        assertEquals(0, store(http).pushPending(access())); assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun lostResponseAndColdRestartReuseOriginalEnvelopeIdDeviceAndUnchangedSource() = runBlocking<Unit> {
        val row = appendOnce(110); var lost = true
        val (http, server) = channel { input ->
            if (input.path.endsWith("/push") && lost) { lost = false; null } else onceReply(input)
        }
        assertTrue(rejected { store(http).sendAndAccept(access(), row.operationId) } is IOException); pending(row)
        val bytes = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        storage.reopen(); assertEquals(committed(), store(http).sendAndAccept(access(), row.operationId))
        server.requests.filter { it.path.endsWith("/push") }.also { assertEquals(2, it.size) }
            .forEach { assertArrayEquals(bytes, it.body) }
        storage.reopen(); val calls = server.requests.size
        assertEquals(replayed(), store(http).sendAndAccept(access(), row.operationId)); assertEquals(calls, server.requests.size)
    }

    @Test fun httpDoesNotHoldAccountOrDatabaseLockAndInFlightUndoIsPreserved() = runBlocking<Unit> {
        val row = appendOnce(120); val arrived = CountDownLatch(1); val release = CountDownLatch(1)
        val (http, _) = channel { input ->
            if (input.path.endsWith("/push")) { arrived.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            onceReply(input)
        }
        val job = async(Dispatchers.IO) { store(http).sendAndAccept(access(), row.operationId) }
        try {
            withContext(Dispatchers.IO) { assertTrue(arrived.await(10, TimeUnit.SECONDS)) }
            val undo = withTimeout(5_000) { appendOnce(121) }
            assertEquals(2, once().read(habit.uuid).queue.optimisticState.version)
            release.countDown(); assertEquals(committed(), job.await())
            assertEquals(undo, db.syncOutboxDao().getById(undo.id))
            assertNull(once().read(habit.uuid).queue.optimisticState.completionEventUuid)
        } finally { release.countDown(); job.cancelAndJoin() }
    }

    @Test fun successorCannotJumpHeadAndOriginalCompleteUndoQueueDrainsNormally() = runBlocking<Unit> {
        val rows = List(2) { appendOnce(130 + it) }; val (http, server) = channel { onceReply(it) }
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { store(http).sendAndAccept(access(), rows[1].operationId) } as NextRequestException).reason)
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, rows[1].operationId))
        assertNull(db.completionFollowUpDao().transmission(rows[1].operationId))
        assertTrue(server.requests.all { it.path.endsWith("/identity") }); rows.forEach { pending(it) }
        assertEquals(2, store(http).pushPending(access())); assertNull(once().read(habit.uuid).confirmed.completionEventUuid)
    }

    @Test fun v4AndMissingBirthOrLegacyAttemptNeverPrepareOrAdoptOldWork() = runBlocking<Unit> {
        val row = appendOnce(140); val (oldHttp, oldServer) = channel { reply(it, version = 4) }
        assertNull(store(oldHttp).sendAndAccept(access(), row.operationId)); pending(row)
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("one_time_transmissions"))
        assertTrue(oldServer.requests.all { it.path.endsWith("/identity") })
        val (http, server) = channel { onceReply(it) }
        val origin = originalIntent(row)
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_request_origins WHERE requestId=?", arrayOf(row.operationId))
        assertEquals(NextRequestException.Reason.OLD_INTENT,
            (rejected { store(http).sendAndAccept(access(), row.operationId) } as NextRequestException).reason)
        pending(row); db.nextRequestDao().insertOrigin(origin)
        accepted().prepare(habit.uuid)
        val attempted = requireNotNull(db.syncOutboxDao().getById(row.id))
        assertNotNull(rejected { store(http).sendAndAccept(access(), row.operationId) })
        assertEquals(attempted, db.syncOutboxDao().getById(row.id)); assertEquals(0, count("next_transmissions"))
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
    }

    @Test fun wrongFullFactsCannotConsumeWorkOrBecomeSuccessAndValidRetryIsExact() = runBlocking<Unit> {
        val row = appendOnce(150); var edit: (JsonObject) -> JsonObject = { it }
        val (http, server) = channel { onceReply(it, edit) }
        for ((field, value) in mapOf("source_device_id" to JsonPrimitive(id(999)), "timezone" to JsonPrimitive("UTC"),
            "occurred_at" to JsonPrimitive(time), "note" to JsonPrimitive("not original"))) {
            edit = { JsonObject(it + (field to value)) }
            assertNotNull(rejected { store(http).sendAndAccept(access(), row.operationId) }); pending(row)
            assertNull(db.syncOutboxDao().getState("activity_event", row.entityUuid))
        }
        edit = { it }; assertEquals(committed(), store(http).sendAndAccept(access(), row.operationId))
        val bodies = server.requests.filter { it.path.endsWith("/push") }.map { it.body }
        assertEquals(5, bodies.size); bodies.forEach { assertArrayEquals(bodies.first(), it) }
    }

    @Test fun explicitConflictQuarantinesOriginalHeadAndSuffixWithoutFalseFactOrRetry() = runBlocking<Unit> {
        val rows = List(2) { appendOnce(160 + it) }
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else {
                val op = wireOperation(input)
                MaterialSocketServer.Reply("""{"results":[{"operation_id":${op["operation_id"]},"entity_type":"activity_event","entity_uuid":${op["entity_uuid"]},"status":"conflict","error_code":"TASK_STATE_CONFLICT","one_time_conflict":{"activity_uuid":"${habit.uuid}","state":{"version":1,"head_event_uuid":"${id(900)}","completion_event_uuid":"${id(900)}"}}}]}""".toByteArray())
            }
        }
        val result = store(http).sendAndAccept(access(), rows[0].operationId)
        assertTrue(result is NextOneTimeOutcome.Rejected)
        val blocked = once().read(habit.uuid)
        assertEquals(0, blocked.confirmed.version); assertEquals(rows.map { it.operationId }, blocked.queue.blockedOperationIds)
        assertEquals(rows[1], db.syncOutboxDao().getById(rows[1].id)); assertEquals(0, count("next_acceptances"))
        assertNull(db.syncOutboxDao().getState("activity_event", rows[0].entityUuid))
        storage.reopen(); val calls = server.requests.size
        assertEquals(result, store(http).sendAndAccept(access(), rows[0].operationId)); assertEquals(calls, server.requests.size)
        assertNotNull(rejected { store(http).sendAndAccept(access(), rows[1].operationId) })
        assertEquals(calls + 1, server.requests.size) // Admission only; no successor business request.
        assertNotNull(rejected { store(http).pushPending(access()) }); assertEquals(calls + 1, server.requests.size)
    }

    @Test fun receiptQueueLinkedProofAndLocalFactFaultsRollBackAndFinalCommitColdRetryWorks() = runBlocking<Unit> {
        val row = appendOnce(170); val (http, _) = channel { onceReply(it) }
        val delivery = requireNotNull(store(http).send(access(), row.operationId)); val before = once().read(habit.uuid)
        val sql = db.openHelper.writableDatabase
        for (fault in listOf("BEFORE INSERT ON next_acceptances BEGIN SELECT RAISE(ABORT,'receipt'); END",
            "BEFORE INSERT ON next_acceptances BEGIN SELECT RAISE(IGNORE); END",
            "BEFORE DELETE ON sync_outbox BEGIN SELECT RAISE(IGNORE); END",
            "AFTER INSERT ON next_acceptances BEGIN UPDATE local_fact_submissions SET referenceUuid='${id(901)}'; END",
            "AFTER INSERT ON next_acceptances BEGIN UPDATE completions SET actualCompletedAt=actualCompletedAt+1; END")) {
            sql.execSQL("CREATE TRIGGER once_ack_fault $fault")
            assertNotNull(rejected { store(http).accept(delivery) }); sql.execSQL("DROP TRIGGER once_ack_fault")
            pending(row); assertEquals(before, once().read(habit.uuid)); assertNull(db.syncOutboxDao().getState("activity_event", row.entityUuid))
        }
        sql.execSQL("CREATE TABLE once_commit_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE once_commit_child(id INTEGER REFERENCES once_commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER once_commit AFTER INSERT ON next_acceptances BEGIN INSERT INTO once_commit_child VALUES(1); END")
        assertNotNull(rejected { store(http).accept(delivery) }); storage.reopen()
        pending(row); assertEquals(before, once().read(habit.uuid)); assertEquals(0, count("once_commit_child"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER once_commit")
        assertEquals(committed(), store(http).accept(delivery)); storage.reopen()
        assertEquals(replayed(), store(http).sendAndAccept(access(), row.operationId))
    }

    @Test fun firstJournalFailureDoesNotSendOrLeavePartialBindingAndOriginalRetrySucceeds() = runBlocking<Unit> {
        val row = appendOnce(180); val (http, server) = channel { onceReply(it) }; val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER once_first BEFORE INSERT ON next_transmissions BEGIN SELECT RAISE(IGNORE); END")
        assertNotNull(rejected { store(http).sendAndAccept(access(), row.operationId) }); sql.execSQL("DROP TRIGGER once_first")
        pending(row); assertEquals(0, count("next_transmissions")); assertEquals(0, count("one_time_transmissions"))
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
        assertEquals(committed(), store(http).sendAndAccept(access(), row.operationId))
    }

    @Test fun firstJournalFinalCommitFailureKeepsBirthAndQueueButNoBindingOrBusinessHttp() = runBlocking<Unit> {
        val row = appendOnce(185); val (http, server) = channel { onceReply(it) }; val sql = db.openHelper.writableDatabase
        val origin = originalIntent(row)
        sql.execSQL("CREATE TABLE once_first_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE once_first_child(id INTEGER REFERENCES once_first_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER once_first_commit AFTER INSERT ON next_transmissions BEGIN INSERT INTO once_first_child VALUES(1); END")
        assertNotNull(rejected { store(http).sendAndAccept(access(), row.operationId) }); storage.reopen()
        pending(row); assertEquals(origin, originalIntent(row))
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("one_time_transmissions"))
        assertEquals(0, count("once_first_child")); assertTrue(server.requests.all { it.path.endsWith("/identity") })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER once_first_commit")
        assertEquals(committed(), store(http).sendAndAccept(access(), row.operationId))
    }

    @Test fun localBirthAndSubmissionFaultsCannotCommitFactWithoutItsOriginOrReceipt() = runBlocking<Unit> {
        val sql = db.openHelper.writableDatabase
        for (table in listOf("next_request_origins", "local_fact_submissions")) {
            sql.execSQL("CREATE TRIGGER once_birth BEFORE INSERT ON $table BEGIN SELECT RAISE(IGNORE); END")
            assertNotNull(rejected { appendOnce(186) }); sql.execSQL("DROP TRIGGER once_birth")
            assertEquals(0, count("next_request_origins")); assertEquals(0, count("local_fact_submissions"))
            assertEquals(0, count("completions")); assertEquals(0, db.syncOutboxDao().count())
        }
        val row = appendOnce(186); assertNotNull(originalIntent(row)); assertEquals(1, count("completions"))
    }

    @Test fun cancelledHttpLeavesOriginalJournalAndCanColdReplayWithoutASecondIntent() = runBlocking<Unit> {
        val row = appendOnce(187); val arrived = CountDownLatch(1); val release = CountDownLatch(1); var block = true
        val (http, server) = channel { input ->
            if (input.path.endsWith("/push") && block) { arrived.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            onceReply(input).copy(allowClientClose = true)
        }
        val request = async(Dispatchers.IO) { store(http).sendAndAccept(access(), row.operationId) }
        try {
            withContext(Dispatchers.IO) { assertTrue(arrived.await(10, TimeUnit.SECONDS)) }
            withTimeout(5_000) { request.cancelAndJoin() }; pending(row)
        } finally { block = false; release.countDown(); request.cancelAndJoin() }
        val bytes = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        storage.reopen(); assertEquals(committed(), store(http).sendAndAccept(access(), row.operationId))
        server.requests.filter { it.path.endsWith("/push") }.also { assertEquals(2, it.size) }
            .forEach { assertArrayEquals(bytes, it.body) }
    }

    @Test fun originalAccountReplicaDeviceAndPermissionChangesCannotAcceptLateResponse() = runBlocking<Unit> {
        val row = appendOnce(190); val (http, _) = channel { onceReply(it) }
        val delivery = requireNotNull(store(http).send(access(), row.operationId))
        register(device = id(902)); assertNotNull(rejected { store(http).accept(delivery) }); pending(row)
        register(permissions = caps - "facts.append", revision = 2)
        assertNotNull(rejected { store(http).accept(delivery) }); pending(row)
        register(); tokens.saveServerIdentity(id(2), id(903))
        assertNotNull(rejected { store(http).accept(delivery) }); pending(row)
        register(); tokens.saveLoginSession("other", "other-refresh", "member", id(904), false)
        assertNotNull(rejected { store(http).accept(delivery) }); pending(row)
    }

    @Test fun boundParentDeleteAllowsShadowOnlyAcceptanceAndColdReplayNeverRecreatesParent() = runBlocking<Unit> {
        val row = appendOnce(200); val (http, server) = channel { onceReply(it) }
        val delivery = requireNotNull(store(http).send(access(), row.operationId))
        // Exercise the foundation's already-physical parent deletion, not the formal retained-delete UI.
        producer().write(local()) { db.habitDao().delete(requireNotNull(db.habitDao().getHabitByUuid(habit.uuid))) }
        val deleted = db.syncOutboxDao().getAll().single { it.recordType == "habit" && it.action == "delete" }
        val original = originalIntent(deleted)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER once_parent AFTER INSERT ON next_acceptances BEGIN UPDATE next_request_origins SET intentJson='{}' WHERE requestId='${deleted.operationId}'; END")
        assertNotNull(rejected { store(http).accept(delivery) }); db.openHelper.writableDatabase.execSQL("DROP TRIGGER once_parent")
        pending(row); assertEquals(original, originalIntent(deleted)); assertNull(db.syncOutboxDao().getState("activity_event", row.entityUuid))
        assertEquals(committed(), store(http).accept(delivery)); assertNull(db.habitDao().getHabitByUuid(habit.uuid))
        assertNull(db.completionDao().getCompletionByUuid(row.entityUuid)); assertEquals(deleted, db.syncOutboxDao().getById(deleted.id))
        storage.reopen(); val calls = server.requests.size
        assertEquals(replayed(), store(http).sendAndAccept(access(), row.operationId)); assertEquals(calls, server.requests.size)
    }

    @Test fun arbitraryParentAbsenceAndCoherentlyBrokenColdReceiptFailClosedWithoutRewind() = runBlocking<Unit> {
        val row = appendOnce(210); val (http, server) = channel { onceReply(it) }
        val delivery = requireNotNull(store(http).send(access(), row.operationId))
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().delete(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertNotNull(rejected { store(http).accept(delivery) }); pending(row)
        assertEquals(0, count("next_acceptances")); assertNull(db.syncOutboxDao().getState("activity_event", row.entityUuid))
        // A new deletion intent provides the missing authority; it does not recreate the parent/fact.
        producer().write(local()) { db.syncOutboxDao().insert(SyncOutboxEntity(operationId = id(905), recordType = "habit", entityUuid = habit.uuid,
            wireEntityUuid = habit.uuid, action = "delete")) }
        assertEquals(committed(), store(http).accept(delivery))
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        val original = Json.parseToJsonElement(receipt.resultJson).jsonObject
        val changed = JsonObject(original + ("entity" to JsonObject(original.getValue("entity").jsonObject +
            ("note" to JsonPrimitive("changed"))))).toString()
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf<Any>(changed, nextRequestHash(changed.toByteArray()), row.operationId))
        storage.reopen(); val calls = server.requests.size
        assertNotNull(rejected { store(http).sendAndAccept(access(), row.operationId) }); assertEquals(calls, server.requests.size)
        assertNull(db.habitDao().getHabitByUuid(habit.uuid)); assertEquals(0L, tokens.syncCursor.first())
    }
}
