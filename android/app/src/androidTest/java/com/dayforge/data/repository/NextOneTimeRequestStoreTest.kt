package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextOneTimeRequestStoreTest : NextCoreRequestFixture() {
    private val native = OneTimeDeferredEndFault()
    private val faults = OnceExistingFixtureFault(native)
    init {
        storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, storage.queryCallback, nativeFactory = faults) }
    }

    private suspend fun deferredTarget(row: SyncOutboxEntity): OneTimeDeferredEndFault.Targets {
        val current = access()
        return OneTimeDeferredEndFault.Targets(row.operationId, row.id, habit.uuid, row.entityUuid,
            current.session.authentication.userId, requireNotNull(current.session.serverInstanceId),
            requireNotNull(current.session.syncEpoch), requireNotNull(current.deviceId),
            originHash = requireNotNull(NextRequestSql.rowHash(db.openHelper.writableDatabase,
                "next_request_origins", "kind=? AND requestId=?", arrayOf<Any>(NEXT_OPERATION, row.operationId))),
            journalHash = NextRequestSql.rowHash(db.openHelper.writableDatabase,
                "next_transmissions", "kind=? AND requestId=?", arrayOf<Any>(NEXT_OPERATION, row.operationId)))
    }

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
        native.installOnNextOpen(); storage.reopen()
        assertTrue(db.openHelper.writableDatabase.isOpen)
        val row = appendOnce(170); val (http, _) = channel { onceReply(it) }
        val delivery = requireNotNull(store(http).send(access(), row.operationId)); val before = once().read(habit.uuid)
        for (kind in listOf(OnceExistingFixtureFault.Kind.RECEIPT_ABORT, OnceExistingFixtureFault.Kind.RECEIPT_IGNORE,
            OnceExistingFixtureFault.Kind.QUEUE_IGNORE, OnceExistingFixtureFault.Kind.RECEIPT_SUBMISSION, OnceExistingFixtureFault.Kind.RECEIPT_FACT)) {
            faults.arm(kind, OnceExistingFixtureFault.Targets(requestId = row.operationId, queueId = row.id, replacementUuid = id(901)))
            val actorFailure = rejected { store(http).accept(delivery) }
            assertNotNull(actorFailure); faults.requireFailure(actorFailure); faults.clear()
            pending(row); assertEquals(before, once().read(habit.uuid)); assertNull(db.syncOutboxDao().getState("activity_event", row.entityUuid))
        }
        native.arm(OneTimeDeferredEndFault.Kind.ACCEPTANCE_END, deferredTarget(row))
        val failure = rejected { store(http).accept(delivery) }
        native.requireTriggered(failure)
        storage.reopen(); assertTrue(db.openHelper.writableDatabase.isOpen)
        native.requireColdRollback()
        pending(row); assertEquals(before, once().read(habit.uuid))
        assertNull(db.syncOutboxDao().getState("activity_event", row.entityUuid))
        assertEquals(committed(), store(http).accept(delivery)); storage.reopen()
        assertEquals(replayed(), store(http).sendAndAccept(access(), row.operationId))
    }

    @Test fun firstJournalFailureDoesNotSendOrLeavePartialBindingAndOriginalRetrySucceeds() = runBlocking<Unit> {
        val row = appendOnce(180); val (http, server) = channel { onceReply(it) }
        faults.arm(OnceExistingFixtureFault.Kind.JOURNAL_IGNORE, OnceExistingFixtureFault.Targets(requestId = row.operationId))
        val actorFailure = rejected { store(http).sendAndAccept(access(), row.operationId) }
        assertNotNull(actorFailure); faults.requireFailure(actorFailure); faults.clear()
        pending(row); assertEquals(0, count("next_transmissions")); assertEquals(0, count("one_time_transmissions"))
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
        assertEquals(committed(), store(http).sendAndAccept(access(), row.operationId))
    }

    @Test fun firstJournalFinalCommitFailureKeepsBirthAndQueueButNoBindingOrBusinessHttp() = runBlocking<Unit> {
        native.installOnNextOpen(); storage.reopen()
        assertTrue(db.openHelper.writableDatabase.isOpen)
        val row = appendOnce(185); val (http, server) = channel { onceReply(it) }
        val origin = originalIntent(row)
        native.arm(OneTimeDeferredEndFault.Kind.JOURNAL_END, deferredTarget(row))
        val failure = rejected { store(http).sendAndAccept(access(), row.operationId) }
        native.requireTriggered(failure)
        storage.reopen(); assertTrue(db.openHelper.writableDatabase.isOpen)
        native.requireColdRollback()
        pending(row); assertEquals(origin, originalIntent(row))
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("one_time_transmissions"))
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
        assertEquals(committed(), store(http).sendAndAccept(access(), row.operationId))
    }

    @Test fun localBirthAndSubmissionFaultsCannotCommitFactWithoutItsOriginOrReceipt() = runBlocking<Unit> {
        for (kind in listOf(OnceExistingFixtureFault.Kind.ORIGIN_IGNORE, OnceExistingFixtureFault.Kind.SUBMISSION_IGNORE)) {
            faults.arm(kind, OnceExistingFixtureFault.Targets(requestId = id(186)))
            val actorFailure = rejected { appendOnce(186) }
            assertNotNull(actorFailure); faults.requireFailure(actorFailure); faults.clear()
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
        faults.arm(OnceExistingFixtureFault.Kind.RECEIPT_PARENT,
            OnceExistingFixtureFault.Targets(requestId = row.operationId, otherRequestId = deleted.operationId))
        val actorFailure = rejected { store(http).accept(delivery) }
        assertNotNull(actorFailure); faults.requireFailure(actorFailure); faults.clear()
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

/** Two fixed OneTime faults below the real controlled factory. No raw handle or SQL escapes. */
private class OneTimeDeferredEndFault : SupportSQLiteOpenHelper.Factory {
    enum class Kind(val table: String, val statement: String) {
        JOURNAL_END("next_transmissions", "INSERT OR ABORT INTO `next_transmissions` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`deviceId`,`wireHash`,`wireBytes`) VALUES (?,?,?,?,?,?,?,?,?,?)"),
        ACCEPTANCE_END("next_acceptances", "INSERT OR ABORT INTO `next_acceptances` (`kind`,`requestId`,`originHash`,`transmissionHash`,`resultHash`,`resultJson`) VALUES (?,?,?,?,?,?)")
    }
    data class Targets(val requestId: String, val queueId: Long, val activityUuid: String, val eventUuid: String,
        val accountId: String, val serverInstanceId: String, val syncEpoch: String, val deviceId: String,
        val originHash: String, val journalHash: String?, val revision: Long = 1)
    private data class Cell(val type: Int, val value: Any?)
    private data class Rows(val columns: List<String>, val data: List<List<Cell>>)
    private data class MainImage(val version: Int, val schemaVersion: Long, val schema: Rows, val tables: Map<String, Rows>)
    private class Attempt(val kind: Kind, val target: Targets, val native: SupportSQLiteDatabase,
        val before: MainImage, val saved: Map<String, Rows>, val operation: JsonObject,
        val originHash: String, val journalHash: String?) {
        val armed = AtomicBoolean(true)
        val ownerInvalidated = AtomicBoolean()
        val actors = AtomicInteger()
        val returned = AtomicInteger()
        val marks = AtomicInteger()
        val endAttempts = AtomicInteger()
        val failedEnds = AtomicInteger()
        val originalConstraint = AtomicReference<SQLiteConstraintException?>()
        val observationFailure = AtomicReference<Throwable?>()
        @Volatile var effectVerified = false
        @Volatile var reported = false
    }
    private val installNext = AtomicBoolean()
    private val installed = AtomicInteger()
    private val selected = AtomicReference<Attempt?>()
    private val writer = ThreadLocal<Attempt?>()
    @Volatile private var opened: SupportSQLiteDatabase? = null

    fun installOnNextOpen() { check(selected.get() == null && installed.get() == 0 && installNext.compareAndSet(false, true)) }
    fun arm(kind: Kind, targets: Targets) {
        require(targets.queueId > 0 && targets.revision > 0 && targets.originHash.matches(Regex("[0-9a-f]{64}")))
        if (kind == Kind.JOURNAL_END) require(targets.journalHash == null)
        else require(targets.journalHash?.matches(Regex("[0-9a-f]{64}")) == true)
        listOf(targets.requestId, targets.activityUuid, targets.eventUuid, targets.accountId,
            targets.serverInstanceId, targets.syncEpoch, targets.deviceId).forEach { require(isContractUuid(it)) }
        val raw = requireNotNull(opened)
        check(installed.get() == 1 && !installNext.get() && selected.get() == null)
        val next = writerDiagnostic(raw) {
            check(scalar(raw, "SELECT enabled FROM temp.once_end_fault_gate WHERE id=1") == 0L)
            check(scalar(raw, "SELECT count(*) FROM temp.once_end_fault_hits") == 0L)
            val saved = listOf("next_request_origins", "local_fact_submissions", "completions", "habits",
                "sync_outbox", "one_time_transmissions", "next_transmissions").associateWith { row(raw, it, targets) }
            val origin = saved.getValue("next_request_origins"); val birth = saved.getValue("local_fact_submissions")
            val queue = saved.getValue("sync_outbox"); val fact = saved.getValue("completions")
            check(integer(origin, "queueId") == targets.queueId && integer(origin, "protocol") == 5L)
            check(string(origin, "kind") == NEXT_OPERATION && string(origin, "requestId") == targets.requestId)
            check(string(origin, "accountId") == targets.accountId && string(origin, "serverInstanceId") == targets.serverInstanceId && string(origin, "syncEpoch") == targets.syncEpoch)
            val operation = Json.parseToJsonElement(string(origin, "intentJson")).jsonObject
            check(operation["operation_id"] == JsonPrimitive(targets.requestId) && operation["entity_type"] == JsonPrimitive("activity_event") &&
                operation["entity_uuid"] == JsonPrimitive(targets.eventUuid) && operation["action"] == JsonPrimitive("upsert"))
            check(string(birth, "entityType") == "activity_event" && string(birth, "entityUuid") == targets.eventUuid &&
                string(birth, "referenceUuid") == targets.activityUuid && Json.parseToJsonElement(string(birth, "payloadJson")) == operation["payload"])
            check(integer(queue, "id") == targets.queueId && string(queue, "operationId") == targets.requestId &&
                string(queue, "recordType") == "one_time_completion" && string(queue, "entityUuid") == targets.eventUuid &&
                string(queue, "wireEntityUuid") == targets.eventUuid && string(queue, "referenceUuid") == targets.activityUuid)
            check(string(fact, "uuid") == targets.eventUuid && string(fact, "habitUuid") == targets.activityUuid &&
                integer(fact, "habitId") == integer(saved.getValue("habits"), "id") && integer(fact, "value") == 1L &&
                string(fact, "oneTimeAction") == "complete" && integer(fact, "oneTimeExpectedVersion") == 0L)
            requireConfirmed(saved.getValue("habits"), targets, false)
            check(row(raw, "next_acceptances", targets).data.isEmpty() && row(raw, "sync_entity_state", targets).data.isEmpty())
            if (kind == Kind.JOURNAL_END) check(saved.getValue("one_time_transmissions").data.isEmpty() && saved.getValue("next_transmissions").data.isEmpty())
            else { requireBinding(raw, targets, operation); requireJournal(raw, targets, operation) }
            Attempt(kind, targets, raw, image(raw), saved, operation,
                targets.originHash, targets.journalHash)
        }
        selected.set(next) // Publish only after the diagnostic writer transaction actually ended.
    }
    fun requireTriggered(failure: Throwable) {
        val a = requireNotNull(selected.get())
        assertFalse(a.armed.get()); assertEquals(1, a.actors.get()); assertEquals(1, a.returned.get()); assertTrue(a.effectVerified)
        assertTrue(a.marks.get() > 0); assertTrue(a.endAttempts.get() > 0); assertEquals(1, a.failedEnds.get())
        assertNull("Fault observations must not replace an engine outcome", a.observationFailure.get())
        val original = requireNotNull(a.originalConstraint.get())
        assertTrue(original.message.orEmpty().contains("FOREIGN KEY constraint failed"))
        assertTrue("The same native END constraint is propagated", generateSequence(failure) { it.cause }.any { it === original })
        a.reported = true // This says nothing about durable rollback on the now-unknown old owner.
    }
    fun requireColdRollback() {
        val a = requireNotNull(selected.get()); val raw = requireNotNull(opened)
        assertTrue(a.reported); assertTrue(a.ownerInvalidated.get()); assertFalse(a.native.isOpen); assertNotSame(a.native, raw)
        assertFalse(installNext.get())
        writerDiagnostic(raw) {
            assertEquals(0L, scalar(raw, "SELECT count(*) FROM temp.sqlite_master WHERE name GLOB 'once_end_fault_*'"))
            assertEquals("New writer proves complete durable MAIN rollback", a.before, image(raw))
        }
    }
    private fun activeWriter(): Attempt? {
        val a = writer.get() ?: return null
        if (a.ownerInvalidated.get()) { writer.remove(); return null }; return a
    }
    private fun closeOwner(raw: SupportSQLiteDatabase?, action: () -> Unit) {
        selected.get()?.takeIf { it.native === raw }?.ownerInvalidated?.set(true)
        action() // Failed close is not rollback; cold proof separately requires isOpen=false.
    }
    private fun <T> writerDiagnostic(raw: SupportSQLiteDatabase, block: () -> T): T {
        check(raw === opened && raw.isOpen && !raw.inTransaction())
        raw.beginTransactionNonExclusive() // IMMEDIATE pins SQLite's primary writer, including TEMP.
        var failure: Throwable? = null
        try { check(raw.inTransaction()); val result = block(); raw.setTransactionSuccessful(); return result }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { raw.endTransaction(); check(!raw.inTransaction()) }
            catch (ending: Throwable) {
                val original = failure
                if (original == null) throw ending
                if (original !== ending) original.addSuppressed(ending)
            }
        }
    }
    private fun rows(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Rows = raw.query(sql, args).use { c ->
        val columns = c.columnNames.toList()
        Rows(columns, buildList { while (c.moveToNext()) add(columns.indices.map { i ->
            val type = c.getType(i)
            Cell(type, when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toRawBits()
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                else -> error("Unknown SQLite cell type")
            })
        }) })
    }
    private fun cell(rows: Rows, column: String) = rows.data.single()[rows.columns.indexOf(column).also { check(it >= 0) }]
    private fun integer(rows: Rows, column: String) = cell(rows, column).also { check(it.type == Cursor.FIELD_TYPE_INTEGER) }.value as Long
    private fun string(rows: Rows, column: String) = cell(rows, column).also { check(it.type == Cursor.FIELD_TYPE_STRING) }.value as String
    private fun bytes(cell: Cell): ByteArray {
        check(cell.type == Cursor.FIELD_TYPE_BLOB)
        return (cell.value as List<*>).map { it as Byte }.toByteArray()
    }
    private fun scalar(raw: SupportSQLiteDatabase, sql: String) = rows(raw, sql).let { integer(it, it.columns.single()) }
    private fun row(raw: SupportSQLiteDatabase, table: String, t: Targets): Rows {
        val (where, args) = when (table) {
            "next_request_origins", "next_transmissions", "next_acceptances" -> "kind=? AND requestId=?" to arrayOf<Any?>(NEXT_OPERATION, t.requestId)
            "local_fact_submissions", "one_time_transmissions" -> "operationId=?" to arrayOf<Any?>(t.requestId)
            "completions" -> "uuid=?" to arrayOf<Any?>(t.eventUuid)
            "habits" -> "uuid=?" to arrayOf<Any?>(t.activityUuid)
            "sync_outbox" -> "id=? OR operationId=?" to arrayOf<Any?>(t.queueId, t.requestId)
            "sync_entity_state" -> "entityType='activity_event' AND entityUuid=?" to arrayOf<Any?>(t.eventUuid)
            else -> error("Not a fixed OneTime fault table")
        }
        return rows(raw, "SELECT rowid AS __once_fault_rowid,* FROM main.$table WHERE $where ORDER BY rowid", args)
    }
    private fun image(raw: SupportSQLiteDatabase): MainImage {
        check(raw.inTransaction())
        val schema = rows(raw, "SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")
        val tables = rows(raw, "SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").data.map { cells ->
            cells.single().also { check(it.type == Cursor.FIELD_TYPE_STRING) }.value as String }
        return MainImage(raw.version, scalar(raw, "PRAGMA main.schema_version"), schema, tables.associateWith { table ->
            rows(raw, "SELECT rowid AS __once_fault_rowid,* FROM main.\"${table.replace("\"", "\"\"")}\" ORDER BY rowid") })
    }
    private fun requireConfirmed(habit: Rows, t: Targets, accepted: Boolean) {
        check(string(habit, "uuid") == t.activityUuid && integer(habit, "oneTimeConfirmedVersion") == if (accepted) 1L else 0L)
        for (name in listOf("oneTimeConfirmedHeadEventUuid", "oneTimeConfirmedCompletionEventUuid")) {
            if (accepted) check(string(habit, name) == t.eventUuid) else check(cell(habit, name).type == Cursor.FIELD_TYPE_NULL)
        }
    }
    private fun requireBinding(raw: SupportSQLiteDatabase, t: Targets, operation: JsonObject) {
        val binding = row(raw, "one_time_transmissions", t)
        check(string(binding, "operationId") == t.requestId && string(binding, "accountId") == t.accountId &&
            string(binding, "serverInstanceId") == t.serverInstanceId && string(binding, "syncEpoch") == t.syncEpoch &&
            string(binding, "deviceId") == t.deviceId && cell(binding, "rejectionJson").type == Cursor.FIELD_TYPE_NULL &&
            Json.parseToJsonElement(string(binding, "operationJson")) == operation)
    }
    private fun requireJournal(raw: SupportSQLiteDatabase, t: Targets, operation: JsonObject) {
        val journal = row(raw, "next_transmissions", t)
        check(string(journal, "kind") == NEXT_OPERATION && string(journal, "requestId") == t.requestId &&
            integer(journal, "queueId") == t.queueId && integer(journal, "protocol") == 5L &&
            string(journal, "accountId") == t.accountId && string(journal, "serverInstanceId") == t.serverInstanceId &&
            string(journal, "syncEpoch") == t.syncEpoch && string(journal, "deviceId") == t.deviceId)
        val bytes = bytes(cell(journal, "wireBytes"))
        check(string(journal, "wireHash") == nextRequestHash(bytes))
        val envelope = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        check(envelope["device_id"] == JsonPrimitive(t.deviceId) && envelope.getValue("operations").jsonArray.single() == operation)
    }
    private fun install(raw: SupportSQLiteDatabase) {
        writerDiagnostic(raw) {
            val before = image(raw)
            raw.execSQL("CREATE TEMP TABLE once_end_fault_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL,mode TEXT NOT NULL,requestId TEXT)")
            raw.execSQL("INSERT INTO temp.once_end_fault_gate VALUES(1,0,'disabled',NULL)")
            raw.execSQL("CREATE TEMP TABLE once_end_fault_hits(id INTEGER PRIMARY KEY,mode TEXT NOT NULL,requestId TEXT NOT NULL)")
            raw.execSQL("CREATE TEMP TABLE once_end_fault_parent(id INTEGER PRIMARY KEY)")
            raw.execSQL("CREATE TEMP TABLE once_end_fault_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES once_end_fault_parent(id) DEFERRABLE INITIALLY DEFERRED)")
            for (kind in Kind.entries) raw.execSQL("CREATE TEMP TRIGGER once_end_fault_${kind.table} AFTER INSERT ON main.${kind.table} " +
                "WHEN NEW.kind='$NEXT_OPERATION' AND NEW.requestId=(SELECT requestId FROM once_end_fault_gate WHERE id=1) " +
                "AND (SELECT enabled FROM once_end_fault_gate WHERE id=1)=1 AND (SELECT mode FROM once_end_fault_gate WHERE id=1)='${kind.name}' " +
                "BEGIN INSERT INTO once_end_fault_hits VALUES(1,'${kind.name}',NEW.requestId); INSERT INTO once_end_fault_child VALUES(1,99); END")
            check(before == image(raw))
        }
        check(installed.incrementAndGet() == 1) // Only after the diagnostic END returned.
    }
    private fun <T> execute(raw: SupportSQLiteDatabase, kind: Kind, keys: Map<Int, Any?>, action: () -> T): T {
        val frame = activeWriter(); val a = selected.get()
        if (a == null || a.ownerInvalidated.get() || a.kind != kind || !a.armed.get() || keys[1] != NEXT_OPERATION || keys[2] != a.target.requestId) return action()
        check(raw === opened && raw === a.native && raw.inTransaction() && frame == null && a.armed.compareAndSet(true, false))
        val t = a.target
        check(scalar(raw, "SELECT enabled FROM temp.once_end_fault_gate WHERE id=1") == 0L && scalar(raw, "SELECT count(*) FROM temp.once_end_fault_hits") == 0L)
        for (table in listOf("next_request_origins", "local_fact_submissions", "completions")) check(row(raw, table, t) == a.saved.getValue(table))
        requireBinding(raw, t, a.operation)
        check(row(raw, "next_acceptances", t).data.isEmpty())
        if (kind == Kind.JOURNAL_END) {
            check(row(raw, "sync_outbox", t) == a.saved.getValue("sync_outbox") && row(raw, "habits", t) == a.saved.getValue("habits"))
            check(row(raw, "next_transmissions", t).data.isEmpty() && row(raw, "sync_entity_state", t).data.isEmpty())
            check(keys[3] == t.queueId && keys[4] == 5L && keys[5] == t.accountId && keys[6] == t.serverInstanceId && keys[7] == t.syncEpoch && keys[8] == t.deviceId)
        } else {
            check(row(raw, "sync_outbox", t).data.isEmpty()) // OneTime acknowledge has already consumed both id/op matches.
            check(row(raw, "one_time_transmissions", t) == a.saved.getValue("one_time_transmissions") && row(raw, "next_transmissions", t) == a.saved.getValue("next_transmissions"))
            requireConfirmed(row(raw, "habits", t), t, true)
            check(keys[3] == a.originHash && keys[4] == a.journalHash)
            val resultText = keys[6] as? String ?: error("Original receipt result missing")
            check(keys[5] == nextRequestHash(resultText.toByteArray(Charsets.UTF_8)))
            val result = Json.parseToJsonElement(resultText).jsonObject; val fact = result.getValue("entity").jsonObject
            check(result["operation_id"] == JsonPrimitive(t.requestId) && result["entity_type"] == JsonPrimitive("activity_event") &&
                result["entity_uuid"] == JsonPrimitive(t.eventUuid) && result["status"] == JsonPrimitive("applied") && result["revision"] == JsonPrimitive(t.revision))
            check(fact["public_id"] == JsonPrimitive(t.eventUuid) && fact["activity_uuid"] == JsonPrimitive(t.activityUuid) &&
                fact["source_device_id"] == JsonPrimitive(t.deviceId) && fact["one_time"] == a.operation.getValue("payload").jsonObject["one_time"])
            val shadow = row(raw, "sync_entity_state", t)
            check(integer(shadow, "revision") == t.revision && integer(shadow, "deleted") == 0L && Json.parseToJsonElement(string(shadow, "payloadJson")) == fact)
            check(string(shadow, "payloadHash") == nextRequestHash(string(shadow, "payloadJson").toByteArray(Charsets.UTF_8)))
        }
        check(scalar(raw, "PRAGMA foreign_keys") == 1L && scalar(raw, "SELECT count(*) FROM temp.once_end_fault_parent") == 0L && scalar(raw, "SELECT count(*) FROM temp.once_end_fault_child") == 0L)
        raw.execSQL("UPDATE temp.once_end_fault_gate SET enabled=1,mode=?,requestId=? WHERE id=1", arrayOf(kind.name, t.requestId))
        check(scalar(raw, "SELECT changes()") == 1L); check(a.actors.incrementAndGet() == 1); writer.set(a)
        val result = action() // Exactly the original native statement; its exception is never replaced.
        check(a.returned.incrementAndGet() == 1 && raw.inTransaction() && scalar(raw, "SELECT changes()") == 1L)
        val hit = rows(raw, "SELECT mode,requestId FROM temp.once_end_fault_hits")
        check(string(hit, "mode") == kind.name && string(hit, "requestId") == t.requestId)
        if (kind == Kind.JOURNAL_END) requireJournal(raw, t, a.operation)
        else {
            val receipt = row(raw, "next_acceptances", t)
            for ((column, index) in listOf("kind" to 1, "requestId" to 2, "originHash" to 3, "transmissionHash" to 4, "resultHash" to 5, "resultJson" to 6)) check(string(receipt, column) == keys[index])
        }
        check(scalar(raw, "SELECT count(*) FROM temp.once_end_fault_child WHERE id=1 AND parent=99") == 1L)
        raw.query("PRAGMA temp.foreign_key_check(once_end_fault_child)").use { c ->
            check(c.moveToFirst() && c.getString(0) == "once_end_fault_child" && c.getLong(1) == 1L && c.getString(2) == "once_end_fault_parent" && !c.moveToNext()) }
        a.effectVerified = true; return result
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null; var adapted: SupportSQLiteDatabase? = null; val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native !== raw) {
                native = raw; opened = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    override fun close() = closeOwner(raw) { raw.close() }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql); val kind = Kind.entries.singleOrNull { it.statement == sql } ?: return statement
                        return object : SupportSQLiteStatement by statement {
                            private val keys = hashMapOf<Int, Any?>()
                            override fun clearBindings() { keys.clear(); statement.clearBindings() }
                            override fun bindNull(index: Int) { keys[index] = null; statement.bindNull(index) }
                            override fun bindLong(index: Int, value: Long) { keys[index] = value; statement.bindLong(index, value) }
                            override fun bindDouble(index: Int, value: Double) { keys[index] = value; statement.bindDouble(index, value) }
                            override fun bindString(index: Int, value: String) { keys[index] = value; statement.bindString(index, value) }
                            override fun bindBlob(index: Int, value: ByteArray) { keys[index] = value.copyOf(); statement.bindBlob(index, value) }
                            override fun execute() = execute(raw, kind, keys.toMap()) { statement.execute() }
                            override fun executeInsert() = execute(raw, kind, keys.toMap()) { statement.executeInsert() }
                            override fun executeUpdateDelete() = execute(raw, kind, keys.toMap()) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        val kind = Kind.entries.singleOrNull { it.statement == sql }
                        if (kind == null) raw.execSQL(sql, bindArgs) else execute(raw, kind,
                            bindArgs.mapIndexed { i, value -> i + 1 to value }.toMap()) { raw.execSQL(sql, bindArgs) }
                    }
                    override fun setTransactionSuccessful() { raw.setTransactionSuccessful(); activeWriter()?.takeIf { it.native === raw }?.marks?.incrementAndGet() }
                    override fun endTransaction() {
                        val a = activeWriter()?.takeIf { it.native === raw }
                        if (a == null) { raw.endTransaction(); return }
                        a.endAttempts.incrementAndGet(); var failed = false
                        try { raw.endTransaction() }
                        catch (failure: Throwable) {
                            failed = true
                            try {
                                check(failure is SQLiteConstraintException && failure.message.orEmpty().contains("FOREIGN KEY constraint failed") && a.marks.get() > 0 && a.effectVerified)
                                check(a.originalConstraint.compareAndSet(null, failure)); check(a.failedEnds.incrementAndGet() == 1)
                            } catch (observation: Throwable) { a.observationFailure.compareAndSet(null, observation) }
                            throw failure
                        } finally {
                            // After a failed END do not inspect/query that unknown owner. Close invalidates it;
                            // only a different, actually opened writer may prove durable rollback.
                            if (!failed) try { if (!raw.inTransaction()) writer.remove() }
                            catch (observation: Throwable) { a.observationFailure.compareAndSet(null, observation) }
                        }
                    }
                }
            }
            requireNotNull(adapted)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) { parent.onOpen(wrap(db)); if (installNext.compareAndSet(true, false)) install(db) }
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override fun close() = closeOwner(native) { helper.close() }
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
