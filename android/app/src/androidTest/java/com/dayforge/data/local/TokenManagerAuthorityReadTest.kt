package com.dayforge.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real DataStore actor, isolated synthetic credentials; count cipher calls, never cache results. */
@RunWith(AndroidJUnit4::class)
class TokenManagerAuthorityReadTest {
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var store: CountingStore
    private lateinit var cipher: CountingCipher
    private lateinit var manager: TokenManager
    private lateinit var expected: LocalSyncAccess
    private lateinit var icon: LocalIconAccess

    @Before fun setup() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "authority-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = CountingStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        cipher = CountingCipher()
        manager = TokenManager(store, cipher)
        manager.saveLoginSession("access", "refresh", "synthetic", id(1), false)
        manager.saveServerIdentity(id(2), id(3))
        manager.saveDeviceRegistration(id(4), setOf("sync.read", "facts.append", "structure.write"), true, 3)
        expected = requireNotNull(manager.localSyncAccess())
        icon = requireNotNull(manager.localIconAccess())
        resetCounts()
    }

    @After fun cleanup() = runBlocking<Unit> {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        file.delete()
    }

    @Test fun registeredAuthorityReadsOneFreshSnapshotAndDecryptsAccessOnly() = runBlocking {
        assertTrue(manager.isCurrentSyncAuthority(expected))
        assertEquals(1, store.reads.get())
        assertEquals(listOf("cipher:access"), cipher.calls.toList())
    }

    @Test fun localGuardsDecryptAccessOnlyButNeverReusePreviousAuthorization() = runBlocking {
        val calls: List<suspend () -> Any?> = listOf(
            { manager.localFactAccess() }, { manager.localCoreWriteAccess() },
            { manager.replicaObservationCoreAccess() }, { manager.localIconAccess() }
        )
        for (call in calls) {
            cipher.unreadable = setOf("cipher:refresh")
            resetCounts()
            assertNotNull(call())
            assertEquals(1, store.reads.get())
            assertEquals(listOf("cipher:access"), cipher.calls.toList())
            cipher.unreadable = setOf("cipher:access")
            assertNull(call())
            assertEquals(2, store.reads.get())
            assertEquals(listOf("cipher:access", "cipher:access"), cipher.calls.toList())
        }
    }

    @Test fun identicalCiphertextMustBeDecryptedAgainAndLaterFailureDeniesAccess() = runBlocking {
        val before = store.data.first()
        resetCounts()
        assertTrue(manager.isCurrentSyncAuthority(expected))
        cipher.unreadable = setOf("cipher:access")
        assertFalse(manager.isCurrentSyncAuthority(expected))
        assertEquals(2, store.reads.get())
        assertEquals(listOf("cipher:access", "cipher:access"), cipher.calls.toList())
        assertEquals(before, store.data.first())
    }

    @Test fun missingOrUnreadableAccessNeverGrantsProofOrHttpCredentials() = runBlocking {
        cipher.unreadable = setOf("cipher:access")
        assertFalse(manager.isCurrentSyncAuthority(expected))
        assertNull(manager.syncAuthenticationSnapshot(expected))
        assertNull(manager.iconAuthenticationSnapshot(icon))
        store.edit { it.remove(stringPreferencesKey("access_token")) }
        cipher.unreadable = emptySet()
        resetCounts()
        assertFalse(manager.isCurrentSyncAuthority(expected))
        assertNull(manager.syncAuthenticationSnapshot(expected))
        assertNull(manager.iconAuthenticationSnapshot(icon))
        assertTrue(cipher.calls.isEmpty())
    }

    @Test fun unreadableRefreshIsUnusedForProofAndStaysNullForHttp() = runBlocking {
        cipher.unreadable = setOf("cipher:refresh")
        assertTrue(manager.isCurrentSyncAuthority(expected))
        assertEquals(listOf("cipher:access"), cipher.calls.toList())
        resetCounts()
        val sync = requireNotNull(manager.syncAuthenticationSnapshot(expected))
        assertEquals("access", sync.accessToken)
        assertNull(sync.refreshToken)
        assertEquals(1, store.reads.get())
        assertEquals(listOf("cipher:access", "cipher:refresh"), cipher.calls.toList())
        resetCounts()
        val material = requireNotNull(manager.iconAuthenticationSnapshot(icon))
        assertEquals("access", material.accessToken)
        assertNull(material.refreshToken)
        assertEquals(1, store.reads.get())
        assertEquals(listOf("cipher:access", "cipher:refresh"), cipher.calls.toList())
    }

    @Test fun httpCredentialsAuthenticateOnceWithinTheirSingleSnapshot() = runBlocking {
        val sync = requireNotNull(manager.syncAuthenticationSnapshot(expected))
        assertEquals(expected.session.authentication, sync.session)
        assertEquals("access", sync.accessToken)
        assertEquals("refresh", sync.refreshToken)
        assertEquals(1, store.reads.get())
        assertEquals(listOf("cipher:access", "cipher:refresh"), cipher.calls.toList())
        resetCounts()
        val material = requireNotNull(manager.iconAuthenticationSnapshot(icon))
        assertEquals(icon.session.authentication, material.session)
        assertEquals("access", material.accessToken)
        assertEquals("refresh", material.refreshToken)
        assertEquals(1, store.reads.get())
        assertEquals(listOf("cipher:access", "cipher:refresh"), cipher.calls.toList())
    }

    @Test fun everyRegisteredScopeFieldIsComparedAgainstTheFreshSnapshot() = runBlocking {
        val original = store.data.first()
        val core = requireNotNull(manager.localCoreWriteAccess())
        val changes = listOf<Pair<String, (MutablePreferences) -> Unit>>(
            "account" to { it[stringPreferencesKey("user_public_id")] = id(9) },
            "generation" to { it[stringPreferencesKey("auth_session_generation")] = id(9) },
            "owner" to { it[stringPreferencesKey("sync_account_id")] = id(9) },
            "server" to { it[stringPreferencesKey("sync_server_instance_id")] = id(9) },
            "epoch" to { it[stringPreferencesKey("sync_epoch")] = id(9) },
            "device" to { it[stringPreferencesKey("sync_device_id")] = id(9) },
            "revision" to { it[stringPreferencesKey("sync_device_capability_revision")] = "4" },
            "capabilities" to { it[stringSetPreferencesKey("sync_device_capabilities")] = setOf("sync.read") },
            "known" to { it[booleanPreferencesKey("sync_device_capabilities_known")] = false },
            "capability owner" to { it[stringPreferencesKey("sync_device_capabilities_device")] = id(9) },
            "read capability" to { it[stringSetPreferencesKey("sync_device_capabilities")] = setOf("facts.append") },
            "missing epoch" to { it.remove(stringPreferencesKey("sync_epoch")); Unit },
            "malformed server" to { it[stringPreferencesKey("sync_server_instance_id")] = "invalid" },
            "invalid revision" to { it[stringPreferencesKey("sync_device_capability_revision")] = "0" }
        )
        for ((name, change) in changes) {
            store.updateData { original }
            store.edit { change(it) }
            resetCounts()
            assertFalse(name, manager.isCurrentSyncAuthority(expected))
            assertEquals(name, 1, store.reads.get())
            assertNull(name, manager.syncAuthenticationSnapshot(expected))
            assertFalse(name, manager.isCurrentDisplayReadAuthority(core, expected))
        }
        store.updateData { original }
        assertTrue(manager.isCurrentSyncAuthority(expected))
    }

    @Test fun registrationCredentialsRemainSeparateFromRegisteredRecoveryAuthority() = runBlocking {
        val registration = LocalSyncAccess(expected.session)
        assertFalse(manager.isCurrentSyncAuthority(registration))
        resetCounts()
        assertEquals(expected.session.authentication,
            requireNotNull(manager.syncAuthenticationSnapshot(registration)).session)
        assertEquals(1, store.reads.get())
        assertEquals(listOf("cipher:access", "cipher:refresh"), cipher.calls.toList())
    }

    @Test fun cancellationFromCipherIsNotConvertedIntoSuccessOrAuthenticationAbsence() = runBlocking {
        cipher.cancel = true
        val calls: List<suspend () -> Any?> = listOf(
            { manager.isCurrentSyncAuthority(expected) },
            { manager.syncAuthenticationSnapshot(expected) },
            { manager.iconAuthenticationSnapshot(icon) }
        )
        for (call in calls) {
            try { call(); fail("Cipher cancellation must propagate") }
            catch (error: CancellationException) { assertEquals("synthetic cancellation", error.message) }
        }
    }

    @Test fun recoveryProofMayReadFrozenScopeButPreferenceActorCannotWriteThroughFence() = runBlocking {
        store.edit { it[ReplicaSwitchGuard.POINTER] = "synthetic-frozen" }
        assertTrue(manager.isCurrentSyncAuthority(expected))
        val before = store.data.first()
        try {
            store.edit {
                manager.requireHabitPreferenceSession(it, expected.session)
                it[booleanPreferencesKey("synthetic_preference")] = true
            }
            fail("Frozen preference actor must reject")
        } catch (error: IllegalStateException) { assertEquals(ReplicaSwitchGuard.PENDING, error.message) }
        assertEquals(before, store.data.first())
    }

    @Test fun preferenceActorRequiresCurrentAuthenticationWithoutASecondSnapshot() = runBlocking {
        store.edit { manager.requireHabitPreferenceSession(it, expected.session) }
        val before = store.data.first()
        resetCounts()
        store.edit { manager.requireHabitPreferenceSession(it, expected.session) }
        assertEquals(0, store.reads.get())
        assertEquals(before, store.data.first())
        manager.saveLoginSession("access", "refresh", "same owner", id(1), false)
        val changed = store.data.first()
        try {
            store.edit {
                manager.requireHabitPreferenceSession(it, expected.session)
                it[booleanPreferencesKey("synthetic_preference")] = true
            }
            fail("Same-owner relogin must invalidate old preference claim")
        } catch (error: IllegalStateException) { assertEquals("HABIT_PREFERENCE_SESSION_CHANGED", error.message) }
        assertEquals(changed, store.data.first())
        cipher.unreadable = setOf("cipher:access")
        val current = LocalDataSession(requireNotNull(manager.replicaUiAuthentication()), id(2), id(3))
        try {
            store.edit { manager.requireHabitPreferenceSession(it, current) }
            fail("Metadata cannot grant preference access when current ciphertext is unreadable")
        } catch (error: IllegalStateException) { assertEquals("HABIT_PREFERENCE_SESSION_CHANGED", error.message) }
    }

    @Test fun refreshCasStillAuthenticatesSameSnapshotAndRejectsChangedScope() = runBlocking {
        val original = requireNotNull(manager.syncAuthenticationSnapshot(expected))
        resetCounts()
        assertTrue(manager.saveRefreshedSyncTokens(original, expected, "next-access", "next-refresh", "member", id(1), false))
        assertEquals(listOf("cipher:access", "cipher:refresh"), cipher.calls.toList())
        val current = requireNotNull(manager.syncAuthenticationSnapshot(expected))
        manager.saveServerIdentity(id(2), id(9))
        assertFalse(manager.saveRefreshedSyncTokens(current, expected, "late-access", "late-refresh", "member", id(1), false))
        manager.clearRejectedSyncRefresh(current, expected)
        assertEquals("next-access", requireNotNull(manager.authenticationSnapshot()).accessToken)
    }

    private fun resetCounts() { cipher.calls.clear(); store.reads.set(0) }

    @Test fun displayFenceChecksCoreAndRegisteredAuthorityInOneFreshSnapshot() = runBlocking {
        val core = requireNotNull(manager.localCoreWriteAccess())
        resetCounts()
        assertTrue(manager.isCurrentDisplayReadAuthority(core, expected))
        assertEquals(1, store.reads.get())
        assertEquals(listOf("cipher:access"), cipher.calls.toList())
        cipher.unreadable = setOf("cipher:access")
        assertFalse(manager.isCurrentDisplayReadAuthority(core, expected))
        assertEquals(2, store.reads.get())
        assertEquals(listOf("cipher:access", "cipher:access"), cipher.calls.toList())
        cipher.unreadable = emptySet()
        store.edit { it[ReplicaSwitchGuard.POINTER] = "synthetic-frozen" }
        resetCounts()
        assertFalse(manager.isCurrentDisplayReadAuthority(core, expected))
        assertFalse(manager.isCurrentDisplayReadAuthority(core, null))
        assertEquals(2, store.reads.get())
        assertTrue(cipher.calls.isEmpty())
    }

    @Test fun uiAuthorityEntrypointsNeverDecryptOnMainAndStillUseFreshCredentials() = runBlocking {
        cipher.rejectMain = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            assertNotNull(manager.authenticationSnapshot())
            assertNotNull(manager.localFactAccess())
            assertNotNull(manager.localIconAccess())
            assertNotNull(manager.iconAuthenticationSnapshot(icon))
            assertEquals("access", manager.accessToken.first())
            assertEquals("refresh", manager.refreshToken.first())
        }
        cipher.unreadable = setOf("cipher:access")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            assertNull(manager.authenticationSnapshot())
            assertNull(manager.localFactAccess())
            assertNull(manager.localIconAccess())
            assertNull(manager.iconAuthenticationSnapshot(icon))
            assertNull(manager.accessToken.first())
        }
    }
    private fun id(n: Int) = "aa160000-0000-4000-8000-${n.toString().padStart(12, '0')}"

    private class CountingCipher : TokenCipher {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var unreadable = emptySet<String>()
        @Volatile var cancel = false
        @Volatile var rejectMain = false
        override fun encrypt(value: String) = "cipher:$value"
        override fun decrypt(value: String): String? {
            if (rejectMain) assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
            calls += value
            if (cancel) throw CancellationException("synthetic cancellation")
            return if (value in unreadable) null else value.removePrefix("cipher:")
        }
    }

    private class CountingStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val reads = AtomicInteger()
        override val data: Flow<Preferences> = flow {
            reads.incrementAndGet()
            emit(delegate.data.first())
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData(transform)
    }
}
