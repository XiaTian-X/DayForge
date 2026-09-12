package com.dayforge.data.api.interceptor

import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.AuthenticationSession
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

        val credentials = runBlocking { tokenManager.authenticationSnapshot() }

        // Add Authorization header if token exists
        val authenticatedRequest = if (credentials != null) {
            request.newBuilder()
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .tag(AuthenticationSession::class.java, credentials.session)
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
