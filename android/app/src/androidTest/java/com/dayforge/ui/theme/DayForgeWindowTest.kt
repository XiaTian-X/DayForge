package com.dayforge.ui.theme

import android.content.ContextWrapper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class DayForgeWindowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nested_title_and_content_consume_status_and_navigation_insets_exactly_once() {
        fixture()
        val root = bounds("root")
        assertDp(root.top + 24.dp, bounds("toolbar").top)
        assertDp(64.dp, bounds("toolbar").height)
        assertDp(root.top + 88.dp, bounds("content").top)
        assertDp(root.bottom - 24.dp, bounds("content").bottom)
    }

    @Test
    fun bottom_navigation_owns_its_inset_and_nested_content_does_not_reserve_it_again() {
        fixture(bottomBar = true)
        assertDp(104.dp, bounds("navigation").height)
        assertDp(bounds("root").bottom, bounds("navigation").bottom)
        assertDp(bounds("navigation").top, bounds("content").bottom)
    }

    @Test
    fun IME_replaces_bottom_inset_then_restores_layout_on_dismissal() {
        val keyboard = mutableStateOf(0.dp)
        fixture(keyboard = { keyboard.value })
        val closedBottom = bounds("content").bottom
        compose.runOnIdle { keyboard.value = 300.dp }
        assertDp(bounds("root").bottom - 300.dp, bounds("content").bottom)
        compose.runOnIdle { keyboard.value = 0.dp }
        assertDp(closedBottom, bounds("content").bottom)
    }

    @Test
    fun bottom_navigation_stays_above_IME_without_a_second_navigation_gap() {
        val keyboard = mutableStateOf(0.dp)
        fixture(bottomBar = true, keyboard = { keyboard.value })
        compose.runOnIdle { keyboard.value = 300.dp }
        assertDp(80.dp, bounds("navigation").height)
        assertDp(bounds("root").bottom - 300.dp, bounds("navigation").bottom)
        assertDp(bounds("navigation").top, bounds("content").bottom)
        compose.runOnIdle { keyboard.value = 0.dp }
        assertDp(104.dp, bounds("navigation").height)
    }

    @Test
    fun side_cutouts_and_caption_bar_remain_physical_in_RTL_and_update_after_rotation() {
        val landscape = mutableStateOf(false)
        fixture(
            safeInsets = {
                if (landscape.value) WindowInsets(left = 32.dp, right = 16.dp, bottom = 24.dp)
                else WindowInsets(top = 40.dp, bottom = 24.dp)
            },
            direction = LayoutDirection.Rtl
        )
        assertDp(bounds("root").top + 40.dp, bounds("toolbar").top)
        compose.runOnIdle { landscape.value = true }
        assertDp(bounds("root").left + 32.dp, bounds("content").left)
        assertDp(bounds("root").right - 16.dp, bounds("content").right)
        assertDp(bounds("root").top, bounds("toolbar").top)
        assertDp(64.dp, bounds("toolbar").height)
    }

    @Test
    fun bottom_navigation_buttons_avoid_side_cutouts_in_RTL() {
        fixture(
            bottomBar = true,
            safeInsets = { WindowInsets(left = 32.dp, right = 16.dp, bottom = 24.dp) },
            direction = LayoutDirection.Rtl
        )
        assertDp(bounds("root").left + 32.dp, bounds("navItem").left)
        assertDp(bounds("root").right - 16.dp, bounds("navItem").right)
        assertDp(bounds("root").bottom - 24.dp, bounds("navItem").bottom)
    }

    @Test
    fun status_protection_follows_visible_status_inset_without_reserving_content_space() {
        val top = mutableStateOf(24.dp)
        var measuredHeight = -1
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("root")) {
                Box(Modifier.fillMaxSize().testTag("content"))
                StatusBarProtection(Color.Red, WindowInsets(top = top.value),
                    Modifier.testTag("protection").onSizeChanged { measuredHeight = it.height })
            }
        }
        assertDp(24.dp, bounds("protection").height)
        assertEquals(bounds("root"), bounds("content"))
        compose.runOnIdle { top.value = 0.dp }
        // A zero-size semantics node has unspecified bounds; assert its actual measured size.
        compose.runOnIdle { assertEquals(0, measuredHeight) }
        assertEquals(bounds("root"), bounds("content"))
    }

    @Test
    fun theme_works_with_a_wrapped_non_activity_view_context() {
        val previewView = View(ContextWrapper(compose.activity.applicationContext))
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("root")) {
                CompositionLocalProvider(LocalView provides previewView) {
                    DayForgeTheme(themeMode = "dark", darkColorThemeId = "oled") {
                        Box(Modifier.fillMaxSize().testTag("content"))
                    }
                }
            }
        }
        assertTrue(bounds("content").width > 0.dp)
        assertEquals(bounds("root"), bounds("content"))
    }

    @Test
    fun window_reacts_to_custom_color_changes_and_composites_transparent_roles() {
        val background = mutableStateOf(Color.White)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                background = background.value,
                primary = Color.Transparent
            )) {
                DayForgeWindow(compose.activity, navigationBarColor = Color.Transparent) { modifier ->
                    Box(modifier)
                }
            }
        }
        val controller = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
        compose.runOnIdle {
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
            background.value = Color.Black
        }
        compose.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        }
    }

    private fun fixture(
        bottomBar: Boolean = false,
        keyboard: () -> Dp = { 0.dp },
        safeInsets: () -> WindowInsets = { WindowInsets(top = 24.dp, bottom = 24.dp) },
        direction: LayoutDirection = LayoutDirection.Ltr
    ) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val bars = safeInsets()
                    val ime = WindowInsets(bottom = keyboard())
                    Box(Modifier.fillMaxSize().testTag("root")) {
                        DayForgeWindow(
                            activity = compose.activity,
                            navigationBarColor = MaterialTheme.colorScheme.surfaceContainer,
                            safeDrawingInsets = bars.union(ime),
                            imeInsets = ime,
                            statusBarInsets = WindowInsets(top = 24.dp),
                            bottomBar = {
                                if (bottomBar) DayForgeNavigationBar(
                                    modifier = Modifier.testTag("navigation"),
                                    safeDrawingInsets = bars
                                ) {
                                    NavigationBarItem(
                                        selected = true,
                                        onClick = {},
                                        icon = { Text("Home") },
                                        modifier = Modifier.testTag("navItem")
                                    )
                                }
                            }
                        ) { modifier ->
                            Scaffold(
                                modifier = modifier,
                                contentWindowInsets = bars.union(ime),
                                topBar = {
                                    TopAppBar(
                                        title = { Text("Title") },
                                        modifier = Modifier.testTag("toolbar"),
                                        windowInsets = bars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                                    )
                                }
                            ) { padding ->
                                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                                    Box(Modifier.fillMaxSize().testTag("content"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertDp(expected: Dp, actual: Dp) = assertEquals(expected.value, actual.value, 0.5f)
}
