package com.dayforge.ui.screens.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.DeviceThemeRepository
import com.dayforge.data.appearance.ThemeFileRepository
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.widget.WidgetRefreshScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAppearanceWidgetRefreshTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = Files.createTempDirectory(context.filesDir.toPath(), "settings-refresh-").toFile()
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = PreferenceDataStoreFactory.create(scope = dataScope) { File(directory, "prefs.preferences_pb") }
    private val failWrites = AtomicBoolean(false)
    private val boundary = object : DataStore<Preferences> by preferences {
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites.get()) throw IOException("synthetic preference write failure")
            return preferences.updateData(transform)
        }
    }
    private val repository = DeviceThemeRepository(boundary, ThemeFileRepository(directory), BuiltInThemes(context.assets))
    private val controller = DeviceThemeController(repository, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private val workflow = SettingsAppearanceWorkflow(context, PreferencesManager(preferences), controller)
    private val notified = mutableListOf<DeviceThemeSelection>()

    @Before fun setup() = runBlocking<Unit> {
        controller.current()
        workflow.refreshLibrary()
        mockkObject(WidgetRefreshScheduler)
        every { WidgetRefreshScheduler.request(context) } answers {
            notified += runBlocking { repository.savedSelection()!!.selection }
            mockk()
        }
    }
    @After fun teardown() = runBlocking<Unit> {
        controller.close()
        dataScope.coroutineContext.job.cancelAndJoin()
        unmockkObject(WidgetRefreshScheduler)
        assertTrue(directory.deleteRecursively())
    }

    @Test fun each_appearance_change_commits_preferences_before_requesting_refresh() = runBlocking<Unit> {
        workflow.changeTheme("dark")
        workflow.changeLightColorTheme(workflow.choices.value.single { it.builtInSlug == "nature" }.id)
        workflow.changeDarkColorTheme(workflow.choices.value.single { it.builtInSlug == "oled" }.id)
        workflow.changeCardColorStyle("personalized")
        assertEquals(4, notified.size)
        assertEquals(DeviceThemeMode.DARK, notified[0].mode)
        assertEquals("df000000-0000-4000-8000-000000000002", notified[1].light.themeId)
        assertEquals("df000000-0000-4000-8000-000000000007", notified[2].dark.themeId)
        assertEquals(DeviceCardStyle.PERSONALIZED, notified[3].cardStyle)
        assertNull(workflow.themeActionError.value)
    }

    @Test fun failed_preference_write_does_not_schedule_misleading_refresh() = runBlocking<Unit> {
        val before = repository.savedSelection()
        failWrites.set(true)
        workflow.changeTheme("dark")
        assertTrue(workflow.themeActionError.value is IOException)
        assertEquals(before, repository.savedSelection())
        assertTrue(notified.isEmpty())
        failWrites.set(false)
        workflow.changeTheme("dark")
        assertEquals(1, notified.size)
        assertEquals(DeviceThemeMode.DARK, repository.savedSelection()!!.selection.mode)
    }
}
