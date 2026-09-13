package com.dayforge.data.api

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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Config(sdk = [26, 34])
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
    private fun caps(transport: Int) = NetworkCapabilities().also { shadowOf(it).addTransportType(transport) }
    private fun available(id: Int, transport: Int): Network = ShadowNetwork.newInstance(id).also {
        callback.captured.onAvailable(it)
        callback.captured.onCapabilitiesChanged(it, caps(transport))
    }

    @Test fun `LAN without internet and VPN both match the subscription`() {
        start()
        val requested = ReflectionHelpers.getField<NetworkCapabilities>(request.captured, "networkCapabilities")
        assertFalse(requested.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(requested.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(requested.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(listOf(wifi), monitor.state.value.localNetworks)
        assertTrue(monitor.state.value.mayBeConnected)
    }

    @Test fun `default cellular does not hide non default wifi or ethernet`() {
        val mobile = ShadowNetwork.newInstance(1)
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

    @Test fun `VPN underlying wifi is not mistaken for direct LAN`() {
        start()
        val vpn = available(1, NetworkCapabilities.TRANSPORT_VPN)
        callback.captured.onCapabilitiesChanged(vpn, caps(NetworkCapabilities.TRANSPORT_VPN)
            .also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        assertTrue(monitor.state.value.mayBeConnected)
        assertTrue(monitor.state.value.localNetworks.isEmpty())
    }

    @Test @Config(sdk = [34]) fun `capabilities and blocked transitions update selectable paths`() {
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

    @Test fun `late callbacks do not resurrect a lost network`() {
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

    @Test fun `duplicate events are quiet but DNS and validation changes notify sync`() {
        start()
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        val before = monitor.state.value.revision
        callback.captured.onAvailable(wifi)
        callback.captured.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(before, monitor.state.value.revision)
        callback.captured.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI)
            .also { shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) })
        assertTrue(monitor.state.value.revision > before)
        val links = LinkProperties().apply { interfaceName = "wlan0" }
        callback.captured.onLinkPropertiesChanged(wifi, links)
        val afterLinks = monitor.state.value.revision
        callback.captured.onLinkPropertiesChanged(wifi, links)
        assertEquals(afterLinks, monitor.state.value.revision)
        ReflectionHelpers.callInstanceMethod<Void>(links, "setDnsServers",
            ClassParameter.from(Collection::class.java, listOf(java.net.InetAddress.getByName("192.0.2.1"))))
        callback.captured.onLinkPropertiesChanged(wifi, links)
        assertTrue(monitor.state.value.revision > afterLinks)
    }

    @Test fun `cold start waits for capabilities rather than onAvailable alone`() = runTest {
        start()
        val result = async { monitor.localNetworks() }
        yield()
        val wifi = ShadowNetwork.newInstance(1)
        callback.captured.onAvailable(wifi)
        yield()
        assertFalse(result.isCompleted)
        callback.captured.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(listOf(wifi), result.await())
    }

    @Test fun `cold start discovery is bounded when no LAN exists`() = runTest {
        start()
        assertTrue(monitor.localNetworks().isEmpty())
        assertTrue(testScheduler.currentTime in 1..1_000)
    }

    @Test fun `cancelling discovery does not close shared observation`() = runTest {
        start()
        val result = async { monitor.localNetworks() }
        yield()
        result.cancel()
        result.join()
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(listOf(wifi), monitor.localNetworks())
        verify(exactly = 0) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test fun `callbacks do not synchronously query Android network properties`() {
        start()
        clearMocks(connectivity, answers = false)
        val wifi = available(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.captured.onLost(wifi)
        verify(exactly = 0) { connectivity.getNetworkCapabilities(any()) }
        verify(exactly = 0) { connectivity.activeNetwork }
    }

    @Test fun `registration failure is unknown and discovery returns without waiting`() = runTest {
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

    @Test fun `snapshot failure still allows subsequent callbacks`() {
        every { connectivity.activeNetwork } throws SecurityException("denied")
        start()
        assertTrue(monitor.state.value.monitoring)
        assertEquals(listOf(available(1, NetworkCapabilities.TRANSPORT_WIFI)), monitor.state.value.localNetworks)
    }

    @Test fun `close unregisters once and ignores queued callbacks`() {
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

    @Test fun `default route switches wake observers without removing connected paths`() {
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

    @Test fun `default subscription failure leaves all-network observation usable`() {
        every { connectivity.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws SecurityException("denied")
        val seeded = ShadowNetwork.newInstance(1)
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

    @Test fun `concurrent paths cannot overwrite each others updates`() {
        start()
        val paths = (1..12).map { ShadowNetwork.newInstance(it) }
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

    @Test fun `observation is registered before default seeding and seeded disconnect is removed`() {
        val wifi = ShadowNetwork.newInstance(1)
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
