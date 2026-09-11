package com.dayforge.ui.screens.settings

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var mockSyncManager: SyncManager
    private lateinit var mockConnectivityManager: ConnectivityManager
    private lateinit var habitRepository: HabitRepository
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: CompletionDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var database: HabitDatabase
    private lateinit var context: Context
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()

        // Create test DataStore for TokenManager
        testDataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(context.cacheDir, "test_settings_viewmodel.preferences_pb") }
        )
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

        // Get real ConnectivityManager from context
        mockConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Create mock config services
        val mockConfigExportService = ConfigExportService(habitDao, metricDao, habitMetricLinkDao)
        val mockConfigImportService = ConfigImportService(habitDao, metricDao, habitMetricLinkDao, database)

        // Create mock theme services
        val mockCustomThemeRepository = CustomThemeRepository(context)
        val mockThemeManager = ThemeManager(context, mockCustomThemeRepository)
        val mockThemeImportService = ThemeImportService(mockCustomThemeRepository, mockThemeManager, context)
        val mockThemeExportService = ThemeExportService(mockThemeManager, mockCustomThemeRepository, context)

        val preferencesManager = PreferencesManager(testDataStore)

        viewModel = SettingsViewModel(
            context = context,
            syncManager = mockSyncManager,
            tokenManager = tokenManager,
            preferencesManager = preferencesManager,
            connectivityManager = mockConnectivityManager,
            habitRepository = habitRepository,
            habitDao = habitDao,
            metricDao = metricDao,
            timeLogDao = timeLogDao,
            completionDao = completionDao,
            metricLogDao = metricLogDao,
            linkDao = habitMetricLinkDao,
            configExportService = mockConfigExportService,
            configImportService = mockConfigImportService,
            themeManager = mockThemeManager,
            themeImportService = mockThemeImportService,
            themeExportService = mockThemeExportService,
            customThemeRepository = mockCustomThemeRepository,
            accountSessionCoordinator = AccountSessionCoordinator()
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        database.close()
        File(context.cacheDir, "test_settings_viewmodel.preferences_pb").delete()
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
