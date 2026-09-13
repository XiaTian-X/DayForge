package com.dayforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
