package com.dayforge.data.repository

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.MainActivity
import com.dayforge.R
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.appearance.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.*
import com.dayforge.ui.screens.recovery.*
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual MainActivity/Hilt -> recovery service -> physical Room/DS/files -> owned FastAPI
 * source and offline-restored peers. No mock protocol, repository, device proof or recovery
 * result. This isolated testbed journey is not formal APK deployment or manual acceptance.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RealBackendReplicaRecoveryJointTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var icons: AccountIconController
    @Inject lateinit var themes: DeviceThemeController
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var auth: AuthApi
    @Inject lateinit var business: BusinessSyncRepository
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var habitPreferences: HabitPreferenceRepository
    @Inject lateinit var metrics: MetricRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var presentation: ReplicaPresentationController
    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject internal lateinit var materialHttp: AccountIconHttp
    @Inject internal lateinit var http: NextSyncHttp
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: ReplicaRecoveryViewModel
    private lateinit var restoredUrl: String
    private lateinit var installation: String
    private var originalPreferences: Preferences? = null
    private fun id(suffix: Int) = "7c160000-0000-4000-8000-${suffix.toString().padStart(12, '0')}"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun <T> step(name: String, block: suspend () -> T): T {
        val start = SystemClock.elapsedRealtime()
        android.util.Log.i("RecoveryJoint", "Starting $name")
        var completed = false
        return try { withTimeout(60_000) { block() }.also { completed = true } }
        catch (error: TimeoutCancellationException) {
            // Test-only diagnostics: stack locations, never credentials or record contents.
            // Keep the original stage deadline and propagate the failure unchanged.
            Thread.getAllStackTraces().values.filter { frames ->
                frames.any { it.className.startsWith("com.dayforge.") }
            }.forEach { frames ->
                android.util.Log.i("RecoveryJoint", "Timeout stack:\n${frames.joinToString("\n")}")
            }
            throw AssertionError("Recovery joint step timed out: $name", error)
        }
        finally { android.util.Log.i("RecoveryJoint", "${if (completed) "Completed" else "Failed"} $name in ${SystemClock.elapsedRealtime() - start}ms") }
    }

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        val args = InstrumentationRegistry.getArguments()
        fun endpoint(key: String): String {
            val port = requireNotNull(args.getString(key)).toInt()
            require(port in 1024..65535)
            return "http://127.0.0.1:$port/"
        }
        val sourceUrl = endpoint("dayforgeRecoverySourcePort")
        restoredUrl = endpoint("dayforgeRecoveryRestoredPort")
        require(sourceUrl != restoredUrl)
        installation = requireNotNull(args.getString("dayforgeRecoveryInstallationId"))
        require(installation == id(1))
        hilt.inject()
        originalPreferences = dataStore.data.first()
        sessions.exclusive { tokens.clearTokens() }
        habits.clearAllData(app)
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        // Public fixture installation only. Registration and all authority come from real APIs.
        dataStore.edit { it[stringPreferencesKey("sync_installation_id")] = installation }
        preferences.setActiveServerUrl(null); preferences.setRemoteServerUrl(null); preferences.setServerUrl(sourceUrl)
        val reply = auth.login(LoginRequest("joint-recovery", "TestPassword123!"))
        sessions.exclusive { tokens.saveLoginSession(reply.accessToken, reply.refreshToken, "joint-recovery", reply.userId, false) }
        step("source authenticated bootstrap") { business.sync() }
    }

    @After fun cleanup() = runBlocking<Unit> {
        val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        scenario?.close()
        withTimeout(10_000) { job?.join() }
        if (::presentation.isInitialized) { presentation.close(); presentation.awaitClosed() }
        if (::icons.isInitialized) { icons.awaitImages(); icons.close() }
        if (::themes.isInitialized) themes.close()
        // Dispose the isolated fixture after every outcome, including a pending recovery.
        // Do not call production reset/cancel or claim the failed replica was recovered.
        // Its callers/controllers are already joined above; restore the exact pre-test DS
        // snapshot so this case cannot poison another Hilt fixture in the same test process.
        check(app.packageName == "com.dayforge.testbed")
        originalPreferences?.let { previous -> dataStore.updateData { previous } }
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
        for (name in listOf("account-icons-v1", "replica-archives-v1")) {
            val owned = File(app.filesDir, name)
            assertTrue(!owned.exists() || owned.deleteRecursively())
        }
    }

    private suspend fun settle(): ReplicaRecoveryUiState = withTimeout(60_000) { model.state.first { it.checked && !it.busy } }
    private suspend fun click(tag: String) {
        val started = SystemClock.elapsedRealtime()
        // Fixed UI tags and phase/notice only; never log credentials or review payloads.
        android.util.Log.i("RecoveryJoint", "Click $tag started")
        compose.onNodeWithTag("replica-recovery-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        val settled = settle()
        android.util.Log.i("RecoveryJoint", "Click $tag settled in ${SystemClock.elapsedRealtime() - started}ms " +
            "notice=${settled.notice} phase=${settled.stage?.phase}")
    }
    private suspend fun login(name: String) {
        click("replica-recovery-login")
        compose.onNodeWithTag("replica-recovery-username").performTextInput(name)
        compose.onNodeWithTag("replica-recovery-password").performTextInput("TestPassword123!")
        compose.onNodeWithTag("replica-recovery-login-submit").performClick()
        settle()
        compose.onNodeWithTag("replica-recovery-password").assertDoesNotExist()
    }
    private suspend fun names() = db.habitDao().getAllHabitsOnce().associate { it.uuid to it.name }
    private fun archive(stage: ReplicaSwitchStage) = File(app.filesDir,
        "replica-archives-v1/${stage.accountId}/${stage.serverInstanceId}/${stage.oldEpoch}/${requireNotNull(stage.archive).archiveId}.candidate")

    // 2026-10-13: user-approved complete real recovery journey; ordinary cases stay at 150 seconds.
    @Test(timeout = 300_000) fun restoredBackendRequiresFreshLoginAndConsentRetainsArchiveAndResumesOrdinarySync() = runBlocking<Unit> {
        assertEquals(mapOf(id(101) to "Recovery acknowledged change", id(102) to "Recovery acknowledged missing"), names())
        assertFalse(db.nextRequestDao().hasAny()); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val original = requireNotNull(tokens.localSyncAccess())
        val oldContext = icons.capture()
        step("source real material download") {
            assertEquals(IconMaterialOutcome.RETRY, icons.synchronize(materialHttp))
            assertEquals(IconMaterialOutcome.COMPLETE, icons.synchronize(materialHttp))
        }
        val version = IconPackVersion(id(301), 1)
        val sourceLibrary = icons.library(oldContext)
        val pack = sourceLibrary.packs.single { it.packId == version.packId && it.revision == version.revision }
        val blob = pack.assets.single { it.assetId == id(201) }.light
        icons.select(oldContext, sourceLibrary.selection.generation, version)
        val originalBytes = icons.withReplicaRuntime { it.store.read(oldContext, id(201), blob.sha256) }
        assertEquals(blob.sha256, hash(originalBytes))
        val originalTheme = themes.current().saved
        val retainedPreference = habitPreferences.read(requireNotNull(db.habitDao().getHabitByUuid(id(101))))
        val missingPreference = habitPreferences.read(requireNotNull(db.habitDao().getHabitByUuid(id(102))))
        for (saved in listOf(retainedPreference, missingPreference)) {
            habitPreferences.setNotification(saved.claim, false)
            habitPreferences.setNeverAskAgain(saved.claim, true)
        }
        val oldProjection = ReplicaProjection.capture(db).hash
        preferences.setServerUrl(restoredUrl); preferences.setActiveServerUrl(null)
        step("ordinary sync refuses changed epoch") {
            val failure = runCatching { business.sync() }.exceptionOrNull()
            assertTrue("Expected explicit recovery requirement, got ${failure?.javaClass?.simpleName}", failure is NextReplicaRecoveryRequired)
            assertEquals(original, tokens.localSyncAccess())
            assertEquals(oldProjection, ReplicaProjection.capture(db).hash)
        }
        step("actual MainActivity settings recovery entry") {
            assertTrue("The actual local problem snapshot must recognize the next replica",
                business.readProblems() is com.dayforge.data.model.SyncProblems.Next)
            var profileLabel = ""
            var settingsLabel = ""
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity {
                model = ViewModelProvider(it)[ReplicaRecoveryViewModel::class.java]
                profileLabel = it.getString(R.string.tab_profile)
                settingsLabel = it.getString(R.string.profile_settings)
            }
            settle()
            // Match the actual localized label on the merged clickable tab, without requiring
            // a separately exposed child icon description from Material navigation.
            val profile = hasText(profileLabel) and hasClickAction()
            try { compose.waitUntil(10_000) { compose.onAllNodes(profile).fetchSemanticsNodes().isNotEmpty() } }
            catch (error: Exception) { compose.onRoot().printToLog("RecoveryJoint"); throw error }
            compose.onNode(profile).performClick()
            // Profile first loads its real Room progress; its LazyColumn does not exist until
            // that async read completes. Scroll the lazy parent, not an uncomposed child.
            compose.waitUntil(10_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1 }
            val settings = hasText(settingsLabel) and hasClickAction()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(settings)
            compose.onNode(settings).performClick()
            // Wait for the actual metadata-only doorway, not an offscreen lazy child or the
            // independent problem-list read. Recovery does not require usable old credentials.
            withTimeout(20_000) { model.state.first { it.entryAvailable && model.canRender(it) } }
            compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-replica-recovery"))
            compose.onNodeWithTag("settings-replica-recovery").performClick(); settle()
            assertTrue(model.state.value.visible)
        }
        step("old JWT and wrong owner cannot authorize recovery") {
            click("replica-recovery-preview")
            assertEquals(ReplicaRecoveryError.AUTHENTICATION, model.state.value.error)
            assertNull(tokens.activeReplicaStage()); assertEquals(oldProjection, ReplicaProjection.capture(db).hash)
            val credentials = requireNotNull(tokens.authenticationSnapshot())
            login("joint-recovery-isolated")
            assertEquals(ReplicaRecoveryError.ACCOUNT, model.state.value.error)
            val retained = requireNotNull(tokens.authenticationSnapshot())
            assertEquals(credentials.session, retained.session)
            assertTrue(credentials.accessToken == retained.accessToken && credentials.refreshToken == retained.refreshToken)
            assertEquals(original, tokens.localSyncAccess())
            login("joint-recovery")
            assertEquals(ReplicaRecoveryNotice.AUTHENTICATED, model.state.value.notice)
            val authenticated = requireNotNull(tokens.localSyncAccess())
            assertEquals(original.session.authentication.userId, authenticated.session.authentication.userId)
            assertNotEquals(original.session.authentication.generation, authenticated.session.authentication.generation)
            assertEquals(original.session.syncEpoch, authenticated.session.syncEpoch)
            assertEquals(original.deviceId, authenticated.deviceId)
            assertEquals(installation, tokens.registeredInstallationId())
        }
        step("complete difference preview") {
            click("replica-recovery-preview")
            val preview = requireNotNull(model.state.value.preview)
            assertEquals(mapOf(id(101) to ReplicaDifferenceKind.CHANGED, id(102) to ReplicaDifferenceKind.MISSING,
                id(103) to ReplicaDifferenceKind.ADDED), preview.differences.filter { it.key.kind == "plan_node" }.associate { it.key.identity to it.kind })
            assertTrue(preview.unconfirmedLocalRecords.isEmpty()); assertFalse(preview.hasUnfinishedTimer)
            assertTrue(model.state.value.canPrepare); assertNull(tokens.activeReplicaStage())
        }
        step("durable private archive and fresh confirmation evidence") {
            click("replica-recovery-prepare")
            assertNotNull(model.state.value.evidence); assertFalse(model.state.value.approved)
        }
        val stage = requireNotNull(tokens.activeReplicaStage())
        val archiveBytes = archive(stage).readBytes()
        assertEquals(requireNotNull(stage.archive).contentHash, hash(archiveBytes))
        step("explicit consent and confirmed replica switch") {
            compose.onNodeWithTag("replica-recovery-list").performScrollToNode(hasTestTag("replica-recovery-confirm"))
            compose.onNodeWithTag("replica-recovery-confirm").assertIsNotEnabled()
            compose.onNodeWithTag("replica-recovery-approve").performClick()
            click("replica-recovery-confirm")
            assertEquals(ReplicaRecoveryNotice.WAITING, model.state.value.notice)
            assertNotNull(tokens.activeReplicaStage()) // Catalog discovery does not silently loop downloads.
        }
        step("explicit bounded material resume and final release") {
            click("replica-recovery-resume")
            assertEquals(ReplicaRecoveryNotice.COMPLETED, model.state.value.notice)
            assertNull(tokens.activeReplicaStage())
            assertEquals(NextReplicaFenceEntity.CLOSED, db.nextReplicaFenceDao().state()!!.mode)
            assertEquals(ReplicaSwitchPhase.COMPLETED, tokens.replicaStage(stage.recoveryId)!!.phase)
        }
        val current = requireNotNull(tokens.localSyncAccess())
        assertEquals(stage.newEpoch, current.session.syncEpoch)
        assertEquals(original.session.serverInstanceId, current.session.serverInstanceId)
        assertEquals(original.session.authentication.userId, current.session.authentication.userId)
        assertEquals(original.deviceId, current.deviceId); assertEquals(installation, tokens.registeredInstallationId())
        assertArrayEquals(archiveBytes, archive(stage).readBytes())
        assertEquals(originalTheme, themes.current().saved)
        val context = icons.capture()
        assertNotEquals(oldContext.namespace, context.namespace)
        assertEquals(version, icons.library(context).selection.pack)
        assertTrue(icons.library(context).readyVersions.contains(version))
        assertTrue(runCatching { icons.library(oldContext) }.isFailure)
        assertArrayEquals(originalBytes, icons.withReplicaRuntime { it.store.read(context, id(201), blob.sha256) })
        val jobs = icons.transferJobs(context)
        assertTrue(jobs.isNotEmpty()); assertTrue(jobs.all { it.kind == IconTransferKind.DOWNLOAD && it.state == IconTransferState.COMPLETE })
        assertEquals(mapOf(id(101) to "Recovery original", id(103) to "Recovery restored addition"), names())
        val retained = habitPreferences.read(requireNotNull(db.habitDao().getHabitByUuid(id(101))))
        val added = habitPreferences.read(requireNotNull(db.habitDao().getHabitByUuid(id(103))))
        assertFalse(retained.notificationEnabled); assertTrue(retained.neverAsk)
        assertEquals(missingPreference.claim.habitId, added.claim.habitId) // Prove actual numeric-ID reuse.
        assertTrue(added.notificationEnabled); assertFalse(added.neverAsk)
        assertTrue(runCatching { habitPreferences.setNotification(missingPreference.claim, false) }.isFailure)
        assertTrue(habitPreferences.read(requireNotNull(db.habitDao().getHabitByUuid(id(103)))).notificationEnabled)
        step("ordinary UI and new metric sync after recovery") {
            withTimeout(10_000) { presentation.state.first { it is ReplicaPresentationState.Open } }
            click("replica-recovery-close")
            compose.onNodeWithTag("replica-recovery-list").assertDoesNotExist()
            val metricId = metrics.createMetric(MetricEntity(name = "Recovery new metric", unit = "kg", iconResId = 0,
                colorHex = "#123456", appearance = ObjectAppearance(IconReference.Role("metric.weight"), "#123456", "theme")),
                creationAuthority = creator.capture())
            val metric = requireNotNull(db.metricDao().getMetricById(metricId))
            val operation = db.syncOutboxDao().getAll().single()
            assertEquals("metric", operation.recordType); assertEquals(metric.uuid, operation.entityUuid)
            val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, operation.operationId))
            assertEquals(current.session.authentication.userId, origin.accountId)
            assertEquals(current.session.syncEpoch, origin.syncEpoch)
            business.sync()
            val snapshot = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
            assertEquals(setOf(metric.uuid), snapshot.changes.filter { it.entityType == "metric" }.map { it.entityUuid }.toSet())
            assertEquals(setOf(id(101), id(103)), snapshot.changes.filter { it.entityType == "plan_node" }.map { it.entityUuid }.toSet())
            // Accepted work retains its original proof; only the active outbox becomes empty.
            assertTrue(db.nextRequestDao().hasAny()); assertTrue(db.syncOutboxDao().getAll().isEmpty())
            assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, operation.operationId))
            val sent = requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, operation.operationId))
            assertEquals(current.session.syncEpoch, sent.syncEpoch)
            assertEquals(current.deviceId, sent.deviceId)
            val accepted = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, operation.operationId))
            assertEquals(hash(sent.wireBytes), sent.wireHash)
            val sql = db.openHelper.writableDatabase
            assertEquals(NextRequestSql.rowHash(sql, "next_transmissions", "kind=? AND requestId=?",
                arrayOf(NEXT_OPERATION, operation.operationId)), accepted.transmissionHash)
            assertEquals(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?",
                arrayOf(NEXT_OPERATION, operation.operationId)), accepted.originHash)
            assertArrayEquals(archiveBytes, archive(stage).readBytes())
        }
    }
}
