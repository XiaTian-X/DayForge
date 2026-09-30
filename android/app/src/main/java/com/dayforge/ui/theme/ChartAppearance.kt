package com.dayforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.toColorInt
import com.dayforge.domain.appearance.AppearanceContrast
import com.dayforge.domain.appearance.ContrastUse

/** Display values only: never modify saved palettes, object accents or business data. */
internal data class ChartAppearance(
    val line: Color,
    val target: Color,
    val targetLabel: Color,
    val label: Color,
    val axis: Color,
    val grid: Color
)

@Composable
internal fun rememberChartAppearance(
    metricColorHex: String,
    lineOverride: Color?,
    background: Color,
    opaqueBackdrop: Color
): ChartAppearance {
    val theme = LocalResolvedTheme.current
    val material = MaterialTheme.colorScheme
    return remember(theme, material, metricColorHex, lineOverride, background, opaqueBackdrop) {
        // Plain MaterialTheme callers still support the current v4 accent. Production App obtains
        // a complete saved theme before rendering: missing/corrupt storage is not handled here.
        val preferredLine = lineOverride?.takeIf { it != Color.Unspecified }?.toArgb()
            ?: theme?.chart?.getValue("line")
            ?: try { metricColorHex.toColorInt() } catch (_: IllegalArgumentException) { material.primary.toArgb() }
        val preferredTarget = theme?.chart?.getValue("target") ?: preferredLine
        val grid = theme?.chart?.getValue("grid") ?: material.outlineVariant.toArgb()
        fun readable(preferred: Int, use: ContrastUse) = Color(AppearanceContrast.resolve(
            preferred, background.toArgb(), opaqueBackdrop.toArgb(), use).argb)
        ChartAppearance(
            line = readable(preferredLine, ContrastUse.LARGE_TEXT_OR_GRAPHIC),
            target = readable(preferredTarget, ContrastUse.LARGE_TEXT_OR_GRAPHIC),
            targetLabel = readable(preferredTarget, ContrastUse.TEXT),
            label = readable(material.onSurfaceVariant.toArgb(), ContrastUse.TEXT),
            axis = readable(grid, ContrastUse.LARGE_TEXT_OR_GRAPHIC),
            // Decorative guidelines retain the saved role; axes/ticks are essential graphics.
            grid = Color(grid)
        )
    }
}
