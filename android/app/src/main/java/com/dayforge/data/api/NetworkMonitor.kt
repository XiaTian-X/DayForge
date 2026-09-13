package com.dayforge.data.api

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Process-owned observation, shared by settings, endpoint resolution and auto sync. */
@Singleton
class NetworkMonitor @Inject constructor(private val connectivity: ConnectivityManager) : AutoCloseable {
    data class Path(val network: Network, val local: Boolean, val blocked: Boolean)

    data class Snapshot(
        val paths: List<Path> = emptyList(),
        val monitoring: Boolean = true,
        val revision: Long = 0
    ) {
        // Observation failure is unknown, not proof that a server cannot be reached.
        val mayBeConnected: Boolean get() = !monitoring || paths.any { !it.blocked }
        val localNetworks: List<Network> get() = paths.filter { it.local && !it.blocked }.map { it.network }
    }

    private data class Entry(
        var capabilities: NetworkCapabilities? = null,
        var links: LinkProperties? = null,
        var blocked: Boolean = false
    )

    private val lock = Any()
    private val networks = linkedMapOf<Network, Entry>()
    private val mutableState = MutableStateFlow(Snapshot())
    val state = mutableState.asStateFlow()
    private val startedAt = SystemClock.elapsedRealtime()
    private var registered = false
    private var closed = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(lock) {
            if (!closed && network !in networks) {
                networks[network] = Entry()
                publish()
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
            if (closed) return@synchronized
            val entry = networks[network] ?: return@synchronized
            if (entry.capabilities != capabilities) {
                entry.capabilities = NetworkCapabilities(capabilities)
                publish()
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = synchronized(lock) {
            if (closed) return@synchronized
            val entry = networks[network] ?: return@synchronized
            if (entry.links != linkProperties) {
                // The copy constructor is not public SDK API; parcel a defensive copy.
                val parcel = Parcel.obtain()
                entry.links = try {
                    linkProperties.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    LinkProperties.CREATOR.createFromParcel(parcel)
                } finally {
                    parcel.recycle()
                }
                publish()
            }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = synchronized(lock) {
            if (closed) return@synchronized
            val entry = networks[network] ?: return@synchronized
            if (entry.blocked != blocked) {
                entry.blocked = blocked
                publish()
            }
        }

        override fun onLost(network: Network) = synchronized(lock) {
            if (!closed && networks.remove(network) != null) publish()
        }
    }

    init {
        synchronized(lock) {
            try {
                val request = NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                // Register before seeding so a concurrent disconnect is delivered afterwards.
                // No INTERNET or VALIDATED requirement: local NAS networks must be observed.
                connectivity.registerNetworkCallback(request, callback)
                registered = true
            } catch (error: RuntimeException) {
                Log.w(TAG, "Network callbacks unavailable; endpoint probing remains enabled", error)
            }
            // A best-effort default-network seed makes the UI useful immediately.
            // Subsequent updates use callback payloads, never synchronous queries in callbacks.
            try {
                connectivity.activeNetwork?.let { network ->
                    connectivity.getNetworkCapabilities(network)?.let { capabilities ->
                        networks[network] = Entry(capabilities = NetworkCapabilities(capabilities))
                    }
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Initial network observation unavailable", error)
            }
            publish(monitoring = registered)
        }
    }

    /** Android has no initial-enumeration-complete event. Bound only the cold-start discovery wait. */
    suspend fun localNetworks(): List<Network> {
        val current = state.value
        val remaining = DISCOVERY_MILLIS - (SystemClock.elapsedRealtime() - startedAt)
        if (current.localNetworks.isEmpty() && current.monitoring && remaining > 0) {
            withTimeoutOrNull(remaining) {
                state.first { it.localNetworks.isNotEmpty() || !it.monitoring }
            }
        }
        return state.value.localNetworks
    }

    private fun publish(monitoring: Boolean = mutableState.value.monitoring) {
        mutableState.value = Snapshot(
            paths = networks.map { (network, entry) ->
                val caps = entry.capabilities
                // VPN may advertise its underlying Wi-Fi; let the default route handle VPN.
                val local = caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                Path(network, local, entry.blocked)
            },
            monitoring = monitoring,
            revision = mutableState.value.revision + 1
        )
    }

    /** Only the process owner (or a test fixture) closes this singleton, never a screen. */
    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        networks.clear()
        publish(monitoring = false)
        if (registered) {
            registered = false
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Network callback cleanup failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "NetworkMonitor"
        const val DISCOVERY_MILLIS = 1_000L
    }
}
