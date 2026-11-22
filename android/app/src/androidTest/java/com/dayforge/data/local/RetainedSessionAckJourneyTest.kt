package com.dayforge.data.local

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.ui.test.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.repository.*
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.di.DatabaseModule
import com.dayforge.domain.service.DeviceCalendar
import com.dayforge.domain.service.SyncManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Actual protocol donor -> closed historical19 INPUT -> original target Host20 -> retained
 * real wire replay. No current mode/cache/acceptance is hand-filled to pass admission. */
@HiltAndroidTest @UninstallModules(DatabaseModule::class) @RunWith(AndroidJUnit4::class)
class RetainedSessionAckJourneyTest : RetainedSessionJourneyFixture() {
    @Inject internal lateinit var factory: DatabaseEpochGraphFactory
    @Inject lateinit var coordination: DatabaseSyncCoordination
    private var donorHost: DatabaseRuntimeHost? = null
    private var donor: RetainedV19Donor? = null
    private var server: RetainedJourneyServer? = null
    private var auxiliaryFailure: Throwable? = null
    private var auxiliaryClosed = false
    private var prepared: PreparedLease? = null
    private var completedBusiness = false
    private var diagnosticCapture: String? = null

    /** JUnit runs this child After before the parent's finishOwners. It is outside the
     * unchanged timed method and shares the existing95s cleanup budget with all real drains.
     * Registration alone cannot claim success if the original method/children failed. */
    @After fun captureCompletedJourneyEvidence() {
        if (!completedBusiness) return
        PhysicalDatabaseRule.requireProcessCleanupClear()
        check(preparesSeparateJourney)
        val label = requireNotNull(diagnosticCapture)
        diagnosticCapture = null // One consumption; failures never retry or fabricate evidence.
        capturePanel(label)
    }

    /** One private immutable input from THIS actual completed preparation. It grants no
     * authority: original command/ACK paths still perform all fresh authorization. */
    private class PreparedLease(
        val peer: RetainedJourneyServer, val metric: MetricEntity,
        val host: DatabaseRuntimeHost, val store: LegacyGenerationStore,
        val cipher: LegacyGenerationCipher, val generation: String
    ) {
        private var consumed = false
        @Synchronized fun consume() { check(!consumed); consumed = true }
    }

    @Before fun registerSourceOwners() {
        val previous = epochs.beforeCleanup
        epochs.beforeCleanup = {
            previous?.invoke()
            runBlocking { closeAuxiliary() }
        }
        val beforeDelete = epochs.beforeDatabaseDeletion
        epochs.beforeDatabaseDeletion = {
            beforeDelete?.invoke() // Original preference actor joins first.
            auxiliaryFailure?.let { throw it }
            check(auxiliaryClosed)
            donor?.releaseAfterHostsClosed(epochs)
        }
        if (preparesSeparateJourney) {
            check(prepared == null)
            prepared = prepareJourney {
                val peer = formalHistoricalSource()
                val metric = current { requireNotNull(it.metricDao.getMetricByUuid(peer.metrics.first().uuid)) }
                PreparedLease(peer, metric, epochs.host, auth.store, auth.cipher, requireNotNull(generation()))
            }
        }
    }
    private suspend fun closeAuxiliary() {
        auxiliaryFailure?.let { throw it }
        if (auxiliaryClosed) return
        try {
            donorHost?.takeUnless { it.lifecycleEnded }?.let { shutdownTestHost(it) }
            check(donorHost == null || requireNotNull(donorHost).lifecycleEnded)
            server?.close(); server = null
            auxiliaryClosed = true
        } catch (error: Throwable) { auxiliaryFailure = error; auth.preserve(error); throw error }
    }
    private suspend fun peer(): RetainedJourneyServer {
        check(server == null)
        return RetainedJourneyServer().also {
            server = it
            settings.setServerUrl(it.server.origin.toString()); settings.setRemoteServerUrl(null)
        }
    }
    private suspend fun formalHistoricalSource(): RetainedJourneyServer {
        assertEquals(0, epochs.realRoomCreates); assertNull(generation())
        withTimeout(45_000) { themes.current() }
        assertEquals(0, epochs.realRoomCreates); assertNull(generation())
        auth.store.committedChanges.set(0)
        val peer = peer()
        val sourceHost = DatabaseRuntimeHost(factory, epochs.tokens).also { donorHost = it }
        val sourceSync = SyncManager(sourceHost, coordination, epochs.tokens, settings)
        sourceSync.sync().getOrThrow() // The ONLY source of formal state/checkpoints/objects.
        assertEquals(1, peer.server.requests.count { it.path == "/api/v2/sync/rounds/bootstrap" })
        assertTrue(peer.pushIds().isEmpty())
        try {
            donor = RetainedV19Donor.captureAndClose(app, sourceHost, epochs.tokens)
        } catch (error: Throwable) {
            // A partial historical-input archive must not disappear under another test.
            auxiliaryFailure = error; auth.preserve(error); throw error
        }
        assertTrue(sourceHost.lifecycleEnded)
        assertEquals(1, epochs.realRoomCreates)
        // This is construction of an old Preferences INPUT, after the donor is terminal.
        // No live command/session uses this generation, and no replacement value is minted.
        auth.store.edit { it.remove(stringPreferencesKey("auth_session_generation")) }
        historical(closedDonor = requireNotNull(donor))
        readyAndEnroll()
        assertEquals(2, epochs.realRoomCreates)
        current { graph -> graph.database.withTransaction {
            requireNotNull(donor).requireBusinessEquals(graph.database.openHelper.writableDatabase)
            val state = NextChallengeStore(graph.database).activeInTransaction(requireNotNull(epochs.tokens.localSyncAccess())).first
            assertEquals(1, state.challengeContract)
        } }
        return peer
    }
    private suspend fun append(metricId: Long, metricUuid: String, value: Double): IndependentMetricSaved = current { graph ->
        val edit = graph.metricRepository.getMetricForEditing(metricId)
        val attempt = graph.metricRepository.beginIndependentValue(metricId, value, "retained real ACK",
            authority = requireNotNull(edit.authority), expectedMetricUuid = metricUuid)
        graph.metricRepository.executeIndependentValue(attempt)
    }

    @RetainedPreparedJourney
    @Test(timeout = 150_000) fun realFormalDonorOldFileRetainsUnknownAndReplaysActualAckThenRecoversNextMetric() {
        preparedJourney { auth.ownedTest {
        val input = requireNotNull(prepared).also { it.consume() }
        assertSame(input.host, epochs.host); assertSame(input.store, auth.store); assertSame(input.cipher, auth.cipher)
        assertSame(input.peer, server); assertEquals(input.generation, generation()); assertEquals(2, epochs.realRoomCreates)
        assertTrue(requireNotNull(donorHost).lifecycleEnded)
        val peer = input.peer
        val metric = input.metric
        val saved = append(metric.id, metric.uuid, 7.25)
        val reference = requireNotNull(saved.reference)
        val original = row(reference)
        assertEquals(CountCommandRecords.Execution.APPLIED, original.status.execution)
        assertTrue(original.status.pending)
        val pending = current { it.syncOutboxDao.getAll().single() }
        val intent = current { requireNotNull(it.database.nextRequestDao().origin(NEXT_OPERATION, pending.operationId)).intentJson }
        peer.loseNextPushResponse()
        val first = sync.sync()
        assertTrue("A real accepted response is lost; no local ACK can be inferred", first.isFailure)
        assertEquals(1, peer.lostResponses)
        assertEquals(listOf(pending.operationId), peer.pushIds())
        current { graph ->
            assertNotNull(graph.syncOutboxDao.getByOperationId(pending.operationId))
            assertNull(graph.database.nextRequestDao().acceptance(NEXT_OPERATION, pending.operationId))
            assertEquals(intent, requireNotNull(graph.database.nextRequestDao().origin(NEXT_OPERATION, pending.operationId)).intentJson)
            assertEquals(1, graph.metricLogDao.countAll())
        }
        assertEquals(0, native.ends.count { it.kind == RetainedJourneyNative.Kind.ACK })
        val afterLostResponse = row(reference)
        assertEquals(original.intent.text(), afterLostResponse.intent.text())
        assertEquals(requireNotNull(original.outcome).text(), requireNotNull(afterLostResponse.outcome).text())
        sync.sync().getOrThrow()
        assertEquals(listOf(pending.operationId, pending.operationId), peer.pushIds())
        val sameRequestBodies = peer.server.requests.filter { it.path == "/api/v2/sync/rounds/push" }.map { it.body.toList() }
        assertEquals(sameRequestBodies[0], sameRequestBodies[1])
        current { graph ->
            assertTrue(graph.syncOutboxDao.getAll().isEmpty())
            assertNotNull(graph.database.nextRequestDao().acceptance(NEXT_OPERATION, pending.operationId))
            assertEquals(1, graph.metricLogDao.countAll())
        }
        assertEquals(listOf(pending.operationId), native.ends.filter { it.kind == RetainedJourneyNative.Kind.ACK }.flatMap { it.acceptedRequests })
        assertRetained()

        // New actual consumer discovers the durable first result; only its original T2 runs.
        val panel = showPanel()
        fun only(reference: CountCommandReference) {
            val page = requireNotNull(panel.frames.value).value.page.value
            assertEquals(1L, page.retainedCount); assertEquals(listOf(reference), page.entries.map { it.reference })
            compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).assertIsEnabled().performClick()
        }
        only(reference)
        compose.waitUntil(45_000) { panel.frames.value?.value?.result is CountCommandPendingRepository.Result.StandaloneMetricSaved && !panel.busy.value }
        assertFalse(panel.failed.value)
        // The actual Panel performs the same repository recovery and original T2. Assert
        // its returned value instead of running a second assertion-only recovery before it.
        val recovered = (requireNotNull(panel.frames.value).value.result as CountCommandPendingRepository.Result.StandaloneMetricSaved).saved
        assertEquals(reference, recovered.reference)
        assertEquals(saved.factUuid, recovered.factUuid)
        assertEquals(7.25, recovered.value, 0.0)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(reference).status.delivery)
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.count_recovery_metrics_saved, 1, 1)).assertIsDisplayed()
        val next = append(metric.id, metric.uuid, 9.75)
        val nextReference = requireNotNull(next.reference)
        assertNotEquals(reference, nextReference); assertNotEquals(saved.factUuid, next.factUuid)
        val nextPending = current { it.syncOutboxDao.getAll().single() }
        sync.sync().getOrThrow()
        assertEquals(listOf(pending.operationId, pending.operationId, nextPending.operationId), peer.pushIds())
        val prior = panel.frames.value
        compose.onNodeWithText(app.getString(R.string.count_recovery_refresh)).performClick()
        compose.waitUntil(45_000) { panel.frames.value != null && panel.frames.value !== prior && !panel.busy.value }
        only(nextReference)
        compose.waitUntil(45_000) { panel.frames.value?.value?.result is CountCommandPendingRepository.Result.StandaloneMetricSaved && !panel.busy.value }
        assertFalse(panel.failed.value)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row(nextReference).status.delivery)
        current { graph ->
            val selected = DatabaseObjectSelection.metric(metric, epochs.host.currentPublication())
            val display = graph.metricDetailSourceReader.observe(selected).first()
            assertEquals(setOf(saved.factUuid, next.factUuid), display.logs.map { it.uuid }.toSet())
            assertEquals(setOf(7.25, 9.75), display.logs.map { it.value }.toSet())
            assertEquals(2L, display.totalLogCount)
            // The original API orders by the exact date timestamp only. A tied timestamp
            // does not grant the later command/UUID a new tie-breaking rule.
            val nativeLogs = graph.metricLogDao.getAllLogsForMetric(metric.id)
            assertEquals(setOf(saved.factUuid, next.factUuid), nativeLogs.map { it.uuid }.toSet())
            val newestDate = nativeLogs.maxOf { it.date }
            val latest = requireNotNull(display.latest)
            assertEquals(newestDate, latest.date)
            assertTrue(nativeLogs.any { it.date == newestDate && it == latest })
            assertEquals(2, graph.metricLogDao.countAll()); assertTrue(graph.syncOutboxDao.getAll().isEmpty())
            assertNotNull(graph.database.nextRequestDao().acceptance(NEXT_OPERATION, nextPending.operationId))
        }
        val immutable = row(reference)
        assertEquals(original.intent.text(), immutable.intent.text())
        assertEquals(requireNotNull(original.outcome).text(), requireNotNull(immutable.outcome).text())
        val habit = current { requireNotNull(it.habitDao.getHabitByUuid(peer.selected.uuid)) }
        assertDashboard(habit, 0); assertRetained()
        sync.sync().getOrThrow() // No duplicate send/fact after both durable deliveries.
        peer.requireTerminal(expectedResults = 2, expectedPushes = 3)
        assertEquals(listOf("applied", "already_applied", "applied"), peer.computedStatuses())
        assertEquals(2, native.ends.count { it.kind == RetainedJourneyNative.Kind.METRIC_T1 })
        assertEquals(2, native.ends.count { it.kind == RetainedJourneyNative.Kind.ACK })
        assertTrue(native.observationFailures.isEmpty())
        check(diagnosticCapture == null)
        diagnosticCapture = "retained_formal_recovered"
    } }
        completedBusiness = true // Only after auth.ownedTest and its original children return.
    }

    @Test(timeout = 150_000) fun nonemptyUnprofiledOldFileCannotBeRelabelledFormalByRealSyncEntry() = auth.ownedTest {
        historical(); readyAndEnroll()
        val peer = peer(); val before = image(); val originalGeneration = generation()
        val error = requireNotNull(sync.sync().exceptionOrNull())
        assertTrue(generateSequence(error) { it.cause }.any { it.message == "SYNC_CONTROLLED_REBUILD_REQUIRED" })
        assertTrue(generateSequence(error) { it.cause }.any { failure ->
            failure.stackTrace.any { it.className == NextProtocolAdmission::class.java.name }
        })
        assertTrue(peer.server.requests.isNotEmpty())
        assertTrue(peer.server.requests.all { it.path.endsWith("/identity") })
        assertTrue(peer.acceptedKinds().isEmpty()); assertEquals(0, peer.lostResponses)
        assertEquals(before, image()); assertEquals(originalGeneration, generation()); assertRetained()
        current { assertTrue(it.database.nextSyncStateDao().rows().isEmpty()) }
        assertFalse(native.ends.any { it.kind in setOf(RetainedJourneyNative.Kind.T0, RetainedJourneyNative.Kind.METRIC_T1,
            RetainedJourneyNative.Kind.COUNT_T1, RetainedJourneyNative.Kind.T2, RetainedJourneyNative.Kind.ACK) })
    }

    @Module @InstallIn(SingletonComponent::class)
    object PreferencesModule {
        @Provides @Singleton fun data(): DataStore<Preferences> = LegacySessionHostPreferences.active().store
        @Provides @Singleton fun cipher(): TokenCipher = LegacySessionHostPreferences.active().cipher
        @Provides @Singleton fun preferences(data: DataStore<Preferences>, calendar: DeviceCalendar, tokens: TokenManager) =
            PreferencesManager(data, calendar, tokens)
        @Provides @Singleton fun connectivity(@ApplicationContext context: Context): ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
}
