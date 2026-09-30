package com.dayforge.ui.theme

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.appearance.BuiltInTheme
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.DeviceThemeLoadState
import com.dayforge.data.appearance.DeviceThemeRepository
import com.dayforge.data.appearance.THEME_CATALOG_KEY
import com.dayforge.data.appearance.ThemeFileRepository
import com.dayforge.data.appearance.ThemeInstallPhase
import com.dayforge.data.appearance.ValidatedTheme
import com.dayforge.data.appearance.ref
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.widget.WidgetRefreshScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeRecoveryViewModelTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val context = app.targetContext
    private val directory = Files.createTempDirectory(context.filesDir.toPath(), "theme-repair-").toFile()
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = PreferenceDataStoreFactory.create(scope = dataScope) { File(directory, "prefs.preferences_pb") }
    private val repository = DeviceThemeRepository(preferences, ThemeFileRepository(directory), BuiltInThemes(context.assets))
    private val controller = DeviceThemeController(repository, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private val store = ViewModelStore()
    private val unrelated = stringPreferencesKey("synthetic_unrelated")
    private fun ref(theme: BuiltInTheme) = ThemeVersionRef(theme.themeId, theme.revision)
    private fun file(ref: ThemeVersionRef) = File(directory, "theme-definitions-v1/${ref.themeId}-${ref.revision}.json")
    private suspend fun open(): ThemeRecoveryViewModel {
        val model = withContext(Dispatchers.Main) {
            ThemeRecoveryViewModel(context, controller).also { store.put("repair", it) }
        }
        idle(model)
        return model
    }
    private suspend fun idle(model: ThemeRecoveryViewModel) = withTimeout(5000) { model.state.first { !it.busy } }
    private suspend fun failed() {
        controller.retry()
        withTimeout(5000) { controller.state.first { it is DeviceThemeLoadState.Failed } }
    }
    private suspend fun custom(): ValidatedTheme = ValidatedTheme.read { app.context.assets.open("next/theme.json") }

    @Before fun start() = runBlocking<Unit> {
        withTimeout(5000) { controller.current() }
        preferences.edit { it[unrelated] = "preserve" }
        mockkObject(WidgetRefreshScheduler)
        every { WidgetRefreshScheduler.request(context) } returns mockk()
    }
    @After fun finish() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { store.clear() }
        controller.close()
        dataScope.coroutineContext.job.cancelAndJoin()
        unmockkObject(WidgetRefreshScheduler)
        assertTrue(directory.deleteRecursively())
    }

    @Test fun damagedSelectedFileCanBeReplacedOnlyByConfirmedChoiceWithoutChangingOtherPreferences() = runBlocking<Unit> {
        val original = repository.savedSelection()!!
        val chosen = repository.select(original.revision, original.selection.copy(
            mode = DeviceThemeMode.DARK, cardStyle = DeviceCardStyle.PERSONALIZED)).saved
        assertTrue(file(chosen.selection.light).delete())
        failed()
        val before = preferences.data.first()
        val model = open()
        assertFalse(model.state.value.canApply)
        assertEquals(before, preferences.data.first())
        withContext(Dispatchers.Main) { model.choose(ref(BuiltInTheme.NATURE), false) }
        assertTrue(model.state.value.canApply)
        assertEquals(before, preferences.data.first())
        withContext(Dispatchers.Main) { model.apply() }
        assertNull(idle(model).error)
        withTimeout(5000) { controller.state.first { it is DeviceThemeLoadState.Ready } }
        val saved = repository.savedSelection()!!
        assertEquals(chosen.revision + 1, saved.revision)
        assertEquals(chosen.selection.copy(light = ref(BuiltInTheme.NATURE)), saved.selection)
        assertEquals("preserve", preferences.data.first()[unrelated])
        assertFalse(file(chosen.selection.light).exists())
        verify(exactly = 1) { WidgetRefreshScheduler.request(context) }
    }

    @Test fun corruptPreferenceCannotBeResetByOpeningOrApplyingRecovery() = runBlocking<Unit> {
        preferences.edit { it[stringPreferencesKey("appearance_theme_selection_v1")] = "damaged" }
        failed()
        val before = preferences.data.first()
        val model = open()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.library)
        withContext(Dispatchers.Main) { model.choose(ref(BuiltInTheme.NATURE), false); model.apply() }
        assertEquals(before, preferences.data.first())
        verify(exactly = 0) { WidgetRefreshScheduler.request(context) }
    }

    @Test fun staleRecoveryDraftCannotOverwriteANewerSelection() = runBlocking<Unit> {
        val model = open()
        val original = repository.savedSelection()!!
        withContext(Dispatchers.Main) { model.choose(ref(BuiltInTheme.NATURE), false) }
        val newer = repository.select(original.revision, original.selection.copy(mode = DeviceThemeMode.DARK)).saved
        withContext(Dispatchers.Main) { model.apply() }
        assertEquals("THEME_SELECTION_CONFLICT", idle(model).error?.message)
        assertEquals(newer, repository.savedSelection())
        verify(exactly = 0) { WidgetRefreshScheduler.request(context) }
    }

    @Test fun brokenPendingInstallCanBeExplicitlyCancelledWithoutChangingActiveSelection() = runBlocking<Unit> {
        val theme = custom()
        val installed = repository.install(theme)
        val pending = installed.next(installed.slots.map {
            if (it.ref == theme.ref()) it.copy(phase = ThemeInstallPhase.INSTALLING) else it
        })
        preferences.edit { it[THEME_CATALOG_KEY] = Json.encodeToString(pending) }
        file(theme.ref()).writeText("damaged pending install")
        failed()
        val saved = repository.savedSelection()
        val model = open()
        assertTrue(file(theme.ref()).exists())
        withContext(Dispatchers.Main) { model.delete(theme.ref(), pending.revision) }
        assertNull(idle(model).error)
        withTimeout(5000) { controller.state.first { it is DeviceThemeLoadState.Ready } }
        assertEquals(saved, repository.savedSelection())
        assertEquals(7, repository.catalog()!!.slots.size)
        assertFalse(file(theme.ref()).exists())
        assertEquals("preserve", preferences.data.first()[unrelated])
        verify(exactly = 1) { WidgetRefreshScheduler.request(context) }
    }

    @Test fun recoveryCannotDeleteASelectedCustomThemeEvenWhenItsFileIsBroken() = runBlocking<Unit> {
        val theme = custom()
        val installed = repository.install(theme)
        val saved = repository.savedSelection()!!
        repository.select(saved.revision, saved.selection.copy(light = theme.ref()))
        file(theme.ref()).writeText("damaged selected file")
        failed()
        val model = open()
        val before = preferences.data.first()
        withContext(Dispatchers.Main) { model.delete(theme.ref(), installed.revision) }
        assertEquals("THEME_IN_USE", idle(model).error?.message)
        assertEquals(before, preferences.data.first())
        assertEquals("damaged selected file", file(theme.ref()).readText())
    }

    @Test fun invalidOrWrongModeChoicesNeverChangeTheDraft() = runBlocking<Unit> {
        val model = open()
        val before = model.state.value
        withContext(Dispatchers.Main) {
            model.choose(ref(BuiltInTheme.OCEAN), true)
            model.choose(ref(BuiltInTheme.DUSK), false)
            model.choose(ThemeVersionRef("60000000-0000-4000-8000-000000000001", 1), false)
        }
        assertEquals(before, model.state.value)
    }
}
