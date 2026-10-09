package com.dayforge.widget.base

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.appearance.*
import com.dayforge.domain.appearance.*
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.ui.theme.LocalDeviceCardStyle
import com.dayforge.ui.theme.LocalResolvedTheme
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetActivityThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = Files.createTempDirectory(context.filesDir.toPath(), "widget-activity-theme-").toFile()
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = PreferenceDataStoreFactory.create(scope = dataScope) { File(directory, "prefs.preferences_pb") }
    private val controller = DeviceThemeController(DeviceThemeRepository(preferences, ThemeFileRepository(directory),
        BuiltInThemes(context.assets)), CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private suspend fun mode(mode: DeviceThemeMode, style: DeviceCardStyle = DeviceCardStyle.FOLLOW_THEME): LoadedDeviceTheme {
        val current = withTimeout(5000) { controller.current() }.saved
        val result = controller.select(current.revision, current.selection.copy(mode = mode, cardStyle = style,
            dark = ThemeVersionRef(BuiltInTheme.OLED.themeId, 1)))
        withTimeout(5000) { controller.state.first { it is DeviceThemeLoadState.Ready && it.theme.saved == result.saved } }
        return result
    }
    @After fun cleanup() = runBlocking<Unit> {
        controller.close(); dataScope.coroutineContext.job.cancelAndJoin()
        assertTrue(directory.deleteRecursively())
    }

    @Test fun coldControllerSuppliesExactSavedLightOledAndCardStyleWithoutChangingTypography() = runBlocking<Unit> {
        val typography = androidx.compose.material3.Typography(titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 21.sp))
        var observed: Triple<Int, Int, CardColorStyle>? = null
        compose.setContent {
            MaterialTheme(typography = typography) {
                val state by controller.state.collectAsState()
                WidgetActivityThemeSnapshot(compose.activity, state, false, {}, {}, {}) {
                    val colors = MaterialTheme.colorScheme
                    val style = LocalDeviceCardStyle.current
                    val actualType = MaterialTheme.typography
                    val resolved = LocalResolvedTheme.current
                    SideEffect {
                        assertEquals(typography, actualType); assertNotNull(resolved)
                        observed = Triple(colors.background.toArgb(), colors.primary.toArgb(), style)
                    }
                    Text("actual-content")
                }
            }
        }
        mode(DeviceThemeMode.LIGHT)
        compose.waitUntil(5000) { observed == Triple(0xfffdfcff.toInt(), 0xff005faf.toInt(), CardColorStyle.FOLLOW_THEME) }
        mode(DeviceThemeMode.DARK, DeviceCardStyle.PERSONALIZED)
        compose.waitUntil(5000) { observed == Triple(0xff000000.toInt(), 0xff00bfa5.toInt(), CardColorStyle.PERSONALIZED) }
        compose.onNodeWithText("actual-content").assertIsDisplayed()
    }

    @Test fun systemModeUsesCurrentConfigurationAndDoesNotMutateTheTranslucentOwnersWindow() = runBlocking<Unit> {
        mode(DeviceThemeMode.SYSTEM)
        val configuration = mutableStateOf(Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        })
        var observed: Int? = null
        val window = compose.activity.window
        val originalFlags = window.attributes.flags
        val originalDecorBackground = window.decorView.background
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                val state by controller.state.collectAsState()
                WidgetActivityThemeSnapshot(compose.activity, state, true, {}, {}, {}) {
                    val background = MaterialTheme.colorScheme.background.toArgb()
                    SideEffect { observed = background }
                    Text("translucent-content")
                }
            }
        }
        compose.waitUntil(5000) { observed == 0xfffdfcff.toInt() }
        compose.runOnIdle { configuration.value = Configuration(configuration.value).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        } }
        compose.waitUntil(5000) { observed == 0xff000000.toInt() }
        compose.runOnIdle {
            assertEquals(originalFlags, window.attributes.flags)
            assertSame(originalDecorBackground, window.decorView.background)
        }
    }

    @Test fun loadingAndFailedDialogHideNormalContentAndOfferOnlyExplicitSafeRecovery() {
        val state = mutableStateOf<DeviceThemeLoadState>(DeviceThemeLoadState.Loading)
        var retries = 0; var manages = 0; var closes = 0; var business = 0
        val window = compose.activity.window
        val flags = window.attributes.flags; val background = window.decorView.background
        compose.setContent {
            WidgetActivityThemeSnapshot(compose.activity, state.value, true, { retries++ }, { manages++ }, { closes++ }) {
                SideEffect { business++ }; Text("business-must-not-render")
            }
        }
        compose.onNodeWithText("business-must-not-render").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, closes); state.value = DeviceThemeLoadState.Failed(IOException("private/path/secret")) }
        compose.onNodeWithText("private/path/secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.action_retry)).performClick()
        compose.onNodeWithText(context.getString(R.string.theme_recovery_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle {
            assertEquals(1, retries); assertEquals(1, manages); assertEquals(2, closes); assertEquals(0, business)
            assertEquals(flags, window.attributes.flags); assertSame(background, window.decorView.background)
        }
    }
}
