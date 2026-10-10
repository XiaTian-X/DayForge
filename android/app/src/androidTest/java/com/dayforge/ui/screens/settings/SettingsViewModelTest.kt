package com.dayforge.ui.screens.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.net.Network
import com.dayforge.data.api.NetworkMonitor
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.MutableStateFlow
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.SyncProgress
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.ConfigExportService
import com.dayforge.domain.service.ConfigImportService
import com.dayforge.domain.service.SyncManager
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.DeviceThemeRepository
import com.dayforge.data.appearance.ThemeFileRepository
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.widget.WidgetRefreshScheduler
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tests the account role state exposed by SettingsViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {
    @Test fun nextProblemSnapshotPublishesAndUnreadableReadIsNotSilentlyEmpty() = runTest(testDispatcher.scheduler) {
        val changes = MutableStateFlow(0)
        every { mockSyncManager.problemChanges() } returns changes.map { Unit }
        val problem = com.dayforge.data.model.NextSyncProblem("sync_operation", "request", "metric", "entity", "INVALID_PAYLOAD")
        val ready = com.dayforge.data.model.SyncProblems.Next(listOf(problem))
        coEvery { mockSyncManager.readProblems() } returns ready
        replaceViewModel()
        testDispatcher.scheduler.runCurrent()
        assertEquals(ready, viewModel.syncProblems.value)
        coEvery { mockSyncManager.readProblems() } throws IllegalStateException("synthetic corrupt private payload")
        changes.value++; testDispatcher.scheduler.runCurrent()
        assertEquals(com.dayforge.data.model.SyncProblems.Unavailable, viewModel.syncProblems.value)
        assertEquals(1, viewModel.syncProblems.value.count)
        assertFalse(viewModel.showSyncError.value)
    }

    @Test fun accountTransitionClearsProblemsSynchronouslyAndRejectsLatePriorRead() = runTest(testDispatcher.scheduler) {
        val first = CompletableDeferred<Unit>()
        val stale = com.dayforge.data.model.SyncProblems.Next(listOf(com.dayforge.data.model.NextSyncProblem(
            "sync_operation", "old", "metric", "old-entity", "INVALID_PAYLOAD")))
        coEvery { mockSyncManager.readProblems() } coAnswers {
            withContext(kotlinx.coroutines.NonCancellable) { first.await() }; stale
        }
        testDispatcher.scheduler.runCurrent()
        withContext(Dispatchers.IO) { tokenManager.saveLoginSession("new", "new-refresh", "member",
            "aa310000-0000-4000-8000-000000000010", false) }
        assertEquals(com.dayforge.data.model.SyncProblems.Checking, viewModel.syncProblems.value)
        val fresh = com.dayforge.data.model.SyncProblems.Next(emptyList())
        coEvery { mockSyncManager.readProblems() } returns fresh
        first.complete(Unit); testDispatcher.scheduler.runCurrent()
        assertEquals(fresh, viewModel.syncProblems.value)
        assertFalse(viewModel.showSyncError.value)
    }

    @Test fun cancelledProblemReadDoesNotBecomeAnErrorOrLeakItsPayload() = runTest(testDispatcher.scheduler) {
        coEvery { mockSyncManager.readProblems() } throws kotlinx.coroutines.CancellationException("leave")
        testDispatcher.scheduler.runCurrent()
        assertEquals(com.dayforge.data.model.SyncProblems.Checking, viewModel.syncProblems.value)
        assertFalse(viewModel.showSyncError.value)
    }

    @Test fun cancelled_primary_device_action_does_not_publish_sync_error_dialog() = runTest(testDispatcher.scheduler) {
        coEvery { mockSyncManager.makeCurrentDevicePrimary() } throws kotlinx.coroutines.CancellationException("screen left")
        viewModel.makeCurrentDevicePrimary(); testDispatcher.scheduler.runCurrent()
        assertFalse(viewModel.showSyncError.value); assertEquals("", viewModel.syncErrorMessage.value)
        coVerify(exactly = 1) { mockSyncManager.makeCurrentDevicePrimary() }
    }

    @Test fun failed_primary_device_action_keeps_manual_diagnostic_without_starting_sync() = runTest(testDispatcher.scheduler) {
        val error = com.dayforge.data.api.NextSyncHttpFailure(403, "DEVICE_REVOKED")
        coEvery { mockSyncManager.makeCurrentDevicePrimary() } throws error
        viewModel.makeCurrentDevicePrimary(); testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.showSyncError.value); assertEquals(error.message, viewModel.syncErrorMessage.value)
        coVerify(exactly = 0) { mockSyncManager.sync(any()) }
    }

    @Test fun v5_permission_and_invalid_reply_preserve_manual_diagnostics_not_network_message() =
        runTest(testDispatcher.scheduler) {
            for (error in listOf(com.dayforge.data.api.NextSyncHttpFailure(403, "DENIED"),
                com.dayforge.data.api.NextSyncReplyInvalid())) {
                coEvery { mockSyncManager.sync(any()) } returns Result.failure(error)
                viewModel.sync(); testDispatcher.scheduler.runCurrent()
                assertTrue(viewModel.showSyncError.value)
                assertEquals(error.message, viewModel.syncErrorMessage.value)
                viewModel.dismissSyncError()
            }
        }

    @Test fun v5_http_failure_during_sync_logout_never_clears_account_or_claims_network_loss() = runTest {
        tokenManager.saveTokens("access", "refresh", "member", "account", false)
        val error = com.dayforge.data.api.NextSyncHttpFailure(422, "INVALID_PAYLOAD")
        coEvery { mockSyncManager.syncAndThen(any()) } returns Result.failure(error)
        var completed = false
        viewModel.syncAndLogout { completed = true }
        withContext(Dispatchers.Default) { withTimeout(5_000) { viewModel.showSyncError.first { it } } }
        assertFalse(completed); assertEquals("access", tokenManager.accessToken.first())
        assertEquals(context.getString(com.dayforge.R.string.error_sync_failed, error.message), viewModel.syncErrorMessage.value)
    }
    @get:org.junit.Rule val storage = com.dayforge.data.local.PhysicalDatabaseRule()

    private lateinit var viewModel: SettingsViewModel
    private lateinit var tokenManager: TokenManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var configWorkflow: SettingsConfigWorkflow
    private lateinit var appearanceWorkflow: SettingsAppearanceWorkflow
    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var mockSyncManager: SyncManager
    private lateinit var syncProgressState: MutableStateFlow<SyncProgress>
    private val networkState = MutableStateFlow(NetworkMonitor.Snapshot())
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var habitRepository: HabitRepository
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: CompletionDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var database: HabitDatabase
    private lateinit var context: Context
    private lateinit var accountSessionCoordinator: AccountSessionCoordinator
    // Drive Main and the real DataStore with the same scheduler so preference writes
    // and collection are deterministic without replacing persistence with a mock.
    private val testDispatcher = StandardTestDispatcher()
    private val viewModelStore = ViewModelStore()
    private val dataStoreScope = CoroutineScope(SupervisorJob() + testDispatcher)
    private lateinit var dataStoreFile: File
    private lateinit var themeDirectory: File
    private lateinit var themeRepository: DeviceThemeRepository
    private lateinit var themeController: DeviceThemeController

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        // This suite verifies settings persistence and dispatch, not background widget rendering.
        // Real queued workers must not outlive the per-test Room instance.
        mockkObject(WidgetRefreshScheduler)
        every { WidgetRefreshScheduler.request(context) } returns mockk()

        // Create test DataStore for TokenManager
        dataStoreFile = File(context.cacheDir, "settings_${java.util.UUID.randomUUID()}.preferences_pb")
        testDataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { dataStoreFile })
        tokenManager = TokenManager(testDataStore)

        database = storage.database
        habitDao = database.habitDao()
        completionDao = database.completionDao()
        timeLogDao = database.timeLogDao()
        val metricDao = database.metricDao()
        val metricLogDao = database.metricLogDao()
        val habitMetricLinkDao = database.habitMetricLinkDao()

        habitRepository = HabitRepository(habitDao, completionDao, timeLogDao, database)
        mockSyncManager = mockk(relaxed = true)
        syncProgressState = MutableStateFlow(SyncProgress.Idle)
        every { mockSyncManager.syncProgress } returns syncProgressState
        every { mockSyncManager.problemChanges() } returns kotlinx.coroutines.flow.flowOf(Unit)
        coEvery { mockSyncManager.readProblems() } returns com.dayforge.data.model.SyncProblems.Legacy(null,
            emptyList(), emptyList(), emptyList())

        networkMonitor = mockk()
        every { networkMonitor.state } returns networkState

        // Create mock config services
        val mockConfigExportService = ConfigExportService(habitDao, metricDao, habitMetricLinkDao)
        val mockConfigImportService = ConfigImportService(habitDao, metricDao, habitMetricLinkDao, database)

        themeDirectory = java.nio.file.Files.createTempDirectory(context.filesDir.toPath(), "settings-themes-").toFile()
        themeRepository = DeviceThemeRepository(testDataStore, ThemeFileRepository(themeDirectory), BuiltInThemes(context.assets))
        themeController = DeviceThemeController(themeRepository, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        preferencesManager = PreferencesManager(testDataStore)
        configWorkflow = SettingsConfigWorkflow(
            context = context,
            habitDao = habitDao,
            metricDao = metricDao,
            timeLogDao = timeLogDao,
            completionDao = completionDao,
            metricLogDao = metricLogDao,
            configExportService = mockConfigExportService,
            configImportService = mockConfigImportService
        )
        appearanceWorkflow = SettingsAppearanceWorkflow(
            context = context,
            preferencesManager = preferencesManager,
            themes = themeController
        )
        accountSessionCoordinator = AccountSessionCoordinator()

        replaceViewModel()
    }

    private fun replaceViewModel() {
        viewModel = SettingsViewModel(
            context = context,
            syncManager = mockSyncManager,
            tokenManager = tokenManager,
            preferencesManager = preferencesManager,
            networkMonitor = networkMonitor,
            habitRepository = habitRepository,
            habitDao = habitDao,
            timeLogDao = timeLogDao,
            configWorkflow = configWorkflow,
            appearanceWorkflow = appearanceWorkflow,
            accountSessionCoordinator = accountSessionCoordinator
        )
        viewModelStore.put("settings", viewModel)
    }

    @After
    fun teardown() {
        viewModelStore.clear()
        testDispatcher.scheduler.runCurrent()
        runTest(testDispatcher) {
            themeController.close()
            dataStoreScope.coroutineContext.job.cancelAndJoin()
        }
        assertTrue(themeDirectory.deleteRecursively())
        database.close()
        dataStoreFile.delete()
        Dispatchers.resetMain()
        unmockkObject(WidgetRefreshScheduler)
    }

    @Test
    fun settings_follows_shared_connectivity_and_callback_failure_remains_unknown() = runTest {
        testDispatcher.scheduler.runCurrent()
        assertFalse(viewModel.isOnline.value)
        networkState.value = NetworkMonitor.Snapshot(listOf(NetworkMonitor.Path(mockk<Network>(), true, false)))
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.isOnline.value)
        networkState.value = NetworkMonitor.Snapshot()
        testDispatcher.scheduler.runCurrent()
        assertFalse(viewModel.isOnline.value)
        networkState.value = NetworkMonitor.Snapshot(monitoring = false)
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.isOnline.value)
    }

    @Test
    fun stale_background_failure_is_status_only_across_settings_recreation() =
        runTest(testDispatcher.scheduler) {
            val staleFailure = SyncProgress.Error("offline", isNetworkFailure = true)
            syncProgressState.value = staleFailure

            replaceViewModel()
            testDispatcher.scheduler.runCurrent()

            assertEquals(staleFailure, viewModel.syncProgress.value)
            assertFalse(viewModel.showSyncError.value)

            replaceViewModel()
            testDispatcher.scheduler.runCurrent()

            assertEquals(staleFailure, viewModel.syncProgress.value)
            assertFalse(viewModel.showSyncError.value)
        }

    @Test
    fun background_failure_while_settings_is_open_does_not_show_modal_error() =
        runTest(testDispatcher.scheduler) {
            testDispatcher.scheduler.runCurrent()

            syncProgressState.value = SyncProgress.Error("offline", isNetworkFailure = true)
            testDispatcher.scheduler.runCurrent()

            assertTrue(viewModel.syncProgress.value is SyncProgress.Error)
            assertFalse(viewModel.showSyncError.value)
        }

    @Test
    fun manual_sync_failure_shows_current_network_error_and_dismiss_does_not_reset_global_state() =
        runTest(testDispatcher.scheduler) {
            coEvery { mockSyncManager.sync(any()) } returns
                Result.failure(java.io.IOException("offline"))

            viewModel.sync()
            testDispatcher.scheduler.runCurrent()

            assertTrue(viewModel.showSyncError.value)
            assertEquals(
                context.getString(com.dayforge.R.string.error_network_failed),
                viewModel.syncErrorMessage.value
            )

            viewModel.dismissSyncError()

            assertFalse(viewModel.showSyncError.value)
            coVerify(exactly = 0) { mockSyncManager.resetProgress() }
        }

    @Test
    fun retry_is_user_owned_uses_shared_dispatch_and_clears_the_old_modal() =
        runTest(testDispatcher.scheduler) {
            coEvery { mockSyncManager.sync(any()) } returns Result.failure(java.io.IOException("offline"))
            coEvery { mockSyncManager.retrySync(any()) } returns Result.success(Unit)

            viewModel.sync()
            testDispatcher.scheduler.runCurrent()
            assertTrue(viewModel.showSyncError.value)

            viewModel.retrySync()
            testDispatcher.scheduler.runCurrent()

            assertFalse(viewModel.showSyncError.value)
            coVerify(exactly = 1) { mockSyncManager.retrySync(any()) }
            coVerify(exactly = 1) { mockSyncManager.sync(any()) }
        }

    @Test fun retry_failure_uses_manual_diagnostic_and_cancellation_does_not_open_modal() =
        runTest(testDispatcher.scheduler) {
            coEvery { mockSyncManager.retrySync(any()) } returns Result.failure(com.dayforge.data.api.NextSyncHttpFailure(403, "DENIED"))
            viewModel.retrySync(); testDispatcher.scheduler.runCurrent()
            assertTrue(viewModel.showSyncError.value)
            assertEquals("SYNC_HTTP_403", viewModel.syncErrorMessage.value)
            viewModel.dismissSyncError()
            coEvery { mockSyncManager.retrySync(any()) } throws kotlinx.coroutines.CancellationException("screen left")
            viewModel.retrySync(); testDispatcher.scheduler.runCurrent()
            assertFalse(viewModel.showSyncError.value)
            coVerify(exactly = 0) { mockSyncManager.sync(any()) }
        }

    @Test fun all_issue_actions_report_protected_failures_propagate_cancellation_and_keep_success_behavior() =
        runTest(testDispatcher.scheduler) {
            var fault: Exception? = com.dayforge.data.repository.ProtocolNextDataRequiresUpgradeException()
            coEvery { mockSyncManager.retryRejectedChange(any()) } coAnswers { fault?.let { throw it }; Unit }
            coEvery { mockSyncManager.retryRejectedTimerCommand(any()) } coAnswers { fault?.let { throw it }; Unit }
            coEvery { mockSyncManager.cancelRejectedTimerCommandAndUseServer(any()) } coAnswers { fault?.let { throw it }; Unit }
            coEvery { mockSyncManager.discardRejectedChange(any()) } coAnswers { fault?.let { throw it }; Unit }
            coEvery { mockSyncManager.resolveConflictUseServer(any()) } coAnswers { fault?.let { throw it }; Unit }
            coEvery { mockSyncManager.resolveConflictUseLocal(any()) } coAnswers { fault?.let { throw it }; Unit }
            coEvery { mockSyncManager.sync(any()) } returns Result.success(Unit)
            val actions = listOf<() -> Unit>(
                { viewModel.retryRejectedChange(1) }, { viewModel.retryRejectedTimerCommand(1) },
                { viewModel.cancelRejectedTimerCommandAndUseServer(1) }, { viewModel.discardRejectedChange(1) },
                { viewModel.resolveConflictUseServer(1) }, { viewModel.resolveConflictUseLocal(1) })
            for (action in actions) {
                action(); testDispatcher.scheduler.runCurrent()
                assertTrue(viewModel.showSyncError.value)
                assertEquals(fault!!.message, viewModel.syncErrorMessage.value)
                viewModel.dismissSyncError()
            }
            coVerify(exactly = 0) { mockSyncManager.sync(any()) }
            val originalMessage = viewModel.syncErrorMessage.value
            fault = kotlinx.coroutines.CancellationException("screen left")
            for (action in actions) {
                action(); testDispatcher.scheduler.runCurrent()
                assertFalse(viewModel.showSyncError.value)
                assertEquals(originalMessage, viewModel.syncErrorMessage.value)
            }
            coVerify(exactly = 0) { mockSyncManager.sync(any()) }
            fault = null
            for (action in actions) {
                action(); testDispatcher.scheduler.runCurrent()
                assertFalse(viewModel.showSyncError.value)
            }
            coVerify(exactly = 1) { mockSyncManager.sync(any()) } // Only the existing timer-use-server action syncs.
        }

    @Test
    fun concurrent_global_failure_does_not_complete_or_duplicate_a_pending_manual_request() =
        runTest(testDispatcher.scheduler) {
            val result = CompletableDeferred<Result<Unit>>()
            coEvery { mockSyncManager.sync(any()) } coAnswers { result.await() }

            viewModel.sync()
            viewModel.sync()
            testDispatcher.scheduler.runCurrent()
            syncProgressState.value = SyncProgress.Error("background offline", isNetworkFailure = true)
            testDispatcher.scheduler.runCurrent()

            assertFalse(viewModel.showSyncError.value)
            coVerify(exactly = 1) { mockSyncManager.sync(any()) }

            result.complete(Result.failure(java.io.IOException("manual offline")))
            testDispatcher.scheduler.runCurrent()

            assertTrue(viewModel.showSyncError.value)
            assertEquals(
                context.getString(com.dayforge.R.string.error_network_failed),
                viewModel.syncErrorMessage.value
            )
        }

    @Test
    fun sync_before_logout_probes_despite_empty_network_hints_and_preserves_account_on_failure() = runTest {
        tokenManager.saveTokens("access", "refresh", "member", "account", false)
        coEvery { mockSyncManager.syncAndThen(any()) } returns Result.failure(java.io.IOException("offline"))
        var completed = false
        viewModel.syncAndLogout { completed = true }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { viewModel.showSyncError.first { it } }
        }
        coVerify(exactly = 1) { mockSyncManager.syncAndThen(any()) }
        assertFalse(completed)
        assertEquals("access", tokenManager.accessToken.first())
    }

    @Test
    fun admin_role_is_false_without_a_session() = runTest {
        viewModel.isAdmin.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun admin_role_follows_authenticated_token_contract() = runTest {
        viewModel.isAdmin.test {
            assertFalse(awaitItem())
            tokenManager.saveTokens("access", "refresh", "admin", "account", true)
            assertTrue(awaitItem())
        }
    }

    @Test
    fun regular_account_is_logged_in_without_admin_access() = runTest {
        viewModel.isLoggedIn.test {
            assertFalse(awaitItem())
            tokenManager.saveTokens("access", "refresh", "member", "account", false)
            assertTrue(awaitItem())
            assertFalse(viewModel.isAdmin.first())
        }
    }

    @Test
    fun configuration_preview_reports_imported_and_replaced_habit_counts() = runTest {
        habitDao.insert(
            HabitEntity(
                name = "Existing habit",
                habitType = HabitType.CHECK_IN,
                iconResId = 0,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )
        )
        val exportedJson = configWorkflow.exportConfigToJson()
        assertNotNull(exportedJson)
        val importFile = File(context.cacheDir, "settings-config-preview.json")
        importFile.writeText(requireNotNull(exportedJson))

        configWorkflow.prepareImport(Uri.fromFile(importFile))

        val preview = configWorkflow.importConfirmData.value
        assertNotNull(preview)
        assertEquals(1, preview?.importHabitCount)
        assertEquals(1, preview?.deleteHabitCount)
        configWorkflow.cancelImport()
        assertNull(configWorkflow.importConfirmData.value)
        importFile.delete()
    }

    @Test
    fun appearance_changes_remain_observable_through_settings_contract() = runTest {
        // Alternate both preferences to require a fresh persisted value every time.
        repeat(100) { index ->
            val style = if (index % 2 == 0) "personalized" else "follow_theme"
            val enabled = index % 2 != 0
            viewModel.changeCardColorStyle(style)
            viewModel.setGlobalNotificationsEnabled(enabled)
            val expectedStyle = if (index % 2 == 0) DeviceCardStyle.PERSONALIZED else DeviceCardStyle.FOLLOW_THEME
            themeController.state.first { state ->
                state is com.dayforge.data.appearance.DeviceThemeLoadState.Ready && state.theme.saved.selection.cardStyle == expectedStyle
            }
            assertEquals(expectedStyle, themeRepository.savedSelection()!!.selection.cardStyle)
            assertEquals(enabled, preferencesManager.globalNotificationsEnabled.first { it == enabled })
        }
        testDispatcher.scheduler.runCurrent()
        verify(exactly = 100) { WidgetRefreshScheduler.request(context) }
    }

    @Test
    fun direct_logout_is_blocked_while_a_timer_is_active() = runTest(testDispatcher.scheduler) {
        tokenManager.saveTokens("access", "refresh", "member", "account", false)
        val habitId = habitDao.insert(
            HabitEntity(
                name = "Running timer",
                habitType = HabitType.TIMER,
                iconResId = 0,
                colorHex = "#2196F3",
                schedule = HabitSchedule.Daily,
                targetValue = 1
            )
        )
        timeLogDao.insert(
            TimeLogEntity(
                habitId = habitId,
                startTime = System.currentTimeMillis() - 10_000,
                endTime = null,
                durationSeconds = 0,
                date = System.currentTimeMillis()
            )
        )
        var completed = false
        assertNotNull(timeLogDao.getActiveTimeLog())

        viewModel.directLogout { completed = true }
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                viewModel.showActiveTimerDialog.first { it }
            }
        }

        assertTrue(viewModel.showActiveTimerDialog.value)
        assertEquals("Running timer", viewModel.activeTimerHabitName.value)
        assertFalse(completed)
        assertEquals("access", tokenManager.accessToken.first())
        assertNotNull(timeLogDao.getActiveTimeLog())
    }
}
