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

    data class Fixture(val seed: String, val dark: Boolean, val colors: List<Int>)

    fun load(): List<Fixture> = requireNotNull(javaClass.getResourceAsStream("/theme/seed-palette.csv"))
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
