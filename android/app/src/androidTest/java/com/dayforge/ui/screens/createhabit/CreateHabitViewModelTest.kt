package com.dayforge.ui.screens.createhabit

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.MetricDao
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CreateHabitViewModelTest {
    @get:org.junit.Rule val storage = com.dayforge.data.local.PhysicalDatabaseRule()

    private lateinit var viewModel: CreateHabitViewModel
    private lateinit var repository: HabitRepository
    private lateinit var habitDao: HabitDao
    private lateinit var completionDao: com.dayforge.data.local.dao.CompletionDao
    private lateinit var metricDao: MetricDao
    private lateinit var database: HabitDatabase
    private lateinit var context: Context
    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var preferencesManager: PreferencesManager
    private val storeJob = kotlinx.coroutines.SupervisorJob()
    private lateinit var storeFile: File
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = storage.database
        habitDao = database.habitDao()
        completionDao = database.completionDao()
        metricDao = database.metricDao()

        // Create test DataStore for PreferencesManager
        storeFile = File(context.cacheDir, "viewmodel-${java.util.UUID.randomUUID()}.preferences_pb")
        testDataStore = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(storeJob + Dispatchers.IO),
            produceFile = { storeFile }
        )
        preferencesManager = PreferencesManager(testDataStore)

        repository = HabitRepository(habitDao, completionDao, database.timeLogDao(), database)
        viewModel = CreateHabitViewModel(repository, habitDao, metricDao, preferencesManager, context)
    }

    @After
    fun teardown() {
        kotlinx.coroutines.runBlocking {
            if (::viewModel.isInitialized) viewModel.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            storeJob.cancelAndJoin()
        }
        database.close()
        if (::storeFile.isInitialized) assertTrue(storeFile.delete() || !storeFile.exists())
        Dispatchers.resetMain()
    }

    @Test
    fun initialFormState_hasDefaultValues() {
        val initialState = viewModel.uiState.value

        assertEquals("Default name should be empty", "", initialState.name)
        assertEquals("Default description should be empty", "", initialState.description)
        assertEquals("Default habitType should be CHECK_IN", HabitType.CHECK_IN, initialState.habitType)
        assertEquals("Default schedule should be Daily", HabitSchedule.Daily, initialState.schedule)
        assertEquals("Default targetValue should be 1", 1, initialState.targetValue)
        assertFalse("Initial isValid should be false", initialState.isValid)
        assertFalse("Initial isSaving should be false", initialState.isSaving)
        assertNull("Initial savedHabitId should be null", initialState.savedHabitId)
    }

    @Test
    fun updateName_updatesStateAndIsValidWhenNameNotBlank() = runTest {
        viewModel.uiState.test {
            // Initial state
            val initial = awaitItem()
            assertEquals("Initial name should be empty", "", initial.name)
            assertFalse("Initial isValid should be false", initial.isValid)

            // Update with valid name
            viewModel.updateName("Test Habit")

            val updated = awaitItem()
            assertEquals("Name should be updated", "Test Habit", updated.name)
            assertTrue("isValid should be true with non-blank name", updated.isValid)

            // Update with blank name
            viewModel.updateName("")

            val invalid = awaitItem()
            assertEquals("Name should be empty", "", invalid.name)
            assertFalse("isValid should be false with blank name", invalid.isValid)
        }
    }

    @Test
    fun updateName_validatesMaxLength() = runTest {
        viewModel.uiState.test {
            skipItems(1) // Skip initial

            // Name at max length (50 characters)
            val maxLengthName = "A".repeat(50)
            viewModel.updateName(maxLengthName)

            val valid = awaitItem()
            assertTrue("isValid should be true for 50 character name", valid.isValid)

            // Name exceeding max length
            val tooLongName = "A".repeat(51)
            viewModel.updateName(tooLongName)

            val invalid = awaitItem()
            assertFalse("isValid should be false for name over 50 characters", invalid.isValid)
        }
    }

    @Test
    fun updateHabitType_changesHabitTypeInState() = runTest {
        viewModel.uiState.test {
            skipItems(1) // Skip initial

            viewModel.updateHabitType(HabitType.COUNTING)

            val updated = awaitItem()
            assertEquals("HabitType should be COUNTING", HabitType.COUNTING, updated.habitType)

            viewModel.updateHabitType(HabitType.CHECK_IN)

            val reverted = awaitItem()
            assertEquals("HabitType should be CHECK_IN", HabitType.CHECK_IN, reverted.habitType)
        }
    }

    @Test
    fun updateTargetValue_changesTargetValueInState() = runTest {
        viewModel.uiState.test {
            skipItems(1) // Skip initial

            viewModel.updateTargetValue(8)

            val updated = awaitItem()
            assertEquals("TargetValue should be 8", 8, updated.targetValue)

            viewModel.updateTargetValue(30)

            val updatedAgain = awaitItem()
            assertEquals("TargetValue should be 30", 30, updatedAgain.targetValue)
        }
    }

    @Test
    fun updateDescription_updatesDescriptionInState() = runTest {
        viewModel.uiState.test {
            skipItems(1) // Skip initial

            viewModel.updateDescription("Test Description")

            val updated = awaitItem()
            assertEquals("Description should match", "Test Description", updated.description)
        }
    }

    @Test
    fun updateColor_updatesColorHexInState() = runTest {
        viewModel.uiState.test {
            skipItems(1) // Skip initial

            viewModel.updateColor("#4CAF50")

            val updated = awaitItem()
            assertEquals("ColorHex should match", "#4CAF50", updated.colorHex)
        }
    }

    @Test
    fun updateSchedule_changesScheduleInState() = runTest {
        viewModel.uiState.test {
            skipItems(1) // Skip initial

            val weeklySchedule = HabitSchedule.Weekly(listOf(1, 3, 5))
            viewModel.updateSchedule(weeklySchedule)

            val updated = awaitItem()
            assertEquals("Schedule should match", weeklySchedule, updated.schedule)
        }
    }

    @Test
    fun saveHabit_withInvalidForm_doesNothing() = runTest {
        // Form is invalid (name is empty), try to save
        viewModel.saveHabit()

        // State should not change - isSaving should remain false
        val state = viewModel.uiState.value
        assertFalse("isSaving should remain false for invalid form", state.isSaving)
        assertNull("savedHabitId should remain null", state.savedHabitId)
    }

    @Test
    fun saveHabit_withValidForm_callsRepositoryCreateHabit() = runBlocking {
        // Set valid form state
        viewModel.updateName("Test Habit")
        viewModel.updateDescription("Test Description")
        viewModel.updateHabitType(HabitType.COUNTING)
        viewModel.updateTargetValue(8)

        // Save the habit (UnconfinedTestDispatcher executes immediately)
        viewModel.saveHabit()

        // Check final state
        val completedState = awaitSaveResult()
        assertFalse("isSaving should be false after save", completedState.isSaving)
        assertNotNull("savedHabitId should be set after successful save", completedState.savedHabitId)
        assertTrue("savedHabitId should be greater than 0", completedState.savedHabitId!! > 0)
        val saved = requireNotNull(habitDao.getHabitById(completedState.savedHabitId!!))
        assertEquals("Test Habit", saved.name)
        assertEquals("Test Description", saved.description)
        assertEquals(HabitType.COUNTING, saved.habitType)
        assertEquals(8, saved.targetValue)
        assertEquals(1, database.syncOutboxDao().count())
    }

    @Test
    fun savedHabitId_isSetAfterSuccessfulSave() = runBlocking {
        // Set valid form
        viewModel.updateName("My Habit")
        viewModel.updateDescription("My Description")

        // Save
        viewModel.saveHabit()

        val completedState = awaitSaveResult()
        assertNotNull("savedHabitId should be set", completedState.savedHabitId)

        // Verify the habit was actually saved
        val savedHabit = habitDao.getHabitById(completedState.savedHabitId!!)
        assertNotNull("Saved habit should exist in database", savedHabit)
        assertEquals("Habit name should match", "My Habit", savedHabit?.name)
    }

    @Test
    fun resetState_clearsAllFormFields() = runTest {
        // Set some state
        viewModel.updateName("Test")
        viewModel.updateDescription("Description")
        viewModel.updateTargetValue(10)

        viewModel.uiState.test {
            // Skip to updated state
            skipItems(1)

            // Reset
            viewModel.resetState()

            val resetState = awaitItem()
            assertEquals("Name should be cleared", "", resetState.name)
            assertEquals("Description should be cleared", "", resetState.description)
            assertEquals("TargetValue should reset to 1", 1, resetState.targetValue)
            assertFalse("isValid should be false", resetState.isValid)
            assertNull("savedHabitId should be null", resetState.savedHabitId)
        }
    }

    @Test
    fun initialState_hasNoErrorMessage() = runTest {
        val initialState = viewModel.uiState.value
        assertNull("Initial state should have no error message", initialState.errorMessage)
    }

    @Test
    fun saveHabit_duplicateName_setsErrorMessage() = runBlocking {
        // First, create a habit with a specific name
        viewModel.updateName("Duplicate Habit")

        viewModel.saveHabit()
        assertNotNull("First habit should be saved", awaitSaveResult().savedHabitId)

        // Reset and try to save duplicate
        viewModel.resetState()
        viewModel.updateName("Duplicate Habit")

        // Trigger save - this will launch a coroutine
        viewModel.saveHabit()
        val errorState = awaitSaveResult()

        // Verify error state
        assertNotNull("Error message should be set after timeout", errorState.errorMessage)
        assertEquals(
            "Error message should be correct",
            context.getString(com.dayforge.R.string.toast_duplicate_habit_name),
            errorState.errorMessage
        )
        assertFalse("Should not be saving", errorState.isSaving)
        assertNull("savedHabitId should be null", errorState.savedHabitId)
        assertEquals(listOf("Duplicate Habit"), habitDao.getAllHabitsOnce().map { it.name })
        assertEquals(1, database.syncOutboxDao().count())
    }

    @Test
    fun updateName_clearsErrorMessage() = runBlocking {
        // First, create a habit
        viewModel.updateName("Test Habit")
        viewModel.saveHabit()

        val savedState = awaitSaveResult()
        assertNotNull("First habit should be saved", savedState.savedHabitId)

        // Now create duplicate to trigger error
        viewModel.resetState()
        viewModel.updateName("Test Habit")
        viewModel.saveHabit()

        val errorState = awaitSaveResult()
        assertNotNull("Error should be set", errorState.errorMessage)

        // Now update name - should clear error
        viewModel.updateName("Different Habit Name")
        val clearedState = viewModel.uiState.value
        assertNull("Error should be cleared after name update", clearedState.errorMessage)
    }

    @Test
    fun outboxFailureDoesNotReportSaveSuccessAndRetryPersistsOneHabit() = runBlocking {
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_viewmodel_outbox BEFORE INSERT ON sync_outbox
            BEGIN SELECT RAISE(ABORT, 'test outbox failure'); END
        """.trimIndent())
        viewModel.updateName("Retry after storage failure")
        viewModel.saveHabit()
        val failure = awaitSaveResult()
        assertNull(failure.savedHabitId)
        assertNotNull(failure.errorMessage)
        assertFalse(failure.isSaving)
        assertTrue(habitDao.getAllHabitsOnce().isEmpty())
        assertEquals(0, database.syncOutboxDao().count())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_viewmodel_outbox")
        viewModel.updateName("Retry after storage failure")
        viewModel.saveHabit()
        val retryState = viewModel.uiState.value
        assertTrue("Retry must start or finish after a failed save", retryState.isSaving || retryState.savedHabitId != null)
        val success = awaitSaveResult()
        assertNotNull(success.savedHabitId)
        assertFalse(success.isSaving)
        viewModel.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        database = storage.reopen()
        habitDao = database.habitDao()
        assertEquals(listOf("Retry after storage failure"), habitDao.getAllHabitsOnce().map { it.name })
        assertEquals(success.savedHabitId, habitDao.getAllHabitsOnce().single().id)
        assertEquals(1, database.syncOutboxDao().count())
    }

    private suspend fun awaitSaveResult(): CreateHabitUiState {
        repeat(200) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            if (!state.isSaving && (state.savedHabitId != null || state.errorMessage != null)) {
                return state
            }
            delay(10)
        }
        error("Timed out waiting for habit save result")
    }
}
