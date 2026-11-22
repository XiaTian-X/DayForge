package com.dayforge.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.service.AccountIconController
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original first-send, replay and acceptance regressions. */
@RunWith(AndroidJUnit4::class)
class NextCoreRequestStoreTest : NextCoreRequestFixture() {
    private val native = NextCoreRequestNativeFault()
    init {
        native.installOnNextOpen()
        storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, storage.queryCallback, nativeFactory = native) }
    }

    /** Current formal entry, independent of the low-level producer fault/batch cases below. */
    private suspend fun <T> typedMetric(block: suspend (MetricRepository, NextObjectEditor) -> T): T {
        assertFalse("The actual editor owns its producer transaction", db.inTransaction())
        val icons = AccountIconController(app, tokens, sessions)
        try {
            val editor = NextObjectEditor(db, tokens, sessions, icons)
            val repository = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(),
                db.habitMetricLinkDao(), nextObjectEditor = editor)
            return block(repository, editor)
        } finally {
            try { icons.awaitImages() } finally { icons.close() }
        }
    }

    private suspend fun coreObservation(): SyncOutboxEntity {
        val previousLogs = db.metricLogDao().getAllLogsForMetric(metric.id).map { it.id }.toSet()
        val previousQueue = db.syncOutboxDao().getAll().map { it.operationId }.toSet()
        val previousOrigins = count("next_request_origins")
        val previousNetwork = servers.map { it.requests.size }
        val inserted = typedMetric { repository, editor ->
            val selected = editor.metric(metric.id)
            val actual = requireNotNull(selected.value); val authority = requireNotNull(selected.authority)
            assertEquals(metric.uuid, actual.uuid); assertNotNull(actual.appearance)
            assertNull("This fixture's original protocol is plain, not inferred from appearance", authority.rounds)
            repository.recordValue(actual.id, 21.125, "captured\nvalue", millis,
                authority = authority, expectedMetricUuid = actual.uuid)
        }
        val logs = db.metricLogDao().getAllLogsForMetric(metric.id)
        val saved = logs.filter { it.id !in previousLogs }.single()
        assertEquals(previousLogs.size + 1, logs.size); assertEquals(inserted, saved.id)
        assertEquals(21.125, saved.value, 0.0); assertEquals("captured\nvalue", saved.note); assertEquals(millis, saved.date)
        val queue = db.syncOutboxDao().getAll()
        val source = queue.filter { it.operationId !in previousQueue }.single()
        assertEquals(previousQueue.size + 1, queue.size)
        assertEquals("metric_log", source.recordType); assertEquals(saved.uuid, source.entityUuid)
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
        assertEquals(source.operationId, origin.requestId); assertEquals(source.id, origin.queueId)
        assertEquals(5, origin.protocol); assertNull(roundOperationIntent(origin.intentJson))
        val operation = decodeNextOperationIntent(origin.intentJson)
        assertEquals("metric_observation", operation.entityType); assertEquals(saved.uuid, operation.entityUuid)
        assertEquals(SyncV2Mapper.metricObservation(saved, metric.uuid), operation.payload)
        assertEquals(previousOrigins + 1, count("next_request_origins"))
        assertEquals(previousNetwork, servers.map { it.requests.size })
        return source
    }

    /** Fixed legal business input for producer atomicity/corruption/cancellation tests.
     * It deliberately tests the original producer callback, not a nested typed repository.
     */
    private suspend fun insertProducerObservation(note: String): MetricLogEntity {
        assertTrue(db.inTransaction())
        val current = requireNotNull(db.metricDao().getMetricById(metric.id))
        assertEquals(metric.uuid, current.uuid); assertNotNull(current.appearance)
        NextStructureMapper.writeMetric(current)
        NextMetricDeletionStore(db).requireWritable(current.uuid)
        val row = MetricLogEntity(metricId = current.id, date = millis, value = 21.125,
            unit = current.unit, note = note)
        val inserted = db.metricLogDao().insertAll(listOf(row)).single()
        val saved = requireNotNull(db.metricLogDao().getLogByUuid(row.uuid))
        assertEquals(row.copy(id = inserted), saved)
        return saved
    }

    private suspend fun requireInsertedObservationBeforeOrigin(saved: MetricLogEntity): SyncOutboxEntity {
        assertTrue(db.inTransaction())
        assertEquals(saved, db.metricLogDao().getLogByUuid(saved.uuid))
        val source = db.syncOutboxDao().getAll().single { it.recordType == "metric_log" && it.entityUuid == saved.uuid }
        assertNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
        return source
    }

    private suspend fun coreLink() = typedMetric { repository, editor ->
        val selected = editor.metric(metric.id)
        val actual = requireNotNull(selected.value); val authority = requireNotNull(selected.authority)
        assertEquals(metric.uuid, actual.uuid); assertNull(authority.rounds)
        repository.linkHabits(actual, setOf(habit.id), authority, mapOf(habit.id to habit.uuid))
    }

    private suspend fun coreDelete() = typedMetric { repository, editor ->
        val selected = editor.metric(metric.id)
        val actual = requireNotNull(selected.value); val authority = requireNotNull(selected.authority)
        assertEquals(metric.uuid, actual.uuid); assertNull(authority.rounds)
        repository.deleteMetric(actual, authority)
    }

    @Test fun rootStructuralAcceptanceRejectsRevisionBelowFrozenBaseWithoutConsumingIntent() = runBlocking<Unit> {
        val revision = AtomicLong(5)
        val seed = editMetric("Seed"); register()
        val (http, server) = channel { successReply(it, revision.get()) }
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), seed.operationId))
        val seedReceipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, seed.operationId))
        val shadow = requireNotNull(db.syncOutboxDao().getState("metric", metric.uuid))
        assertEquals(5L, shadow.revision)

        val row = editMetric("Desired")
        val origin = originalIntent(row)
        assertEquals(JsonPrimitive(5), Json.parseToJsonElement(origin.intentJson).jsonObject["base_revision"])
        assertNull(requireNotNull(db.nextStructuralCausalDao().dependency(row.operationId)).predecessorId)
        val current = requireNotNull(db.metricDao().getMetricById(metric.id))
        assertEquals("Desired", current.name)
        revision.set(4)
        val store = sender(http)
        val delivery = requireNotNull(store.sendOperation(access(), row.operationId))
        val wire = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        val transmissionProof = NextRequestSql.rowHash(db.openHelper.writableDatabase, "next_transmissions",
            "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, row.operationId))
        val operation = Json.parseToJsonElement(wire.toString(Charsets.UTF_8)).jsonObject["operations"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(5), operation["base_revision"])
        assertEquals(JsonPrimitive(row.operationId), operation["operation_id"])
        assertEquals(4L, delivery.result.results.single().revision)
        assertEquals(JsonPrimitive(4), delivery.result.results.single().entity!!["revision"])
        val requests = server.requests.size

        suspend fun unchanged() {
            assertUnaccepted(row)
            assertEquals(origin, originalIntent(row))
            assertArrayEquals(wire, transmission(NEXT_OPERATION, row.operationId).wireBytes)
            assertEquals(transmissionProof, NextRequestSql.rowHash(db.openHelper.writableDatabase, "next_transmissions",
                "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, row.operationId)))
            assertEquals(current, db.metricDao().getMetricById(metric.id))
            assertEquals(shadow, db.syncOutboxDao().getState("metric", metric.uuid))
            assertEquals(seedReceipt, db.nextRequestDao().acceptance(NEXT_OPERATION, seed.operationId))
            assertEquals(1, count("next_acceptances"))
        }
        for (status in listOf("applied", "already_applied")) {
            val result = delivery.result.results.single().copy(status = status)
            assertTrue(rejected { store.acceptOperation(delivery.copy(result = delivery.result.copy(results = listOf(result)))) }
                is IllegalArgumentException)
            unchanged()
            assertEquals(requests, server.requests.size)
        }

        storage.reopen()
        assertTrue(rejected { sender(http).sendAndAcceptOperation(access(), row.operationId) } is IllegalArgumentException)
        unchanged()
        server.requests.filter { wireOrIdentity(it) == row.operationId }.also { assertEquals(2, it.size) }
            .forEach { assertArrayEquals(wire, it.body) }
        revision.set(6)
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        val accepted = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        val acceptedMetric = requireNotNull(db.metricDao().getMetricById(metric.id))
        val acceptedShadow = requireNotNull(db.syncOutboxDao().getState("metric", metric.uuid))
        assertEquals("Desired", acceptedMetric.name); assertEquals(6L, acceptedShadow.revision)
        assertEquals(2, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
        assertEquals(1, count("metrics")); assertEquals(0L, tokens.syncCursor.first())
        server.requests.filter { wireOrIdentity(it) == row.operationId }.also { assertEquals(3, it.size) }
            .forEach { assertArrayEquals(wire, it.body) }

        // A coherent hash is not proof that a root receipt respects its frozen merge base.
        val goodResult = Json.parseToJsonElement(accepted.resultJson).jsonObject
        val badResult = buildJsonObject {
            goodResult.forEach { (key, value) -> put(key, value) }
            put("revision", 4)
            put("entity", buildJsonObject {
                goodResult.getValue("entity").jsonObject.forEach { (key, value) -> put(key, value) }
                put("revision", 4)
            })
        }.toString()
        val badHash = nextRequestHash(badResult.toByteArray(Charsets.UTF_8))
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
            arrayOf<Any>(badResult, badHash, NEXT_OPERATION, row.operationId))
        storage.reopen()
        val beforeReplay = server.requests.size
        assertTrue(rejected { sender(http).sendAndAcceptOperation(access(), row.operationId) } is IllegalArgumentException)
        assertEquals(beforeReplay, server.requests.size)
        assertEquals(accepted.copy(resultJson = badResult, resultHash = badHash), db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertEquals(acceptedMetric, db.metricDao().getMetricById(metric.id))
        assertEquals(acceptedShadow, db.syncOutboxDao().getState("metric", metric.uuid))
        assertEquals(0, count("sync_outbox")); assertEquals(2, count("next_acceptances"))
        assertEquals(0L, tokens.syncCursor.first())
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
            arrayOf<Any>(accepted.resultJson, accepted.resultHash, NEXT_OPERATION, row.operationId))
        storage.reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(beforeReplay, server.requests.size)
        assertEquals(accepted, db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertArrayEquals(wire, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertEquals(acceptedMetric, db.metricDao().getMetricById(metric.id))
        assertEquals(acceptedShadow, db.syncOutboxDao().getState("metric", metric.uuid))
        assertEquals(0, count("sync_outbox")); assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun offlineNewBusinessAndOriginsCommitTogetherWithoutAdoptingOldZeroAttemptQueues() = runBlocking<Unit> {
        val old = SyncOutboxEntity(operationId = id(40), recordType = "metric", entityUuid = metric.uuid,
            wireEntityUuid = metric.uuid, action = "upsert", payloadJson = "{\"old\":true}", attemptedAt = 1, attemptCount = 1)
        val oldId = db.syncOutboxDao().insert(old)
        val lost = TimerCommandEntity(commandId = id(41), sessionUuid = id(42), sequence = 1,
            commandType = "start", occurredAt = millis, expectedControlGeneration = 0, attemptCount = 0)
        val lostId = db.timeLogDao().insertTimerCommand(lost)
        val observation = coreObservation(); val timer = start()
        storage.reopen()
        assertEquals(old.copy(id = oldId), db.syncOutboxDao().getById(oldId))
        assertEquals(lost.copy(id = lostId), db.timeLogDao().getTimerCommand(lostId))
        assertNull(db.nextRequestDao().origin(NEXT_OPERATION, old.operationId))
        assertNull(db.nextRequestDao().origin(NEXT_TIMER, lost.commandId))
        for ((kind, request) in listOf(NEXT_OPERATION to observation.operationId, NEXT_TIMER to timer.commandId)) {
            val origin = requireNotNull(db.nextRequestDao().origin(kind, request))
            assertEquals(5, origin.protocol); assertEquals(id(1), origin.accountId)
            assertNull(origin.serverInstanceId); assertNull(origin.syncEpoch)
        }
        assertEquals(0, count("next_transmissions"))
        register(); val captured = access(); val (http, server) = channel()
        assertEquals(NextRequestException.Reason.OLD_INTENT, (rejected { sender(http).sendCommand(captured, lost.commandId) } as NextRequestException).reason)
        assertEquals(NextRequestException.Reason.OLD_INTENT, (rejected { sender(http).sendOperation(captured, old.operationId) } as NextRequestException).reason)
        assertEquals(NextRequestException.Reason.OLD_INTENT,
            (rejected { sender(http).sendAndAcceptOperation(captured, old.operationId) } as NextRequestException).reason)
        storage.reopen()
        assertEquals(NextRequestException.Reason.OLD_INTENT,
            (rejected { sender(http).sendAndAcceptOperation(captured, old.operationId) } as NextRequestException).reason)
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
        assertEquals(0, count("next_transmissions"))
        assertEquals(0, count("next_acceptances"))
        assertEquals(old.copy(id = oldId), db.syncOutboxDao().getById(oldId))
        assertEquals(lost.copy(id = lostId), db.timeLogDao().getTimerCommand(lostId))
        assertNull(db.nextRequestDao().origin(NEXT_OPERATION, old.operationId))
        assertNull(db.nextRequestDao().origin(NEXT_TIMER, lost.commandId))
    }

    @Test fun lostResponseThenColdReopenReplaysWholeOriginalEnvelopeAndNeverConsumesTheQueue() = runBlocking<Unit> {
        val observation = coreObservation(); val timer = start(); register()
        val captured = access(); var drop = true
        val (http, server) = channel { if (!it.path.endsWith("/identity") && drop) null else reply(it) }
        assertTrue(rejected { sender(http).sendOperation(captured, observation.operationId) } is IOException)
        assertTrue(rejected { sender(http).sendCommand(captured, timer.commandId) } is IOException)
        val operationWire = transmission(NEXT_OPERATION, observation.operationId).wireBytes.copyOf()
        val timerWire = transmission(NEXT_TIMER, timer.commandId).wireBytes.copyOf()
        storage.reopen(); drop = false
        assertEquals("INVALID_PAYLOAD", sender(http).sendOperation(captured, observation.operationId)!!.result.results.single().errorCode)
        assertEquals("already_applied", sender(http).sendCommand(captured, timer.commandId)!!.result.results.single().status)
        server.requests.filter { it.path.endsWith("/push") }.also { assertEquals(2, it.size) }.forEach { assertArrayEquals(operationWire, it.body) }
        server.requests.filter { it.path.endsWith("/commands") }.also { assertEquals(2, it.size) }.forEach { assertArrayEquals(timerWire, it.body) }
        val body = Json.parseToJsonElement(operationWire.toString(Charsets.UTF_8)).jsonObject
            .getValue("operations").jsonArray.single().jsonObject
        assertEquals(observation.operationId, body.getValue("operation_id").jsonPrimitive.content)
        assertEquals("metric_observation", body.getValue("entity_type").jsonPrimitive.content)
        val payload = body.getValue("payload").jsonObject
        assertEquals(JsonPrimitive(21.125), payload["value"]); assertEquals(JsonPrimitive(time), payload["occurred_at"])
        assertEquals(JsonPrimitive("captured\nvalue"), payload["note"])
        assertEquals(observation, db.syncOutboxDao().getById(observation.id))
        assertEquals(timer, db.timeLogDao().getTimerCommand(timer.id)); assertEquals(0L, tokens.syncCursor.first())
        assertNull(requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, observation.operationId)).serverInstanceId)
    }

    @Test fun allFiveOrdinaryKindsFreezeAtLocalWriteNotFromLaterMutableBusinessState() = runBlocking<Unit> {
        val session = local()
        native.beginProducerObservation()
        // Deterministic non-target transactions exercise both sides of the actual producer.
        // They must not claim its identity or inflate its exact one-BEGIN/one-END contract.
        db.withTransaction {
            assertEquals(0, count("metric_logs"))
            native.observeProducerCalibration(NextCoreRequestNativeFault.ProducerCalibration.BEFORE_TARGET)
        }
        producer().write(session) {
            // This method verifies the generic producer's one-transaction five-kind contract.
            val firstHabit = habit.copy(name = "First name", updatedAt = System.currentTimeMillis())
            val firstMetric = metric.copy(name = "First metric", updatedAt = System.currentTimeMillis())
            NextStructureMapper.writePlan(firstHabit); NextStructureMapper.writeMetric(firstMetric)
            db.habitDao().update(firstHabit)
            db.metricDao().update(firstMetric)
            val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
                uuid = id(50), date = Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                actualCompletedAt = millis, value = 3, recordedTimezone = "Asia/Shanghai")
            NextCountDayStore(db).capture(habit, fact)
            db.completionDao().insertForSync(fact)
            insertProducerObservation("note")
            db.habitMetricLinkDao().insertOrIgnore(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                metricId = metric.id, metricUuid = metric.uuid, uuid = id(51), coefficient = 0.5,
                showInHabitDetail = true, promptOnComplete = true))
            assertEquals(1, count("metric_logs")); assertEquals(5, count("sync_outbox"))
            assertEquals(0, count("next_request_origins"))
            native.observeProducerCallback()
        }
        db.withTransaction {
            assertEquals(1, count("metric_logs")); assertEquals(5, count("sync_outbox"))
            native.observeProducerCalibration(NextCoreRequestNativeFault.ProducerCalibration.AFTER_TARGET)
        }
        native.requireProducerCalibrations()
        native.requireProducerCommitted()
        val rows = db.syncOutboxDao().getAll(); assertEquals(5, rows.size)
        producer().write(session) {
            habits().updateHabit(requireNotNull(db.habitDao().getHabitById(habit.id)).copy(name = "Later name"))
            metrics().updateMetric(requireNotNull(db.metricDao().getMetricById(metric.id)).copy(name = "Later metric"))
        }
        register(); val captured = access(); val (http, server) = channel { successReply(it) }
        // A dependent first count cannot pass an unacknowledged structural predecessor.
        rows.forEach { assertNotNull(sender(http).sendAndAcceptOperation(captured, it.operationId)) }
        val sent = server.requests.filter { it.path.endsWith("/push") }.map {
            Json.parseToJsonElement(it.body.toString(Charsets.UTF_8)).jsonObject.getValue("operations").jsonArray.single().jsonObject
        }.associateBy { it.getValue("entity_type").jsonPrimitive.content }
        assertEquals(setOf("plan_node", "metric", "activity_event", "metric_observation", "activity_metric_link"), sent.keys)
        assertEquals(JsonPrimitive("First name"), sent.getValue("plan_node").getValue("payload").jsonObject["title"])
        assertEquals(JsonPrimitive("First metric"), sent.getValue("metric").getValue("payload").jsonObject["name"])
        assertEquals(JsonPrimitive(3), sent.getValue("activity_event").getValue("payload").jsonObject["value"])
        assertEquals(2, db.syncOutboxDao().getAll().size); assertEquals(5, count("next_transmissions"))
        assertEquals(5, count("next_acceptances"))
        assertTrue(rows.all { db.syncOutboxDao().getByOperationId(it.operationId) == null })
    }

    @Test fun sameAccountReauthenticationUsesCurrentCredentialsButOriginalDurableIdentity() = runBlocking<Unit> {
        val row = coreObservation(); register(); var drop = true
        val (http, server) = channel { if (!it.path.endsWith("/identity") && drop) null else reply(it) }
        val originalAccess = access()
        assertTrue(rejected { sender(http).sendOperation(originalAccess, row.operationId) } is IOException)
        val wire = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        tokens.saveLoginSession("synthetic-new", "synthetic-new-refresh", "member", id(1), false)
        storage.reopen(); drop = false
        val newAccess = access(); assertNotEquals(originalAccess.session.authentication, newAccess.session.authentication)
        assertNotNull(sender(http).sendOperation(newAccess, row.operationId))
        val sent = server.requests.filter { it.path.endsWith("/push") }
        assertEquals("Bearer synthetic-first", sent[0].headers["authorization"])
        assertEquals("Bearer synthetic-new", sent[1].headers["authorization"])
        assertArrayEquals(wire, sent[1].body); assertEquals(id(4), transmission(NEXT_OPERATION, row.operationId).deviceId)
    }

    @Test fun changedDeviceReplicaAndOwnerCannotRebindTheOriginalTransmission() = runBlocking<Unit> {
        val row = coreObservation(); register(); val (http, server) = channel { if (it.path.endsWith("/identity")) reply(it) else null }
        assertTrue(rejected { sender(http).sendOperation(access(), row.operationId) } is IOException)
        val wire = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        register(device = id(80))
        assertEquals(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED,
            (rejected { sender(http).sendOperation(access(), row.operationId) } as NextRequestException).reason)
        tokens.saveServerIdentity(id(2), id(81))
        val error = rejected { sender(http).sendOperation(access(), row.operationId) }
        assertTrue(error is com.dayforge.data.api.NextSyncHttpFailure)
        tokens.saveLoginSession("synthetic-other", "synthetic-other-refresh", "other", id(82), false)
        register()
        assertEquals(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED,
            (rejected { sender(http).sendOperation(access(), row.operationId) } as NextRequestException).reason)
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        assertArrayEquals(wire, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertEquals(row, db.syncOutboxDao().getById(row.id))
    }

    @Test fun publicV4OrUnknownVersionCannotCreateFirstSendJournalsOrPrivateTraffic() = runBlocking<Unit> {
        val row = coreObservation(); val command = start(); register()
        for (version in listOf(4, 6)) {
            val (http, server) = channel { reply(it, version) }
            assertNull(sender(http).sendOperation(access(), row.operationId))
            assertNull(sender(http).sendCommand(access(), command.commandId))
            assertEquals(0, count("next_transmissions")); assertTrue(server.requests.all { it.path.endsWith("/identity") })
        }
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(command, db.timeLogDao().getTimerCommand(command.id))
    }

    @Test fun failedOriginInsertRollsBackRealRepositoryWriteAndQueue() = runBlocking<Unit> {
        native.arm(NextCoreRequestNativeFault.Kind.ORIGIN_ABORT,
            NextCoreRequestNativeFault.Targets(metricId = metric.id, metricUuid = metric.uuid, accountId = id(1),
                expectedDate = millis, expectedValue = 21.125, expectedNote = "captured\nvalue", expectedUnit = metric.unit))
        val failure = rejected { coreObservation() }
        assertNotNull(failure); native.requireTriggered(failure); native.requireWarmRollback()
        assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
        native.expectWarmRetry()
        coreObservation()
        native.requireWarmRetryCommitted()
        storage.reopen()
        assertEquals(1, count("metric_logs")); assertEquals(1, count("next_request_origins"))
    }

    @Test fun failedFirstJournalInsertLeavesOriginalIntentAndSendsNoBusinessRequest() = runBlocking<Unit> {
        val row = coreObservation(); register()
        val origin = db.nextRequestDao().origin(NEXT_OPERATION, row.operationId)
        val (http, server) = channel()
        native.arm(NextCoreRequestNativeFault.Kind.JOURNAL_ABORT,
            NextCoreRequestNativeFault.Targets(row.operationId, row.id, metric.id, metric.uuid, id(1)))
        val failure = rejected { sender(http).sendOperation(access(), row.operationId) }
        assertNotNull(failure); native.requireTriggered(failure); native.requireWarmRollback()
        assertEquals(0, count("next_transmissions")); assertTrue(server.requests.all { it.path.endsWith("/identity") })
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, row.operationId)); assertEquals(row, db.syncOutboxDao().getById(row.id))
        native.expectWarmRetry()
        assertNotNull(sender(http).sendOperation(access(), row.operationId))
        native.requireWarmRetryCommitted()
        assertEquals(1, count("next_transmissions"))
    }

    @Test fun failedFinalCommitNeverSendsAndColdReopenHasNoCommittedJournal() = runBlocking<Unit> {
        val row = coreObservation(); register()
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, row.operationId))
        val (http, server) = channel()
        native.arm(NextCoreRequestNativeFault.Kind.JOURNAL_END,
            NextCoreRequestNativeFault.Targets(row.operationId, row.id, metric.id, metric.uuid, id(1)))
        val failure = rejected { sender(http).sendOperation(access(), row.operationId) }
        assertTrue(failure is SQLiteConstraintException); native.requireTriggered(failure)
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
        // Confirm the actual file after a real deferred END failure, never a WAL-reader guess.
        storage.reopen(); assertTrue(db.openHelper.writableDatabase.isOpen)
        native.requireColdFaultAbsent()
        assertEquals(0, count("next_transmissions"))
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, row.operationId))
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(1, count("metric_logs"))
        assertNotNull(sender(http).sendOperation(access(), row.operationId))
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        storage.reopen()
        assertEquals(1, count("next_transmissions")); assertEquals(row, db.syncOutboxDao().getById(row.id))
    }

    @Test fun appliedDeliveryStillRequiresSeparateAcceptanceBeforeQueueOrCursorChanges() = runBlocking<Unit> {
        val row = coreObservation(); register()
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input)
            else MaterialSocketServer.Reply(reply(input).bytes.toString(Charsets.UTF_8)
                .replace("\"status\":\"rejected\",\"error_code\":\"INVALID_PAYLOAD\"",
                    "\"status\":\"applied\",\"revision\":9007199254740993").toByteArray())
        }
        val result = requireNotNull(sender(http).sendOperation(access(), row.operationId)).result.results.single()
        assertEquals("applied", result.status); assertNull(result.errorCode)
        assertEquals(9_007_199_254_740_993L, result.revision)
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        storage.reopen()
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(0L, tokens.syncCursor.first())
        assertEquals(1, count("next_request_origins")); assertEquals(1, count("next_transmissions"))
        assertEquals(0, count("sync_entity_state"))
    }

    @Test fun retiredTimerIdentityCannotBeReusedForAnotherNewSession() = runBlocking<Unit> {
        val old = start()
        db.timeLogDao().deleteTimerCommand(old.id) // Model later explicit consumption, not implicit delivery.
        val logCount = count("timelogs")
        assertEquals(NextRequestException.Reason.REQUEST_ID_REUSED,
            (rejected { start(session = 120, command = 21) } as NextRequestException).reason)
        assertEquals(logCount, count("timelogs")); assertEquals(0, count("timer_command_outbox"))
        assertEquals(1, count("next_request_origins"))
    }

    @Test fun exactHighRevisionIsCapturedAndNonIntegerStoredRevisionCannotBeCoerced() = runBlocking<Unit> {
        db.syncOutboxDao().upsertState(SyncEntityStateEntity("metric", metric.uuid, 9_007_199_254_740_993L, payloadJson = "{}"))
        producer().write(local()) { metrics().updateMetric(metric.copy(name = "Precise revision")) }
        val row = db.syncOutboxDao().getAll().single(); register(); val (http, server) = channel()
        assertNotNull(sender(http).sendOperation(access(), row.operationId))
        val wire = server.requests.single { it.path.endsWith("/push") }.body.toString(Charsets.UTF_8)
        assertTrue(wire.contains("\"base_revision\":9007199254740993"))
        val previous = requireNotNull(db.metricDao().getMetricById(metric.id))
        db.openHelper.writableDatabase.execSQL("UPDATE sync_entity_state SET revision=1.5")
        assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
            (rejected { producer().write(local()) { metrics().updateMetric(previous.copy(name = "Must rollback")) } } as NextRequestException).reason)
        assertEquals(previous, db.metricDao().getMetricById(metric.id)); assertEquals(1, count("next_request_origins"))
    }

    @Test fun newEpochEvenWithMatchingPublicDiscoveryCannotClaimAnOldFrozenRequest() = runBlocking<Unit> {
        val row = coreObservation(); register(); var epoch = id(3)
        val (http, server) = channel {
            if (it.path.endsWith("/identity")) MaterialSocketServer.Reply("""{"server_instance_id":"${id(2)}","sync_epoch":"$epoch","protocol_version":5,"capabilities":["sync.read"],"server_time":"$time"}""".toByteArray())
            else null
        }
        assertTrue(rejected { sender(http).sendOperation(access(), row.operationId) } is IOException)
        val original = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        epoch = id(130); tokens.saveServerIdentity(id(2), epoch)
        assertEquals(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED,
            (rejected { sender(http).sendOperation(access(), row.operationId) } as NextRequestException).reason)
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        assertArrayEquals(original, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertEquals(id(3), transmission(NEXT_OPERATION, row.operationId).syncEpoch)
    }

    @Test fun cancellationInsideBusinessWriteRollsBackQueueAndOrigins() = runBlocking<Unit> {
        val wrote = CompletableDeferred<Unit>(); val owner = SupervisorJob()
        val taskScope = CoroutineScope(owner + Dispatchers.IO)
        val originalDatabase = db; val originalOwner = requireNotNull(CountTrustRuntime.forDatabase(db))
        try {
            val session = local()
            native.beginProducerObservation()
            val task = taskScope.async<Unit> {
                producer().write(session) {
                    val saved = insertProducerObservation("cancel")
                    requireInsertedObservationBeforeOrigin(saved)
                    assertEquals(1, count("metric_logs")); assertEquals(1, count("sync_outbox"))
                    assertEquals(0, count("next_request_origins"))
                    native.observeProducerCallback()
                    wrote.complete(Unit)
                    awaitCancellation()
                }
            }
            withTimeout(5000) {
                select<Unit> {
                    wrote.onAwait { }
                    task.onAwait { error("Producer returned before the requested cancellation") }
                }
            }
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
            try { task.await(); fail("Cancelled producer must not return success") }
            catch (_: CancellationException) { /* Only the explicitly cancelled task is expected. */ }
            native.requireProducerRolledBack()
            assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
            assertSame(originalDatabase, db); assertSame(originalOwner, CountTrustRuntime.forDatabase(db))
            val retry = coreObservation()
            assertEquals(1, count("metric_logs")); assertEquals(1, count("sync_outbox"))
            assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, retry.operationId))
        } finally { owner.cancelAndJoin() }
    }

    @Test fun knownDeniedPermissionsAndStaleLocalAccountRollbackTheBusinessWrite() = runBlocking<Unit> {
        val stale = local(); register(permissions = setOf("sync.read"))
        assertEquals(NextRequestException.Reason.PERMISSION_DENIED, (rejected { coreObservation() } as NextRequestException).reason)
        assertEquals(NextRequestException.Reason.PERMISSION_DENIED,
            (rejected { producer().write(local()) { habits().updateHabit(habit.copy(name = "Denied")) } } as NextRequestException).reason)
        assertEquals(NextRequestException.Reason.PERMISSION_DENIED, (rejected { start() } as NextRequestException).reason)
        assertEquals(0, count("metric_logs")); assertEquals(0, count("timelogs")); assertEquals(0, count("next_request_origins"))
        assertEquals(habit, db.habitDao().getHabitById(habit.id))
        assertEquals(NextRequestException.Reason.STALE_ACCESS,
            (rejected { producer().write(stale) { error("stale callback must not run") } } as NextRequestException).reason)
    }

    @Test fun alteredOldQueueCannotBeRelabelledEvenWithZeroAttempts() = runBlocking<Unit> {
        val old = TimerCommandEntity(commandId = id(90), sessionUuid = id(91), sequence = 1,
            commandType = "start", occurredAt = millis, expectedControlGeneration = 0)
        val oldId = db.timeLogDao().insertTimerCommand(old)
        native.beginProducerObservation()
        assertEquals(NextRequestException.Reason.SOURCE_CHANGED, (rejected {
            producer().write(local()) {
                db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET commandId=? WHERE id=?", arrayOf<Any>(id(92), oldId))
                val saved = insertProducerObservation("must rollback")
                requireInsertedObservationBeforeOrigin(saved)
                assertEquals(id(92), db.timeLogDao().getTimerCommand(oldId)!!.commandId)
                native.observeProducerCallback()
            }
        } as NextRequestException).reason)
        native.requireProducerRolledBack()
        assertEquals(old.copy(id = oldId), db.timeLogDao().getTimerCommand(oldId))
        assertEquals(0, count("metric_logs")); assertEquals(0, count("next_request_origins"))
    }

    @Test fun rawSqlIntegerWidthAndTypeCorruptionFailBeforeRoomCanCoerceTheOrigin() = runBlocking<Unit> {
        val row = coreObservation(); register(); val (http, server) = channel()
        for (value in listOf("4294967301", "5.5", "'bad'")) {
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET protocol=$value")
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
                (rejected { sender(http).sendOperation(access(), row.operationId) } as NextRequestException).reason)
            assertEquals(0, count("next_transmissions"))
        }
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET protocol=5")
        assertNotNull(sender(http).sendOperation(access(), row.operationId))
    }

    @Test fun persistedMalformedWireCannotBecomeAValidRequestByReencoding() = runBlocking<Unit> {
        val row = coreObservation(); register(); val (http, server) = channel()
        assertNotNull(sender(http).sendOperation(access(), row.operationId))
        val original = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        val bad = (original.toString(Charsets.UTF_8).dropLast(1) + ",\"unexpected\":true}").toByteArray()
        db.openHelper.writableDatabase.execSQL("UPDATE next_transmissions SET wireBytes=?,wireHash=?", arrayOf<Any>(bad, nextRequestHash(bad)))
        storage.reopen()
        assertTrue(rejected { sender(http).sendOperation(access(), row.operationId) } is IllegalArgumentException)
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        assertArrayEquals(bad, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertEquals(row, db.syncOutboxDao().getById(row.id))
    }

    @Test fun blockedResponseDoesNotHoldDatabaseOrAccountLocksAndOldAccountCannotReturnDelivery() = runBlocking<Unit> {
        val row = coreObservation(); register(); val captured = access()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val owner = SupervisorJob()
        val taskScope = CoroutineScope(owner + Dispatchers.IO)
        val (http, _) = channel {
            if (!it.path.endsWith("/identity")) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            reply(it)
        }
        try {
            val task = taskScope.async { runCatching { sender(http).sendOperation(captured, row.operationId) } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withTimeout(3000) { sessions.exclusive { db.withTransaction { assertEquals(row, db.syncOutboxDao().getById(row.id)) } } }
            tokens.saveLoginSession("synthetic-other", "synthetic-other-refresh", "other", id(99), false)
            release.countDown()
            assertNotNull(withTimeout(5000) { task.await() }.exceptionOrNull())
            assertEquals(id(1), transmission(NEXT_OPERATION, row.operationId).accountId)
            assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(0L, tokens.syncCursor.first())
        } finally { release.countDown(); owner.cancelAndJoin() }
    }

    @Test fun offlineFullTimerTransitionCommandsSurviveReopenWithExactElapsedAndSequence() = runBlocking<Unit> {
        val start = start(); val dao = db.timeLogDao()
        val log = requireNotNull(dao.getTimeLogByUuid(start.sessionUuid))
        producer().write(local()) {
            dao.updatePauseAndQueue(log.id, true, millis + 30000, 0, 3, millis + 30000, 30000, null, null,
                TimerCommandEntity(commandId = id(22), sessionUuid = start.sessionUuid, sequence = 2, commandType = "pause",
                    occurredAt = millis + 30000, expectedControlGeneration = 1, activeElapsedMillis = 30000))
        }
        producer().write(local()) {
            dao.updatePauseAndQueue(log.id, false, null, 10000, 4, millis + 40000, 30000, null, null,
                TimerCommandEntity(commandId = id(23), sessionUuid = start.sessionUuid, sequence = 3, commandType = "resume",
                    occurredAt = millis + 40000, expectedControlGeneration = 1, activeElapsedMillis = 30000),
                TimerSegmentEntity(sessionUuid = start.sessionUuid, sequence = 2, startedAt = millis + 40000))
        }
        producer().write(local()) {
            dao.finishTimerAndQueue(log.id, millis + 70000, 60, 10000, 5, 60000,
                TimerCommandEntity(commandId = id(24), sessionUuid = start.sessionUuid, sequence = 4, commandType = "stop",
                    occurredAt = millis + 70000, expectedControlGeneration = 1, activeElapsedMillis = 60000), false)
        }
        val completed = dao.getById(log.id); val commands = dao.getPendingTimerCommands()
        storage.reopen(); register(); val captured = access(); val (http, server) = channel()
        commands.forEach { assertNotNull(sender(http).sendCommand(captured, it.commandId)) }
        val sent = server.requests.filter { it.path.endsWith("/commands") }.map {
            Json.parseToJsonElement(it.body.toString(Charsets.UTF_8)).jsonObject.getValue("commands").jsonArray.single().jsonObject
        }
        assertEquals(listOf("start", "pause", "resume", "stop"), sent.map { it.getValue("command_type").jsonPrimitive.content })
        assertEquals(listOf(1, 2, 3, 4), sent.map { it.getValue("sequence").jsonPrimitive.int })
        assertEquals(listOf(JsonNull, JsonPrimitive(30000), JsonPrimitive(30000), JsonPrimitive(60000)), sent.map { it.getValue("active_elapsed_ms") })
        assertEquals(commands, db.timeLogDao().getPendingTimerCommands()); assertEquals(completed, db.timeLogDao().getById(log.id))
        assertEquals(listOf(millis + 30000, millis + 70000), db.timeLogDao().getTimerSegments(start.sessionUuid).map { it.endedAt })
    }

    @Test fun undoGetsStableNewEventIdentityAndCapturedTimeWithoutRewritingOldQueue() = runBlocking<Unit> {
        val session = local()
        producer().write(session) {
            val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
                uuid = id(100), date = millis, actualCompletedAt = millis, value = 3, recordedTimezone = "Asia/Shanghai")
            NextCountDayStore(db).capture(habit, fact)
            db.completionDao().insertForSync(fact)
        }
        val before = db.syncOutboxDao().getAll().single()
        producer().write(session) { db.completionDao().deleteByHabitId(habit.id) }
        val undo = db.syncOutboxDao().getAll().last()
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, undo.operationId))
        val intent = Json.parseToJsonElement(origin.intentJson).jsonObject
        assertNotEquals(undo.entityUuid, intent.getValue("entity_uuid").jsonPrimitive.content)
        assertEquals(JsonPrimitive("upsert"), intent["action"])
        assertEquals(JsonPrimitive(undo.entityUuid), intent.getValue("payload").jsonObject["reverts_event_uuid"])
        assertEquals(JsonPrimitive(Instant.ofEpochMilli(undo.createdAt).toString()), intent.getValue("payload").jsonObject["occurred_at"])
        storage.reopen(); register(); val (http, _) = channel()
        assertNotNull(sender(http).sendOperation(access(), undo.operationId))
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, undo.operationId))
        assertEquals(before, db.syncOutboxDao().getById(before.id)); assertEquals(undo, db.syncOutboxDao().getById(undo.id))
    }

    @Test fun cancelledOfflineTimerRetainsCommandsWithoutNeedingADeletedLocalSessionForReplay() = runBlocking<Unit> {
        val start = start(); val log = requireNotNull(db.timeLogDao().getTimeLogByUuid(start.sessionUuid))
        producer().write(local()) {
            db.timeLogDao().deleteTimerAndQueue(log, TimerCommandEntity(commandId = id(25), sessionUuid = start.sessionUuid,
                sequence = 2, commandType = "cancel", occurredAt = millis + 10000, expectedControlGeneration = 1,
                activeElapsedMillis = 10000))
        }
        storage.reopen(); assertNull(db.timeLogDao().getTimeLogByUuid(start.sessionUuid))
        assertTrue(db.timeLogDao().getTimerSegments(start.sessionUuid).isEmpty())
        val commands = db.timeLogDao().getPendingTimerCommands(); register(); val (http, server) = channel()
        commands.forEach { assertNotNull(sender(http).sendCommand(access(), it.commandId)) }
        val wire = server.requests.last { it.path.endsWith("/commands") }.body.toString(Charsets.UTF_8)
        assertTrue(wire.contains("\"command_type\":\"cancel\"")); assertTrue(wire.contains("\"active_elapsed_ms\":10000"))
        assertEquals(commands, db.timeLogDao().getPendingTimerCommands()); assertEquals(0, count("timelogs"))
    }

    @Test fun suppressedOrDamagedOutboxControlCannotCommitAnUnqueuedBusinessWrite() = runBlocking<Unit> {
        val sql = db.openHelper.writableDatabase
        native.beginProducerObservation()
        assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (rejected {
            producer().write(local()) {
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                insertProducerObservation("must rollback")
                assertEquals(1, count("metric_logs")); assertEquals(0, count("sync_outbox"))
                assertEquals(0, count("next_request_origins"))
                sql.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use {
                    assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
                }
                native.observeProducerCallback()
            }
        } as NextRequestException).reason)
        native.requireProducerRolledBack()
        assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
        sql.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
        for (bad in listOf("1", "0.5", "'bad'")) {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=$bad WHERE id=1")
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (rejected {
                producer().write(local()) { error("invalid control must not run business callback") }
            } as NextRequestException).reason)
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        coreObservation(); assertEquals(1, count("next_request_origins"))
    }

    @Test fun ordinaryObservationSuccessAtomicallyAcceptsCanonicalTimeShadowQueueAndReceipt() = runBlocking<Unit> {
        val row = coreObservation(); val oldLog = requireNotNull(db.metricLogDao().getLogByUuid(row.entityUuid))
        val (store, delivery) = success(row)
        val original = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        assertUnaccepted(row)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        val shadow = requireNotNull(db.syncOutboxDao().getState("metric_observation", row.entityUuid))
        assertEquals(delivery.result.results.single().entity, Json.parseToJsonElement(requireNotNull(shadow.payloadJson)))
        assertEquals(syncPayloadHash(requireNotNull(shadow.payloadJson)), shadow.payloadHash)
        assertNull(db.syncOutboxDao().getById(row.id))
        assertEquals(oldLog.value, db.metricLogDao().getLogByUuid(row.entityUuid)!!.value, 0.0)
        assertEquals(oldLog.date, db.metricLogDao().getLogByUuid(row.entityUuid)!!.date)
        assertEquals(21.125, db.metricLogDao().getLogByUuid(row.entityUuid)!!.value, 0.0)
        storage.reopen()
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertEquals("2026-10-06T00:00:01.000123Z", Json.parseToJsonElement(db.syncOutboxDao().getState("metric_observation", row.entityUuid)!!.payloadJson!!)
            .jsonObject["received_at"]!!.jsonPrimitive.content)
        assertArrayEquals(original, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun actualSendAndAcceptCommitsAndColdReplayNeedsNoSecondHttpOrOverwrite() = runBlocking<Unit> {
        producer().write(local()) { metrics().updateMetric(metric.copy(name = "First intent")) }
        val row = db.syncOutboxDao().getAll().single(); register()
        val (http, server) = channel { successReply(it) }
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        val first = requireNotNull(db.metricDao().getMetricById(metric.id))
        assertEquals("First intent", first.name); assertEquals(millis + 1000, first.createdAt)
        producer().write(local()) { metrics().updateMetric(first.copy(name = "Later offline edit")) }
        val suffix = db.syncOutboxDao().getAll().single()
        val current = db.metricDao().getMetricById(metric.id)
        storage.reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(current, db.metricDao().getMetricById(metric.id)); assertEquals(suffix, db.syncOutboxDao().getById(suffix.id))
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
    }

    @Test fun exactAppliedAndAlreadyAppliedCallbacksAreEquivalentButChangedResultIsNot() = runBlocking<Unit> {
        val row = coreObservation(); val (store, delivery) = success(row)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        val repeated = delivery.copy(result = delivery.result.copy(results = listOf(delivery.result.results.single().copy(status = "already_applied"))))
        assertEquals(NextOperationAcceptance.REPLAYED, store.acceptOperation(repeated))
        val result = repeated.result.results.single()
        val changed = repeated.copy(result = repeated.result.copy(results = listOf(result.copy(entity =
            JsonObject(result.entity!! + ("note" to JsonPrimitive("different")))))))
        assertEquals(NextRequestException.Reason.RESULT_CHANGED, (rejected { store.acceptOperation(changed) } as NextRequestException).reason)
        assertEquals(1, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
    }

    @Test fun concurrentCallbacksConsumeExactlyOnce() = runBlocking<Unit> {
        val row = coreObservation(); val (store, delivery) = success(row)
        val outcomes = coroutineScope { listOf(async(Dispatchers.IO) { store.acceptOperation(delivery) },
            async(Dispatchers.IO) { store.acceptOperation(delivery) }).awaitAll() }
        assertEquals(setOf(NextOperationAcceptance.COMMITTED, NextOperationAcceptance.REPLAYED), outcomes.toSet())
        assertEquals(1, count("next_acceptances")); assertEquals(0, count("sync_outbox")); assertEquals(1, count("metric_logs"))
    }

    @Test fun acceptanceOfFirstMetricEditPreservesLaterEditableFieldsAndOriginalSuffix() = runBlocking<Unit> {
        producer().write(local()) { metrics().updateMetric(metric.copy(name = "Sent name")) }
        val row = db.syncOutboxDao().getAll().single(); val (store, delivery) = success(row)
        producer().write(local()) { metrics().updateMetric(db.metricDao().getMetricById(metric.id)!!.copy(name = "Offline suffix", decimalPlaces = 6)) }
        val suffix = db.syncOutboxDao().getAll().last()
        val origin = db.nextRequestDao().origin(NEXT_OPERATION, suffix.operationId)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        val current = requireNotNull(db.metricDao().getMetricById(metric.id))
        assertEquals("Offline suffix", current.name); assertEquals(6, current.decimalPlaces)
        assertEquals(millis + 1000, current.createdAt)
        assertEquals("Sent name", Json.parseToJsonElement(db.syncOutboxDao().getState("metric", metric.uuid)!!.payloadJson!!).jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(suffix, db.syncOutboxDao().getById(suffix.id)); assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, suffix.operationId))
    }

    @Test fun outOfOrderMetricCallbackCannotJumpAnUnacceptedPredecessor() = runBlocking<Unit> {
        producer().write(local()) { metrics().updateMetric(metric.copy(name = "Earlier")) }
        producer().write(local()) { metrics().updateMetric(db.metricDao().getMetricById(metric.id)!!.copy(name = "Later")) }
        val rows = db.syncOutboxDao().getAll()
        register()
        val (http, server) = channel { successReply(it) }
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendOperation(access(), rows.last().operationId) } as NextRequestException).reason)
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
        assertEquals(0, count("next_transmissions"))
        assertUnaccepted(rows.last()); assertUnaccepted(rows.first()); assertEquals(0, count("sync_entity_state"))
    }

    @Test fun recurringHabitAndGoalSuccessRetainUiFieldsAndCanonicalSingleParent() = runBlocking<Unit> {
        val goal = habit.copy(uuid = id(200), id = 0, name = "Goal", habitType = HabitType.GOAL,
            completionPolicy = null, targetValue = 1, planMetadata = PlanStructureMetadata(time, 0, null, null, null, null, null, null))
        producer().write(local()) { db.habitDao().insert(goal) }
        val goalRow = db.syncOutboxDao().getAll().single(); val (goalStore, goalDelivery) = success(goalRow)
        assertEquals(NextOperationAcceptance.COMMITTED, goalStore.acceptOperation(goalDelivery))
        producer().write(local()) { habits().updateHabit(habit.copy(name = "Nested", parentHabitId = goal.uuid)) }
        val childRow = db.syncOutboxDao().getAll().single(); val (childStore, childDelivery) = success(childRow)
        assertEquals(NextOperationAcceptance.COMMITTED, childStore.acceptOperation(childDelivery))
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        assertEquals(goal.uuid, current.parentHabitId); assertEquals(HabitType.COUNTING, current.habitType)
        assertEquals(habit.iconResId, current.iconResId); assertEquals(habit.appearance, current.appearance)
        assertEquals(2, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
    }

    @Test fun pendingHabitRenameIsNotOverwrittenByOlderAcceptedStructure() = runBlocking<Unit> {
        producer().write(local()) { habits().updateHabit(habit.copy(name = "Sent habit")) }
        val row = db.syncOutboxDao().getAll().single(); val (store, delivery) = success(row)
        producer().write(local()) { habits().updateHabit(db.habitDao().getHabitById(habit.id)!!.copy(name = "Later habit")) }
        val suffix = db.syncOutboxDao().getAll().last()
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertEquals("Later habit", db.habitDao().getHabitById(habit.id)!!.name)
        assertEquals(suffix, db.syncOutboxDao().getById(suffix.id))
        assertEquals("Sent habit", Json.parseToJsonElement(db.syncOutboxDao().getState("plan_node", habit.uuid)!!.payloadJson!!).jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test fun positiveCountingSnapshotSuccessIsConfirmedWithoutASecondLocalRecord() = runBlocking<Unit> {
        val row = completion(); val (store, delivery) = success(row)
        // Android records the cumulative value in both counting modes, never a fabricated delta.
        assertEquals("count_snapshot", delivery.result.results.single().entity!!["event_type"]!!.jsonPrimitive.content)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertEquals(1, count("completions")); assertEquals(3, db.completionDao().getCompletionByUuid(row.entityUuid)!!.value)
        assertEquals(0, count("sync_outbox"))
    }

    @Test fun countdownCountingSnapshotIsConfirmedWithoutChangingCompletionMeaning() = runBlocking<Unit> {
        val row = completion(countdown = true); val (store, delivery) = success(row)
        assertEquals("count_snapshot", delivery.result.results.single().entity!!["event_type"]!!.jsonPrimitive.content)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertEquals(3, db.completionDao().getCompletionByUuid(row.entityUuid)!!.value)
        assertEquals(1, count("next_acceptances"))
    }

    @Test fun checkInSuccessAndUndoUseDistinctImmutableWireEvents() = runBlocking<Unit> {
        val first = completion(HabitType.CHECK_IN); val (store, delivery) = success(first)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        producer().write(local()) { db.completionDao().delete(db.completionDao().getCompletionByUuid(first.entityUuid)!!) }
        val undo = db.syncOutboxDao().getAll().single(); val (undoStore, undoDelivery) = success(undo)
        val fact = undoDelivery.result.results.single().entity!!
        assertNotEquals(first.entityUuid, fact["public_id"]!!.jsonPrimitive.content)
        assertEquals(first.entityUuid, fact["reverts_event_uuid"]!!.jsonPrimitive.content)
        assertEquals(NextOperationAcceptance.COMMITTED, undoStore.acceptOperation(undoDelivery))
        assertEquals(0, count("completions")); assertEquals(2, count("next_acceptances"))
        assertNotNull(db.syncOutboxDao().getState("activity_event", first.entityUuid))
        assertNotNull(db.syncOutboxDao().getState("activity_event", fact["public_id"]!!.jsonPrimitive.content))
    }

    @Test fun eventAcceptedAfterOfflineUndoDoesNotResurrectDeletedLocalCompletion() = runBlocking<Unit> {
        val row = completion(); val (store, delivery) = success(row)
        producer().write(local()) { db.completionDao().delete(db.completionDao().getCompletionByUuid(row.entityUuid)!!) }
        val undo = db.syncOutboxDao().getAll().last()
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertEquals(0, count("completions")); assertEquals(undo, db.syncOutboxDao().getById(undo.id))
        assertEquals(1, count("next_acceptances"))
    }

    @Test fun linkSuccessPreservesEndpointsAndLaterLocalFlags() = runBlocking<Unit> {
        coreLink()
        val row = db.syncOutboxDao().getAll().single(); val (store, delivery) = success(row)
        val link = db.habitMetricLinkDao().getLinkByUuid(row.entityUuid)!!
        producer().write(local()) { db.habitMetricLinkDao().upsert(link.copy(promptOnComplete = false)) }
        val suffix = db.syncOutboxDao().getAll().last()
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        val saved = db.habitMetricLinkDao().getLinkByUuid(row.entityUuid)!!
        assertFalse(saved.promptOnComplete); assertEquals(habit.id, saved.habitId); assertEquals(metric.id, saved.metricId)
        assertEquals(suffix, db.syncOutboxDao().getById(suffix.id)); assertEquals(1, count("next_acceptances"))
    }

    @Test fun missingOrMalformedCanonicalFactProofNeverAcknowledgesSuccess() = runBlocking<Unit> {
        val row = coreObservation(); val (store, delivery) = success(row)
        val result = delivery.result.results.single()
        val invalid = listOf(result.copy(entity = null), result.copy(revision = null), result.copy(revision = 0),
            result.copy(entity = JsonObject(result.entity!! - "received_at")),
            result.copy(entity = JsonObject(result.entity!! + ("value" to JsonPrimitive(999)))),
            result.copy(entity = JsonObject(result.entity!! + ("source_device_id" to JsonPrimitive(id(99))))),
            result.copy(entity = JsonObject(result.entity!! + ("deleted_at" to JsonPrimitive(time)))),
            result.copy(errorCode = "INVALID_PAYLOAD"), result.copy(baseEntity = buildJsonObject {}))
        for (bad in invalid) {
            assertNotNull(rejected { store.acceptOperation(delivery.copy(result = delivery.result.copy(results = listOf(bad)))) })
            assertUnaccepted(row); assertEquals(0, count("sync_entity_state")); assertEquals(1, count("metric_logs"))
        }
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
    }

    @Test fun changedSourceOrRemovedQueueWithoutReceiptCannotBecomeAnAcknowledgement() = runBlocking<Unit> {
        val row = coreObservation(); val (store, delivery) = success(row)
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET lastError='changed' WHERE id=?", arrayOf<Any>(row.id))
        assertEquals(NextRequestException.Reason.SOURCE_CHANGED, (rejected { store.acceptOperation(delivery) } as NextRequestException).reason)
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        db.syncOutboxDao().deleteById(row.id)
        assertEquals(NextRequestException.Reason.SOURCE_CHANGED, (rejected { store.acceptOperation(delivery) } as NextRequestException).reason)
        assertEquals(0, count("sync_entity_state")); assertEquals(0, count("next_acceptances"))
    }

    @Test fun changedDevicePermissionRevisionAndAuthenticationRejectLateAcceptance() = runBlocking<Unit> {
        val row = coreObservation(); val (store, delivery) = success(row)
        register(device = id(99))
        assertEquals(NextRequestException.Reason.STALE_ACCESS, (rejected { store.acceptOperation(delivery) } as NextRequestException).reason)
        register(permissions = setOf("sync.read"), revision = 2)
        assertNotNull(rejected { store.acceptOperation(delivery) }); assertUnaccepted(row)
        tokens.saveLoginSession("synthetic-new", "synthetic-refresh", "member", id(1), false)
        assertNotNull(rejected { store.acceptOperation(delivery) }); assertUnaccepted(row)
    }

    @Test fun receiptAndQueueTriggerFailuresRollbackCanonicalProjectionAndCanRetry() = runBlocking<Unit> {
        producer().write(local()) { metrics().updateMetric(metric.copy(name = "Sent")) }
        val row = db.syncOutboxDao().getAll().single(); val (store, delivery) = success(row)
        val before = db.metricDao().getMetricById(metric.id)
        val faults = listOf(
            NextCoreRequestNativeFault.Kind.RECEIPT_ABORT,
            NextCoreRequestNativeFault.Kind.RECEIPT_IGNORE,
            NextCoreRequestNativeFault.Kind.RECEIPT_HASH,
            NextCoreRequestNativeFault.Kind.RECEIPT_QUEUE_ERROR,
            NextCoreRequestNativeFault.Kind.QUEUE_ABORT,
            NextCoreRequestNativeFault.Kind.QUEUE_IGNORE,
            NextCoreRequestNativeFault.Kind.QUEUE_WIRE_HASH,
            NextCoreRequestNativeFault.Kind.QUEUE_DELETE_RECEIPT,
            NextCoreRequestNativeFault.Kind.QUEUE_RECEIPT_HASH,
            NextCoreRequestNativeFault.Kind.QUEUE_SHADOW_HASH)
        for (fault in faults) {
            native.arm(fault, NextCoreRequestNativeFault.Targets(row.operationId, row.id, metric.id, metric.uuid, id(1)))
            val failure = rejected { store.acceptOperation(delivery) }
            assertNotNull(failure); native.requireTriggered(failure); native.requireWarmRollback()
            assertUnaccepted(row); assertEquals(before, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("sync_entity_state"))
        }
        native.expectWarmRetry()
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        native.requireWarmRetryCommitted()
    }

    @Test fun acceptanceFinalCommitFailureIsNotSuccessAndColdRetryPreservesOriginalRequest() = runBlocking<Unit> {
        val row = coreObservation(); val (store, delivery) = success(row)
        val original = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        native.arm(NextCoreRequestNativeFault.Kind.ACCEPTANCE_END,
            NextCoreRequestNativeFault.Targets(row.operationId, row.id, metric.id, metric.uuid, id(1)))
        val failure = rejected { store.acceptOperation(delivery) }
        assertTrue(failure is SQLiteConstraintException); native.requireTriggered(failure)
        storage.reopen(); assertTrue(db.openHelper.writableDatabase.isOpen)
        native.requireColdFaultAbsent()
        assertUnaccepted(row); assertEquals(0, count("sync_entity_state"))
        assertArrayEquals(original, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        val (http, _) = channel { successReply(it) }
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        storage.reopen()
        assertEquals(1, count("next_acceptances")); assertNull(db.syncOutboxDao().getById(row.id))
    }

    @Test fun corruptReceiptCannotAuthorizeReplayOrReinsertConsumedWork() = runBlocking<Unit> {
        val row = coreObservation(); val (store, delivery) = success(row)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        val receipt = db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId)!!
        for (statement in listOf("UPDATE next_acceptances SET resultJson='{}'",
            "UPDATE next_acceptances SET resultHash=CAST(resultHash AS BLOB)",
            "UPDATE next_acceptances SET originHash='different'")) {
            db.openHelper.writableDatabase.execSQL(statement)
            assertNotNull(rejected { store.acceptOperation(delivery) })
            assertNull(db.syncOutboxDao().getById(row.id)); assertEquals(1, count("metric_logs"))
            db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=?,originHash=?",
                arrayOf<Any>(receipt.resultJson, receipt.resultHash, receipt.originHash))
        }
        assertEquals(NextOperationAcceptance.REPLAYED, store.acceptOperation(delivery))
    }

    @Test fun rejectedDeleteResultKeepsPendingWorkAndNoCursor() = runBlocking<Unit> {
        coreDelete()
        val row = db.syncOutboxDao().getAll().single(); register(); val (http, _) = channel()
        val store = sender(http); val delivery = requireNotNull(store.sendOperation(access(), row.operationId))
        assertEquals("INVALID_PAYLOAD", delivery.result.results.single().errorCode)
        assertTrue(rejected { store.acceptOperation(delivery) } is IllegalArgumentException)
        assertUnaccepted(row)
    }

    @Test fun acceptanceLeavesUnrelatedOfflineTimerCommandsAndOriginalOnceProofUntouched() = runBlocking<Unit> {
        val timer = start()
        val timerOrigin = db.nextRequestDao().origin(NEXT_TIMER, timer.commandId)
        val once = OneTimeTransmissionEntity(id(230), id(1), id(2), id(3), id(4), "original-once-bytes")
        db.completionFollowUpDao().insertTransmission(once)
        val row = coreObservation(); val (store, delivery) = success(row)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        storage.reopen()
        assertEquals(timer, db.timeLogDao().getTimerCommand(timer.id))
        assertEquals(timerOrigin, db.nextRequestDao().origin(NEXT_TIMER, timer.commandId))
        assertEquals(once, db.completionFollowUpDao().transmission(once.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_TIMER, timer.commandId))
    }

    @Test fun cancelledQueuedAcceptanceNeverCommitsBusinessShadowOrReceipt() = runBlocking<Unit> {
        val row = coreObservation(); val (store, delivery) = success(row)
        sessions.exclusive {
            val task = launch(start = CoroutineStart.UNDISPATCHED) { store.acceptOperation(delivery) }
            task.cancelAndJoin()
        }
        assertUnaccepted(row); assertEquals(0, count("sync_entity_state"))
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
    }

    @Test fun ordinaryCheckCannotConsumeAResultCarryingAnUnrelatedOneTimeProof() = runBlocking<Unit> {
        val row = completion(HabitType.CHECK_IN); val (store, delivery) = success(row)
        val result = delivery.result.results.single()
        val payload = JsonObject(result.entity!! + mapOf(
            "one_time" to buildJsonObject {
                put("event_uuid", row.entityUuid); put("action", "complete"); put("expected_version", 0)
                put("expected_head_event_uuid", JsonNull); put("reverts_event_uuid", JsonNull)
            },
            "one_time_state_after" to buildJsonObject {
                put("version", 1); put("head_event_uuid", row.entityUuid); put("completion_event_uuid", row.entityUuid)
            }))
        val changed = delivery.copy(result = delivery.result.copy(results = listOf(result.copy(entity = payload))))
        assertTrue(rejected { store.acceptOperation(changed) } is IllegalArgumentException)
        assertUnaccepted(row); assertEquals(0, count("sync_entity_state")); assertEquals(1, count("completions"))
    }
}
