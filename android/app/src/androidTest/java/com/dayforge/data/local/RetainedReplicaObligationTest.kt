package com.dayforge.data.local

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.ThemeFileRepository
import com.dayforge.data.repository.*
import com.dayforge.di.DatabaseModule
import com.dayforge.domain.service.*
import com.dayforge.ui.screens.recovery.ReplicaRecoveryUiState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Baseline negative oracle: a complete B input is valid to inspect, but its unknown old
 * obligation must prevent the real CONTROL prepare entry from creating a recovery stage. */
@HiltAndroidTest @UninstallModules(DatabaseModule::class) @RunWith(AndroidJUnit4::class)
class RetainedReplicaObligationTest : RetainedSessionJourneyFixture() {
    @Inject internal lateinit var factory: DatabaseEpochGraphFactory
    @Inject internal lateinit var coordination: DatabaseSyncCoordination
    @Inject internal lateinit var recovery: DatabaseRecoveryFacade
    @Inject internal lateinit var connection: ReplicaRecoveryConnection
    @Inject internal lateinit var iconController: AccountIconController
    private var donorHost: DatabaseRuntimeHost? = null
    private var coldHost: DatabaseRuntimeHost? = null
    private var donor: RetainedV19Donor? = null
    private var sourcePeer: RetainedJourneyServer? = null
    private var targetPeer: RetainedReplicaPreviewPeer? = null
    private var failure: Throwable? = null
    private var auxiliariesClosed = false
    private var preparedGeneration: String? = null

    @Before fun prepareActualHistoricalInput() {
        val previous = epochs.beforeCleanup
        epochs.beforeCleanup = { previous?.invoke(); runBlocking { closeAuxiliaries() } }
        val beforeDelete = epochs.beforeDatabaseDeletion
        epochs.beforeDatabaseDeletion = {
            beforeDelete?.invoke(); failure?.let { throw it }; check(auxiliariesClosed)
            donor?.releaseAfterHostsClosed(epochs)
        }
        check(preparesSeparateJourney)
        preparedGeneration = prepareJourney {
            assertEquals(0, epochs.realRoomCreates); assertNull(generation())
            withTimeout(45_000) { themes.current() }
            assertEquals(0, epochs.realRoomCreates); assertNull(generation())
            auth.store.committedChanges.set(0)
            val peer = RetainedJourneyServer().also { sourcePeer = it }
            settings.setServerUrl(peer.server.origin.toString()); settings.setRemoteServerUrl(null)
            val source = DatabaseRuntimeHost(factory, epochs.tokens).also { donorHost = it }
            SyncManager(source, coordination, epochs.tokens, settings).sync().getOrThrow()
            assertEquals(1, peer.server.requests.count { it.path == "/api/v2/sync/rounds/bootstrap" })
            assertTrue(peer.pushIds().isEmpty())
            try { donor = RetainedV19Donor.captureAndClose(app, source, epochs.tokens) }
            catch (error: Throwable) { failure = error; auth.preserve(error); throw error }
            assertTrue(source.lifecycleEnded); assertEquals(1, epochs.realRoomCreates)
            // An old preferences INPUT only, after the original Host has actually closed.
            auth.store.edit { it.remove(stringPreferencesKey("auth_session_generation")) }
            historical(closedDonor = requireNotNull(donor)); readyAndEnroll()
            current { graph -> graph.database.withTransaction {
                requireNotNull(donor).requireBusinessEquals(graph.database.openHelper.writableDatabase)
                assertEquals(1, NextChallengeStore(graph.database).activeInTransaction(
                    requireNotNull(epochs.tokens.localSyncAccess())).first.challengeContract)
            } }
            val target = RetainedReplicaPreviewPeer(peer, requireNotNull(epochs.tokens.registeredInstallationId()))
                .also { targetPeer = it }
            settings.setServerUrl(target.server.origin.toString()); settings.setRemoteServerUrl(null)
            requireNotNull(generation())
        }
    }
    private suspend fun closeAuxiliaries() {
        failure?.let { throw it }; if (auxiliariesClosed) return
        try {
            coldHost?.takeUnless { it.lifecycleEnded }?.let { shutdownTestHost(it) }
            donorHost?.takeUnless { it.lifecycleEnded }?.let { shutdownTestHost(it) }
            check(coldHost == null || requireNotNull(coldHost).lifecycleEnded)
            check(donorHost == null || requireNotNull(donorHost).lifecycleEnded)
            targetPeer?.close(); sourcePeer?.close(); auxiliariesClosed = true
        } catch (error: Throwable) { failure = error; auth.preserve(error); throw error }
    }
    private suspend fun raw(host: DatabaseRuntimeHost = epochs.host): JsonObject =
        host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { graph -> graph.database.withTransaction {
            NextReplicaStorageProof(graph.database).readInTransaction().contents
        } }
    private fun archiveFiles(): Map<String, String> {
        val root = File(app.filesDir, "replica-archives-v1")
        if (!root.exists()) return emptyMap()
        return root.walkTopDown().filter(File::isFile).associate { file ->
            file.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    @RetainedPreparedJourney
    @Test(timeout = 150_000) fun retainedUnknownAloneBlocksRealControlPrepareWithoutStageOrSourceChange() {
        preparedJourney { auth.ownedTest {
            assertEquals(preparedGeneration, generation()); assertEquals(2, epochs.realRoomCreates)
            assertTrue(requireNotNull(donorHost).lifecycleEnded); assertRetained()
            current { graph -> graph.database.withTransaction {
                val root = CountCommandStorageInitialization.verifyRootAnchor(graph.database.openHelper.writableDatabase)
                assertEquals(CountCommandRootCodec.Mode.ACCOUNT_BOUND_RETAINED, root.header.mode)
                assertEquals(1L, root.header.legacyCount); assertEquals(0L, root.header.pending.count)
                val caller = currentCoroutineContext()
                CountCommandRetainedPartition.verify(graph.database.openHelper.writableDatabase, root) { caller.ensureActive() }
            } }
            val preview = recovery.preview()
            try {
                assertFalse(preview.activationAuthorized); assertFalse(preview.hasUnfinishedTimer)
                assertTrue(preview.unconfirmedLocalRecords.isEmpty())
                assertEquals(mapOf(ReplicaRecoveryReview.LOCAL_COMMAND_RECOVERY to 1L), preview.assessment.reviewCounts)
                assertFalse(ReplicaRecoveryUiState(preview = preview).canPrepare)
                // Prove complete actual business/material/theme input has no other reason to
                // refuse; this is the same provider composition used by the repository.
                epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { graph ->
                    connection.use { connected -> iconController.withReplicaRuntime { runtime ->
                        val inputs = runtime.metadata.replicaInputReader(app, graph.database, settings,
                            connected.businessHttp, runtime.transfers, runtime.store, auth.store,
                            ThemeFileRepository(app.filesDir), BuiltInThemes(app.assets))
                        inputs.inspect(preview.assessment.original, preview.oldStorage.hash) { input, _ ->
                            assertFalse(input.hasReview); assertFalse(input.activationAuthorized)
                            assertTrue(input.business.unconfirmed.isEmpty()); assertFalse(input.business.hasUnfinishedTimer)
                            assertTrue(input.missingAssets.isEmpty()); assertFalse(input.icons.hasUnresolvedTransfers)
                            assertNull(input.icons.metadata.selection.pack)
                        }
                    } }
                }
                assertRetained()
                val before = raw(); val preferencesBefore = auth.store.data.first().asMap()
                val archivesBefore = archiveFiles()
                assertNull(epochs.tokens.activeReplicaStage())
                var returnedStage: ReplicaSwitchStage? = null
                val rejected = try { returnedStage = recovery.prepare(preview); null }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { error }
                // Fixed synthetic diagnostics record the actual branch before the assertion. No
                // credential, row value or alleged PASS is published from this marker.
                android.util.Log.i("RetainedReplicaObligation", "expected_review=LOCAL_COMMAND_RECOVERY count=1 " +
                    "returned_phase=${returnedStage?.phase} error_type=${rejected?.javaClass?.simpleName} " +
                    "review_refused=${rejected?.message == "SYNC_RECOVERY_REVIEW_REQUIRED"}")
                // Baseline715 must fail this exact semantic oracle; incidental errors do not pass.
                assertEquals("B unknown must be rejected by the actual prepare owner",
                    "SYNC_RECOVERY_REVIEW_REQUIRED", rejected?.message)
                assertNull(epochs.tokens.activeReplicaStage()); assertFalse(epochs.tokens.replicaSwitchPending())
                assertEquals(before, raw())
                assertTrue("Preparation rejection changed complete preferences", preferencesBefore == auth.store.data.first().asMap())
                assertEquals(archivesBefore, archiveFiles()); assertRetained()
                assertTrue(requireNotNull(sourcePeer).pushIds().isEmpty())
                assertTrue(requireNotNull(targetPeer).server.requests.all { it.method == "GET" })
                // A real closed owner and new Host must still observe the same obligation.
                val previousRooms = epochs.createdRoomInspection()
                assertEquals(2, epochs.realRoomCreates)
                assertEquals(2, previousRooms.size)
                val original = previousRooms.last()
                assertFalse(previousRooms.first().isOpen)
                assertSame(original, epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { it.database })
                assertTrue(original.isOpen)
                assertTrue(preview.heldPreview)
                // Keep the preview hold until the old Host has joined its replacement worker.
                shutdownTestHost(epochs.host); assertTrue(epochs.host.lifecycleEnded)
                assertEquals(2, epochs.realRoomCreates)
                assertFalse(original.isOpen)
                assertTrue(preview.heldPreview)
                ReplicaDifferencePreview.discard(preview)
                assertFalse(preview.heldPreview)
                val cold = DatabaseRuntimeHost(factory, epochs.tokens).also { coldHost = it }
                assertEquals(before, raw(cold)); assertEquals(3, epochs.realRoomCreates)
                val reopened = epochs.createdRoomInspection().last()
                assertNotSame(original, reopened)
                assertTrue(reopened.isOpen)
                cold.withGraph(DatabaseRuntimeHost.Work.CONTROL) { graph -> graph.database.withTransaction {
                    assertSame(reopened, graph.database)
                    assertEquals(1L, CountCommandRecoveryInventory(graph.database, epochs.tokens)
                        .pendingInTransaction(requireNotNull(epochs.tokens.localSyncAccess())))
                    assertNull(graph.database.nextReplicaFenceDao().state())
                } }
                assertEquals(preparedGeneration, generation())
            } finally { ReplicaDifferencePreview.discard(preview) }
        } }
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
