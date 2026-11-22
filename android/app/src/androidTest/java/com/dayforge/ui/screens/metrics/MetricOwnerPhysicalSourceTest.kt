package com.dayforge.ui.screens.metrics

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.DatabaseEpochValue
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.ui.epochPublishedState
import com.dayforge.ui.screens.editmetric.EditMetricScreen
import com.dayforge.ui.screens.editmetric.EditMetricViewModel
import com.dayforge.ui.screens.metricdetail.MetricDetailScreen
import com.dayforge.ui.screens.metricdetail.MetricDetailViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Hilt Host and actual clear/drain/close/rebuild. Original public UI actions never use
 * fixture.current as their authority. Legacy rows are fixture setup, not a claim that a v5
 * creation or command-recovery journey was exercised. No fake epoch swap or suspend spy.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class MetricOwnerPhysicalSourceTest {
    @get:Rule(order = 0) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 1) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 2) val compose = createEmptyComposeRule()
    @Inject lateinit var preferences: PreferencesManager
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private val createdModels = mutableListOf<ViewModel>()
    private val auxiliary = mutableListOf<Job>()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private lateinit var realActivity: ComponentActivity
    private fun label(id: Int) = context.getString(id)

    @Before fun start() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login()
        activity = ActivityScenario.launch(ComponentActivity::class.java)
        activity.onActivity { instance ->
            realActivity = instance
            instance.setContent { MaterialTheme { content.value?.invoke() } }
        }
    }

    @After fun finish() = runBlocking<Unit> {
        if (::activity.isInitialized) {
            compose.runOnIdle { content.value = null }
            compose.waitForIdle()
            activity.close()
        }
        withTimeout(5_000) {
            val jobs = withContext(Dispatchers.Main.immediate) {
                createdModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }.also { models.clear() }
            }
            auxiliary.forEach { it.cancel() }; auxiliary.joinAll(); jobs.joinAll()
        }
        // PhysicalDatabaseEpochFixture then checks no accepted work remains and all seen
        // physical handles close. It does not force unknown native transactions closed.
    }

    private fun <T : ViewModel> retain(create: () -> T): T {
        var result: T? = null
        compose.runOnIdle {
            val model = create(); result = model; createdModels += model
            models.put("model-${createdModels.size}", model)
        }
        return requireNotNull(result)
    }
    private fun detail(id: Long) = retain {
        MetricDetailViewModel(context, epochs.host, SavedStateHandle(mapOf("metricId" to id)))
    }
    private fun edit(id: Long) = retain {
        EditMetricViewModel(context, epochs.host, SavedStateHandle(mapOf("metricId" to id)))
    }
    private fun show(block: @Composable () -> Unit) { compose.runOnIdle { content.value = block } }
    private fun seedMetric(name: String, uuid: String): MetricEntity = runBlocking {
        epochs.current { graph ->
            val row = MetricEntity(id = 41L, name = name, unit = "kg", decimalPlaces = 1,
                iconResId = 1, colorHex = "#123456", uuid = uuid)
            assertEquals(41L, graph.metricRepository.createMetric(row))
            requireNotNull(graph.metricDao.getMetricById(41L)).also { assertEquals(row, it) }
        }
    }
    private fun oldMetric() = seedMetric("Original metric", "ac491000-0000-4000-8000-000000000001")
    private fun replacement(): MetricEntity {
        runBlocking { epochs.clearAndAwaitReplacement() }
        return seedMetric("Replacement metric", "ac491000-0000-4000-8000-000000000002")
    }
    private fun selection(id: Long) = runBlocking { epochs.metricSelection(id) }
    private fun proof() = runBlocking { epochs.exactMain() }
    private fun awaitDetail(model: MetricDetailViewModel, uuid: String) {
        compose.waitUntil(10_000) { model.uiState.value.let { !it.isLoading && !it.sourceExpired && it.metric?.uuid == uuid } }
        assertNull("A fresh source must not retain the initial unbound-frame error", model.uiState.value.errorMessage)
    }
    private fun awaitEdit(model: EditMetricViewModel, source: DatabaseObjectSelection) {
        compose.waitUntil(10_000) { model.uiState.value.let { it.isLoaded && !it.sourceExpired && it.sourceSelection === source } }
    }
    /** Compose idle is not a Room/command completion signal. Snapshot direct scope children
     * on Main, invoke the actual retained callback, then join every newly created command.
     * A child already completed before the second snapshot has already made its durable
     * effects observable by the subsequent full native proof. No child is cancelled here.
     */
    private fun invokeAndJoinNewChildren(vararg selected: ViewModel,
        immediately: () -> Unit = {}, action: () -> Unit) {
        var created: List<Job> = emptyList()
        var immediateFailure: Throwable? = null
        compose.runOnIdle {
            val owners = selected.map { requireNotNull(it.viewModelScope.coroutineContext[Job]) }
            val before = owners.flatMap { it.children.toList() }.toSet()
            try { action(); immediately() }
            catch (failure: Throwable) { immediateFailure = failure }
            finally { created = owners.flatMap { it.children.toList() }.filter { it !in before }.distinct() }
        }
        try { runBlocking { withTimeout(5_000) { created.joinAll() } } }
        catch (failure: Throwable) {
            immediateFailure?.let { failure.addSuppressed(it) }
            throw failure
        }
        immediateFailure?.let { throw it }
        compose.waitForIdle()
    }

    private fun retainedClick(matcher: SemanticsMatcher): () -> Boolean = requireNotNull(
        compose.onNode(matcher).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun recordButton() = hasText(label(R.string.metric_record_value)) and hasClickAction() and hasAnyAncestor(isDialog())
    private fun openRecord() {
        compose.onNodeWithText(label(R.string.metric_record_value)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.metric_input_enter_value)).performTextInput("12.5")
        compose.onNodeWithText(label(R.string.metric_input_note_optional)).performTextInput("physical source")
    }

    @Test fun retainedOverviewClickCannotRetagOldRowAfterPhysicalReplacement() {
        val old = oldMetric()
        val model = retain { MetricsViewModel(context, preferences, epochs.host) }
        val navigated = mutableListOf<DatabaseObjectSelection>(); var legacyNavigation = 0
        show { MetricsScreen(model, calculateWindowSizeClass(realActivity), {}, { legacyNavigation++ },
            onMetricSelection = { navigated += it }) }
        compose.waitUntil(10_000) { model.metricsWithLatest.value.singleOrNull()?.metric?.uuid == old.uuid }
        val oldClick = retainedClick(hasText(old.name) and hasClickAction())
        val fresh = replacement()
        compose.waitUntil(10_000) { model.metricsWithLatest.value.singleOrNull()?.metric?.uuid == fresh.uuid }
        val before = proof()
        invokeAndJoinNewChildren(model) { assertTrue(oldClick()) }
        assertEquals(before, proof()); assertTrue(navigated.isEmpty()); assertEquals(0, legacyNavigation)
        compose.onNode(hasText(fresh.name) and hasClickAction()).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, navigated.size); assertEquals(fresh.id, navigated.single().id)
            assertEquals(fresh.uuid, navigated.single().uuid); assertTrue(navigated.single().publication.isCurrentInMemory())
        }
    }

    @Test fun retainedDetailConfirmCannotRecordIntoSameIdReplacementButFreshConfirmCan() {
        val old = oldMetric(); val oldSource = selection(old.id); val oldModel = detail(old.id)
        show { MetricDetailScreen(old.id, {}, {}, viewModel = oldModel, sourceSelection = oldSource) }
        awaitDetail(oldModel, old.uuid); openRecord(); val oldClick = retainedClick(recordButton())
        val fresh = replacement(); val freshSource = selection(fresh.id); val freshModel = detail(fresh.id)
        show { MetricDetailScreen(fresh.id, {}, {}, viewModel = freshModel, sourceSelection = freshSource) }
        awaitDetail(freshModel, fresh.uuid)
        assertTrue(oldModel.uiState.value.sourceExpired); assertNull(oldModel.uiState.value.metric)
        val before = proof()
        invokeAndJoinNewChildren(oldModel, immediately = {
            assertFalse(oldModel.uiState.value.isRecording); assertFalse(oldModel.uiState.value.isMutating)
        }) { assertTrue(oldClick()) }
        assertEquals(before, proof())
        openRecord(); compose.onNode(recordButton()).assertIsEnabled().performClick()
        compose.waitUntil(10_000) { freshModel.uiState.value.let { !it.isRecording && it.logs.size == 1 } }
        runBlocking { epochs.current { graph ->
            val row = graph.metricLogDao.getAllLogsForMetric(fresh.id).single()
            assertEquals(fresh.id, row.metricId); assertEquals(fresh.uuid, graph.metricDao.getMetricById(row.metricId)?.uuid)
            assertEquals(12.5, row.value, 0.0)
            assertEquals("physical source", row.note)
            assertEquals(1, graph.database.syncOutboxDao().getAll().count { it.recordType == "metric_log" && it.entityUuid == row.uuid })
        } }
    }

    @Test fun retainedEditSaveCannotAdoptNewSourceAndRevocationClearsItsDraft() {
        val old = oldMetric(); val oldSource = selection(old.id); val oldModel = edit(old.id)
        show { EditMetricScreen(old.id, oldModel, {}, sourceSelection = oldSource) }
        awaitEdit(oldModel, oldSource)
        compose.onNodeWithText(label(R.string.edit_metric_name_required)).performTextReplacement("User-owned draft")
        compose.onNodeWithText(label(R.string.edit_metric_save_changes)).performScrollTo()
        val oldSave = retainedClick(hasText(label(R.string.edit_metric_save_changes)) and hasClickAction())
        val fresh = replacement(); val freshSource = selection(fresh.id); val freshModel = edit(fresh.id)
        show { EditMetricScreen(fresh.id, freshModel, {}, sourceSelection = freshSource) }
        awaitEdit(freshModel, freshSource)
        assertTrue(oldModel.uiState.value.sourceExpired); assertEquals("", oldModel.uiState.value.name)
        assertEquals("", oldModel.uiState.value.unit); assertNull(oldModel.uiState.value.editAuthority)
        val before = proof()
        invokeAndJoinNewChildren(oldModel, immediately = { assertFalse(oldModel.uiState.value.isSaving) }) { assertTrue(oldSave()) }
        assertEquals(before, proof())
        compose.onNodeWithText(label(R.string.edit_metric_name_required)).performTextReplacement("Fresh saved name")
        compose.onNodeWithText(label(R.string.edit_metric_save_changes)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { freshModel.uiState.value.isSaved }
        runBlocking { epochs.current { graph ->
            assertEquals("Fresh saved name", graph.metricDao.getMetricById(fresh.id)?.name)
            assertEquals(fresh.uuid, graph.metricDao.getMetricById(fresh.id)?.uuid)
            assertEquals(2, graph.database.syncOutboxDao().getAll().count { it.recordType == "metric" && it.entityUuid == fresh.uuid })
        } }
    }

    @Test fun sameTargetViewModelRefusesNewSourceAndOldSaveUntilNavigationCreatesFreshTarget() {
        val old = oldMetric(); val oldSource = selection(old.id); val model = edit(old.id)
        val shownSource = mutableStateOf(oldSource)
        show { EditMetricScreen(old.id, model, {}, sourceSelection = shownSource.value) }
        awaitEdit(model, oldSource)
        compose.onNodeWithText(label(R.string.edit_metric_name_required)).performTextReplacement("Old target draft")
        compose.onNodeWithText(label(R.string.edit_metric_save_changes)).performScrollTo()
        val oldSave = retainedClick(hasText(label(R.string.edit_metric_save_changes)) and hasClickAction())
        val fresh = replacement(); val freshSource = selection(fresh.id)
        // Targets bind one original selection. Do not invent rebind permission merely to
        // make a same-VM test pass: the genuine different source must remain rejected.
        compose.runOnIdle { shownSource.value = freshSource }
        compose.waitForIdle()
        assertTrue(model.uiState.value.sourceExpired)
        assertSame(oldSource, model.uiState.value.sourceSelection)
        assertEquals("", model.uiState.value.name)
        val before = proof()
        invokeAndJoinNewChildren(model, immediately = { assertFalse(model.uiState.value.isSaving) }) {
            assertTrue(oldSave()); model.saveMetric(model.uiState.value)
        }
        assertEquals(before, proof()); assertFalse(model.uiState.value.isSaved)
        val freshModel = edit(fresh.id)
        show { EditMetricScreen(fresh.id, freshModel, {}, sourceSelection = freshSource) }
        awaitEdit(freshModel, freshSource)
        compose.onNodeWithText(label(R.string.edit_metric_name_required)).performTextReplacement("Fresh navigation draft")
        compose.onNodeWithText(label(R.string.edit_metric_save_changes)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { freshModel.uiState.value.isSaved }
        runBlocking { epochs.current { graph ->
            val stored = requireNotNull(graph.metricDao.getMetricById(fresh.id))
            assertEquals(fresh.uuid, stored.uuid); assertEquals("Fresh navigation draft", stored.name)
        } }
    }

    @Test fun samePhysicalDatabaseReloginRevokesOldDetailAndEditInputs() {
        val old = oldMetric(); val source = selection(old.id); val detailModel = detail(old.id); val editor = edit(old.id)
        compose.runOnIdle { detailModel.resumeReads(source); editor.adoptSource(source) }
        awaitDetail(detailModel, old.uuid); awaitEdit(editor, source)
        compose.runOnIdle { detailModel.toggleValueInput(detailModel.uiState.value); editor.updateName("Old account draft") }
        val detailFrame = detailModel.uiState.value; val editFrame = editor.uiState.value
        val oldDb = runBlocking { epochs.current { it.database } }
        runBlocking { epochs.login() }
        assertSame(oldDb, runBlocking { epochs.current { it.database } })
        assertFalse(source.publication.isCurrentInMemory())
        compose.waitUntil(10_000) { detailModel.uiState.value.sourceExpired && editor.uiState.value.sourceExpired }
        val before = proof()
        invokeAndJoinNewChildren(detailModel, editor, immediately = {
            assertFalse(detailModel.uiState.value.isRecording); assertFalse(detailModel.uiState.value.isMutating)
            assertFalse(editor.uiState.value.isSaving)
        }) {
            detailModel.recordValue(9.0, "expired", detailFrame); editor.saveMetric(editFrame)
            detailModel.updateAggregationType("sum", detailFrame)
        }
        assertEquals(before, proof())
        assertNull(detailModel.uiState.value.metric); assertNull(editor.uiState.value.editAuthority)
        assertEquals("", editor.uiState.value.name)
        val nextSource = selection(old.id); val next = detail(old.id)
        compose.runOnIdle { next.resumeReads(nextSource) }; awaitDetail(next, old.uuid)
        compose.runOnIdle { next.toggleValueInput(next.uiState.value); next.recordValue(3.0, "fresh", next.uiState.value) }
        compose.waitUntil(10_000) { next.uiState.value.logs.size == 1 && !next.uiState.value.isRecording }
    }

    @Test fun oldEqualLookingDialogCannotConfirmOrDismissReplacementRequest() {
        val row = oldMetric(); val source = selection(row.id); val model = detail(row.id)
        compose.runOnIdle { model.resumeReads(source) }; awaitDetail(model, row.uuid)
        compose.runOnIdle { model.toggleValueInput(model.uiState.value) }
        val old = model.uiState.value
        compose.runOnIdle { model.toggleValueInput(old); model.toggleValueInput(model.uiState.value) }
        val fresh = model.uiState.value
        assertNotSame(old.valueInputRequest, fresh.valueInputRequest)
        val before = proof()
        invokeAndJoinNewChildren(model, immediately = {
            assertFalse(model.uiState.value.isRecording); assertFalse(model.uiState.value.isMutating)
            assertSame(fresh.valueInputRequest, model.uiState.value.valueInputRequest)
        }) { model.recordValue(99.0, "old", old); model.toggleValueInput(old) }
        assertEquals(before, proof()); assertSame(fresh.valueInputRequest, model.uiState.value.valueInputRequest)
        assertTrue(model.uiState.value.showValueInput)
        compose.runOnIdle { model.recordValue(2.0, "new", fresh) }
        compose.waitUntil(10_000) { !model.uiState.value.isRecording && model.uiState.value.logs.size == 1 }
        compose.runOnIdle { model.toggleDeleteConfirm(model.uiState.value) }
        val deleteA = model.uiState.value
        compose.runOnIdle { model.toggleDeleteConfirm(deleteA); model.toggleDeleteConfirm(model.uiState.value) }
        val deleteB = model.uiState.value; val beforeDelete = proof()
        invokeAndJoinNewChildren(model, immediately = {
            assertFalse(model.uiState.value.isRecording); assertFalse(model.uiState.value.isMutating)
            assertSame(deleteB.deleteRequest, model.uiState.value.deleteRequest)
        }) { model.deleteMetric(deleteA); model.toggleDeleteConfirm(deleteA) }
        assertEquals(beforeDelete, proof()); assertSame(deleteB.deleteRequest, model.uiState.value.deleteRequest)
        assertTrue(model.uiState.value.showDeleteConfirm)
    }

    @Test fun selectedHabitNumericReuseIsRejectedByActualRepositoryUuidGuard() {
        val row = oldMetric(); val source = selection(row.id); val model = detail(row.id)
        val oldHabit = HabitEntity(id = 73L, name = "Original choice", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily,
            uuid = "ac492000-0000-4000-8000-000000000001")
        runBlocking { epochs.current { graph -> graph.database.withTransaction { graph.habitDao.insert(oldHabit) } } }
        compose.runOnIdle { model.resumeReads(source) }; awaitDetail(model, row.uuid)
        compose.runOnIdle { model.showLinkHabitDialog(model.uiState.value) }
        compose.waitUntil(10_000) { model.uiState.value.showLinkHabit && !model.uiState.value.isMutating }
        compose.runOnIdle { model.toggleHabitSelection(oldHabit.id, model.uiState.value) }
        val originalChoice = model.uiState.value
        assertEquals(oldHabit.uuid, originalChoice.availableHabits.single().uuid)
        val replacement = oldHabit.copy(name = "Replacement choice", uuid = "ac492000-0000-4000-8000-000000000002")
        runBlocking { epochs.current { graph -> graph.database.withTransaction {
            graph.habitDao.delete(oldHabit); graph.habitDao.insert(replacement)
        } } }
        assertTrue(source.publication.isCurrentInMemory())
        val before = proof()
        invokeAndJoinNewChildren(model, immediately = { assertTrue(model.uiState.value.isMutating) }) {
            model.clearError(); assertNull(model.uiState.value.errorMessage)
            model.linkSelectedHabits(originalChoice)
        }
        assertFalse(model.uiState.value.isMutating); assertNotNull(model.uiState.value.errorMessage)
        assertTrue(requireNotNull(model.uiState.value.errorMessage).contains("OBJECT_WRITE_LINK_CHANGED"))
        assertEquals(before, proof())
        runBlocking { epochs.current { graph ->
            assertEquals(replacement, graph.habitDao.getHabitById(oldHabit.id))
            assertTrue(graph.habitMetricLinkDao.getVisibleLinksForHabit(oldHabit.id).isEmpty())
        } }
    }

    @Test fun restoredNumericOnlyTargetsNeverAcquireCurrentSourceForWrites() {
        val row = oldMetric(); val detailModel = detail(row.id); val editor = edit(row.id)
        val before = proof()
        invokeAndJoinNewChildren(detailModel, editor, immediately = {
            assertFalse(detailModel.uiState.value.isRecording); assertFalse(detailModel.uiState.value.isMutating)
            assertFalse(editor.uiState.value.isSaving)
        }) {
            detailModel.resumeReads(); detailModel.toggleValueInput(); detailModel.recordValue(1.0, "no source")
            detailModel.updateAggregationType("sum"); detailModel.toggleDeleteConfirm(); detailModel.deleteMetric()
            editor.adoptSource(null); editor.updateName("Numeric only draft"); editor.saveMetric()
        }
        assertEquals(before, proof())
        assertTrue(detailModel.uiState.value.sourceExpired); assertNull(detailModel.uiState.value.metric)
        assertTrue(editor.uiState.value.sourceExpired); assertNull(editor.uiState.value.editAuthority)
        assertFalse(editor.uiState.value.isSaved)
    }

    @Test fun hidingDetailCancelsItsReadersWithoutCancellingAlreadyAcceptedValue() = runBlocking<Unit> {
        val row = oldMetric(); val source = selection(row.id); val model = detail(row.id)
        compose.runOnIdle { model.resumeReads(source) }; awaitDetail(model, row.uuid)
        compose.runOnIdle { model.toggleValueInput(model.uiState.value) }
        val frame = model.uiState.value
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val barrier = launch(Dispatchers.IO) { epochs.sessions.exclusive { held.complete(Unit); release.await() } }
        try {
            withTimeout(10_000) { held.await() }
            compose.runOnIdle {
                model.recordValue(4.0, "accepted before hide", frame)
                assertTrue(model.uiState.value.isRecording)
                model.pauseReads()
            }
        } finally { release.complete(Unit); withTimeout(5_000) { barrier.join() } }
        compose.waitUntil(10_000) { !model.uiState.value.isRecording }
        epochs.current { graph ->
            val log = graph.metricLogDao.getAllLogsForMetric(row.id).single()
            assertEquals(4.0, log.value, 0.0); assertEquals("accepted before hide", log.note)
            assertEquals(1, graph.database.syncOutboxDao().getAll().count {
                it.recordType == "metric_log" && it.entityUuid == log.uuid
            })
        }
        val committed = proof()
        compose.runOnIdle { model.resumeReads(source) }; awaitDetail(model, row.uuid)
        compose.waitUntil(10_000) { model.uiState.value.logs.size == 1 }
        assertEquals(committed, proof())
    }

    private fun assertEditorPersonalDataCleared(model: EditMetricViewModel, secrets: List<String>) {
        val state = model.uiState.value
        assertTrue(state.sourceExpired); assertFalse(state.isValid); assertFalse(state.isSaved)
        assertEquals("", state.name); assertEquals("", state.description)
        assertEquals("", state.unit); assertEquals("", state.customUnit)
        assertEquals("", state.targetValueInput); assertEquals("", state.targetValueUpperInput)
        assertNull(state.appearance); assertNull(state.editAuthority)
        secrets.forEach { compose.onAllNodesWithText(it, substring = true).assertCountEquals(0) }
        compose.onNodeWithText(label(R.string.database_view_expired)).assertIsDisplayed()
    }

    @Test fun anotherAccountCannotSeeTypedDraftAndOldTextAndSaveCallbacksCannotRestoreIt() {
        val row = oldMetric(); val source = selection(row.id); val model = edit(row.id)
        show { EditMetricScreen(row.id, model, {}, sourceSelection = source) }; awaitEdit(model, source)
        val secretName = "Private account-A draft"; val secretDescription = "Account-A private description"
        val secretUnit = "private-unit-A"; val secretTarget = "137.5"
        compose.onNodeWithText(label(R.string.edit_metric_name_required)).performTextReplacement(secretName)
        val oldText = requireNotNull(compose.onNodeWithText(label(R.string.edit_metric_name_required))
            .fetchSemanticsNode().config[SemanticsActions.SetText].action)
        compose.runOnIdle {
            model.updateDescription(secretDescription); model.updateUnit(secretUnit)
            model.updateTargetDirection("increase"); model.updateTargetValueInput(secretTarget)
        }
        // An ordinary same-source background write does not discard a valid active draft.
        runBlocking { epochs.current { graph -> graph.metricRepository.createMetric(MetricEntity(
            id = 42L, name = "Unrelated metric", unit = "cm", iconResId = 1, colorHex = "#123456",
            uuid = "ac491000-0000-4000-8000-000000000042")) } }
        assertTrue(source.publication.isCurrentInMemory()); assertEquals(secretName, model.uiState.value.name)
        assertEquals(secretDescription, model.uiState.value.description); assertEquals(secretUnit, model.uiState.value.unit)
        compose.onNodeWithText(label(R.string.edit_metric_save_changes)).performScrollTo().assertIsEnabled()
        val oldSave = retainedClick(hasText(label(R.string.edit_metric_save_changes)) and hasClickAction())
        runBlocking { epochs.login("ac490000-0000-4000-8000-000000000002") }
        compose.waitUntil(10_000) { model.uiState.value.sourceExpired }
        val secrets = listOf(secretName, secretDescription, secretUnit, secretTarget)
        assertEditorPersonalDataCleared(model, secrets)
        val before = proof()
        invokeAndJoinNewChildren(model, immediately = { assertFalse(model.uiState.value.isSaving) }) {
            assertTrue(oldText(AnnotatedString(secretName)))
            model.updateDescription(secretDescription); model.updateUnit(secretUnit); model.updateTargetValueInput(secretTarget)
            assertTrue(oldSave())
        }
        compose.waitForIdle(); assertEditorPersonalDataCleared(model, secrets)
        assertFalse(source.publication.isCurrentInMemory()); assertEquals(before, proof())
    }

    @Test fun logoutImmediatelyHidesEditedPersonalFieldsAndReloginDoesNotReviveOldSource() {
        val row = oldMetric(); val source = selection(row.id); val model = edit(row.id)
        show { EditMetricScreen(row.id, model, {}, sourceSelection = source) }; awaitEdit(model, source)
        val secretName = "Private revoked draft"; val secretDescription = "Private revoked description"
        compose.onNodeWithText(label(R.string.edit_metric_name_required)).performTextReplacement(secretName)
        val oldText = requireNotNull(compose.onNodeWithText(label(R.string.edit_metric_name_required))
            .fetchSemanticsNode().config[SemanticsActions.SetText].action)
        compose.runOnIdle { model.updateDescription(secretDescription) }
        compose.onNodeWithText(label(R.string.edit_metric_save_changes)).performScrollTo().assertIsEnabled()
        val oldSave = retainedClick(hasText(label(R.string.edit_metric_save_changes)) and hasClickAction())
        val before = proof()
        runBlocking { epochs.sessions.exclusive { epochs.tokens.clearTokens() } }
        compose.waitUntil(10_000) { model.uiState.value.sourceExpired }
        assertEditorPersonalDataCleared(model, listOf(secretName, secretDescription))
        invokeAndJoinNewChildren(model, immediately = { assertFalse(model.uiState.value.isSaving) }) {
            assertTrue(oldText(AnnotatedString(secretName))); model.updateDescription(secretDescription); assertTrue(oldSave())
        }
        compose.waitForIdle(); assertEditorPersonalDataCleared(model, listOf(secretName, secretDescription))
        // Restoring an independently fresh login permits the oracle to access current data;
        // it must not make the old opaque source or old draft presentable again.
        runBlocking { epochs.login() }
        assertFalse(source.publication.isCurrentInMemory())
        assertEquals(before, proof()); assertEditorPersonalDataCleared(model, listOf(secretName, secretDescription))
    }

    @Test fun actualOldOverviewEmissionReleasedAfterRebuildCannotRepopulateReplayState() = runBlocking<Unit> {
        val old = oldMetric(); val captured = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        auxiliary += requireNotNull(scope.coroutineContext[Job])
        var held = false
        val source = epochs.host.observe { graph -> graph.metricOverviewProvider.observe() }.map { frame ->
            if (!held && frame.value.any { it.metric.uuid == old.uuid }) {
                held = true; captured.complete(Unit); release.await()
            }
            DatabaseEpochValue(frame.value.map { it.metric.uuid }, frame.publication)
        }
        val state = epochPublishedState(scope, epochs.host, SharingStarted.Eagerly, emptyList(), source)
        withTimeout(10_000) { captured.await() }
        val fresh = replacement(); val seen = mutableListOf<List<String>>()
        val collector = scope.launch { state.collect { seen += it } }; auxiliary += collector
        val before = proof(); release.complete(Unit)
        compose.waitUntil(10_000) { state.value == listOf(fresh.uuid) }
        withContext(Dispatchers.Main.immediate) { assertTrue(seen.none { old.uuid in it }) }
        assertEquals(before, proof())
    }
}
