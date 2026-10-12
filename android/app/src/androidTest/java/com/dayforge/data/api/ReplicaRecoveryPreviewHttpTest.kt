package com.dayforge.data.api

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.authenticator.TokenAuthenticator
import com.dayforge.data.api.dto.*
import com.dayforge.data.api.interceptor.AuthInterceptor
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.ChallengeRoundRecord
import com.dayforge.domain.model.initialChallengeRoundHead
import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual sockets and file preferences in the physical testbed; no active scope replacement. */
@RunWith(AndroidJUnit4::class)
class ReplicaRecoveryPreviewHttpTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var data: DataStore<Preferences>
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var client: OkHttpClient
    private lateinit var installation: String
    private val servers = mutableListOf<MaterialSocketServer>()
    private fun id(n: Int) = "a9700000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val time = "2026-10-12T01:02:03Z"
    private val path = "/api/v2/sync/rounds/recovery-preview"
    private val wire = Json { encodeDefaults = true }
    private fun reply(value: String, status: Int = 200) = MaterialSocketServer.Reply(value.toByteArray(), status)
    private fun identity(epoch: String = id(5), sid: String = id(2), version: Int = 5) =
        wire.encodeToJsonElement(ServerIdentityResponse(sid, epoch, version, listOf("sync.read"), time)).toString()
    private fun emptySnapshot() = RoundSyncBootstrapResponse(emptyList(), 20, time, emptyList(), 1, emptyList(), emptyList())
    private fun proof() = RecoveryDeviceProof(id(4), installation, "android", "interactive", null, null,
        false, false, 1, listOf("sync.read", "facts.append", "timer.control", "devices.manage_self"), time)
    private fun candidate() = wire.encodeToJsonElement(ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(5), proof(), emptySnapshot(), null)).jsonObject
    private suspend fun capture() = requireNotNull(tokens.localSyncAccess())

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        directory = Files.createTempDirectory(app.filesDir.toPath(), "replica-preview-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") })
        tokens = TokenManager(data); preferences = PreferencesManager(data)
        tokens.saveLoginSession("old-synthetic-access", "synthetic-refresh", "member", id(1), false)
        installation = tokens.getOrCreateInstallationId()
        tokens.saveServerIdentity(id(2), id(3)); tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 7)
        client = OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(preferences)).addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, preferences, SelectedNetworkTransport())).build()
    }
    @After fun cleanup() = runBlocking<Unit> {
        var failure: Throwable? = null
        suspend fun finish(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        }
        servers.forEach { finish { it.close() } }
        finish { if (::client.isInitialized) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }
        finish { if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin() }
        finish { if (::directory.isInitialized) assertTrue(directory.deleteRecursively()) }
        failure?.let { throw it }
    }
    private suspend fun peer(respond: (MaterialSocketServer.Input, Socket) -> MaterialSocketServer.Reply?): Pair<NextSyncHttp, MaterialSocketServer> {
        val server = MaterialSocketServer(respond); servers.add(server)
        preferences.setServerUrl(server.origin.toString())
        return NextSyncHttp(client, tokens, server.origin) to server
    }
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { if (error is CancellationException) throw error; return error }
        throw AssertionError("Must reject")
    }

    @Test fun frozenReplicaStillAllowsOnlyFixedAuthenticatedNewEpochReadWithoutPublishingAuthority() = runBlocking<Unit> {
        val original = capture()
        val (http, server) = peer { input, _ -> if (input.path.endsWith("/identity")) reply(identity()) else {
            assertEquals(path, input.path); assertEquals("GET", input.method); reply(candidate().toString())
        } }
        data.edit { it[com.dayforge.data.local.ReplicaSwitchGuard.POINTER] = "missing-stage" }
        val before = data.data.first()
        assertEquals(id(5), http.recoveryPreview(original, id(5), installation).syncEpoch)
        assertEquals(2, server.requests.size); assertEquals(original, capture()); assertEquals(before, data.data.first())
        rejected { http.session(original) { fail("No ordinary Session") } }
        assertEquals(2, server.requests.size); assertEquals(before, data.data.first())
    }

    @Test fun reloginReadsNewScopeWithoutPublishingIdentityCapabilitiesCursorOrSuccess() = runBlocking<Unit> {
        val original = capture()
        tokens.saveLoginSession("fresh-synthetic-access", "fresh-synthetic-refresh", "member", id(1), false)
        val current = capture()
        assertEquals(original.session.syncEpoch, current.session.syncEpoch)
        assertNotEquals(original.session.authentication, current.session.authentication)
        val (http, server) = peer { input, _ ->
            if (input.path.endsWith("/identity")) {
                assertNull(input.headers["authorization"]); reply(identity())
            } else {
                assertEquals(path, input.path); assertEquals("GET", input.method)
                assertEquals("Bearer fresh-synthetic-access", input.headers["authorization"])
                assertEquals("5", input.headers["x-dayforge-protocol"])
                assertEquals(id(2), input.headers["x-dayforge-server-instance"])
                assertEquals(id(5), input.headers["x-dayforge-sync-epoch"])
                val url = ("http://localhost" + input.target).toHttpUrl()
                assertEquals(listOf(id(4)), url.queryParameterValues("device_id"))
                assertEquals(listOf(installation), url.queryParameterValues("installation_id"))
                assertEquals(listOf("1"), url.queryParameterValues("challenge_contract"))
                reply(candidate().toString())
            }
        }
        val before = data.data.first()
        val result = http.recoveryPreview(current, id(5), installation)
        assertEquals(id(5), result.syncEpoch); assertEquals(1, result.device.capabilityRevision)
        assertFalse(result.device.isPrimaryEditor) // Fresh rights may be lower after restore; never reuse old rights.
        assertEquals(before, data.data.first()); assertEquals(current, capture())
        assertEquals(2, server.requests.size)
        assertTrue(rejected { http.session(current) { error("Old work must not enter new epoch") } } is NextSyncHttpFailure)
        assertEquals(3, server.requests.size); assertEquals(current, capture())
    }

    @Test fun unauthorizedPreviewNeverRefreshesClearsOrRelabelsOldCredentials() = runBlocking<Unit> {
        val original = capture()
        val (http, server) = peer { input, _ -> if (input.path.endsWith("/identity")) reply(identity()) else reply("{}", 401) }
        val before = data.data.first()
        val error = rejected { http.recoveryPreview(original, id(5), installation) }
        assertTrue(error is NextSyncHttpFailure && error.status == 401)
        assertEquals(before, data.data.first()); assertEquals(original, capture())
        assertEquals(listOf("/api/v2/system/identity", path), server.requests.map { it.path })
    }

    @Test fun staleAuthenticationOrInstallationFailsBeforeAnyNetworkCall() = runBlocking<Unit> {
        val old = capture()
        val (http, server) = peer { _, _ -> error("Must not make a request") }
        assertTrue(rejected { http.recoveryPreview(old, id(5), id(90)) } is IOException)
        assertTrue(rejected { http.recoveryPreview(old, id(3), installation) } is IllegalArgumentException)
        tokens.saveLoginSession("fresh-synthetic-access", "fresh-synthetic-refresh", "member", id(1), false)
        assertTrue(rejected { http.recoveryPreview(old, id(5), installation) } is IOException)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun changedDiscoveryScopeOrUnknownProtocolNeverSendsPersonalGet() = runBlocking<Unit> {
        val original = capture(); var raw = identity()
        val (http, server) = peer { input, _ -> assertTrue(input.path.endsWith("/identity")); assertNull(input.headers["authorization"]); reply(raw) }
        for (value in listOf(identity(sid = id(80)), identity(epoch = id(81)), identity(version = 4), identity(version = 6))) {
            raw = value; assertTrue(rejected { http.recoveryPreview(original, id(5), installation) } is NextSyncHttpFailure)
        }
        assertEquals(4, server.requests.size); assertEquals(original, capture())
    }

    @Test fun mismatchedIncompleteOrCoercedCandidateNeverPublishesAnyPreferences() = runBlocking<Unit> {
        val original = capture(); var raw = candidate().toString()
        val base = candidate()
        val corrupt = listOf(
            JsonObject(base + ("account_id" to JsonPrimitive(id(80)))),
            JsonObject(base + ("server_instance_id" to JsonPrimitive(id(80)))),
            JsonObject(base + ("sync_epoch" to JsonPrimitive(id(80)))),
            JsonObject(base + ("protocol_version" to JsonPrimitive(true))),
            JsonObject(base + ("recovery_contract" to JsonPrimitive("1"))),
            JsonObject(base - "active_timer"),
            JsonObject(base + ("device" to JsonObject(base.getValue("device").jsonObject - "capability_revision"))),
            JsonObject(base + ("device" to JsonObject(base.getValue("device").jsonObject + ("device_id" to JsonPrimitive(id(80)))))),
            JsonObject(base + ("device" to JsonObject(base.getValue("device").jsonObject + ("installation_id" to JsonPrimitive(id(80)))))),
            JsonObject(base + ("device" to JsonObject(base.getValue("device").jsonObject + ("is_primary_editor" to JsonPrimitive(true)))))
        )
        val (http, server) = peer { input, _ -> if (input.path.endsWith("/identity")) reply(identity()) else reply(raw) }
        val before = data.data.first()
        for (value in corrupt) { raw = value.toString(); assertTrue(rejected { http.recoveryPreview(original, id(5), installation) } is NextSyncReplyInvalid) }
        raw = base.toString().dropLast(1) + ",\"account_id\":\"${id(1)}\"}"
        assertTrue(rejected { http.recoveryPreview(original, id(5), installation) } is IOException)
        assertEquals((corrupt.size + 1) * 2, server.requests.size)
        assertEquals(before, data.data.first()); assertEquals(original, capture())
    }

    @Test fun runningAndPausedCandidatesRequireCompleteBirthAndLiveActivity() = runBlocking<Unit> {
        val original = capture(); var raw = candidate().toString()
        val head = initialChallengeRoundHead(id(11))
        val activity = SyncV2Change(0, "plan_node", id(11), "upsert", 1, buildJsonObject {
            put("node_kind", "activity"); put("activity", buildJsonObject { put("completion_policy", "recurring") })
            put("appearance", buildJsonObject { put("icon", buildJsonObject { put("kind", "role"); put("role", "habit.water") }); put("accent_color", "#4488AA"); put("icon_tint", "theme") })
        }, time, null)
        val snapshot = RoundSyncBootstrapResponse(listOf(activity), 20, time, emptyList(), 1,
            listOf(ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))), listOf(ChallengeBirth("timer_session", id(10), head)))
        val (http, _) = peer { input, _ -> if (input.path.endsWith("/identity")) reply(identity()) else reply(raw) }
        for (state in listOf("running", "paused")) {
            val timer = TimerSessionResponse(id(10), id(11), state, id(4), 1, 1, 2, time, time,
                timezone = "Asia/Shanghai", isCountdown = false, targetSeconds = 60, maxDurationSeconds = 180, activeElapsedMs = 20_000)
            val good = ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(5), proof(), snapshot, timer)
            raw = wire.encodeToJsonElement(good).toString()
            assertEquals(state, http.recoveryPreview(original, id(5), installation).activeTimer!!.state)
            val encoded = wire.encodeToJsonElement(good).jsonObject
            raw = JsonObject(encoded + ("snapshot" to JsonObject(encoded.getValue("snapshot").jsonObject + ("births" to JsonArray(emptyList()))))).toString()
            assertTrue(rejected { http.recoveryPreview(original, id(5), installation) } is NextSyncReplyInvalid)
        }
        assertEquals(original, capture())
    }

    @Test fun lateLogoutRejectsCandidateAndCannotResurrectCredentials() = runBlocking<Unit> {
        val original = capture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val (http, _) = peer { input, _ -> if (input.path.endsWith("/identity")) reply(identity()) else {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); reply(candidate().toString())
        } }
        supervisorScope {
            val task = async(Dispatchers.IO) { rejected { http.recoveryPreview(original, id(5), installation) } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); tokens.clearAuthenticationTokens(); release.countDown()
                assertTrue(task.await() is IOException)
            } finally { release.countDown(); withContext(NonCancellable) { task.cancelAndJoin() } }
        }
        assertNull(tokens.authenticationSnapshot()); assertEquals(id(3), tokens.syncEpoch.first())
    }

    @Test fun cancellationClosesActualReadAndJoinsIoWithoutPreferenceWrites() = runBlocking<Unit> {
        val original = capture(); val entered = CountDownLatch(1); val closed = CountDownLatch(1)
        val (http, _) = peer { input, socket -> if (input.path.endsWith("/identity")) reply(identity()) else {
            socket.getOutputStream().apply { write("HTTP/1.1 200 Fixture\r\nContent-Type: application/json\r\nContent-Length: 1024\r\n\r\n".toByteArray()); flush() }
            entered.countDown(); assertEquals(-1, socket.getInputStream().read()); closed.countDown(); null
        } }
        val before = data.data.first()
        val task = async(Dispatchers.IO) { http.recoveryPreview(original, id(5), installation) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); withTimeout(5000) { task.cancelAndJoin() }
            assertTrue(closed.await(5, TimeUnit.SECONDS)); assertTrue(task.isCompleted)
        } finally { withContext(NonCancellable) { task.cancelAndJoin() } }
        assertEquals(before, data.data.first()); assertEquals(original, capture())
    }
}
