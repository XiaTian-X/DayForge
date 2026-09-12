package com.dayforge.data.api

import com.dayforge.data.api.dto.SyncV2PushRequest
import com.dayforge.data.api.dto.SyncV2PushResponse
import com.dayforge.data.api.dto.TimerCommandBatchRequest
import com.dayforge.data.api.dto.TimerCommandBatchResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class RetrofitContractTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `sync fixture survives actual Retrofit request and response conversion`() = runBlocking {
        val response = fixture("server/push-applied-response.json")
        val api = api("/api/v2/sync/push", "client/push-all-entities.json", response)
        val result = api.push(json.decodeFromString<SyncV2PushRequest>(fixture("client/push-all-entities.json")))
        assertEquals(json.decodeFromString<SyncV2PushResponse>(response), result)
    }

    @Test
    fun `timer command ordering and preconditions survive Retrofit conversion`() = runBlocking {
        val response = fixture("server/timer-commands-response.json")
        val api = api("/api/v2/timers/commands", "client/timer-commands.json", response)
        val result = api.pushTimerCommands(json.decodeFromString<TimerCommandBatchRequest>(fixture("client/timer-commands.json")))
        assertEquals(json.decodeFromString<TimerCommandBatchResponse>(response), result)
    }

    @Test
    fun `unauthorized response remains an HTTP failure for authentication recovery`() = runBlocking {
        val api = api("/api/v2/sync/push", "client/push-all-entities.json", "{\"detail\":\"Unauthorized\"}", 401)
        val error = runCatching {
            api.push(json.decodeFromString<SyncV2PushRequest>(fixture("client/push-all-entities.json")))
        }.exceptionOrNull()
        assertTrue(error is HttpException)
        assertEquals(401, (error as HttpException).code())
    }

    private fun api(path: String, requestFixture: String, response: String, status: Int = 200): SyncV2Api {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(path, request.url.encodedPath)
            assertEquals("POST", request.method)
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            // Compare to the DTO's canonical representation, including explicit defaults.
            val canonical = if (requestFixture.contains("timer")) {
                json.encodeToString(json.decodeFromString<TimerCommandBatchRequest>(fixture(requestFixture)))
            } else {
                json.encodeToString(json.decodeFromString<SyncV2PushRequest>(fixture(requestFixture)))
            }
            assertEquals(json.parseToJsonElement(canonical), json.parseToJsonElement(buffer.readUtf8()))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(SyncV2Api::class.java)
    }

    private fun fixture(path: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("sync-v2/$path")).readText()
}
