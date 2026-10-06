package com.dayforge.data.api.authenticator

import com.dayforge.BuildConfig
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.dto.RefreshRequest
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.LocalIconAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.api.MaterialHttpRoute
import com.dayforge.data.api.MaterialCallCancellation
import com.dayforge.data.api.MaterialRefreshApi
import com.dayforge.data.api.MaterialRefreshBoundary
import com.dayforge.data.api.materialTokenSafe
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject

/**
 * OkHttp authenticator that handles 401 responses by refreshing tokens.
 * Automatically retries the original request with new tokens.
 *
 * Refresh is serialized because OkHttp's Authenticator API is synchronous; waiting
 * requests reuse the token produced by the first refresh instead of failing early.
 */
@OptIn(ExperimentalSerializationApi::class)
class TokenAuthenticator @Inject constructor(
    private val tokenManager: TokenManager,
    private val preferencesManager: PreferencesManager,
    private val selectedTransport: SelectedNetworkTransport
) : Authenticator {
    private val materialRefresh = ReentrantLock()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Don't retry more than once
        if (response.responseCount >= 2) {
            return null
        }

        // Skip refresh for auth endpoints
        val url = response.request.url.toString()
        val materialRoute = response.request.tag(MaterialHttpRoute::class.java)
        if (materialRoute != null && (!materialRoute.matches(response.request.url) ||
                response.request.url.encodedPath == MaterialHttpRoute.IDENTITY_PATH)) return null
        if (url.contains("auth/login") || url.contains("auth/refresh")) {
            return null
        }

        if (materialRoute == null) return synchronized(this) { refresh(response, null) }
        // A material request must not wait indefinitely behind an unrelated legacy refresh.
        if (!materialRefresh.tryLock(5, TimeUnit.SECONDS)) return null
        return try { refresh(response, materialRoute) } finally { materialRefresh.unlock() }
    }

    private fun refresh(response: Response, materialRoute: MaterialHttpRoute?): Request? {
        // A concurrent request may have refreshed while this request waited.
        val originalSession = response.request.tag(AuthenticationSession::class.java) ?: return null
        val iconAccess = response.request.tag(LocalIconAccess::class.java)
        val syncAccess = response.request.tag(LocalSyncAccess::class.java)
        val credentials = runBlocking {
            if (syncAccess != null) tokenManager.syncAuthenticationSnapshot(syncAccess)
            else if (iconAccess == null) tokenManager.authenticationSnapshot()
            else tokenManager.iconAuthenticationSnapshot(iconAccess)
        } ?: return null
        if (originalSession != credentials.session) return null
        val currentAccessToken = credentials.accessToken
        // Concurrent refresh/reuse is a separate attachment boundary, not the original interceptor.
        if (materialRoute != null && !materialTokenSafe(currentAccessToken)) return null
        val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        if (requestToken != currentAccessToken) {
            return response.request.newBuilder()
                .header("Authorization", "Bearer $currentAccessToken")
                .build()
        }

        try {
            val refreshToken = credentials.refreshToken

            if (refreshToken == null) return null

            // Create a temporary AuthApi client without an authenticator to avoid a loop.
            val json = Json {
                ignoreUnknownKeys = materialRoute == null
                isLenient = materialRoute == null
            }
            val builder = OkHttpClient.Builder()
            if (materialRoute == null) {
                builder.socketFactory(selectedTransport.socketFactory).dns(selectedTransport.dns)
                    .addInterceptor(BaseUrlInterceptor(preferencesManager))
            } else {
                materialRoute.bind(builder).followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS)
                val cancellation = response.request.tag(MaterialCallCancellation::class.java)
                builder.eventListener(object : okhttp3.EventListener() {
                    override fun callStart(call: okhttp3.Call) { cancellation?.register(call) }
                    override fun callEnd(call: okhttp3.Call) { cancellation?.unregister(call) }
                    override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { cancellation?.unregister(call) }
                })
                builder.addInterceptor(MaterialRefreshBoundary(strictSync = syncAccess != null))
            }
            val client = builder
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG && materialRoute == null) {
                        HttpLoggingInterceptor.Level.BASIC
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
                    redactHeader("Authorization")
                    redactHeader("Cookie")
                })
                .build()

            val refreshResponse = try {
                val retrofit = Retrofit.Builder()
                    .baseUrl(materialRoute?.authBaseUrl() ?: "http://localhost:8000/api/v1/".toHttpUrl())
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .client(client)
                    .build()

                // AuthApi's relative path must preserve /api/v1/ on the captured origin.
                runBlocking {
                    if (materialRoute == null) retrofit.create(AuthApi::class.java).refreshToken(RefreshRequest(refreshToken))
                    else retrofit.create(MaterialRefreshApi::class.java).refresh(RefreshRequest(refreshToken)).value()
                }
            } finally {
                if (materialRoute != null) {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }

            val saved = runBlocking {
                if (syncAccess != null) tokenManager.saveRefreshedSyncTokens(
                    credentials, syncAccess, refreshResponse.accessToken, refreshResponse.refreshToken,
                    refreshResponse.username, refreshResponse.userId, refreshResponse.isAdmin
                ) else tokenManager.saveRefreshedTokens(
                    credentials,
                    refreshResponse.accessToken,
                    refreshResponse.refreshToken,
                    username = refreshResponse.username,
                    userId = refreshResponse.userId,
                    isAdmin = refreshResponse.isAdmin
                )
            }
            if (!saved) return null
            if (iconAccess != null && runBlocking { tokenManager.iconAuthenticationSnapshot(iconAccess) } == null) return null
            if (syncAccess != null && runBlocking { tokenManager.syncAuthenticationSnapshot(syncAccess) } == null) return null

            return response.request.newBuilder()
                .header("Authorization", "Bearer ${refreshResponse.accessToken}")
                .build()

        } catch (error: Exception) {
            // A timeout, unreachable NAS, proxy failure, or server 5xx does not
            // prove that the refresh token is invalid. Keep the local account
            // session so an automatic retry can recover without data stranding.
            val definitivelyRejected = error is HttpException &&
                error.code() in DEFINITIVE_REFRESH_REJECTION_CODES
            if (definitivelyRejected) {
                runBlocking {
                    if (syncAccess == null) tokenManager.clearRejectedRefresh(credentials)
                    else tokenManager.clearRejectedSyncRefresh(credentials, syncAccess)
                }
            }
            return null
        }
    }

    /**
     * Extension property to count response chain length.
     */
    private val Response.responseCount: Int
        get() {
            var count = 1
            var priorResponse = priorResponse
            while (priorResponse != null) {
                count++
                priorResponse = priorResponse.priorResponse
            }
            return count
        }

    private companion object {
        val DEFINITIVE_REFRESH_REJECTION_CODES = setOf(400, 401, 403)
    }
}
