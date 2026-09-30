package com.dayforge.data.appearance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.ThemeVersionRef
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceThemeRepositoryTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val parent = Files.createTempDirectory(app.targetContext.filesDir.toPath(), "device-appearance-").toFile()
    private val file = File(parent, "selection.preferences_pb")
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun newPreferences() = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    private var preferences = newPreferences()
    private val files = ThemeFileRepository(parent)
    private val builtIns = BuiltInThemes(app.targetContext.assets)
    private fun repository(storage: ThemeFileRepository = files) = DeviceThemeRepository(preferences, storage, builtIns)
    private fun customBytes() = app.context.assets.open("next/theme.json").use { it.readBytes() }
    private fun themeFile(ref: ThemeVersionRef) = File(parent, "theme-definitions-v1/${ref.themeId}-${ref.revision}.json")
    private suspend fun reopen() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = newPreferences()
    }
    @After fun cleanup() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(parent.deleteRecursively())
    }

    @Test fun concurrentColdStartsInstallAndSelectOnceWithoutChangingLegacyOrAccountPreferences() = runBlocking<Unit> {
        val oldMode = stringPreferencesKey("theme_mode")
        val account = stringPreferencesKey("synthetic_account_setting")
        preferences.edit { it[oldMode] = "dark"; it[account] = "preserve" }
        val starts = (0..7).map { async { repository().initialize() } }.awaitAll()
        assertEquals(1, starts.map { it.saved }.toSet().size)
        val initial = starts.first().saved
        assertEquals(1L, initial.revision)
        assertEquals(ThemeVersionRef(BuiltInTheme.OCEAN.themeId, 1), initial.selection.light)
        assertEquals(ThemeVersionRef(BuiltInTheme.DUSK.themeId, 1), initial.selection.dark)
        assertEquals(DeviceThemeMode.SYSTEM, initial.selection.mode)
        assertEquals(DeviceCardStyle.FOLLOW_THEME, initial.selection.cardStyle)
        assertEquals("dark", preferences.data.first()[oldMode])
        assertEquals("preserve", preferences.data.first()[account])
        assertEquals(7, repository().catalog()!!.slots.size)
        reopen()
        assertEquals(initial, repository().initialize().saved)
    }

    @Test fun completeChoiceSurvivesColdStartAndStaleSelectionCannotOverwriteIt() = runBlocking<Unit> {
        val initial = repository().initialize()
        val preview = repository().preview { ByteArrayInputStream(customBytes()) }
        repository().install(preview)
        val choice = initial.saved.selection.copy(light = preview.ref(),
            dark = ThemeVersionRef(BuiltInTheme.OLED.themeId, 1), mode = DeviceThemeMode.DARK,
            cardStyle = DeviceCardStyle.PERSONALIZED)
        val changed = repository().select(initial.saved.revision, choice)
        assertEquals(choice, changed.saved.selection)
        assertEquals(0xff000000.toInt(), changed.resolve(false).material.getValue("background"))
        val stale = assertThrows(ThemeSelectionException::class.java) {
            runBlocking { repository().select(initial.saved.revision, initial.saved.selection) }
        }
        assertEquals("THEME_SELECTION_CONFLICT", stale.code)
        reopen()
        val reloaded = repository().initialize()
        assertEquals(changed.saved, reloaded.saved)
        assertEquals(changed.light.material, reloaded.light.material)
        assertEquals(changed.dark.material, reloaded.dark.material)
    }

    @Test fun frozenPreviewOnlyInstallsOnConfirmationAndExportsTheSameCompleteBytes() = runBlocking<Unit> {
        val initial = repository().initialize()
        val before = preferences.data.first()
        val source = customBytes()
        val opens = AtomicInteger(); val closes = AtomicInteger()
        val preview = repository().preview {
            opens.incrementAndGet()
            object : ByteArrayInputStream(source) {
                override fun close() { closes.incrementAndGet(); super.close() }
            }
        }
        assertEquals(1, opens.get()); assertEquals(1, closes.get())
        assertEquals(before, preferences.data.first())
        assertFalse(themeFile(preview.ref()).exists())
        source.fill(0)
        repository().install(preview)
        val export = repository().export(preview.ref())
        assertArrayEquals(customBytes(), export.exportBytes())
        assertEquals(initial.saved, repository().load().saved)
        assertEquals(1, opens.get())
        repository().delete(preview.ref(), repository().catalog()!!.revision)
        assertFalse(themeFile(preview.ref()).exists())
        assertEquals(initial.saved, repository().load().saved)
    }

    @Test fun interruptedPublishedImportRecoversBeforeColdThemeLoadWithoutSelectingIt() = runBlocking<Unit> {
        val initial = repository().initialize()
        val preview = repository().preview { ByteArrayInputStream(customBytes()) }
        val broken = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun rename(source: String, target: String) { super.rename(source, target); throw IOException("after publish") }
        })
        assertThrows(IOException::class.java) { runBlocking { repository(broken).install(preview) } }
        assertEquals(ThemeInstallPhase.INSTALLING, repository().catalog()!!.slots.last().phase)
        reopen()
        assertEquals(initial.saved, repository().initialize().saved)
        assertEquals(ThemeInstallPhase.ACTIVE, repository().catalog()!!.slots.last().phase)
        assertArrayEquals(customBytes(), repository().export(preview.ref()).exportBytes())
    }

    @Test fun malformedMetadataAndMissingSavedPalettesYieldFailureNotAChangedSelection() = runBlocking<Unit> {
        val key = stringPreferencesKey("appearance_theme_selection_v1")
        preferences.edit { it[key] = "{}" }
        val malformed = repository().observe().toList()
        assertEquals(DeviceThemeLoadState.Loading, malformed.first())
        assertTrue(malformed.last() is DeviceThemeLoadState.Failed)
        assertEquals("{}", preferences.data.first()[key])
        assertFalse(File(parent, "theme-definitions-v1").exists())
        preferences.edit { it.remove(key) }
        val initial = repository().initialize()
        val before = preferences.data.first()
        val missing = themeFile(initial.saved.selection.light)
        assertTrue(missing.delete())
        reopen()
        val failure = repository().observe().toList()
        assertEquals(2, failure.size)
        assertTrue(failure.last() is DeviceThemeLoadState.Failed)
        assertFalse(missing.exists())
        assertEquals(before, preferences.data.first())
        // Restore only the synthetic damaged fixture; production never silently recreates it.
        files.install(builtIns.readAll().single { it.ref() == initial.saved.selection.light })
        assertEquals(initial.saved, repository().initialize().saved)
    }

    @Test fun observerPublishesAtomicChoicesAndPropagatesFileFailuresWithoutDefaultFallback() = runBlocking<Unit> {
        val states = Channel<DeviceThemeLoadState>(Channel.UNLIMITED)
        val observer = launch { repository().observe().collect { states.send(it) } }
        try {
            assertEquals(DeviceThemeLoadState.Loading, withTimeout(5000) { states.receive() })
            val initial = (withTimeout(5000) { states.receive() } as DeviceThemeLoadState.Ready).theme
            val changed = repository().select(initial.saved.revision, initial.saved.selection.copy(
                mode = DeviceThemeMode.LIGHT, cardStyle = DeviceCardStyle.PERSONALIZED))
            val updated = (withTimeout(5000) { states.receive() } as DeviceThemeLoadState.Ready).theme
            assertEquals(changed.saved, updated.saved)
            themeFile(changed.saved.selection.dark).writeText("damaged")
            // An explicit re-subscription retries and revalidates stored files; a timer tick does not.
            val failed = repository().observe().toList()
            assertTrue(failed.last() is DeviceThemeLoadState.Failed)
            assertEquals(changed.saved, DeviceThemeStore(preferences, files).read())
        } finally { observer.cancelAndJoin(); states.close() }
    }

    @Test fun brokenUnusedPresetDoesNotPreventLoadingTheValidSavedChoice() = runBlocking<Unit> {
        val initial = repository().initialize()
        val unused = ThemeVersionRef(BuiltInTheme.NATURE.themeId, 1)
        assertTrue(themeFile(unused).delete())
        reopen()
        assertEquals(initial.saved, repository().initialize().saved)
        val listing = repository().library()
        assertTrue(listing.items.single { it.slot.ref == unused }.content is ThemeCatalogContent.Unavailable)
        assertEquals(6, listing.items.count { it.content is ThemeCatalogContent.Available })
        assertFalse(themeFile(unused).exists())
        assertThrows(Exception::class.java) {
            runBlocking { repository().select(initial.saved.revision, initial.saved.selection.copy(light = unused)) }
        }
        assertEquals(initial.saved, repository().load().saved)
    }

    @Test fun cancelledPreviewClosesInputWithoutCatalogOrPreferenceChanges() = runBlocking<Unit> {
        repository().initialize()
        val before = preferences.data.first()
        var closed = false
        val cancelled = CancellationException("cancel source")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                repository().preview {
                    object : ByteArrayInputStream(customBytes()) {
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw cancelled
                        override fun close() { closed = true; super.close() }
                    }
                }
            }
        }
        assertSame(cancelled, thrown); assertTrue(closed)
        assertEquals(before, preferences.data.first())
        assertEquals(7, repository().catalog()!!.slots.size)
    }

    @Test fun uninitializedReloadAndSelectionAreErrorsNotImplicitInitialization() = runBlocking<Unit> {
        val error = assertThrows(ThemeSelectionException::class.java) { runBlocking { repository().load() } }
        assertEquals("THEME_SELECTION_UNINITIALIZED", error.code)
        assertNull(repository().catalog())
        assertFalse(File(parent, "theme-definitions-v1").exists())
    }
}
