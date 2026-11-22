package com.dayforge.ui.recovery

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
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

/** Actual current-source user Continue after login, paired with the old live callback.
 * This is panel/Hilt/Room evidence, not Launcher or a process-death simulation. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CountCommandReauthenticationPanelTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var gate: CountActionGate
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private var model: CountCommandRecoveryViewModel? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private lateinit var habit: HabitEntity
    @Before fun connect() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login(); epochs.tokens.getOrCreateInstallationId()
        habit = HabitEntity(id = 818, name = "Original generic intent", habitType = HabitType.COUNTING,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 10,
            uuid = UUID.randomUUID().toString())
        epochs.current { graph -> graph.database.withTransaction {
            val sql = graph.database.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            assertEquals(habit.id, graph.habitDao.insert(habit)); sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        } }
    }
    @After fun finish() = runBlocking<Unit> {
        activity?.close()
        model?.let { vm ->
            val job = withContext(Dispatchers.Main.immediate) { requireNotNull(vm.viewModelScope.coroutineContext[Job]).also { models.clear() } }
            withTimeout(5_000) { job.join() }
        }
    }
    private suspend fun prepared(): Pair<CountCommandReference, String> = epochs.current { graph ->
        val identity = graph.countActionReader.legacy(habit)
        assertNull(graph.completionDao.getCompletionById(980_000))
        val input = CountCommandInputs.explicitTargetAbsent(graph.database, UUID.randomUUID().toString(), 980_000, identity)
        val store = CountCommandStore(graph.database, epochs.tokens, epochs.sessions); val saved = store.register(input)
        store.releaseUnadmitted(requireNotNull(saved.ticket))
        CountCommandReference.from(saved.reference) to input.text()
    }
    private suspend fun open() {
        withContext(Dispatchers.Main.immediate) {
            model = CountCommandRecoveryViewModel(epochs.host, CountCommandRecoveryWorkflow(app, epochs.host, gate))
                .also { models.put("reauth-panel", it) }
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { screen ->
            screen.setContent { MaterialTheme { CountCommandRecoveryPanel(requireNotNull(model)) } }
        } }
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it != null } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertFalse(requireNotNull(model).failed.value)
    }
    private suspend fun relogin() {
        val old = requireNotNull(epochs.tokens.localCoreWriteAccess())
        val installation = epochs.tokens.registeredInstallationId()
        epochs.sessions.exclusive { epochs.tokens.saveLoginSession("same-account-new-session", "same-account-new-refresh", "member",
            old.session.authentication.userId, false) }
        val current = requireNotNull(epochs.tokens.localCoreWriteAccess())
        assertNotEquals(old.session.authentication.generation, current.session.authentication.generation)
        assertEquals(old.session.authentication.userId, current.session.authentication.userId)
        assertEquals(installation, epochs.tokens.registeredInstallationId())
        epochs.current { }
    }
    private suspend fun terminal(reference: CountCommandReference) = epochs.current { graph ->
        CountCommandStore(graph.database, epochs.tokens, epochs.sessions).resolve(reference.native()).historical
    }

    @Test(timeout = 150_000) fun oldPanelCallbackStaysInvalidButFreshExplicitPanelCompletesSameOriginalNoEffect() = runBlocking<Unit> {
        val (reference, frozen) = prepared(); open()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(app.getString(R.string.count_recovery_continue)).fetchSemanticsNodes().isNotEmpty() }
        val click = requireNotNull(compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        val oldSource = requireNotNull(model).frames.value!!
        compose.mainClock.autoAdvance = false
        try {
            relogin(); assertFalse(oldSource.publication.isCurrentInMemory())
            val before = epochs.exactMain()
            val actions = withContext(Dispatchers.Main.immediate) {
                assertFalse(requireNotNull(model).busy.value)
                assertFalse(requireNotNull(model).failed.value)
                val job = requireNotNull(requireNotNull(model).viewModelScope.coroutineContext[Job]); val previous = job.children.toSet()
                assertTrue(click.invoke()); job.children.filter { it !in previous }.toList()
            }
            withTimeout(5_000) { actions.forEach { it.join() } }
            assertFalse(requireNotNull(model).busy.value)
            assertTrue(requireNotNull(model).failed.value)
            assertEquals(before, epochs.exactMain())
            assertEquals(CountCommandRecords.Execution.PREPARED, terminal(reference).status.execution)
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithText(app.getString(R.string.count_recovery_refresh)).assertIsEnabled().performClick()
        val fresh = withTimeout(45_000) { requireNotNull(model).frames.first { it != null && it !== oldSource } }!!
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertFalse(requireNotNull(model).failed.value)
        assertEquals(reference, fresh.value.page.value.entries.single().reference)
        val beforeOldAgain = epochs.exactMain()
        val staleActions = withContext(Dispatchers.Main.immediate) {
            assertFalse(requireNotNull(model).busy.value)
            assertFalse(requireNotNull(model).failed.value)
            val job = requireNotNull(requireNotNull(model).viewModelScope.coroutineContext[Job]); val previous = job.children.toSet()
            assertTrue(click.invoke()); job.children.filter { it !in previous }.toList()
        }
        withTimeout(5_000) { staleActions.forEach { it.join() } }
        assertFalse(requireNotNull(model).busy.value)
        assertTrue(requireNotNull(model).failed.value)
        assertEquals(beforeOldAgain, epochs.exactMain())
        assertSame(fresh, requireNotNull(model).frames.value)
        compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).assertIsEnabled().performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it?.value?.result is CountCommandPendingRepository.Result.Saved } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        val result = terminal(reference)
        assertEquals(frozen, result.intent.text()); assertEquals(CountCommandRecords.Execution.NO_EFFECT, result.status.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, result.status.delivery)
        epochs.current { graph -> assertTrue(graph.completionDao.getAllCompletionsOnce().isEmpty()); assertTrue(graph.syncOutboxDao.getAll().isEmpty()) }
    }

    @Test(timeout = 150_000) fun freshSessionExplicitContinueStillHonorsExistingSameHabitGateWithoutQueuedRetry() = runBlocking<Unit> {
        val (reference, frozen) = prepared(); relogin(); open()
        val access = requireNotNull(epochs.tokens.localCoreWriteAccess())
        val held = requireNotNull(gate.tryAcquire(access.session, habit.uuid, habit.id))
        try {
            val before = epochs.exactMain()
            compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).performClick()
            withTimeout(5_000) { requireNotNull(model).failed.first { it } }
            assertEquals(before, epochs.exactMain())
            val result = terminal(reference); assertEquals(frozen, result.intent.text())
            assertEquals(CountCommandRecords.Execution.PREPARED, result.status.execution)
        } finally { gate.release(held) }
    }
}
