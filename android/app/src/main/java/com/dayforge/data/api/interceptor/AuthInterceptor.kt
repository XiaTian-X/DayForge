package com.dayforge.data.api.interceptor

import com.dayforge.data.local.TokenManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that adds Authorization header to requests.
 * Skips auth header for login/refresh endpoints.
 *
 * Reads the current token at the request boundary so account changes cannot
 * race an asynchronously populated cache.
 */
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        // Skip auth header for authentication endpoints
        if (shouldSkipAuth(url)) {
            return chain.proceed(request)
        }

        // Use cached token (no blocking)
        val token = runBlocking { tokenManager.accessToken.first() }

        // Add Authorization header if token exists
        val authenticatedRequest = if (token != null) {
            request.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }

        return chain.proceed(authenticatedRequest)
    }

    /**
     * Returns true if the URL should not have auth header.
     * This includes login and refresh endpoints.
     */
    private fun shouldSkipAuth(url: String): Boolean {
        return url.contains("auth/login") ||
                url.contains("auth/refresh")
    }
}
