package com.dayforge.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.api.NetworkMonitor
import com.dayforge.data.appearance.*
import com.dayforge.data.local.DataStoreProvider
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.SyncProgress
import com.dayforge.data.repository.HabitRepository
import com.dayforge.di.DeviceThemeControllerEntryPoint
import com.dayforge.domain.appearance.*
import com.dayforge.domain.service.*
import com.dayforge.ui.theme.DayForgeTheme
import com.dayforge.widget.IsolatedWidgetRefreshRule
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest

/** Real settings screen -> ViewModel -> injected workflow/controller -> production DataStore/files.
 * Sync observation and refresh dispatch are boundaries; no real network or background Room work.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsThemeIntegrationTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 2) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 3) val compose = createAndroidComposeRule<ComponentActivity>()
    @Inject lateinit var controller: DeviceThemeController
    @Inject lateinit var appearance: SettingsAppearanceWorkflow
    @Inject lateinit var preferences: DataStore<Preferences>
    private val app = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = app.targetContext
    private val models = ViewModelStore()
    private lateinit var model: SettingsViewModel
    private lateinit var original: DeviceThemeSelection
    private var imported: ThemeVersionRef? = null
    private var source: File? = null
    private var renderedBackground: Int? = null

    private fun text(id: Int) = context.getString(id)
    private fun click(id: Int) = compose.onNodeWithText(text(id)).performClick()
    private fun settingsClick(id: Int) {
        val entry = hasText(text(id)) and hasClickAction()
        compose.onNodeWithTag("settings-list").performScrollToNode(entry)
        compose.onNode(entry).performClick()
    }
    private fun awaitChoice(check: (DeviceThemeSelection) -> Boolean) {
        compose.waitUntil(5000) {
            (controller.state.value as? DeviceThemeLoadState.Ready)?.theme?.saved?.selection?.let(check) == true
        }
    }

    @Before fun start() = runBlocking<Unit> {
        check(context.packageName == "com.dayforge.testbed")
        hilt.inject()
        assertSame(controller, DeviceThemeControllerEntryPoint.from(context).themeController())
        assertSame(preferences, DataStoreProvider.get(context))
        val saved = withTimeout(5000) { controller.current() }.saved
        original = saved.selection
        val initial = controller.select(saved.revision, DeviceThemeSelection(
            ThemeVersionRef(BuiltInTheme.OCEAN.themeId, 1), ThemeVersionRef(BuiltInTheme.DUSK.themeId, 1),
            DeviceThemeMode.LIGHT, DeviceCardStyle.FOLLOW_THEME))
        withTimeout(5000) { controller.state.first { it is DeviceThemeLoadState.Ready && it.theme.saved == initial.saved } }
        appearance.refreshLibrary()
        val sync = mockk<SyncManager>()
        every { sync.syncProgress } returns MutableStateFlow(SyncProgress.Idle)
        every { sync.getLastSyncTime() } returns flowOf(null)
        every { sync.canEditStructure() } returns flowOf(true)
        every { sync.isPrimaryEditor() } returns flowOf(false)
        every { sync.observeRejectedChanges() } returns flowOf(emptyList())
        every { sync.observeConflicts() } returns flowOf(emptyList())
        every { sync.observeRejectedTimerCommands() } returns flowOf(emptyList())
        val network = mockk<NetworkMonitor>()
        every { network.state } returns MutableStateFlow(NetworkMonitor.Snapshot())
        val db = storage.database
        val config = SettingsConfigWorkflow(context, db.habitDao(), db.metricDao(), db.timeLogDao(),
            db.completionDao(), db.metricLogDao(), ConfigExportService(db.habitDao(), db.metricDao(), db.habitMetricLinkDao()),
            ConfigImportService(db.habitDao(), db.metricDao(), db.habitMetricLinkDao(), db))
        withContext(Dispatchers.Main) {
            model = SettingsViewModel(context, sync, TokenManager(preferences), PreferencesManager(preferences),
                network, HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db),
                db.habitDao(), db.timeLogDao(), config, appearance, AccountSessionCoordinator())
            models.put("settings", model)
        }
        compose.setContent {
            val status by controller.state.collectAsState()
            val theme = (status as? DeviceThemeLoadState.Ready)?.theme
            if (theme != null) DayForgeTheme(theme) {
                val background = MaterialTheme.colorScheme.background.toArgb()
                SideEffect { renderedBackground = background }
                SettingsScreen(model, onNavigateBack = {})
            }
        }
    }

    @After fun finish() = runBlocking<Unit> {
        if (::model.isInitialized) withContext(Dispatchers.Main) { models.clear() }
        if (::original.isInitialized) {
            val current = controller.current().saved
            controller.select(current.revision, original)
        }
        imported?.let { ref ->
            val catalog = controller.catalog()!!
            if (catalog.slots.any { it.ref == ref }) controller.delete(ref, catalog.revision)
        }
        if (::controller.isInitialized) controller.close()
        source?.let { assertTrue(it.delete()) }
    }

    @Test fun settingsTouchesPersistCompleteChoicesAndShareTheHiltWidgetSource() {
        settingsClick(R.string.settings_theme)
        click(R.string.settings_theme_dark)
        awaitChoice { it.mode == DeviceThemeMode.DARK }
        settingsClick(R.string.settings_dark_theme)
        click(R.string.theme_name_oled)
        awaitChoice { it.dark.themeId == BuiltInTheme.OLED.themeId }
        settingsClick(R.string.card_color_style_title)
        click(R.string.card_color_style_personalized)
        awaitChoice { it.cardStyle == DeviceCardStyle.PERSONALIZED }
        compose.runOnIdle { assertEquals(0xff000000.toInt(), renderedBackground) }
        runBlocking {
            val cold = DeviceThemeRepository(preferences, ThemeFileRepository(context.filesDir), BuiltInThemes(context.assets)).load()
            assertEquals(controller.current().saved, cold.saved)
            val colors = com.dayforge.widget.base.WidgetColorResolver(context,
                DeviceThemeControllerEntryPoint.from(context).themeController()).resolveWidgetColors("#FF123456")
            assertEquals(0xff123456.toInt(), colors.backgroundColorArgb)
        }
        assertEquals(3, widgets.requestCount)
    }

    @Test fun settingsPreviewConfirmationAndDeleteCancellationReachDurableCatalog() {
        val id = UUID.randomUUID().toString()
        val bytes = app.context.assets.open("next/theme.json").use { it.readBytes() }
            .toString(Charsets.UTF_8).replace("50000000-0000-0000-0000-000000000001", id)
        source = File(context.cacheDir, "settings-theme-$id.json").also { it.writeText(bytes) }
        compose.runOnIdle { model.importTheme(Uri.fromFile(checkNotNull(source))) }
        compose.waitUntil(5000) { model.themePreview.value != null }
        imported = ThemeVersionRef(id, 1)
        val target = File(context.filesDir, "theme-definitions-v1/$id-1.json")
        assertFalse(target.exists())
        click(R.string.action_cancel)
        compose.waitUntil(5000) { model.themePreview.value == null }
        assertFalse(target.exists())
        compose.runOnIdle { model.importTheme(Uri.fromFile(checkNotNull(source))) }
        compose.waitUntil(5000) { model.themePreview.value != null }
        val name = model.themePreview.value!!.definition.name
        click(R.string.action_confirm)
        compose.waitUntil(5000) { model.themeImportResult.value?.isSuccess == true }
        assertArrayEquals(bytes.toByteArray(), target.readBytes())
        click(R.string.common_ok)
        compose.waitUntil(5000) { model.themeChoices.value.any { it.ref == imported } }
        settingsClick(R.string.settings_light_theme)
        // Find the custom row's actual action, avoiding similarly named preset/export controls.
        compose.onNodeWithTag("theme-selection-list").performScrollToNode(hasText(name))
        val deletion = "theme-delete-$id:1"
        compose.onNodeWithTag(deletion).performClick()
        click(R.string.action_cancel)
        assertTrue(target.exists())
        compose.onNodeWithTag(deletion).performClick()
        click(R.string.action_delete)
        compose.waitUntil(5000) { model.themeDeleteResult.value?.isSuccess == true }
        assertFalse(target.exists())
        runBlocking { assertFalse(controller.catalog()!!.slots.any { it.ref == imported }) }
        assertEquals(0, widgets.requestCount)
    }
}
