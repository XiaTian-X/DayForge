package com.dayforge.data.local

import android.content.Context
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.repository.ReplicaPresentationRepository
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.runners.model.MultipleFailureException

/** Real old Preferences shape, real disk actor and a per-test Android Keystore alias.
 * Fault hooks surround the actual delegate transform/persistence; they do not mint authority. */
internal class LegacyGenerationStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    val beforeTransform = AtomicReference<(suspend () -> Unit)?>(null)
    val afterTransform = AtomicReference<(suspend () -> Unit)?>(null)
    val afterPersistence = AtomicReference<(suspend () -> Unit)?>(null)
    val committedChanges = AtomicInteger()
    private class GenerationObservation(val transformed: (suspend () -> Unit)?, val persisted: (suspend () -> Unit)?)
    private val generationObservation = AtomicReference<GenerationObservation?>()
    /** Test-only one-shot pair. No raw preferences/credentials leave this wrapper. Original
     * general hooks remain independent, with their unchanged ordering and failure contract. */
    internal fun observeNextGenerationChange(afterTransform: (suspend () -> Unit)? = null,
        afterPersistence: (suspend () -> Unit)? = null) {
        require(afterTransform != null || afterPersistence != null)
        check(generationObservation.compareAndSet(null, GenerationObservation(afterTransform, afterPersistence))) {
            "LEGACY_GENERATION_OBSERVATION_ALREADY_ARMED"
        }
    }
    override val data: Flow<Preferences> get() = delegate.data
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        var changed = false
        var matched: GenerationObservation? = null
        var createdGeneration: String? = null
        val saved = delegate.updateData { previous ->
            beforeTransform.getAndSet(null)?.invoke()
            val next = transform(previous)
            changed = previous.asMap() != next.asMap()
            afterTransform.getAndSet(null)?.invoke()
            if (generationObservation.get() != null) {
                // Raw key name/type inspection is deliberate: the original malformed-key
                // matrix includes an Int/Boolean under this name and must never be cast.
                val key = "auth_session_generation"
                val absent = previous.asMap().keys.none { it.name == key }
                val candidate = next.asMap().entries.singleOrNull { it.key.name == key }?.value as? String
                if (absent && candidate != null && com.dayforge.domain.model.isContractUuid(candidate)) {
                    matched = generationObservation.getAndSet(null)
                    createdGeneration = candidate
                    matched?.transformed?.invoke()
                }
            }
            next
        }
        if (changed) committedChanges.incrementAndGet()
        afterPersistence.getAndSet(null)?.invoke()
        matched?.let { observation ->
            val actual = saved.asMap().entries.singleOrNull { it.key.name == "auth_session_generation" }?.value
            check(actual is String && actual == createdGeneration) { "LEGACY_GENERATION_SAVED_TRANSITION_CHANGED" }
            observation.persisted?.invoke() // The SAME delegate updateData actually returned.
        }
        return saved
    }
}

internal class LegacyGenerationCipher(alias: String) : TokenCipher {
    private val real = AndroidKeystoreTokenCipher(alias)
    val decryptions = AtomicInteger()
    val mainDecryptions = AtomicInteger()
    val beforeDecrypt = AtomicReference<(() -> Unit)?>(null)
    override fun encrypt(value: String): String = real.encrypt(value)
    override fun decrypt(value: String): String? {
        decryptions.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) mainDecryptions.incrementAndGet()
        beforeDecrypt.getAndSet(null)?.invoke()
        return real.decrypt(value)
    }
}

internal suspend fun seedLegacyGenerationPreferences(store: DataStore<Preferences>, cipher: TokenCipher,
    account: String = legacyGenerationId(1), refresh: Boolean = true) {
    val access = cipher.encrypt("synthetic-legacy-access")
    val refreshValue = if (refresh) cipher.encrypt("synthetic-legacy-refresh") else null
    store.edit { values ->
        values.clear()
        values[stringPreferencesKey("user_public_id")] = account
        values[stringPreferencesKey("sync_account_id")] = account
        values[stringPreferencesKey("access_token")] = access
        refreshValue?.let { values[stringPreferencesKey("refresh_token")] = it }
        values[stringPreferencesKey("user_email")] = "synthetic-legacy"
        values[booleanPreferencesKey("is_admin")] = false
        values[stringPreferencesKey("sync_server_instance_id")] = legacyGenerationId(2)
        values[stringPreferencesKey("sync_epoch")] = legacyGenerationId(3)
        values[stringPreferencesKey("sync_device_id")] = legacyGenerationId(4)
        values[stringPreferencesKey("sync_installation_id")] = legacyGenerationId(5)
        values[stringPreferencesKey("sync_device_capabilities_device")] = legacyGenerationId(4)
        values[stringPreferencesKey("sync_device_capability_revision")] = "3"
        values[stringSetPreferencesKey("sync_device_capabilities")] = setOf("sync.read", "structure.write", "facts.append", "timer.control")
        values[booleanPreferencesKey("sync_device_capabilities_known")] = true
        values[booleanPreferencesKey("sync_device_primary_editor")] = true
        values[stringPreferencesKey("sync_cursor")] = "51"
        values[booleanPreferencesKey("sync_bootstrapped")] = true
    }
}

internal fun legacyGenerationId(n: Int) = "ab575000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"

/** Keep the original method failure even when a later rule cleanup throws. JUnit's
 * multiple-failure list is flattened so the original cannot become its own suppressed cause. */
internal fun preserveLegacySessionMethodFailure(original: Throwable?, observed: Throwable): Throwable {
    if (original == null) return observed
    fun append(error: Throwable) {
        if (error === original) return
        if (error is MultipleFailureException) error.failures.forEach(::append)
        else if (original.suppressed.none { it === error }) original.addSuppressed(error)
    }
    append(observed)
    return original
}

abstract class LegacySessionGenerationFixture {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(150)
    private var originalMethodFailure: Throwable? = null
    @get:Rule(order = 1) val failures = TestRule { base: Statement, _: Description -> object : Statement() {
        override fun evaluate() {
            try { base.evaluate() } catch (error: Throwable) {
                throw preserveLegacySessionMethodFailure(originalMethodFailure, error)
            }
        }
    } }
    @get:Rule(order = 2) val storage = PhysicalDatabaseRule()
    internal val sessions = AccountSessionCoordinator()
    internal lateinit var scope: CoroutineScope
    internal lateinit var file: File
    internal lateinit var store: LegacyGenerationStore
    internal lateinit var cipher: LegacyGenerationCipher
    internal lateinit var tokens: TokenManager
    private lateinit var alias: String
    private var actorFailure: Throwable? = null
    internal val generationKey = stringPreferencesKey("auth_session_generation")

    @Before fun openLegacyPreferences() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(app.packageName == "com.dayforge.testbed")
        val nonce = UUID.randomUUID().toString()
        file = File(app.filesDir, "legacy-generation-$nonce.preferences_pb")
        alias = "dayforge.test.legacy-generation.$nonce"
        cipher = LegacyGenerationCipher(alias)
        openStore()
        withContext(Dispatchers.IO) { seedLegacyGenerationPreferences(store, cipher) }
        store.committedChanges.set(0); cipher.decryptions.set(0)
    }
    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = LegacyGenerationStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        tokens = TokenManager(store, cipher)
    }
    /** Register the original test coroutine, including its structured children, before work. */
    protected fun legacyGenerationTest(block: suspend CoroutineScope.() -> Unit): Unit = try {
        runBlocking<Unit> {
            storage.ownRecoveryMethod(coroutineContext[Job]!!)
            block()
        }
    } catch (error: Throwable) { originalMethodFailure = error; throw error }
    private suspend fun stopActor() {
        actorFailure?.let { throw it }
        if (!::scope.isInitialized) return
        try {
            val owner = scope.coroutineContext[Job]!!
            withTimeout(5_000) { owner.cancelAndJoin() }
            check(owner.isCompleted) { "LEGACY_GENERATION_ACTOR_NOT_DRAINED" }
        } catch (error: Throwable) {
            // A later join must not erase a cold-reopen timeout's unknown owner boundary.
            actorFailure = error
            throw error
        }
    }
    internal suspend fun cold() {
        // No second DataStore is admitted unless the first real actor has ended.
        stopActor()
        openStore()
    }
    internal suspend fun prepare(): LegacySessionGenerationResult = sessions.exclusive {
        val expected = tokens.captureLegacySessionPreparationWhileAccountLocked()
        val checked = storage.database.withTransaction {
            ReplicaPresentationRepository(storage.database, tokens, sessions).inspectInTransactionWhileAccountLocked()
        }
        check(checked.problem == null)
        check(!storage.database.inTransaction())
        tokens.prepareLegacySessionGenerationWhileAccountLocked(expected)
    }
    internal suspend fun raw() = store.data.first()
    internal suspend fun generation(): String? = raw().asMap().entries.singleOrNull { it.key.name == generationKey.name }?.value as? String

    @After fun closeLegacyPreferences() = runBlocking<Unit> {
        storage.recoveryCleanup {
            stopActor()
            storage.afterDatabaseClose {
                if (::file.isInitialized) check(!file.exists() || file.delete())
                if (::alias.isInitialized) runBlocking(Dispatchers.IO) {
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
                }
            }
        }
    }
}
