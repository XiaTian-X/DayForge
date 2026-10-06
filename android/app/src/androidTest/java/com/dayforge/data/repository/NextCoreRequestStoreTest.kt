package com.dayforge.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.authenticator.TokenAuthenticator
import com.dayforge.data.api.interceptor.AuthInterceptor
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production file Room/DataStore + real HTTP. Delivery is deliberately not called an ACK. */
@RunWith(AndroidJUnit4::class)
class NextCoreRequestStoreTest {
    @get:Rule val storage = PhysicalDatabaseRule()
    private val db get() = storage.database
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val sessions = AccountSessionCoordinator()
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var client: OkHttpClient
    private lateinit var habit: HabitEntity
    private lateinit var timerHabit: HabitEntity
    private lateinit var metric: MetricEntity
    private val servers = mutableListOf<MaterialSocketServer>()
    private val caps = setOf("sync.read", "structure.write", "facts.append", "timer.control")
    private val time = "2026-10-06T00:00:00Z"
    private val millis get() = Instant.parse(time).toEpochMilli()
    private fun id(n: Int) = "aa310000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun producer() = NextCoreLocalIntentStore(db, tokens, sessions)
    private suspend fun local() = requireNotNull(tokens.localCoreWriteAccess()).session
    private suspend fun access() = requireNotNull(tokens.localSyncAccess())
    private suspend fun register(device: String = id(4), permissions: Set<String> = caps, revision: Int = 1) {
        tokens.saveServerIdentity(id(2), id(3))
        tokens.saveDeviceRegistration(device, permissions, true, revision)
    }
    private fun habits() = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db)
    private fun metrics() = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao())

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        directory = Files.createTempDirectory(app.filesDir.toPath(), "next-journal-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") })
        tokens = TokenManager(data); preferences = PreferencesManager(data)
        client = OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(preferences)).addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, preferences, SelectedNetworkTransport())).build()
        tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val row = HabitEntity(uuid = id(10), name = "Recurring count", habitType = HabitType.COUNTING,
                iconResId = 0, colorHex = "#000000", schedule = HabitSchedule.Daily, targetValue = 10,
                failMode = FailMode.LOOSE, createdAt = millis, updatedAt = millis, completionPolicy = "recurring",
                appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
                planMetadata = PlanStructureMetadata(time, 0, null, null, "Asia/Shanghai", null, null, null))
            habit = row.copy(id = db.habitDao().insert(row))
            val timer = row.copy(uuid = id(12), name = "Complete timer", habitType = HabitType.TIMER, targetValue = 1,
                planMetadata = requireNotNull(row.planMetadata).copy(targetUnit = "second"))
            timerHabit = timer.copy(id = db.habitDao().insert(timer))
            val m = MetricEntity(uuid = id(11), name = "Weight", unit = "kg", decimalPlaces = 3, iconResId = 0,
                colorHex = "#000000", createdAt = millis, updatedAt = millis,
                appearance = ObjectAppearance(IconReference.Role("metric.custom"), "#123456", "object"))
            metric = m.copy(id = db.metricDao().insert(m))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }

    @After fun cleanup() = runBlocking<Unit> {
        var failure: Throwable? = null
        suspend fun finish(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        servers.forEach { finish { it.close() } }
        finish { if (::client.isInitialized) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }
        finish { if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin() }
        finish { if (::directory.isInitialized) assertTrue(directory.deleteRecursively()) }
        failure?.let { throw it }
    }

    private fun reply(input: MaterialSocketServer.Input, version: Int = 5): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) {
            assertNull(input.headers["authorization"])
            return MaterialSocketServer.Reply("""{"server_instance_id":"${id(2)}","sync_epoch":"${id(3)}","protocol_version":$version,"capabilities":["sync.read"],"server_time":"$time"}""".toByteArray())
        }
        assertEquals("5", input.headers["x-dayforge-protocol"])
        assertEquals(id(2), input.headers["x-dayforge-server-instance"])
        assertEquals(id(3), input.headers["x-dayforge-sync-epoch"])
        val body = Json.parseToJsonElement(input.body.toString(Charsets.UTF_8)).jsonObject
        val result = if (input.path.endsWith("/push")) {
            val operation = body.getValue("operations").jsonArray.single().jsonObject
            """{"results":[{"operation_id":${operation.getValue("operation_id")},"entity_type":${operation.getValue("entity_type")},"entity_uuid":${operation.getValue("entity_uuid")},"status":"rejected","error_code":"INVALID_PAYLOAD"}]}"""
        } else {
            assertEquals("/api/v2/timers/commands", input.path)
            val command = body.getValue("commands").jsonArray.single().jsonObject
            """{"results":[{"command_id":${command.getValue("command_id")},"session_id":${command.getValue("session_id")},"status":"already_applied","error_code":null,"message":null,"session":null}],"server_time":"$time"}"""
        }
        return MaterialSocketServer.Reply(result.toByteArray())
    }
    private suspend fun channel(respond: (MaterialSocketServer.Input) -> MaterialSocketServer.Reply? = { reply(it) }): Pair<NextSyncHttp, MaterialSocketServer> {
        val server = MaterialSocketServer { input, _ -> respond(input) }; servers.add(server)
        preferences.setServerUrl(server.origin.toString())
        return NextSyncHttp(client, tokens, server.origin) to server
    }
    private fun sender(http: NextSyncHttp) = NextCoreRequestStore(db, tokens, sessions, http)
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { if (error is CancellationException) throw error; return error }
        throw AssertionError("Must reject")
    }
    private suspend fun observation(): SyncOutboxEntity {
        producer().write(local()) { metrics().recordValue(metric.id, 21.125, "captured\nvalue", millis) }
        return db.syncOutboxDao().getAll().last()
    }
    private suspend fun start(session: Int = 20, command: Int = 21): TimerCommandEntity {
        producer().write(local()) {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, startTime = millis, endTime = null,
                durationSeconds = 0, date = millis, uuid = id(session), timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                TimerCommandEntity(commandId = id(command), sessionUuid = id(session), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(session), sequence = 1, startedAt = millis))
        }
        return db.timeLogDao().getPendingTimerCommands(Int.MAX_VALUE).last()
    }
    private suspend fun transmission(kind: String, request: String) = requireNotNull(db.nextRequestDao().transmission(kind, request))
    private fun count(table: String): Int = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test fun offlineNewBusinessAndOriginsCommitTogetherWithoutAdoptingOldZeroAttemptQueues() = runBlocking<Unit> {
        val old = SyncOutboxEntity(operationId = id(40), recordType = "metric", entityUuid = metric.uuid,
            wireEntityUuid = metric.uuid, action = "upsert", payloadJson = "{\"old\":true}", attemptedAt = 1, attemptCount = 1)
        val oldId = db.syncOutboxDao().insert(old)
        val lost = TimerCommandEntity(commandId = id(41), sessionUuid = id(42), sequence = 1,
            commandType = "start", occurredAt = millis, expectedControlGeneration = 0, attemptCount = 0)
        val lostId = db.timeLogDao().insertTimerCommand(lost)
        val observation = observation(); val timer = start()
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
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
        assertEquals(0, count("next_transmissions"))
    }

    @Test fun lostResponseThenColdReopenReplaysWholeOriginalEnvelopeAndNeverConsumesTheQueue() = runBlocking<Unit> {
        val observation = observation(); val timer = start(); register()
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
        producer().write(session) {
            habits().updateHabit(habit.copy(name = "First name"))
            metrics().updateMetric(metric.copy(name = "First metric"))
            db.completionDao().insertForSync(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
                uuid = id(50), date = millis, actualCompletedAt = millis, value = 3, recordedTimezone = "Asia/Shanghai"))
            metrics().recordValue(metric.id, 21.125, "note", millis)
            db.habitMetricLinkDao().insertOrIgnore(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                metricId = metric.id, metricUuid = metric.uuid, uuid = id(51), coefficient = 0.5,
                showInHabitDetail = true, promptOnComplete = true))
        }
        val rows = db.syncOutboxDao().getAll(); assertEquals(5, rows.size)
        producer().write(session) {
            habits().updateHabit(requireNotNull(db.habitDao().getHabitById(habit.id)).copy(name = "Later name"))
            metrics().updateMetric(requireNotNull(db.metricDao().getMetricById(metric.id)).copy(name = "Later metric"))
        }
        register(); val captured = access(); val (http, server) = channel()
        rows.forEach { assertNotNull(sender(http).sendOperation(captured, it.operationId)) }
        val sent = server.requests.filter { it.path.endsWith("/push") }.map {
            Json.parseToJsonElement(it.body.toString(Charsets.UTF_8)).jsonObject.getValue("operations").jsonArray.single().jsonObject
        }.associateBy { it.getValue("entity_type").jsonPrimitive.content }
        assertEquals(setOf("plan_node", "metric", "activity_event", "metric_observation", "activity_metric_link"), sent.keys)
        assertEquals(JsonPrimitive("First name"), sent.getValue("plan_node").getValue("payload").jsonObject["title"])
        assertEquals(JsonPrimitive("First metric"), sent.getValue("metric").getValue("payload").jsonObject["name"])
        assertEquals(JsonPrimitive(3), sent.getValue("activity_event").getValue("payload").jsonObject["value"])
        assertEquals(7, db.syncOutboxDao().getAll().size); assertEquals(5, count("next_transmissions"))
    }

    @Test fun sameAccountReauthenticationUsesCurrentCredentialsButOriginalDurableIdentity() = runBlocking<Unit> {
        val row = observation(); register(); var drop = true
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
        val row = observation(); register(); val (http, server) = channel { if (it.path.endsWith("/identity")) reply(it) else null }
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
        val row = observation(); val command = start(); register()
        for (version in listOf(4, 6)) {
            val (http, server) = channel { reply(it, version) }
            assertNull(sender(http).sendOperation(access(), row.operationId))
            assertNull(sender(http).sendCommand(access(), command.commandId))
            assertEquals(0, count("next_transmissions")); assertTrue(server.requests.all { it.path.endsWith("/identity") })
        }
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(command, db.timeLogDao().getTimerCommand(command.id))
    }

    @Test fun failedOriginInsertRollsBackRealRepositoryWriteAndQueue() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_origin BEFORE INSERT ON next_request_origins BEGIN SELECT RAISE(ABORT,'synthetic origin failure'); END")
        assertNotNull(rejected { observation() })
        assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_origin")
        observation(); storage.reopen()
        assertEquals(1, count("metric_logs")); assertEquals(1, count("next_request_origins"))
    }

    @Test fun failedFirstJournalInsertLeavesOriginalIntentAndSendsNoBusinessRequest() = runBlocking<Unit> {
        val row = observation(); register()
        val origin = db.nextRequestDao().origin(NEXT_OPERATION, row.operationId)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_transmission BEFORE INSERT ON next_transmissions BEGIN SELECT RAISE(ABORT,'synthetic journal failure'); END")
        val (http, server) = channel()
        assertNotNull(rejected { sender(http).sendOperation(access(), row.operationId) })
        assertEquals(0, count("next_transmissions")); assertTrue(server.requests.all { it.path.endsWith("/identity") })
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, row.operationId)); assertEquals(row, db.syncOutboxDao().getById(row.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_transmission")
        assertNotNull(sender(http).sendOperation(access(), row.operationId)); assertEquals(1, count("next_transmissions"))
    }

    @Test fun failedFinalCommitNeverSendsAndColdReopenHasNoCommittedJournal() = runBlocking<Unit> {
        val row = observation(); register(); val sql = db.openHelper.writableDatabase
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, row.operationId))
        sql.execSQL("CREATE TABLE journal_commit_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE journal_commit_child(id INTEGER REFERENCES journal_commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER fail_final_commit AFTER INSERT ON next_transmissions BEGIN INSERT INTO journal_commit_child(id) VALUES(1); END")
        val (http, server) = channel()
        assertTrue(rejected { sender(http).sendOperation(access(), row.operationId) } is SQLiteConstraintException)
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
        // A deferred COMMIT failure can leave the framework writer connection in a native
        // transaction. A WAL reader seeing zero rows does not prove durable rollback.
        // Close/reopen the real file, as after process death; do not reuse that writer.
        storage.reopen()
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("journal_commit_child"))
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, row.operationId))
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertEquals(1, count("metric_logs"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_final_commit")
        assertNotNull(sender(http).sendOperation(access(), row.operationId))
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        storage.reopen()
        assertEquals(1, count("next_transmissions")); assertEquals(row, db.syncOutboxDao().getById(row.id))
    }

    @Test fun appliedDeliveryStillRequiresSeparateAcceptanceBeforeQueueOrCursorChanges() = runBlocking<Unit> {
        val row = observation(); register()
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
        val row = observation(); register(); var epoch = id(3)
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
        try {
            val session = local()
            val task = taskScope.launch {
                producer().write(session) { metrics().recordValue(metric.id, 21.125, "cancel", millis); wrote.complete(Unit); awaitCancellation() }
            }
            withTimeout(5000) { wrote.await() }; task.cancelAndJoin()
            assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
        } finally { owner.cancelAndJoin() }
    }

    @Test fun knownDeniedPermissionsAndStaleLocalAccountRollbackTheBusinessWrite() = runBlocking<Unit> {
        val stale = local(); register(permissions = setOf("sync.read"))
        assertEquals(NextRequestException.Reason.PERMISSION_DENIED, (rejected { observation() } as NextRequestException).reason)
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
        assertEquals(NextRequestException.Reason.SOURCE_CHANGED, (rejected {
            producer().write(local()) {
                db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET commandId=? WHERE id=?", arrayOf<Any>(id(92), oldId))
                metrics().recordValue(metric.id, 21.125, "must rollback", millis)
            }
        } as NextRequestException).reason)
        assertEquals(old.copy(id = oldId), db.timeLogDao().getTimerCommand(oldId))
        assertEquals(0, count("metric_logs")); assertEquals(0, count("next_request_origins"))
    }

    @Test fun rawSqlIntegerWidthAndTypeCorruptionFailBeforeRoomCanCoerceTheOrigin() = runBlocking<Unit> {
        val row = observation(); register(); val (http, server) = channel()
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
        val row = observation(); register(); val (http, server) = channel()
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
        val row = observation(); register(); val captured = access()
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
        producer().write(session) { db.completionDao().insertForSync(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
            uuid = id(100), date = millis, actualCompletedAt = millis, value = 3, recordedTimezone = "Asia/Shanghai")) }
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
        assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (rejected {
            producer().write(local()) {
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                metrics().recordValue(metric.id, 21.125, "must rollback", millis)
            }
        } as NextRequestException).reason)
        assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
        sql.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
        for (bad in listOf("1", "0.5", "'bad'")) {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=$bad WHERE id=1")
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (rejected {
                producer().write(local()) { error("invalid control must not run business callback") }
            } as NextRequestException).reason)
        }
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        observation(); assertEquals(1, count("next_request_origins"))
    }

    /** Mirrors authoritative serializer fields, without calling the production acceptance mappers. */
    private fun successReply(input: MaterialSocketServer.Input, revision: Long = 1,
        edit: (JsonObject) -> JsonObject = { it }): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        val op = Json.parseToJsonElement(input.body.toString(Charsets.UTF_8)).jsonObject
            .getValue("operations").jsonArray.single().jsonObject
        val payload = op.getValue("payload").jsonObject
        val type = op.getValue("entity_type").jsonPrimitive.content
        val serverTime = "2026-10-06T00:00:01.000123Z"
        val canonical = buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            put("public_id", op.getValue("entity_uuid")); put("revision", revision)
            put("created_at", payload["created_at"] ?: JsonPrimitive(serverTime))
            put("updated_at", serverTime); put("deleted_at", JsonNull)
            if (type in setOf("activity_event", "metric_observation")) {
                put("note", payload["note"] ?: JsonPrimitive(""))
                put("source_type", payload["source_type"] ?: JsonPrimitive("app"))
                put("source_device_id", payload["source_device_id"]?.takeUnless { it == JsonNull } ?: JsonPrimitive(id(4)))
                put("external_event_id", payload["external_event_id"] ?: JsonNull)
                put("metadata", payload["metadata"] ?: buildJsonObject {})
                put("received_at", serverTime)
                if (type == "activity_event") {
                    put("value", payload["value"]?.takeUnless { it == JsonNull } ?:
                        if (payload["event_type"] == JsonPrimitive("check_in")) JsonPrimitive(1) else JsonNull)
                    for (key in listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "reverts_event_uuid"))
                        put(key, payload[key] ?: JsonNull)
                }
            }
        }
        val result = buildJsonObject {
            put("operation_id", op.getValue("operation_id")); put("entity_type", op.getValue("entity_type"))
            put("entity_uuid", op.getValue("entity_uuid")); put("status", "applied"); put("revision", revision)
            put("entity", edit(canonical))
        }
        return MaterialSocketServer.Reply(buildJsonObject { put("results", JsonArray(listOf(result))) }.toString().toByteArray())
    }
    private suspend fun success(row: SyncOutboxEntity, edit: (JsonObject) -> JsonObject = { it }):
        Pair<NextCoreRequestStore, NextCoreDelivery<com.dayforge.data.api.dto.NextSyncPushResponse>> {
        register()
        val (http, _) = channel { successReply(it, edit = edit) }
        val store = sender(http)
        return store to requireNotNull(store.sendOperation(access(), row.operationId))
    }
    private suspend fun assertUnaccepted(row: SyncOutboxEntity) {
        assertEquals(row, db.syncOutboxDao().getById(row.id))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertEquals(0L, tokens.syncCursor.first())
        db.openHelper.writableDatabase.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun ordinaryObservationSuccessAtomicallyAcceptsCanonicalTimeShadowQueueAndReceipt() = runBlocking<Unit> {
        val row = observation(); val oldLog = requireNotNull(db.metricLogDao().getLogByUuid(row.entityUuid))
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
        val row = observation(); val (store, delivery) = success(row)
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
        val row = observation(); val (store, delivery) = success(row)
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
        val (store, delivery) = success(rows.last())
        assertTrue(rejected { store.acceptOperation(delivery) } is IllegalArgumentException)
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

    private suspend fun completion(type: HabitType = HabitType.COUNTING, countdown: Boolean = false): SyncOutboxEntity {
        if (type != habit.habitType || countdown != habit.isCountdown) db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(habitType = type, isCountdown = countdown, targetValue = if (type == HabitType.CHECK_IN) 1 else 10)
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        producer().write(local()) {
            db.completionDao().insert(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(210),
                value = if (type == HabitType.CHECK_IN) 1 else 3, date = millis, actualCompletedAt = millis,
                recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06"))
        }
        return db.syncOutboxDao().getAll().single()
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
        producer().write(local()) { metrics().linkHabits(metric, setOf(habit.id)) }
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
        val row = observation(); val (store, delivery) = success(row)
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
        val row = observation(); val (store, delivery) = success(row)
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET lastError='changed' WHERE id=?", arrayOf<Any>(row.id))
        assertEquals(NextRequestException.Reason.SOURCE_CHANGED, (rejected { store.acceptOperation(delivery) } as NextRequestException).reason)
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        db.syncOutboxDao().deleteById(row.id)
        assertEquals(NextRequestException.Reason.SOURCE_CHANGED, (rejected { store.acceptOperation(delivery) } as NextRequestException).reason)
        assertEquals(0, count("sync_entity_state")); assertEquals(0, count("next_acceptances"))
    }

    @Test fun changedDevicePermissionRevisionAndAuthenticationRejectLateAcceptance() = runBlocking<Unit> {
        val row = observation(); val (store, delivery) = success(row)
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
            "BEFORE INSERT ON next_acceptances BEGIN SELECT RAISE(ABORT,'accept failed'); END",
            "BEFORE INSERT ON next_acceptances BEGIN SELECT RAISE(IGNORE); END",
            "AFTER INSERT ON next_acceptances BEGIN UPDATE next_acceptances SET resultHash='changed'; END",
            "AFTER INSERT ON next_acceptances BEGIN UPDATE sync_outbox SET lastError='changed'; END",
            "BEFORE DELETE ON sync_outbox BEGIN SELECT RAISE(ABORT,'consume failed'); END",
            "BEFORE DELETE ON sync_outbox BEGIN SELECT RAISE(IGNORE); END",
            "AFTER DELETE ON sync_outbox BEGIN UPDATE next_transmissions SET wireHash='changed'; END",
            "AFTER DELETE ON sync_outbox BEGIN DELETE FROM next_acceptances; END",
            "AFTER DELETE ON sync_outbox BEGIN UPDATE next_acceptances SET resultHash='changed'; END",
            "AFTER DELETE ON sync_outbox BEGIN UPDATE sync_entity_state SET payloadHash='changed'; END")
        for (fault in faults) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER acceptance_fault $fault")
            assertNotNull(rejected { store.acceptOperation(delivery) })
            assertUnaccepted(row); assertEquals(before, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("sync_entity_state"))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER acceptance_fault")
        }
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
    }

    @Test fun acceptanceFinalCommitFailureIsNotSuccessAndColdRetryPreservesOriginalRequest() = runBlocking<Unit> {
        val row = observation(); val (store, delivery) = success(row)
        val original = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE acceptance_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE acceptance_child(id INTEGER REFERENCES acceptance_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER acceptance_commit_fault AFTER INSERT ON next_acceptances BEGIN INSERT INTO acceptance_child VALUES(1); END")
        assertTrue(rejected { store.acceptOperation(delivery) } is SQLiteConstraintException)
        storage.reopen()
        assertUnaccepted(row); assertEquals(0, count("acceptance_child")); assertEquals(0, count("sync_entity_state"))
        assertArrayEquals(original, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER acceptance_commit_fault")
        val (http, _) = channel { successReply(it) }
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        storage.reopen()
        assertEquals(1, count("next_acceptances")); assertNull(db.syncOutboxDao().getById(row.id))
    }

    @Test fun corruptReceiptCannotAuthorizeReplayOrReinsertConsumedWork() = runBlocking<Unit> {
        val row = observation(); val (store, delivery) = success(row)
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

    @Test fun unsupportedDeleteAndRejectedResultKeepPendingWorkAndNoCursor() = runBlocking<Unit> {
        producer().write(local()) { metrics().deleteMetric(metric) }
        val row = db.syncOutboxDao().getAll().single(); register(); val (http, _) = channel()
        val store = sender(http); val delivery = requireNotNull(store.sendOperation(access(), row.operationId))
        assertEquals(NextRequestException.Reason.UNSUPPORTED_ACCEPTANCE, (rejected { store.acceptOperation(delivery) } as NextRequestException).reason)
        assertUnaccepted(row)
    }

    @Test fun acceptanceLeavesUnrelatedOfflineTimerCommandsAndOriginalOnceProofUntouched() = runBlocking<Unit> {
        val timer = start()
        val timerOrigin = db.nextRequestDao().origin(NEXT_TIMER, timer.commandId)
        val once = OneTimeTransmissionEntity(id(230), id(1), id(2), id(3), id(4), "original-once-bytes")
        db.completionFollowUpDao().insertTransmission(once)
        val row = observation(); val (store, delivery) = success(row)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        storage.reopen()
        assertEquals(timer, db.timeLogDao().getTimerCommand(timer.id))
        assertEquals(timerOrigin, db.nextRequestDao().origin(NEXT_TIMER, timer.commandId))
        assertEquals(once, db.completionFollowUpDao().transmission(once.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_TIMER, timer.commandId))
    }

    @Test fun cancelledQueuedAcceptanceNeverCommitsBusinessShadowOrReceipt() = runBlocking<Unit> {
        val row = observation(); val (store, delivery) = success(row)
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
