package com.dayforge.data.repository

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.CountTrustRetirementReason
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.service.SyncManager
import com.dayforge.ui.components.MetricSubmissionPhase
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.components.PostCheckInDialog
import com.dayforge.ui.metrics.FactMetricPrompt
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.metrics.LinkedMetricPromptState
import com.dayforge.ui.producerEpochFrameState
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Hilt Host, original formal producer/receipt and original delivery sequence.
 * Native observations never manufacture END, a receipt, source authority or a UI permission. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MetricSubmissionPhasePhysicalTest {
    private val native = MetricSubmissionPhaseNativeBoundary()
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { it.nativeFactory = native }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var coordinator: LinkedMetricCoordinator
    @Inject lateinit var sync: SyncManager
    private val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var peer: FormalCountJourneyServer? = null
    private var activity: ActivityScenario<ComponentActivity>? = null
    private val operations = CopyOnWriteArrayList<Job>()
    private lateinit var parent: CountCommandReceipt
    private lateinit var frame: DatabaseEpochValue<LinkedMetricPromptState?>
    private val prompt: FactMetricPrompt get() = requireNotNull(frame.value?.factPrompt)
    private val values: List<MetricValueInput> get() = requireNotNull(frame.value).linkedMetrics.mapIndexed { index, metric ->
        MetricValueInput(metric.metricId, 41.125 + index, "original phase value $index")
    }

    @After fun finish() = runBlocking<Unit> {
        native.release()
        activity?.close(); activity = null
        operations.forEach { it.cancel() }
        for (operation in operations) withTimeout(5_000) { operation.join() }
        peer?.close(); peer = null
    }

    private suspend fun prepare() {
        hilt.inject(); epochs.connect(); epochs.login()
        val server = FormalCountJourneyServer(false).also { peer = it }
        preferences.setServerUrl(server.server.origin.toString()); preferences.setRemoteServerUrl(null)
        sync.sync().getOrThrow() // Actual registration and round bootstrap, not seeded proof rows.
        prepareCurrentPrompt(server)
    }

    private suspend fun prepareCurrentPrompt(server: FormalCountJourneyServer) {
        epochs.current { graph ->
            val habit = requireNotNull(graph.habitDao.getHabitByUuid(server.selected.uuid))
            assertNotNull(habit.appearance); assertNotNull(habit.planMetadata)
            assertEquals(1, graph.database.nextSyncStateDao().rows().single().challengeContract)
            preferences.setNeverAskAgain(requireNotNull(epochs.tokens.localCoreWriteAccess()).session, habit.uuid, false)
            parent = requireNotNull(graph.habitRepository.logCompletionWithReceipt(app, habit.id,
                authority = requireNotNull(graph.nextObjectEditor.habit(habit.id).authority), expectedHabitUuid = habit.uuid).command)
            val source = graph.widgetFactReader.read(requireNotNull(graph.habitDao.getVisibleHabitById(habit.id)))
            preferences.addPendingMetricHabit(source.claim.session(), habit.uuid)
            coordinator.showCompletionPrompt(source, coordinator.captureCompletionPrompt(), command = parent)
        }
        frame = requireNotNull(coordinator.postCheckInFrames.value)
        assertEquals(parent.commandId, prompt.command?.commandId)
        assertEquals(2, requireNotNull(frame.value).linkedMetrics.size)
        assertNotNull(prompt.completion); assertNotNull(prompt.submissionPhase)
        assertNull(requireNotNull(prompt.submissionPhase).value)
        assertTrue(frame.publication.belongsTo(epochs.host)); assertTrue(frame.publication.isCurrentInMemory())
    }

    private suspend fun submit(original: DatabaseEpochValue<LinkedMetricPromptState?> = frame,
        inputs: List<MetricValueInput> = values, neverAskAgain: Boolean = false): Boolean = withContext(Dispatchers.Main.immediate) {
        epochs.host.withSourceGraph(original.publication) {
            val source = requireNotNull(original.value?.factPrompt)
            coordinator.recordMetricValues(source.habit.id, inputs, expectedFactPrompt = source, frame = original,
                neverAskAgain = neverAskAgain)
        }
    }

    private fun CoroutineScope.recordPhases(original: FactMetricPrompt, seen: MutableList<MetricSubmissionPhase?>): Job =
        launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            requireNotNull(original.submissionPhase).collect { seen += it }
        }.also(operations::add)

    private fun CoroutineScope.startSubmission(original: DatabaseEpochValue<LinkedMetricPromptState?> = frame,
        inputs: List<MetricValueInput> = values): Deferred<Boolean> = async(Dispatchers.Default) {
        submit(original, inputs)
    }.also(operations::add)

    private suspend fun awaitBoundary() = withContext(Dispatchers.IO) {
        assertTrue("Original native transaction did not reach the fixed phase boundary", native.awaitBoundary())
    }

    private fun childReference(image: MetricSubmissionPhaseNativeBoundary.Image,
        parentReceipt: CountCommandReceipt = parent): CountCommandReference {
        val intents = image.tables.getValue("count_command_intents")
        val child = intents.rows.single { intents.text(it, "action") == CountCommandRecords.Action.LINKED_METRICS.name }
        return CountCommandReference(intents.text(child, "commandId"), intents.text(child, "inputHash"),
            intents.text(child, "namespaceHash"), parentReceipt.datasetId)
    }

    private fun assertPendingNative(image: MetricSubmissionPhaseNativeBoundary.Image) {
        val statuses = image.tables.getValue("count_command_status")
        assertEquals(2, statuses.rows.size)
        val decoded = statuses.rows.map { row ->
            val text = statuses.text(row, "stateJson").toByteArray(Charsets.UTF_8)
            CountCommandRecords.Status.decode(text, text.size) {}
        }
        assertTrue(decoded.all { it.pending && it.execution == CountCommandRecords.Execution.APPLIED })
        assertEquals(CountCommandRecords.Delivery.METRICS_COMMITTED, decoded.single { it.commandId == parent.commandId }.delivery)
        assertEquals(2, image.tables.getValue("metric_logs").rows.size)
        assertEquals(2, image.tables.getValue("count_command_results").rows.size)
    }

    private suspend fun currentImage(): JsonObject = epochs.current { graph -> graph.database.withTransaction {
        NextReplicaStorageProof(graph.database).readInTransaction().contents
    } }

    private suspend fun sameBusinessAndOriginalGroup(image: MetricSubmissionPhaseNativeBoundary.Image,
        child: CountCommandReference, pending: Boolean) = epochs.current { graph ->
        val store = CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
        val childRow = store.resolve(child.native()).historical
        val parentRow = store.resolve(parent.reference.native()).historical
        assertEquals(child.commandId, parentRow.status.child)
        assertEquals(parent.commandId, childRow.status.parent)
        assertEquals(pending, childRow.status.pending); assertEquals(pending, parentRow.status.pending)
        for (table in listOf("metric_logs", "count_command_intents", "count_command_results")) {
            val expected = image.tables.getValue(table)
            graph.database.openHelper.writableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                assertEquals(expected.columns, cursor.columnNames.toList())
                val rows = buildList {
                    while (cursor.moveToNext()) add(expected.columns.indices.map { column ->
                        val type = cursor.getType(column)
                        MetricSubmissionPhaseNativeBoundary.Cell(type, when (type) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column).toString()
                            android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits().toString()
                            android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                            else -> error("PHASE_TEST_UNEXPECTED_NATIVE_TYPE")
                        })
                    })
                }
                assertEquals("Original $table changed", expected.rows, rows)
            }
        }
    }

    @Test(timeout = 150_000) fun lostActualMetricEndNeverPublishesSavedBeforeOriginalIdRecovery() = runBlocking<Unit> {
        prepare()
        val original = prompt; val originalFrame = frame
        val seen = CopyOnWriteArrayList<MetricSubmissionPhase?>()
        val observer = recordPhases(original, seen)
        val old = epochs.current { requireNotNull(CountTrustRuntime.forDatabase(it.database)) }
        native.arm(MetricSubmissionPhaseNativeBoundary.Mode.LOSE_METRIC_END)
        val operation = startSubmission()
        try {
            awaitBoundary()
            assertFalse(native.trace.metricsCommitted.isCompleted)
            assertNull(original.submittedCommand)
            assertEquals(MetricSubmissionPhase.SAVING, requireNotNull(original.submissionPhase).value)
            native.release()
            assertFalse(operation.await())
        } finally { native.release(); withTimeout(5_000) { operation.cancelAndJoin() } }
        assertNotNull(native.failure)
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in old.retirementReasons)
        assertTrue(seen.none { it in setOf(MetricSubmissionPhase.SAVED_DELIVERING, MetricSubmissionPhase.SAVED_PENDING, MetricSubmissionPhase.DELIVERED) })
        assertNull(original.submittedCommand)
        withTimeout(45_000) {
            requireNotNull(original.submissionPhase).first { it == null }
            epochs.host.controlChanges.first { !originalFrame.publication.isCurrentInMemory() }
        }
        assertFalse(originalFrame.publication.isCurrentInMemory())
        val committedImage = requireNotNull(native.image)
        val child = childReference(committedImage)
        assertPendingNative(committedImage)
        epochs.current { graph ->
            assertNotSame(old, CountTrustRuntime.forDatabase(graph.database))
            val recovered = graph.metricRepository.resumeLinkedMetricCommand(child)
            assertTrue(recovered is CountCommandRecovery.HistoricalCommitted)
            assertEquals(child, (recovered as CountCommandRecovery.HistoricalCommitted).receipt.reference)
        }
        sameBusinessAndOriginalGroup(committedImage, child, pending = true)
        assertNull(requireNotNull(original.submissionPhase).value)
        observer.cancelAndJoin(); assertTrue(native.trace.observationFailures.isEmpty())
    }

    @Test(timeout = 150_000) fun realSavedPhaseIsVisibleWhileOriginalT2HasNotChangedEitherPendingMember() = runBlocking<Unit> {
        prepare()
        val original = prompt; val originalFrame = frame; val inputs = values
        native.arm(MetricSubmissionPhaseNativeBoundary.Mode.PAUSE_T2_BEFORE_STATUS)
        val result = CompletableDeferred<Boolean>()
        val recordJobs = Channel<Job>(Channel.UNLIMITED)
        val displayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        operations += requireNotNull(displayScope.coroutineContext[Job])
        val displayed = producerEpochFrameState(displayScope, epochs.host, SharingStarted.Eagerly,
            coordinator.postCheckInFrames, coordinator::publishPromptFrame)
        assertSame(originalFrame, withTimeout(45_000) { displayed.first { it === originalFrame } })
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { screen ->
            screen.setContent { MaterialTheme {
                val accepted by displayed.collectAsState()
                accepted?.value?.let { state ->
                    PostCheckInDialog(state.habitName, state.linkedMetrics,
                        onRecord = { actual, neverAsk ->
                            val job = requireNotNull(currentCoroutineContext()[Job])
                            operations += job
                            check(recordJobs.trySend(job).isSuccess)
                            result.complete(submit(originalFrame, actual, neverAsk))
                        },
                        onSkip = { error("Phase observation must not skip original follow-up") },
                        onDismiss = { epochs.host.withSourceGraph(originalFrame.publication) {
                            coordinator.closePrompt(state, originalFrame)
                        } },
                        initialInputs = inputs.associate { it.metricId to (it.value.toString() to it.note) },
                        submissionProgress = state.factPrompt?.submissionPhase,
                        savedSubmission = { state.factPrompt?.savedSubmissionForDisplay() },
                        onContinue = {
                            val job = requireNotNull(currentCoroutineContext()[Job])
                            operations += job; check(recordJobs.trySend(job).isSuccess)
                            result.complete(epochs.host.withSourceGraph(originalFrame.publication) {
                                coordinator.continueSavedMetricDelivery(original.habit.id, original, originalFrame)
                            })
                        })
                }
            } }
        } }
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(app.getString(R.string.post_checkin_record)).performClick()
        val firstRecord = withTimeout(45_000) { recordJobs.receive() }
        try {
            awaitBoundary()
            assertTrue(native.trace.metricsCommitted.isCompleted); assertFalse(native.trace.delivered.isCompleted)
            assertNotNull(original.submittedCommand)
            assertEquals(MetricSubmissionPhase.SAVED_DELIVERING, requireNotNull(original.submissionPhase).value)
            assertSame(originalFrame, coordinator.postCheckInFrames.value)
            assertSame(originalFrame.value, coordinator.postCheckInState.value)
            assertPendingNative(requireNotNull(native.image))
            compose.onNodeWithText(app.getString(R.string.metric_submission_saved_delivering)).assertIsDisplayed()
            assertFalse(result.isCompleted)
            // Cancel the actual ongoing Dialog submission while original T2 still owns
            // its transaction. The release permits its real cancellation/rollback path.
            firstRecord.cancel()
            native.release()
            withTimeout(5_000) { firstRecord.join() }
        } finally { native.release() }
        assertTrue(firstRecord.isCancelled); assertFalse(result.isCompleted)
        assertSame(originalFrame, coordinator.postCheckInFrames.value)
        assertSame(original, coordinator.postCheckInState.value?.factPrompt)
        assertEquals(MetricSubmissionPhase.SAVED_PENDING, requireNotNull(original.submissionPhase).value)
        compose.onNodeWithText(app.getString(R.string.metric_submission_saved_pending)).assertIsDisplayed()
        val receipt = requireNotNull(original.submittedCommand)
        sameBusinessAndOriginalGroup(requireNotNull(native.image), receipt.reference, pending = true)
        assertFalse(native.trace.delivered.isCompleted)
        val saved = requireNotNull(original.savedSubmissionForDisplay())
        assertEquals(inputs, saved.values); assertTrue(saved.neverAskAgain)
        compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
        assertFalse(submit(originalFrame, inputs.mapIndexed { index, value ->
            if (index == 0) value.copy(value = value.value + 1.0) else value
        }, neverAskAgain = true))
        assertEquals(MetricSubmissionPhase.SAVED_PENDING, requireNotNull(original.submissionPhase).value)
        assertFalse(submit(originalFrame, inputs, neverAskAgain = false))
        assertEquals(MetricSubmissionPhase.SAVED_PENDING, requireNotNull(original.submissionPhase).value)
        assertSame(saved, original.savedSubmissionForDisplay())
        sameBusinessAndOriginalGroup(requireNotNull(native.image), receipt.reference, pending = true)
        // Continue supplies no values or checkbox choice; it resumes the original child only.
        compose.onNodeWithText(app.getString(R.string.metric_submission_continue)).performClick()
        val retry = withTimeout(45_000) { recordJobs.receive() }
        assertNotSame(firstRecord, retry)
        assertTrue(withTimeout(45_000) { result.await() })
        withTimeout(5_000) { retry.join() }
        assertEquals(MetricSubmissionPhase.DELIVERED, requireNotNull(original.submissionPhase).value)
        val child = requireNotNull(original.submittedCommand).reference
        assertSame(receipt, original.submittedCommand)
        sameBusinessAndOriginalGroup(requireNotNull(native.image), child, pending = false)
        assertEquals(1, native.trace.ends.count { it.kind == CountJourneyNativeTrace.Kind.METRIC_T1 })
        recordJobs.close()
        assertTrue(native.trace.observationFailures.isEmpty())
    }

    @Test(timeout = 150_000) fun changedAuthenticationAndRealHostReplacementCannotPublishAnOldAttemptsPhaseIntoANewPrompt() = runBlocking<Unit> {
        prepare()
        val original = prompt; val originalFrame = frame; val inputs = values
        native.arm(MetricSubmissionPhaseNativeBoundary.Mode.PAUSE_T2_BEFORE_STATUS)
        val operation = startSubmission()
        try {
            awaitBoundary()
            assertEquals(MetricSubmissionPhase.SAVED_DELIVERING, requireNotNull(original.submissionPhase).value)
            epochs.tokens.clearAuthenticationTokens()
            assertNull(requireNotNull(original.submissionPhase).value)
            assertNull(coordinator.postCheckInState.value)
            native.release()
            assertFalse(operation.await())
        } finally { native.release(); withTimeout(5_000) { operation.cancelAndJoin() } }
        epochs.sessions.exclusive {
            epochs.tokens.saveLoginSession("synthetic-phase-new", "synthetic-phase-refresh", "member",
                "ac490000-0000-4000-8000-000000000001", false)
        }
        val (old, fresh) = epochs.clearAndAwaitReplacement()
        assertNotSame(old, fresh); assertFalse(originalFrame.publication.isCurrentInMemory())
        sync.sync().getOrThrow()
        prepareCurrentPrompt(requireNotNull(peer))
        val nextFrame = frame; val nextPrompt = prompt
        assertNotSame(original, nextPrompt)
        assertNull(requireNotNull(nextPrompt.submissionPhase).value)
        val before = currentImage()
        val rejected = try { submit(originalFrame, inputs); error("An old source frame must reject") }
            catch (error: Exception) { if (error is CancellationException) throw error; error }
        assertEquals("DATABASE_EPOCH_INPUT_EXPIRED", rejected.message)
        assertSame(nextFrame, coordinator.postCheckInFrames.value)
        assertNull(requireNotNull(original.submissionPhase).value)
        assertNull(requireNotNull(nextPrompt.submissionPhase).value)
        assertEquals(before, currentImage())
    }

    @Test(timeout = 150_000) fun lostActualT2EndDoesNotClaimDeliveredAndFreshConsumersKeepOriginalMetricIds() = runBlocking<Unit> {
        prepare()
        val original = prompt; val seen = CopyOnWriteArrayList<MetricSubmissionPhase?>()
        val observer = recordPhases(original, seen)
        val old = epochs.current { requireNotNull(CountTrustRuntime.forDatabase(it.database)) }
        native.arm(MetricSubmissionPhaseNativeBoundary.Mode.LOSE_T2_END)
        assertFalse(submit())
        assertNotNull(native.failure)
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in old.retirementReasons)
        assertTrue(seen.contains(MetricSubmissionPhase.SAVED_DELIVERING))
        assertFalse(seen.contains(MetricSubmissionPhase.DELIVERED))
        withTimeout(45_000) { requireNotNull(original.submissionPhase).first { it == null } }
        val image = requireNotNull(native.image)
        val child = childReference(image)
        assertEquals(child, requireNotNull(original.submittedCommand).reference)
        repeat(2) {
            epochs.current { graph ->
                assertNotSame(old, CountTrustRuntime.forDatabase(graph.database))
                val owner = CountCommandStore(graph.database, epochs.tokens, epochs.sessions)
                val proof = requireNotNull(owner.completedMetricGroup(child.native(), preferences))
                assertFalse(proof.pending)
                owner.deliverCompletedMetricGroup(proof, preferences) { true }
            }
            sameBusinessAndOriginalGroup(image, child, pending = false)
        }
        assertEquals(1, native.trace.ends.count { it.kind == CountJourneyNativeTrace.Kind.METRIC_T1 })
        assertNull(requireNotNull(original.submissionPhase).value)
        observer.cancelAndJoin(); assertTrue(native.trace.observationFailures.isEmpty())
    }

    @Test(timeout = 150_000) fun closingSavedPendingPromptKeepsTheActualParentChildAndAllBusinessForRecovery() = runBlocking<Unit> {
        prepare()
        val original = prompt; val originalFrame = frame
        native.arm(MetricSubmissionPhaseNativeBoundary.Mode.PAUSE_T2_BEFORE_STATUS)
        val operation = startSubmission()
        try {
            awaitBoundary()
            assertTrue(native.trace.metricsCommitted.isCompleted)
            assertFalse(native.trace.delivered.isCompleted)
            operation.cancel(); native.release()
            withTimeout(5_000) { operation.join() }
        } finally { native.release(); withContext(NonCancellable) { withTimeout(5_000) { operation.cancelAndJoin() } } }
        assertTrue(operation.isCancelled)
        assertEquals(MetricSubmissionPhase.SAVED_PENDING, requireNotNull(original.submissionPhase).value)
        val receipt = requireNotNull(original.submittedCommand)
        val image = requireNotNull(native.image)
        sameBusinessAndOriginalGroup(image, receipt.reference, pending = true)
        val before = currentImage()
        // Same original onDismiss/Host path as Later; never skipPrompt or a delivery ACK.
        epochs.host.withSourceGraph(originalFrame.publication) { coordinator.closePrompt(originalFrame.value, originalFrame) }
        assertNull(coordinator.postCheckInState.value); assertNull(coordinator.postCheckInFrames.value)
        assertNull(requireNotNull(original.submissionPhase).value)
        assertEquals(before, currentImage())
        sameBusinessAndOriginalGroup(image, receipt.reference, pending = true)
        assertFalse(native.trace.delivered.isCompleted)
        assertEquals(1, native.trace.ends.count { it.kind == CountJourneyNativeTrace.Kind.METRIC_T1 })
        assertTrue(native.trace.observationFailures.isEmpty())
    }

}
