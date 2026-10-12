package com.dayforge.data.api.interceptor

import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.LocalIconAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.api.MaterialHttpRoute
import com.dayforge.data.api.materialTokenSafe
import com.dayforge.data.appearance.ReplicaMaterialAccess
import com.dayforge.data.repository.ReplicaDestinationReadAccess
import java.io.IOException
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
        val materialRecovery = request.tag(ReplicaMaterialAccess::class.java)
        materialRecovery?.let { runBlocking { it.authorizeRequest(tokenManager, request) } }
        val destinationRead = request.tag(ReplicaDestinationReadAccess::class.java)
        destinationRead?.let { runBlocking { it.authorizeRequest(tokenManager, request) } }

        // Skip auth header for authentication endpoints
        if (shouldSkipAuth(url)) {
            return chain.proceed(request)
        }

        val expected = request.tag(AuthenticationSession::class.java)
        val iconAccess = request.tag(LocalIconAccess::class.java)
        val syncAccess = request.tag(LocalSyncAccess::class.java)
        val route = request.tag(MaterialHttpRoute::class.java)
        // Fixed preview and verified destination GET/download only; never ordinary writes,
        // refresh, registration, timer commands or a query/header-only material exemption.
        if (request.url.pathSegments.take(2) == listOf("api", "v2") &&
            request.url.pathSegments.getOrNull(2) in setOf("sync", "timers", "devices", "appearance")) {
            val recoveryRead = route != null && syncAccess != null && request.method == "GET" &&
                request.url.encodedPath == "/api/v2/sync/rounds/recovery-preview" &&
                request.header("X-DayForge-Server-Instance") == syncAccess.session.serverInstanceId &&
                request.header("X-DayForge-Sync-Epoch")?.let {
                    com.dayforge.domain.model.isContractUuid(it) && it != syncAccess.session.syncEpoch
                } == true
            if (!recoveryRead && materialRecovery == null && destinationRead == null && runBlocking { tokenManager.replicaSwitchPending() })
                throw IOException(com.dayforge.data.local.ReplicaSwitchGuard.PENDING)
        }
        if (route != null && !route.matches(request.url)) throw IOException("MATERIAL_ROUTE_CHANGED")
        val credentials = runBlocking {
            if (syncAccess != null) tokenManager.syncAuthenticationSnapshot(syncAccess)
            else if (iconAccess == null) tokenManager.authenticationSnapshot()
            else tokenManager.iconAuthenticationSnapshot(iconAccess)
        }
        // Explicitly captured work may never inherit a new account/login/replica's credentials.
        if ((expected != null && credentials?.session != expected) ||
            ((iconAccess != null || syncAccess != null) && credentials == null)) {
            throw IOException("AUTHENTICATION_SESSION_CHANGED")
        }
        // Material admission proves the public server identity BEFORE sending personal credentials.
        if (route != null && request.url.encodedPath == MaterialHttpRoute.IDENTITY_PATH) {
            return chain.proceed(request.newBuilder().removeHeader("Authorization").build())
        }
        if (route != null && credentials != null && !materialTokenSafe(credentials.accessToken)) {
            throw IOException("MATERIAL_CREDENTIAL_INVALID")
        }

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
