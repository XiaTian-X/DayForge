package com.dayforge.data.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.mockk.*
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
    private lateinit var connectivity: ConnectivityManager
    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val defaultCallback = slot<ConnectivityManager.NetworkCallback>()
    private val request = slot<NetworkRequest>()
    private lateinit var monitor: NetworkMonitor

    @Before fun setup() {
        connectivity = mockk(relaxed = true)
        every { connectivity.activeNetwork } returns null
        every { connectivity.registerNetworkCallback(capture(request), capture(callback)) } just Runs
        every { connectivity.registerDefaultNetworkCallback(capture(defaultCallback)) } just Runs
    }

    @After fun teardown() {
        if (::monitor.isInitialized) monitor.close()
    }

    private fun start() { monitor = NetworkMonitor(connectivity) }
    private val networkHandles = mutableMapOf<Int, Network>()
    private fun network(id: Int): Network = networkHandles.getOrPut(id) { mockk(name = "network-$id") }

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
        callback.captured.onAvailable(it)
        callback.captured.onCapabilitiesChanged(it, caps(transport))
    }

    @Test fun LAN_without_internet_and_VPN_both_match_the_subscription() {
        start()
        val requested = request.captured
        assertFalse(requested.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(requested.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(requested.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(listOf(wifi), monitor.state.value.localNetworks)
        assertTrue(monitor.state.value.mayBeConnected)
    }

    @Test fun default_cellular_does_not_hide_non_default_wifi_or_ethernet() {
        val mobile = network(1)
        every { connectivity.activeNetwork } returns mobile
        every { connectivity.getNetworkCapabilities(mobile) } returns caps(NetworkCapabilities.TRANSPORT_CELLULAR)
        start()
        val wifi = available(2, NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = available(3, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(listOf(wifi, ethernet), monitor.state.value.localNetworks)
        callback.captured.onLost(wifi)
        assertEquals(listOf(ethernet), monitor.state.value.localNetworks)
        callback.captured.onLost(ethernet)
        assertTrue(monitor.state.value.mayBeConnected)
        callback.captured.onLost(mobile)
        assertFalse(monitor.state.value.mayBeConnected)
    }

    @Test fun VPN_underlying_wifi_is_not_mistaken_for_direct_LAN() {
        start()
        val vpn = available(1, NetworkCapabilities.TRANSPORT_VPN)
        callback.captured.onCapabilitiesChanged(vpn, caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(monitor.state.value.mayBeConnected)
        assertTrue(monitor.state.value.localNetworks.isEmpty())
    }

    @Test fun capabilities_and_blocked_transitions_update_selectable_paths() {
        start()
        val network = available(1, NetworkCapabilities.TRANSPORT_CELLULAR)
        callback.captured.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(listOf(network), monitor.state.value.localNetworks)
        callback.captured.onBlockedStatusChanged(network, true)
        assertTrue(monitor.state.value.localNetworks.isEmpty())
        assertFalse(monitor.state.value.mayBeConnected)
        if (android.os.Build.VERSION.SDK_INT >= 29) callback.captured.onBlockedStatusChanged(network, false)
        assertEquals(listOf(network), monitor.state.value.localNetworks)
    }

    @Test fun late_callbacks_do_not_resurrect_a_lost_network() {
        start()
        val network = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.captured.onLost(network)
        val snapshot = monitor.state.value
        callback.captured.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_WIFI))
        callback.captured.onLinkPropertiesChanged(network, LinkProperties())
        if (android.os.Build.VERSION.SDK_INT >= 29) callback.captured.onBlockedStatusChanged(network, false)
        callback.captured.onLost(network)
        assertEquals(snapshot, monitor.state.value)
    }

    @Test fun duplicate_events_are_quiet_but_DNS_and_validation_changes_notify_sync() {
        start()
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        val before = monitor.state.value.revision
        callback.captured.onAvailable(wifi)
        callback.captured.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(before, monitor.state.value.revision)
        callback.captured.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI, validated = true))
        assertTrue(monitor.state.value.revision > before)
        val links = LinkProperties().apply { interfaceName = "wlan0" }
        callback.captured.onLinkPropertiesChanged(wifi, links)
        val afterLinks = monitor.state.value.revision
        callback.captured.onLinkPropertiesChanged(wifi, links)
        assertEquals(afterLinks, monitor.state.value.revision)
        links.setDnsServers(listOf(java.net.InetAddress.getByName("192.0.2.1")))
        callback.captured.onLinkPropertiesChanged(wifi, links)
        assertTrue(monitor.state.value.revision > afterLinks)
    }

    @Test fun cold_start_waits_for_capabilities_rather_than_onAvailable_alone() = runTest {
        start()
        val result = async { monitor.localNetworks() }
        yield()
        val wifi = network(1)
        callback.captured.onAvailable(wifi)
        yield()
        assertFalse(result.isCompleted)
        callback.captured.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
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
        verify(exactly = 0) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test fun callbacks_do_not_synchronously_query_Android_network_properties() {
        start()
        clearMocks(connectivity, answers = false)
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.captured.onLost(wifi)
        verify(exactly = 0) { connectivity.getNetworkCapabilities(any()) }
        verify(exactly = 0) { connectivity.activeNetwork }
    }

    @Test fun registration_failure_is_unknown_and_discovery_returns_without_waiting() = runTest {
        every { connectivity.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) } throws SecurityException("denied")
        every { connectivity.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws SecurityException("denied")
        start()
        assertFalse(monitor.state.value.monitoring)
        assertTrue(monitor.state.value.mayBeConnected)
        assertTrue(monitor.localNetworks().isEmpty())
        assertEquals(0L, testScheduler.currentTime)
        monitor.close()
        verify(exactly = 0) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test fun snapshot_failure_still_allows_subsequent_callbacks() {
        every { connectivity.activeNetwork } throws SecurityException("denied")
        start()
        assertTrue(monitor.state.value.monitoring)
        assertEquals(listOf(available(1, NetworkCapabilities.TRANSPORT_WIFI)), monitor.state.value.localNetworks)
    }

    @Test fun close_unregisters_once_and_ignores_queued_callbacks() {
        start()
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        monitor.close()
        val snapshot = monitor.state.value
        callback.captured.onAvailable(wifi)
        callback.captured.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        monitor.close()
        assertEquals(snapshot, monitor.state.value)
        verify(exactly = 1) { connectivity.unregisterNetworkCallback(callback.captured) }
        verify(exactly = 1) { connectivity.unregisterNetworkCallback(defaultCallback.captured) }
    }

    @Test fun default_route_switches_wake_observers_without_removing_connected_paths() {
        start()
        val mobile = available(1, NetworkCapabilities.TRANSPORT_CELLULAR)
        val wifi = available(2, NetworkCapabilities.TRANSPORT_WIFI)
        defaultCallback.captured.onAvailable(mobile)
        val before = monitor.state.value.revision
        defaultCallback.captured.onAvailable(wifi)
        assertTrue(monitor.state.value.revision > before)
        assertEquals(wifi, monitor.state.value.defaultNetwork)
        defaultCallback.captured.onLost(mobile)
        assertEquals(wifi, monitor.state.value.defaultNetwork)
        assertEquals(2, monitor.state.value.paths.size)
        defaultCallback.captured.onLost(wifi)
        assertNull(monitor.state.value.defaultNetwork)
        assertEquals(listOf(wifi), monitor.state.value.localNetworks)
        assertTrue(monitor.state.value.mayBeConnected)
    }

    @Test fun default_subscription_failure_leaves_all_network_observation_usable() {
        every { connectivity.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws SecurityException("denied")
        val seeded = network(1)
        every { connectivity.activeNetwork } returns seeded
        every { connectivity.getNetworkCapabilities(seeded) } returns caps(NetworkCapabilities.TRANSPORT_WIFI)
        start()
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(monitor.state.value.monitoring)
        assertEquals(listOf(wifi), monitor.state.value.localNetworks)
        assertEquals(wifi, monitor.state.value.defaultNetwork)
        callback.captured.onLost(wifi)
        assertNull(monitor.state.value.defaultNetwork)
        assertFalse(monitor.state.value.mayBeConnected)
        monitor.close()
        verify(exactly = 1) { connectivity.unregisterNetworkCallback(callback.captured) }
    }

    @Test fun concurrent_paths_cannot_overwrite_each_others_updates() {
        start()
        val paths = (1..12).map { network(it) }
        paths.map { network ->
            Thread {
                callback.captured.onAvailable(network)
                callback.captured.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_WIFI))
            }.apply { start() }
        }.forEach { it.join() }
        assertEquals(paths.toSet(), monitor.state.value.localNetworks.toSet())
        paths.dropLast(1).map { network ->
            Thread { callback.captured.onLost(network) }.apply { start() }
        }.forEach { it.join() }
        assertEquals(listOf(paths.last()), monitor.state.value.localNetworks)
        assertTrue(monitor.state.value.mayBeConnected)
    }

    @Test fun observation_is_registered_before_default_seeding_and_seeded_disconnect_is_removed() {
        val wifi = network(1)
        every { connectivity.activeNetwork } returns wifi
        every { connectivity.getNetworkCapabilities(wifi) } returns caps(NetworkCapabilities.TRANSPORT_WIFI)
        start()
        verifyOrder {
            connectivity.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
            connectivity.activeNetwork
            connectivity.getNetworkCapabilities(wifi)
        }
        assertEquals(listOf(wifi), monitor.state.value.localNetworks)
        callback.captured.onLost(wifi)
        assertTrue(monitor.state.value.localNetworks.isEmpty())
    }
}
