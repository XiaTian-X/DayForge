package com.dayforge.ui.screens.settings

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
import androidx.room.Room
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests the account role state exposed by SettingsViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SettingsViewModelTest {

    private lateinit var viewModel: SettingsViewModel
    private lateinit var tokenManager: TokenManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var configWorkflow: SettingsConfigWorkflow
    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var mockSyncManager: SyncManager
    private val networkState = MutableStateFlow(NetworkMonitor.Snapshot())
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var habitRepository: HabitRepository
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: CompletionDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var database: HabitDatabase
    private lateinit var context: Context
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

        // Create in-memory database
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).build()
        habitDao = database.habitDao()
        completionDao = database.completionDao()
        timeLogDao = database.timeLogDao()
        val metricDao = database.metricDao()
        val metricLogDao = database.metricLogDao()
        val habitMetricLinkDao = database.habitMetricLinkDao()

        habitRepository = HabitRepository(habitDao, completionDao, timeLogDao, database)
        mockSyncManager = mockk(relaxed = true)

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
        val appearanceWorkflow = SettingsAppearanceWorkflow(
            context = context,
            preferencesManager = preferencesManager,
            themeManager = mockThemeManager,
            themeImportService = mockThemeImportService,
            themeExportService = mockThemeExportService,
            customThemeRepository = mockCustomThemeRepository
        )

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
            accountSessionCoordinator = AccountSessionCoordinator()
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
    fun `settings follows shared connectivity and callback failure remains unknown`() = runTest {
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
    fun `sync before logout probes despite empty network hints and preserves account on failure`() = runTest {
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
    fun `admin role is false without a session`() = runTest {
        viewModel.isAdmin.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `admin role follows authenticated token contract`() = runTest {
        viewModel.isAdmin.test {
            assertFalse(awaitItem())
            tokenManager.saveTokens("access", "refresh", "admin", "account", true)
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `regular account is logged in without admin access`() = runTest {
        viewModel.isLoggedIn.test {
            assertFalse(awaitItem())
            tokenManager.saveTokens("access", "refresh", "member", "account", false)
            assertTrue(awaitItem())
            assertFalse(viewModel.isAdmin.first())
        }
    }

    @Test
    fun `configuration preview reports imported and replaced habit counts`() = runTest {
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
    fun `appearance changes remain observable through settings contract`() = runTest {
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
    fun `direct logout is blocked while a timer is active`() = runTest(testDispatcher.scheduler) {
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
