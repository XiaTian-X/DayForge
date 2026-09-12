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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TokenManagerTest {
    private lateinit var manager: TokenManager
    private lateinit var context: Context
    private lateinit var store: DataStore<Preferences>
    private lateinit var file: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "token_manager_v2.preferences_pb")
        store = PreferenceDataStoreFactory.create(produceFile = { file })
        manager = TokenManager(store, TestTokenCipher)
    }

    @After
    fun teardown() { file.delete() }

    @Test
    fun `credentials persist account identity and admin role encrypted`() = runTest {
        manager.saveTokens("access-secret", "refresh-secret", "admin", "account-a", true)

        assertEquals("access-secret", manager.accessToken.first())
        assertEquals("account-a", manager.userId.first())
        assertTrue(manager.isAdmin.first())
        val persisted = store.data.first().asMap().values.map { it.toString() }
        assertFalse(persisted.contains("access-secret"))
        assertFalse(persisted.contains("refresh-secret"))
    }

    @Test
    fun `legacy plaintext tokens migrate in place`() = runTest {
        store.edit {
            it[stringPreferencesKey("access_token")] = "legacy-access"
            it[stringPreferencesKey("refresh_token")] = "legacy-refresh"
        }
        manager.migrateLegacyTokenStorage()
        assertEquals("legacy-access", manager.accessToken.first())
        assertTrue(store.data.first().asMap().values.any { it == "encrypted:legacy-access" })
    }

    @Test
    fun `switching sync owner clears device cursor and bootstrap state`() = runTest {
        manager.prepareSyncAccount("account-a")
        manager.saveSyncDeviceId("device-a")
        manager.saveSyncCursor(42)
        manager.prepareSyncAccount("account-b")

        assertNull(manager.syncDeviceId.first())
        assertEquals(0, manager.syncCursor.first())
        assertFalse(manager.isSyncBootstrapped.first())
    }

    @Test
    fun `authentication failure retains sync ownership for safe relogin`() = runTest {
        manager.saveTokens("access", "refresh", "member", "account-a", false)
        manager.prepareSyncAccount("account-a")
        manager.clearAuthenticationTokens()

        assertNull(manager.accessToken.first())
        assertNull(manager.userId.first())
        assertFalse(manager.isAdmin.first())
        assertEquals("account-a", manager.syncAccountId.first())
    }

    @Test
    fun `new server epoch clears only replica cursor and device registration`() = runTest {
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
    fun `refresh cannot restore a logged out session`() = runTest {
        manager.saveTokens("a", "r", "member", "account-a", false)
        val original = manager.authenticationSnapshot()!!
        manager.clearTokens()
        assertFalse(manager.saveRefreshedTokens(original, "new-a", "new-r", "member", "account-a", false))
        assertNull(manager.authenticationSnapshot())
    }

    @Test
    fun `old refresh success and failure cannot overwrite a new login`() = runTest {
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
    fun `normal refresh keeps generation while stale rejection keeps new credentials`() = runTest {
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
    fun `refresh response for another user is refused`() = runTest {
        manager.saveTokens("a", "r", "member", "account-a", false)
        val original = manager.authenticationSnapshot()!!
        assertFalse(manager.saveRefreshedTokens(original, "b", "br", "other", "account-b", true))
        assertEquals("a", manager.accessToken.first())
    }

    @Test
    fun `current refresh rejection clears authentication but preserves sync ownership`() = runTest {
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
