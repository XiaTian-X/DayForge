package com.dayforge.ui.screens.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.appearance.*
import com.dayforge.domain.appearance.ThemeColorField
import com.dayforge.domain.appearance.ThemeColorGroup
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.service.DeviceThemeController
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeEditorWorkflowTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val parent = Files.createTempDirectory(app.targetContext.filesDir.toPath(), "theme-editor-").toFile()
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = PreferenceDataStoreFactory.create(scope = preferenceScope,
        produceFile = { File(parent, "editor.preferences_pb") })
    private val failAfterPublish = AtomicBoolean(false)
    private val failBeforePublish = AtomicBoolean(false)
    private val files = ThemeFileRepository(parent, object : ThemeFileIo() {
        override fun rename(source: String, target: String) {
            if (failBeforePublish.getAndSet(false)) throw IOException("synthetic publication failure")
            super.rename(source, target)
            if (failAfterPublish.getAndSet(false)) throw IOException("synthetic publication response lost")
        }
    })
    private val repository = DeviceThemeRepository(preferences, files, BuiltInThemes(app.targetContext.assets))
    private var controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var controller = DeviceThemeController(repository, controllerScope)
    private val primary = ThemeColorField(false, ThemeColorGroup.MATERIAL, "primary")
    private val handle = SavedStateHandle()
    private fun editor(saved: SavedStateHandle = handle) = ThemeEditorWorkflow(controller, saved)
    private suspend fun choice(ref: ThemeVersionRef = ThemeVersionRef(BuiltInTheme.OCEAN.themeId, 1)): ThemeChoiceSummary {
        val library = controller.library()
        return ThemeChoiceSummary(library.items.single { it.slot.ref == ref }, library.state.revision)
    }
    private suspend fun custom(revision: Int = 1): ValidatedTheme {
        val source = ValidatedTheme.read { app.context.assets.open("next/theme.json") }
        return ValidatedTheme.read { Json.encodeToString(source.definition.copy(revision = revision)).byteInputStream() }
    }
    private suspend fun <T> main(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }
    @Before fun initialize() = runBlocking<Unit> {
        assertEquals("com.dayforge.testbed", app.targetContext.packageName)
        withTimeout(5000) { controller.current() }
    }
    @After fun cleanup() = runBlocking {
        controller.close(); preferenceScope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(parent.deleteRecursively())
    }

    @Test fun invalidInputPreviewAndCancelCannotWriteCatalogFilesOrActiveSelection() = runBlocking<Unit> {
        val before = preferences.data.first()
        val editor = editor(); val source = choice()
        main {
            editor.begin(source); editor.color(primary, "#bad"); editor.prepare()
            assertEquals("THEME_EDIT_INVALID", editor.state.value.error!!.message)
            assertEquals("#bad", editor.state.value.draft!!.color(primary))
            editor.color(primary, "#112233"); editor.rename("Personal colors"); editor.prepare()
        }
        assertEquals("#112233", editor.state.value.preview!!.definition.light.material["primary"])
        assertEquals(before, preferences.data.first())
        assertEquals(7, File(parent, "theme-definitions-v1").listFiles()!!.size)
        main { editor.cancel() }
        assertFalse(handle.contains(ThemeEditorWorkflow.KEY)); assertFalse(editor.state.value.open)
        assertEquals(before, preferences.data.first())
    }

    @Test fun builtinForkAndRepeatedConfirmationSaveOnceAndRoundTripWithoutActivation() = runBlocking<Unit> {
        val selected = controller.current().saved
        val source = choice(); val original = controller.export(source.ref).exportBytes()
        val editor = editor()
        main { editor.begin(source); editor.color(primary, "#123456"); editor.prepare() }
        val preview = editor.state.value.preview!!
        assertNotEquals(source.ref.themeId, preview.definition.themeId); assertEquals(1, preview.definition.revision)
        main { coroutineScope { repeat(4) { launch { editor.confirm(preview) } } } }
        assertEquals(8, controller.catalog()!!.slots.size)
        assertEquals(preview.ref(), editor.state.value.saved)
        assertEquals(selected, controller.current().saved)
        assertArrayEquals(original, controller.export(source.ref).exportBytes())
        val exported = controller.export(preview.ref()).exportBytes()
        assertEquals(preview.definition, ValidatedTheme.read { exported.inputStream() }.definition)
        assertFalse(handle.contains(ThemeEditorWorkflow.KEY))
    }

    @Test fun customEditingAdvancesHighestRevisionAndSelectedOldVersionStaysProtected() = runBlocking<Unit> {
        val first = custom(); controller.install(first); controller.install(custom(7))
        val before = controller.current().saved
        val selected = controller.select(before.revision, before.selection.copy(light = first.ref())).saved
        val original = controller.export(first.ref()).exportBytes()
        val editor = editor(); val source = choice(first.ref())
        main { editor.begin(source); editor.color(primary, "#112233"); editor.prepare() }
        val preview = editor.state.value.preview!!
        assertEquals(first.definition.themeId, preview.definition.themeId); assertEquals(8, preview.definition.revision)
        main { editor.confirm(preview) }
        assertEquals(selected, controller.current().saved)
        assertArrayEquals(original, controller.export(first.ref()).exportBytes())
        val error = assertThrows(ThemeCatalogException::class.java) {
            runBlocking { controller.delete(first.ref(), controller.catalog()!!.revision) }
        }
        assertEquals("THEME_IN_USE", error.code)
        assertEquals(8, controller.export(preview.ref()).definition.revision)
    }

    @Test fun staleCatalogCheckHappensBeforeReservationAndPreservesFrozenCandidate() = runBlocking<Unit> {
        val editor = editor(); val source = choice()
        main { editor.begin(source); editor.color(primary, "#112233"); editor.prepare() }
        val preview = editor.state.value.preview!!
        val unrelated = custom(); controller.install(unrelated)
        val before = controller.catalog()
        main { editor.confirm(preview) }
        assertEquals("THEME_CATALOG_CONFLICT", editor.state.value.error!!.message)
        assertSame(preview, editor.state.value.preview); assertTrue(editor.state.value.locked)
        assertEquals(before, controller.catalog())
        assertFalse(File(parent, "theme-definitions-v1/${preview.definition.themeId}-1.json").exists())
        main { editor.cancel() }
        assertEquals(unrelated.definition, controller.export(unrelated.ref()).definition)
    }

    @Test fun concurrentEditorsCannotOverwriteTheSameCustomRevisionWithDifferentColors() = runBlocking<Unit> {
        val first = custom(); controller.install(first)
        val selected = controller.current().saved
        val source = choice(first.ref())
        val one = editor(SavedStateHandle()); val two = editor(SavedStateHandle())
        main {
            one.begin(source); two.begin(source)
            one.color(primary, "#112233"); two.color(primary, "#445566")
            one.prepare(); two.prepare()
        }
        val candidates = listOf(one.state.value.preview!!, two.state.value.preview!!)
        assertEquals(candidates[0].ref(), candidates[1].ref())
        main { coroutineScope {
            launch { one.confirm(candidates[0]) }; launch { two.confirm(candidates[1]) }
        } }
        val states = listOf(one.state.value, two.state.value)
        val winner = states.indexOfFirst { it.saved != null }
        assertTrue(winner >= 0)
        assertEquals(1, states.count { it.saved != null })
        assertEquals("THEME_VERSION_REUSED", states[1 - winner].error!!.message)
        assertTrue(states[1 - winner].locked)
        assertEquals(candidates[winner].definition, controller.export(candidates[winner].ref()).definition)
        assertEquals(9, controller.catalog()!!.slots.size)
        assertEquals(selected, controller.current().saved)
    }

    @Test fun failureAfterPublicationLocksCandidateAndRetryFinishesTheSameOwnedJournal() = runBlocking<Unit> {
        val first = custom(); controller.install(first)
        val editor = editor(); val source = choice(first.ref())
        val selected = controller.current().saved
        main { editor.begin(source); editor.color(primary, "#112233"); editor.prepare() }
        val preview = editor.state.value.preview!!
        failAfterPublish.set(true)
        main { editor.confirm(preview) }
        assertTrue(editor.state.value.error is IOException); assertTrue(editor.state.value.locked)
        assertTrue(File(parent, "theme-definitions-v1/${preview.definition.themeId}-2.json").exists())
        assertEquals(ThemeInstallPhase.INSTALLING, controller.catalog()!!.slots.single { it.ref == preview.ref() }.phase)
        main { editor.color(primary, "#FF0000"); editor.rename("Must not replace uncertain candidate"); editor.backToEdit() }
        assertSame(preview, editor.state.value.preview)
        assertEquals("#112233", editor.state.value.draft!!.color(primary))
        main { editor.confirm(preview) }
        assertEquals(preview.ref(), editor.state.value.saved)
        assertEquals(ThemeInstallPhase.ACTIVE, controller.catalog()!!.slots.single { it.ref == preview.ref() }.phase)
        assertEquals(9, controller.catalog()!!.slots.size); assertEquals(selected, controller.current().saved)
        assertEquals(preview.definition, controller.export(preview.ref()).definition)
    }

    @Test fun restoredLostResponseRecognisesCommittedTargetEvenWhenSourceWasRemoved() = runBlocking<Unit> {
        val first = custom(); controller.install(first)
        val editor = editor(); val source = choice(first.ref())
        val selected = controller.current().saved
        main { editor.begin(source); editor.color(primary, "#112233"); editor.prepare() }
        val target = editor.state.value.preview!!.ref()
        failAfterPublish.set(true)
        main { editor.confirm(editor.state.value.preview!!) }
        val saved = main { handle.get<String>(ThemeEditorWorkflow.KEY)!! }
        controller.close()
        controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controller = DeviceThemeController(repository, controllerScope)
        withTimeout(5000) { controller.current() }
        assertEquals(ThemeInstallPhase.ACTIVE, controller.catalog()!!.slots.single { it.ref == target }.phase)
        controller.delete(first.ref(), controller.catalog()!!.revision)
        val restoredHandle = SavedStateHandle(mapOf(ThemeEditorWorkflow.KEY to saved))
        val restored = editor(restoredHandle)
        main { restored.restore() }
        assertEquals(target, restored.state.value.saved); assertFalse(restored.state.value.open)
        assertFalse(restoredHandle.contains(ThemeEditorWorkflow.KEY))
        assertEquals(selected, controller.current().saved)
        assertEquals("#112233", controller.export(target).definition.light.material["primary"])
    }

    @Test fun prePublicationFailureRestoresAndRetriesExactCandidateAfterEmptyJournalRecovery() = runBlocking<Unit> {
        val first = custom(); controller.install(first)
        val selected = controller.current().saved
        val editor = editor(); val source = choice(first.ref())
        main { editor.begin(source); editor.color(primary, "#112233"); editor.prepare() }
        val preview = editor.state.value.preview!!
        failBeforePublish.set(true)
        main { editor.confirm(preview) }
        assertTrue(editor.state.value.error is IOException)
        val target = File(parent, "theme-definitions-v1/${preview.definition.themeId}-2.json")
        assertFalse(target.exists())
        assertEquals(ThemeInstallPhase.INSTALLING, controller.catalog()!!.slots.single { it.ref == preview.ref() }.phase)
        val saved = main { handle.get<String>(ThemeEditorWorkflow.KEY)!! }
        controller.close()
        controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controller = DeviceThemeController(repository, controllerScope)
        withTimeout(5000) { controller.current() }
        assertFalse(controller.catalog()!!.slots.any { it.ref == preview.ref() })
        val recovered = controller.catalog()!!
        val restored = editor(SavedStateHandle(mapOf(ThemeEditorWorkflow.KEY to saved)))
        main { restored.restore() }
        assertTrue(restored.state.value.locked)
        assertEquals(preview.definition, restored.state.value.preview!!.definition)
        assertEquals(recovered, controller.catalog()); assertFalse(target.exists())
        main { restored.confirm(restored.state.value.preview!!) }
        assertEquals(preview.ref(), restored.state.value.saved)
        assertEquals(preview.definition, controller.export(preview.ref()).definition)
        assertEquals(9, controller.catalog()!!.slots.size)
        assertEquals(selected, controller.current().saved)
    }

    @Test fun recoveredRetryRejectsNewerRevisionAndDeletedSourceWithoutMintingReplacement() = runBlocking<Unit> {
        val first = custom(); controller.install(first)
        val editor = editor(); val source = choice(first.ref())
        main { editor.begin(source); editor.color(primary, "#112233"); editor.prepare() }
        val preview = editor.state.value.preview!!
        failBeforePublish.set(true)
        main { editor.confirm(preview) }
        controller.recover()
        val newer = custom(3); controller.install(newer)
        var before = preferences.data.first()
        main { editor.confirm(preview) }
        assertEquals("THEME_CATALOG_CONFLICT", editor.state.value.error!!.message)
        assertSame(preview, editor.state.value.preview)
        assertEquals(before, preferences.data.first())
        assertFalse(controller.catalog()!!.slots.any { it.ref == preview.ref() })
        controller.delete(first.ref(), controller.catalog()!!.revision)
        before = preferences.data.first()
        main { editor.confirm(preview) }
        assertEquals("THEME_NOT_AVAILABLE", editor.state.value.error!!.message)
        assertEquals(before, preferences.data.first())
        assertFalse(controller.catalog()!!.slots.any { it.ref == preview.ref() })
        assertEquals(newer.definition, controller.export(newer.ref()).definition)
    }

    @Test fun restoredInvalidInputsStayVisibleWhileCorruptFutureAndDuplicateStateIsNotDefaulted() = runBlocking<Unit> {
        val editor = editor(); val source = choice()
        main { editor.begin(source); editor.color(primary, "#bad") }
        val raw = main { handle.get<String>(ThemeEditorWorkflow.KEY)!! }
        val restored = editor(SavedStateHandle(mapOf(ThemeEditorWorkflow.KEY to raw)))
        main { restored.restore() }
        assertEquals("#bad", restored.state.value.draft!!.color(primary)); assertFalse(restored.state.value.draft!!.valid)
        val before = preferences.data.first()
        for (bad in listOf(raw.replace("\"version\":1", "\"version\":2"),
            "{\"version\":1," + raw.drop(1), raw.replace(primary.key, "light.material.future"), "x".repeat(16_385))) {
            val badHandle = SavedStateHandle(mapOf(ThemeEditorWorkflow.KEY to bad))
            val broken = editor(badHandle)
            main { broken.restore() }
            assertNotNull(broken.state.value.error); assertNull(broken.state.value.draft)
            assertEquals(bad, badHandle.get<String>(ThemeEditorWorkflow.KEY))
            assertEquals(before, preferences.data.first())
            main { broken.cancel() }
            assertFalse(badHandle.contains(ThemeEditorWorkflow.KEY))
        }
    }

    @Test fun preConfirmationSnapshotsRecogniseMatchingCommitButNeverOverwriteAnotherCandidate() = runBlocking<Unit> {
        val source = choice(); val editor = editor()
        main { editor.begin(source); editor.color(primary, "#112233") }
        val beforePreview = main { handle.get<String>(ThemeEditorWorkflow.KEY)!! }
        main { editor.prepare() }
        val beforeConfirmation = main { handle.get<String>(ThemeEditorWorkflow.KEY)!! }
        val candidate = editor.state.value.preview!!
        main { editor.confirm(candidate) }
        val before = preferences.data.first()
        for (raw in listOf(beforePreview, beforeConfirmation)) {
            val restored = editor(SavedStateHandle(mapOf(ThemeEditorWorkflow.KEY to raw)))
            main { restored.restore() }
            assertEquals(candidate.ref(), restored.state.value.saved)
            assertEquals(before, preferences.data.first())
        }
        val stale = beforePreview.replace("#112233", "#445566")
        val restoredHandle = SavedStateHandle(mapOf(ThemeEditorWorkflow.KEY to stale))
        val restored = editor(restoredHandle)
        main { restored.restore() }
        assertEquals("THEME_VERSION_REUSED", restored.state.value.error!!.message)
        assertEquals(stale, restoredHandle.get<String>(ThemeEditorWorkflow.KEY))
        assertEquals(candidate.definition, controller.export(candidate.ref()).definition)
        assertEquals(before, preferences.data.first())
    }

    @Test fun exhaustedRevisionAndStaleSourceChoiceCannotCreateDraftOrInstallFiles() = runBlocking<Unit> {
        val source = choice()
        val last = custom(Int.MAX_VALUE); controller.install(last)
        val before = preferences.data.first(); val editor = editor()
        main { editor.begin(source) }
        assertEquals("THEME_CATALOG_CONFLICT", editor.state.value.error!!.message)
        main { editor.cancel() }
        val current = choice(last.ref())
        main { editor.begin(current) }
        assertEquals("THEME_EDIT_REVISION_EXHAUSTED", editor.state.value.error!!.message)
        assertFalse(handle.contains(ThemeEditorWorkflow.KEY)); assertEquals(before, preferences.data.first())
        assertEquals(8, controller.catalog()!!.slots.size)
    }
}
