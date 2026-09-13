package com.dayforge.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** MainActivity owns its window; the reusable theme must not mutate an arbitrary Context. */
@Composable
internal fun DayForgeWindow(
    activity: ComponentActivity,
    navigationBarColor: Color,
    bottomBar: @Composable () -> Unit = {},
    safeDrawingInsets: WindowInsets = WindowInsets.safeDrawing,
    imeInsets: WindowInsets = WindowInsets.ime,
    statusBarInsets: WindowInsets = WindowInsets.statusBars,
    content: @Composable (Modifier) -> Unit
) {
    // Resolve translucent custom colors against a deterministic opaque window background.
    val background = MaterialTheme.colorScheme.background.compositeOver(Color.Black)
    val statusBackground = MaterialTheme.colorScheme.primary.compositeOver(background)
    val navigationBackground = navigationBarColor.compositeOver(background)
    SideEffect {
        activity.applyDayForgeSystemBars(statusBackground, navigationBackground)
    }

    Box(Modifier.fillMaxSize().background(background)) {
        Scaffold(
            modifier = Modifier.windowInsetsPadding(imeInsets),
            containerColor = background,
            contentWindowInsets = safeDrawingInsets,
            bottomBar = bottomBar
        ) { padding ->
            content(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
        }
        // Paint behind the transparent system bar, without shifting or consuming content insets.
        StatusBarProtection(statusBackground, statusBarInsets)
    }
}

@Composable
internal fun StatusBarProtection(color: Color, insets: WindowInsets, modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().windowInsetsTopHeight(insets).background(color))
}

/** Material3's current default includes system bars, but not side display cutouts. */
@Composable
internal fun DayForgeNavigationBar(
    modifier: Modifier = Modifier,
    safeDrawingInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable RowScope.() -> Unit
) {
    NavigationBar(
        modifier = modifier,
        windowInsets = safeDrawingInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        content = content
    )
}

internal fun ComponentActivity.applyDayForgeSystemBars(
    statusBackground: Color,
    navigationBackground: Color
) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) {
            !useDarkSystemBarIcons(statusBackground)
        },
        // Auto retains platform three-button contrast protection on API 29+; older versions
        // use the actual navigation background instead of a scrim tied to the system theme.
        navigationBarStyle = SystemBarStyle.auto(navigationBackground.toArgb(), navigationBackground.toArgb()) {
            !useDarkSystemBarIcons(navigationBackground)
        }
    )
}

/** Choose whichever monochrome icon color has greater WCAG contrast with the opaque background. */
internal fun useDarkSystemBarIcons(background: Color): Boolean {
    val luminance = background.luminance()
    return (luminance + 0.05f) / 0.05f >= 1.05f / (luminance + 0.05f)
}
