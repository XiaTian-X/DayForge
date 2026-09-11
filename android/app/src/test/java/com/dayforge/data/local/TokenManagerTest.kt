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

    private object TestTokenCipher : TokenCipher {
        override fun encrypt(value: String) = if (value.startsWith("encrypted:")) value else "encrypted:$value"
        override fun decrypt(value: String) = value.removePrefix("encrypted:")
    }
}
