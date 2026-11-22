package com.dayforge.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Test-only owners. The original Host drains before its independent DataStore actor;
 * files/alias/current are released only after both real drains and database cleanup succeed. */
internal class LegacySessionHostPreferences : TestRule {
    internal lateinit var store: LegacyGenerationStore
    internal lateinit var cipher: LegacyGenerationCipher
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var alias: String
    private var epochs: PhysicalDatabaseEpochFixture? = null
    private val ownerLock = Any()
    private var methodOwner: Job? = null
    private var cleanupStarted = false
    private var methodDrained = false
    private var actorDrained = false
    private var cleanupFailure: Throwable? = null
    private var originalMethodFailure: Throwable? = null
    private var published = false
    private var complete = false

    internal fun bind(fixture: PhysicalDatabaseEpochFixture) {
        check(epochs == null)
        epochs = fixture
        val previous = fixture.beforeCleanup
        fixture.beforeCleanup = { drainMethod(); previous?.invoke() }
        check(fixture.beforeDatabaseDeletion == null)
        fixture.beforeDatabaseDeletion = { stopActor() }
    }

    internal fun ownedTest(block: suspend CoroutineScope.() -> Unit): Unit = try {
        runBlocking<Unit> {
            synchronized(ownerLock) {
                check(!cleanupStarted && methodOwner == null) { "LEGACY_HOST_METHOD_ADMISSION_CLOSED" }
                methodOwner = coroutineContext[Job]!!
            }
            block()
        }
    } catch (error: Throwable) { originalMethodFailure = error; throw error }

    /** Also entered by UI @After before closing consumers; repeating a failed drain cannot
     * turn it into permission to clear the files or release the scheduler isolation. */
    internal fun drainMethod() = runBlocking<Unit> {
        cleanupFailure?.let { throw it }
        try {
            val owner = synchronized(ownerLock) { cleanupStarted = true; methodOwner }
            if (!methodDrained) {
                if (owner != null) withTimeout(5_000) { owner.cancelAndJoin() }
                check(owner == null || owner.isCompleted) { "LEGACY_HOST_METHOD_NOT_DRAINED" }
                methodDrained = true
            }
        } catch (error: Throwable) { preserve(error); throw error }
    }

    internal fun preserve(error: Throwable) {
        if (cleanupFailure == null) cleanupFailure = error
        PhysicalDatabaseRule.preserveProcessCleanupFailure(error)
    }
    internal fun requireCleanupCompleted() {
        cleanupFailure?.let { throw it }
        check(complete) { "LEGACY_HOST_CLEANUP_NOT_COMPLETED" }
    }

    private suspend fun stopActor() {
        cleanupFailure?.let { throw it }
        try {
            if (!actorDrained && ::scope.isInitialized) {
                val actor = scope.coroutineContext[Job]!!
                withTimeout(5_000) { actor.cancelAndJoin() }
                check(actor.isCompleted) { "LEGACY_HOST_ACTOR_NOT_DRAINED" }
                actorDrained = true
            }
        } catch (error: Throwable) { preserve(error); throw error }
    }

    private fun before() = runBlocking<Unit> {
        PhysicalDatabaseRule.requireProcessCleanupClear()
        check(current == null) { "PREVIOUS_LEGACY_HOST_PREFERENCES_NOT_RELEASED" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        check(app.packageName == "com.dayforge.testbed")
        val nonce = UUID.randomUUID().toString()
        file = File(app.filesDir, "legacy-host-$nonce.preferences_pb")
        alias = "dayforge.test.legacy-host.$nonce"
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = LegacyGenerationStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        cipher = LegacyGenerationCipher(alias)
        withContext(Dispatchers.IO) { seedLegacyGenerationPreferences(store, cipher) }
        store.committedChanges.set(0)
        current = this@LegacySessionHostPreferences
        published = true
    }
    private fun after() = runBlocking<Unit> {
        cleanupFailure?.let { throw it }
        check(current == null || current === this@LegacySessionHostPreferences) {
            "PREVIOUS_LEGACY_HOST_PREFERENCES_NOT_RELEASED"
        }
        drainMethod()
        // If an inner fixture failed, its retained Room configuration/DB is still owned.
        // Do not cancel its preferences actor or erase its credentials underneath it.
        if (published) check(requireNotNull(epochs).cleanupCompleted) { "LEGACY_HOST_DATABASE_CLEANUP_NOT_COMPLETED" }
        stopActor()
        if (::file.isInitialized) check(!file.exists() || file.delete())
        if (::alias.isInitialized) withContext(Dispatchers.IO) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
        if (published) {
            check(current === this@LegacySessionHostPreferences)
            current = null
        }
        complete = true
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            var failure: Throwable? = null
            try { before(); base.evaluate() } catch (error: Throwable) {
                failure = preserveLegacySessionMethodFailure(originalMethodFailure, error)
            }
            try { after() } catch (error: Throwable) {
                preserve(error)
                failure = preserveLegacySessionMethodFailure(failure, error)
            }
            failure?.let { throw it }
        }
    }
    companion object {
        @Volatile private var current: LegacySessionHostPreferences? = null
        internal fun active() = checkNotNull(current)
    }
}
