package com.dayforge.domain.appearance

import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.domain.model.ThemePalette
import com.dayforge.domain.model.isAccentColor
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Immutable sRGB values from a saved palette. No generator, storage or UI-library defaults. */
internal class ResolvedTheme private constructor(
    val themeId: String,
    val revision: Int,
    val dark: Boolean,
    val material: Map<String, Int>,
    val status: Map<String, Int>,
    val chart: Map<String, Int>
) {
    companion object {
        fun from(theme: ThemeDefinition, dark: Boolean): ResolvedTheme {
            val selected = if (dark) theme.dark else theme.light
            // Revalidate copied maps: a caller may have mutated the original Map since DTO creation.
            val frozen = ThemePalette(selected.material.toMap(), selected.status.toMap(), selected.chart.toMap())
            fun colors(values: Map<String, String>) = Collections.unmodifiableMap(values.mapValues { parseAppearanceColor(it.value) })
            return ResolvedTheme(theme.themeId, theme.revision, dark,
                colors(frozen.material), colors(frozen.status), colors(frozen.chart))
        }
    }
}

/** Theme RGB and object ARGB syntax only; malformed values are never silently replaced. */
internal fun parseAppearanceColor(value: String): Int {
    require(isAccentColor(value))
    return if (value.length == 7) (0xff000000L or value.drop(1).toLong(16)).toInt()
    else value.drop(1).toLong(16).toInt()
}

internal enum class ContrastUse(val minimum: Double) { TEXT(4.5), LARGE_TEXT_OR_GRAPHIC(3.0) }
internal data class ReadableForeground(val argb: Int, val backgroundArgb: Int, val ratio: Double, val adjusted: Boolean)

/** Display-only adjustment. Callers supply the actual opaque backdrop, never an assumed theme mode. */
internal object AppearanceContrast {
    fun composite(foreground: Int, opaqueBackground: Int): Int {
        require(opaqueBackground ushr 24 == 255)
        val alpha = (foreground ushr 24) / 255.0
        fun channel(shift: Int): Int = (((foreground ushr shift) and 255) * alpha +
            ((opaqueBackground ushr shift) and 255) * (1 - alpha)).roundToInt()
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun ratio(foreground: Int, opaqueBackground: Int): Double {
        val painted = composite(foreground, opaqueBackground)
        val first = luminance(painted)
        val second = luminance(opaqueBackground)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    fun resolve(preferred: Int, background: Int, opaqueBackdrop: Int, use: ContrastUse = ContrastUse.TEXT): ReadableForeground {
        val actualBackground = composite(background, opaqueBackdrop)
        val originalRatio = ratio(preferred, actualBackground)
        if (originalRatio >= use.minimum) return ReadableForeground(preferred, actualBackground, originalRatio, false)
        val black = 0xff000000.toInt()
        val white = 0xffffffff.toInt()
        val adjusted = if (ratio(black, actualBackground) >= ratio(white, actualBackground)) black else white
        return ReadableForeground(adjusted, actualBackground, ratio(adjusted, actualBackground), true)
    }

    private fun luminance(opaque: Int): Double {
        fun linear(shift: Int): Double {
            val component = ((opaque ushr shift) and 255) / 255.0
            return if (component <= 0.04045) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
}
