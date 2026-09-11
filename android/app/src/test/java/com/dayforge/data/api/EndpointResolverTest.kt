package com.dayforge.data.api

import android.content.Context
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EndpointResolverTest {
    private lateinit var store: DataStore<Preferences>
    private lateinit var file: File
    private lateinit var preferences: PreferencesManager
    private lateinit var tokens: TokenManager
    private lateinit var selectedTransport: SelectedNetworkTransport
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "endpoint-resolver.preferences_pb")
        store = PreferenceDataStoreFactory.create(produceFile = { file })
        preferences = PreferencesManager(store)
        tokens = TokenManager(store)
        selectedTransport = SelectedNetworkTransport()
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        serverThread = Thread {
            runCatching {
                server.accept().use { socket ->
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
            }
        }
        serverThread.start()
    }

    @After
    fun teardown() {
        server.close()
        serverThread.join(1_000)
        file.delete()
    }

    @Test
    fun `reachable local endpoint is selected and remembered`() = runTest {
        val url = "http://127.0.0.1:${server.localPort}"
        preferences.setServerUrl(url)

        val identity = resolver().resolve()

        assertEquals(SERVER_ID, identity?.serverInstanceId)
        assertEquals(url, preferences.activeServerUrl.first())
        assertNotNull(selectedTransport.currentNetwork())
    }

    @Test
    fun `known server identity prevents failover to a different instance`() = runTest {
        preferences.setServerUrl("http://127.0.0.1:${server.localPort}")
        tokens.saveServerIdentity("00000000-0000-4000-8000-000000000099", EPOCH)

        val error = runCatching { resolver().resolve() }.exceptionOrNull()

        assertTrue(error is EndpointIdentityMismatchException)
        assertNull(preferences.activeServerUrl.first())
    }

    @Test
    fun `remote endpoint refuses cleartext even when reachable`() = runTest {
        preferences.setRemoteServerUrl("http://127.0.0.1:${server.localPort}")

        val error = runCatching { resolver().resolve() }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertNull(preferences.activeServerUrl.first())
    }

    private fun resolver() = EndpointResolver(
        ApplicationProvider.getApplicationContext(),
        preferences,
        tokens,
        Json { ignoreUnknownKeys = true },
        selectedTransport
    )

    private companion object {
        const val SERVER_ID = "00000000-0000-4000-8000-000000000001"
        const val EPOCH = "00000000-0000-4000-8000-000000000002"
        const val IDENTITY = """{"server_instance_id":"$SERVER_ID","sync_epoch":"$EPOCH","protocol_version":3,"capabilities":["sync_v2","timer_commands"],"server_time":"2026-08-14T00:00:00Z"}"""
    }
}
