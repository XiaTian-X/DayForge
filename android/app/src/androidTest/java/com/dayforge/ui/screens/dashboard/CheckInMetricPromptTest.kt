package com.dayforge.ui.screens.dashboard

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.ui.components.MetricValueInput
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.ActiveTimerStateProvider
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitCompletionCoordinator
import com.dayforge.domain.service.HabitDeletionCoordinator
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.HabitLifecycleCoordinator
import com.dayforge.domain.service.HabitTimerCoordinator
import com.dayforge.domain.service.MetricOverviewProvider
import com.dayforge.domain.service.TimerManager
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class CheckInMetricPromptTest {

    private lateinit var viewModel: DashboardViewModel
    private lateinit var repository: HabitRepository
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: CompletionDao
    private lateinit var timeLogDao: TimeLogDao
    private lateinit var metricDao: MetricDao
    private lateinit var metricLogDao: MetricLogDao
    private lateinit var habitMetricLinkDao: HabitMetricLinkDao
    private lateinit var database: HabitDatabase
    private lateinit var context: Context
    private lateinit var checkInService: CheckInService
    private lateinit var preferencesManager: PreferencesManager
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var preferenceScope: CoroutineScope

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Use in-memory database for test isolation
        database = Room.inMemoryDatabaseBuilder(
            context,
            HabitDatabase::class.java
        ).addCallback(SyncSchemaCallback).build()
        // Finish lazy Room initialization before starting ViewModel queries. Cancellation can
        // finish before a blocking query returns; closing while its first open is in progress
        // reverses Room's close lock / SQLite open lock order and can deadlock this fixture.
        runBlocking(Dispatchers.IO) { database.openHelper.writableDatabase }
        habitDao = database.habitDao()
        completionDao = database.completionDao()
        timeLogDao = database.timeLogDao()
        metricDao = database.metricDao()
        metricLogDao = database.metricLogDao()
        habitMetricLinkDao = database.habitMetricLinkDao()
        repository = HabitRepository(habitDao, completionDao, timeLogDao, database)
        preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferencesManager = PreferencesManager(PreferenceDataStoreFactory.create(
            scope = preferenceScope,
            produceFile = { temporary.root.resolve("prompt.preferences_pb") }
        ))
        // Create a real CheckInService that uses the repository
        checkInService = CheckInService(repository, completionDao, timeLogDao)
        // Create HabitStatusCalculator
        val failureChecker = FailureChecker(completionDao, timeLogDao)
        val habitStatusCalculator = HabitStatusCalculator(failureChecker, completionDao, timeLogDao)
        val metricRepository = MetricRepository(
            database,
            metricDao,
            metricLogDao,
            habitDao,
            habitMetricLinkDao
        )
        val linkedMetricCoordinator =
            LinkedMetricCoordinator(context, preferencesManager, metricRepository, repository)
        val completionCoordinator =
            HabitCompletionCoordinator(context, checkInService, repository, metricRepository)
        viewModel = DashboardViewModel(
            context,
            repository,
            completionCoordinator,
            HabitDeletionCoordinator(context, repository),
            DashboardHabitListBuilder(habitStatusCalculator),
            DashboardTimeWindowTicker(repository, preferencesManager),
            HabitLifecycleCoordinator(context, repository),
            HabitTimerCoordinator(
                TimerManager(context, habitDao, timeLogDao),
                ActiveTimerStateProvider(timeLogDao, repository, context)
            ),
            timeLogDao,
            habitDao,
            preferencesManager,
            completionDao,
            metricRepository,
            linkedMetricCoordinator,
            MetricOverviewProvider(metricRepository)
        )
    }

    @After
    fun teardown() = runBlocking {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::database.isInitialized) database.close()
        preferenceScope.coroutineContext[Job]?.cancelAndJoin()
        Unit
    }

    private suspend fun seedHabit(name: String): Pair<Long, Long> {
        val habit = HabitEntity(name = name, habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily)
        val habitId = habitDao.insert(habit)
        val metric = MetricEntity(name = "Weight", unit = "kg", decimalPlaces = 1,
            iconResId = 1, colorHex = "#123456")
        val metricId = metricDao.insert(metric)
        habitMetricLinkDao.insert(HabitMetricLinkEntity(habitId = habitId, habitUuid = habit.uuid,
            metricId = metricId, metricUuid = metric.uuid, promptOnComplete = true))
        return habitId to metricId
    }

    private suspend fun awaitCondition(predicate: suspend () -> Boolean) = withTimeout(5_000) {
        while (!predicate()) delay(10)
    }

    @Test fun real_check_in_opens_prompt_and_recording_persists_metric_plus_outbox() = runBlocking {
        val (habitId, metricId) = seedHabit("Walk")
        val subscription = viewModel.viewModelScope.launch { viewModel.habitsWithStats.collect {} }
        awaitCondition { viewModel.habitsWithStats.value.any { it.habit.id == habitId } }
        viewModel.checkIn(habitId)
        awaitCondition { viewModel.postCheckInState.value != null }
        val prompt = requireNotNull(viewModel.postCheckInState.value)
        assertEquals(habitId, prompt.habitId)
        assertEquals("Walk", prompt.habitName)
        assertEquals(listOf(metricId), prompt.linkedMetrics.map { it.metricId })
        assertEquals(1, completionDao.getAllCompletionsOnce().size)
        assertTrue(viewModel.recordMetricValues(habitId, listOf(MetricValueInput(metricId, 68.5))))
        assertEquals(68.5, metricLogDao.getLatestLog(metricId)!!.value, 0.0)
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sync_outbox WHERE recordType = 'metric_log'"
        ).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        viewModel.dismissPostCheckInDialog()
        assertNull(viewModel.postCheckInState.value)
        subscription.cancelAndJoin()
    }

    @Test fun never_ask_again_survives_datastore_reopen_and_is_scoped_to_the_selected_habit() = runBlocking {
        val (first, _) = seedHabit("Walk")
        val (second, _) = seedHabit("Run")
        viewModel.setNeverAskAgain(first, true)
        preferenceScope.coroutineContext[Job]!!.cancelAndJoin()
        preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopened = PreferencesManager(PreferenceDataStoreFactory.create(
            scope = preferenceScope,
            produceFile = { temporary.root.resolve("prompt.preferences_pb") }
        ))
        assertTrue(reopened.getNeverAskAgain(first).first())
        assertFalse(reopened.getNeverAskAgain(second).first())
        val metrics = MetricRepository(database, metricDao, metricLogDao, habitDao,
            habitMetricLinkDao)
        val coordinator = LinkedMetricCoordinator(context, reopened, metrics, repository)
        coordinator.showPromptIfNeeded(first, "Walk")
        assertNull(coordinator.postCheckInState.value)
        coordinator.showPromptIfNeeded(second, "Run")
        assertEquals(second, coordinator.postCheckInState.value!!.habitId)
    }
}
