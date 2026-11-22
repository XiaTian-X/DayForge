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

/** Actual main-panel consumer, shared Host/Gate and native primary state. No real PID death,
 * system task handoff, pixel timing or main navigation journey is inferred from this class. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CountCommandPendingConsumerTest {
    private val nativeBarrier = CountRecoveryConsumerNativeBarrier()
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { it.nativeFactory = nativeBarrier }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var gate: CountActionGate
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private var model: CountCommandRecoveryViewModel? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private lateinit var habit: HabitEntity
    private val workflow get() = CountCommandRecoveryWorkflow(app, epochs.host, gate)

    @Before fun connect() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login()
        habit = HabitEntity(id = 814, name = "Original pending COUNT", habitType = HabitType.COUNTING,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 10,
            uuid = UUID.randomUUID().toString())
        epochs.current { graph -> graph.database.withTransaction {
            val sql = graph.database.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            assertEquals(habit.id, graph.habitDao.insert(habit))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        } }
    }
    @After fun close() = runBlocking<Unit> {
        nativeBarrier.release()
        activity?.close()
        model?.let { vm ->
            val job = withContext(Dispatchers.Main.immediate) {
                requireNotNull(vm.viewModelScope.coroutineContext[Job]).also { models.clear() }
            }
            withTimeout(5_000) { job.join() }
        }
    }
    private suspend fun prepared(index: Int, original: com.dayforge.domain.model.CountActionIdentity? = null): CountCommandReference = epochs.current { graph ->
        val identity = original ?: graph.countActionReader.legacy(habit)
        val absent = 900_000L + index
        assertNull(graph.completionDao.getCompletionById(absent))
        val input = CountCommandInputs.explicitTargetAbsent(graph.database, UUID.randomUUID().toString(), absent, identity)
        val store = CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
        val result = store.register(input)
        store.releaseUnadmitted(requireNotNull(result.ticket))
        CountCommandReference.from(result.reference)
    }
    private suspend fun native(reference: CountCommandReference) = epochs.current { graph ->
        CountCommandStore(graph.database, epochs.tokens, epochs.sessions).resolve(reference.native())
    }
    private suspend fun noBusiness() = epochs.current { graph ->
        assertTrue(graph.completionDao.getAllCompletionsOnce().isEmpty())
        assertTrue(graph.syncOutboxDao.getAll().isEmpty())
        assertEquals(0, graph.metricLogDao.countAll())
    }
    private suspend fun open() {
        withContext(Dispatchers.Main.immediate) {
            model = CountCommandRecoveryViewModel(epochs.host, workflow).also { models.put("pending", it) }
        }
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { screen ->
            screen.setContent { MaterialTheme { CountCommandRecoveryPanel(requireNotNull(model)) } }
        } }
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it != null } }
        compose.waitForIdle()
    }
    private suspend fun joinedAction(click: () -> Unit) {
        val vm = requireNotNull(model)
        val children = withContext(Dispatchers.Main.immediate) {
            val job = requireNotNull(vm.viewModelScope.coroutineContext[Job]); val old = job.children.toSet()
            click(); job.children.filter { it !in old }.toList()
        }
        withTimeout(5_000) { children.forEach { it.join() } }
    }

    @Test(timeout = 150_000) fun unresolvedFirstPageDoesNotHideLaterOriginalIdsAndReadsArePure() = runBlocking<Unit> {
        val identity = epochs.current { it.countActionReader.legacy(habit) }
        val refs = (0..32).map { prepared(it, identity) }
        val before = epochs.exactMain()
        val first = workflow.page(); assertEquals(32, first.value.entries.size); assertFalse(first.value.complete)
        assertEquals(refs.take(32), first.value.entries.map { it.reference })
        val second = workflow.page(first); assertEquals(listOf(refs.last()), second.value.entries.map { it.reference })
        assertTrue(second.value.complete)
        assertTrue(first.value.entries.all { it.execution == CountCommandRecords.Execution.PREPARED })
        assertEquals(before, epochs.exactMain()); noBusiness()
    }

    @Test(timeout = 150_000) fun changedRootRejectsTheOldContinuationWithoutMixingDirectoryVersions() = runBlocking<Unit> {
        prepared(0); prepared(1)
        val first = epochs.host.snapshot { it.countCommandPendingRepository.page(size = 1) }
        assertFalse(first.value.complete)
        val appended = prepared(2); val before = epochs.exactMain()
        val failure = try { workflow.page(first); null } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) { error }
        assertEquals("COMMAND_DISCOVERY_ROOT_CHANGED", failure?.message)
        assertEquals(before, epochs.exactMain())
        val current = workflow.page(); assertTrue(current.value.complete)
        assertEquals(3, current.value.entries.size); assertEquals(appended, current.value.entries.last().reference)
        noBusiness()
    }

    @Test(timeout = 150_000) fun actualVisibleContinueDeliversTheSameNoEffectWithoutInventingABusinessFact() = runBlocking<Unit> {
        val reference = prepared(0); open()
        compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).assertIsEnabled().performClick()
        val frame = withTimeout(45_000) { requireNotNull(model).frames.first { it?.value?.result is CountCommandPendingRepository.Result.Saved } }!!
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        val result = frame.value.result as CountCommandPendingRepository.Result.Saved
        assertEquals(reference, result.receipt.reference); assertFalse(result.receipt.recorded)
        compose.onNodeWithText(app.getString(R.string.count_recovery_no_effect)).assertExists()
        val stored = native(reference).historical
        assertEquals(CountCommandRecords.Execution.NO_EFFECT, stored.status.execution)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, stored.status.delivery)
        noBusiness()
        val before = epochs.exactMain()
        // Reusing the same still-visible original ID observes delivery; no second result/T2.
        val previous = requireNotNull(model).frames.value
        compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).assertIsEnabled().performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it !== previous } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        assertEquals(before, epochs.exactMain()); noBusiness()
    }

    @Test(timeout = 150_000) fun alreadyHeldSharedGateDeclinesTheOriginalPendingWithoutQueueingExecution() = runBlocking<Unit> {
        val reference = prepared(0); open()
        val access = requireNotNull(epochs.tokens.localCoreWriteAccess())
        val lease = requireNotNull(gate.tryAcquire(access.session, habit.uuid, habit.id))
        try {
            val before = epochs.exactMain()
            compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).performClick()
            withTimeout(5_000) { requireNotNull(model).failed.first { it } }
            assertEquals(before, epochs.exactMain()); noBusiness()
            assertEquals(CountCommandRecords.Execution.PREPARED, native(reference).historical.status.execution)
        } finally { gate.release(lease) }
    }

    @Test(timeout = 150_000) fun capturedOldPanelCallbackCannotRebindToANewAuthenticatedOwner() = runBlocking<Unit> {
        prepared(0); open()
        val callback = requireNotNull(compose.onNodeWithText(app.getString(R.string.count_recovery_continue))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val source = requireNotNull(model).frames.value!!
        compose.mainClock.autoAdvance = false
        try {
            epochs.sessions.exclusive { epochs.tokens.saveLoginSession("replacement-access", "replacement-refresh", "member",
                "ac490000-0000-4000-8000-000000000001", false) }
            assertFalse(source.publication.isCurrentInMemory())
            epochs.current { }
            val before = epochs.exactMain()
            requireNotNull(activity).onActivity { assertFalse(it.isFinishing); assertFalse(it.isDestroyed) }
            joinedAction { assertTrue(callback.invoke()) }
            assertEquals(before, epochs.exactMain()); noBusiness()
            assertTrue(requireNotNull(model).failed.value)
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test(timeout = 150_000) fun closedPanelCannotConsumeALateActualCommittedResultButFreshPanelCanDeliverSameId() = runBlocking<Unit> {
        val reference = prepared(0); open()
        // Capture real UI actions before pausing the Room thread, not a fake suspend writer.
        val continueAction = requireNotNull(compose.onNodeWithText(app.getString(R.string.count_recovery_continue))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val closeAction = requireNotNull(compose.onNodeWithText(app.getString(R.string.count_recovery_close))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        nativeBarrier.arm()
        try {
            withContext(Dispatchers.Main.immediate) { assertTrue(continueAction.invoke()) }
            assertTrue(withContext(Dispatchers.IO) { nativeBarrier.awaitCommitted() })
            assertEquals(1, nativeBarrier.committedOutcomeInserts)
            requireNotNull(activity).onActivity { assertFalse(it.isFinishing); assertFalse(it.isDestroyed) }
            withContext(Dispatchers.Main.immediate) { assertTrue(closeAction.invoke()) }
        } finally { nativeBarrier.release() }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        val pending = native(reference).historical
        assertEquals(CountCommandRecords.Execution.NO_EFFECT, pending.status.execution)
        assertEquals(CountCommandRecords.Delivery.RESULT_PENDING, pending.status.delivery)
        assertTrue(pending.status.pending); noBusiness()
        val originalInput = pending.intent.text()
        compose.onNodeWithText(app.getString(R.string.count_recovery_open)).performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it != null } }
        compose.onNodeWithText(app.getString(R.string.count_recovery_continue)).assertIsEnabled().performClick()
        withTimeout(45_000) { requireNotNull(model).frames.first { it?.value?.result is CountCommandPendingRepository.Result.Saved } }
        withTimeout(45_000) { requireNotNull(model).busy.first { !it } }
        val delivered = native(reference).historical
        assertEquals(originalInput, delivered.intent.text())
        assertEquals(CountCommandRecords.Delivery.DELIVERED, delivered.status.delivery)
        assertEquals(1, nativeBarrier.committedOutcomeInserts)
        epochs.current { graph -> graph.database.openHelper.writableDatabase.query("SELECT count(*) FROM main.count_command_results").use {
            assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
        } }
        noBusiness()
    }

}
