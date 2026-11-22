package com.dayforge.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.DatabaseCreationInput
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.ui.screens.createhabit.CreateHabitViewModel
import com.dayforge.ui.screens.createmetric.CreateMetricViewModel
import com.dayforge.ui.screens.createtemptask.CreateTempTaskViewModel
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real production source-entry/VMs and Hilt Host; current() is setup/native oracle only. */
@HiltAndroidTest @RunWith(AndroidJUnit4::class)
class CreationSourcePhysicalTest {
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 2) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    @Inject lateinit var preferences: PreferencesManager
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private val retained = mutableListOf<ViewModel>()
    @Before fun start() = runBlocking<Unit> { hilt.inject(); epochs.connect(); epochs.login() }
    @After fun finish() = runBlocking<Unit> { withTimeout(5_000) {
        val jobs = withContext(Dispatchers.Main.immediate) {
            retained.mapNotNull { it.viewModelScope.coroutineContext[Job] }.also { models.clear() }
        }; jobs.joinAll()
    } }
    private suspend fun <T : ViewModel> retain(create: () -> T): T = withContext(Dispatchers.Main.immediate) {
        create().also { retained += it; models.put("vm-${retained.size}", it) }
    }
    private suspend fun source(): DatabaseCreationInput {
        val entry = retain { ObjectCreationSourceViewModel(epochs.host) }
        withContext(Dispatchers.Main.immediate) { entry.begin(null, false) }
        val result = withTimeout(5_000) { entry.state.first { it !is CreationSourceState.Loading } }
        assertTrue("Actual source entry failed: $result", result is CreationSourceState.Ready)
        return (result as CreationSourceState.Ready).input
    }
    private suspend fun completed(model: ViewModel, action: () -> Unit) {
        val jobs = withContext(Dispatchers.Main.immediate) {
            val parent = requireNotNull(model.viewModelScope.coroutineContext[Job]); val old = parent.children.toSet()
            action(); parent.children.filterNot { it in old }.toList()
        }; withTimeout(5_000) { jobs.joinAll() }
    }
    private fun metric() = MetricEntity(id = 71, name = "Original option", unit = "times", iconResId = 1,
        colorHex = "#123456", uuid = "bc195000-0000-4000-8000-000000000001")
    private fun habit() = HabitEntity(id = 72, name = "Original habit", habitType = HabitType.CHECK_IN,
        iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 1,
        uuid = "bc195000-0000-4000-8000-000000000011")

    @Test fun originalHabitFormCannotSaveAfterActualClearAndASeparateFreshFormCanSave() = runBlocking<Unit> {
        val input = source(); val form = retain { CreateHabitViewModel(epochs.host, preferences, context) }
        withContext(Dispatchers.Main.immediate) { form.beginSource(input); form.updateName("Private old draft") }
        epochs.clearAndAwaitReplacement(); val before = epochs.exactMain()
        completed(form) { form.updateName("Late old callback"); form.saveHabit() }
        assertEquals("", form.uiState.value.name); assertNull(form.uiState.value.savedHabitId)
        assertEquals(before, epochs.exactMain())
        val fresh = retain { CreateHabitViewModel(epochs.host, preferences, context) }; val next = source()
        withContext(Dispatchers.Main.immediate) { fresh.beginSource(next); fresh.updateName("Fresh actual form") }
        completed(fresh) { fresh.saveHabit() }
        val id = requireNotNull(fresh.uiState.value.savedHabitId)
        epochs.current { assertEquals("Fresh actual form", it.habitRepository.getHabitById(id)?.name) }
    }

    @Test fun habitFormDoesNotRecaptureSelectedMetricUuidAfterSameReplicaIdReuse() = runBlocking<Unit> {
        val original = metric(); epochs.current { it.database.withTransaction { it.metricDao.insert(original) } }
        val input = source(); val form = retain { CreateHabitViewModel(epochs.host, preferences, context) }
        withContext(Dispatchers.Main.immediate) {
            form.beginSource(input); form.updateName("Child with selected metric"); form.loadMetricsIfNeeded()
        }
        withTimeout(5_000) { form.uiState.first { it.availableMetrics.any { row -> row.uuid == original.uuid } } }
        withContext(Dispatchers.Main.immediate) { form.toggleMetricSelection(original.id, original.uuid) }
        epochs.current { graph -> graph.database.withTransaction {
            graph.metricDao.delete(original)
            graph.metricDao.insert(original.copy(uuid = "bc195000-0000-4000-8000-000000000002", name = "Replacement"))
        } }
        val before = epochs.exactMain(); completed(form) { form.saveHabit() }
        assertNull(form.uiState.value.savedHabitId)
        assertTrue(requireNotNull(form.uiState.value.errorMessage).contains("OBJECT_WRITE_LINK_CHANGED"))
        assertEquals(before, epochs.exactMain())
    }

    @Test fun metricFormKeepsSelectedHabitUuidThroughRefreshAndActualReusedIdRejection() = runBlocking<Unit> {
        val original = habit(); epochs.current { it.database.withTransaction { it.habitDao.insert(original) } }
        val input = source(); val form = retain { CreateMetricViewModel(context, epochs.host) }
        withContext(Dispatchers.Main.immediate) {
            form.beginSource(input); form.updateName("New metric"); form.updateUnit("times"); form.loadHabitsIfNeeded()
        }
        withTimeout(5_000) { form.uiState.first { it.availableHabits.any { row -> row.uuid == original.uuid } } }
        withContext(Dispatchers.Main.immediate) { form.toggleHabitSelection(original.id, original.uuid) }
        epochs.current { graph -> graph.database.withTransaction {
            graph.habitDao.delete(original)
            graph.habitDao.insert(original.copy(uuid = "bc195000-0000-4000-8000-000000000012", name = "Replacement"))
        } }
        val before = epochs.exactMain(); completed(form) { form.saveMetric() }
        assertNull(form.uiState.value.savedMetricId)
        assertTrue(requireNotNull(form.uiState.value.errorMessage).contains("OBJECT_WRITE_LINK_CHANGED"))
        assertEquals(before, epochs.exactMain())
    }

    @Test fun temporaryTaskFormCannotRebindItsOldDraftAfterSameDatabaseRelogin() = runBlocking<Unit> {
        val input = source(); val form = retain { CreateTempTaskViewModel(epochs.host, context) }
        withContext(Dispatchers.Main.immediate) { form.beginSource(input); form.updateName("Private old draft") }
        val database = epochs.current { it.database }; epochs.login(); epochs.current { assertSame(database, it.database) }
        val before = epochs.exactMain(); completed(form) { form.updateName("Late text"); form.saveTempTask() }
        assertEquals("", form.uiState.value.name); assertNull(form.uiState.value.savedHabitId)
        val next = source()
        val failure = withContext(Dispatchers.Main.immediate) { runCatching { form.beginSource(next) }.exceptionOrNull() }
        assertEquals("DATABASE_EPOCH_SELECTION_CHANGED", failure?.message); assertEquals(before, epochs.exactMain())
    }
}
