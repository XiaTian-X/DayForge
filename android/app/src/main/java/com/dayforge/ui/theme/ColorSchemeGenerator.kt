package com.dayforge.ui.theme

import android.util.Log
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.dayforge.domain.model.GlobalColorTheme
import me.tatarka.google.material.scheme.Scheme

private const val TAG = "ColorSchemeGenerator"

/**
 * Generates Material3 ColorScheme from seed colors using tonal palette algorithm.
 */
object ColorSchemeGenerator {

    fun generateLightColorScheme(theme: GlobalColorTheme): ColorScheme {
        Log.d(TAG, "generateLightColorScheme: id=${theme.id}, hasCustomColors: ${theme.hasCustomColors()}")

        if (theme.id == "oled" && !theme.isCustom) {
            return generateOledColorScheme()
        }

        val argb = parseSeedColor(theme.seedColor)
        val scheme = Scheme.light(argb)

        return lightColorScheme(
            primary = theme.primary?.toColor() ?: Color(scheme.primary),
            onPrimary = theme.onPrimary?.toColor() ?: Color(scheme.onPrimary),
            primaryContainer = theme.primaryContainer?.toColor() ?: Color(scheme.primaryContainer),
            onPrimaryContainer = theme.onPrimaryContainer?.toColor() ?: Color(scheme.onPrimaryContainer),
            inversePrimary = theme.inversePrimary?.toColor() ?: Color(scheme.inversePrimary),

            secondary = theme.secondary?.toColor() ?: Color(scheme.secondary),
            onSecondary = theme.onSecondary?.toColor() ?: Color(scheme.onSecondary),
            secondaryContainer = theme.secondaryContainer?.toColor() ?: Color(scheme.secondaryContainer),
            onSecondaryContainer = theme.onSecondaryContainer?.toColor() ?: Color(scheme.onSecondaryContainer),

            tertiary = theme.tertiary?.toColor() ?: Color(scheme.tertiary),
            onTertiary = theme.onTertiary?.toColor() ?: Color(scheme.onTertiary),
            tertiaryContainer = theme.tertiaryContainer?.toColor() ?: Color(scheme.tertiaryContainer),
            onTertiaryContainer = theme.onTertiaryContainer?.toColor() ?: Color(scheme.onTertiaryContainer),

            error = theme.error?.toColor() ?: Color(scheme.error),
            onError = theme.onError?.toColor() ?: Color(scheme.onError),
            errorContainer = theme.errorContainer?.toColor() ?: Color(scheme.errorContainer),
            onErrorContainer = theme.onErrorContainer?.toColor() ?: Color(scheme.onErrorContainer),

            background = theme.background?.toColor() ?: Color(scheme.background),
            onBackground = theme.onBackground?.toColor() ?: Color(scheme.onBackground),

            surface = theme.surface?.toColor() ?: Color(scheme.surface),
            onSurface = theme.onSurface?.toColor() ?: Color(scheme.onSurface),
            surfaceVariant = theme.surfaceVariant?.toColor() ?: Color(scheme.surfaceVariant),
            onSurfaceVariant = theme.onSurfaceVariant?.toColor() ?: Color(scheme.onSurfaceVariant),

            outline = theme.outline?.toColor() ?: Color(scheme.outline),
            outlineVariant = theme.outlineVariant?.toColor() ?: Color(scheme.outlineVariant),

            inverseSurface = theme.inverseSurface?.toColor() ?: Color(scheme.inverseSurface),
            inverseOnSurface = theme.inverseOnSurface?.toColor() ?: Color(scheme.inverseOnSurface)
        )
    }

    fun generateDarkColorScheme(theme: GlobalColorTheme): ColorScheme {
        if (theme.id == "oled" && !theme.isCustom) {
            return generateOledColorScheme()
        }

        val argb = parseSeedColor(theme.seedColor)
        val scheme = Scheme.dark(argb)

        return darkColorScheme(
            primary = theme.primary?.toColor() ?: Color(scheme.primary),
            onPrimary = theme.onPrimary?.toColor() ?: Color(scheme.onPrimary),
            primaryContainer = theme.primaryContainer?.toColor() ?: Color(scheme.primaryContainer),
            onPrimaryContainer = theme.onPrimaryContainer?.toColor() ?: Color(scheme.onPrimaryContainer),
            inversePrimary = theme.inversePrimary?.toColor() ?: Color(scheme.inversePrimary),

            secondary = theme.secondary?.toColor() ?: Color(scheme.secondary),
            onSecondary = theme.onSecondary?.toColor() ?: Color(scheme.onSecondary),
            secondaryContainer = theme.secondaryContainer?.toColor() ?: Color(scheme.secondaryContainer),
            onSecondaryContainer = theme.onSecondaryContainer?.toColor() ?: Color(scheme.onSecondaryContainer),

            tertiary = theme.tertiary?.toColor() ?: Color(scheme.tertiary),
            onTertiary = theme.onTertiary?.toColor() ?: Color(scheme.onTertiary),
            tertiaryContainer = theme.tertiaryContainer?.toColor() ?: Color(scheme.tertiaryContainer),
            onTertiaryContainer = theme.onTertiaryContainer?.toColor() ?: Color(scheme.onTertiaryContainer),

            error = theme.error?.toColor() ?: Color(scheme.error),
            onError = theme.onError?.toColor() ?: Color(scheme.onError),
            errorContainer = theme.errorContainer?.toColor() ?: Color(scheme.errorContainer),
            onErrorContainer = theme.onErrorContainer?.toColor() ?: Color(scheme.onErrorContainer),

            background = theme.background?.toColor() ?: Color(scheme.background),
            onBackground = theme.onBackground?.toColor() ?: Color(scheme.onBackground),

            surface = theme.surface?.toColor() ?: Color(scheme.surface),
            onSurface = theme.onSurface?.toColor() ?: Color(scheme.onSurface),
            surfaceVariant = theme.surfaceVariant?.toColor() ?: Color(scheme.surfaceVariant),
            onSurfaceVariant = theme.onSurfaceVariant?.toColor() ?: Color(scheme.onSurfaceVariant),

            outline = theme.outline?.toColor() ?: Color(scheme.outline),
            outlineVariant = theme.outlineVariant?.toColor() ?: Color(scheme.outlineVariant),

            inverseSurface = theme.inverseSurface?.toColor() ?: Color(scheme.inverseSurface),
            inverseOnSurface = theme.inverseOnSurface?.toColor() ?: Color(scheme.inverseOnSurface)
        )
    }

    fun generateOledColorScheme(): ColorScheme {
        return darkColorScheme(
            primary = Color(0xFF00BFA5),
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF004D40),
            onPrimaryContainer = Color(0xFF80CBC4),
            inversePrimary = Color(0xFF00BFA5),

            secondary = Color(0xFF3D5AFE),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFF1A237E),
            onSecondaryContainer = Color(0xFFB388FF),

            tertiary = Color(0xFF2979FF),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFF0D47A1),
            onTertiaryContainer = Color(0xFF82B1FF),

            error = Color(0xFFCF6679),
            onError = Color.Black,
            errorContainer = Color(0xFF4D1F1F),
            onErrorContainer = Color(0xFFFFB4AB),

            background = Color(0xFF000000),
            onBackground = Color.White,

            surface = Color(0xFF000000),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF121212),
            onSurfaceVariant = Color(0xFFB0B0B0),

            outline = Color(0xFF404040),
            outlineVariant = Color(0xFF202020),

            inverseSurface = Color.White,
            inverseOnSurface = Color.Black
        )
    }

    private fun parseSeedColor(seedColorHex: String): Int {
        return try {
            android.graphics.Color.parseColor(seedColorHex)
        } catch (e: IllegalArgumentException) {
            android.graphics.Color.parseColor("#1976D2")
        }
    }

    private fun String.toColor(): Color {
        return try {
            Color(android.graphics.Color.parseColor(this))
        } catch (e: Exception) {
            Color.Black
        }
    }

}
