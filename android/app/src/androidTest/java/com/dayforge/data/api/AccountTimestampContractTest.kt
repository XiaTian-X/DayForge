package com.dayforge.data.api

import com.dayforge.data.api.dto.AdminUserCreate
import com.dayforge.data.api.dto.ApiTokenCreateRequest
import com.dayforge.data.api.dto.UserStatusUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Response metadata remains lossless with canonical UTC and older server formats. */
@RunWith(Parameterized::class)
class AccountTimestampContractTest(private val timestamp: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val clients = CopyOnWriteArrayList<OkHttpClient>()

    @After fun closeClients() {
        clients.forEach { client ->
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun user_token_creation_and_listing_preserve_timestamps_and_nullable_fields() = runBlocking {
        val api = retrofit().create(TokenApi::class.java)
        val created = api.createToken(ApiTokenCreateRequest("fixture", 7))
        assertEquals(timestamp, created.created_at)
        assertEquals(timestamp, created.expires_at)
        assertNull(created.last_used_at)
        assertEquals("test-only-placeholder", created.token)
        val listed = api.listTokens()
        assertEquals(1, listed.size)
        assertEquals(timestamp, listed.single().created_at)
        assertEquals(timestamp, listed.single().last_used_at)
        assertNull(listed.single().expires_at)
    }

    @Test
    fun admin_token_routes_accept_the_same_UTC_response_contract() = runBlocking {
        val api = retrofit().create(AdminApi::class.java)
        val created = api.createUserToken(7, ApiTokenCreateRequest("fixture", 7))
        assertEquals(timestamp, created.created_at)
        assertEquals(timestamp, created.expires_at)
        assertNull(created.last_used_at)
        val listed = api.listUserTokens(7).single()
        assertEquals(timestamp, listed.created_at)
        assertEquals(timestamp, listed.last_used_at)
        assertNull(listed.expires_at)
    }

    @Test
    fun admin_account_create_list_and_update_preserve_timestamp_precision() = runBlocking {
        val api = retrofit().create(AdminApi::class.java)
        val created = api.createUser(AdminUserCreate("fixture", "test-password"))
        val listed = api.listUsers().single()
        val updated = api.updateUserStatus(7, UserStatusUpdate(false))
        for (user in listOf(created, listed, updated)) {
            assertEquals(timestamp, user.createdAt)
            assertEquals(timestamp, user.updatedAt)
            assertEquals("00000000-0000-4000-8000-000000000007", user.publicId)
        }
    }

    private fun retrofit(): Retrofit {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val response = when {
                path == "/api/v1/auth/tokens" || path == "/api/v1/admin/users/7/tokens" -> {
                    when (request.method) {
                        "POST" -> """{"id":1,"name":"fixture","prefix":"public-part","token":"test-only-placeholder","created_at":"$timestamp","last_used_at":null,"expires_at":"$timestamp"}"""
                        "GET" -> """[{"id":1,"name":"fixture","prefix":"public-part","created_at":"$timestamp","last_used_at":"$timestamp","expires_at":null}]"""
                        else -> error("Unexpected token method: ${request.method}")
                    }
                }
                path == "/api/v1/admin/users" || path == "/api/v1/admin/users/7/status" -> {
                    val user = """{"id":7,"public_id":"00000000-0000-4000-8000-000000000007","username":"fixture","is_active":false,"is_verified":false,"is_admin":false,"status":"disabled","created_at":"$timestamp","updated_at":"$timestamp"}"""
                    when (request.method) {
                        "GET" -> "[$user]"
                        "POST", "PUT" -> user
                        else -> error("Unexpected account method: ${request.method}")
                    }
                }
                else -> error("Unexpected path: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.method == "POST") 201 else 200).message("fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        clients += client
        return Retrofit.Builder().baseUrl("https://example.invalid/api/v1/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "timestamp={0}")
        fun timestamps(): Collection<Array<String>> = listOf(
            arrayOf("2026-09-13T23:59:59Z"),
            arrayOf("2026-09-13T23:59:59.123456Z"),
            arrayOf("2026-09-13T23:59:59.123456"),
            arrayOf("2026-09-13T23:59:59.123456+08:00")
        )
    }
}
