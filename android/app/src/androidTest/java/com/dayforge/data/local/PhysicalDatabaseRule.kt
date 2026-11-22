package com.dayforge.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Uses the production on-disk builder, migrations and triggers, isolated to the test APK. */
class PhysicalDatabaseRule internal constructor(private val cleanupFence: CleanupFence) : ExternalResource() {
    constructor() : this(processCleanupFence)

    /** Process-local refusal only; it creates no work and has no reset API.
     * Only a new instrumentation process may resume after unknown fixture cleanup. Negative
     * lifecycle tests inject their own fence, then dispose their own resources explicitly.
     */
    internal class CleanupFence {
        @Volatile private var failure: Throwable? = null
        @Synchronized fun preserve(error: Throwable) { if (failure == null) failure = error }
        fun requireClear() {
            failure?.let { throw IllegalStateException("PHYSICAL_DATABASE_CLEANUP_BLOCKED", it) }
        }
    }
    companion object {
        private val processCleanupFence = CleanupFence()
        /** Negative-test disposal failed for real: block even a fresh injected test fence. */
        internal fun preserveProcessCleanupFailure(error: Throwable) { processCleanupFence.preserve(error) }
        /** Read-only admission shared by the real-Hilt fixture before it touches any file. */
        internal fun requireProcessCleanupClear() { processCleanupFence.requireClear() }
    }

    private val recoveryLock = Any()
    private val recoveryDrain = Mutex()
    private var recoveryOwner: Job? = null
    private var cleanupStarted = false
    private var ownerDrained = false
    private val afterClose = mutableListOf<() -> Unit>()

    /** Called first inside the original method's runBlocking, never a detached replacement. */
    internal fun ownRecoveryMethod(owner: Job) = synchronized(recoveryLock) {
        cleanupFence.requireClear()
        check(!cleanupStarted && recoveryOwner == null) { "RECOVERY_TEST_OWNER_ADMISSION_CLOSED" }
        recoveryOwner = owner
    }

    /** Every inherited @After enters here: JUnit continues with later @After after failures.
     * Registration and sealing share one lock, so an unstarted late method cannot escape.
     * Keep the exact Job even when interrupted runBlocking returns before its children exit.
     */
    internal suspend fun recoveryCleanup(block: suspend () -> Unit) {
        cleanupFence.requireClear()
        try {
            recoveryDrain.withLock {
                cleanupFence.requireClear()
                val owner = synchronized(recoveryLock) { cleanupStarted = true; recoveryOwner }
                if (!ownerDrained) {
                    if (owner != null) withTimeout(5_000) { owner.cancelAndJoin() }
                    check(owner == null || owner.isCompleted) { "RECOVERY_TEST_OWNER_NOT_DRAINED" }
                    ownerDrained = true
                }
            }
            cleanupFence.requireClear()
            block()
        } catch (error: Throwable) {
            cleanupFence.preserve(error)
            throw error
        }
    }

    internal val usesRecoveryCleanup: Boolean get() = synchronized(recoveryLock) { recoveryOwner != null }

    /** Original non-recovery fixtures keep their cleanup order. Recovery files are deleted
     * only after ALL owned resources and the actual main database have closed successfully.
     */
    internal fun afterDatabaseClose(block: () -> Unit) {
        cleanupFence.requireClear()
        if (usesRecoveryCleanup) afterClose += block else block()
    }

    // Isolated G0 can supply a wrapped open helper without changing the production builder.
    var isolatedBuilder: ((Context) -> HabitDatabase)? = null
    // Optional per-fixture counter; receives SQL but must never log SQL or bind arguments.
    var queryCallback: androidx.room.RoomDatabase.QueryCallback? = null
    private lateinit var context: Context
    private var previousHosts: List<DatabaseRuntimeHost> = emptyList()
    lateinit var database: HabitDatabase
        private set

    override fun before() {
        // Check before touching the Provider or any file; after() is not run if before() fails.
        // An injected negative-case fence cannot bypass failed outer-harness disposal.
        if (cleanupFence !== processCleanupFence) processCleanupFence.requireClear()
        cleanupFence.requireClear()
        try {
            previousHosts = DatabaseRuntimeHosts.snapshot()
            check(previousHosts.all { it.lifecycleEnded }) { "A prior test left an active Host; refusing to delete its database" }
            context = ApplicationProvider.getApplicationContext()
            check(context.packageName == "com.dayforge.testbed")
            HabitDatabaseProvider.clearInstanceForTesting()
            context.deleteDatabase("habit_database")
            database = isolatedBuilder?.invoke(context)?.also(HabitDatabaseProvider::setInstanceForTesting)
                ?: queryCallback?.let { HabitDatabaseProvider.build(context, it).also(HabitDatabaseProvider::setInstanceForTesting) }
                ?: HabitDatabaseProvider.getInstance(context)
        } catch (error: Throwable) {
            cleanupFence.preserve(error)
            throw error
        }
    }

    fun reopen(): HabitDatabase {
        database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        database = isolatedBuilder?.invoke(context)?.also(HabitDatabaseProvider::setInstanceForTesting)
            ?: HabitDatabaseProvider.getInstance(context)
        return database
    }

    /** Test-only full SQLite image, never a live database/WAL copy or production backup API. */
    fun snapshotClosedDatabase(): ByteArray {
        check(context.packageName == "com.dayforge.testbed" && !database.inTransaction())
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            check(it.moveToFirst() && it.getInt(0) == 0 && it.getInt(1) == it.getInt(2))
        }
        database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        return try {
            val file = context.getDatabasePath("habit_database")
            val wal = java.io.File(file.path + "-wal")
            check(!wal.exists() || wal.length() == 0L)
            check(file.isFile && file.length() in 1..67_108_864L)
            file.readBytes()
        } finally { database = HabitDatabaseProvider.getInstance(context) }
    }

    /** Replace only this isolated fixture's closed DB; credentials/account binding stay untouched. */
    fun restoreClosedDatabase(image: ByteArray) {
        check(context.packageName == "com.dayforge.testbed" && !database.inTransaction())
        require(image.isNotEmpty() && image.size <= 67_108_864)
        database.close()
        HabitDatabaseProvider.clearInstanceForTesting()
        try {
            val file = context.getDatabasePath("habit_database")
            check(context.deleteDatabase("habit_database") || !file.exists())
            check(file.parentFile!!.isDirectory)
            file.writeBytes(image)
        } finally { database = HabitDatabaseProvider.getInstance(context) }
    }

    /** The opt-in owner and its explicitly registered independent observers have already
     * joined. This existing native boundary additionally rejects unknown END; it grants no
     * business permission and never stands in for joining those Jobs.
     */
    private suspend fun closeRecoveryDatabase() {
        val runtime = requireNotNull(CountTrustRuntime.forDatabase(database))
        check(!runtime.hasUnknownGraphBoundary()) { "RECOVERY_TEST_NATIVE_END_UNKNOWN" }
        if (runtime.isGraphCloseConfirmed(database)) return // A drained Host performed the exact close.
        val unopened = runtime.trySealUnopenedGraphClose(database)
        if (unopened != null) {
            database.close()
            runtime.requireUnopenedGraphClosed(database, unopened)
            return
        }
        val proof = withTimeout(5_000) {
            var sealed: CountTrustOpenHelperFactory.GraphDrain.Ready? = null
            while (sealed == null) {
                check(!runtime.hasUnknownGraphBoundary()) { "RECOVERY_TEST_NATIVE_END_UNKNOWN" }
                when (val observed = runtime.captureGraphDrain(database)) {
                    is CountTrustOpenHelperFactory.GraphDrain.Ready ->
                        if (runtime.trySealGraphClose(database, observed.witness, knownOnly = true)) sealed = observed
                    CountTrustOpenHelperFactory.GraphDrain.Unknown -> error("RECOVERY_TEST_NATIVE_END_UNKNOWN")
                    CountTrustOpenHelperFactory.GraphDrain.Busy -> Unit
                }
                if (sealed == null) delay(10)
            }
            requireNotNull(sealed)
        }
        database.close() // A failed first Room.close must never be retried as if it confirmed close.
        runtime.requireGraphClosed(database, proof.witness)
    }

    override fun after() = runBlocking {
        recoveryCleanup {
            var failure: Throwable? = null
            // Non-creating inventory; only Hosts this fixture actually created are drained.
            val created = DatabaseRuntimeHosts.snapshot().filter { candidate -> previousHosts.none { it === candidate } }
            for (host in created) try {
                shutdownTestHost(host)
            } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
            failure?.let { throw it }
            if (::database.isInitialized) {
                if (usesRecoveryCleanup) closeRecoveryDatabase()
                else database.close()
            }
            // No finally: failed/unknown native close preserves Provider, DB and deferred files.
            afterClose.forEach { it() }
            if (::context.isInitialized && context.packageName == "com.dayforge.testbed") {
                HabitDatabaseProvider.clearInstanceForTesting()
                check(context.deleteDatabase("habit_database") || !context.getDatabasePath("habit_database").exists())
            }
        }
    }
}
