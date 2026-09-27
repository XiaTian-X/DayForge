package com.dayforge.data.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NetworkMonitorTest {
    private lateinit var connectivity: FakeObservationSource
    private val callback get() = connectivity.callback
    private val defaultCallback get() = connectivity.defaultCallback
    private lateinit var monitor: NetworkMonitor

    @Before fun setup() {
        connectivity = FakeObservationSource()
    }

    @After fun teardown() {
        if (::monitor.isInitialized) monitor.close()
    }

    private fun start() { monitor = NetworkMonitor(connectivity) }

    /** Records system calls only. All path/state/concurrency decisions remain in NetworkMonitor. */
    private class FakeObservationSource : NetworkObservationSource {
        var initialNetwork: Network? = null
        val capabilities = mutableMapOf<Network, NetworkCapabilities>()
        val calls = mutableListOf<String>()
        val capabilityQueries = mutableListOf<Network>()
        val unregistered = mutableListOf<ConnectivityManager.NetworkCallback>()
        lateinit var callback: ConnectivityManager.NetworkCallback
        lateinit var defaultCallback: ConnectivityManager.NetworkCallback
        lateinit var request: NetworkRequest
        var failRegistration = false
        var failDefaultRegistration = false
        var failSnapshot = false
        override val activeNetwork: Network? get() {
            calls += "active"
            if (failSnapshot) throw SecurityException("denied")
            return initialNetwork
        }
        override fun getNetworkCapabilities(network: Network): NetworkCapabilities? {
            calls += "capabilities"
            capabilityQueries += network
            return capabilities[network]
        }
        override fun registerNetworkCallback(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback) {
            calls += "register"
            if (failRegistration) throw SecurityException("denied")
            this.request = request
            this.callback = callback
        }
        override fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            calls += "registerDefault"
            if (failDefaultRegistration) throw SecurityException("denied")
            defaultCallback = callback
        }
        override fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            calls += "unregister"
            unregistered += callback
        }
    }

    @Test fun production_constructor_registers_and_closes_real_android_observation() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val real = NetworkMonitor(context.getSystemService(ConnectivityManager::class.java))
        real.use { assertTrue(it.state.value.monitoring) }
        assertFalse(real.state.value.monitoring)
        assertTrue(real.state.value.paths.isEmpty())
        real.close()
    }

    // Identity values only: never bind sockets or query a real interface with these handles.
    // AOSP android15-release packages/modules/Connectivity/framework/src/android/net/Network.java
    // parcels one netId integer. Exercise Android's real equality/hashCode rather than MockK's
    // invocation recording on every map/StateFlow comparison; see the round-trip regression below.
    private fun network(id: Int): Network {
        require(id > 0)
        val parcel = android.os.Parcel.obtain()
        return try {
            parcel.writeInt(id)
            parcel.setDataPosition(0)
            Network.CREATOR.createFromParcel(parcel).also { assertEquals(0, parcel.dataAvail()) }
        } finally {
            parcel.recycle()
        }
    }

    @Test fun real_network_values_round_trip_and_keep_equal_identity_across_instances() {
        val first = network(1)
        val same = network(1)
        val other = network(2)
        assertNotSame(first, same)
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, other)
        assertEquals(2, setOf(first, same, other).size)
        assertEquals(first, Network.fromNetworkHandle(first.networkHandle))
        val parcel = android.os.Parcel.obtain()
        try {
            first.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            assertEquals(1, parcel.readInt())
            assertEquals(0, parcel.dataAvail())
            parcel.setDataPosition(0)
            assertEquals(first, Network.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
        start()
        callback.onAvailable(first)
        callback.onCapabilitiesChanged(same, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(listOf(first), monitor.state.value.localNetworks)
        callback.onLost(network(1))
        assertTrue(monitor.state.value.paths.isEmpty())
    }

    // Test fixture only: Android 15 NetworkRequest parcels start with NetworkCapabilities.
    // AOSP android15-release/framework/src/android/net/NetworkRequest.java#writeToParcel.
    // Public builders/CREATOR construct real values, so the production defensive copy is exercised.
    // Explicit assertions below fail if the platform parcel format or builder defaults change.
    private fun caps(vararg transports: Int, validated: Boolean = false): NetworkCapabilities {
        val builder = NetworkRequest.Builder().clearCapabilities()
        transports.forEach { builder.addTransportType(it) }
        if (validated) builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val parcel = android.os.Parcel.obtain()
        try {
            builder.build().writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return NetworkCapabilities.CREATOR.createFromParcel(parcel).also {
                assertEquals(transports.toSet(), (0..10).filter(it::hasTransport).toSet())
                assertEquals(
                    if (validated) setOf(NetworkCapabilities.NET_CAPABILITY_VALIDATED) else emptySet<Int>(),
                    it.capabilities.toSet()
                )
            }
        } finally {
            parcel.recycle()
        }
    }
    private fun available(id: Int, transport: Int): Network = network(id).also {
        callback.onAvailable(it)
        callback.onCapabilitiesChanged(it, caps(transport))
    }

    @Test fun LAN_without_internet_and_VPN_both_match_the_subscription() {
        start()
        val requested = connectivity.request
        assertFalse(requested.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(requested.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(requested.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(listOf(wifi), monitor.state.value.localNetworks)
        assertTrue(monitor.state.value.mayBeConnected)
    }

    @Test fun default_cellular_does_not_hide_non_default_wifi_or_ethernet() {
        val mobile = network(1)
        connectivity.initialNetwork = mobile
        connectivity.capabilities[mobile] = caps(NetworkCapabilities.TRANSPORT_CELLULAR)
        start()
        val wifi = available(2, NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = available(3, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(listOf(wifi, ethernet), monitor.state.value.localNetworks)
        callback.onLost(wifi)
        assertEquals(listOf(ethernet), monitor.state.value.localNetworks)
        callback.onLost(ethernet)
        assertTrue(monitor.state.value.mayBeConnected)
        callback.onLost(mobile)
        assertFalse(monitor.state.value.mayBeConnected)
    }

    @Test fun VPN_underlying_wifi_is_not_mistaken_for_direct_LAN() {
        start()
        val vpn = available(1, NetworkCapabilities.TRANSPORT_VPN)
        callback.onCapabilitiesChanged(vpn, caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(monitor.state.value.mayBeConnected)
        assertTrue(monitor.state.value.localNetworks.isEmpty())
    }

    @Test fun capabilities_and_blocked_transitions_update_selectable_paths() {
        start()
        val network = available(1, NetworkCapabilities.TRANSPORT_CELLULAR)
        callback.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(listOf(network), monitor.state.value.localNetworks)
        callback.onBlockedStatusChanged(network, true)
        assertTrue(monitor.state.value.localNetworks.isEmpty())
        assertFalse(monitor.state.value.mayBeConnected)
        if (android.os.Build.VERSION.SDK_INT >= 29) callback.onBlockedStatusChanged(network, false)
        assertEquals(listOf(network), monitor.state.value.localNetworks)
    }

    @Test fun late_callbacks_do_not_resurrect_a_lost_network() {
        start()
        val network = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.onLost(network)
        val snapshot = monitor.state.value
        callback.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_WIFI))
        callback.onLinkPropertiesChanged(network, LinkProperties())
        if (android.os.Build.VERSION.SDK_INT >= 29) callback.onBlockedStatusChanged(network, false)
        callback.onLost(network)
        assertEquals(snapshot, monitor.state.value)
    }

    @Test fun duplicate_events_are_quiet_but_DNS_and_validation_changes_notify_sync() {
        start()
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        val before = monitor.state.value.revision
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(before, monitor.state.value.revision)
        callback.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI, validated = true))
        assertTrue(monitor.state.value.revision > before)
        val links = LinkProperties().apply { interfaceName = "wlan0" }
        callback.onLinkPropertiesChanged(wifi, links)
        val afterLinks = monitor.state.value.revision
        callback.onLinkPropertiesChanged(wifi, links)
        assertEquals(afterLinks, monitor.state.value.revision)
        links.setDnsServers(listOf(java.net.InetAddress.getByName("192.0.2.1")))
        callback.onLinkPropertiesChanged(wifi, links)
        assertTrue(monitor.state.value.revision > afterLinks)
    }

    @Test fun cold_start_waits_for_capabilities_rather_than_onAvailable_alone() = runTest {
        start()
        val result = async { monitor.localNetworks() }
        yield()
        val wifi = network(1)
        callback.onAvailable(wifi)
        yield()
        assertFalse(result.isCompleted)
        callback.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(listOf(wifi), result.await())
    }

    @Test fun cold_start_discovery_is_bounded_when_no_LAN_exists() = runTest {
        start()
        assertTrue(monitor.localNetworks().isEmpty())
        assertTrue(testScheduler.currentTime in 1..1_000)
    }

    @Test fun cancelling_discovery_does_not_close_shared_observation() = runTest {
        start()
        val result = async { monitor.localNetworks() }
        yield()
        result.cancel()
        result.join()
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(listOf(wifi), monitor.localNetworks())
        assertTrue(connectivity.unregistered.isEmpty())
    }

    @Test fun callbacks_do_not_synchronously_query_Android_network_properties() {
        start()
        connectivity.calls.clear()
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.onLost(wifi)
        assertTrue(connectivity.calls.isEmpty())
    }

    @Test fun registration_failure_is_unknown_and_discovery_returns_without_waiting() = runTest {
        connectivity.failRegistration = true
        connectivity.failDefaultRegistration = true
        start()
        assertFalse(monitor.state.value.monitoring)
        assertTrue(monitor.state.value.mayBeConnected)
        assertTrue(monitor.localNetworks().isEmpty())
        assertEquals(0L, testScheduler.currentTime)
        monitor.close()
        assertTrue(connectivity.unregistered.isEmpty())
    }

    @Test fun snapshot_failure_still_allows_subsequent_callbacks() {
        connectivity.failSnapshot = true
        start()
        assertTrue(monitor.state.value.monitoring)
        assertEquals(listOf(available(1, NetworkCapabilities.TRANSPORT_WIFI)), monitor.state.value.localNetworks)
    }

    @Test fun close_unregisters_once_and_ignores_queued_callbacks() {
        start()
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        monitor.close()
        val snapshot = monitor.state.value
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        monitor.close()
        assertEquals(snapshot, monitor.state.value)
        assertEquals(listOf(callback, defaultCallback), connectivity.unregistered)
    }

    @Test fun default_route_switches_wake_observers_without_removing_connected_paths() {
        start()
        val mobile = available(1, NetworkCapabilities.TRANSPORT_CELLULAR)
        val wifi = available(2, NetworkCapabilities.TRANSPORT_WIFI)
        defaultCallback.onAvailable(mobile)
        val before = monitor.state.value.revision
        defaultCallback.onAvailable(wifi)
        assertTrue(monitor.state.value.revision > before)
        assertEquals(wifi, monitor.state.value.defaultNetwork)
        defaultCallback.onLost(mobile)
        assertEquals(wifi, monitor.state.value.defaultNetwork)
        assertEquals(2, monitor.state.value.paths.size)
        defaultCallback.onLost(wifi)
        assertNull(monitor.state.value.defaultNetwork)
        assertEquals(listOf(wifi), monitor.state.value.localNetworks)
        assertTrue(monitor.state.value.mayBeConnected)
    }

    @Test fun default_subscription_failure_leaves_all_network_observation_usable() {
        connectivity.failDefaultRegistration = true
        val seeded = network(1)
        connectivity.initialNetwork = seeded
        connectivity.capabilities[seeded] = caps(NetworkCapabilities.TRANSPORT_WIFI)
        start()
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(monitor.state.value.monitoring)
        assertEquals(listOf(wifi), monitor.state.value.localNetworks)
        assertEquals(wifi, monitor.state.value.defaultNetwork)
        callback.onLost(wifi)
        assertNull(monitor.state.value.defaultNetwork)
        assertFalse(monitor.state.value.mayBeConnected)
        monitor.close()
        assertEquals(listOf(callback), connectivity.unregistered)
    }

    @Test fun concurrent_paths_cannot_overwrite_each_others_updates() {
        start()
        val paths = (1..12).map { network(it) }
        paths.map { network ->
            Thread {
                callback.onAvailable(network)
                callback.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_WIFI))
            }.apply { start() }
        }.forEach { it.join() }
        assertEquals(paths.toSet(), monitor.state.value.localNetworks.toSet())
        paths.dropLast(1).map { network ->
            Thread { callback.onLost(network) }.apply { start() }
        }.forEach { it.join() }
        assertEquals(listOf(paths.last()), monitor.state.value.localNetworks)
        assertTrue(monitor.state.value.mayBeConnected)
    }

    @Test fun observation_is_registered_before_default_seeding_and_seeded_disconnect_is_removed() {
        val wifi = network(1)
        connectivity.initialNetwork = wifi
        connectivity.capabilities[wifi] = caps(NetworkCapabilities.TRANSPORT_WIFI)
        start()
        assertEquals(listOf("register", "registerDefault", "active", "capabilities"), connectivity.calls)
        assertEquals(listOf(wifi), connectivity.capabilityQueries)
        assertEquals(listOf(wifi), monitor.state.value.localNetworks)
        callback.onLost(wifi)
        assertTrue(monitor.state.value.localNetworks.isEmpty())
    }
}
