package com.dayforge.data.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.net.Network
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndpointResolverTest {
    private val storeJob = SupervisorJob()
    private val serverError = AtomicReference<Throwable?>()
    private lateinit var store: DataStore<Preferences>
    private lateinit var file: File
    private lateinit var preferences: PreferencesManager
    private lateinit var tokens: TokenManager
    private lateinit var selectedTransport: SelectedNetworkTransport
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var networks: NetworkMonitor

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "endpoint-resolver-${UUID.randomUUID()}.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = CoroutineScope(storeJob + Dispatchers.IO), produceFile = { file })
        preferences = PreferencesManager(store)
        tokens = TokenManager(store)
        selectedTransport = SelectedNetworkTransport()
        networks = mockk()
        coEvery { networks.localNetworks() } returns listOf(route())
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        serverThread = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val body = IDENTITY.toByteArray()
                    socket.getOutputStream().bufferedWriter().use { writer ->
                        writer.write("HTTP/1.1 200 OK\r\n")
                        writer.write("Content-Type: application/json\r\n")
                        writer.write("Content-Length: ${body.size}\r\n")
                        writer.write("Connection: close\r\n\r\n")
                        writer.write(IDENTITY)
                    }
                }
            } catch (error: Throwable) {
                if (error !is SocketException || !server.isClosed) serverError.set(error)
            }
        }
        serverThread.start()
    }

    @After
    fun teardown() {
        server.close()
        serverThread.join(3_000)
        runBlocking { storeJob.cancelAndJoin() }
        assertTrue("DataStore fixture was not removed", file.delete() || !file.exists())
        assertTrue("HTTP fixture thread did not stop", !serverThread.isAlive)
        assertNull("Unexpected HTTP fixture failure", serverError.get())
    }

    @Test
    fun reachable_local_endpoint_is_selected_and_remembered() = runBlocking {
        val url = "http://127.0.0.1:${server.localPort}"
        preferences.setServerUrl(url)

        val identity = resolver().resolve()

        assertEquals(SERVER_ID, identity?.serverInstanceId)
        assertEquals(url, preferences.activeServerUrl.first())
        assertNotNull(selectedTransport.currentNetwork())
    }

    @Test
    fun known_server_identity_prevents_failover_to_a_different_instance() = runBlocking {
        preferences.setServerUrl("http://127.0.0.1:${server.localPort}")
        tokens.saveServerIdentity("00000000-0000-4000-8000-000000000099", EPOCH)

        val error = runCatching { resolver().resolve() }.exceptionOrNull()

        assertTrue(error is EndpointIdentityMismatchException)
        assertNull(preferences.activeServerUrl.first())
    }

    @Test
    fun a_failed_wifi_route_falls_through_to_working_ethernet() = runBlocking {
        val broken = mockk<Network>()
        every { broken.socketFactory } throws java.io.IOException("network disappeared")
        val working = route()
        coEvery { networks.localNetworks() } returns listOf(broken, working)
        preferences.setServerUrl("http://127.0.0.1:${server.localPort}")
        assertEquals(SERVER_ID, resolver().resolve()?.serverInstanceId)
        assertEquals(working, selectedTransport.currentNetwork())
    }

    @Test
    fun failed_bound_paths_fall_through_to_the_default_VPN_route() = runBlocking {
        val broken = mockk<Network>()
        every { broken.socketFactory } throws java.io.IOException("network disappeared")
        coEvery { networks.localNetworks() } returns listOf(broken)
        preferences.setServerUrl("http://127.0.0.1:${server.localPort}")
        assertEquals(SERVER_ID, resolver().resolve()?.serverInstanceId)
        assertNull(selectedTransport.currentNetwork())
    }

    @Test
    fun unknown_or_empty_network_observation_does_not_block_local_server_probing() = runBlocking {
        coEvery { networks.localNetworks() } returns emptyList()
        preferences.setServerUrl("http://127.0.0.1:${server.localPort}")
        assertEquals(SERVER_ID, resolver().resolve()?.serverInstanceId)
        assertNull(selectedTransport.currentNetwork())
    }

    @Test
    fun cancellation_during_a_probe_never_selects_or_falls_back_to_another_route() = runBlocking {
        val cancelled = mockk<Network>()
        every { cancelled.socketFactory } throws CancellationException("cancelled")
        coEvery { networks.localNetworks() } returns listOf(cancelled, route())
        preferences.setServerUrl("http://127.0.0.1:${server.localPort}")
        assertTrue(runCatching { resolver().resolve() }.exceptionOrNull() is CancellationException)
        assertNull(preferences.activeServerUrl.first())
        assertNull(selectedTransport.currentNetwork())
    }

    @Test
    fun no_configured_address_clears_selection_without_waiting_for_network_discovery() = runBlocking {
        selectedTransport.select(route())
        preferences.setActiveServerUrl("http://example.invalid")
        assertNull(resolver().resolve())
        assertNull(selectedTransport.currentNetwork())
        assertNull(preferences.activeServerUrl.first())
        coVerify(exactly = 0) { networks.localNetworks() }
    }

    @Test
    fun all_failed_candidates_clear_a_previously_selected_network() = runBlocking {
        val port = server.localPort
        server.close()
        selectedTransport.select(route())
        preferences.setServerUrl("http://127.0.0.1:$port")
        assertTrue(runCatching { resolver().resolve() }.exceptionOrNull() is java.io.IOException)
        assertNull(selectedTransport.currentNetwork())
    }

    @Test
    fun remote_endpoint_refuses_cleartext_even_when_reachable() = runBlocking {
        preferences.setRemoteServerUrl("http://127.0.0.1:${server.localPort}")

        val error = runCatching { resolver().resolve() }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertNull(preferences.activeServerUrl.first())
        coVerify(exactly = 0) { networks.localNetworks() }
    }

    private fun resolver() = EndpointResolver(
        networks,
        preferences,
        tokens,
        Json { ignoreUnknownKeys = true },
        selectedTransport
    )

    private fun route(): Network = mockk<Network>().also { network ->
        every { network.socketFactory } returns javax.net.SocketFactory.getDefault()
        every { network.getAllByName(any()) } answers { InetAddress.getAllByName(firstArg()) }
    }

    private companion object {
        const val SERVER_ID = "00000000-0000-4000-8000-000000000001"
        const val EPOCH = "00000000-0000-4000-8000-000000000002"
        const val IDENTITY = """{"server_instance_id":"$SERVER_ID","sync_epoch":"$EPOCH","protocol_version":3,"capabilities":["sync_v2","timer_commands"],"server_time":"2026-08-14T00:00:00Z"}"""
    }
}
