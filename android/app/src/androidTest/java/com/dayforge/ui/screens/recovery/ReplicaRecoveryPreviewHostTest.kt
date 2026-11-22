package com.dayforge.ui.screens.recovery

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.NextSyncHttpFailure
import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.api.dto.RefreshRequest
import com.dayforge.data.api.dto.ServerIdentityResponse
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.*
import com.dayforge.data.repository.*
import com.dayforge.domain.service.*
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual FastAPI-issued credentials/registration, Host/Facade and production ViewModel.
 * The separate loopback peer ONLY injects HTTP401: it is not a JWT-validation or successful
 * recovery server. No synthetic login token, preview certificate, state publisher or raw
 * authority edit. Original real MainActivity recovery675 remains a separate complete test. */
@HiltAndroidTest @RunWith(AndroidJUnit4::class)
class ReplicaRecoveryPreviewHostTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { fixture ->
        fixture.beforeCleanup = { ownerFailure?.let { throw it }; check(consumersClosed) { "RECOVERY_401_CONSUMERS_NOT_JOINED" } }
        fixture.beforeDatabaseDeletion = { check(consumersClosed && fixture.host.lifecycleEnded) }
    }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @Inject internal lateinit var tokens: TokenManager
    @Inject internal lateinit var sessions: AccountSessionCoordinator
    @Inject internal lateinit var settings: PreferencesManager
    @Inject internal lateinit var auth: AuthApi
    @Inject internal lateinit var business: BusinessSyncRepository
    @Inject internal lateinit var facade: DatabaseRecoveryFacade
    @Inject internal lateinit var themes: DeviceThemeController
    @Inject internal lateinit var data: DataStore<Preferences>
    @Inject internal lateinit var cipher: TokenCipher
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var connected = false
    private var injectionStarted = false
    private var consumersClosed = false
    private var ownerFailure: Throwable? = null
    private val methodMonitor = Any()
    private var closing = false
    private var methodJob: Job? = null
    private fun <T> journey(body: suspend CoroutineScope.() -> T): T = runBlocking {
        val job = requireNotNull(currentCoroutineContext()[Job])
        synchronized(methodMonitor) { check(!closing && methodJob == null); methodJob = job }
        body()
    }
    private val models = ViewModelStore()
    private var model: ReplicaRecoveryViewModel? = null
    private var server: MaterialSocketServer? = null
    private lateinit var original: LocalSyncAccess
    private lateinit var originalDatabase: HabitDatabase
    private lateinit var originalOwner: CountTrustRuntime
    private lateinit var before: JsonObject
    private val sequence = AtomicLong()
    private val events = CopyOnWriteArrayList<Pair<Long, String>>()
    private val received401 = CompletableDeferred<Unit>()
    private val returnOriginalFailure = CompletableDeferred<Unit>()
    private val actualFailure = AtomicReference<Exception?>()
    private val forwardedFailure = AtomicReference<Exception?>()
    private val observationFailure = AtomicReference<Exception?>()
    private val externalRevocations = AtomicInteger()
    private val previewRequests = AtomicInteger()
    private val forwardedInvalidations = AtomicInteger()
    private val peerFailures = CopyOnWriteArrayList<String>()
    private val cacheOwners = mutableListOf<AccountIconMemory.Cache>()
    private var hostRegistration: Any? = null
    // Both listener and its registration are strongly held for the whole test (Host is weak).
    private val hostListener = object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) = record("host_memory", "blocked=$blocked")
    }
    private fun record(kind: String, fields: String = "") {
        val id = sequence.incrementAndGet()
        val line = "seq=$id mono=${SystemClock.elapsedRealtimeNanos()} $kind $fields"
        events += id to kind
        android.util.Log.i("Replica401Trace", line) // Fixed labels, booleans/status/enum only.
    }
    private suspend fun image(): JsonObject = epochs.current { graph -> graph.database.withTransaction {
        NextReplicaStorageProof(graph.database).readInTransaction().contents
    } }
    private suspend fun assertNativeUnchanged() {
        val after = image()
        val caches = setOf("count_trust_root", "count_trust_nodes", "count_trust_routes")
        fun tables(raw: JsonObject) = raw.getValue("tables").jsonObject
        fun control(raw: JsonObject): JsonObject = tables(raw).getValue("count_trust_control").jsonArray.single().jsonObject
        fun integer(row: JsonObject, column: String): Long {
            val cell = row.getValue(column).jsonObject
            assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER, cell.getValue("sqlite_type").jsonPrimitive.int)
            return cell.getValue("value").jsonPrimitive.long
        }
        val old = control(before); val next = control(after)
        assertEquals(old.keys, next.keys)
        for (row in listOf(old, next)) {
            assertEquals(1L, integer(row, "id")); assertEquals(1L, integer(row, "__recovery_rowid"))
            assertTrue(integer(row, "generation") >= 0); assertTrue(integer(row, "dirty") in 0L..1L)
        }
        assertEquals(integer(old, "generation"), integer(next, "generation"))
        assertEquals(integer(old, "dirty"), integer(next, "dirty"))
        for (table in caches) assertEquals(tables(before).getValue(table), tables(after).getValue(table))
        assertEquals("FAILED_HTTP_MUST_NOT_CHANGE_ANY_NATIVE_CONTENT", before, after)
        fun retained(raw: JsonObject): JsonObject {
            val source = tables(raw)
            val stableControl = JsonObject(control(raw).filterKeys { it !in setOf("generation", "dirty") })
            // storageId/commandRootHash/id/rowid and every other cell remain exact and typed.
            val stableTables = source.filterKeys { it !in caches } +
                ("count_trust_control" to JsonArray(listOf(stableControl)))
            return JsonObject(raw + ("tables" to JsonObject(stableTables)))
        }
        assertTrue("PRIMARY_CONTROL_OR_NON_OPTIONAL_NATIVE_CHANGED", retained(before) == retained(after))
    }
    private val observedActions: ReplicaRecoveryActions by lazy { object : ReplicaRecoveryActions by facade {
        override fun observeInvalidation(cache: AccountIconMemory.Cache) {
            val observer = object : AccountIconMemory.Cache {
                override fun authenticationTransition(blocked: Boolean) {
                    forwardedInvalidations.incrementAndGet()
                    record("facade_invalidation", "blocked=$blocked external=${externalRevocations.get() != 0}")
                    cache.authenticationTransition(blocked) // Exactly the original notification.
                }
            }
            cacheOwners += observer
            facade.observeInvalidation(observer)
        }
        override fun entryChanges(): Flow<Unit> = facade.entryChanges().onEach { record("entry_hint") }
        override suspend fun control(): ReplicaRecoveryControl = facade.control().also {
            record("control", "same_auth=${it.authentication == original.session.authentication} stage_null=${it.stage == null} entry=${it.entryAvailable}")
        }
        override suspend fun preview(): ReplicaDifferencePreview {
            record("preview_call")
            try { return facade.preview() }
            catch (error: Exception) {
                actualFailure.set(error)
                record("preview_exception", "http=${(error as? NextSyncHttpFailure)?.status ?: 0} retired=${originalOwner.isRetired} old_open=${originalDatabase.isOpen}")
                received401.complete(Unit)
                if (error is NextSyncHttpFailure && error.status == 401) {
                    // Deterministic scheduling only, AFTER real Facade/HTTP/CONTROL cleanup.
                    // Never change the exception, a stamp, publication or callback Boolean.
                    try { withTimeout(15_000) { returnOriginalFailure.await() } }
                    catch (timeout: TimeoutCancellationException) {
                        observationFailure.compareAndSet(null, timeout)
                        error.addSuppressed(timeout)
                        record("diagnostic_hold_timeout")
                    }
                }
                forwardedFailure.set(error); record("preview_exception_forwarded")
                throw error
            }
        }
    } }

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        injectionStarted = true; hilt.inject(); epochs.connect(); connected = true
        // Use the production application DataStore/cipher: the same physical installation
        // must not become a new server primary between complete classes in one owned backend.
        assertTrue(cipher is AndroidKeystoreTokenCipher)
        sessions.exclusive { tokens.clearTokens() }
        epochs.clearAndAwaitReplacement() // No enclosing lease; real empty local initialization.
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("dayforgeJointPort")).toInt()
        require(port in 1024..65535)
        settings.setActiveServerUrl(null); settings.setRemoteServerUrl(null); settings.setServerUrl("http://127.0.0.1:$port/")
        val login = auth.login(LoginRequest("joint-count-command", "TestPassword123!"))
        val refreshed = auth.refreshToken(RefreshRequest(login.refreshToken)); assertTrue(login.userId == refreshed.userId)
        sessions.exclusive { tokens.saveLoginSession(refreshed.accessToken, refreshed.refreshToken, "joint-count-command", login.userId, false) }
        business.sync() // Real v5 registration, source proof and bootstrap; no hand-filled root.
        withTimeout(45_000) { themes.current() }
        original = requireNotNull(tokens.localSyncAccess())
        assertTrue(tokens.isCurrentSyncAuthority(original)); assertNotNull(tokens.registeredInstallationId())
        val values = data.data.first()
        assertTrue(requireNotNull(values[stringPreferencesKey("access_token")]).startsWith("enc:v1:"))
        originalDatabase = epochs.current { it.database }
        originalOwner = requireNotNull(CountTrustRuntime.forDatabase(originalDatabase))
        assertFalse(originalOwner.isRetired)
        before = image()
        val injectedEpoch = UUID.randomUUID().toString(); check(injectedEpoch != original.session.syncEpoch)
        val identity = Json.encodeToString(ServerIdentityResponse(requireNotNull(original.session.serverInstanceId), injectedEpoch, 5,
            listOf("sync.read"), Instant.now().toString()))
        server = MaterialSocketServer { input, _ ->
            when (input.path) {
                "/api/v2/system/identity" -> {
                    if (input.method != "GET") peerFailures += "identity_method"
                    record("fault_peer_identity")
                    MaterialSocketServer.Reply(identity.toByteArray())
                }
                "/api/v2/sync/rounds/recovery-preview" -> {
                    previewRequests.incrementAndGet()
                    if (input.method != "GET" || input.headers["authorization"]?.startsWith("Bearer ") != true)
                        peerFailures += "preview_method_or_auth_header"
                    record("fault_peer_401", "status=401 retired=${originalOwner.isRetired}")
                    MaterialSocketServer.Reply("{}".toByteArray(), status = 401)
                }
                else -> {
                    peerFailures += "unexpected_path"
                    MaterialSocketServer.Reply("{}".toByteArray(), status = 500)
                }
            }
        }
        settings.setServerUrl(requireNotNull(server).origin.toString()); settings.setActiveServerUrl(null)
        hostRegistration = epochs.host.observeMemory(hostListener)
        model = withContext(Dispatchers.Main) {
            ReplicaRecoveryViewModel(observedActions).also { models.put("recovery", it) }
        }
        idle()
        assertTrue(requireNotNull(model).state.value.entryAvailable)
        assertTrue(facade.control().authentication == original.session.authentication)
        record("ready", "same_auth=true rooms=${epochs.realRoomCreates}")
    }
    private suspend fun idle() = withTimeout(45_000) { requireNotNull(model).state.first { it.checked && !it.busy } }
    private suspend fun actual401WithoutSelfRetirement() {
        val created = epochs.realRoomCreates
        val invalidations = forwardedInvalidations.get()
        withContext(Dispatchers.Main) { requireNotNull(model).preview(); record("preview_admitted", "busy=${requireNotNull(model).state.value.busy}") }
        withTimeout(15_000) { received401.await() }
        assertTrue("ACTUAL_HTTP_401_REQUIRED", actualFailure.get() is NextSyncHttpFailure && (actualFailure.get() as NextSyncHttpFailure).status == 401)
        assertTrue(peerFailures.isEmpty()); assertEquals(1, previewRequests.get())
        // 681 retains the old actual close/rebuild counterexample. The corrected path must
        // never initiate that transition on a failed HTTP request, so no artificial wait.
        val fresh = facade.control()
        assertTrue(fresh.authentication == original.session.authentication && fresh.stage == null)
        assertFalse(originalOwner.isRetired)
        assertFalse(CountTrustRetirementReason.RECOVERY_PREPARATION in originalOwner.retirementReasons)
        assertTrue(originalDatabase.isOpen)
        assertFalse(originalOwner.isGraphCloseConfirmed(originalDatabase))
        assertTrue(epochs.current { it.database === originalDatabase })
        assertEquals(created, epochs.realRoomCreates)
        assertEquals(invalidations, forwardedInvalidations.get())
        assertTrue(tokens.localSyncAccess() == original && tokens.isCurrentSyncAuthority(original))
        assertEquals(0, externalRevocations.get())
        assertNativeUnchanged()
        record("failure_without_retirement_proved", "same_auth=true old_open=true")
    }

    @Test(timeout = 150_000) fun failedPreviewSelfRetirementPreservesActualAuthenticationFailure() = journey<Unit> {
        actual401WithoutSelfRetirement()
        val failure = actualFailure.get()
        returnOriginalFailure.complete(Unit)
        val result = idle()
        assertNull(observationFailure.get()); assertSame(failure, forwardedFailure.get())
        assertEventOrder()
        assertTrue("ORIGINAL_AUTHORITY_CHANGED", tokens.localSyncAccess() == original && tokens.isCurrentSyncAuthority(original))
        assertNativeUnchanged()
        assertTrue(result.preview == null && result.evidence == null && result.stage == null && !result.approved)
        assertTrue(peerFailures.isEmpty()); assertEquals(1, previewRequests.get())
        record("self_result", "error=${result.error} external=false")
        // Preserve the original 681 expected outcome; do not relabel its failure as a pass.
        assertFalse(originalOwner.isRetired); assertTrue(originalDatabase.isOpen)
        assertTrue(epochs.current { it.database === originalDatabase })
        assertEquals(ReplicaRecoveryError.AUTHENTICATION, result.error)
    }

    @Test(timeout = 150_000) fun externalLogoutStillRevokesTheSameInFlight401AndCannotPublishOldState() = journey<Unit> {
        actual401WithoutSelfRetirement()
        val stale = requireNotNull(model).state.value
        externalRevocations.incrementAndGet(); record("external_logout_begin")
        sessions.exclusive { tokens.clearTokens() } // Original guard; no raw DS edit or fake auth.
        assertNull(tokens.replicaUiAuthentication()); record("external_logout_completed", "auth_null=true")
        returnOriginalFailure.complete(Unit)
        val result = idle()
        assertNull(observationFailure.get()); assertSame(actualFailure.get(), forwardedFailure.get())
        assertEventOrder()
        assertEquals(ReplicaRecoveryError.CHANGED, result.error)
        assertFalse(requireNotNull(model).canRender(stale))
        assertTrue(result.preview == null && result.evidence == null && result.stage == null && !result.approved)
        assertNativeUnchanged()
        assertEquals(1, externalRevocations.get()); assertTrue(peerFailures.isEmpty()); assertEquals(1, previewRequests.get())
        record("external_result", "error=${result.error} auth_null=true")
    }

    private fun assertEventOrder() {
        fun order(name: String) = events.filter { it.second == name }.single().first
        assertTrue(order("preview_call") < order("fault_peer_401"))
        assertTrue(order("fault_peer_401") < order("preview_exception"))
        assertTrue(order("preview_exception") < order("failure_without_retirement_proved"))
        assertTrue(order("failure_without_retirement_proved") < order("preview_exception_forwarded"))
    }

    @After fun cleanup() = runBlocking<Unit> {
        val owned = synchronized(methodMonitor) { closing = true; methodJob }
        try {
            returnOriginalFailure.complete(Unit)
            withTimeout(5_000) { owned?.cancelAndJoin() }; check(owned?.isCompleted != false)
            val job = model?.viewModelScope?.coroutineContext?.get(Job)
            withContext(Dispatchers.Main) { models.clear() }
            withTimeout(5_000) { job?.join() }; check(job?.isCompleted != false)
            server?.close()
            if (::themes.isInitialized) withTimeout(5_000) { themes.close() }
            if (connected) { shutdownTestHost(epochs.host); check(epochs.host.lifecycleEnded) }
            else check(!injectionStarted && DatabaseRuntimeHosts.snapshot().all { it.lifecycleEnded }) { "RECOVERY_401_INJECTION_OWNER_UNKNOWN" }
            if (::settings.isInitialized) {
                settings.setServerUrl(null); settings.setActiveServerUrl(null); settings.setRemoteServerUrl(null)
            }
            // Application DataStoreProvider is shared, not a test-created actor/file/key.
            // Do not cancel/delete it; EpochFixture performs original guarded token clearing.
            consumersClosed = true
            record("owners_closed", "known=true")
            // Retain these weak-registration owners through the final known-close witness.
            check(model == null || hostRegistration != null); cacheOwners.clear(); hostRegistration = null
        } catch (failure: Throwable) {
            ownerFailure = failure; PhysicalDatabaseRule.preserveProcessCleanupFailure(failure); throw failure
        }
    }
}
