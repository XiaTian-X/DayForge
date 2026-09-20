package com.dayforge.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb

/** Independent pre-migration outputs, not recomputed by the implementation under test. */
internal object SeedPaletteFixtures {
    val roles = listOf("primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
        "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
        "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
        "error", "onError", "errorContainer", "onErrorContainer", "background", "onBackground",
        "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "outline", "outlineVariant",
        "inverseSurface", "inverseOnSurface")

    val oledColors = listOf(
        0xFF00BFA5, 0xFF000000, 0xFF004D40, 0xFF80CBC4, 0xFF00BFA5,
        0xFF3D5AFE, 0xFFFFFFFF, 0xFF1A237E, 0xFFB388FF,
        0xFF2979FF, 0xFFFFFFFF, 0xFF0D47A1, 0xFF82B1FF,
        0xFFCF6679, 0xFF000000, 0xFF4D1F1F, 0xFFFFB4AB, 0xFF000000, 0xFFFFFFFF,
        0xFF000000, 0xFFFFFFFF, 0xFF121212, 0xFFB0B0B0, 0xFF404040, 0xFF202020,
        0xFFFFFFFF, 0xFF000000
    ).map { it.toInt() }

    fun ocean(dark: Boolean): List<Int> = load().single { it.seed == "FF1976D2" && it.dark == dark }.colors

    data class Fixture(val seed: String, val dark: Boolean, val colors: List<Int>)

    fun load(): List<Fixture> = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("theme/seed-palette.csv")
        .bufferedReader().use { reader ->
            reader.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val fields = line.split(',')
                require(fields.size == 29 && fields[1] in listOf("light", "dark"))
                Fixture(fields[0], fields[1] == "dark", fields.drop(2).map { it.toLong(16).toInt() })
            }
        }

    fun colors(scheme: ColorScheme): List<Int> = with(scheme) {
        listOf(primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary,
            secondary, onSecondary, secondaryContainer, onSecondaryContainer,
            tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
            error, onError, errorContainer, onErrorContainer, background, onBackground,
            surface, onSurface, surfaceVariant, onSurfaceVariant, outline, outlineVariant,
            inverseSurface, inverseOnSurface).map { it.toArgb() }
    }
}
