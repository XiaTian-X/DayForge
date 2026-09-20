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
}
