package com.dayforge.data.api

import android.net.Network
import com.dayforge.data.api.dto.ServerIdentityResponse
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    private val networks: NetworkMonitor,
    private val preferences: PreferencesManager,
    private val tokens: TokenManager,
    private val json: Json,
    private val selectedTransport: SelectedNetworkTransport
) {
    suspend fun resolve(): ServerIdentityResponse? = withContext(Dispatchers.IO) {
        val local = preferences.serverUrl.first()?.takeIf { it.isNotBlank() }
        val remote = preferences.remoteServerUrl.first()?.takeIf { it.isNotBlank() }
        if (local == null && remote == null) {
            selectedTransport.select(null)
            preferences.setActiveServerUrl(null)
            return@withContext null
        }

        val localNetworks = if (local != null) networks.localNetworks() else emptyList()
        val ordered = buildList {
            if (local != null) {
                localNetworks.forEach { add(Candidate(local, it, requireHttps = false)) }
            }
            remote?.let { add(Candidate(it, null, requireHttps = true)) }
            // Default-route fallback also supports VPN, loopback, callback failure and late discovery.
            local?.let { add(Candidate(it, null, requireHttps = false)) }
        }
        val knownInstance = tokens.serverInstanceId.first()
        var identityMismatch = false
        var lastFailure: Throwable? = null
        ordered.forEach { candidate ->
            coroutineContext.ensureActive()
            try {
                val identity = probe(candidate)
                coroutineContext.ensureActive()
                if (knownInstance != null && identity.serverInstanceId != knownInstance) {
                    identityMismatch = true
                    return@forEach
                }
                selectedTransport.select(candidate.network)
                preferences.setActiveServerUrl(candidate.url)
                return@withContext identity
            } catch (error: CancellationException) {
                throw error
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
