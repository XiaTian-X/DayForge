package com.dayforge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.dayforge.domain.model.DefaultGlobalColorThemes
import com.dayforge.domain.service.ThemeManager

@Composable
fun DayForgeTheme(
    themeMode: String? = null,
    lightColorThemeId: String = "ocean",
    darkColorThemeId: String = "dusk",
    themeManager: ThemeManager? = null,  // Optional: use ThemeManager if available, fallback to DefaultGlobalColorThemes
    content: @Composable () -> Unit
) {
    // Compute darkTheme from themeMode parameter or system state
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        null -> isSystemInDarkTheme()  // System default
        else -> isSystemInDarkTheme()  // Fallback for invalid values
    }

    // Get theme based on current mode (D-14)
    val themeId = if (darkTheme) darkColorThemeId else lightColorThemeId

    // Use ThemeManager if available, otherwise fallback to DefaultGlobalColorThemes for compatibility
    val globalColorTheme = if (themeManager != null) {
        themeManager.getById(themeId)
    } else {
        DefaultGlobalColorThemes.getById(themeId)
    }

    // Generate ColorScheme from theme (supports custom colors)
    val colorScheme = if (darkTheme) {
        ColorSchemeGenerator.generateDarkColorScheme(globalColorTheme)
    } else {
        ColorSchemeGenerator.generateLightColorScheme(globalColorTheme)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}