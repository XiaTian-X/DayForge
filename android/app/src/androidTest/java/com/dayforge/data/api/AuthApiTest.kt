package com.dayforge.data.api

import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.api.dto.RefreshRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.After
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Wire expectations are independent of Retrofit annotations and DTO serializers. */
@RunWith(AndroidJUnit4::class)
class AuthApiTest {
    private val requestAssertions = mutableListOf<() -> Unit>()

    @After fun verifyRecordedRequests() {
        assertTrue("Every test must exercise an HTTP request", requestAssertions.isNotEmpty())
        requestAssertions.forEach { it() }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val account = "00000000-0000-4000-8000-000000000007"
    private val tokenResponse = """{"access_token":"access","refresh_token":"refresh","token_type":"bearer","user_id":"$account","username":"member","is_admin":true}"""

    @Test fun login_sends_username_and_password_to_the_documented_route() = runBlocking {
        val api = api("POST", "/api/v1/auth/login", """{"username":"member","password":"password123"}""", tokenResponse)
        val result = api.login(LoginRequest("member", "password123"))
        assertEquals("access", result.accessToken)
        assertEquals("refresh", result.refreshToken)
        assertEquals(account, result.userId)
        assertEquals("member", result.username)
        assertTrue(result.isAdmin)
    }

    @Test fun refresh_sends_its_token_and_preserves_the_public_account_contract() = runBlocking {
        val api = api("POST", "/api/v1/auth/refresh", """{"refresh_token":"old-refresh"}""", tokenResponse)
        val result = api.refreshToken(RefreshRequest("old-refresh"))
        assertEquals(account, result.userId)
        assertEquals("access", result.accessToken)
        assertEquals("refresh", result.refreshToken)
        assertTrue(result.isAdmin)
    }

    @Test fun health_uses_the_server_root_instead_of_the_versioned_API_base() = runBlocking {
        assertEquals("ok", api("GET", "/health", null, """{"status":"ok"}""").health().status)
    }

    @Test fun authentication_errors_remain_HTTP_failures() = runBlocking {
        val api = api("POST", "/api/v1/auth/login", """{"username":"member","password":"wrong"}""", """{"detail":"Unauthorized"}""", 401)
        val error = runCatching { api.login(LoginRequest("member", "wrong")) }.exceptionOrNull()
        assertTrue(error is HttpException)
        assertEquals(401, (error as HttpException).code())
    }

    private fun api(method: String, path: String, body: String?, response: String, status: Int = 200): AuthApi {
        val recorded = AtomicReference<Request>()
        // Keep assertions on the JUnit thread: AssertionError on OkHttp's dispatcher
        // would crash instrumentation and prevent the remaining tests from running.
        requestAssertions += {
            val request = requireNotNull(recorded.get()) { "Retrofit did not send a request" }
            assertEquals(method, request.method)
            assertEquals(path, request.url.encodedPath)
            if (body == null) assertNull(request.body) else {
                val buffer = Buffer()
                requireNotNull(request.body).writeTo(buffer)
                assertEquals(json.parseToJsonElement(body), json.parseToJsonElement(buffer.readUtf8()))
                assertEquals("application/json", request.body!!.contentType()?.let { "${it.type}/${it.subtype}" })
            }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            recorded.set(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://example.invalid/api/v1/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(AuthApi::class.java)
    }
}
