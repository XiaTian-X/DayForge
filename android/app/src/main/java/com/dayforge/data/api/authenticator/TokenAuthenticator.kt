package com.dayforge.data.api.authenticator

import com.dayforge.BuildConfig
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.dto.RefreshRequest
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import kotlinx.coroutines.flow.first
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

    override fun authenticate(route: Route?, response: Response): Request? {
        // Don't retry more than once
        if (response.responseCount >= 2) {
            return null
        }

        // Skip refresh for auth endpoints
        val url = response.request.url.toString()
        if (url.contains("auth/login") || url.contains("auth/refresh")) {
            return null
        }

        synchronized(this) {
            // A concurrent request may have refreshed while this request waited.
            val currentAccessToken = runBlocking { tokenManager.accessToken.first() }
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            if (currentAccessToken != null && requestToken != currentAccessToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentAccessToken")
                    .build()
            }

            try {
                val refreshToken = runBlocking { tokenManager.refreshToken.first() }

                if (refreshToken == null) return null

                // Create a temporary AuthApi client without an authenticator to avoid a loop.
                val json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
                val client = OkHttpClient.Builder()
                    .socketFactory(selectedTransport.socketFactory)
                    .dns(selectedTransport.dns)
                    .addInterceptor(BaseUrlInterceptor(preferencesManager))
                    .addInterceptor(HttpLoggingInterceptor().apply {
                        level = if (BuildConfig.DEBUG) {
                            HttpLoggingInterceptor.Level.BASIC
                        } else {
                            HttpLoggingInterceptor.Level.NONE
                        }
                        redactHeader("Authorization")
                        redactHeader("Cookie")
                    })
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl("http://localhost:8000/api/v1/")
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .client(client)
                    .build()

                val authApi = retrofit.create(AuthApi::class.java)
                val refreshResponse = runBlocking {
                    authApi.refreshToken(RefreshRequest(refreshToken))
                }

                runBlocking {
                    tokenManager.saveTokens(
                        refreshResponse.accessToken,
                        refreshResponse.refreshToken,
                        email = refreshResponse.username,
                        userId = refreshResponse.userId,
                        isAdmin = refreshResponse.isAdmin
                    )
                }

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
                        tokenManager.clearAuthenticationTokens()
                    }
                }
                return null
            }
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
