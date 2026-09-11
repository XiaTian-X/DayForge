package com.dayforge.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.dayforge.data.api.dto.ServerIdentityResponse
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Selects one verified endpoint for an entire sync attempt. */
@Singleton
class EndpointResolver @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: PreferencesManager,
    private val tokens: TokenManager,
    private val json: Json,
    private val selectedTransport: SelectedNetworkTransport
) {
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun resolve(): ServerIdentityResponse? = withContext(Dispatchers.IO) {
        val local = preferences.serverUrl.first()?.takeIf { it.isNotBlank() }
        val remote = preferences.remoteServerUrl.first()?.takeIf { it.isNotBlank() }
        if (local == null && remote == null) {
            selectedTransport.select(null)
            preferences.setActiveServerUrl(null)
            return@withContext null
        }

        val localNetwork = findLocalNetwork()
        val ordered = if (localNetwork != null) {
            listOfNotNull(
                local?.let { Candidate(it, localNetwork, requireHttps = false) },
                remote?.let { Candidate(it, null, requireHttps = true) }
            )
        } else {
            listOfNotNull(
                remote?.let { Candidate(it, null, requireHttps = true) },
                local?.let { Candidate(it, null, requireHttps = false) }
            )
        }
        val knownInstance = tokens.serverInstanceId.first()
        var identityMismatch = false
        var lastFailure: Throwable? = null
        ordered.forEach { candidate ->
            try {
                val identity = probe(candidate)
                if (knownInstance != null && identity.serverInstanceId != knownInstance) {
                    identityMismatch = true
                    return@forEach
                }
                selectedTransport.select(candidate.network)
                preferences.setActiveServerUrl(candidate.url)
                return@withContext identity
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        if (identityMismatch) {
            selectedTransport.select(null)
            throw EndpointIdentityMismatchException("备用地址连接到了另一台 DayForge 服务器")
        }
        selectedTransport.select(null)
        throw IOException("无法连接已配置的 DayForge 服务器", lastFailure)
    }

    private fun findLocalNetwork(): Network? = connectivity.allNetworks.firstOrNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun probe(candidate: Candidate): ServerIdentityResponse {
        val base = candidate.url.toHttpUrlOrNull()
            ?: throw IOException("DayForge server URL is invalid")
        if (candidate.requireHttps && !base.isHttps) {
            throw IOException("Remote DayForge endpoint must use HTTPS")
        }
        val identityUrl = base.newBuilder()
            .encodedPath("/api/v2/system/identity")
            .query(null)
            .build()
        val builder = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
        candidate.network?.let { network ->
            builder.socketFactory(network.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            })
        }
        builder.build().newCall(Request.Builder().url(identityUrl).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Identity probe failed: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Identity probe returned no body")
            return json.decodeFromString(ServerIdentityResponse.serializer(), body)
        }
    }

    private data class Candidate(
        val url: String,
        val network: Network?,
        val requireHttps: Boolean
    )

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 4L
    }
}

class EndpointIdentityMismatchException(message: String) : IllegalStateException(message)
