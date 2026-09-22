package com.dayforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.graphics.toColorInt
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.widget.base.ColorUtils

/**
 * Default fallback color palette when theme colors are not available.
 */
val DEFAULT_COLOR_PALETTE = listOf(
    "#2196F3", // Blue
    "#4CAF50", // Green
    "#FF9800", // Orange
    "#F44336", // Red
    "#9C27B0", // Purple
    "#E91E63", // Pink
    "#00BCD4", // Cyan
    "#FFC107", // Amber
    "#795548", // Brown
    "#607D8B"  // Blue Grey
)

/**
 * Convert Color to HEX string format (#RRGGBB).
 */
fun colorToHex(color: Color): String {
    val argb = color.toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}".uppercase()
}

/**
 * Convert RGB to HSV (Hue, Saturation, Value).
 * Returns float array [hue (0-360), saturation (0-1), value (0-1)].
 */
private fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f

    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val delta = max - min

    val h = when {
        delta == 0f -> 0f
        max == rf -> 60f * (((gf - bf) / delta) % 6)
        max == gf -> 60f * ((bf - rf) / delta + 2)
        else -> 60f * ((rf - gf) / delta + 4)
    }

    val s = if (max == 0f) 0f else delta / max
    val v = max

    return floatArrayOf(if (h < 0) h + 360 else h, s, v)
}

/**
 * Convert HSV to RGB color.
 */
private fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
    val m = v - c

    val (r1, g1, b1) = when {
        h < 60 -> floatArrayOf(c, x, 0f)
        h < 120 -> floatArrayOf(x, c, 0f)
        h < 180 -> floatArrayOf(0f, c, x)
        h < 240 -> floatArrayOf(0f, x, c)
        h < 300 -> floatArrayOf(x, 0f, c)
        else -> floatArrayOf(c, 0f, x)
    }

    return Color(
        red = ((r1 + m) * 255).toInt().coerceIn(0, 255),
        green = ((g1 + m) * 255).toInt().coerceIn(0, 255),
        blue = ((b1 + m) * 255).toInt().coerceIn(0, 255)
    )
}

/**
 * Generate harmonious colors based on a seed color from the theme.
 * Uses color theory to create complementary, analogous, and triadic colors
 * that coordinate with the theme but are distinct enough for habit cards.
 *
 * @param seedColor The theme's primary or accent color to base harmony on
 * @return List of 10 harmonious HEX color strings
 */
private fun generateHarmoniousPalette(seedColor: Color): List<String> {
    val argb = seedColor.toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF

    val hsv = rgbToHsv(r, g, b)
    val baseHue = hsv[0]
    val baseSat = hsv[1]
    val baseVal = hsv[2]

    // Generate colors using color harmony theory
    // Avoid colors too similar to theme (adjust saturation/value to differentiate)
    return listOf(
        // Complementary color (opposite on color wheel) - with adjusted saturation
        hsvToColor((baseHue + 180) % 360, (baseSat * 0.8f).coerceIn(0.4f, 0.9f), 0.85f),

        // Analogous colors (adjacent on color wheel)
        hsvToColor((baseHue + 30) % 360, (baseSat * 0.9f).coerceIn(0.5f, 0.95f), 0.8f),
        hsvToColor((baseHue + 330) % 360, (baseSat * 0.85f).coerceIn(0.45f, 0.9f), 0.75f),

        // Triadic colors (120° apart)
        hsvToColor((baseHue + 120) % 360, (baseSat * 0.75f).coerceIn(0.4f, 0.85f), 0.85f),
        hsvToColor((baseHue + 240) % 360, (baseSat * 0.7f).coerceIn(0.35f, 0.8f), 0.8f),

        // Split-complementary (adjacent to complement)
        hsvToColor((baseHue + 150) % 360, (baseSat * 0.8f).coerceIn(0.4f, 0.85f), 0.9f),
        hsvToColor((baseHue + 210) % 360, (baseSat * 0.75f).coerceIn(0.35f, 0.8f), 0.85f),

        // Tints and shades of analogous
        hsvToColor((baseHue + 60) % 360, (baseSat * 0.6f).coerceIn(0.3f, 0.7f), 0.95f),
        hsvToColor((baseHue + 90) % 360, (baseSat * 0.55f).coerceIn(0.25f, 0.65f), 0.7f),

        // Neutral earth tone (low saturation)
        hsvToColor(baseHue, 0.25f, 0.55f)
    ).map { colorToHex(it) }
}

/**
 * Generate theme-based color palette that complements the current theme.
 * Uses color harmony theory to create colors that work well with the theme
 * but are distinct enough to avoid confusion with UI elements.
 */
@Composable
fun rememberThemeColorPalette(): List<String> {
    val colorScheme = MaterialTheme.colorScheme
    return remember(colorScheme.primary) {
        // Use primary color as seed to generate harmonious palette
        // This ensures colors coordinate with theme but don't match it exactly
        generateHarmoniousPalette(colorScheme.primary)
    }
}

@Composable
fun ColorPicker(
    selectedColor: String,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    presetColors: List<String> = rememberThemeColorPalette()
) {
    var hexInput by rememberSaveable { mutableStateOf("") }
    val hexRegex = Regex("^#[0-9A-Fa-f]{6}$")
    val isValidHex = hexInput.isNotEmpty() && hexRegex.matches(hexInput)
    val validationError = if (hexInput.isNotEmpty() && !hexRegex.matches(hexInput)) {
        stringResource(R.string.color_picker_hex_invalid)
    } else null

    // Track which color is selected: preset or HEX
    val isHexSelected = isValidHex && hexInput == selectedColor

    // Calculate grid columns based on preset count (5 for 10, 6 for 12)
    val gridColumns = if (presetColors.size <= 10) 5 else 6

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_metric_select_color)) },
        text = {
            Column {
                // Preview Box (48x48dp, centered)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.CenterHorizontally)
                        .background(
                            color = if (isValidHex) ColorUtils.parseColor(hexInput, MaterialTheme.colorScheme.outline) else MaterialTheme.colorScheme.outline,
                            shape = MaterialTheme.shapes.small
                        )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // HEX Input TextField
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { newValue ->
                        hexInput = newValue
                        // Auto-select when valid HEX (no separate button)
                        if (hexRegex.matches(newValue)) {
                            onColorSelected(newValue)
                        }
                    },
                    label = { Text(stringResource(R.string.color_picker_hex_label)) },
                    placeholder = { Text("#RRGGBB") },
                    singleLine = true,
                    isError = validationError != null,
                    supportingText = validationError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    trailingIcon = {
                        if (hexInput.isNotEmpty()) {
                            IconButton(onClick = { hexInput = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.color_picker_hex_clear)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Preset Colors Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(presetColors.size) { index ->
                        val color = presetColors[index]
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    color = Color(color.toColorInt()),
                                    shape = MaterialTheme.shapes.small
                                )
                                .clickable {
                                    hexInput = ""  // Clear HEX field (exclusive mode)
                                    onColorSelected(color)
                                }
                        ) {
                            if (color == selectedColor && !isHexSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.content_description_selected),
                                    tint = Color.White,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        }
    )
}
