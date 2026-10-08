package com.dayforge.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.NextSyncPushResponse
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
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule

/** Shared per-case physical database, authenticated preferences and real loopback HTTP.
 * Abstract and contains no tests; concrete suites inherit the same rules/setup/cleanup.
 */
abstract class NextCoreRequestFixture {
    @get:Rule val storage = PhysicalDatabaseRule()
    internal val db get() = storage.database
    internal val app = InstrumentationRegistry.getInstrumentation().targetContext
    internal val sessions = AccountSessionCoordinator()
    internal lateinit var directory: File
    internal lateinit var scope: CoroutineScope
    internal lateinit var tokens: TokenManager
    internal lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    internal lateinit var preferences: PreferencesManager
    internal lateinit var client: OkHttpClient
    internal lateinit var habit: HabitEntity
    internal lateinit var timerHabit: HabitEntity
    internal lateinit var metric: MetricEntity
    internal val servers = mutableListOf<MaterialSocketServer>()
    internal val caps = setOf("sync.read", "structure.write", "facts.append", "timer.control")
    internal val time = "2026-10-06T00:00:00Z"
    internal val millis get() = Instant.parse(time).toEpochMilli()

    internal fun id(n: Int) = "aa310000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"

    internal fun producer() = NextCoreLocalIntentStore(db, tokens, sessions)

    internal suspend fun local() = requireNotNull(tokens.localCoreWriteAccess()).session

    internal suspend fun access() = requireNotNull(tokens.localSyncAccess())

    internal suspend fun register(device: String = id(4), permissions: Set<String> = caps, revision: Int = 1) {
        tokens.saveServerIdentity(id(2), id(3))
        tokens.saveDeviceRegistration(device, permissions, true, revision)
    }

    internal fun habits() = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db)

    internal fun metrics() = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao())

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        directory = Files.createTempDirectory(app.filesDir.toPath(), "next-journal-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") })
        dataStore = data
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

    internal fun reply(input: MaterialSocketServer.Input, version: Int = 5): MaterialSocketServer.Reply {
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

    internal suspend fun channel(respond: (MaterialSocketServer.Input) -> MaterialSocketServer.Reply? = { reply(it) }): Pair<NextSyncHttp, MaterialSocketServer> {
        val server = MaterialSocketServer { input, _ -> respond(input) }; servers.add(server)
        preferences.setServerUrl(server.origin.toString())
        return NextSyncHttp(client, tokens, server.origin) to server
    }

    internal fun sender(http: NextSyncHttp) = NextCoreRequestStore(db, tokens, sessions, http)

    internal suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { if (error is CancellationException) throw error; return error }
        throw AssertionError("Must reject")
    }

    internal suspend fun observation(): SyncOutboxEntity {
        producer().write(local()) { metrics().recordValue(metric.id, 21.125, "captured\nvalue", millis) }
        return db.syncOutboxDao().getAll().last()
    }

    internal suspend fun start(session: Int = 20, command: Int = 21): TimerCommandEntity {
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

    internal suspend fun transmission(kind: String, request: String) = requireNotNull(db.nextRequestDao().transmission(kind, request))

    internal fun count(table: String): Int = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    internal suspend fun editMetric(name: String, description: String = ""): SyncOutboxEntity {
        producer().write(local()) { metrics().updateMetric(db.metricDao().getMetricById(metric.id)!!.copy(name = name, description = description)) }
        return db.syncOutboxDao().getAll().last()
    }

    internal suspend fun originalIntent(row: SyncOutboxEntity) = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, row.operationId))

    internal fun wireOperation(input: MaterialSocketServer.Input) = Json.parseToJsonElement(input.body.toString(Charsets.UTF_8))
        .jsonObject.getValue("operations").jsonArray.single().jsonObject

    internal fun wireOrIdentity(input: MaterialSocketServer.Input): String? = if (input.path.endsWith("/identity")) null
        else wireOperation(input).getValue("operation_id").jsonPrimitive.content

    /** Mirrors authoritative serializer fields, without calling the production acceptance mappers. */
    internal fun successReply(input: MaterialSocketServer.Input, revision: Long = 1,
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

    internal suspend fun success(row: SyncOutboxEntity, edit: (JsonObject) -> JsonObject = { it }):
        Pair<NextCoreRequestStore, NextCoreDelivery<com.dayforge.data.api.dto.NextSyncPushResponse>> {
        register()
        val (http, _) = channel { successReply(it, edit = edit) }
        val store = sender(http)
        return store to requireNotNull(store.sendOperation(access(), row.operationId))
    }

    internal suspend fun assertUnaccepted(row: SyncOutboxEntity) {
        assertEquals(row, db.syncOutboxDao().getById(row.id))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertEquals(0L, tokens.syncCursor.first())
        db.openHelper.writableDatabase.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    internal suspend fun completion(type: HabitType = HabitType.COUNTING, countdown: Boolean = false): SyncOutboxEntity {
        if (type != habit.habitType || countdown != habit.isCountdown) db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(habitType = type, isCountdown = countdown, targetValue = if (type == HabitType.CHECK_IN) 1 else 10)
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        producer().write(local()) {
            val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(210),
                value = if (type == HabitType.CHECK_IN) 1 else 3, date = millis, actualCompletedAt = millis,
                recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06")
            if (type == HabitType.COUNTING) NextCountDayStore(db).capture(habit, fact)
            db.completionDao().insert(fact)
        }
        return db.syncOutboxDao().getAll().single()
    }
}
