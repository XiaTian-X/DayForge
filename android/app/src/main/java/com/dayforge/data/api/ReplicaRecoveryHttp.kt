package com.dayforge.data.api

import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.api.dto.ServerIdentityResponse
import com.dayforge.data.api.dto.TokenResponse
import com.dayforge.domain.model.isContractUuid
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Public identity and explicit login only. No bearer, cookies, refresh, redirects or cache.
 * Every request joins actual socket shutdown on cancellation; response/error bytes are bounded.
 */
internal class ReplicaRecoveryHttp(shared: OkHttpClient, private val route: MaterialHttpRoute) : AutoCloseable {
    private val builder = shared.newBuilder().also {
        it.interceptors().clear(); it.networkInterceptors().clear()
    }
    private val client = route.bind(builder).authenticator(Authenticator.NONE)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null)
        .connectionPool(ConnectionPool(1, 1, TimeUnit.MINUTES))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .build()
    @Volatile private var closed = false

    suspend fun identity(): ServerIdentityResponse = value(
        Request.Builder().url(route.origin.newBuilder().encodedPath(MaterialHttpRoute.IDENTITY_PATH).build()).get().build(),
        ServerIdentityResponse.serializer()).also {
        require(isContractUuid(it.serverInstanceId) && isContractUuid(it.syncEpoch) && it.protocolVersion > 0 &&
            it.capabilities.size <= 256 && it.capabilities.distinct().size == it.capabilities.size &&
            it.capabilities.all { capability -> capability.isNotBlank() && capability.length <= 256 }) { "SYNC_RECOVERY_IDENTITY_INVALID" }
        Instant.parse(it.serverTime)
    }

    suspend fun login(username: String, password: String): TokenResponse {
        require(username.isNotBlank() && password.isNotEmpty()) { "SYNC_RECOVERY_LOGIN_INPUT_INVALID" }
        val bytes = Json.encodeToString(LoginRequest(username, password)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16_384) { "SYNC_RECOVERY_LOGIN_INPUT_INVALID" }
        return value(Request.Builder().url(route.authBaseUrl().newBuilder().addPathSegments("auth/login").build())
            .post(bytes.toRequestBody("application/json".toMediaType())).build(), TokenResponse.serializer()).also {
            require(materialTokenSafe(it.accessToken) && materialTokenSafe(it.refreshToken) &&
                it.tokenType == "bearer" && isContractUuid(it.userId) && it.username.isNotBlank()) { "SYNC_RECOVERY_LOGIN_RESPONSE_INVALID" }
        }
    }

    private suspend fun <T> value(request: Request, serializer: KSerializer<T>): T = supervisorScope {
        check(!closed && route.matches(request.url)) { "SYNC_RECOVERY_CONNECTION_CLOSED" }
        currentCoroutineContext().ensureActive()
        val call = client.newCall(request.newBuilder().header("Accept-Encoding", "identity").build())
        val worker = async(Dispatchers.IO) { withContext(NonCancellable) {
            call.execute().use { response ->
                if (response.code != 200) throw NextSyncHttpFailure(response.code, null)
                if (response.header("Content-Encoding")?.let { it != "identity" } == true) throw NextSyncReplyInvalid()
                requireSyncJsonType(response)
                val body = response.body ?: throw NextSyncReplyInvalid()
                if (body.contentLength() > REPLY_LIMIT) throw NextSyncReplyInvalid()
                val output = ByteArrayOutputStream(8192)
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (call.isCanceled()) throw IOException("SYNC_RECOVERY_CALL_CANCELLED")
                        val count = input.read(buffer, 0, minOf(buffer.size, REPLY_LIMIT - output.size() + 1))
                        if (count == -1) break
                        if (count == 0 || output.size() + count > REPLY_LIMIT) throw NextSyncReplyInvalid()
                        output.write(buffer, 0, count)
                    }
                }
                output.toByteArray()
            }
        } }
        val bytes = try { worker.await() } finally {
            call.cancel()
            withContext(NonCancellable) { worker.join() }
        }
        check(!closed) { "SYNC_RECOVERY_CONNECTION_CLOSED" }
        val caller = currentCoroutineContext()
        decodeSyncReply(bytes, REPLY_LIMIT, serializer, { caller.ensureActive() })
    }

    override fun close() { closed = true; client.connectionPool.evictAll() }
    private companion object { const val REPLY_LIMIT = 65_536 }
}
