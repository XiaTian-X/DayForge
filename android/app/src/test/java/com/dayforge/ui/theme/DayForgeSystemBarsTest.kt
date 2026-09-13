package com.dayforge.ui.theme

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 29, 35], application = Application::class)
class DayForgeSystemBarsTest {
    @Test
    fun `icons follow each bar background independently on repeated theme changes`() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            // App theme can disagree with system theme; status primary can also disagree with surface.
            for (systemNight in listOf(Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO)) {
                activity.resources.configuration.uiMode = Configuration.UI_MODE_TYPE_NORMAL or systemNight
                activity.applyDayForgeSystemBars(Color.Black, Color.White)
                assertFalse(insets.isAppearanceLightStatusBars)
                assertTrue(insets.isAppearanceLightNavigationBars)
                activity.applyDayForgeSystemBars(Color.White, Color.Black)
                assertTrue(insets.isAppearanceLightStatusBars)
                assertFalse(insets.isAppearanceLightNavigationBars)
            }
        }
    }

    @Suppress("DEPRECATION") // Read old-platform outputs only; production never sets legacy bar colors.
    @Test
    fun `status is transparent and navigation retains platform contrast protection`() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val navigation = Color(0xFF203040)
            activity.applyDayForgeSystemBars(Color.Red, navigation)
            assertEquals(android.graphics.Color.TRANSPARENT, activity.window.statusBarColor)
            if (Build.VERSION.SDK_INT >= 29) {
                assertTrue(activity.window.isNavigationBarContrastEnforced)
                assertFalse(activity.window.isStatusBarContrastEnforced)
                assertEquals(android.graphics.Color.TRANSPARENT, activity.window.navigationBarColor)
            } else {
                assertEquals(navigation.toArgb(), activity.window.navigationBarColor)
            }
        }
    }

    @Test
    fun `black or white icons use the greater contrast instead of theme mode`() {
        assertFalse(useDarkSystemBarIcons(Color.Black))
        assertTrue(useDarkSystemBarIcons(Color.White))
        assertFalse(useDarkSystemBarIcons(Color(0xFF0061A4))) // Light Ocean's dark primary.
        assertTrue(useDarkSystemBarIcons(Color(0xFFA1C9FF))) // Dark Ocean's light primary.
        assertFalse(useDarkSystemBarIcons(Color(0xFF747474)))
        assertTrue(useDarkSystemBarIcons(Color(0xFF767676)))
        assertTrue(useDarkSystemBarIcons(Color.Yellow))
        assertFalse(useDarkSystemBarIcons(Color.Blue))
    }
}
