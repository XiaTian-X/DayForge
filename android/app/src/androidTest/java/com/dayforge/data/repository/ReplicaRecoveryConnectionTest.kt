package com.dayforge.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.api.interceptor.*
import com.dayforge.data.appearance.AccountIconHttp
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.*
import com.dayforge.domain.service.AccountSessionCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real TCP/file DS in the isolated phone testbed. Synthetic login witnesses are NOT SQL
 * recovery/archive proofs; the composition suite separately exercises those real participants.
 */
@RunWith(AndroidJUnit4::class)
class ReplicaRecoveryConnectionTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var data: DataStore<Preferences>
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var client: OkHttpClient
    private lateinit var networks: NetworkMonitor
    private val selected = SelectedNetworkTransport()
    private val sessions = AccountSessionCoordinator()
    private val peers = mutableListOf<MaterialSocketServer>()
    private val json = Json { encodeDefaults = true }
    private fun id(n: Int) = "a9800000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun identity(epoch: String = id(5), server: String = id(2), protocol: Int = 5) =
        json.encodeToString(ServerIdentityResponse(server, epoch, protocol, listOf("sync.read"), "2026-10-13T00:00:00Z"))
    private fun login(owner: String = id(1)) = json.encodeToString(TokenResponse("new-synthetic-access", "new-synthetic-refresh", "bearer", owner, "member", false))
    private fun reply(text: String, status: Int = 200) = MaterialSocketServer.Reply(text.toByteArray(), status)
    private fun peer(block: (MaterialSocketServer.Input, Socket) -> MaterialSocketServer.Reply?): MaterialSocketServer =
        MaterialSocketServer(block).also { peers += it }
    private fun ordinaryPeer() = peer { input, _ -> when (input.path) {
        MaterialHttpRoute.IDENTITY_PATH -> reply(identity())
        "/api/v1/auth/login" -> reply(login())
        else -> error("Unexpected ordinary request ${input.path}")
    } }
    private fun connection() = ReplicaRecoveryConnection(EndpointResolver(networks, preferences, tokens, json, selected),
        tokens, sessions, client, NextSyncHttp(client, tokens, peers.first().origin), AccountIconHttp(client, tokens, peers.first().origin))
    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { if (error is CancellationException) throw error; return error }
        throw AssertionError("Expected rejection")
    }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        directory = Files.createTempDirectory(app.filesDir.toPath(), "replica-login-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") })
        tokens = TokenManager(data); preferences = PreferencesManager(data)
        tokens.saveLoginSession("old-synthetic-access", "old-synthetic-refresh", "member", id(1), false)
        tokens.getOrCreateInstallationId(); tokens.saveServerIdentity(id(2), id(3))
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "facts.append"), false, 2)
        tokens.saveSyncCursor(20)
        networks = mockk(); coEvery { networks.localNetworks() } returns emptyList()
        client = OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(preferences)).addInterceptor(AuthInterceptor(tokens)).build()
    }
    @After fun cleanup() = runBlocking<Unit> {
        var failure: Throwable? = null
        suspend fun finish(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        }
        peers.forEach { finish { it.close() } }
        finish { if (::client.isInitialized) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }
        finish { if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin() }
        finish { if (::directory.isInitialized) assertTrue(directory.deleteRecursively()) }
        failure?.let { throw it }
    }
    private fun withoutCredentials(values: Preferences) = values.asMap().filterKeys { it.name !in
        setOf("access_token", "refresh_token", "auth_session_generation", "user_email", "user_public_id", "is_admin") }

    @Test fun explicitSameOwnerLoginPreservesAllReplicaBytesAndNeverUsesOldBearerOrGlobalRoute() = runBlocking<Unit> {
        val wrong = peer { _, _ -> error("Captured route must not use ordinary selected URL") }
        val server = ordinaryPeer(); preferences.setServerUrl(server.origin.toString()); preferences.setActiveServerUrl(wrong.origin.toString())
        val old = data.data.first(); val oldSession = tokens.authenticationSnapshot()!!.session
        val authenticated = connection().reauthenticate("member", "synthetic-password")
        assertEquals(id(1), authenticated.userId); assertNotEquals(oldSession.generation, authenticated.generation)
        assertEquals(withoutCredentials(old), withoutCredentials(data.data.first()))
        assertEquals("new-synthetic-access", tokens.authenticationSnapshot()!!.accessToken)
        assertEquals(wrong.origin.toString(), preferences.activeServerUrl.first()); assertNull(selected.currentNetwork())
        assertTrue(wrong.requests.isEmpty()); assertEquals(1, server.requests.count { it.path == "/api/v1/auth/login" })
        assertTrue(server.requests.all { "authorization" !in it.headers && "cookie" !in it.headers })
        assertEquals(LoginRequest("member", "synthetic-password"), Json.decodeFromString<LoginRequest>(server.requests.single { it.method == "POST" }.body.toString(Charsets.UTF_8)))
    }

    @Test fun frozenSameOwnerLoginKeepsPointerOldReplicaAndStageAndAllowsNoOrdinaryWriter() = runBlocking<Unit> {
        val server = ordinaryPeer(); preferences.setServerUrl(server.origin.toString())
        val stage = tokens.replicaStageFixture().copy(newEpoch = id(5))
        tokens.persistReplicaStage(null, stage); val old = data.data.first()
        connection().reauthenticate("member", "synthetic-password")
        assertEquals(stage, tokens.activeReplicaStage()); assertEquals(withoutCredentials(old), withoutCredentials(data.data.first()))
        assertEquals(id(3), tokens.syncEpoch.first()); assertTrue(tokens.replicaSwitchPending())
        assertEquals(ReplicaSwitchGuard.PENDING, failure { tokens.saveSyncCursor(99) }.message)
    }

    @Test fun anotherAccountLoginResponseCannotAdoptClearOrReplaceAnyLocalValue() = runBlocking<Unit> {
        val server = peer { input, _ -> if (input.method == "POST") reply(login(id(9))) else reply(identity()) }
        preferences.setServerUrl(server.origin.toString()); val old = data.data.first()
        assertEquals("SYNC_RECOVERY_LOGIN_ACCOUNT_MISMATCH", failure { connection().reauthenticate("another", "synthetic-password") }.message)
        assertEquals(old, data.data.first()); assertEquals(1, server.requests.count { it.method == "POST" })
    }

    @Test fun changedEpochDuringLoginCannotPublishAnyCredentialOrReplicaValue() = runBlocking<Unit> {
        val replied = AtomicBoolean(false)
        val server = peer { input, _ -> if (input.method == "POST") { replied.set(true); reply(login()) }
            else reply(identity(if (replied.get()) id(6) else id(5))) }
        preferences.setServerUrl(server.origin.toString()); val old = data.data.first()
        assertEquals("SYNC_RECOVERY_ENDPOINT_CHANGED", failure { connection().reauthenticate("member", "synthetic-password") }.message)
        assertEquals(old, data.data.first())
    }

    @Test fun lateLoginAfterLocalPreferenceOrAuthenticationChangesCannotOverwriteEither() = runBlocking<Unit> {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val server = peer { input, _ -> if (input.method == "POST") {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); reply(login())
        } else reply(identity()) }
        preferences.setServerUrl(server.origin.toString())
        val pending = async(Dispatchers.IO) { failure { connection().reauthenticate("member", "synthetic-password") } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            tokens.saveLoginSession("later-synthetic-access", "later-refresh", "member", id(1), true)
            val actual = data.data.first(); release.countDown()
            assertEquals("SYNC_RECOVERY_LOGIN_CHANGED", pending.await().message); assertEquals(actual, data.data.first())
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun cancellationJoinsOwnedLoginAndNeverSavesReturnedTokens() = runBlocking<Unit> {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val server = peer { input, _ -> if (input.method == "POST") {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); reply(login()).copy(allowClientClose = true)
        } else reply(identity()) }
        preferences.setServerUrl(server.origin.toString()); val old = data.data.first()
        val pending = async(Dispatchers.IO) { connection().reauthenticate("member", "synthetic-password") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); pending.cancel(); release.countDown(); pending.cancelAndJoin()
            assertEquals(old, data.data.first()); assertEquals(1, server.requests.count { it.method == "POST" })
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun mismatchedInstanceOrUnsupportedProtocolNeverReceivesPassword() = runBlocking<Unit> {
        val wrong = peer { _, _ -> reply(identity(server = id(7))) }
        val oldProtocol = peer { _, _ -> reply(identity(protocol = 4)) }
        preferences.setServerUrl(wrong.origin.toString()); val old = data.data.first()
        assertTrue(failure { connection().reauthenticate("member", "synthetic-password") } is EndpointIdentityMismatchException)
        assertEquals(old, data.data.first()); assertTrue(wrong.requests.all { it.method == "GET" })
        preferences.setServerUrl(oldProtocol.origin.toString())
        assertTrue(failure { connection().reauthenticate("member", "synthetic-password") } is EndpointIdentityMismatchException)
        assertTrue(oldProtocol.requests.all { it.method == "GET" })
    }

    @Test fun unchangedEpochAndCleartextRemoteCannotTurnLoginIntoAutomaticRecovery() = runBlocking<Unit> {
        val same = peer { _, _ -> reply(identity(epoch = id(3))) }
        preferences.setServerUrl(same.origin.toString()); val old = data.data.first()
        assertEquals("SYNC_RECOVERY_NOT_REQUIRED", failure { connection().reauthenticate("member", "synthetic-password") }.message)
        assertEquals(old, data.data.first()); assertTrue(same.requests.all { it.method == "GET" })
        preferences.setServerUrl(null); preferences.setRemoteServerUrl(same.origin.toString()); val count = same.requests.size
        assertTrue(failure { connection().reauthenticate("member", "synthetic-password") } is java.io.IOException)
        assertEquals(count, same.requests.size)
    }

    @Test fun invalidOrUnknownDurableAccountOrOrphanPointerStopsBeforeNetworkAndIconInitialization() = runBlocking<Unit> {
        val server = ordinaryPeer(); preferences.setServerUrl(server.origin.toString())
        val initial = data.data.first()
        data.edit { it[stringPreferencesKey("sync_account_id")] = id(8) }
        val changed = data.data.first(); failure { connection().reauthenticate("member", "synthetic-password") }
        assertEquals(changed, data.data.first()); assertTrue(server.requests.isEmpty())
        data.updateData { initial }; data.edit { it[ReplicaSwitchGuard.POINTER] = id(10) }
        val orphan = data.data.first(); failure { connection().reauthenticate("member", "synthetic-password") }
        assertEquals(orphan, data.data.first()); assertTrue(server.requests.isEmpty())
        data.updateData { initial }; tokens.clearAuthenticationTokens()
        val controller = com.dayforge.domain.service.AccountIconController({ throw AssertionError("Must not initialize") }, tokens)
        assertEquals("ICON_ACCESS_DENIED", failure { controller.withReplicaRuntime { Unit } }.message)
    }

    @Test fun malformedOversizeOrRedirectedLoginIsNotSavedRefreshedOrFollowed() = runBlocking<Unit> {
        val destination = peer { _, _ -> error("Redirect must not be followed") }
        for (bad in listOf(reply(login().replace("\"is_admin\":false", "\"is_admin\":\"false\"")),
            reply(login()).copy(length = 65_537, allowClientClose = true),
            reply("", 302).copy(headers = mapOf("Location" to destination.origin.toString())),
            reply("", 401))) {
            val server = peer { input, _ -> if (input.method == "POST") bad else reply(identity()) }
            preferences.setServerUrl(server.origin.toString()); val old = data.data.first()
            failure { connection().reauthenticate("member", "synthetic-password") }
            assertEquals(old, data.data.first()); assertEquals(1, server.requests.count { it.method == "POST" })
            assertFalse(server.requests.any { it.path.endsWith("refresh") }); assertTrue(destination.requests.isEmpty())
        }
    }

    @Test fun actualPreviewUsesPinnedEndpointDespiteOrdinaryActiveUrlAndExpiredConnectionCannotSend() = runBlocking<Unit> {
        val wrong = peer { _, _ -> error("No request may inherit active URL") }
        val installation = tokens.registeredInstallationId()!!
        val response = ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(5),
            RecoveryDeviceProof(id(4), installation, "android", "interactive", null, null, false, false, 2,
                listOf("sync.read", "facts.append"), "2026-10-13T00:00:00Z"),
            RoundSyncBootstrapResponse(emptyList(), 20, "2026-10-13T00:00:00Z", emptyList(), 1, emptyList(), emptyList()), null)
        val server = peer { input, _ -> if (input.path == MaterialHttpRoute.IDENTITY_PATH) reply(identity())
            else reply(json.encodeToString(response)) }
        preferences.setServerUrl(server.origin.toString()); preferences.setActiveServerUrl(wrong.origin.toString())
        val original = tokens.localSyncAccess()!!; val before = data.data.first()
        lateinit var escaped: NextSyncHttp
        connection().use { connected ->
            escaped = connected.businessHttp
            assertEquals(response, escaped.recoveryPreview(original, connected.epoch, installation))
        }
        val calls = server.requests.size
        assertEquals("SYNC_RECOVERY_CONNECTION_CLOSED", failure { escaped.recoveryPreview(original, id(5), installation) }.message)
        assertEquals(calls, server.requests.size); assertTrue(wrong.requests.isEmpty()); assertEquals(before, data.data.first())
        assertEquals("Bearer old-synthetic-access", server.requests.single { it.path.endsWith("recovery-preview") }.headers["authorization"])
        assertTrue(server.requests.filter { it.path.endsWith("identity") }.all { "authorization" !in it.headers })
    }

    @Test fun missingOldCredentialsCanReauthenticateButFailedCipherWriteRollsBackTheWholeActorUpdate() = runBlocking<Unit> {
        val server = ordinaryPeer(); preferences.setServerUrl(server.origin.toString())
        val stage = tokens.replicaStageFixture().copy(newEpoch = id(5)); tokens.persistReplicaStage(null, stage)
        tokens.clearAuthenticationTokens(); val missing = data.data.first()
        connection().reauthenticate("member", "synthetic-password")
        assertEquals(stage, tokens.activeReplicaStage()); assertEquals(withoutCredentials(missing), withoutCredentials(data.data.first()))
        val failing = TokenManager(data, object : TokenCipher {
            override fun encrypt(value: String): String {
                check(value != "failing-refresh") { "synthetic cipher failure" }; return value
            }
            override fun decrypt(value: String) = value
        })
        val witness = failing.replicaLoginWitness(); val old = data.data.first()
        assertEquals("synthetic cipher failure", failure {
            failing.saveReplicaLogin(witness, "replacement-access", "failing-refresh", "member", id(1), false)
        }.message)
        assertEquals(old, data.data.first()); assertEquals(stage, tokens.activeReplicaStage())
    }

    @Test fun failedWifiFallsThroughToVerifiedBoundLanWithoutChangingOrdinaryTransport() = runBlocking<Unit> {
        val server = ordinaryPeer(); preferences.setServerUrl("http://nas.dayforge.invalid:${server.origin.port}/")
        val broken = mockk<android.net.Network>(); every { broken.socketFactory } throws java.io.IOException("Synthetic lost Wi-Fi")
        val dns = AtomicInteger(); val lan = mockk<android.net.Network>()
        every { lan.socketFactory } returns javax.net.SocketFactory.getDefault()
        every { lan.getAllByName(any()) } answers {
            dns.incrementAndGet(); arrayOf(java.net.InetAddress.getByName("127.0.0.1"))
        }
        val normal = mockk<android.net.Network>(); selected.select(normal)
        coEvery { networks.localNetworks() } returns listOf(broken, lan)
        val old = data.data.first()
        connection().reauthenticate("member", "synthetic-password")
        assertTrue(dns.get() > 0); assertSame(normal, selected.currentNetwork())
        assertEquals(withoutCredentials(old), withoutCredentials(data.data.first()))
        assertEquals(1, server.requests.count { it.method == "POST" })
        assertTrue(server.requests.all { "authorization" !in it.headers && "cookie" !in it.headers })
    }

    @Test fun missingObservedExternalNetworkCannotFollowALaterDefaultOrClearOrdinarySelection() = runBlocking<Unit> {
        val server = ordinaryPeer(); preferences.setServerUrl("http://nas.dayforge.invalid:${server.origin.port}/")
        every { networks.state } returns MutableStateFlow(NetworkMonitor.Snapshot(monitoring = false))
        val normal = mockk<android.net.Network>(); selected.select(normal); val old = data.data.first()
        assertTrue(failure { connection().reauthenticate("member", "synthetic-password") } is java.io.IOException)
        assertTrue(server.requests.isEmpty()); assertSame(normal, selected.currentNetwork()); assertEquals(old, data.data.first())
    }

    @Test fun witnessScopeFieldsCannotOverrideTheActualPersistedAccountInTheActor() = runBlocking<Unit> {
        val actual = tokens.replicaLoginWitness(); val before = data.data.first()
        val forged = ReplicaLoginWitness(tokens, actual.values, id(8), actual.serverInstanceId, actual.currentEpoch, actual.destinationEpoch)
        assertEquals("SYNC_RECOVERY_LOGIN_CHANGED", failure { tokens.requireReplicaLoginWitness(forged) }.message)
        assertEquals("SYNC_RECOVERY_LOGIN_CHANGED", failure {
            tokens.saveReplicaLogin(forged, "new-access", "new-refresh", "another", id(8), false)
        }.message)
        assertEquals(before, data.data.first()); assertEquals(id(1), tokens.authenticationSnapshot()!!.session.userId)
    }
}
