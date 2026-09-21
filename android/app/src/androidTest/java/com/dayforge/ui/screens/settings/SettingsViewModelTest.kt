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
import com.dayforge.domain.service.ThemeManager
import com.dayforge.domain.service.ThemeImportService
import com.dayforge.domain.service.ThemeExportService
import com.dayforge.domain.repository.CustomThemeRepository
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()

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

        networkMonitor = mockk()
        every { networkMonitor.state } returns networkState

        // Create mock config services
        val mockConfigExportService = ConfigExportService(habitDao, metricDao, habitMetricLinkDao)
        val mockConfigImportService = ConfigImportService(habitDao, metricDao, habitMetricLinkDao, database)

        // Create mock theme services
        val mockCustomThemeRepository = CustomThemeRepository(context)
        val mockThemeManager = ThemeManager(context, mockCustomThemeRepository)
        val mockThemeImportService = ThemeImportService(mockCustomThemeRepository, mockThemeManager, context)
        val mockThemeExportService = ThemeExportService(mockThemeManager, mockCustomThemeRepository, context)

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
            themeManager = mockThemeManager,
            themeImportService = mockThemeImportService,
            themeExportService = mockThemeExportService,
            customThemeRepository = mockCustomThemeRepository
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
        runTest(testDispatcher) { dataStoreScope.coroutineContext.job.cancelAndJoin() }
        database.close()
        dataStoreFile.delete()
        Dispatchers.resetMain()
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
    fun retry_is_user_owned_reactivates_rejected_changes_and_clears_the_old_modal() =
        runTest(testDispatcher.scheduler) {
            coEvery { mockSyncManager.sync(any()) } returnsMany listOf(
                Result.failure(java.io.IOException("offline")),
                Result.success(Unit)
            )

            viewModel.sync()
            testDispatcher.scheduler.runCurrent()
            assertTrue(viewModel.showSyncError.value)

            viewModel.retrySync()
            testDispatcher.scheduler.runCurrent()

            assertFalse(viewModel.showSyncError.value)
            coVerify(exactly = 1) { mockSyncManager.retryRejectedChanges() }
            coVerify(exactly = 2) { mockSyncManager.sync(any()) }
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
            assertEquals(style, preferencesManager.cardColorStyle.first { it == style })
            assertEquals(enabled, preferencesManager.globalNotificationsEnabled.first { it == enabled })
        }
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
