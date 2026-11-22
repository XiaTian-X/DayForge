package com.dayforge.ui.screens.editgoal

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
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

/** Actual Hilt Host and original Screen callbacks. DAO writes are explicit fixture setup;
 * every examined action uses the real ViewModel/Repository path. No fake graph or suspend spy. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EditGoalPhysicalSourceTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @Inject lateinit var preferences: PreferencesManager
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private val createdModels = mutableListOf<ViewModel>()
    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private lateinit var activity: ActivityScenario<ComponentActivity>
    private fun label(id: Int) = context.getString(id)

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
    }
    private fun editor(): EditGoalViewModel {
        var value: EditGoalViewModel? = null
        compose.runOnIdle {
            val model = EditGoalViewModel(context, epochs.host, preferences)
            value = model; createdModels += model; models.put("goal-${createdModels.size}", model)
        }
        return requireNotNull(value)
    }
    private fun show(block: @Composable () -> Unit) { compose.runOnIdle { content.value = block } }
    private fun proof() = runBlocking { epochs.exactMain() }
    private fun selection() = runBlocking { epochs.habitSelection(51L) }
    private fun goal() = HabitEntity(id = 51L, name = "Original goal", habitType = HabitType.GOAL,
        iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily,
        uuid = "ac495000-0000-4000-8000-000000000001")
    private fun child(parent: HabitEntity) = HabitEntity(id = 52L, name = "Original child",
        habitType = HabitType.CHECK_IN, iconResId = 1, colorHex = "#234567", schedule = HabitSchedule.Daily,
        uuid = "ac495000-0000-4000-8000-000000000052", parentHabitId = parent.uuid)
    private fun seed(row: HabitEntity): HabitEntity = runBlocking {
        epochs.current { graph -> graph.database.withTransaction {
            assertEquals(row.id, graph.habitDao.insert(row))
            requireNotNull(graph.habitDao.getHabitById(row.id)).also { assertEquals(row, it) }
        } }
    }
    private fun replace(old: HabitEntity): HabitEntity {
        runBlocking { epochs.clearAndAwaitReplacement() }
        return seed(old.copy(name = "Replacement goal", uuid = "ac495000-0000-4000-8000-000000000002"))
    }
    private fun awaitEditor(model: EditGoalViewModel, source: DatabaseObjectSelection) {
        compose.waitUntil(10_000) { model.uiState.value.let {
            !it.isLoading && !it.sourceExpired && it.sourceSelection === source && it.goalUuid == source.uuid
        } }
    }
    private fun retainedClick(matcher: SemanticsMatcher): () -> Boolean = requireNotNull(
        compose.onNode(matcher).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun saveMatcher() = hasText(label(R.string.action_save)) and hasClickAction() and !hasAnyAncestor(isDialog())
    private fun dialogAction(id: Int) = hasText(label(id)) and hasClickAction() and hasAnyAncestor(isDialog())
    /** Lazy items outside the viewport have no node yet. Scroll the actual vertical
     * container to compose the original control, then retain/click its real semantics. */
    private fun lazyNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            !hasAnyAncestor(isDialog())).performScrollToNode(matcher)
        return compose.onNode(matcher).assertExists()
    }
    private fun name(text: String) {
        lazyNode(hasText(label(R.string.edit_habit_name_label))).performTextReplacement(text)
    }
    private fun save() { lazyNode(saveMatcher()).assertIsEnabled().performClick() }
    /** Main/Compose idle alone cannot prove a launched Room write has completed. */
    private fun invokeAndJoinNewChildren(model: EditGoalViewModel,
        immediately: () -> Unit = {}, action: () -> Unit) {
        var jobs: List<Job> = emptyList(); var immediateFailure: Throwable? = null
        compose.runOnIdle {
            val owner = requireNotNull(model.viewModelScope.coroutineContext[Job])
            val before = owner.children.toSet()
            try { action(); immediately() } catch (failure: Throwable) { immediateFailure = failure }
            finally { jobs = owner.children.filter { it !in before }.toList() }
        }
        try { runBlocking { withTimeout(5_000) { jobs.joinAll() } } }
        catch (failure: Throwable) { immediateFailure?.let { failure.addSuppressed(it) }; throw failure }
        immediateFailure?.let { throw it }; compose.waitForIdle()
    }
    private fun expectSaved(row: HabitEntity, name: String) = runBlocking {
        epochs.current { graph ->
            val current = requireNotNull(graph.habitDao.getHabitById(row.id))
            assertEquals(row.copy(name = name, updatedAt = current.updatedAt), current)
            val queue = graph.database.syncOutboxDao().getAll()
                .filter { it.recordType == "habit" && it.entityUuid == row.uuid }
            assertEquals(2, queue.size); assertTrue(queue.all { it.action == "upsert" })
            assertEquals(2, queue.map { it.operationId }.distinct().size)
        }
    }

    @Test fun oldActualSaveCannotAdoptReplacementSourceAndFreshTargetSaves() {
        val old = seed(goal()); val oldSource = selection(); val model = editor(); var saved = 0
        val shown = mutableStateOf(oldSource)
        show { EditGoalScreen(51L, model, { saved++ }, {}, {}, sourceSelection = shown.value) }
        awaitEditor(model, oldSource); name("Old draft")
        lazyNode(saveMatcher()).assertIsEnabled(); val oldSave = retainedClick(saveMatcher())
        val fresh = replace(old); val newSource = selection()
        assertFalse(oldSource.publication.isCurrentInMemory()); assertTrue(newSource.publication.isCurrentInMemory())
        invokeAndJoinNewChildren(model) { shown.value = newSource; model.loadGoal(51L, newSource) }
        compose.waitUntil(10_000) { model.uiState.value.sourceExpired }
        val before = proof(); val requests = widgets.requestCount
        invokeAndJoinNewChildren(model, immediately = {
            assertFalse(model.uiState.value.isSaving); assertFalse(model.uiState.value.isDeleting)
        }) { assertTrue(oldSave()) }
        assertEquals(before, proof()); assertEquals(requests, widgets.requestCount); assertEquals(0, saved)
        assertEquals("", model.uiState.value.name); assertNull(model.uiState.value.editAuthority)
        val freshModel = editor()
        show { EditGoalScreen(51L, freshModel, { saved++ }, {}, {}, sourceSelection = newSource) }
        awaitEditor(freshModel, newSource); name("Fresh draft"); save()
        compose.waitUntil(10_000) { saved == 1 && !freshModel.uiState.value.isSaving }
        expectSaved(fresh, "Fresh draft"); assertEquals(requests + 1, widgets.requestCount)
    }

    @Test fun oldSaveCannotConsumeNewDraftInTheSameSourceAndFreshSaveUsesExactCurrentDraft() {
        val row = seed(goal()); val source = selection(); val model = editor(); var saved = 0
        show { EditGoalScreen(51L, model, { saved++ }, {}, {}, sourceSelection = source) }; awaitEditor(model, source)
        name("Draft A"); lazyNode(saveMatcher()).assertIsEnabled()
        val oldSave = retainedClick(saveMatcher())
        name("Draft B"); lazyNode(saveMatcher()).assertIsEnabled()
        assertTrue(source.publication.isCurrentInMemory())
        val before = proof(); val requests = widgets.requestCount
        invokeAndJoinNewChildren(model, immediately = {
            assertFalse(model.uiState.value.isSaving); assertFalse(model.uiState.value.isDeleting)
            assertEquals("Draft B", model.uiState.value.name)
        }) { assertTrue(oldSave()) }
        assertEquals(before, proof()); assertEquals(requests, widgets.requestCount); assertEquals(0, saved)
        assertEquals("Draft B", model.uiState.value.name)
        save(); compose.waitUntil(10_000) { saved == 1 && !model.uiState.value.isSaving }
        expectSaved(row, "Draft B"); assertEquals(requests + 1, widgets.requestCount)
    }

    @Test fun oldChildAndCreateCallbacksCannotMintReplacementSelections() {
        val old = seed(goal()); val oldChild = seed(child(old)); val oldSource = selection(); val model = editor()
        val edits = mutableListOf<DatabaseObjectSelection>(); val creates = mutableListOf<DatabaseObjectSelection>()
        var rawCalls = 0
        show { EditGoalScreen(51L, model, {}, { rawCalls++ }, { rawCalls++ }, oldSource,
            { edits += it }, { creates += it }) }
        awaitEditor(model, oldSource)
        lazyNode(hasText(oldChild.name))
        val oldEdit = retainedClick(hasText(oldChild.name) and hasClickAction())
        val add = hasText(label(R.string.edit_habit_add_key_result)) and hasClickAction()
        lazyNode(add); val oldCreate = retainedClick(add)
        val fresh = replace(old)
        val freshChild = seed(child(fresh).copy(name = "Replacement child", uuid = "ac495000-0000-4000-8000-000000000053"))
        val source = selection(); val freshModel = editor()
        show { EditGoalScreen(51L, freshModel, {}, { rawCalls++ }, { rawCalls++ }, source,
            { edits += it }, { creates += it }) }
        awaitEditor(freshModel, source); val before = proof()
        invokeAndJoinNewChildren(model) { assertTrue(oldEdit()); assertTrue(oldCreate()) }
        assertEquals(before, proof()); assertTrue(edits.isEmpty()); assertTrue(creates.isEmpty()); assertEquals(0, rawCalls)
        lazyNode(hasText(freshChild.name)).assertIsEnabled().performClick()
        lazyNode(add).assertIsEnabled().performClick()
        assertEquals(1, edits.size); assertEquals(1, creates.size)
        assertEquals(freshChild.id, edits.single().id); assertEquals(freshChild.uuid, edits.single().uuid)
        assertSame(source.publication, edits.single().publication); assertSame(source, creates.single())
        assertEquals(0, rawCalls); assertEquals(before, proof())
    }

    @Test fun oldChildrenConfirmCannotConsumeNewRequestAndFreshKeepUsesOriginalAtomicWriter() {
        val parent = seed(goal()); val child = seed(child(parent)); val source = selection(); val model = editor(); var deleted = 0
        show { EditGoalScreen(51L, model, { deleted++ }, {}, {}, sourceSelection = source) }; awaitEditor(model, source)
        fun openChildren() {
            compose.onNodeWithContentDescription(label(R.string.action_delete)).assertIsEnabled().performClick()
            compose.onNode(dialogAction(R.string.action_delete)).assertIsEnabled().performClick()
            compose.waitUntil(10_000) { model.uiState.value.showDeleteChildrenDialog && !model.uiState.value.isDeleting }
            assertEquals(1, model.uiState.value.pendingDeleteChildrenCount)
        }
        openChildren(); val first = requireNotNull(model.uiState.value.deleteRequest)
        val oldKeep = retainedClick(dialogAction(R.string.dialog_delete_habit_keep_children))
        Espresso.pressBack(); compose.waitUntil(10_000) { !model.uiState.value.showDeleteChildrenDialog }
        openChildren(); val second = requireNotNull(model.uiState.value.deleteRequest); assertNotSame(first, second)
        val before = proof(); val requests = widgets.requestCount
        invokeAndJoinNewChildren(model, immediately = {
            assertFalse(model.uiState.value.isDeleting); assertSame(second, model.uiState.value.deleteRequest)
        }) { assertTrue(oldKeep()) }
        assertEquals(before, proof()); assertEquals(requests, widgets.requestCount); assertEquals(0, deleted)
        assertTrue(model.uiState.value.showDeleteChildrenDialog); assertSame(second, model.uiState.value.deleteRequest)
        compose.onNode(dialogAction(R.string.dialog_delete_habit_keep_children)).assertIsEnabled().performClick()
        compose.waitUntil(10_000) { deleted == 1 && !model.uiState.value.isDeleting }
        runBlocking { epochs.current { graph ->
            assertNull(graph.habitDao.getHabitById(parent.id))
            val remaining = requireNotNull(graph.habitDao.getHabitById(child.id))
            assertEquals(child.copy(parentHabitId = null, updatedAt = remaining.updatedAt), remaining)
            val queue = graph.database.syncOutboxDao().getAll(); assertEquals(4, queue.size)
            assertEquals(1, queue.count { it.entityUuid == parent.uuid && it.action == "delete" })
            assertEquals(1, queue.count { it.entityUuid == parent.uuid && it.action == "upsert" })
            assertEquals(2, queue.count { it.entityUuid == child.uuid && it.action == "upsert" })
            assertTrue(queue.all { it.recordType == "habit" }); assertEquals(4, queue.map { it.operationId }.distinct().size)
        } }
        assertEquals(requests + 1, widgets.requestCount)
    }

    @Test fun sameDatabaseSessionReplacementClearsPersonalDraftAndOldCallbacksCannotReviveIt() {
        val row = seed(goal()); val source = selection(); val model = editor(); var saved = 0
        show { EditGoalScreen(51L, model, { saved++ }, {}, {}, sourceSelection = source) }; awaitEditor(model, source)
        val secret = "Private goal draft"; name(secret)
        val oldSetText = requireNotNull(compose.onNodeWithText(label(R.string.edit_habit_name_label))
            .fetchSemanticsNode().config[SemanticsActions.SetText].action)
        lazyNode(saveMatcher()).assertIsEnabled(); val oldSave = retainedClick(saveMatcher())
        val database = runBlocking { epochs.current { it.database } }
        runBlocking { epochs.login() }
        assertSame(database, runBlocking { epochs.current { it.database } }); assertFalse(source.publication.isCurrentInMemory())
        compose.waitUntil(10_000) { model.uiState.value.sourceExpired }
        assertEquals("", model.uiState.value.name); assertEquals("", model.uiState.value.description)
        assertTrue(model.uiState.value.children.isEmpty()); assertNull(model.uiState.value.appearance); assertNull(model.uiState.value.editAuthority)
        compose.onNodeWithText(secret).assertDoesNotExist()
        val before = proof(); val requests = widgets.requestCount
        invokeAndJoinNewChildren(model, immediately = { assertFalse(model.uiState.value.isSaving) }) {
            assertTrue(oldSetText(AnnotatedString(secret))); assertTrue(oldSave()); model.updateName(secret); model.loadGoal(row.id, source)
        }
        assertEquals(before, proof()); assertEquals(requests, widgets.requestCount); assertEquals(0, saved)
        assertEquals("", model.uiState.value.name); compose.onNodeWithText(secret).assertDoesNotExist()
        val freshSource = selection(); val freshModel = editor()
        show { EditGoalScreen(51L, freshModel, { saved++ }, {}, {}, sourceSelection = freshSource) }
        awaitEditor(freshModel, freshSource); name("Fresh session goal"); save()
        compose.waitUntil(10_000) { saved == 1 && !freshModel.uiState.value.isSaving }
        expectSaved(row, "Fresh session goal")
    }

    @Test fun sameEpochNumericIdReuseFailsAtCurrentRowAndDoesNotWriteReplacement() {
        val old = seed(goal()); val source = selection(); val model = editor(); var saved = 0
        show { EditGoalScreen(51L, model, { saved++ }, {}, {}, sourceSelection = source) }; awaitEditor(model, source)
        name("Must not rename replacement"); lazyNode(saveMatcher()).assertIsEnabled()
        val oldSave = retainedClick(saveMatcher())
        // Controlled, same-file fixture mutation. This does not pretend to be a new UI command.
        val replacement = old.copy(name = "Same epoch new UUID", uuid = "ac495000-0000-4000-8000-000000000003")
        runBlocking { epochs.current { graph -> graph.database.withTransaction { graph.habitDao.update(replacement) } } }
        assertTrue(source.publication.isCurrentInMemory())
        val before = proof(); val requests = widgets.requestCount
        invokeAndJoinNewChildren(model) { assertTrue(oldSave()) }
        assertEquals(before, proof()); assertEquals(requests, widgets.requestCount); assertEquals(0, saved)
        assertFalse(model.uiState.value.isSaving); assertNotNull(model.uiState.value.errorMessage)
        runBlocking { epochs.current { assertEquals(replacement, it.habitDao.getHabitById(old.id)) } }
        compose.onNode(dialogAction(R.string.action_confirm)).performClick()
        val freshSource = selection(); val freshModel = editor()
        show { EditGoalScreen(51L, freshModel, { saved++ }, {}, {}, sourceSelection = freshSource) }
        awaitEditor(freshModel, freshSource); name("Fresh UUID permitted"); save()
        compose.waitUntil(10_000) { saved == 1 && !freshModel.uiState.value.isSaving }
        runBlocking { epochs.current { graph ->
            val current = requireNotNull(graph.habitDao.getHabitById(old.id))
            assertEquals(replacement.copy(name = "Fresh UUID permitted", updatedAt = current.updatedAt), current)
        } }
    }
}
