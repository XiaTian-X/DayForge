package com.dayforge.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class AuthenticationSession(val userId: String, val generation: String)

/** No credentials; a captured UI intent must not survive login or replica replacement. */
internal data class LocalDataSession(
    val authentication: AuthenticationSession,
    val serverInstanceId: String?,
    val syncEpoch: String?
)

internal data class LocalFactAccess(val session: LocalDataSession, val canAppend: Boolean)

/** Offline core writes may precede registration; known permissions must still be respected. */
internal data class LocalCoreWriteAccess(val session: LocalDataSession, val capabilities: Set<String>?,
    val capturedDeviceId: String? = null)

/** A single snapshot; icon access never inherits legacy/unknown device permissions. */
internal data class LocalIconAccess(
    val session: LocalDataSession,
    val deviceId: String,
    val capabilityRevision: Int,
    val canDeclare: Boolean
)

/** Captured core request authority; registration deliberately has no device proof. */
internal data class LocalSyncAccess(
    val session: LocalDataSession,
    val deviceId: String? = null,
    val capabilityRevision: Int? = null,
    val capabilities: Set<String> = emptySet()
) {
    init {
        require(listOf(session.authentication.userId, session.authentication.generation,
            session.serverInstanceId, session.syncEpoch).all { it != null && com.dayforge.domain.model.isContractUuid(it) })
        if (deviceId == null) require(capabilityRevision == null && capabilities.isEmpty())
        else require(com.dayforge.domain.model.isContractUuid(deviceId) &&
            capabilityRevision != null && capabilityRevision > 0 && "sync.read" in capabilities)
    }
}

/** No data-class toString: credentials must not appear in request-tag diagnostics. */
class AuthenticationSnapshot(
    val session: AuthenticationSession,
    val accessToken: String,
    val refreshToken: String?
)

/** Ephemeral login witness, not replica authority. Never stringify/export its raw preferences. */
internal class ReplicaLoginWitness internal constructor(internal val owner: TokenManager,
    internal val values: Preferences, val accountId: String, val serverInstanceId: String,
    val currentEpoch: String, val destinationEpoch: String?)

/**
 * Manages authentication tokens using DataStore for persistent storage.
 * Provides secure storage for access and refresh tokens.
 */
@Singleton
class TokenManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val tokenCipher: TokenCipher,
    private val iconMemory: AccountIconMemory
) {
    internal fun usesPreferences(value: DataStore<Preferences>) = dataStore === value
    constructor(dataStore: DataStore<Preferences>, tokenCipher: TokenCipher) :
        this(dataStore, tokenCipher, AccountIconMemory())
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
        private val DEVICE_CAPABILITIES_DEVICE_KEY = stringPreferencesKey("sync_device_capabilities_device")
        private val ICON_IDENTITY_KEYS = listOf(USER_ID_KEY, AUTH_SESSION_KEY, SYNC_ACCOUNT_ID_KEY,
            SYNC_DEVICE_ID_KEY, SERVER_INSTANCE_ID_KEY, SYNC_EPOCH_KEY, DEVICE_CAPABILITIES_KEY,
            DEVICE_CAPABILITIES_KNOWN_KEY, DEVICE_CAPABILITY_REVISION_KEY, DEVICE_CAPABILITIES_DEVICE_KEY,
            ReplicaSwitchGuard.POINTER)
    }

    internal fun registerIconCache(cache: AccountIconMemory.Cache) = iconMemory.register(cache)
    private val replicaPresentation = ReplicaPresentationMemory()
    internal fun registerReplicaPresentation(cache: ReplicaPresentationMemory.Cache) = replicaPresentation.register(cache)
    internal fun beginIconSelectionTransition() = iconMemory.beginTransition()
    internal fun endIconSelectionTransition() = iconMemory.endTransition()

    // Token bytes are deliberately excluded: same-generation refresh preserves the cache.
    private fun iconIdentity(preferences: Preferences): List<Any?> =
        listOf(preferences[ACCESS_TOKEN_KEY] != null) + ICON_IDENTITY_KEYS.map { preferences[it] }

    /** No delayed Flow collector: block publication across the actual persisted transition. */
    private suspend fun editPreferences(block: suspend (MutablePreferences) -> Unit): Preferences {
        val caller = currentCoroutineContext()
        var transitioning = false
        var replicaTransitioning = false
        try {
            // DataStore owns the write actor: cancelling its caller need not stop a write
            // after transform. Join actual persistence before reopening image publication.
            val result = withContext(NonCancellable) {
                dataStore.edit { preferences ->
                    caller.ensureActive()
                    val before = iconIdentity(preferences)
                    val beforePointer = preferences.asMap().filterKeys { it.name == ReplicaSwitchGuard.POINTER.name }
                    block(preferences)
                    caller.ensureActive()
                    if (before != iconIdentity(preferences)) {
                        iconMemory.beginTransition()
                        transitioning = true
                    }
                    if (beforePointer != preferences.asMap().filterKeys { it.name == ReplicaSwitchGuard.POINTER.name }) {
                        replicaPresentation.begin()
                        replicaTransitioning = true
                    }
                }
            }
            caller.ensureActive()
            return result
        } finally {
            // Also runs after failed/cancelled writes. Never pretend persistence succeeded;
            // conservative memory eviction is safe and cannot delete installation intent.
            if (transitioning) iconMemory.endTransition()
            if (replicaTransitioning) replicaPresentation.end()
        }
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
        !ReplicaSwitchGuard.blocked(preferences) &&
            (!known || "structure.write" in (preferences[DEVICE_CAPABILITIES_KEY] ?: emptySet()))
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
    ) = persistTokens(accessToken, refreshToken, email, userId, isAdmin, bindLocalOwner = false)

    /**
     * Activate a login only after the caller has cleared the previous account's cache or
     * obtained consent to adopt unowned data, under AccountSessionCoordinator.
     * Ownership and credentials commit together even when initial sync is skipped.
     */
    suspend fun saveLoginSession(
        accessToken: String,
        refreshToken: String,
        email: String?,
        userId: String,
        isAdmin: Boolean
    ) = persistTokens(accessToken, refreshToken, email, userId, isAdmin, bindLocalOwner = true)

    private suspend fun persistTokens(
        accessToken: String,
        refreshToken: String,
        email: String?,
        userId: String,
        isAdmin: Boolean,
        bindLocalOwner: Boolean
    ) {
        editPreferences { preferences ->
            if (ReplicaSwitchGuard.blocked(preferences))
                check(preferences[SYNC_ACCOUNT_ID_KEY] == userId) { ReplicaSwitchGuard.PENDING }
            if (bindLocalOwner) prepareSyncAccount(preferences, userId)
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

    /** Credential-free UI generation only, NOT authenticated access. Allows explicit recovery
     * login even when old Keystore ciphertext is unusable; actual operations still prove auth.
     */
    internal suspend fun replicaUiAuthentication(): AuthenticationSession? {
        val values = dataStore.data.first()
        val user = values[USER_ID_KEY] ?: return null
        val generation = values[AUTH_SESSION_KEY] ?: return null
        if (!com.dayforge.domain.model.isContractUuid(user) || !com.dayforge.domain.model.isContractUuid(generation)) return null
        return AuthenticationSession(user, generation)
    }

    /** Same-owner recovery login may work even when the old JWT cannot be decrypted/used.
     * No registration, account adoption, stage repair or namespace publication is authorized.
     */
    internal suspend fun replicaLoginWitness(): ReplicaLoginWitness = replicaLoginWitness(dataStore.data.first())

    private suspend fun replicaLoginWitness(values: Preferences): ReplicaLoginWitness {
        val account = requireNotNull(values[SYNC_ACCOUNT_ID_KEY]) { "SYNC_RECOVERY_LOGIN_SCOPE_INVALID" }
        val server = requireNotNull(values[SERVER_INSTANCE_ID_KEY]) { "SYNC_RECOVERY_LOGIN_SCOPE_INVALID" }
        val epoch = requireNotNull(values[SYNC_EPOCH_KEY]) { "SYNC_RECOVERY_LOGIN_SCOPE_INVALID" }
        val device = requireNotNull(values[SYNC_DEVICE_ID_KEY]) { "SYNC_RECOVERY_LOGIN_SCOPE_INVALID" }
        val installation = requireNotNull(values[INSTALLATION_ID_KEY]) { "SYNC_RECOVERY_LOGIN_SCOPE_INVALID" }
        require(listOf(account, server, epoch, device, installation).all { com.dayforge.domain.model.isContractUuid(it) })
        require(values[USER_ID_KEY]?.let { it == account } != false &&
            values[DEVICE_CAPABILITIES_KNOWN_KEY] == true && values[DEVICE_CAPABILITIES_DEVICE_KEY] == device &&
            values[DEVICE_CAPABILITY_REVISION_KEY]?.toIntOrNull()?.let { it > 0 } == true &&
            "sync.read" in values[DEVICE_CAPABILITIES_KEY].orEmpty()) { "SYNC_RECOVERY_LOGIN_SCOPE_INVALID" }
        val stage = if (ReplicaSwitchGuard.blocked(values)) {
            val id = requireNotNull(values[ReplicaSwitchGuard.POINTER]) { "SYNC_REPLICA_STAGE_MISSING" }
            ReplicaSwitchStage.decode(requireNotNull(values[ReplicaSwitchStage.key(id)]) { "SYNC_REPLICA_STAGE_MISSING" }).also {
                verifyReplicaAudits(values, it)
                require(!it.terminal && it.recoveryId == id && it.accountId == account &&
                    it.serverInstanceId == server && it.installationId == installation && it.deviceId == device &&
                    epoch == if (it.destinationPending) it.newEpoch else it.oldEpoch) { "SYNC_REPLICA_STAGE_CHANGED" }
            }
        } else null
        return ReplicaLoginWitness(this, values, account, server, epoch, stage?.newEpoch)
    }

    internal suspend fun requireReplicaLoginWitness(expected: ReplicaLoginWitness) {
        requireReplicaLoginWitness(expected, dataStore.data.first())
    }

    private suspend fun requireReplicaLoginWitness(expected: ReplicaLoginWitness, values: Preferences) {
        check(expected.owner === this && values.asMap() == expected.values.asMap()) {
            "SYNC_RECOVERY_LOGIN_CHANGED"
        }
        val actual = replicaLoginWitness(values)
        check(actual.accountId == expected.accountId && actual.serverInstanceId == expected.serverInstanceId &&
            actual.currentEpoch == expected.currentEpoch && actual.destinationEpoch == expected.destinationEpoch) {
            "SYNC_RECOVERY_LOGIN_CHANGED"
        }
    }

    /** The exact pre-request witness and SAME public account must survive ONE actor CAS.
     * Keep all sync/device/stage/installation/preferences bytes; replace credentials only.
     */
    internal suspend fun saveReplicaLogin(expected: ReplicaLoginWitness, accessToken: String,
        refreshToken: String, username: String, userId: String, isAdmin: Boolean): AuthenticationSession {
        require(userId == expected.accountId && com.dayforge.domain.model.isContractUuid(userId) &&
            com.dayforge.data.api.materialTokenSafe(accessToken) && com.dayforge.data.api.materialTokenSafe(refreshToken)) {
            "SYNC_RECOVERY_LOGIN_ACCOUNT_MISMATCH"
        }
        val generation = UUID.randomUUID().toString()
        editPreferences { values ->
            requireReplicaLoginWitness(expected, values)
            values[AUTH_SESSION_KEY] = generation
            values[ACCESS_TOKEN_KEY] = tokenCipher.encrypt(accessToken)
            values[REFRESH_TOKEN_KEY] = tokenCipher.encrypt(refreshToken)
            values[USER_EMAIL_KEY] = username
            values[USER_ID_KEY] = userId
            values[IS_ADMIN_KEY] = isAdmin
        }
        return AuthenticationSession(userId, generation)
    }

    /** Read ownership, generation, replica and permissions from the same persisted snapshot. */
    internal suspend fun localFactAccess(): LocalFactAccess? {
        return factAccess(dataStore.data.first())
    }

    internal val factAccessChanges: Flow<LocalFactAccess?> = dataStore.data.map(::factAccess)
        .distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun factAccess(preferences: Preferences): LocalFactAccess? {
        val authentication = snapshot(preferences)?.session ?: return null
        if (preferences[SYNC_ACCOUNT_ID_KEY] != authentication.userId) return null
        val server = preferences[SERVER_INSTANCE_ID_KEY]
        val epoch = preferences[SYNC_EPOCH_KEY]
        if ((server == null) != (epoch == null)) return null
        return LocalFactAccess(
            LocalDataSession(authentication, server, epoch),
            !ReplicaSwitchGuard.blocked(preferences) && (preferences[DEVICE_CAPABILITIES_KNOWN_KEY] != true ||
                "facts.append" in (preferences[DEVICE_CAPABILITIES_KEY] ?: emptySet()))
        )
    }

    /** Called inside the owning preferences actor after its caller proved the Room habit UUID.
     * No account-lock re-entry or second DataStore snapshot; this is not a business-write grant.
     */
    internal fun requireHabitPreferenceSession(values: Preferences, expected: LocalDataSession) {
        ReplicaSwitchGuard.requireOpen(values)
        check(factAccess(values)?.session == expected) { "HABIT_PREFERENCE_SESSION_CHANGED" }
    }

    internal suspend fun localIconAccess(): LocalIconAccess? = iconAccess(dataStore.data.first())

    internal suspend fun localCoreWriteAccess(): LocalCoreWriteAccess? = withContext(Dispatchers.IO) {
        coreWriteAccess(dataStore.data.first())
    }

    /** Read-only recovery proof, never a ticket for an ordinary writer. */
    internal suspend fun replicaObservationCoreAccess(): LocalCoreWriteAccess? = withContext(Dispatchers.IO) {
        observationCoreAccess(dataStore.data.first())
    }

    internal suspend fun replicaSwitchPending(): Boolean = ReplicaSwitchGuard.blocked(dataStore.data.first())
    internal val replicaPresentationChanges: Flow<Unit> = dataStore.data.map { Unit }

    /** One actual snapshot, no credential export. Orphan/corrupt stages are not an open replica. */
    internal suspend fun replicaPresentationWitness(): ReplicaPresentationWitness {
        val values = dataStore.data.first()
        val rows = values.asMap().filterKeys { it.name.startsWith(ReplicaSwitchStage.KEY_PREFIX) }
        val historyRows = values.asMap().filterKeys { ReplicaSwitchStage.isHistoryKey(it.name) }
        check(historyRows.values.sumOf { (it as? String)?.toByteArray(Charsets.UTF_8)?.size?.toLong()
            ?: error("SYNC_REPLICA_STAGE_INVALID") } <= ReplicaSwitchStage.HISTORY_LIMIT) { "SYNC_REPLICA_STAGE_LIMIT" }
        val history = rows.map { (key, raw) ->
            ReplicaSwitchStage.decode(raw as String).also {
                check(key.name == ReplicaSwitchStage.key(it.recoveryId).name) { "SYNC_REPLICA_STAGE_CHANGED" }
                verifyReplicaAudits(values, it)
            }
        }
        val present = ReplicaSwitchGuard.blocked(values)
        val active = if (present) {
            val id = requireNotNull(values[ReplicaSwitchGuard.POINTER]) { "SYNC_REPLICA_STAGE_MISSING" }
            requireNotNull(history.singleOrNull { it.recoveryId == id && !it.terminal }) {
                "SYNC_REPLICA_STAGE_MISSING"
            }
        } else null
        return ReplicaPresentationWitness(present, active, history)
    }
    internal suspend fun requireOrdinaryReplicaAccess() = ReplicaSwitchGuard.requireOpen(dataStore)

    internal suspend fun activeReplicaStage(): ReplicaSwitchStage? {
        val values = dataStore.data.first()
        if (!ReplicaSwitchGuard.blocked(values)) return null
        val id = requireNotNull(values[ReplicaSwitchGuard.POINTER]) { "SYNC_REPLICA_STAGE_MISSING" }
        val stage = ReplicaSwitchStage.decode(requireNotNull(values[ReplicaSwitchStage.key(id)]) { "SYNC_REPLICA_STAGE_MISSING" })
        check(stage.recoveryId == id && !stage.terminal) { "SYNC_REPLICA_STAGE_CHANGED" }
        return stage
    }

    internal suspend fun replicaStage(id: String): ReplicaSwitchStage? {
        val values = dataStore.data.first()
        val raw = values[ReplicaSwitchStage.key(id)] ?: return null
        return ReplicaSwitchStage.decode(raw).also {
            check(it.recoveryId == id) { "SYNC_REPLICA_STAGE_CHANGED" }
            verifyReplicaAudits(values, it)
        }
    }

    /** Specialized durable CAS, not a general preferences writer or ordinary-write override.
     * The participant calls this after checking the actual SQL phase. Destination publication
     * changes ONLY the verified target identity/checkpoint with its journal in ONE actor write.
     * The pointer remains present: neither SQL_COMMITTED nor DESTINATION_BOUND reopens work.
     */
    internal suspend fun persistReplicaStage(expected: ReplicaSwitchStage?, next: ReplicaSwitchStage): ReplicaSwitchStage {
        val raw = ReplicaSwitchStage.encode(next)
        val frozen = ReplicaSwitchStage.decode(raw)
        editPreferences { values ->
            val key = ReplicaSwitchStage.key(frozen.recoveryId)
            if (expected == null) {
                ReplicaSwitchGuard.requireOpen(values)
                check(frozen.phase == ReplicaSwitchPhase.FREEZING && values[key] == null) { "SYNC_REPLICA_STAGE_CHANGED" }
                check(syncAccess(values) == frozen.original && values[INSTALLATION_ID_KEY] == frozen.installationId) {
                    "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
                }
            } else {
                check(values[ReplicaSwitchGuard.POINTER] == expected.recoveryId &&
                    values[key]?.let { ReplicaSwitchStage.decode(it) } == expected &&
                    frozen == expected.transition(frozen.phase, frozen.archive, frozen.target, frozen.catchup,
                        frozen.release, frozen.completion)) { "SYNC_REPLICA_STAGE_CHANGED" }
                verifyReplicaAudits(values, expected)
                val actual = syncAccess(values)
                val authenticated = actual?.let { if (expected.destinationPending)
                    expected.destination(it.session.authentication)
                    else frozen.original.copy(session = frozen.original.session.copy(authentication = it.session.authentication)) }
                check(values[INSTALLATION_ID_KEY] == frozen.installationId &&
                    actual != null && actual.session.authentication.userId == frozen.accountId && actual == authenticated &&
                    (frozen.phase in setOf(ReplicaSwitchPhase.ABORTED, ReplicaSwitchPhase.SQL_COMMITTED,
                        ReplicaSwitchPhase.DESTINATION_BOUND, ReplicaSwitchPhase.CATCHUP_COMMITTING,
                        ReplicaSwitchPhase.CATCHUP_BOUND, ReplicaSwitchPhase.RELEASING,
                        ReplicaSwitchPhase.COMPLETED) || actual == frozen.original)) {
                    "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
                }
                if (frozen.phase == ReplicaSwitchPhase.DESTINATION_BOUND) {
                    val target = requireNotNull(frozen.target)
                    values[SERVER_INSTANCE_ID_KEY] = frozen.serverInstanceId
                    values[SYNC_EPOCH_KEY] = frozen.newEpoch
                    values[SYNC_DEVICE_ID_KEY] = target.deviceId
                    values[DEVICE_CAPABILITIES_KEY] = target.capabilities.toSet()
                    values[DEVICE_CAPABILITIES_KNOWN_KEY] = true
                    values[DEVICE_PRIMARY_EDITOR_KEY] = target.isPrimaryEditor
                    values[DEVICE_CAPABILITY_REVISION_KEY] = target.capabilityRevision.toString()
                    values[DEVICE_CAPABILITIES_DEVICE_KEY] = target.deviceId
                    values[SYNC_CURSOR_KEY] = frozen.candidateCursor.toString()
                    values[SYNC_BOOTSTRAPPED_KEY] = true
                    check(syncAccess(values) == frozen.destination(actual.session.authentication)) {
                        "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
                    }
                }
                if (frozen.phase == ReplicaSwitchPhase.CATCHUP_COMMITTING) {
                    check(values[SYNC_CURSOR_KEY] == expected.publishedSnapshot.cursor.toString() &&
                        values[SYNC_BOOTSTRAPPED_KEY] == true &&
                        requireNotNull(frozen.catchup).authenticationGeneration == actual.session.authentication.generation) {
                        "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
                    }
                    val audit = ReplicaSwitchStage.catchupKey(frozen)
                    check(values[audit] == null) { "SYNC_REPLICA_STAGE_CHANGED" }
                    values[audit] = ReplicaSwitchStage.encodeCatchup(frozen)
                }
                if (frozen.phase == ReplicaSwitchPhase.CATCHUP_BOUND) {
                    check(values[SYNC_CURSOR_KEY] == expected.publishedSnapshot.cursor.toString() &&
                        values[SYNC_BOOTSTRAPPED_KEY] == true) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
                    values[SYNC_CURSOR_KEY] = frozen.publishedSnapshot.cursor.toString()
                    val target = frozen.publishedSnapshot.target
                    values[DEVICE_CAPABILITIES_KEY] = target.capabilities.toSet()
                    values[DEVICE_PRIMARY_EDITOR_KEY] = target.isPrimaryEditor
                    values[DEVICE_CAPABILITY_REVISION_KEY] = target.capabilityRevision.toString()
                    check(syncAccess(values) == frozen.destination(actual.session.authentication)) {
                        "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
                    }
                }
                if (frozen.phase in setOf(ReplicaSwitchPhase.RELEASING, ReplicaSwitchPhase.COMPLETED)) {
                    check(values[SYNC_CURSOR_KEY] == expected.publishedSnapshot.cursor.toString() &&
                        values[SYNC_BOOTSTRAPPED_KEY] == true &&
                        values[DEVICE_PRIMARY_EDITOR_KEY] == expected.publishedSnapshot.target.isPrimaryEditor) {
                        "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
                    }
                    val proof = requireNotNull(frozen.release)
                    if (frozen.phase == ReplicaSwitchPhase.RELEASING) {
                        check(proof.authenticationGeneration == actual.session.authentication.generation &&
                            values[ReplicaSwitchStage.releaseKey(frozen)] == null) { "SYNC_REPLICA_STAGE_CHANGED" }
                        values[ReplicaSwitchStage.releaseKey(frozen)] = ReplicaSwitchStage.encodeRelease(frozen)
                    } else {
                        check(requireNotNull(frozen.completion).authenticationGeneration == actual.session.authentication.generation) {
                            "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED"
                        }
                        val witness = ReplicaSwitchStage.completionKey(frozen.recoveryId)
                        check(values[witness] == null) { "SYNC_REPLICA_STAGE_CHANGED" }
                        values[witness] = ReplicaSwitchStage.completionWitness(raw)
                    }
                }
            }
            var bytes = raw.toByteArray(Charsets.UTF_8).size.toLong()
            values.asMap().forEach { (other, value) ->
                if (ReplicaSwitchStage.isHistoryKey(other.name) && other.name != key.name) {
                    check(value is String) { "SYNC_REPLICA_STAGE_INVALID" }
                    bytes += value.toByteArray(Charsets.UTF_8).size
                }
            }
            check(bytes <= ReplicaSwitchStage.HISTORY_LIMIT) { "SYNC_REPLICA_STAGE_HISTORY_LIMIT" }
            values[key] = raw
            if (frozen.terminal) values.remove(ReplicaSwitchGuard.POINTER)
            else values[ReplicaSwitchGuard.POINTER] = frozen.recoveryId
        }
        return frozen
    }

    /** Cold proof of ALL published metadata, not only an epoch or a decoded phase name.
     * Same-owner fresh authentication can finish committed recovery without restoring old JWTs.
     */
    internal suspend fun replicaDestinationAccess(stage: ReplicaSwitchStage): LocalSyncAccess = withContext(Dispatchers.IO) {
        require(stage.destinationPending)
        val values = dataStore.data.first()
        val actual = requireNotNull(syncAccess(values)) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
        val target = stage.publishedSnapshot.target
        verifyReplicaAudits(values, stage)
        check(values[ReplicaSwitchGuard.POINTER] == stage.recoveryId &&
            values[ReplicaSwitchStage.key(stage.recoveryId)]?.let { ReplicaSwitchStage.decode(it) } == stage &&
            actual.session.authentication.userId == stage.accountId && actual == stage.destination(actual.session.authentication) &&
            values[INSTALLATION_ID_KEY] == stage.installationId &&
            values[SYNC_CURSOR_KEY] == stage.publishedSnapshot.cursor.toString() && values[SYNC_BOOTSTRAPPED_KEY] == true &&
            values[DEVICE_PRIMARY_EDITOR_KEY] == target.isPrimaryEditor) { "SYNC_REPLICA_STAGE_AUTHORITY_CHANGED" }
        actual
    }

    private fun verifyReplicaAudits(values: Preferences, stage: ReplicaSwitchStage) {
        check(stage.catchup == null || values[ReplicaSwitchStage.catchupKey(stage)] == ReplicaSwitchStage.encodeCatchup(stage)) {
            "SYNC_REPLICA_CATCHUP_INTENT_CHANGED"
        }
        check(stage.release == null || values[ReplicaSwitchStage.releaseKey(stage)] == ReplicaSwitchStage.encodeRelease(stage)) {
            "SYNC_REPLICA_RELEASE_INTENT_CHANGED"
        }
        if (stage.phase == ReplicaSwitchPhase.COMPLETED) {
            val raw = requireNotNull(values[ReplicaSwitchStage.key(stage.recoveryId)]) { "SYNC_REPLICA_STAGE_MISSING" }
            check(values[ReplicaSwitchStage.completionKey(stage.recoveryId)] == ReplicaSwitchStage.completionWitness(raw)) {
                "SYNC_REPLICA_COMPLETION_WITNESS_CHANGED"
            }
        }
    }

    private fun coreWriteAccess(preferences: Preferences): LocalCoreWriteAccess? {
        if (ReplicaSwitchGuard.blocked(preferences)) return null
        return observationCoreAccess(preferences)
    }

    private fun observationCoreAccess(preferences: Preferences): LocalCoreWriteAccess? {
        val authentication = snapshot(preferences)?.session ?: return null
        if (preferences[SYNC_ACCOUNT_ID_KEY] != authentication.userId ||
            !com.dayforge.domain.model.isContractUuid(authentication.userId) ||
            !com.dayforge.domain.model.isContractUuid(authentication.generation)) return null
        val server = preferences[SERVER_INSTANCE_ID_KEY]
        val epoch = preferences[SYNC_EPOCH_KEY]
        if ((server == null) != (epoch == null) ||
            (server != null && (!com.dayforge.domain.model.isContractUuid(server) ||
                !com.dayforge.domain.model.isContractUuid(requireNotNull(epoch))))) return null
        val device = preferences[SYNC_DEVICE_ID_KEY]
        if (device != null && !com.dayforge.domain.model.isContractUuid(device)) return null
        return LocalCoreWriteAccess(LocalDataSession(authentication, server, epoch),
            if (preferences[DEVICE_CAPABILITIES_KNOWN_KEY] == true)
                (preferences[DEVICE_CAPABILITIES_KEY] ?: emptySet()).toSet() else null, device)
    }

    internal suspend fun localSyncAccess(): LocalSyncAccess? = withContext(Dispatchers.IO) { syncAccess(dataStore.data.first()) }

    /** Fresh registered authority proof without exporting or decrypting unused refresh credentials.
     * Every call reads one current snapshot and decrypts its current access ciphertext once.
     * No successful result, plaintext, or ciphertext is cached across calls or account transitions.
     */
    internal suspend fun isCurrentSyncAuthority(expected: LocalSyncAccess): Boolean = withContext(Dispatchers.IO) {
        val preferences = dataStore.data.first()
        syncAccessMetadata(preferences) == expected &&
            preferences[ACCESS_TOKEN_KEY]?.let(tokenCipher::decrypt) != null
    }

    /** Discovery supplies the explicit replica, but never rewrites the persisted owner/replica. */
    internal suspend fun registrationSyncAccess(server: String, epoch: String): LocalSyncAccess? = withContext(Dispatchers.IO) {
        val preferences = dataStore.data.first()
        val credentials = snapshot(preferences) ?: return@withContext null
        val expected = LocalSyncAccess(LocalDataSession(credentials.session, server, epoch))
        expected.takeIf { registrationMatches(preferences, it) }
    }

    /** The refresh and original request each recheck ONE persisted authority snapshot. */
    internal suspend fun syncAuthenticationSnapshot(expected: LocalSyncAccess): AuthenticationSnapshot? = withContext(Dispatchers.IO) {
        val preferences = dataStore.data.first()
        if (!syncMatches(preferences, expected)) return@withContext null
        snapshot(preferences)?.takeIf { it.session == expected.session.authentication }
    }

    // Metadata only: each caller must ALSO authenticate snapshot()/matches() from these same values.
    private fun syncMatches(preferences: Preferences, expected: LocalSyncAccess): Boolean =
        if (expected.deviceId == null) registrationMatches(preferences, expected) else syncAccessMetadata(preferences) == expected

    private fun registrationMatches(preferences: Preferences, expected: LocalSyncAccess): Boolean {
        if (preferences[SYNC_ACCOUNT_ID_KEY] != expected.session.authentication.userId ||
            preferences[USER_ID_KEY] != expected.session.authentication.userId ||
            preferences[AUTH_SESSION_KEY] != expected.session.authentication.generation) return false
        val server = preferences[SERVER_INSTANCE_ID_KEY]
        val epoch = preferences[SYNC_EPOCH_KEY]
        return (server == null && epoch == null) ||
            (server == expected.session.serverInstanceId && epoch == expected.session.syncEpoch)
    }

    private fun syncAccess(preferences: Preferences): LocalSyncAccess? {
        val access = iconAccess(preferences) ?: return null
        return LocalSyncAccess(access.session, access.deviceId, access.capabilityRevision,
            requireNotNull(preferences[DEVICE_CAPABILITIES_KEY]).toSet())
    }

    /** Private parser only; never expose a metadata-only value as an authenticated access grant. */
    private fun syncAccessMetadata(preferences: Preferences): LocalSyncAccess? {
        val access = iconAccessMetadata(preferences) ?: return null
        return LocalSyncAccess(access.session, access.deviceId, access.capabilityRevision,
            requireNotNull(preferences[DEVICE_CAPABILITIES_KEY]).toSet())
    }

    /** Canonical non-secret lifecycle signal; Keystore work never runs on Main. */
    internal val iconAccessChanges: Flow<LocalIconAccess?> = dataStore.data.map {
        if (ReplicaSwitchGuard.blocked(it)) null else iconAccess(it)
    }
        .distinctUntilChanged().flowOn(Dispatchers.IO)

    /** Bind credentials and all material permissions to ONE actual persisted snapshot. */
    internal suspend fun iconAuthenticationSnapshot(expected: LocalIconAccess): AuthenticationSnapshot? {
        val preferences = dataStore.data.first()
        if (iconAccessMetadata(preferences) != expected) return null
        return snapshot(preferences)?.takeIf { it.session == expected.session.authentication }
    }

    private fun iconAccess(preferences: Preferences): LocalIconAccess? {
        val access = iconAccessMetadata(preferences) ?: return null
        // Local ownership still proves the CURRENT access ciphertext on every check. Refresh
        // credentials are not part of this snapshot (a missing/invalid refresh never denied it).
        // Do not repeat hardware Keystore decryption of that unused value for each image guard.
        preferences[ACCESS_TOKEN_KEY]?.let(tokenCipher::decrypt) ?: return null
        return access
    }

    private fun iconAccessMetadata(preferences: Preferences): LocalIconAccess? {
        val userId = preferences[USER_ID_KEY] ?: return null
        val authentication = AuthenticationSession(userId, preferences[AUTH_SESSION_KEY] ?: "legacy")
        if (preferences[SYNC_ACCOUNT_ID_KEY] != authentication.userId ||
            preferences[DEVICE_CAPABILITIES_KNOWN_KEY] != true) return null
        val capabilities = preferences[DEVICE_CAPABILITIES_KEY] ?: return null
        if ("sync.read" !in capabilities) return null
        val revision = preferences[DEVICE_CAPABILITY_REVISION_KEY]?.toIntOrNull() ?: return null
        if (revision <= 0) return null
        val server = preferences[SERVER_INSTANCE_ID_KEY] ?: return null
        val epoch = preferences[SYNC_EPOCH_KEY] ?: return null
        val device = preferences[SYNC_DEVICE_ID_KEY] ?: return null
        if (preferences[DEVICE_CAPABILITIES_DEVICE_KEY] != device) return null
        if (listOf(authentication.userId, authentication.generation, server, epoch, device)
                .any { !com.dayforge.domain.model.isContractUuid(it) }) return null
        return LocalIconAccess(
            LocalDataSession(authentication, server, epoch), device, revision,
            "structure.write" in capabilities
        )
    }

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
    ): Boolean = persistRefreshedTokens(expected, accessToken, refreshToken, username, userId, isAdmin)

    /** Core v5 refresh cannot publish old-origin credentials after a replica/device change. */
    internal suspend fun saveRefreshedSyncTokens(
        expected: AuthenticationSnapshot, context: LocalSyncAccess, accessToken: String, refreshToken: String,
        username: String, userId: String, isAdmin: Boolean
    ): Boolean = persistRefreshedTokens(expected, accessToken, refreshToken, username, userId, isAdmin, context)

    private suspend fun persistRefreshedTokens(
        expected: AuthenticationSnapshot, accessToken: String, refreshToken: String, username: String,
        userId: String, isAdmin: Boolean, syncContext: LocalSyncAccess? = null
    ): Boolean {
        if (userId != expected.session.userId) return false
        var saved = false
        editPreferences { preferences ->
            val authorityMatches = syncContext == null || syncMatches(preferences, syncContext)
            if (authorityMatches && matches(preferences, expected)) {
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
    suspend fun clearRejectedRefresh(expected: AuthenticationSnapshot) = clearRejectedRefresh(expected, null)

    internal suspend fun clearRejectedSyncRefresh(expected: AuthenticationSnapshot, context: LocalSyncAccess) =
        clearRejectedRefresh(expected, context)

    private suspend fun clearRejectedRefresh(expected: AuthenticationSnapshot, context: LocalSyncAccess?) {
        editPreferences { preferences ->
            if ((context == null || syncMatches(preferences, context)) && matches(preferences, expected)) {
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
        editPreferences { preferences ->
            preferences[ACCESS_TOKEN_KEY]?.let {
                preferences[ACCESS_TOKEN_KEY] = tokenCipher.encrypt(it)
            }
            preferences[REFRESH_TOKEN_KEY]?.let {
                preferences[REFRESH_TOKEN_KEY] = tokenCipher.encrypt(it)
            }
        }
    }

    /** Device controls must prove the registered installation, not allocate a replacement. */
    internal suspend fun registeredInstallationId(): String? = dataStore.data.first()[INSTALLATION_ID_KEY]

    suspend fun getOrCreateInstallationId(): String {
        // Concurrent callers must return the same committed installation, not two UUIDs.
        val saved = editPreferences { preferences ->
            if (preferences[INSTALLATION_ID_KEY] == null) {
                ReplicaSwitchGuard.requireOpen(preferences)
                preferences[INSTALLATION_ID_KEY] = UUID.randomUUID().toString()
            }
        }
        return requireNotNull(saved[INSTALLATION_ID_KEY])
    }

    /** One conditional persistence boundary; never publish a late registration into new authority. */
    internal suspend fun saveNextRegistration(
        expectedCore: LocalCoreWriteAccess, expectedSync: LocalSyncAccess?, installation: String,
        server: String, epoch: String, device: String, capabilities: Set<String>,
        primary: Boolean, revision: Int
    ): LocalSyncAccess {
        require(listOf(installation, server, epoch, device).all { com.dayforge.domain.model.isContractUuid(it) })
        require(revision > 0 && "sync.read" in capabilities && capabilities.all(String::isNotBlank))
        require(expectedCore.capturedDeviceId == null || expectedCore.capturedDeviceId == device) { "SYNC_DEVICE_CHANGED" }
        val session = expectedCore.session.copy(serverInstanceId = server, syncEpoch = epoch)
        require(expectedCore.session.serverInstanceId == null || expectedCore.session == session) { "SYNC_REPLICA_CHANGED" }
        val result = LocalSyncAccess(session, device, revision, capabilities.toSet())
        editPreferences { preferences ->
            check(coreWriteAccess(preferences) == expectedCore && syncAccess(preferences) == expectedSync &&
                preferences[INSTALLATION_ID_KEY] == installation) { "SYNC_REGISTRATION_AUTHORITY_CHANGED" }
            preferences[SERVER_INSTANCE_ID_KEY] = server
            preferences[SYNC_EPOCH_KEY] = epoch
            preferences[SYNC_DEVICE_ID_KEY] = device
            preferences[DEVICE_CAPABILITIES_KEY] = result.capabilities
            preferences[DEVICE_CAPABILITIES_KNOWN_KEY] = true
            preferences[DEVICE_PRIMARY_EDITOR_KEY] = primary
            preferences[DEVICE_CAPABILITY_REVISION_KEY] = revision.toString()
            preferences[DEVICE_CAPABILITIES_DEVICE_KEY] = device
        }
        return result
    }

    /** Reset device/cursor state when the authenticated account changes. */
    suspend fun prepareSyncAccount(accountId: String) {
        editPreferences { preferences ->
            prepareSyncAccount(preferences, accountId)
        }
    }

    private fun prepareSyncAccount(preferences: MutablePreferences, accountId: String) {
        if (preferences[SYNC_ACCOUNT_ID_KEY] != accountId) {
            ReplicaSwitchGuard.requireOpen(preferences)
            preferences[SYNC_ACCOUNT_ID_KEY] = accountId
            preferences.remove(SYNC_DEVICE_ID_KEY)
            preferences.remove(SYNC_CURSOR_KEY)
            preferences.remove(SYNC_BOOTSTRAPPED_KEY)
            preferences.remove(SERVER_INSTANCE_ID_KEY)
            preferences.remove(SYNC_EPOCH_KEY)
            clearDeviceCapabilities(preferences)
        }
    }

    suspend fun saveSyncDeviceId(deviceId: String) {
        editPreferences { ReplicaSwitchGuard.requireOpen(it); it[SYNC_DEVICE_ID_KEY] = deviceId }
    }

    suspend fun saveDeviceRegistration(
        deviceId: String,
        capabilities: Set<String>,
        isPrimaryEditor: Boolean,
        capabilityRevision: Int
    ) {
        editPreferences { preferences ->
            ReplicaSwitchGuard.requireOpen(preferences)
            preferences[SYNC_DEVICE_ID_KEY] = deviceId
            if (capabilities.isNotEmpty()) {
                preferences[DEVICE_CAPABILITIES_KEY] = capabilities
                preferences[DEVICE_CAPABILITIES_KNOWN_KEY] = true
                preferences[DEVICE_PRIMARY_EDITOR_KEY] = isPrimaryEditor
                preferences[DEVICE_CAPABILITY_REVISION_KEY] = capabilityRevision.toString()
                preferences[DEVICE_CAPABILITIES_DEVICE_KEY] = deviceId
            } else {
                // Preserve v4's legacy semantics, but do not authorize new icon access from stale capabilities.
                preferences.remove(DEVICE_CAPABILITIES_DEVICE_KEY)
            }
        }
    }

    /** Fail closed after the server rejects a structural operation for this device. */
    suspend fun markStructuralEditingDenied() {
        editPreferences { preferences ->
            ReplicaSwitchGuard.requireOpen(preferences)
            val capabilities = (preferences[DEVICE_CAPABILITIES_KEY] ?: emptySet()).toMutableSet()
            capabilities.remove("structure.write")
            preferences[DEVICE_CAPABILITIES_KEY] = capabilities
            preferences[DEVICE_CAPABILITIES_KNOWN_KEY] = true
            preferences[DEVICE_PRIMARY_EDITOR_KEY] = false
        }
    }

    suspend fun saveServerIdentity(serverInstanceId: String, syncEpoch: String) {
        editPreferences {
            ReplicaSwitchGuard.requireOpen(it)
            it[SERVER_INSTANCE_ID_KEY] = serverInstanceId
            it[SYNC_EPOCH_KEY] = syncEpoch
        }
    }

    /** Start a clean replica after the same server reports a new database epoch. */
    suspend fun resetReplicaForEpoch(serverInstanceId: String, syncEpoch: String) {
        editPreferences { preferences ->
            ReplicaSwitchGuard.requireOpen(preferences)
            preferences[SERVER_INSTANCE_ID_KEY] = serverInstanceId
            preferences[SYNC_EPOCH_KEY] = syncEpoch
            preferences.remove(SYNC_DEVICE_ID_KEY)
            preferences.remove(SYNC_CURSOR_KEY)
            preferences.remove(SYNC_BOOTSTRAPPED_KEY)
            clearDeviceCapabilities(preferences)
        }
    }

    suspend fun saveSyncCursor(cursor: Long, bootstrapped: Boolean = true) {
        editPreferences {
            ReplicaSwitchGuard.requireOpen(it)
            it[SYNC_CURSOR_KEY] = cursor.toString()
            if (bootstrapped) it[SYNC_BOOTSTRAPPED_KEY] = true
        }
    }

    /** Force the next safe sync to rebuild the local cache from server state. */
    suspend fun requireSyncBootstrap() {
        editPreferences { ReplicaSwitchGuard.requireOpen(it); it[SYNC_BOOTSTRAPPED_KEY] = false }
    }

    /** Clear only credentials after refresh failure, retaining local-data ownership. */
    suspend fun clearAuthenticationTokens() {
        editPreferences { preferences ->
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
        editPreferences { preferences ->
            ReplicaSwitchGuard.requireOpen(preferences)
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
        editPreferences { preferences ->
            preferences[USER_EMAIL_KEY] = email
        }
    }

    /**
     * Clears all stored tokens.
     * Called on logout or when token refresh fails.
     */
    suspend fun clearTokens() {
        editPreferences { preferences ->
            ReplicaSwitchGuard.requireOpen(preferences)
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
        preferences.remove(DEVICE_CAPABILITIES_DEVICE_KEY)
    }

}
