package com.dayforge.ui.screens.settings

import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import android.net.Uri
import androidx.room.withTransaction
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.appearance.*
import com.dayforge.data.export.dto.ConfigExportDto
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.*
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real Hilt Host/Settings production constructors, actual local files and original import
 * stores. These are workflow token/frame gates, not a claim of OS picker/Compose coverage. */
@HiltAndroidTest @RunWith(AndroidJUnit4::class)
class SettingsConfigHostPhysicalTest {
    private val native = FencedDmlProbe()
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 2) val epochs = PhysicalDatabaseEpochFixture().apply { nativeFactory = native }
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    @Inject lateinit var legacy: SettingsConfigWorkflow
    private val flow get() = requireNotNull(legacy.v2)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var files: File
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrations = mutableListOf<Any>()
    private var orphan: Pair<HabitDatabase, NextReplicaFenceEntity>? = null
    private fun id(n: Int) = "bc196000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    @Before fun start() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login()
        files = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "settings-host-").toFile()
        flow.refresh(); assertEquals(ConfigFileProfile.LEGACY, flow.state.value.profile)
    }
    @After fun finish() = runBlocking<Unit> {
        native.releaseRebuildRead()
        withTimeout(5_000) { tasks.coroutineContext[Job]!!.cancelAndJoin() }
        // Test fault cleanup uses the original exact-identity pre-COMMIT fence participant.
        // It does not activate data, rewrite a stage or fake Host replacement.
        orphan?.let { (db, expected) -> epochs.current { graph ->
            assertSame(db, graph.database)
            NextReplicaWriteFence(graph.database).closeBeforeCommit(expected)
        } }
        if (::files.isInitialized) check(files.deleteRecursively())
    }
    private suspend fun registeredEmpty() {
        epochs.sessions.exclusive {
            epochs.tokens.saveServerIdentity(id(2), id(3))
            epochs.tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write", "facts.append", "timer.control"), true, 1)
        }
        val access = requireNotNull(epochs.tokens.localSyncAccess())
        epochs.current { graph -> graph.database.withTransaction {
            graph.database.nextSyncStateDao().insert(NextSyncStateEntity(access.session.authentication.userId,
                requireNotNull(access.session.serverInstanceId), requireNotNull(access.session.syncEpoch),
                requireNotNull(access.deviceId), 1, 0, "a".repeat(64), "b".repeat(64)))
        } }
        flow.refresh(); assertEquals(ConfigFileProfile.NEXT, flow.state.value.profile); assertTrue(flow.state.value.canImport)
    }
    private suspend fun seedLegacy(name: String) = epochs.current { it.habitRepository.createHabit(name, "", HabitType.CHECK_IN,
        1, "#123456", HabitSchedule.Daily, targetValue = 1) }
    private suspend fun legacyFile(name: String): Uri {
        val body = epochs.current { it.configExportService.exportConfigToJson().getOrThrow() }
        val original = Json.decodeFromString<ConfigExportDto>(body)
        assertEquals(1, original.habits.size)
        val data = original.copy(habits = listOf(original.habits.single().copy(name = name)))
        val file = File(files, "$name.json").also { it.writeText(Json.encodeToString(data)) }
        return Uri.fromFile(file)
    }
    private suspend fun bundle(): ValidatedConfigBundle {
        val source = ConfigFileFixture.empty().copy(themes = listOf(ConfigFileFixture.manifest().themes.single()))
        return ConfigBundleOutput.create(source) { error("No asset dependency") }
    }
    private fun file(source: ValidatedConfigBundle) = Uri.fromFile(File(files, "recovery.zip").also { it.writeBytes(source.exportBytes()) })
    private suspend fun prepared(source: ValidatedConfigBundle): String = epochs.current {
        it.nextConfigImportRepository.confirm(it.nextConfigImportRepository.preview(source))
    }
    private suspend fun journal(importId: String, source: ValidatedConfigBundle) = epochs.current { graph ->
        val preview = graph.nextConfigImportRepository.previewRecovery(importId, source)
        graph.database.withTransaction { NextConfigImportStore(graph.database).read(preview.original.context, importId, source) }
    }

    @Test fun oldLegacyExportPickerCannotExportNewReplicaAfterActualClearAndFreshPickerStillWorks() = runBlocking<Unit> {
        seedLegacy("Private original")
        flow.refresh(); val originalFrame = flow.state.value
        val old = requireNotNull(legacy.beginPickerFrom(originalFrame, true))
        epochs.clearAndAwaitReplacement(); seedLegacy("Replacement household")
        val before = epochs.exactMain(); val output = File(files, "out.json").also { it.writeText("unchanged sentinel") }
        legacy.writeExportFrom(old, Uri.fromFile(output))
        assertEquals("unchanged sentinel", output.readText()); assertEquals(before, epochs.exactMain())
        assertNull(legacy.frame.value.exportResult)
        flow.refresh(); val next = requireNotNull(legacy.beginPickerFrom(flow.state.value, true))
        legacy.writeExportFrom(next, Uri.fromFile(output))
        val exported = Json.decodeFromString<ConfigExportDto>(output.readText())
        assertEquals(listOf("Replacement household"), exported.habits.map { it.name })
        assertEquals(before, epochs.exactMain())
    }

    @Test fun oldLegacyConfirmationCannotConsumeTheNewDialogAfterSameDatabaseRelogin() = runBlocking<Unit> {
        seedLegacy("Original")
        val firstFile = legacyFile("First imported"); val secondFile = legacyFile("Second imported")
        flow.refresh(); legacy.readImportFrom(requireNotNull(legacy.beginPickerFrom(flow.state.value, false)), firstFile)
        val first = legacy.frame.value; assertNotNull(first.confirm)
        val database = epochs.current { it.database }; epochs.login(); epochs.current { assertSame(database, it.database) }
        flow.refresh(); legacy.readImportFrom(requireNotNull(legacy.beginPickerFrom(flow.state.value, false)), secondFile)
        val second = legacy.frame.value; assertNotNull(second.confirm); assertNotSame(first.request, second.request)
        val before = epochs.exactMain()
        legacy.cancelImportFrom(first); legacy.confirmImportFrom(first)
        assertSame(second.confirm, legacy.frame.value.confirm); assertEquals(before, epochs.exactMain())
        legacy.confirmImportFrom(second)
        assertTrue(requireNotNull(legacy.frame.value.importResult).isSuccess)
        epochs.current { assertEquals(listOf("Second imported"), it.habitDao.getAllHabitsOnce().map { row -> row.name }) }
    }

    @Test fun stalePickerCallbackCannotDrainTheNextLegacyImportRequest() = runBlocking<Unit> {
        seedLegacy("Original"); val source = legacyFile("Selected file")
        flow.refresh(); val a = requireNotNull(legacy.beginPickerFrom(flow.state.value, false))
        legacy.pickerFailedFrom(a)
        val b = requireNotNull(legacy.beginPickerFrom(flow.state.value, false))
        val before = epochs.exactMain(); legacy.readImportFrom(a, source)
        assertNull(legacy.frame.value.confirm); assertEquals(before, epochs.exactMain())
        legacy.readImportFrom(b, source)
        assertEquals(source, requireNotNull(legacy.frame.value.confirm).uri); assertEquals(before, epochs.exactMain())
    }

    @Test fun nextPickerFromOriginalEpochCannotPublishPrivatePreviewAfterRealClear() = runBlocking<Unit> {
        registeredEmpty(); val source = bundle(); val old = requireNotNull(flow.beginImportFrom(flow.state.value))
        epochs.clearAndAwaitReplacement(); val before = epochs.exactMain()
        flow.readImportFrom(old, file(source))
        assertFalse(flow.state.value.pickerPending); assertFalse(flow.state.value.busy); assertNull(flow.state.value.preview)
        assertEquals(before, epochs.exactMain())
        flow.refresh(); assertEquals(ConfigFileProfile.LEGACY, flow.state.value.profile)
    }

    @Test fun cancelledAfterPickerPublicationBeforeResultDeliveryReleasesOnlyItsPreparedRequest() = runBlocking<Unit> {
        registeredEmpty(); val original = flow.state.value; val before = epochs.exactMain()
        val returned = AtomicBoolean(); val observedBeforeReturn = AtomicBoolean(); val observed = CompletableDeferred<Unit>()
        lateinit var caller: Deferred<Unit>
        // Unconfined resumes from StateFlow publication itself. The assertion below fails if
        // it ran after the result reached the caller; this is not a delay/timing approximation.
        val watcher = tasks.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            flow.state.first { it.pickerPending }
            observedBeforeReturn.set(!returned.get()); observed.complete(Unit); caller.cancel()
        }
        caller = tasks.async(start = CoroutineStart.LAZY) {
            flow.beginImportFrom(original); returned.set(true)
        }
        caller.start()
        try {
            withTimeout(5_000) { observed.await(); caller.join(); watcher.join() }
            assertTrue(observedBeforeReturn.get()); assertTrue(caller.isCancelled); assertFalse(returned.get())
            assertFalse(flow.state.value.pickerPending); assertFalse(flow.state.value.busy)
            assertEquals(before, epochs.exactMain())
            val current = requireNotNull(flow.beginImportFrom(flow.state.value))
            assertTrue(flow.state.value.pickerPending)
            flow.readImportFrom(current, null)
            assertFalse(flow.state.value.pickerPending)
        } finally { caller.cancel(); watcher.cancel(); withTimeout(5_000) { caller.join(); watcher.join() } }
    }

    @Test fun originalPreparedJournalCanBeResuppliedAndAbandonedWithoutReallocatingOrCommitting() = runBlocking<Unit> {
        registeredEmpty(); val source = bundle(); val importId = prepared(source); val original = journal(importId, source)
        flow.refresh(); assertEquals(ConfigImportPhase.PREPARED, flow.state.value.progress?.phase)
        val before = epochs.exactMain()
        val picker = requireNotNull(flow.beginImportFrom(flow.state.value, true)); flow.readImportFrom(picker, file(source))
        val preview = flow.state.value; assertTrue(requireNotNull(preview.preview).recovery); assertFalse(preview.busy)
        assertEquals(original.plan.identities, journal(importId, source).plan.identities)
        assertEquals(before, epochs.exactMain())
        flow.abandonPreparedFrom(preview)
        assertFalse(flow.state.value.busy); assertNull(flow.state.value.progress)
        epochs.current { assertTrue(it.habitDao.getAllHabitsOnce().isEmpty()); assertTrue(it.metricDao.getAllMetricsOnce().isEmpty()) }
    }

    @Test fun actualFencedClearRollbackKeepsPreparedDataAndControlRecoveryWithoutReopeningBusiness() = runBlocking<Unit> {
        registeredEmpty(); val source = bundle(); val importId = prepared(source)
        val sentinel = epochs.tokens.replicaStageFixture() // Orphan SQL fault input, NOT a trusted Token stage.
        val database = epochs.current { graph -> NextReplicaWriteFence(graph.database).acquire(sentinel.fence); graph.database }
        orphan = database to sentinel.fence
        val owner = requireNotNull(CountTrustRuntime.forDatabase(database)); val before = epochs.exactMain()
        // The same already-captured full native proof identifies which row-level DELETE
        // fence must fire first. Empty tables execute their DELETE but cannot fire a trigger.
        val tables = before.contents.getValue("tables") as JsonObject
        val firstPopulated = FencedDmlProbe.CLEAR_TABLES.first { (tables.getValue(it) as JsonArray).isNotEmpty() }
        val expectedDeletes = FencedDmlProbe.CLEAR_TABLES.takeWhile { it != firstPopulated } + firstPopulated
        assertFalse(owner.isRetired); native.controlOwner.set(owner)
        val failure = try { epochs.current { graph -> epochs.host.withLocalDataReset {
            epochs.sessions.exclusive { graph.habitRepository.clearAllData(context) }
        } }; null } catch (error: Exception) { error }
        finally { native.controlOwner.set(null) }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("SYNC_REPLICA_SWITCH_FENCED") == true })
        val actualDeletes = native.clearDeletes.toList()
        assertEquals("Actual native reset DELETEs must be the exact fixed prefix", expectedDeletes, actualDeletes.map { it.table })
        assertTrue("Retirement must precede the FIRST actual native reset DML", actualDeletes.first().retiredBefore)
        assertTrue(actualDeletes.all { it.retiredBefore && it.inTransaction })
        assertSame("The final exact DELETE must itself throw the original SQLite fence", actualDeletes.last(), native.rejectedClearDelete.get())
        assertEquals(firstPopulated, requireNotNull(native.rejectedClearDelete.get()).table)
        android.util.Log.i("SettingsClearFenceProbe", "attempted=${actualDeletes.joinToString { it.table }} rejected=$firstPopulated retired_before_first=true")
        assertTrue(owner.isRetired); assertTrue(CountTrustRetirementReason.RECOVERY_PREPARATION in owner.retirementReasons)
        assertEquals(before, epochs.exactMain()); assertFalse(epochs.tokens.replicaSwitchPending())
        flow.refresh(); val frame = flow.state.value
        assertEquals(ConfigFileProfile.NEXT, frame.profile); assertEquals(importId, frame.progress?.importId)
        assertFalse(frame.businessAvailable)
        val controlSource = requireNotNull(frame.source)
        assertTrue(controlSource.control.isCurrentInMemory()); assertFalse(controlSource.business.isCurrentInMemory())
        val ordinaryFailure = runCatching { epochs.current { it.replicaPresentationRepository.requireOrdinaryAccess() } }.exceptionOrNull()
        assertEquals(ReplicaSwitchGuard.PENDING, ordinaryFailure?.message)
        val ordinaryEntered = AtomicBoolean()
        val waiting = tasks.async(start = CoroutineStart.UNDISPATCHED) { epochs.host.withGraph { ordinaryEntered.set(true) } }
        try { assertFalse(ordinaryEntered.get()); assertTrue(waiting.isActive) }
        finally { waiting.cancel(); withTimeout(5_000) { waiting.join() } }
        assertNull(flow.beginExportFrom(frame, false)); assertFalse(flow.state.value.busy)
        assertEquals(before, epochs.exactMain())
        val pickerObserved = CompletableDeferred<Unit>(); val previewObserved = CompletableDeferred<Unit>()
        registrations += epochs.host.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
            override fun transition(blocked: Boolean) {
                if (!blocked && flow.state.value.pickerPending) pickerObserved.complete(Unit)
                if (!blocked && flow.state.value.preview?.recovery == true) previewObserved.complete(Unit)
            }
        })
        // The native gate holds the real automatic rebuild's read-only fence inspection.
        // Host is actually Reserving. Start recovery from that rendered CONTROL frame while
        // it is busy but has not published any picker; then let the real defer finish.
        val gate = native.armRebuildRead()
        var opening: Deferred<ConfigFilePicker?>? = null
        val picker = try {
            epochs.host.requestRebuild()
            withTimeout(5_000) { gate.entered.await() }
            assertTrue(controlSource.control.isCurrentInMemory()); assertFalse(controlSource.business.isCurrentInMemory())
            assertNull("A real reserved Host cannot admit an optional active-graph lease",
                epochs.host.tryWithActiveGraph { "unexpected active graph" })
            val preparing = flow.state.value
            opening = tasks.async { flow.beginImportFrom(preparing, true) }
            withTimeout(5_000) { flow.state.first { it.busy } }
            assertSame(preparing.request, flow.state.value.request)
            assertFalse(flow.state.value.pickerPending); assertNull(flow.state.value.preview)
            assertTrue(requireNotNull(opening).isActive)
            gate.release()
            requireNotNull(requireNotNull(opening).await()).also {
                assertEquals(1, gate.steps.get()); assertTrue(gate.knownEnd.get()); assertFalse(gate.timedOut.get())
                assertTrue(controlSource.control.isCurrentInMemory()); assertFalse(flow.state.value.businessAvailable)
            }
        } finally {
            gate.release()
            opening?.let { withTimeout(5_000) { it.cancelAndJoin() } }
        }
        withTimeout(5_000) { pickerObserved.await() } // Actual automatic reserve/defer, no manual state flip.
        assertTrue(flow.state.value.pickerPending)
        flow.readImportFrom(picker, file(source))
        val preview = flow.state.value
        assertTrue(requireNotNull(preview.preview).recovery); assertEquals(source.manifest.themes, preview.preview.themeChoices)
        withTimeout(5_000) { previewObserved.await() }
        assertSame(preview.preview, flow.state.value.preview); assertFalse(flow.state.value.busy)
        assertEquals(before, epochs.exactMain())
        native.fenceFailures.set(0)
        flow.confirmImportFrom(preview)
        assertTrue("Original SQL fence must actually reject the attempted write", native.fenceFailures.get() > 0)
        assertFalse(flow.state.value.busy); assertEquals(ConfigFileMessage.FAILED, flow.state.value.message)
        assertEquals(ConfigImportPhase.PREPARED, flow.state.value.progress?.phase)
        assertEquals(before, epochs.exactMain())
        val retry = requireNotNull(flow.beginImportFrom(flow.state.value, true)); flow.readImportFrom(retry, file(source))
        val recovering = flow.state.value; native.fenceFailures.set(0)
        flow.abandonPreparedFrom(recovering)
        assertTrue(native.fenceFailures.get() > 0); assertFalse(flow.state.value.busy)
        assertEquals(before, epochs.exactMain()); assertFalse(journal(importId, source).committed)
        assertEquals(sentinel.fence, epochs.current { it.database.nextReplicaFenceDao().state() })
    }

    /** Native failure recording only, below unchanged CountTrust SQL/handle closure. */
    private class FencedDmlProbe : SupportSQLiteOpenHelper.Factory {
        val fenceFailures = AtomicInteger()
        val controlOwner = AtomicReference<CountTrustRuntime?>()
        class RebuildReadGate {
            val entered = CompletableDeferred<Unit>()
            val armed = AtomicBoolean(true); val steps = AtomicInteger()
            val knownEnd = AtomicBoolean(); val timedOut = AtomicBoolean()
            val thread = AtomicReference<Thread?>()
            private val latch = CountDownLatch(1)
            fun release() { latch.countDown() }
            fun hold() {
                entered.complete(Unit)
                if (!latch.await(5, TimeUnit.SECONDS)) { timedOut.set(true); error("SETTINGS_REBUILD_READ_GATE_TIMEOUT") }
            }
        }
        private val rebuildRead = AtomicReference<RebuildReadGate?>()
        fun armRebuildRead() = RebuildReadGate().also { check(rebuildRead.compareAndSet(null, it)) }
        fun releaseRebuildRead() { rebuildRead.get()?.release() }
        private fun rebuildCursor(sql: String, raw: SupportSQLiteDatabase, cursor: Cursor): Cursor {
            val gate = rebuildRead.get()
            if (gate == null || sql != "SELECT * FROM next_replica_fence ORDER BY id" ||
                DatabaseOperationSource.current() != null) return cursor
            return object : CursorWrapper(cursor) {
                private fun stepped(found: Boolean): Boolean {
                    if (gate.armed.compareAndSet(true, false)) {
                        check(found && raw.inTransaction()) { "SETTINGS_REBUILD_READ_NOT_ACTUAL" }
                        check(cursor.getString(cursor.getColumnIndexOrThrow("mode")) == NextReplicaFenceEntity.FROZEN)
                        gate.steps.incrementAndGet(); gate.thread.set(Thread.currentThread()); gate.hold()
                    }
                    return found
                }
                override fun moveToFirst() = stepped(cursor.moveToFirst())
                override fun moveToNext() = stepped(cursor.moveToNext())
                override fun moveToPosition(position: Int) = stepped(cursor.moveToPosition(position))
            }
        }
        data class ClearDelete(val table: String, val retiredBefore: Boolean, val inTransaction: Boolean)
        val clearDeletes = ConcurrentLinkedQueue<ClearDelete>()
        val rejectedClearDelete = AtomicReference<ClearDelete?>()
        private fun clearDml(sql: String, bindArgs: Array<out Any?>, raw: SupportSQLiteDatabase, body: () -> Unit) {
            val selected = controlOwner.get()
            val table = CLEAR_SQL[sql]
            if (selected != null && table != null) check(bindArgs.isEmpty()) { "Fixed reset DELETE unexpectedly bound arguments" }
            // Observe only this explicitly armed original clear attempt and its five exact
            // reset statements, immediately before the actual native delegate executes.
            val attempt = if (selected != null && table != null) ClearDelete(table,
                CountTrustRetirementReason.RECOVERY_PREPARATION in selected.retirementReasons,
                raw.inTransaction()).also(clearDeletes::add) else null
            try { observed(body) } catch (error: Throwable) {
                if (attempt != null && generateSequence(error) { it.cause }.any { it.message?.contains("SYNC_REPLICA_SWITCH_FENCED") == true })
                    check(rejectedClearDelete.compareAndSet(null, attempt)) { "More than one reset DELETE was fenced" }
                throw error
            }
        }
        companion object {
            // Independent fixed oracle for the current Room19 reset order, not a generic
            // DELETE matcher or a reference copied from the production table collection.
            val CLEAR_TABLES = listOf("count_trust_root", "count_trust_nodes", "count_trust_routes", "count_command_results", "count_trust_control")
            private val CLEAR_SQL = CLEAR_TABLES.associateBy { table ->
                if (table == "count_trust_control") "DELETE FROM count_trust_control" else "DELETE FROM `$table`"
            }
        }
        private fun <T> observed(body: () -> T): T = try { body() } catch (error: Throwable) {
            if (generateSequence(error) { it.cause }.any { it.message?.contains("SYNC_REPLICA_SWITCH_FENCED") == true }) fenceFailures.incrementAndGet()
            throw error
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var rawOwner: SupportSQLiteDatabase? = null; var wrapped: SupportSQLiteDatabase? = null; val monitor = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (rawOwner !== raw) { rawOwner = raw; wrapped = object : SupportSQLiteDatabase by raw {
                    override fun query(query: String) = rebuildCursor(query, raw, raw.query(query))
                    override fun query(query: String, bindArgs: Array<out Any?>) = rebuildCursor(query, raw, raw.query(query, bindArgs))
                    override fun query(query: SupportSQLiteQuery) = rebuildCursor(query.sql, raw, raw.query(query))
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?) =
                        rebuildCursor(query.sql, raw, raw.query(query, cancellationSignal))
                    override fun endTransaction() {
                        raw.endTransaction()
                        rebuildRead.get()?.let { gate ->
                            if (gate.thread.compareAndSet(Thread.currentThread(), null)) {
                                check(!raw.inTransaction()) { "SETTINGS_REBUILD_OUTER_END_NOT_OBSERVED" }
                                gate.knownEnd.set(true)
                            }
                        }
                    }
                    override fun execSQL(sql: String) = clearDml(sql, emptyArray(), raw) { raw.execSQL(sql) }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = clearDml(sql, bindArgs, raw) { raw.execSQL(sql, bindArgs) }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val actual = raw.compileStatement(sql)
                        return object : SupportSQLiteStatement by actual {
                            override fun execute() = observed { actual.execute() }
                            override fun executeInsert() = observed { actual.executeInsert() }
                            override fun executeUpdateDelete() = observed { actual.executeUpdateDelete() }
                        }
                    }
                } }
                requireNotNull(wrapped)
            }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val actual = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by actual {
                override val readableDatabase get() = wrap(actual.readableDatabase)
                override val writableDatabase get() = wrap(actual.writableDatabase)
            }
        }
    }
}
