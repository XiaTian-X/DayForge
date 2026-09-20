package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.SyncV2Api
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Real production Room and Retrofit; only the HTTP response boundary is scripted. */
@RunWith(AndroidJUnit4::class)
class TimerSyncDurabilityTest {
    @get:Rule val storage = PhysicalDatabaseRule()
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = CopyOnWriteArrayList<JsonObject>()
    private lateinit var client: OkHttpClient
    private lateinit var api: SyncV2Api
    private var respond: (JsonObject) -> String = { response("applied") }
    private val dao get() = storage.database.timeLogDao()
    private val repository get() = TimerSyncRepository(api, dao)
    private var logId = 0L

    @Before fun setup() = runBlocking {
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method != "POST" || request.url.encodedPath != "/api/v2/timers/commands") {
                throw IOException("Unexpected timer request: ${request.method} ${request.url}")
            }
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            requests += body
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("scripted timer boundary")
                .body(respond(body).toResponseBody("application/json".toMediaType())).build()
        }.build()
        api = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(SyncV2Api::class.java)
        val habitId = storage.database.habitDao().insert(HabitEntity(
            uuid = ACTIVITY, name = "Durable timer", habitType = HabitType.TIMER,
            iconResId = 0, colorHex = "#2196F3", schedule = HabitSchedule.Daily, targetValue = 1
        ))
        logId = dao.insertSyncedTimer(
            TimeLogEntity(habitId = habitId, startTime = 10_000, endTime = null,
                durationSeconds = 0, date = 0, uuid = SESSION, timerNextCommandSequence = 2,
                timerControlGeneration = 1, timerLastCommandAt = 10_000,
                timerTimezone = "UTC", timerElapsedRealtimeAnchor = 10_000, timerBootCount = 1),
            TimerCommandEntity(commandId = START, sessionUuid = SESSION, sequence = 1,
                commandType = "start", occurredAt = 10_000, expectedControlGeneration = 0,
                activityUuid = ACTIVITY, timezone = "UTC"),
            TimerSegmentEntity(sessionUuid = SESSION, sequence = 1, startedAt = 10_000)
        )
        // Model a previously acknowledged start. Completion still uses the real atomic transition.
        dao.deleteTimerCommand(dao.getPendingTimerCommands().single().id)
        dao.finishTimerAndQueue(logId, 70_000, 60, 0, 3, 60_000,
            TimerCommandEntity(commandId = STOP, sessionUuid = SESSION, sequence = 2,
                commandType = "stop", occurredAt = 70_000, expectedControlGeneration = 1,
                expectedRevision = 1, activeElapsedMillis = 60_000), false)
    }

    @After fun teardown() {
        if (::client.isInitialized) {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun lostResponseReplaysExactWireCommandAfterDatabaseReopen() = runBlocking {
        val before = dao.getPendingTimerCommands().single()
        val completed = dao.getById(logId)
        respond = { throw IOException("server committed but response was lost") }
        assertTrue(runCatching { repository.pushPending(DEVICE) }.exceptionOrNull() is IOException)
        storage.reopen()
        assertEquals(listOf(before), dao.getPendingTimerCommands())
        assertEquals(completed, dao.getById(logId))
        respond = { response("already_applied") }
        repository.pushPending(DEVICE)
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
        // Independent wire oracle, not serialization of the production request DTO.
        assertEquals(json.parseToJsonElement("""{"device_id":"$DEVICE","commands":[{"command_id":"$STOP","session_id":"$SESSION","sequence":2,"command_type":"stop","occurred_at":"1970-01-01T00:01:10Z","expected_control_generation":1,"expected_revision":1,"active_elapsed_ms":60000}]}"""), requests[0])
        storage.reopen()
        assertTrue(dao.getPendingTimerCommands().isEmpty())
        assertEquals(completed, dao.getById(logId))
        assertEquals(listOf(70_000L), dao.getTimerSegments(SESSION).map { it.endedAt })
    }

    @Test fun transientConflictPreservesIdentityAndPersistsDiagnosticForRetry() = runBlocking {
        val before = dao.getPendingTimerCommands().single()
        respond = { response("conflict", error = "MISSING_PREDECESSOR") }
        repository.pushPending(DEVICE)
        storage.reopen()
        assertEquals(listOf(before.copy(attemptCount = 1, errorCode = "MISSING_PREDECESSOR", lastError = "diagnostic")), dao.getPendingTimerCommands())
        assertTrue(dao.getRejectedTimerCommands().isEmpty())
        respond = { response("applied") }
        repository.pushPending(DEVICE)
        assertEquals(requests[0], requests[1])
        storage.reopen()
        assertTrue(dao.getPendingTimerCommands().isEmpty())
        assertEquals(60, dao.getById(logId)?.durationSeconds)
    }

    @Test fun permanentRejectionSurvivesReopenAndManualRetryUsesNewIdentity() = runBlocking {
        val before = dao.getPendingTimerCommands().single()
        respond = { response("rejected", error = "ACTIVITY_NOT_FOUND") }
        assertTrue(runCatching { repository.pushPending(DEVICE) }.exceptionOrNull() is TimerSyncRequiresAttentionException)
        storage.reopen()
        val rejected = dao.getRejectedTimerCommands().single()
        assertNotNull(rejected.deadLetteredAt)
        assertEquals(before.copy(attemptCount = 1, errorCode = "ACTIVITY_NOT_FOUND", lastError = "diagnostic", deadLetteredAt = rejected.deadLetteredAt), rejected)
        assertTrue(dao.getPendingTimerCommands().isEmpty())
        repository.pushPending(DEVICE)
        assertEquals(1, requests.size)
        repository.retryRejectedCommand(rejected.id)
        storage.reopen()
        val retried = dao.getPendingTimerCommands().single()
        assertNotEquals(STOP, retried.commandId)
        assertEquals(retried.commandId, UUID.fromString(retried.commandId).toString())
        assertEquals(before.copy(commandId = retried.commandId), retried)
        assertTrue(dao.getRejectedTimerCommands().isEmpty())
        respond = { response("applied", command = retried.commandId) }
        repository.pushPending(DEVICE)
        storage.reopen()
        assertTrue(dao.getPendingTimerCommands().isEmpty())
        assertEquals(60, dao.getById(logId)?.durationSeconds)
    }

    @Test fun acknowledgementForDifferentSessionCannotDeletePendingCommand() = runBlocking {
        val before = dao.getPendingTimerCommands()
        respond = { response("applied", session = "90000000-0000-4000-8000-000000000009") }
        val error = runCatching { repository.pushPending(DEVICE) }.exceptionOrNull()
        storage.reopen()
        assertEquals("Mismatched session must retain the durable command", before, dao.getPendingTimerCommands())
        assertTrue(error is IllegalStateException)
        respond = { response("applied") }
        repository.pushPending(DEVICE)
        storage.reopen()
        assertTrue(dao.getPendingTimerCommands().isEmpty())
    }

    @Test fun missingAndUnknownAcknowledgementsRetainPendingCommand() = runBlocking {
        val before = dao.getPendingTimerCommands()
        for (payload in listOf("""{"results":[],"server_time":"2026-09-20T00:00:00Z"}""",
            response("applied", command = START))) {
            respond = { payload }
            assertTrue(runCatching { repository.pushPending(DEVICE) }.exceptionOrNull() is IllegalStateException)
            storage.reopen()
            assertEquals(before, dao.getPendingTimerCommands())
        }
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
    }

    private fun response(status: String, session: String = SESSION, command: String = STOP, error: String? = null): String =
        """{"results":[{"command_id":"$command","session_id":"$session","status":"$status","error_code":${error?.let { "\"$it\"" } ?: "null"},"message":"diagnostic"}],"server_time":"2026-09-20T00:00:00Z"}"""

    private companion object {
        const val DEVICE = "40000000-0000-4000-8000-000000000004"
        const val SESSION = "10000000-0000-4000-8000-000000000001"
        const val ACTIVITY = "20000000-0000-4000-8000-000000000002"
        const val START = "30000000-0000-4000-8000-000000000001"
        const val STOP = "30000000-0000-4000-8000-000000000002"
    }
}
