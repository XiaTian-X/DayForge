package com.dayforge.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class AuthenticationSession(val userId: String, val generation: String)

/** No data-class toString: credentials must not appear in request-tag diagnostics. */
class AuthenticationSnapshot(
    val session: AuthenticationSession,
    val accessToken: String,
    val refreshToken: String?
)

/**
 * Manages authentication tokens using DataStore for persistent storage.
 * Provides secure storage for access and refresh tokens.
 */
@Singleton
class TokenManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val tokenCipher: TokenCipher
) {
    constructor(dataStore: DataStore<Preferences>) : this(dataStore, PlaintextTokenCipher)
    companion object {
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        private val USER_EMAIL_KEY = stringPreferencesKey("user_email")
        private val USER_ID_KEY = stringPreferencesKey("user_public_id")
        private val IS_ADMIN_KEY = booleanPreferencesKey("is_admin")
        private val AUTH_SESSION_KEY = stringPreferencesKey("auth_session_generation")
        private val INSTALLATION_ID_KEY = stringPreferencesKey("sync_installation_id")
        private val SYNC_ACCOUNT_ID_KEY = stringPreferencesKey("sync_account_id")
        private val SYNC_DEVICE_ID_KEY = stringPreferencesKey("sync_device_id")
        private val SYNC_CURSOR_KEY = stringPreferencesKey("sync_cursor")
        private val SYNC_BOOTSTRAPPED_KEY = booleanPreferencesKey("sync_bootstrapped")
        private val SERVER_INSTANCE_ID_KEY = stringPreferencesKey("sync_server_instance_id")
        private val SYNC_EPOCH_KEY = stringPreferencesKey("sync_epoch")
        private val DEVICE_CAPABILITIES_KEY = stringSetPreferencesKey("sync_device_capabilities")
        private val DEVICE_CAPABILITIES_KNOWN_KEY = booleanPreferencesKey("sync_device_capabilities_known")
        private val DEVICE_PRIMARY_EDITOR_KEY = booleanPreferencesKey("sync_device_primary_editor")
        private val DEVICE_CAPABILITY_REVISION_KEY = stringPreferencesKey("sync_device_capability_revision")
    }

    /**
     * Flow of the current access token.
     * Returns null if no token is stored.
     */
    val accessToken: Flow<String?> = dataStore.data.map { preferences ->
        preferences[ACCESS_TOKEN_KEY]?.let(tokenCipher::decrypt)
    }

    /**
     * Flow of the current refresh token.
     * Returns null if no token is stored.
     */
    val refreshToken: Flow<String?> = dataStore.data.map { preferences ->
        preferences[REFRESH_TOKEN_KEY]?.let(tokenCipher::decrypt)
    }

    /**
     * Flow of the current user email.
     * Returns null if no email is stored.
     */
    val userEmail: Flow<String?> = dataStore.data.map { preferences ->
        preferences[USER_EMAIL_KEY]
    }

    val userId: Flow<String?> = dataStore.data.map { it[USER_ID_KEY] }
    val isAdmin: Flow<Boolean> = dataStore.data.map { it[IS_ADMIN_KEY] ?: false }
    val syncDeviceId: Flow<String?> = dataStore.data.map { it[SYNC_DEVICE_ID_KEY] }
    val syncAccountId: Flow<String?> = dataStore.data.map { it[SYNC_ACCOUNT_ID_KEY] }
    val syncCursor: Flow<Long> = dataStore.data.map { it[SYNC_CURSOR_KEY]?.toLongOrNull() ?: 0L }
    val isSyncBootstrapped: Flow<Boolean> = dataStore.data.map { it[SYNC_BOOTSTRAPPED_KEY] ?: false }
    val serverInstanceId: Flow<String?> = dataStore.data.map { it[SERVER_INSTANCE_ID_KEY] }
    val syncEpoch: Flow<String?> = dataStore.data.map { it[SYNC_EPOCH_KEY] }
    val deviceCapabilities: Flow<Set<String>> = dataStore.data.map {
        it[DEVICE_CAPABILITIES_KEY] ?: emptySet()
    }
    val isPrimaryEditor: Flow<Boolean> = dataStore.data.map {
        it[DEVICE_PRIMARY_EDITOR_KEY] ?: false
    }
    val canEditStructure: Flow<Boolean> = dataStore.data.map { preferences ->
        val known = preferences[DEVICE_CAPABILITIES_KNOWN_KEY] ?: false
        !known || "structure.write" in (preferences[DEVICE_CAPABILITIES_KEY] ?: emptySet())
    }

    /**
     * Saves both access and refresh tokens to DataStore.
     * This replaces any existing tokens.
     */
    suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        email: String? = null,
        userId: String,
        isAdmin: Boolean
    ) {
        dataStore.edit { preferences ->
            preferences[AUTH_SESSION_KEY] = UUID.randomUUID().toString()
            preferences[ACCESS_TOKEN_KEY] = tokenCipher.encrypt(accessToken)
            preferences[REFRESH_TOKEN_KEY] = tokenCipher.encrypt(refreshToken)
            if (email != null) {
                preferences[USER_EMAIL_KEY] = email
            }
            preferences[USER_ID_KEY] = userId
            preferences[IS_ADMIN_KEY] = isAdmin
        }
    }

    /** Read request credentials and their account generation from one DataStore snapshot. */
    suspend fun authenticationSnapshot(): AuthenticationSnapshot? =
        snapshot(dataStore.data.first())

    private fun snapshot(preferences: Preferences): AuthenticationSnapshot? {
        val userId = preferences[USER_ID_KEY] ?: return null
        val accessToken = preferences[ACCESS_TOKEN_KEY]?.let(tokenCipher::decrypt) ?: return null
        return AuthenticationSnapshot(
            AuthenticationSession(userId, preferences[AUTH_SESSION_KEY] ?: "legacy"),
            accessToken,
            preferences[REFRESH_TOKEN_KEY]?.let(tokenCipher::decrypt)
        )
    }

    private fun matches(preferences: Preferences, expected: AuthenticationSnapshot): Boolean {
        val current = snapshot(preferences) ?: return false
        return current.session == expected.session && current.accessToken == expected.accessToken &&
            current.refreshToken == expected.refreshToken
    }

    /** Refresh may update only the credentials that initiated it, never a subsequent login. */
    suspend fun saveRefreshedTokens(
        expected: AuthenticationSnapshot,
        accessToken: String,
        refreshToken: String,
        username: String,
        userId: String,
        isAdmin: Boolean
    ): Boolean {
        if (userId != expected.session.userId) return false
        var saved = false
        dataStore.edit { preferences ->
            if (matches(preferences, expected)) {
                preferences[ACCESS_TOKEN_KEY] = tokenCipher.encrypt(accessToken)
                preferences[REFRESH_TOKEN_KEY] = tokenCipher.encrypt(refreshToken)
                preferences[USER_EMAIL_KEY] = username
                preferences[IS_ADMIN_KEY] = isAdmin
                saved = true
            }
        }
        return saved
    }

    /** A rejected refresh cannot invalidate credentials created while it was in flight. */
    suspend fun clearRejectedRefresh(expected: AuthenticationSnapshot) {
        dataStore.edit { preferences ->
            if (matches(preferences, expected)) {
                preferences.remove(ACCESS_TOKEN_KEY)
                preferences.remove(REFRESH_TOKEN_KEY)
                preferences.remove(USER_EMAIL_KEY)
                preferences.remove(USER_ID_KEY)
                preferences.remove(IS_ADMIN_KEY)
                preferences.remove(AUTH_SESSION_KEY)
            }
        }
    }

    /**
     * Rewrites credentials created by older app versions through the active
     * cipher. AndroidKeystoreTokenCipher leaves already-encrypted values alone,
     * so this is safe to call at every authenticated sync.
     */
    suspend fun migrateLegacyTokenStorage() {
        dataStore.edit { preferences ->
            preferences[ACCESS_TOKEN_KEY]?.let {
                preferences[ACCESS_TOKEN_KEY] = tokenCipher.encrypt(it)
            }
            preferences[REFRESH_TOKEN_KEY]?.let {
                preferences[REFRESH_TOKEN_KEY] = tokenCipher.encrypt(it)
            }
        }
    }

    suspend fun getOrCreateInstallationId(): String {
        val existing = dataStore.data.first()[INSTALLATION_ID_KEY]
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        dataStore.edit { it[INSTALLATION_ID_KEY] = created }
        return created
    }

    /** Reset device/cursor state when the authenticated account changes. */
    suspend fun prepareSyncAccount(accountId: String) {
        dataStore.edit { preferences ->
            if (preferences[SYNC_ACCOUNT_ID_KEY] != accountId) {
                preferences[SYNC_ACCOUNT_ID_KEY] = accountId
                preferences.remove(SYNC_DEVICE_ID_KEY)
                preferences.remove(SYNC_CURSOR_KEY)
                preferences.remove(SYNC_BOOTSTRAPPED_KEY)
                preferences.remove(SERVER_INSTANCE_ID_KEY)
                preferences.remove(SYNC_EPOCH_KEY)
                clearDeviceCapabilities(preferences)
            }
        }
    }

    suspend fun saveSyncDeviceId(deviceId: String) {
        dataStore.edit { it[SYNC_DEVICE_ID_KEY] = deviceId }
    }

    suspend fun saveDeviceRegistration(
        deviceId: String,
        capabilities: Set<String>,
        isPrimaryEditor: Boolean,
        capabilityRevision: Int
    ) {
        dataStore.edit { preferences ->
            preferences[SYNC_DEVICE_ID_KEY] = deviceId
            if (capabilities.isNotEmpty()) {
                preferences[DEVICE_CAPABILITIES_KEY] = capabilities
                preferences[DEVICE_CAPABILITIES_KNOWN_KEY] = true
                preferences[DEVICE_PRIMARY_EDITOR_KEY] = isPrimaryEditor
                preferences[DEVICE_CAPABILITY_REVISION_KEY] = capabilityRevision.toString()
            }
        }
    }

    /** Fail closed after the server rejects a structural operation for this device. */
    suspend fun markStructuralEditingDenied() {
        dataStore.edit { preferences ->
            val capabilities = (preferences[DEVICE_CAPABILITIES_KEY] ?: emptySet()).toMutableSet()
            capabilities.remove("structure.write")
            preferences[DEVICE_CAPABILITIES_KEY] = capabilities
            preferences[DEVICE_CAPABILITIES_KNOWN_KEY] = true
            preferences[DEVICE_PRIMARY_EDITOR_KEY] = false
        }
    }

    suspend fun saveServerIdentity(serverInstanceId: String, syncEpoch: String) {
        dataStore.edit {
            it[SERVER_INSTANCE_ID_KEY] = serverInstanceId
            it[SYNC_EPOCH_KEY] = syncEpoch
        }
    }

    /** Start a clean replica after the same server reports a new database epoch. */
    suspend fun resetReplicaForEpoch(serverInstanceId: String, syncEpoch: String) {
        dataStore.edit { preferences ->
            preferences[SERVER_INSTANCE_ID_KEY] = serverInstanceId
            preferences[SYNC_EPOCH_KEY] = syncEpoch
            preferences.remove(SYNC_DEVICE_ID_KEY)
            preferences.remove(SYNC_CURSOR_KEY)
            preferences.remove(SYNC_BOOTSTRAPPED_KEY)
            clearDeviceCapabilities(preferences)
        }
    }

    suspend fun saveSyncCursor(cursor: Long, bootstrapped: Boolean = true) {
        dataStore.edit {
            it[SYNC_CURSOR_KEY] = cursor.toString()
            if (bootstrapped) it[SYNC_BOOTSTRAPPED_KEY] = true
        }
    }

    /** Force the next safe sync to rebuild the local cache from server state. */
    suspend fun requireSyncBootstrap() {
        dataStore.edit { it[SYNC_BOOTSTRAPPED_KEY] = false }
    }

    /** Clear only credentials after refresh failure, retaining local-data ownership. */
    suspend fun clearAuthenticationTokens() {
        dataStore.edit { preferences ->
            preferences.remove(AUTH_SESSION_KEY)
            preferences.remove(ACCESS_TOKEN_KEY)
            preferences.remove(REFRESH_TOKEN_KEY)
            preferences.remove(USER_EMAIL_KEY)
            preferences.remove(USER_ID_KEY)
            preferences.remove(IS_ADMIN_KEY)
        }
    }

    /** Reset synchronization metadata after the corresponding local database is cleared. */
    suspend fun clearSyncState() {
        dataStore.edit { preferences ->
            preferences.remove(SYNC_ACCOUNT_ID_KEY)
            preferences.remove(SYNC_DEVICE_ID_KEY)
            preferences.remove(SYNC_CURSOR_KEY)
            preferences.remove(SYNC_BOOTSTRAPPED_KEY)
            preferences.remove(SERVER_INSTANCE_ID_KEY)
            preferences.remove(SYNC_EPOCH_KEY)
            clearDeviceCapabilities(preferences)
        }
    }

    /**
     * Saves user email to DataStore.
     */
    suspend fun saveUserEmail(email: String) {
        dataStore.edit { preferences ->
            preferences[USER_EMAIL_KEY] = email
        }
    }

    /**
     * Clears all stored tokens.
     * Called on logout or when token refresh fails.
     */
    suspend fun clearTokens() {
        dataStore.edit { preferences ->
            preferences.remove(AUTH_SESSION_KEY)
            preferences.remove(ACCESS_TOKEN_KEY)
            preferences.remove(REFRESH_TOKEN_KEY)
            preferences.remove(USER_EMAIL_KEY)
            preferences.remove(USER_ID_KEY)
            preferences.remove(IS_ADMIN_KEY)
            preferences.remove(SYNC_ACCOUNT_ID_KEY)
            preferences.remove(SYNC_DEVICE_ID_KEY)
            preferences.remove(SYNC_CURSOR_KEY)
            preferences.remove(SYNC_BOOTSTRAPPED_KEY)
            preferences.remove(SERVER_INSTANCE_ID_KEY)
            preferences.remove(SYNC_EPOCH_KEY)
            clearDeviceCapabilities(preferences)
        }
    }

    private fun clearDeviceCapabilities(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.remove(DEVICE_CAPABILITIES_KEY)
        preferences.remove(DEVICE_CAPABILITIES_KNOWN_KEY)
        preferences.remove(DEVICE_PRIMARY_EDITOR_KEY)
        preferences.remove(DEVICE_CAPABILITY_REVISION_KEY)
    }

}
