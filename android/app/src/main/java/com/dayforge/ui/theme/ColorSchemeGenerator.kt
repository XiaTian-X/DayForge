package com.dayforge.ui.theme

import android.util.Log
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.core.graphics.toColorInt
import androidx.compose.ui.graphics.Color
import com.dayforge.domain.model.GlobalColorTheme
import com.dayforge.domain.service.SeedColorPalette
import com.dayforge.domain.service.SeedColorRole

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
        val scheme = SeedColorPalette(argb)

        return lightColorScheme(
            primary = theme.primary?.toColor() ?: Color(scheme[SeedColorRole.PRIMARY]),
            onPrimary = theme.onPrimary?.toColor() ?: Color(scheme[SeedColorRole.ON_PRIMARY]),
            primaryContainer = theme.primaryContainer?.toColor() ?: Color(scheme[SeedColorRole.PRIMARY_CONTAINER]),
            onPrimaryContainer = theme.onPrimaryContainer?.toColor() ?: Color(scheme[SeedColorRole.ON_PRIMARY_CONTAINER]),
            inversePrimary = theme.inversePrimary?.toColor() ?: Color(scheme[SeedColorRole.INVERSE_PRIMARY]),

            secondary = theme.secondary?.toColor() ?: Color(scheme[SeedColorRole.SECONDARY]),
            onSecondary = theme.onSecondary?.toColor() ?: Color(scheme[SeedColorRole.ON_SECONDARY]),
            secondaryContainer = theme.secondaryContainer?.toColor() ?: Color(scheme[SeedColorRole.SECONDARY_CONTAINER]),
            onSecondaryContainer = theme.onSecondaryContainer?.toColor() ?: Color(scheme[SeedColorRole.ON_SECONDARY_CONTAINER]),

            tertiary = theme.tertiary?.toColor() ?: Color(scheme[SeedColorRole.TERTIARY]),
            onTertiary = theme.onTertiary?.toColor() ?: Color(scheme[SeedColorRole.ON_TERTIARY]),
            tertiaryContainer = theme.tertiaryContainer?.toColor() ?: Color(scheme[SeedColorRole.TERTIARY_CONTAINER]),
            onTertiaryContainer = theme.onTertiaryContainer?.toColor() ?: Color(scheme[SeedColorRole.ON_TERTIARY_CONTAINER]),

            error = theme.error?.toColor() ?: Color(scheme[SeedColorRole.ERROR]),
            onError = theme.onError?.toColor() ?: Color(scheme[SeedColorRole.ON_ERROR]),
            errorContainer = theme.errorContainer?.toColor() ?: Color(scheme[SeedColorRole.ERROR_CONTAINER]),
            onErrorContainer = theme.onErrorContainer?.toColor() ?: Color(scheme[SeedColorRole.ON_ERROR_CONTAINER]),

            background = theme.background?.toColor() ?: Color(scheme[SeedColorRole.BACKGROUND]),
            onBackground = theme.onBackground?.toColor() ?: Color(scheme[SeedColorRole.ON_BACKGROUND]),

            surface = theme.surface?.toColor() ?: Color(scheme[SeedColorRole.SURFACE]),
            onSurface = theme.onSurface?.toColor() ?: Color(scheme[SeedColorRole.ON_SURFACE]),
            surfaceVariant = theme.surfaceVariant?.toColor() ?: Color(scheme[SeedColorRole.SURFACE_VARIANT]),
            onSurfaceVariant = theme.onSurfaceVariant?.toColor() ?: Color(scheme[SeedColorRole.ON_SURFACE_VARIANT]),

            outline = theme.outline?.toColor() ?: Color(scheme[SeedColorRole.OUTLINE]),
            outlineVariant = theme.outlineVariant?.toColor() ?: Color(scheme[SeedColorRole.OUTLINE_VARIANT]),

            inverseSurface = theme.inverseSurface?.toColor() ?: Color(scheme[SeedColorRole.INVERSE_SURFACE]),
            inverseOnSurface = theme.inverseOnSurface?.toColor() ?: Color(scheme[SeedColorRole.INVERSE_ON_SURFACE])
        )
    }

    fun generateDarkColorScheme(theme: GlobalColorTheme): ColorScheme {
        if (theme.id == "oled" && !theme.isCustom) {
            return generateOledColorScheme()
        }

        val argb = parseSeedColor(theme.seedColor)
        val scheme = SeedColorPalette(argb, dark = true)

        return darkColorScheme(
            primary = theme.primary?.toColor() ?: Color(scheme[SeedColorRole.PRIMARY]),
            onPrimary = theme.onPrimary?.toColor() ?: Color(scheme[SeedColorRole.ON_PRIMARY]),
            primaryContainer = theme.primaryContainer?.toColor() ?: Color(scheme[SeedColorRole.PRIMARY_CONTAINER]),
            onPrimaryContainer = theme.onPrimaryContainer?.toColor() ?: Color(scheme[SeedColorRole.ON_PRIMARY_CONTAINER]),
            inversePrimary = theme.inversePrimary?.toColor() ?: Color(scheme[SeedColorRole.INVERSE_PRIMARY]),

            secondary = theme.secondary?.toColor() ?: Color(scheme[SeedColorRole.SECONDARY]),
            onSecondary = theme.onSecondary?.toColor() ?: Color(scheme[SeedColorRole.ON_SECONDARY]),
            secondaryContainer = theme.secondaryContainer?.toColor() ?: Color(scheme[SeedColorRole.SECONDARY_CONTAINER]),
            onSecondaryContainer = theme.onSecondaryContainer?.toColor() ?: Color(scheme[SeedColorRole.ON_SECONDARY_CONTAINER]),

            tertiary = theme.tertiary?.toColor() ?: Color(scheme[SeedColorRole.TERTIARY]),
            onTertiary = theme.onTertiary?.toColor() ?: Color(scheme[SeedColorRole.ON_TERTIARY]),
            tertiaryContainer = theme.tertiaryContainer?.toColor() ?: Color(scheme[SeedColorRole.TERTIARY_CONTAINER]),
            onTertiaryContainer = theme.onTertiaryContainer?.toColor() ?: Color(scheme[SeedColorRole.ON_TERTIARY_CONTAINER]),

            error = theme.error?.toColor() ?: Color(scheme[SeedColorRole.ERROR]),
            onError = theme.onError?.toColor() ?: Color(scheme[SeedColorRole.ON_ERROR]),
            errorContainer = theme.errorContainer?.toColor() ?: Color(scheme[SeedColorRole.ERROR_CONTAINER]),
            onErrorContainer = theme.onErrorContainer?.toColor() ?: Color(scheme[SeedColorRole.ON_ERROR_CONTAINER]),

            background = theme.background?.toColor() ?: Color(scheme[SeedColorRole.BACKGROUND]),
            onBackground = theme.onBackground?.toColor() ?: Color(scheme[SeedColorRole.ON_BACKGROUND]),

            surface = theme.surface?.toColor() ?: Color(scheme[SeedColorRole.SURFACE]),
            onSurface = theme.onSurface?.toColor() ?: Color(scheme[SeedColorRole.ON_SURFACE]),
            surfaceVariant = theme.surfaceVariant?.toColor() ?: Color(scheme[SeedColorRole.SURFACE_VARIANT]),
            onSurfaceVariant = theme.onSurfaceVariant?.toColor() ?: Color(scheme[SeedColorRole.ON_SURFACE_VARIANT]),

            outline = theme.outline?.toColor() ?: Color(scheme[SeedColorRole.OUTLINE]),
            outlineVariant = theme.outlineVariant?.toColor() ?: Color(scheme[SeedColorRole.OUTLINE_VARIANT]),

            inverseSurface = theme.inverseSurface?.toColor() ?: Color(scheme[SeedColorRole.INVERSE_SURFACE]),
            inverseOnSurface = theme.inverseOnSurface?.toColor() ?: Color(scheme[SeedColorRole.INVERSE_ON_SURFACE])
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
            seedColorHex.toColorInt()
        } catch (e: IllegalArgumentException) {
            "#1976D2".toColorInt()
        }
    }

    private fun String.toColor(): Color {
        return try {
            Color(this.toColorInt())
        } catch (e: Exception) {
            Color.Black
        }
    }

}
