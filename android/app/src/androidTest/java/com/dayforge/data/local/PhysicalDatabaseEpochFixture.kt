package com.dayforge.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.repository.NextReplicaStorageProof
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.rules.ExternalResource

/** Actual Hilt Host/GraphFactory/Room/controlled owner, not a fake swap or Provider replacement.
 * Declare rule before HiltAndroidRule; call connect() only after hilt.inject(). A caller must
 * clear its ViewModelStore and join command jobs before teardown. No raw graph is supplied as
 * a Hilt replacement. Seen graph identities are inspection evidence, never switch authority.
 */
class PhysicalDatabaseEpochFixture : ExternalResource() {
    private lateinit var context: Context
    internal lateinit var host: DatabaseRuntimeHost
        private set
    internal lateinit var tokens: TokenManager
        private set
    internal lateinit var sessions: AccountSessionCoordinator
        private set
    private lateinit var icons: AccountIconController
    private val databases: MutableSet<HabitDatabase> = Collections.newSetFromMap(IdentityHashMap())
    private var connected = false
    /** Optional real-background-work owner proof; runs before tokens or Host can be released. */
    internal var beforeCleanup: (() -> Unit)? = null
    /** Optional independent resource owner: called only after the original Host and icon
     * drains succeed, before deleting any database. Failure preserves all database files. */
    internal var beforeDatabaseDeletion: (suspend () -> Unit)? = null
    internal var cleanupCompleted: Boolean = false
        private set
    internal var queryCallback: androidx.room.RoomDatabase.QueryCallback? = null
    internal var nativeFactory: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory? = null
    private lateinit var roomConfiguration: DatabaseEpochTestRooms.Configuration
    internal val realRoomCreates: Int get() = roomConfiguration.creates.get()
    internal fun createdRoomInspection(): List<HabitDatabase> = roomConfiguration.createdRooms.toList()

    override fun before() {
        PhysicalDatabaseRule.requireProcessCleanupClear()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.dayforge.testbed")
        check(DatabaseRuntimeHosts.snapshot().all { it.lifecycleEnded }) { "A prior test left an active Host" }
        roomConfiguration = DatabaseEpochTestRooms.install(queryCallback, nativeFactory)
        // Existing fixture isolation only. Never clear/repoint HabitDatabaseProvider or kill
        // a process to simulate a runtime owner replacement.
        for (name in listOf("habit_database", com.dayforge.data.appearance.AccountIconDatabase.NAME)) {
            context.deleteDatabase(name)
            check(!context.getDatabasePath(name).exists())
        }
    }

    fun connect() {
        check(!connected)
        val entry = EntryPointAccessors.fromApplication(context, DatabaseEpochFixtureEntryPoint::class.java)
        host = entry.host(); tokens = entry.tokens(); sessions = entry.sessions(); icons = entry.icons()
        connected = true
    }

    internal suspend fun login(accountId: String = "ac490000-0000-4000-8000-000000000001") {
        check(connected)
        sessions.exclusive {
            tokens.clearTokens()
            tokens.saveLoginSession("synthetic-epoch-access", "synthetic-epoch-refresh", "member", accountId, false)
        }
        current { }
    }

    /** Only setup/independent oracle code may take this unqualified current control graph.
     * Public UI actions in tests must use their actual original selection/frame API.
     */
    internal suspend fun <T> current(block: suspend (DatabaseEpochGraph) -> T): T =
        host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { graph ->
            synchronized(databases) { databases += graph.database }
            block(graph)
        }

    internal suspend fun habitSelection(id: Long): DatabaseObjectSelection = current { graph ->
        val row = requireNotNull(graph.habitRepository.getHabitById(id))
        DatabaseObjectSelection.habit(row, host.currentPublication())
    }
    internal suspend fun metricSelection(id: Long): DatabaseObjectSelection = current { graph ->
        val row = requireNotNull(graph.metricRepository.getMetricForEditing(id).value)
        DatabaseObjectSelection.metric(row, host.currentPublication())
    }

    internal suspend fun exactMain() = current { graph -> graph.database.withTransaction {
        NextReplicaStorageProof(graph.database).readInTransaction().let { PhysicalDatabaseMainProof(it.contents, it.hash) }
    } }

    /** Real original clear+retirement followed by Host actual drain, old close and fresh graph.
     * No Provider reset, direct reopen, fake owner, source re-signing or manual retirement reset.
     */
    internal suspend fun clearAndAwaitReplacement(): Pair<HabitDatabase, HabitDatabase> {
        val old = current { graph ->
            host.withLocalDataReset { sessions.exclusive { graph.habitRepository.clearAllData(context) } }
            graph.database
        }
        val fresh = try {
            withTimeout(30_000) { host.withGraph { graph ->
                synchronized(databases) { databases += graph.database }
                graph.database
            } }
        } catch (failure: Throwable) {
            // Preserve the original timeout/cancellation identity. The existing failed
            // rebuild is evidence only: no SQL, retry, new graph or authority is acquired.
            host.lastRebuildFailure.value?.let { rebuild ->
                if (rebuild !== failure && failure.suppressed.none { it === rebuild })
                    failure.addSuppressed(rebuild)
            }
            throw failure
        }
        assertNotSame(old, fresh); assertFalse(old.isOpen); assertTrue(fresh.isOpen)
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(old)).isRetired)
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(fresh)).isRetired)
        return old to fresh
    }

    /** The same real clear succeeds, then a distinct following operation fails. The failure is
     * not a fabricated DB error and does not resolve an unknown commit; caller supplies the
     * actual failing preferences/token collaborator when that contract is under test.
     */
    internal suspend fun <T> resetThen(next: suspend () -> T): T = current { graph ->
        host.withLocalDataReset { sessions.exclusive {
            graph.habitRepository.clearAllData(context)
            next()
        } }
    }

    override fun after() = runBlocking<Unit> {
        beforeCleanup?.invoke()
        if (!connected) {
            if (::roomConfiguration.isInitialized) DatabaseEpochTestRooms.release(roomConfiguration)
            cleanupCompleted = true
            return@runBlocking
        }
        var failure: Throwable? = null
        suspend fun finish(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        finish { sessions.exclusive { tokens.clearTokens() } }
        finish { shutdownTestHost(host) }
        if (!host.lifecycleEnded) throw requireNotNull(failure) { "Host lifecycle did not close" }
        finish { icons.awaitImages(); icons.close() }
        failure?.let { throw it }
        beforeDatabaseDeletion?.invoke()
        finish {
            assertTrue("A graph's physical connection remained open", synchronized(databases) { databases.none { it.isOpen } })
            for (name in listOf("habit_database", com.dayforge.data.appearance.AccountIconDatabase.NAME)) {
                context.deleteDatabase(name); assertFalse(context.getDatabasePath(name).exists())
            }
        }
        failure?.let { throw it }
        DatabaseEpochTestRooms.release(roomConfiguration)
        cleanupCompleted = true
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DatabaseEpochFixtureEntryPoint {
    fun host(): DatabaseRuntimeHost
    fun tokens(): TokenManager
    fun sessions(): AccountSessionCoordinator
    fun icons(): AccountIconController
}

/** Structural equality over the SAME full native read and its original hash. Production's
 * transport object has identity equality; returning it made every two-read assert unequal. */
internal data class PhysicalDatabaseMainProof(val contents: kotlinx.serialization.json.JsonObject, val hash: String)
