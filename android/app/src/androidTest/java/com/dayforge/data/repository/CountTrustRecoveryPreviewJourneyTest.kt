package com.dayforge.data.repository

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.*
import com.dayforge.data.appearance.AccountIconHttp
import com.dayforge.data.local.*
import com.dayforge.domain.service.*
import com.dayforge.ui.screens.recovery.ReplicaRecoveryViewModel
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production repository/HTTP/Room/StageStore, with deterministic server barriers.
 * ViewModel tests invoke its real actions; they do not claim rendered UI performance. */
@RunWith(AndroidJUnit4::class)
class CountTrustRecoveryPreviewJourneyTest : NextReplicaDestinationFixture() {
    private val models = mutableListOf<ViewModelStore>()
    private val modelJobs = mutableListOf<Job>()
    private val deliveryGates = mutableListOf<CompletableDeferred<Unit>>()
    private val barriers = mutableListOf<CountDownLatch>()
    private val previews = mutableListOf<ReplicaDifferencePreview>()
    @After fun releaseOwnedLifetimes() = runBlocking {
        barriers.forEach { it.countDown() }; deliveryGates.forEach { it.complete(Unit) }
        withContext(Dispatchers.Main) { models.forEach { it.clear() } }
        modelJobs.forEach { it.join() }
        previews.forEach(ReplicaDifferencePreview::discard)
    }
    private fun repository(): ReplicaRecoveryRepository {
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val network = mockk<NetworkMonitor>(); coEvery { network.localNetworks() } returns emptyList()
        val business = BusinessSyncRepository(mockk(), mockk(), db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
        val icons = AccountIconController({ runtime() }, tokens)
        val connection = ReplicaRecoveryConnection(EndpointResolver(network, preferences, tokens, Json, SelectedNetworkTransport()),
            tokens, sessions, client, http, AccountIconHttp(client, tokens, peer.origin))
        return ReplicaRecoveryRepository(context, db, tokens, sessions, preferences, dataStore, business,
            NextReplicaCandidateValidator(context, tokens, sessions, preferences), icons, connection)
    }
    private suspend fun configuredRepository(): ReplicaRecoveryRepository {
        initialize(); materialReply = { materialResponse(it, emptyList()) }; return repository()
    }
    private suspend fun preview(repository: ReplicaRecoveryRepository) = repository.preview().also { previews += it }
    private suspend fun suppressed() = db.withTransaction {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        owner.maintenance(db) {
            val storage = db.openHelper.writableDatabase.query("SELECT storageId FROM count_trust_control WHERE id=1").use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            owner.recoveryPreviewSuppressed(storage)
        }
    }
    private suspend fun model(repository: ReplicaRecoveryRepository, actions: ReplicaRecoveryActions? = null): ReplicaRecoveryViewModel {
        val result = withContext(Dispatchers.Main) {
            val store = ViewModelStore().also { models += it }
            ReplicaRecoveryViewModel(actions ?: ReplicaRecoveryService(repository, tokens, sessions)).also {
                store.put("recovery", it); modelJobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
            }
        }
        result.state.first { it.checked && !it.busy }
        withContext(Dispatchers.Main) { result.show() }
        result.state.first { !it.busy }
        return result
    }
    private suspend fun act(model: ReplicaRecoveryViewModel, action: () -> Unit) {
        withContext(Dispatchers.Main) { action() }
        model.state.first { !it.busy }
        model.canRender(model.state.value) // Synchronize with final local disposal under its monitor.
    }
    private fun pauseResponse(): Pair<CountDownLatch, CountDownLatch> {
        val reached = CountDownLatch(1); val release = CountDownLatch(1).also { barriers += it }
        destinationTransform = { value ->
            reached.countDown(); check(release.await(10, TimeUnit.SECONDS)); value
        }
        return reached to release
    }
    @Test fun returnedPreviewWithoutConfirmationBlocksFreshQualificationAndCloseDoesNotReviveOldOwner() = runBlocking<Unit> {
        val repository = configuredRepository(); val value = preview(repository)
        assertTrue(value.livePreview); assertTrue(value.heldPreview); assertTrue(suppressed()); assertNull(tokens.activeReplicaStage())
        val before = CountConsumerReadFixtureAssertions(db, tokens, sessions).capture()
        storage.reopen()
        CountHistoryReader(db, tokens, sessions).readSummary(habit)
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        assertTrue(owner.isRetired); assertTrue(CountTrustRetirementReason.RECOVERY_PREVIEW in owner.retirementReasons)
        CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(before)
        assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", runCatching { repository().prepare(value) }.exceptionOrNull()?.message)
        ReplicaDifferencePreview.discard(value)
        assertFalse(value.livePreview); assertFalse(value.heldPreview); assertFalse(suppressed()); assertTrue(owner.isRetired)
    }
    @Test fun viewModelReplacementAndDismissReleaseOnlyTheirOwnReturnedPreview() = runBlocking<Unit> {
        val repository = configuredRepository(); val independent = preview(repository); val model = model(repository)
        act(model) { model.preview() }
        val firstState = model.state.value; val first = requireNotNull(firstState.preview).also { previews += it }
        assertTrue(first.heldPreview)
        act(model) { model.preview() }
        val second = requireNotNull(model.state.value.preview).also { previews += it }
        assertFalse(first.livePreview); assertFalse(first.heldPreview); assertFalse(model.canRender(firstState))
        assertTrue(second.heldPreview); assertTrue(independent.heldPreview)
        val secondState = model.state.value
        withContext(Dispatchers.Main) { model.dismiss() }
        assertFalse(second.livePreview); assertFalse(second.heldPreview); assertFalse(model.canRender(secondState))
        assertTrue(independent.heldPreview); assertTrue(suppressed())
        ReplicaDifferencePreview.discard(independent); assertFalse(suppressed())
    }
    @Test fun closingDuringRealPreviewHttpRevokesPublicationAndWaitsForOriginalCancellationCleanup() = runBlocking<Unit> {
        val repository = configuredRepository(); val model = model(repository); val (reached, release) = pauseResponse()
        val beforeNative = CountConsumerReadFixtureAssertions(db, tokens, sessions).capture()
        val responseReleased = CompletableDeferred<Unit>()
        val beforeCalls = peer.requests.size
        withContext(Dispatchers.Main) { model.preview() }
        try {
            assertTrue(withContext(Dispatchers.IO) { reached.await(5, TimeUnit.SECONDS) })
            val cancelledRequest = peer.requests.drop(beforeCalls).single {
                it.path == "/api/v2/sync/rounds/recovery-preview"
            }
            assertEquals("GET", cancelledRequest.method)
            // Only this already reached response may observe the explicit client cancellation.
            // Other requests retain MaterialSocketServer's original strict write contract.
            destinationClientCloseExpected = { request ->
                if (request !== cancelledRequest) false else {
                    check(responseReleased.complete(Unit)); true
                }
            }
            // HTTP has not succeeded: no optional cleanup/hold is needed yet.
            assertFalse(suppressed()); assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
            val old = model.state.value
            withContext(Dispatchers.Main) { model.dismiss() }
            assertFalse(model.canRender(old)); assertFalse(model.state.value.visible); assertNull(model.state.value.preview)
        } finally { release.countDown(); destinationTransform = { it } }
        model.state.first { !it.busy }; model.canRender(model.state.value)
        withTimeout(5_000) { responseReleased.await() }
        assertNull(model.state.value.preview); assertNull(tokens.activeReplicaStage()); assertFalse(suppressed())
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
        CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(beforeNative)
    }
    @Test fun closingAnAlreadyStartedPrepareKeepsHoldUntilItsRealStageHandoffAndEnd() = runBlocking<Unit> {
        val repository = configuredRepository(); val value = preview(repository); val (reached, release) = pauseResponse()
        val task = async(Dispatchers.IO) { repository.prepare(value) }
        try {
            assertTrue(withContext(Dispatchers.IO) { reached.await(5, TimeUnit.SECONDS) })
            ReplicaDifferencePreview.discard(value)
            assertFalse(value.livePreview); assertTrue(value.heldPreview); assertTrue(suppressed())
            assertNull(tokens.activeReplicaStage())
        } finally { release.countDown(); destinationTransform = { it } }
        val stage = task.await()
        assertEquals(ReplicaSwitchPhase.ARCHIVE_VERIFIED, stage.phase)
        assertEquals(stage.recoveryId, value.handedRecoveryId); assertFalse(value.heldPreview)
        assertEquals(stage, tokens.activeReplicaStage())
        assertNotNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
        val retained = archive(stage).readBytes()
        val aborted = repository.cancel(stage)
        assertEquals(ReplicaSwitchPhase.ABORTED, aborted.phase); assertNull(tokens.activeReplicaStage())
        assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
        assertArrayEquals(retained, archive(stage).readBytes()); assertTrue(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
    }
    @Test fun completionReleasesOnlyTheOriginalDurableBarrierWithFreshConfirmationAndArchive() = runBlocking<Unit> {
        val repository = configuredRepository(); val value = preview(repository); val stage = repository.prepare(value)
        assertEquals(stage.recoveryId, value.handedRecoveryId); assertFalse(value.livePreview); assertFalse(value.heldPreview)
        val retained = archive(stage).readBytes()
        val result = repository.confirm(repository.confirmation(stage)) as ReplicaCompletionOutcome.Completed
        assertEquals(ReplicaSwitchPhase.COMPLETED, result.stage.phase); assertNull(tokens.activeReplicaStage())
        assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
        assertArrayEquals(retained, archive(stage).readBytes()); assertFalse(suppressed())
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        assertTrue((repository.resume(result.stage) as ReplicaRecoveryContinuation.Destination).outcome is ReplicaCompletionOutcome.Completed)
    }
    @Test fun zeroPreviewHoldsCannotReleaseAnOrphanDurableStageAfterReopen() = runBlocking<Unit> {
        val repository = configuredRepository(); val value = preview(repository); val stage = repository.prepare(value)
        ReplicaDifferencePreview.discard(value); assertFalse(value.heldPreview)
        dataStore.edit { it.remove(ReplicaSwitchGuard.POINTER) }
        val preferencesBefore = dataStore.data.first()
        val nativeBefore = CountConsumerReadFixtureAssertions(db, tokens, sessions).capture()
        val retained = archive(stage).readBytes()
        storage.reopen()
        // The original complete reader may inspect source facts; its arithmetic result is
        // not a presentation permit. Orphan state must prevent qualification and publication.
        assertTrue(nativeBefore.getValue("count_trust_root").isEmpty())
        val summary = CountHistoryReader(db, tokens, sessions).readSummary(habit)
        assertEquals(0L, summary.totalQuantity)
        CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(nativeBefore)
        val presentation = ReplicaPresentationRepository(db, tokens, sessions)
        assertEquals(ReplicaPresentationProblem.ORPHAN_STAGE, presentation.inspect().problem)
        val ordinary = runCatching { presentation.requireOrdinaryAccess() }.exceptionOrNull()
        assertTrue(ordinary is SyncProtocolException); assertEquals(ReplicaSwitchGuard.PENDING, ordinary?.message)
        val publisher = WidgetDisplayPublisher(tokens, sessions, presentation)
        var prepared = false; var published = false
        assertNull(publisher.capturePublication())
        assertFalse(publisher.renderPrepared(onReadFailure = { fail("Orphan must reject before display preparation") }) {
            prepared = true
            val render: suspend () -> Unit = { assertEquals(0L, summary.totalQuantity); published = true }
            render
        })
        assertFalse(prepared); assertFalse(published)
        assertEquals("SYNC_REPLICA_STAGE_CHANGED", runCatching { repository().cancel(stage) }.exceptionOrNull()?.message)
        assertEquals(preferencesBefore, dataStore.data.first())
        CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(nativeBefore)
        assertArrayEquals(retained, archive(stage).readBytes())
    }
    @Test fun clearedModelDisposesALateRealPreviewAfterCleanupWithoutClosingAnotherPreview() = runBlocking<Unit> {
        val repository = configuredRepository(); val independent = preview(repository)
        val returned = CompletableDeferred<ReplicaDifferencePreview>(); val release = CompletableDeferred<Unit>().also { deliveryGates += it }
        val service = ReplicaRecoveryService(repository, tokens, sessions)
        val actions = object : ReplicaRecoveryActions by service {
            override suspend fun preview(): ReplicaDifferencePreview {
                val value = repository.preview()
                returned.complete(value)
                // Keep a real completed IO result before Main delivery, even after screen clear.
                return withContext(NonCancellable) { release.await(); value }
            }
        }
        val model = model(repository, actions)
        withContext(Dispatchers.Main) { model.preview() }
        val value = returned.await().also { previews += it }
        try {
            val old = model.state.value
            withContext(Dispatchers.Main) { models.last().clear() }
            assertFalse(model.canRender(old)); assertTrue(value.heldPreview); assertTrue(independent.heldPreview)
        } finally { release.complete(Unit) }
        withTimeout(5_000) { while (value.heldPreview) yield() }
        assertNull(model.state.value.preview); assertFalse(value.livePreview)
        assertTrue(independent.heldPreview); assertTrue(suppressed())
        ReplicaDifferencePreview.discard(independent); assertFalse(suppressed())
    }
    @Test fun actualAuthenticationInvalidationClosesTheOwnedPreviewWithoutPublishingOldValues() = runBlocking<Unit> {
        val repository = configuredRepository(); val model = model(repository)
        act(model) { model.preview() }
        val old = model.state.value; val value = requireNotNull(old.preview).also { previews += it }
        val before = CountConsumerReadFixtureAssertions(db, tokens, sessions).capture()
        tokens.saveLoginSession("fresh-synthetic-access", "fresh-synthetic-refresh", "member", id(1), false)
        assertFalse(model.canRender(old)); assertNull(model.state.value.preview)
        assertFalse(value.livePreview); assertFalse(value.heldPreview)
        CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(before)
    }
    @Test fun unknownPathStillAllowsOriginalPreviewPrepareCancelAndFullBusinessReads() = runBlocking<Unit> {
        configuredRepository()
        storage.isolatedBuilder = ::unknownPathDatabase; storage.reopen()
        val repository = repository(); val value = preview(repository)
        assertTrue(value.livePreview); assertTrue(value.heldPreview)
        val stage = repository.prepare(value)
        assertEquals(ReplicaSwitchPhase.ARCHIVE_VERIFIED, stage.phase)
        assertEquals(stage.recoveryId, value.handedRecoveryId); assertFalse(value.heldPreview)
        val retained = archive(stage).readBytes()
        val cancelled = repository.cancel(stage)
        assertEquals(ReplicaSwitchPhase.ABORTED, cancelled.phase); assertNull(tokens.activeReplicaStage())
        assertNull(ReplicaPresentationRepository(db, tokens, sessions).inspect().problem)
        val before = CountConsumerReadFixtureAssertions(db, tokens, sessions).capture()
        storage.reopen()
        CountHistoryReader(db, tokens, sessions).readSummary(habit)
        val fresh = requireNotNull(CountTrustRuntime.forDatabase(db))
        assertTrue(fresh.isRetired)
        assertTrue(fresh.retirementReasons.any { it.name == "CONNECTION_IDENTITY_UNKNOWN" })
        CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(before)
        assertArrayEquals(retained, archive(stage).readBytes())
    }

    @Test fun invalidCleanupSchemaStillRejectsBeforeAuthenticatedPreviewHttp() = runBlocking<Unit> {
        val repository = configuredRepository()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER unexpected_preview_cleanup AFTER DELETE ON count_trust_nodes BEGIN UPDATE metrics SET name='must-not-run'; END")
        }
        val native = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = native.capture(); val calls = peer.requests.size
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val failure = runCatching { repository.preview() }.exceptionOrNull()
        assertEquals("COUNT_TRUST_RECOVERY_SCHEMA_CHANGED", failure?.message)
        assertTrue(peer.requests.drop(calls).none { it.path == "/api/v2/sync/rounds/recovery-preview" })
        assertFalse(CountTrustRetirementReason.RECOVERY_PREPARATION in owner.retirementReasons)
        assertNull(tokens.activeReplicaStage()); native.assertUnchanged(before)
    }

    @Test fun fenceAppearingDuringSuccessfulHttpIsRecheckedBeforeCleanupAndPreviewPublication() = runBlocking<Unit> {
        val repository = configuredRepository(); val (reached, release) = pauseResponse()
        val access = requireNotNull(tokens.localSyncAccess())
        val fence = NextReplicaFenceEntity(id(971), id(1), id(2), id(3), id(974),
            access.session.authentication.generation, requireNotNull(access.deviceId),
            requireNotNull(access.capabilityRevision).toLong(), "a".repeat(64))
        val native = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        var acquired = false
        try { supervisorScope {
            val task = async(Dispatchers.IO) { repository.preview() }
            try {
                assertTrue(withContext(Dispatchers.IO) { reached.await(5, TimeUnit.SECONDS) })
                assertFalse(owner.isRetired); assertFalse(suppressed())
                NextReplicaWriteFence(db).acquire(fence); acquired = true
                val before = native.capture()
                release.countDown()
                assertEquals("SYNC_REPLICA_SWITCH_FENCED", runCatching { task.await() }.exceptionOrNull()?.message)
                assertFalse(CountTrustRetirementReason.RECOVERY_PREPARATION in owner.retirementReasons)
                assertNull(tokens.activeReplicaStage()); native.assertUnchanged(before)
            } finally { release.countDown() }
        } } finally {
            destinationTransform = { it }
            if (acquired) NextReplicaWriteFence(db).closeBeforeCommit(fence)
        }
    }
}
