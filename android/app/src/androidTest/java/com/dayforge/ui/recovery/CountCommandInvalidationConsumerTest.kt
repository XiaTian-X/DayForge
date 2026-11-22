package com.dayforge.ui.recovery

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.*
import com.dayforge.domain.service.CountActionGate
import com.dayforge.domain.service.CountCommandRecoveryWorkflow
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual invalidated result-model consumer; no invented APPLIED/NO_EFFECT result or pixel claim. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CountCommandInvalidationConsumerTest {
    private val barrier = CountMetricResultReadBarrier()
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { it.nativeFactory = barrier }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var gate: CountActionGate
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private var model: CountCommandRecoveryViewModel? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private lateinit var reference: CountCommandReference
    private lateinit var original: String
    @Before fun connect() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login(); epochs.tokens.getOrCreateInstallationId()
        val habit = HabitEntity(id = 820, name = "Original plan awaiting execution", habitType = HabitType.COUNTING,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 10,
            uuid = UUID.randomUUID().toString())
        epochs.current { graph ->
            graph.database.withTransaction {
                val sql = graph.database.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                assertEquals(habit.id, graph.habitDao.insert(habit)); sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
            val identity = graph.countActionReader.legacy(habit)
            val input = CountCommandLegacyInput.add(graph.database, requireNotNull(epochs.tokens.localCoreWriteAccess()),
                habit, identity, Instant.now(), ZoneId.systemDefault(), 1)
            val store = CountCommandStore(graph.database, epochs.tokens, epochs.sessions); val saved = store.register(input)
            store.releaseUnadmitted(requireNotNull(saved.ticket)); reference = CountCommandReference.from(saved.reference); original = input.text()
            graph.habitRepository.updateHabit(habit.copy(targetValue = 11))
        }
    }
    @After fun finish() = runBlocking<Unit> {
        barrier.release(); activity?.close()
        model?.let { vm ->
            val job = withContext(Dispatchers.Main.immediate) { requireNotNull(vm.viewModelScope.coroutineContext[Job]).also { models.clear() } }
            withTimeout(5_000) { job.join() }
        }
    }
    private suspend fun row() = epochs.current { graph ->
        CountCommandStore(graph.database, epochs.tokens, epochs.sessions).resolve(reference.native()).historical
    }
    private suspend fun noExecution() = epochs.current { graph ->
        assertTrue(graph.completionDao.getAllCompletionsOnce().isEmpty())
        val result = CountCommandStore(graph.database, epochs.tokens, epochs.sessions).resolve(reference.native()).historical
        assertEquals(original, result.intent.text()); assertNull(result.outcome)
    }
    private suspend fun open() {
        withContext(Dispatchers.Main.immediate) {
            model = CountCommandRecoveryViewModel(epochs.host, CountCommandRecoveryWorkflow(app, epochs.host, gate))
                .also { models.put("invalidation-result", it) }
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { screen ->
            screen.setContent { MaterialTheme { CountCommandRecoveryPanel(requireNotNull(model)) } }
        } }
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it != null } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertFalse(requireNotNull(model).failed.value)
    }
    private fun proceed() = compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).assertIsEnabled().performClick()
    private suspend fun awaitResult() = withTimeout(45_000) {
        requireNotNull(model).frames.first { it?.value?.result is CountCommandPendingRepository.Result.Invalidated }
    }!!
    @Test(timeout = 150_000) fun visibleContinueExplainsTheOriginalUnexecutedPlanAndDeliversWithoutBusinessOutcome() = runBlocking<Unit> {
        open(); proceed(); val displayed = awaitResult(); withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertFalse(requireNotNull(model).failed.value)
        val result = displayed.value.result as CountCommandPendingRepository.Result.Invalidated
        assertEquals(reference, result.reference); assertNull(displayed.value.followUp)
        compose.onNodeWithText(app.getString(R.string.count_recovery_invalidated_plan)).assertExists()
        assertEquals(CountCommandRecords.Execution.INVALIDATED, row().status.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row().status.delivery); noExecution()
        val before = epochs.exactMain(); val old = requireNotNull(model).frames.value
        proceed(); withTimeout(45_000) { requireNotNull(model).frames.first { it !== old } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertEquals(before, epochs.exactMain()); noExecution()
    }
    @Test(timeout = 150_000) fun closeAfterActualReadEndRetainsUndeliveredInvalidationUntilANewExplicitConsumer() = runBlocking<Unit> {
        open(); barrier.arm(reference.commandId); proceed()
        assertTrue(withContext(Dispatchers.IO) { barrier.awaitReadEnd() })
        compose.onNodeWithText(app.getString(R.string.count_recovery_close)).assertIsEnabled().performClick(); barrier.release()
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertEquals(1, barrier.ends); assertNull(requireNotNull(model).frames.value)
        assertEquals(CountCommandRecords.Execution.INVALIDATED, row().status.execution)
        assertEquals(CountCommandRecords.Delivery.RESULT_PENDING, row().status.delivery); noExecution()
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it != null } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        proceed(); awaitResult(); withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertEquals(CountCommandRecords.Delivery.DELIVERED, row().status.delivery); noExecution()
    }
}
