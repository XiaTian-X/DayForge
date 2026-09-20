package com.dayforge.data.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import com.dayforge.data.api.dto.SyncV2PushRequest
import com.dayforge.data.api.dto.SyncV2PushResponse
import com.dayforge.data.api.dto.TimerCommandBatchRequest
import com.dayforge.data.api.dto.TimerCommandBatchResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@RunWith(AndroidJUnit4::class)
class RetrofitContractTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val clients = CopyOnWriteArrayList<OkHttpClient>()

    @After fun closeClients() {
        clients.forEach { client ->
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
    private data class CapturedRequest(val path: String, val method: String, val body: String)
    private val requests = CopyOnWriteArrayList<CapturedRequest>()

    @Test
    fun sync_fixture_survives_actual_Retrofit_request_and_response_conversion() = runBlocking {
        val response = fixture("server/push-applied-response.json")
        val api = api(response)
        val result = api.push(json.decodeFromString<SyncV2PushRequest>(fixture("client/push-all-entities.json")))
        assertEquals(json.decodeFromString<SyncV2PushResponse>(response), result)
        assertCapturedRequest("/api/v2/sync/push", "client/push-all-entities.json")
        val applied = result.results.single()
        assertEquals("70000000-0000-4000-8000-000000000001", applied.operationId)
        assertEquals("10000000-0000-4000-8000-000000000001", applied.entityUuid)
        assertEquals("plan_node", applied.entityType)
        assertEquals("applied", applied.status)
        assertEquals(1L, applied.revision)
        assertEquals(json.parseToJsonElement(response).jsonObject["results"]!!.jsonArray.single().jsonObject["entity"], applied.entity)
    }

    @Test
    fun timer_command_ordering_and_preconditions_survive_Retrofit_conversion() = runBlocking {
        val response = fixture("server/timer-commands-response.json")
        val api = api(response)
        val result = api.pushTimerCommands(json.decodeFromString<TimerCommandBatchRequest>(fixture("client/timer-commands.json")))
        assertEquals(json.decodeFromString<TimerCommandBatchResponse>(response), result)
        assertCapturedRequest("/api/v2/timers/commands", "client/timer-commands.json")
        val applied = result.results.single()
        assertEquals("81000000-0000-4000-8000-000000000004", applied.commandId)
        assertEquals("80000000-0000-4000-8000-000000000001", applied.sessionId)
        assertEquals("applied", applied.status)
        val session = requireNotNull(applied.session)
        assertEquals("completed", session.state)
        assertEquals(1, session.controlGeneration)
        assertEquals(4, session.revision)
        assertEquals(5, session.nextCommandSequence)
        assertEquals(60_000L, session.activeElapsedMs)
        assertEquals("2026-08-03T16:01:30Z", session.endedAt)
        assertEquals("Asia/Shanghai", session.timezone)
        assertEquals("2026-08-03T16:01:31Z", result.serverTime)
    }

    @Test
    fun unauthorized_response_remains_an_HTTP_failure_for_authentication_recovery() = runBlocking {
        val api = api("{\"detail\":\"Unauthorized\"}", 401)
        val error = runCatching {
            api.push(json.decodeFromString<SyncV2PushRequest>(fixture("client/push-all-entities.json")))
        }.exceptionOrNull()
        assertTrue(error is HttpException)
        assertEquals(401, (error as HttpException).code())
        assertCapturedRequest("/api/v2/sync/push", "client/push-all-entities.json")
    }

    private fun api(response: String, status: Int = 200): SyncV2Api {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            requests += CapturedRequest(request.url.encodedPath, request.method, buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        clients += client
        return Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(SyncV2Api::class.java)
    }

    private fun assertCapturedRequest(path: String, fixturePath: String) {
        val request = requests.single()
        assertEquals(path, request.path)
        assertEquals("POST", request.method)
        val raw = json.parseToJsonElement(fixture(fixturePath)).jsonObject
        val collection = if (fixturePath.contains("timer")) "commands" else "operations"
        // Defaults are explicit protocol expectations, never generated by the DTO under test.
        val nullableFields = if (collection == "commands")
            listOf("expected_revision", "activity_uuid", "timezone", "active_elapsed_ms") else listOf("base_revision")
        val expected = JsonObject(raw + (collection to JsonArray(raw.getValue(collection).jsonArray.map { element ->
            JsonObject(nullableFields.associateWith { JsonNull } + element.jsonObject)
        })))
        assertEquals(expected, json.parseToJsonElement(request.body))
    }

    private fun fixture(path: String): String = InstrumentationRegistry.getInstrumentation().context.assets
        .open("sync-v2/$path").bufferedReader().use { it.readText() }
}
