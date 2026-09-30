package com.dayforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.LoadedDeviceTheme
import com.dayforge.domain.appearance.ResolvedTheme
import com.dayforge.domain.model.CardColorStyle

internal val LocalDeviceCardStyle = staticCompositionLocalOf { CardColorStyle.DEFAULT }
internal val LocalResolvedTheme = staticCompositionLocalOf<ResolvedTheme?> { null }

/** A complete atomic snapshot, including card style. No file IO or generated palettes in composition. */
@Composable
internal fun DayForgeTheme(theme: LoadedDeviceTheme, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val resolved = remember(theme, systemDark) { theme.resolve(systemDark) }
    val colors = remember(resolved) { resolved.toComposeColors() }
    val cardStyle = when (theme.saved.selection.cardStyle) {
        DeviceCardStyle.FOLLOW_THEME -> CardColorStyle.FOLLOW_THEME
        DeviceCardStyle.PERSONALIZED -> CardColorStyle.PERSONALIZED
    }
    CompositionLocalProvider(LocalDeviceCardStyle provides cardStyle, LocalResolvedTheme provides resolved) {
        MaterialTheme(colorScheme = colors, typography = Typography, content = content)
    }
}
