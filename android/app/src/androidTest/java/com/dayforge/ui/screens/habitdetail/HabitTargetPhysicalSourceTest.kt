package com.dayforge.ui.screens.habitdetail

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.CountActionGate
import com.dayforge.ui.screens.edithabit.EditHabitScreen
import com.dayforge.ui.screens.edithabit.EditHabitViewModel
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Hilt Host, native clear/drain/close/rebuild, original target Screens/VMs and Room.
 * Retained Semantics OnClick actions are invoked after the very same target VM has loaded
 * a genuinely new source. No fake graph, rewritten source, suspend spy or private VM setter.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HabitTargetPhysicalSourceTest {
    // Only the post-commit background widget scheduler is isolated, following its original
    // test rule. Business callbacks, native writes, Host replacement and TokenManager are real.
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var linkedMetricCoordinator: com.dayforge.ui.metrics.LinkedMetricCoordinator
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var countActions: CountActionGate
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private val createdModels = mutableListOf<ViewModel>()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private fun label(id: Int) = context.getString(id)
    private val activeSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @Before fun start() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login()
        activity = ActivityScenario.launch(ComponentActivity::class.java)
        activity.onActivity { it.setContent { MaterialTheme { content.value?.invoke() } } }
    }

    @After fun finish() = runBlocking<Unit> {
        if (::activity.isInitialized) {
            compose.runOnIdle { content.value = null }; compose.waitForIdle(); activity.close()
        }
        withTimeout(5_000) {
            val jobs = withContext(Dispatchers.Main.immediate) {
                createdModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }.also { models.clear() }
            }
            jobs.joinAll()
        }
        // The fixture next refuses accepted/unknown native work before strict physical close.
    }

    private fun <T : ViewModel> retain(create: () -> T): T {
        var result: T? = null
        compose.runOnIdle {
            val model = create(); result = model; createdModels += model
            models.put("model-${createdModels.size}", model)
        }
        return requireNotNull(result)
    }
    private fun editor() = retain { EditHabitViewModel(epochs.host, preferences, context) }
    private fun detail() = retain { HabitDetailViewModel(context, epochs.host, preferences, countActions, linkedMetricCoordinator) }
    private fun show(block: @Composable () -> Unit) { compose.runOnIdle { content.value = block } }
    private fun proof() = runBlocking { epochs.exactMain() }
    private fun selection(id: Long = 51L) = runBlocking { epochs.habitSelection(id) }
    private fun original(type: HabitType = HabitType.CHECK_IN, targetCycles: Int? = null,
        active: Boolean = true) = HabitEntity(id = 51L, name = "Original habit", habitType = type,
        iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 3,
        uuid = "ac493000-0000-4000-8000-000000000001", targetCycles = targetCycles, isActive = active)
    private fun seed(row: HabitEntity): HabitEntity = runBlocking {
        epochs.current { graph -> graph.database.withTransaction {
            assertEquals(row.id, graph.habitDao.insert(row))
            requireNotNull(graph.habitDao.getHabitById(row.id)).also { assertEquals(row, it) }
        } }
    }
    private fun replace(old: HabitEntity, active: Boolean = true, targetCycles: Int? = null): HabitEntity {
        runBlocking { epochs.clearAndAwaitReplacement() }
        return seed(old.copy(name = "Replacement habit", uuid = "ac493000-0000-4000-8000-000000000002",
            isActive = active, targetCycles = targetCycles))
    }
    private fun awaitEdit(model: EditHabitViewModel, source: DatabaseObjectSelection) {
        compose.waitUntil(10_000) { model.uiState.value.let {
            !it.isLoading && !it.sourceExpired && !it.habitNotFound && it.sourceSelection === source
        } }
    }
    private fun awaitDetail(model: HabitDetailViewModel, source: DatabaseObjectSelection) {
        compose.waitUntil(10_000) { model.uiState.value.let {
            !it.isLoading && !it.readError && it.sourceSelection === source && it.habit?.uuid == source.uuid
        } }
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
    private fun saveMatcher() = hasText(label(R.string.edit_habit_save_changes)) and hasClickAction()
    private fun dialogAction(resource: Int) = hasText(label(resource)) and hasClickAction() and hasAnyAncestor(isDialog())
    private fun name(value: String) {
        compose.onNodeWithText(label(R.string.edit_habit_name_required)).performScrollTo().performTextReplacement(value)
    }
    private fun save() { compose.onNode(saveMatcher()).performScrollTo().assertIsEnabled().performClick() }
    private fun expectStored(row: HabitEntity, expectedName: String = row.name, active: Boolean = row.isActive) = runBlocking {
        epochs.current { graph ->
            val current = requireNotNull(graph.habitDao.getHabitById(row.id))
            assertEquals(row.copy(name = expectedName, isActive = active, updatedAt = current.updatedAt), current)
        }
    }
    private fun expectSingleUpdate(row: HabitEntity) = runBlocking {
        epochs.current { graph ->
            val queue = graph.database.syncOutboxDao().getAll().filter { it.recordType == "habit" && it.entityUuid == row.uuid }
            assertEquals(2, queue.size)
            // Legacy insert and update both use the original trigger action "upsert".
            assertTrue(queue.all { it.action == "upsert" })
            assertEquals(2, queue.map { it.operationId }.distinct().size)
        }
    }

    @Test fun actualOldSaveCannotAdoptFreshDraftInSameEditorAfterPhysicalIdReuse() {
        val old = seed(original()); val oldSource = selection(); val model = editor()
        val shownSource = mutableStateOf(oldSource); var savedCallbacks = 0
        show { EditHabitScreen(51L, model, { savedCallbacks++ }, {}, shownSource.value) }
        awaitEdit(model, oldSource); name("Old draft")
        compose.onNode(saveMatcher()).performScrollTo().assertIsEnabled()
        val oldSave = retainedClick(saveMatcher())
        val fresh = replace(old); val freshSource = selection()
        assertFalse(oldSource.publication.isCurrentInMemory()); assertTrue(freshSource.publication.isCurrentInMemory())
        compose.runOnIdle { shownSource.value = freshSource }; awaitEdit(model, freshSource)
        name("New draft must stay unchanged")
        compose.onNode(saveMatcher()).performScrollTo().assertIsEnabled()
        assertTrue(model.uiState.value.hasChanges)
        val before = proof(); val beforeRequests = widgets.requestCount
        invokeAndJoinNewChildren(model, immediately = {
            assertFalse(model.uiState.value.isSaving); assertFalse(model.uiState.value.isDeleting)
        }) { assertTrue(oldSave()) }
        assertEquals(before, proof()); assertEquals(beforeRequests, widgets.requestCount)
        assertEquals(0, savedCallbacks); assertFalse(model.uiState.value.saved)
        assertEquals("New draft must stay unchanged", model.uiState.value.name)
        save(); compose.waitUntil(10_000) { model.uiState.value.saved && savedCallbacks == 1 }
        expectStored(fresh, "New draft must stay unchanged"); expectSingleUpdate(fresh)
        assertEquals(beforeRequests + 1, widgets.requestCount)
    }

    @Test fun actualOldActiveSwitchCannotToggleFreshSameIdHabitInSameDetailViewModel() {
        val old = seed(original()); val oldSource = selection(); val model = detail()
        val shownSource = mutableStateOf(oldSource)
        show { HabitDetailScreen(51L, {}, viewModel = model, sourceSelection = shownSource.value) }
        awaitDetail(model, oldSource); compose.onNode(activeSwitch).assertIsOn()
        val oldSwitch = retainedClick(activeSwitch)
        val fresh = replace(old); val freshSource = selection()
        assertFalse(oldSource.publication.isCurrentInMemory()); assertTrue(freshSource.publication.isCurrentInMemory())
        compose.runOnIdle { shownSource.value = freshSource }; awaitDetail(model, freshSource)
        compose.onNode(activeSwitch).assertIsOn()
        val before = proof(); val beforeRequests = widgets.requestCount
        invokeAndJoinNewChildren(model) { assertTrue(oldSwitch()) }
        assertEquals(before, proof()); assertEquals(beforeRequests, widgets.requestCount)
        expectStored(fresh); compose.onNode(activeSwitch).assertIsOn()
        compose.onNode(activeSwitch).assertIsEnabled().performClick()
        compose.waitUntil(10_000) { model.uiState.value.habit?.isActive == false && 51L !in model.busyHabitIds.value }
        expectStored(fresh, active = false); expectSingleUpdate(fresh)
        assertEquals(beforeRequests + 1, widgets.requestCount)
    }

    @Test fun oldDefaultTargetConfirmCannotModifyOrSaveReplacementDraftRequest() {
        val row = seed(original(HabitType.COUNTING)); val source = selection(); val model = editor(); var saved = 0
        show { EditHabitScreen(51L, model, { saved++ }, {}, source) }; awaitEdit(model, source)
        compose.onNodeWithText(label(R.string.edit_habit_daily_target)).performScrollTo().performTextClearance()
        save(); compose.waitUntil(10_000) { model.uiState.value.showDefaultTargetDialog }
        val requestA = requireNotNull(model.uiState.value.saveConfirmationToken)
        val oldConfirm = retainedClick(dialogAction(R.string.action_confirm))
        compose.onNode(dialogAction(R.string.action_cancel)).performClick()
        compose.waitUntil(10_000) { !model.uiState.value.showDefaultTargetDialog }
        name("Replacement default draft"); save()
        compose.waitUntil(10_000) { model.uiState.value.showDefaultTargetDialog }
        val requestB = requireNotNull(model.uiState.value.saveConfirmationToken)
        assertNotSame(requestA, requestB)
        val before = proof(); val beforeRequests = widgets.requestCount
        invokeAndJoinNewChildren(model, immediately = {
            assertFalse(model.uiState.value.isSaving); assertFalse(model.uiState.value.isDeleting)
            assertSame(requestB, model.uiState.value.saveConfirmationToken)
            assertTrue(model.uiState.value.showDefaultTargetDialog); assertNull(model.uiState.value.targetValue)
        }) { assertTrue(oldConfirm()) }
        assertEquals(before, proof()); assertEquals(beforeRequests, widgets.requestCount)
        assertSame(requestB, model.uiState.value.saveConfirmationToken)
        assertTrue(model.uiState.value.showDefaultTargetDialog); assertNull(model.uiState.value.targetValue)
        assertEquals("Replacement default draft", model.uiState.value.name); assertEquals(0, saved)
        compose.onNode(dialogAction(R.string.action_confirm)).assertIsEnabled().performClick()
        compose.waitUntil(10_000) { model.uiState.value.saved && saved == 1 }
        runBlocking { epochs.current { graph ->
            val current = requireNotNull(graph.habitDao.getHabitById(row.id))
            assertEquals(row.copy(name = "Replacement default draft", targetValue = 1, updatedAt = current.updatedAt), current)
        } }
        expectSingleUpdate(row); assertEquals(beforeRequests + 1, widgets.requestCount)
    }

    @Test fun oldChildrenKeepConfirmationCannotConsumeReopenedRequestAndFreshKeepIsAtomic() {
        val parent = seed(original(HabitType.GOAL))
        val child = seed(HabitEntity(id = 52L, name = "Retained child", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#234567", schedule = HabitSchedule.Daily,
            uuid = "ac493000-0000-4000-8000-000000000052", parentHabitId = parent.uuid))
        val source = selection(); val model = editor(); var deleted = 0
        show { EditHabitScreen(51L, model, {}, { deleted++ }, source) }; awaitEdit(model, source)
        fun openChildren() {
            compose.onNodeWithContentDescription(label(R.string.action_delete)).performClick()
            compose.onNode(dialogAction(R.string.action_delete)).performClick()
            compose.waitUntil(10_000) { model.uiState.value.showDeleteChildrenDialog && !model.uiState.value.isDeleting }
            assertEquals(1, model.uiState.value.pendingDeleteChildrenCount)
        }
        openChildren()
        val requestA = requireNotNull(model.uiState.value.deleteConfirmationToken)
        val oldKeep = retainedClick(dialogAction(R.string.dialog_delete_habit_keep_children))
        Espresso.pressBack()
        compose.waitUntil(10_000) { !model.uiState.value.showDeleteChildrenDialog }
        openChildren()
        val requestB = requireNotNull(model.uiState.value.deleteConfirmationToken)
        assertNotSame(requestA, requestB)
        val before = proof(); val beforeRequests = widgets.requestCount
        invokeAndJoinNewChildren(model, immediately = {
            assertFalse(model.uiState.value.isSaving); assertFalse(model.uiState.value.isDeleting)
            assertSame(requestB, model.uiState.value.deleteConfirmationToken)
        }) { assertTrue(oldKeep()) }
        assertEquals(before, proof()); assertEquals(beforeRequests, widgets.requestCount); assertEquals(0, deleted)
        assertSame(requestB, model.uiState.value.deleteConfirmationToken); assertTrue(model.uiState.value.showDeleteChildrenDialog)
        compose.onNode(dialogAction(R.string.dialog_delete_habit_keep_children)).assertIsEnabled().performClick()
        compose.waitUntil(10_000) { deleted == 1 && !model.uiState.value.isDeleting }
        runBlocking { epochs.current { graph ->
            assertNull(graph.habitDao.getHabitById(parent.id))
            val remaining = requireNotNull(graph.habitDao.getHabitById(child.id))
            assertEquals(child.copy(parentHabitId = null, updatedAt = remaining.updatedAt), remaining)
            val queue = graph.database.syncOutboxDao().getAll()
            assertEquals(1, queue.count { it.entityUuid == parent.uuid && it.recordType == "habit" && it.action == "delete" })
            assertEquals(2, queue.count { it.entityUuid == child.uuid && it.recordType == "habit" && it.action == "upsert" })
            assertEquals(1, queue.count { it.entityUuid == parent.uuid && it.recordType == "habit" && it.action == "upsert" })
            assertEquals(4, queue.size); assertEquals(4, queue.map { it.operationId }.distinct().size)
        } }
        assertEquals(beforeRequests + 1, widgets.requestCount)
    }

    @Test fun sameDatabaseReloginClearsPersonalEditorStateAndRejectsOldActualSave() {
        val row = seed(original()); val oldSource = selection(); val model = editor()
        val shownSource = mutableStateOf(oldSource); var saved = 0
        show { EditHabitScreen(51L, model, { saved++ }, {}, shownSource.value) }; awaitEdit(model, oldSource)
        name("Old private draft"); compose.onNode(saveMatcher()).performScrollTo().assertIsEnabled()
        val oldSave = retainedClick(saveMatcher()); val database = runBlocking { epochs.current { it.database } }
        runBlocking { epochs.login() }
        assertSame(database, runBlocking { epochs.current { it.database } }); assertFalse(oldSource.publication.isCurrentInMemory())
        compose.waitUntil(10_000) { model.uiState.value.sourceExpired }
        assertEquals("", model.uiState.value.name); assertEquals("", model.uiState.value.description)
        assertTrue(model.uiState.value.availableMetrics.isEmpty()); assertTrue(model.uiState.value.topLevelHabits.isEmpty())
        assertNull(model.uiState.value.editAuthority)
        val before = proof(); val beforeRequests = widgets.requestCount
        invokeAndJoinNewChildren(model, immediately = {
            assertFalse(model.uiState.value.isSaving); assertFalse(model.uiState.value.isDeleting)
        }) { assertTrue(oldSave()); model.loadHabit(row.id, oldSource) }
        assertEquals(before, proof()); assertEquals(beforeRequests, widgets.requestCount)
        assertTrue(model.uiState.value.sourceExpired); assertEquals(0, saved)
        val fresh = selection(); compose.runOnIdle { shownSource.value = fresh }; awaitEdit(model, fresh)
        name("Fresh authenticated draft"); save()
        compose.waitUntil(10_000) { model.uiState.value.saved && saved == 1 }
        expectStored(row, "Fresh authenticated draft"); expectSingleUpdate(row)
    }

    @Test fun oldErrorRetryCannotDismissNewRouteReactivationAndSourceFreeDismissIsRejected() {
        val old = seed(original()); val oldSource = selection(); val model = detail()
        val shownSource = mutableStateOf(oldSource)
        show { HabitDetailScreen(51L, {}, viewModel = model, sourceSelection = shownSource.value) }
        awaitDetail(model, oldSource)
        runBlocking { epochs.clearAndAwaitReplacement() }
        compose.waitUntil(10_000) { model.uiState.value.readError }
        compose.onNodeWithText(label(R.string.data_read_failed)).assertIsDisplayed()
        val oldRetry = retainedClick(hasText(label(R.string.action_retry)) and hasClickAction())
        val fresh = seed(old.copy(uuid = "ac493000-0000-4000-8000-000000000002",
            name = "New restart target", isActive = false, targetCycles = 3))
        val freshSource = selection(); compose.runOnIdle { shownSource.value = freshSource }; awaitDetail(model, freshSource)
        compose.onNode(activeSwitch).assertIsOff().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { model.uiState.value.showReactivationDialog &&
            model.uiState.value.reactivationInput != null && 51L !in model.busyHabitIds.value }
        val request = requireNotNull(model.uiState.value.reactivationInput)
        assertEquals(fresh.uuid, request.habit.uuid)
        val before = proof(); val beforeRequests = widgets.requestCount
        invokeAndJoinNewChildren(model, immediately = {
            assertSame(request, model.uiState.value.reactivationInput)
            assertTrue(model.uiState.value.showReactivationDialog)
        }) { assertTrue(oldRetry()); model.dismissReactivationDialog() }
        assertEquals(before, proof()); assertEquals(beforeRequests, widgets.requestCount)
        assertSame(request, model.uiState.value.reactivationInput); assertTrue(model.uiState.value.showReactivationDialog)
        assertSame(freshSource, model.uiState.value.sourceSelection); expectStored(fresh, active = false)
        compose.onNode(dialogAction(R.string.action_cancel)).performClick()
        compose.waitUntil(10_000) { !model.uiState.value.showReactivationDialog }
        assertNull(model.uiState.value.reactivationInput); assertEquals(before, proof())
    }
}
