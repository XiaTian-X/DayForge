package com.dayforge.data.local

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real DataStore + platform Keystore. A change signal never replaces a fresh action guard. */
@RunWith(AndroidJUnit4::class)
class TokenManagerCoreAccessFlowTest {
    private val alias = "dayforge.test.core-flow.${UUID.randomUUID()}"
    private val calls = AtomicInteger()
    private val decryptions = Channel<Unit>(Channel.UNLIMITED)
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var tokens: TokenManager
    @Volatile private var cancelCipher = false

    @Before fun setup() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "core-flow-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val delegate = AndroidKeystoreTokenCipher(alias)
        tokens = TokenManager(store, object : TokenCipher {
            override fun encrypt(value: String) = delegate.encrypt(value)
            override fun decrypt(value: String): String? {
                assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                calls.incrementAndGet()
                try {
                    if (cancelCipher) throw CancellationException("test cipher cancelled")
                    return delegate.decrypt(value)
                } finally { decryptions.trySend(Unit) }
            }
        })
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "synthetic", id(1), false)
        tokens.saveServerIdentity(id(2), id(3))
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "facts.append", "structure.write"), true, 3)
        resetCounts()
    }

    @After fun cleanup() = runBlocking<Unit> {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        decryptions.close()
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    @Test fun directSignalKeepsSameAuthorityWithOneInsteadOfThreeDecryptions() = runBlocking<Unit> {
        val before = store.data.first()
        val baselineStart = SystemClock.elapsedRealtime()
        // Previous consumers used both values only as triggers, then read a third snapshot.
        val baseline = combine(tokens.factAccessChanges, tokens.iconAccessChanges) { _, _ ->
            tokens.localCoreWriteAccess()
        }.first()
        val baselineMs = SystemClock.elapsedRealtime() - baselineStart
        assertNotNull(baseline)
        assertEquals(3, calls.get())
        resetCounts()
        val start = SystemClock.elapsedRealtime()
        val result = withContext(Dispatchers.Main) { tokens.coreAccessChanges.first() }
        val elapsed = SystemClock.elapsedRealtime() - start
        assertEquals(baseline, result)
        assertEquals(1, calls.get())
        assertEquals(before, store.data.first())
        Log.i("CoreAccessFlowCost", "baseline_ms=$baselineMs baseline_decrypts=3 direct_ms=$elapsed direct_decrypts=1")
    }

    @Test fun lifecycleTracksOwnerGenerationReplicaDevicePermissionsAndFreeze() = runBlocking<Unit> {
        val events = Channel<LocalCoreWriteAccess?>(Channel.UNLIMITED)
        val job = launch { tokens.coreAccessChanges.collect { events.send(it) } }
        try {
            var current = requireNotNull(events.next())
            suspend fun change(edit: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
                store.edit { edit(it) }
                val next = requireNotNull(events.next())
                assertNotEquals(current, next)
                assertEquals(tokens.localCoreWriteAccess(), next)
                current = next
            }
            change { it[stringPreferencesKey("auth_session_generation")] = id(8) }
            change {
                it[stringPreferencesKey("user_public_id")] = id(9)
                it[stringPreferencesKey("sync_account_id")] = id(9)
            }
            change { it[stringPreferencesKey("sync_server_instance_id")] = id(10) }
            change { it[stringPreferencesKey("sync_epoch")] = id(11) }
            change { it[stringPreferencesKey("sync_device_id")] = id(12) }
            change { it[stringSetPreferencesKey("sync_device_capabilities")] = setOf("sync.read") }
            change { it[booleanPreferencesKey("sync_device_capabilities_known")] = false }
            store.edit { it[ReplicaSwitchGuard.POINTER] = "test-frozen" }
            assertNull(events.next())
            assertNull(tokens.localCoreWriteAccess())
            store.edit { it.remove(ReplicaSwitchGuard.POINTER) }
            assertEquals(current, events.next())
            store.edit { it[stringPreferencesKey("sync_account_id")] = id(13) }
            assertNull(events.next())
            assertNull(tokens.localCoreWriteAccess())
        } finally { job.cancelAndJoin(); events.close() }
    }

    @Test fun unrelatedEditsReauthenticateBeforeDedupAndDeletedWarmKeyRevokesSignal() = runBlocking<Unit> {
        val events = Channel<LocalCoreWriteAccess?>(Channel.UNLIMITED)
        val job = launch { tokens.coreAccessChanges.collect { events.send(it) } }
        try {
            assertNotNull(events.next())
            resetCounts()
            store.edit { it[booleanPreferencesKey("test_unrelated")] = true }
            withTimeout(5_000) { decryptions.receive() }
            // An unchanged value is not republished, but its ciphertext WAS checked again.
            assertEquals(1, calls.get())
            assertNull(withTimeoutOrNull(200) { events.receive(); "unexpected emission" })
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            store.edit { it[booleanPreferencesKey("test_unrelated")] = false }
            assertNull(events.next())
            assertNull(tokens.localCoreWriteAccess())
        } finally { job.cancelAndJoin(); events.close() }
    }

    @Test fun recollectionReauthenticatesAndCorruptAccessCannotReusePriorValue() = runBlocking<Unit> {
        val first = requireNotNull(tokens.coreAccessChanges.first())
        assertEquals(1, calls.get())
        assertEquals(first, tokens.coreAccessChanges.first())
        assertEquals(2, calls.get())
        store.edit { it[stringPreferencesKey("access_token")] = "enc:v1:invalid" }
        assertNull(tokens.coreAccessChanges.first())
        assertEquals(3, calls.get())
        assertNull(tokens.localCoreWriteAccess())
    }

    @Test fun refreshTokenCorruptionDoesNotChangeLocalAuthority() = runBlocking<Unit> {
        val expected = requireNotNull(tokens.coreAccessChanges.first())
        store.edit { it[stringPreferencesKey("refresh_token")] = "enc:v1:invalid" }
        resetCounts()
        assertEquals(expected, tokens.coreAccessChanges.first())
        assertEquals(1, calls.get())
        assertNull(requireNotNull(tokens.authenticationSnapshot()).refreshToken)
    }

    @Test fun stoppedCollectorHasNoBackgroundDecryptionsAndRestartReadsCurrentState() = runBlocking<Unit> {
        val events = Channel<LocalCoreWriteAccess?>(Channel.UNLIMITED)
        val job = launch { tokens.coreAccessChanges.collect { events.send(it) } }
        assertNotNull(events.next())
        job.cancelAndJoin()
        resetCounts()
        store.edit { it[stringPreferencesKey("sync_account_id")] = id(15) }
        assertEquals(0, calls.get())
        assertNull(tokens.coreAccessChanges.first())
        assertEquals(1, calls.get())
        events.close()
    }

    @Test fun cipherCancellationPropagatesInsteadOfBecomingAnAuthorityValue() = runBlocking<Unit> {
        cancelCipher = true
        try {
            tokens.coreAccessChanges.first()
            fail("Cipher cancellation must propagate")
        } catch (error: CancellationException) { assertEquals("test cipher cancelled", error.message) }
    }

    private fun resetCounts() {
        calls.set(0)
        while (decryptions.tryReceive().isSuccess) { /* drain completed test probes */ }
    }
    private suspend fun Channel<LocalCoreWriteAccess?>.next() = withTimeout(5_000) { receive() }
    private fun id(n: Int) = "aa160000-0000-4000-8000-${n.toString().padStart(12, '0')}"
}
