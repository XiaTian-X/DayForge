package com.dayforge.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class TokenManagerTest {
    private lateinit var storeScope: CoroutineScope
    private lateinit var manager: TokenManager
    private lateinit var context: Context
    private lateinit var store: DataStore<Preferences>
    private lateinit var file: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "token_manager_${UUID.randomUUID()}.preferences_pb")
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file })
        manager = TokenManager(store, TestTokenCipher)
    }

    @After
    fun teardown() = runBlocking { storeScope.coroutineContext[Job]!!.cancelAndJoin(); file.delete(); Unit }

    @Test
    fun credentials_persist_account_identity_and_delegate_token_storage_to_cipher() = runBlocking {
        manager.saveTokens("access-secret", "refresh-secret", "admin", "account-a", true)

        assertEquals("access-secret", manager.accessToken.first())
        assertEquals("account-a", manager.userId.first())
        assertTrue(manager.isAdmin.first())
        val persisted = store.data.first().asMap().values.map { it.toString() }
        // This fake verifies wiring only; AndroidKeystoreTokenCipherTest verifies encryption.
        assertTrue(persisted.contains("encrypted:access-secret"))
        assertTrue(persisted.contains("encrypted:refresh-secret"))
    }

    @Test
    fun failed_login_session_write_rolls_back_owner_credentials_and_replica_metadata() = runBlocking {
        manager.prepareSyncAccount("account-a")
        manager.saveServerIdentity("server-a", "epoch-a")
        manager.saveDeviceRegistration("device-a", setOf("facts.write"), false, 3)
        manager.saveSyncCursor(42)
        manager.saveTokens("access-a", "refresh-a", "member-a", "account-a", false)
        val before = store.data.first()
        val failingManager = TokenManager(store, object : TokenCipher {
            override fun encrypt(value: String): String {
                check(value != "refresh-b") { "cipher unavailable" }
                return "encrypted:$value"
            }
            override fun decrypt(value: String) = value.removePrefix("encrypted:")
        })

        try {
            failingManager.saveLoginSession("access-b", "refresh-b", "member-b", "account-b", true)
            fail("Partial login must not commit")
        } catch (expected: IllegalStateException) {
            assertEquals("cipher unavailable", expected.message)
        }
        assertEquals(before, store.data.first())

        manager.saveLoginSession("access-b", "refresh-b", "member-b", "account-b", true)
        assertEquals("account-b", manager.syncAccountId.first())
        assertEquals("account-b", manager.userId.first())
        assertEquals("access-b", manager.accessToken.first())
        assertTrue(manager.isAdmin.first())
        assertNull(manager.syncDeviceId.first())
        assertNull(manager.serverInstanceId.first())
        assertNull(manager.syncEpoch.first())
        assertEquals(0L, manager.syncCursor.first())
        assertFalse(manager.isSyncBootstrapped.first())
        assertTrue(manager.deviceCapabilities.first().isEmpty())
    }

    @Test
    fun legacy_plaintext_tokens_migrate_in_place() = runBlocking {
        store.edit {
            it[stringPreferencesKey("access_token")] = "legacy-access"
            it[stringPreferencesKey("refresh_token")] = "legacy-refresh"
        }
        manager.migrateLegacyTokenStorage()
        assertEquals("legacy-access", manager.accessToken.first())
        assertTrue(store.data.first().asMap().values.any { it == "encrypted:legacy-access" })
    }

    @Test
    fun switching_sync_owner_clears_device_cursor_and_bootstrap_state() = runBlocking {
        manager.prepareSyncAccount("account-a")
        manager.saveSyncDeviceId("device-a")
        manager.saveSyncCursor(42)
        manager.prepareSyncAccount("account-b")

        assertNull(manager.syncDeviceId.first())
        assertEquals(0, manager.syncCursor.first())
        assertFalse(manager.isSyncBootstrapped.first())
    }

    @Test
    fun authentication_failure_retains_sync_ownership_for_safe_relogin() = runBlocking {
        manager.saveTokens("access", "refresh", "member", "account-a", false)
        manager.prepareSyncAccount("account-a")
        manager.clearAuthenticationTokens()

        assertNull(manager.accessToken.first())
        assertNull(manager.userId.first())
        assertFalse(manager.isAdmin.first())
        assertEquals("account-a", manager.syncAccountId.first())
    }

    @Test
    fun new_server_epoch_clears_only_replica_cursor_and_device_registration() = runBlocking {
        manager.prepareSyncAccount("account-a")
        manager.saveSyncDeviceId("device-a")
        manager.saveSyncCursor(42)
        manager.resetReplicaForEpoch("server-a", "epoch-b")

        assertEquals("server-a", manager.serverInstanceId.first())
        assertEquals("epoch-b", manager.syncEpoch.first())
        assertNull(manager.syncDeviceId.first())
        assertEquals(0, manager.syncCursor.first())
        assertFalse(manager.isSyncBootstrapped.first())
        assertEquals("account-a", manager.syncAccountId.first())
    }

    @Test
    fun refresh_cannot_restore_a_logged_out_session() = runBlocking {
        manager.saveTokens("a", "r", "member", "account-a", false)
        val original = manager.authenticationSnapshot()!!
        manager.clearTokens()
        assertFalse(manager.saveRefreshedTokens(original, "new-a", "new-r", "member", "account-a", false))
        assertNull(manager.authenticationSnapshot())
    }

    @Test
    fun old_refresh_success_and_failure_cannot_overwrite_a_new_login() = runBlocking {
        for (nextAccount in listOf("account-a", "account-b")) {
            manager.saveTokens("a", "r", "member", "account-a", false)
            val original = manager.authenticationSnapshot()!!
            // Identical tokens can be issued by the server within the same second.
            manager.saveTokens("a", "r", "next", nextAccount, true)
            assertFalse(manager.saveRefreshedTokens(original, "late-a", "late-r", "member", "account-a", false))
            manager.clearRejectedRefresh(original)
            assertEquals(nextAccount, manager.userId.first())
            assertEquals("a", manager.accessToken.first())
            assertTrue(manager.isAdmin.first())
        }
    }

    @Test
    fun normal_refresh_keeps_generation_while_stale_rejection_keeps_new_credentials() = runBlocking {
        manager.saveTokens("a", "r", "member", "account-a", false)
        val original = manager.authenticationSnapshot()!!
        assertTrue(manager.saveRefreshedTokens(original, "new-a", "r", "member", "account-a", false))
        assertEquals(original.session, manager.authenticationSnapshot()!!.session)
        manager.clearRejectedRefresh(original)
        assertEquals("new-a", manager.accessToken.first())
        assertFalse(manager.saveRefreshedTokens(original, "late-a", "late-r", "member", "account-a", false))
        assertEquals("new-a", manager.accessToken.first())
    }

    @Test
    fun refresh_response_for_another_user_is_refused() = runBlocking {
        manager.saveTokens("a", "r", "member", "account-a", false)
        val original = manager.authenticationSnapshot()!!
        assertFalse(manager.saveRefreshedTokens(original, "b", "br", "other", "account-b", true))
        assertEquals("a", manager.accessToken.first())
    }

    @Test
    fun current_refresh_rejection_clears_authentication_but_preserves_sync_ownership() = runBlocking {
        manager.saveTokens("a", "r", "member", "account-a", false)
        manager.prepareSyncAccount("account-a")
        manager.clearRejectedRefresh(manager.authenticationSnapshot()!!)
        assertNull(manager.authenticationSnapshot())
        assertEquals("account-a", manager.syncAccountId.first())
    }

    private object TestTokenCipher : TokenCipher {
        override fun encrypt(value: String) = if (value.startsWith("encrypted:")) value else "encrypted:$value"
        override fun decrypt(value: String) = value.removePrefix("encrypted:")
    }

    private fun id(n: Int) = "aa320000-0000-4000-8000-${n.toString().padStart(12, '0')}"

    @Test fun concurrent_installation_creation_returns_one_committed_identity() = runBlocking<Unit> {
        val ids = (1..32).map { async(Dispatchers.IO) { manager.getOrCreateInstallationId() } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(ids.first(), manager.getOrCreateInstallationId())
        assertEquals(ids.first(), store.data.first()[stringPreferencesKey("sync_installation_id")])
    }

    @Test fun next_registration_atomically_saves_complete_proof_without_resetting_legacy_cursor() = runBlocking<Unit> {
        manager.saveLoginSession("a", "r", "member", id(1), false)
        manager.saveSyncCursor(42)
        val original = manager.localCoreWriteAccess()!!
        val installation = manager.getOrCreateInstallationId()
        val result = manager.saveNextRegistration(original, null, installation, id(2), id(3), id(4),
            setOf("sync.read", "structure.write"), true, 3)
        assertEquals(result, manager.localSyncAccess())
        assertEquals(original.session.authentication, result.session.authentication)
        assertEquals(id(2), result.session.serverInstanceId); assertEquals(id(3), result.session.syncEpoch)
        assertEquals(3, result.capabilityRevision); assertEquals(id(4), manager.localCoreWriteAccess()!!.capturedDeviceId)
        assertEquals(42L, manager.syncCursor.first()); assertTrue(manager.isSyncBootstrapped.first())
        assertTrue(manager.isPrimaryEditor.first())
    }

    @Test fun late_next_registration_rejects_changed_auth_replica_device_or_capability_revision() = runBlocking<Unit> {
        for (change in 0..3) {
            manager.saveLoginSession("a", "r", "member", id(1), false)
            manager.saveServerIdentity(id(2), id(3))
            manager.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 1)
            val core = manager.localCoreWriteAccess()!!; val access = manager.localSyncAccess()!!
            val installation = manager.getOrCreateInstallationId()
            when (change) {
                0 -> manager.saveLoginSession("a", "r", "again", id(1), false)
                1 -> manager.saveServerIdentity(id(2), id(9))
                2 -> manager.saveDeviceRegistration(id(9), setOf("sync.read"), false, 2)
                3 -> manager.saveDeviceRegistration(id(4), setOf("sync.read"), false, 2)
            }
            val before = store.data.first()
            try {
                manager.saveNextRegistration(core, access, installation, id(2), id(3), id(4),
                    setOf("sync.read", "structure.write"), true, 1)
                fail("Late proof must not commit")
            } catch (_: IllegalStateException) { }
            assertEquals(before, store.data.first())
        }
    }
}
