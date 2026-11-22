package com.dayforge.ui.screens.settings

import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.api.NetworkMonitor
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.SyncProblemFrame
import com.dayforge.data.model.SyncProblems
import com.dayforge.domain.service.SyncManager
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real SettingsScreen callbacks, production VM/Manager, and physical Host/Room. Legacy
 * queue rows are fixture inputs; they are not claimed to be v5 request proofs. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsSyncProblemScreenPhysicalTest {
    private val native = LateProblemRead()
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 2) val epochs = PhysicalDatabaseEpochFixture().apply { nativeFactory = native }
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 4) val compose = createEmptyComposeRule()
    @Inject lateinit var sync: SyncManager
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var network: NetworkMonitor
    @Inject lateinit var config: SettingsConfigWorkflow
    @Inject lateinit var appearance: SettingsAppearanceWorkflow
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private lateinit var model: SettingsViewModel
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private val visible = mutableStateOf(false)
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrations = mutableListOf<Any>()
    private fun label(id: Int) = context.getString(id)
    private fun uuid(n: Int) = "bc199000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"

    @Before fun start() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login()
        activity = ActivityScenario.launch(ComponentActivity::class.java)
        activity.onActivity { it.setContent { MaterialTheme { if (visible.value) SettingsScreen(model, {}) } } }
    }

    @After fun finish() = runBlocking<Unit> {
        native.release.countDown()
        if (::activity.isInitialized) {
            compose.runOnIdle { visible.value = false }; compose.waitForIdle(); activity.close()
        }
        withTimeout(5_000) {
            val owner = withContext(Dispatchers.Main.immediate) {
                (if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null).also { models.clear() }
            }
            jobs.coroutineContext[Job]!!.cancelAndJoin(); owner?.join()
        }
        assertFalse("The fixed native read barrier exceeded its original 5s release budget", native.timedOut.get())
    }

    private fun startScreen() {
        compose.runOnIdle {
            model = SettingsViewModel(context, sync, epochs.tokens, preferences, network, config,
                appearance, epochs.sessions, SavedStateHandle(), epochs.host)
            models.put("settings", model); visible.value = true
        }
    }
    private fun seed(n: Int, message: String): SyncOutboxEntity = runBlocking {
        val row = SyncOutboxEntity(id = 101, operationId = uuid(n), recordType = "habit",
            entityUuid = uuid(n + 100), wireEntityUuid = uuid(n + 100), action = "upsert",
            payloadJson = "{}", errorCode = "INVALID_PAYLOAD", lastError = message,
            deadLetteredAt = 1_000, createdAt = 1_000)
        epochs.current { graph -> graph.database.withTransaction { assertEquals(101L, graph.syncOutboxDao.insert(row)) } }
        row
    }
    private fun awaitFrame(row: SyncOutboxEntity): SyncProblemFrame {
        compose.waitUntil(10_000) { (model.syncProblemFrame.value?.value as? SyncProblems.Legacy)?.changes == listOf(row) }
        return requireNotNull(model.syncProblemFrame.value).also { assertTrue(it.isCurrent()) }
    }
    private fun mainProof() = runBlocking { epochs.exactMain() }
    private fun preferenceProof() = runBlocking { dataStore.data.first() }
    private fun assertPreferences(before: Preferences) {
        // Never put persisted token/ciphertext values into an assertion/log message.
        assertTrue("Complete preferences changed during a rejected/read-only UI action", before == preferenceProof())
    }
    private fun retainedClick(matcher: SemanticsMatcher): () -> Boolean = requireNotNull(
        compose.onNode(matcher).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun inDialog(id: Int) = hasText(label(id)) and hasClickAction() and hasAnyAncestor(isDialog())
    private fun openProblems() {
        compose.onNodeWithText(context.getString(R.string.sync_rejected_count, 1)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(label(R.string.sync_rejected_title)).assertIsDisplayed()
    }
    private fun openDiscard() {
        openProblems()
        compose.onNode(inDialog(R.string.sync_rejected_discard)).assertIsEnabled().performClick()
        compose.onNodeWithText(label(R.string.sync_rejected_discard_title)).assertIsDisplayed()
    }
    /** A Compose idle cycle is not proof of Room completion. Capture and join new direct
     * VM jobs on Main; already-completed jobs have made effects visible to the next proof. */
    private fun invokeAndJoin(rejected: Boolean, action: () -> Unit) {
        var created: List<Job> = emptyList()
        var immediate: Throwable? = null
        compose.runOnIdle {
            val owner = requireNotNull(model.viewModelScope.coroutineContext[Job])
            val before = owner.children.toSet()
            try { action(); if (rejected) assertFalse(model.actionBusy.value) }
            catch (failure: Throwable) { immediate = failure }
            finally { created = owner.children.filter { it !in before }.toList() }
        }
        try { runBlocking { withTimeout(5_000) { created.joinAll() } } }
        catch (failure: Throwable) { immediate?.let { failure.addSuppressed(it) }; throw failure }
        immediate?.let { throw it }; compose.waitForIdle()
    }

    @Test fun oldActualDiscardConfirmationCannotConsumeReopenedRequestButFreshConfirmationCommits() {
        val row = seed(1, "Original rejected change")
        runBlocking { epochs.sessions.exclusive { epochs.tokens.saveSyncCursor(0) } }
        startScreen(); val original = awaitFrame(row)
        assertTrue(runBlocking { epochs.tokens.isSyncBootstrapped.first() })
        openDiscard(); val oldConfirm = retainedClick(inDialog(R.string.action_confirm))
        compose.onNode(inDialog(R.string.action_cancel)).performClick()
        openDiscard()
        val before = mainProof(); val beforePreferences = preferenceProof()
        invokeAndJoin(rejected = true) { assertTrue(oldConfirm()) }
        assertEquals(before, mainProof()); assertPreferences(beforePreferences)
        assertTrue(original.isCurrent())
        compose.onNodeWithText(label(R.string.sync_rejected_discard_title)).assertIsDisplayed()
        compose.onNode(inDialog(R.string.action_confirm)).assertIsEnabled()
        val fresh = retainedClick(inDialog(R.string.action_confirm))
        invokeAndJoin(rejected = false) { assertTrue(fresh()) }
        runBlocking { epochs.current { assertNull(it.syncOutboxDao.getById(row.id)) } }
        assertFalse(runBlocking { epochs.tokens.isSyncBootstrapped.first() })
        compose.waitUntil(10_000) { (model.syncProblemFrame.value?.value as? SyncProblems.Legacy)?.changes?.isEmpty() == true }
        compose.onNodeWithText(label(R.string.sync_rejected_discard_title)).assertDoesNotExist()
    }

    @Test fun retainedActualRetryCannotRetargetSameIdAfterPhysicalReplacementButFreshRetryWorks() {
        val old = seed(2, "Old household rejection"); startScreen(); val oldFrame = awaitFrame(old)
        openProblems(); val oldRetry = retainedClick(inDialog(R.string.sync_rejected_retry))
        compose.onNode(inDialog(R.string.action_close)).performClick()
        runBlocking { epochs.clearAndAwaitReplacement() }
        val fresh = seed(3, "Replacement household rejection"); val freshFrame = awaitFrame(fresh)
        assertNotSame(oldFrame, freshFrame); assertFalse(oldFrame.isCurrent())
        val before = mainProof(); val beforePreferences = preferenceProof()
        invokeAndJoin(rejected = true) { assertTrue(oldRetry()) }
        assertEquals(before, mainProof()); assertPreferences(beforePreferences)
        assertEquals(listOf(fresh), (model.syncProblemFrame.value?.value as? SyncProblems.Legacy)?.changes)
        assertTrue(requireNotNull(model.syncProblemFrame.value).isCurrent())
        openProblems(); compose.onNode(inDialog(R.string.sync_rejected_retry)).assertIsEnabled()
        val freshRetry = retainedClick(inDialog(R.string.sync_rejected_retry))
        invokeAndJoin(rejected = false) { assertTrue(freshRetry()) }
        runBlocking { epochs.current { graph ->
            val actual = requireNotNull(graph.syncOutboxDao.getById(fresh.id))
            assertNotEquals(fresh.operationId, actual.operationId); UUID.fromString(actual.operationId)
            assertEquals(fresh.copy(operationId = actual.operationId, payloadJson = null, baseRevision = null,
                basePayloadJson = null, attemptedAt = null, errorCode = null, lastError = null, deadLetteredAt = null), actual)
            assertEquals(listOf(actual), graph.syncOutboxDao.getAll())
        } }
        assertPreferences(beforePreferences)
        compose.waitUntil(10_000) { (model.syncProblemFrame.value?.value as? SyncProblems.Legacy)?.changes?.isEmpty() == true }
    }

    @Test fun inFlightOldFiniteReadPassesOriginalFreshCheckButCannotPublishAfterRealResetRevokesItsSource() = runBlocking<Unit> {
        val old = seed(4, "Before late read"); startScreen(); val originalFrame = awaitFrame(old)
        val authority = requireNotNull(epochs.tokens.localCoreWriteAccess())
        val sourceRevoked = CompletableDeferred<Unit>(); val latePublished = AtomicBoolean()
        registrations += epochs.host.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
            override fun transition(blocked: Boolean) {
                if (blocked && native.paused.get()) sourceRevoked.complete(Unit)
            }
        })
        val publicationObserver = jobs.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            model.syncProblemFrame.collect { frame ->
                if (sourceRevoked.isCompleted && (frame?.value as? SyncProblems.Legacy)?.changes?.any {
                    it.lastError == LATE_VALUE
                } == true) latePublished.set(true)
            }
        }
        native.arm()
        epochs.current { graph -> graph.database.withTransaction { graph.syncOutboxDao.markError(old.id, LATE_VALUE) } }
        withTimeout(5_000) { native.reached.await() }
        assertTrue(native.mappedExactRow.get()); assertTrue(native.inRealTransaction.get())
        val reset = jobs.async { epochs.clearAndAwaitReplacement() }
        try {
            withTimeout(5_000) { sourceRevoked.await() }
            assertFalse(originalFrame.isCurrent()); assertFalse(reset.isCompleted)
            assertEquals(authority, epochs.tokens.localCoreWriteAccess())
            assertNull(model.syncProblemFrame.value)
            native.release.countDown()
            withTimeout(5_000) { native.actualEnd.await() }
            assertTrue("Original reader's final fresh checks must pass before its actual successful END", native.committedRead.get())
            // The real source stamp changed, not the original credential/permission snapshot.
            assertEquals(authority, epochs.tokens.localCoreWriteAccess())
            withTimeout(30_000) { reset.await() }
            val replacement = seed(5, "Current household rejection"); val fresh = awaitFrame(replacement)
            assertTrue(fresh.isCurrent()); assertFalse(latePublished.get())
            val before = mainProof(); val beforePreferences = preferenceProof()
            compose.waitForIdle()
            assertEquals(listOf(replacement), (model.syncProblemFrame.value?.value as? SyncProblems.Legacy)?.changes)
            assertTrue(requireNotNull(model.syncProblemFrame.value).isCurrent())
            assertEquals(before, mainProof()); assertPreferences(beforePreferences)
            assertEquals(1, native.selectedReadEnds.get()); assertFalse(native.timedOut.get())
        } finally {
            native.release.countDown()
            withTimeout(5_000) { reset.join(); publicationObserver.cancelAndJoin() }
        }
    }

    /** Exact original dead-letter DAO query only; no fake values, general fault hook or raw
     * handle escapes. Pause after actual mapping/close, record that same native read TX END. */
    private class LateProblemRead : SupportSQLiteOpenHelper.Factory {
        private val armed = AtomicBoolean()
        val reached = CompletableDeferred<Unit>(); val actualEnd = CompletableDeferred<Unit>()
        val release = CountDownLatch(1); val paused = AtomicBoolean(); val timedOut = AtomicBoolean()
        val mappedExactRow = AtomicBoolean(); val inRealTransaction = AtomicBoolean()
        val committedRead = AtomicBoolean(); val selectedReadEnds = AtomicInteger()
        private val selected = ThreadLocal<Boolean>(); private val successful = ThreadLocal<Boolean>()
        fun arm() { check(armed.compareAndSet(false, true)) }
        private fun wrapCursor(raw: SupportSQLiteDatabase, sql: String, cursor: Cursor): Cursor {
            if (sql.trim() != DEAD_LETTERS || !armed.get()) return cursor
            return object : CursorWrapper(cursor) {
                private var mapped = false
                override fun getString(columnIndex: Int): String? = super.getString(columnIndex).also { value ->
                    if (getColumnName(columnIndex) == "lastError" && value == LATE_VALUE) mapped = true
                }
                override fun close() {
                    super.close()
                    if (!mapped || !armed.compareAndSet(true, false)) return
                    mappedExactRow.set(true); inRealTransaction.set(raw.inTransaction())
                    check(inRealTransaction.get()); selected.set(true); successful.set(false)
                    paused.set(true); reached.complete(Unit)
                    if (!release.await(5, TimeUnit.SECONDS)) { timedOut.set(true); error("SETTINGS_LATE_READ_RELEASE_TIMEOUT") }
                }
            }
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val monitor = Any(); var native: SupportSQLiteDatabase? = null; var decorated: SupportSQLiteDatabase? = null
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (native !== raw) {
                    native = raw
                    decorated = object : SupportSQLiteDatabase by raw {
                        override fun query(query: String) = wrapCursor(raw, query, raw.query(query))
                        override fun query(query: String, bindArgs: Array<out Any?>) = wrapCursor(raw, query, raw.query(query, bindArgs))
                        override fun query(query: SupportSQLiteQuery) = wrapCursor(raw, query.sql, raw.query(query))
                        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?) =
                            wrapCursor(raw, query.sql, raw.query(query, cancellationSignal))
                        override fun setTransactionSuccessful() {
                            raw.setTransactionSuccessful(); if (selected.get() == true) successful.set(true)
                        }
                        override fun endTransaction() {
                            raw.endTransaction()
                            if (selected.get() == true && !raw.inTransaction()) {
                                committedRead.set(successful.get() == true); selectedReadEnds.incrementAndGet()
                                selected.remove(); successful.remove(); actualEnd.complete(Unit)
                            }
                        }
                    }
                }
                requireNotNull(decorated)
            }
            val original = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = original.onCorruption(wrap(db))
            }
            val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val readableDatabase get() = wrap(delegate.readableDatabase)
                override val writableDatabase get() = wrap(delegate.writableDatabase)
            }
        }
    }
    private companion object {
        const val LATE_VALUE = "Late old read must not publish"
        const val DEAD_LETTERS = "SELECT * FROM sync_outbox WHERE deadLetteredAt IS NOT NULL ORDER BY deadLetteredAt, id"
    }
}
