package com.dayforge.data.api.interceptor

import com.dayforge.data.local.PreferencesManager
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that dynamically changes the base URL for API requests.
 * Reads custom server URL from PreferencesManager and rewrites request URLs accordingly.
 *
 * The interceptor replaces only scheme/host/port and preserves the request's API path.
 * This is required while authenticated endpoints use both /api/v1 and /api/v2.
 *
 * Reads DataStore synchronously at the request boundary so a just-saved NAS
 * address is always used by the very next login or sync request.
 */
class BaseUrlInterceptor @Inject constructor(
    private val preferencesManager: PreferencesManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Use cached URL (no blocking)
        val customServerUrl = runBlocking {
            preferencesManager.activeServerUrl.first()
                ?: preferencesManager.serverUrl.first()
        }

        // If no custom URL is set, proceed with the original request
        if (customServerUrl.isNullOrBlank()) {
            return chain.proceed(request)
        }

        // Parse the custom server URL
        val customBaseUrl = customServerUrl.toHttpUrlOrNull()
        if (customBaseUrl == null) {
            throw IOException("Invalid configured DayForge server URL")
        }

        // Rebuild the request URL with the custom base URL
        val newUrl = rebuildUrl(request.url, customBaseUrl)

        val newRequest = request.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }

    /**
     * Rebuilds the request URL using the custom base URL.
     * Extracts scheme, host, port from customBaseUrl (ignoring any path in it)
     * and preserves the complete API endpoint path and query string.
     *
     * @param originalUrl The original request URL (e.g., http://default/api/v1/habits)
     * @param customBaseUrl The custom base URL (e.g., http://192.168.1.1:8080 or http://192.168.1.1:8080/api/v1/)
     * @return The rebuilt URL (e.g., http://192.168.1.1:8080/api/v1/habits)
     */
    internal fun rebuildUrl(originalUrl: HttpUrl, customBaseUrl: HttpUrl): HttpUrl {
        return originalUrl.newBuilder()
            .scheme(customBaseUrl.scheme)
            .host(customBaseUrl.host)
            .port(customBaseUrl.port)
            .build()
    }
}
