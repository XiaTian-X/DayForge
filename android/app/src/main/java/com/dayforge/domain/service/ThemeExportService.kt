package com.dayforge.domain.service

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.dayforge.domain.model.GlobalColorTheme
import com.dayforge.domain.repository.CustomThemeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.graphics.toColorInt
import androidx.compose.ui.graphics.Color
import com.dayforge.ui.theme.ColorSchemeGenerator

private const val TAG = "ThemeExportService"

/**
 * Service for exporting themes to JSON.
 *
 * Export sources:
 * - Preset themes: read from assets/themes/ directory as JSON files
 * - Custom themes: read from files/themes/ directory as JSON files
 * - Themes with custom colors: serialize all color fields
 */
@Singleton
class ThemeExportService @Inject constructor(
    private val themeManager: ThemeManager,
    private val customThemeRepository: CustomThemeRepository,
    @param:ApplicationContext private val context: Context
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false  // Only include non-null values
    }

    /**
     * Export a theme to JSON string (with all 27 color fields).
     *
     * @param themeId Theme ID to export
     * @param includeGeneratedColors If true, generate all 27 colors from seedColor (for template export)
     * @return Result.success with JSON string, or Result.failure on error
     */
    suspend fun exportToJson(themeId: String, includeGeneratedColors: Boolean = false): Result<String> {
        Log.d(TAG, "exportToJson called with themeId: $themeId, includeGeneratedColors: $includeGeneratedColors")
        return try {
            // 1. Get theme from manager
            val theme = themeManager.getById(themeId)
            Log.d(TAG, "Got theme: id=${theme.id}, name=${theme.name}, isCustom=${theme.isCustom}, isDefault=${theme.isDefault}")

            // 2. If includeGeneratedColors, generate complete theme with all 27 colors
            if (includeGeneratedColors) {
                val completeTheme = generateCompleteTheme(theme)
                val jsonString = json.encodeToString(completeTheme)
                Log.d(TAG, "Generated complete theme JSON (${jsonString.length} chars)")
                return Result.success(jsonString)
            }

            // 3. Determine if custom or preset theme
            val isCustomTheme = theme.isCustom

            // 4. Try to get original JSON from source
            val originalJson = if (isCustomTheme) {
                Log.d(TAG, "Loading from custom theme repository")
                customThemeRepository.getThemeJson(themeId)
            } else {
                Log.d(TAG, "Loading from assets: themes/$themeId.json")
                getPresetThemeJsonFromAssets(themeId)
            }
            Log.d(TAG, "Original JSON loaded: ${originalJson?.take(100) ?: "null"}...")

            // 5. If original JSON available, return it (preserves exact file content)
            if (!originalJson.isNullOrBlank()) {
                Log.d(TAG, "Returning original JSON (${originalJson.length} chars)")
                Result.success(originalJson)
            } else {
                // 6. Otherwise serialize from GlobalColorTheme object
                Log.d(TAG, "Original JSON not available, serializing theme object")
                val jsonString = serializeTheme(theme)
                Log.d(TAG, "Serialized theme: ${jsonString.take(100)}...")
                if (jsonString.isBlank()) {
                    Log.e(TAG, "Serialized JSON is blank!")
                    Result.failure(Exception("Failed to serialize theme"))
                } else {
                    Log.d(TAG, "Returning serialized JSON (${jsonString.length} chars)")
                    Result.success(jsonString)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            Result.failure(Exception("Export failed: ${e.message}", e))
        }
    }

    /**
     * Generate a complete theme with all 27 color fields from seedColor.
     * Used for template export to provide a reference for customization.
     */
    private fun generateCompleteTheme(theme: GlobalColorTheme): GlobalColorTheme {
        // Special case: OLED preset theme
        if (theme.id == "oled" && !theme.isCustom) {
            val oledScheme = ColorSchemeGenerator.generateOledColorScheme()
            return theme.copy(
                primary = oledScheme.primary.toHexString(),
                onPrimary = oledScheme.onPrimary.toHexString(),
                primaryContainer = oledScheme.primaryContainer.toHexString(),
                onPrimaryContainer = oledScheme.onPrimaryContainer.toHexString(),
                inversePrimary = oledScheme.inversePrimary.toHexString(),
                secondary = oledScheme.secondary.toHexString(),
                onSecondary = oledScheme.onSecondary.toHexString(),
                secondaryContainer = oledScheme.secondaryContainer.toHexString(),
                onSecondaryContainer = oledScheme.onSecondaryContainer.toHexString(),
                tertiary = oledScheme.tertiary.toHexString(),
                onTertiary = oledScheme.onTertiary.toHexString(),
                tertiaryContainer = oledScheme.tertiaryContainer.toHexString(),
                onTertiaryContainer = oledScheme.onTertiaryContainer.toHexString(),
                error = oledScheme.error.toHexString(),
                onError = oledScheme.onError.toHexString(),
                errorContainer = oledScheme.errorContainer.toHexString(),
                onErrorContainer = oledScheme.onErrorContainer.toHexString(),
                background = oledScheme.background.toHexString(),
                onBackground = oledScheme.onBackground.toHexString(),
                surface = oledScheme.surface.toHexString(),
                onSurface = oledScheme.onSurface.toHexString(),
                surfaceVariant = oledScheme.surfaceVariant.toHexString(),
                onSurfaceVariant = oledScheme.onSurfaceVariant.toHexString(),
                outline = oledScheme.outline.toHexString(),
                outlineVariant = oledScheme.outlineVariant.toHexString(),
                inverseSurface = oledScheme.inverseSurface.toHexString(),
                inverseOnSurface = oledScheme.inverseOnSurface.toHexString()
            )
        }

        // Generate scheme from seedColor (use light scheme for reference)
        val argb = parseSeedColor(theme.seedColor)
        val scheme = SeedColorPalette(argb)

        return theme.copy(
            primary = Color(scheme[SeedColorRole.PRIMARY]).toHexString(),
            onPrimary = Color(scheme[SeedColorRole.ON_PRIMARY]).toHexString(),
            primaryContainer = Color(scheme[SeedColorRole.PRIMARY_CONTAINER]).toHexString(),
            onPrimaryContainer = Color(scheme[SeedColorRole.ON_PRIMARY_CONTAINER]).toHexString(),
            inversePrimary = Color(scheme[SeedColorRole.INVERSE_PRIMARY]).toHexString(),
            secondary = Color(scheme[SeedColorRole.SECONDARY]).toHexString(),
            onSecondary = Color(scheme[SeedColorRole.ON_SECONDARY]).toHexString(),
            secondaryContainer = Color(scheme[SeedColorRole.SECONDARY_CONTAINER]).toHexString(),
            onSecondaryContainer = Color(scheme[SeedColorRole.ON_SECONDARY_CONTAINER]).toHexString(),
            tertiary = Color(scheme[SeedColorRole.TERTIARY]).toHexString(),
            onTertiary = Color(scheme[SeedColorRole.ON_TERTIARY]).toHexString(),
            tertiaryContainer = Color(scheme[SeedColorRole.TERTIARY_CONTAINER]).toHexString(),
            onTertiaryContainer = Color(scheme[SeedColorRole.ON_TERTIARY_CONTAINER]).toHexString(),
            error = Color(scheme[SeedColorRole.ERROR]).toHexString(),
            onError = Color(scheme[SeedColorRole.ON_ERROR]).toHexString(),
            errorContainer = Color(scheme[SeedColorRole.ERROR_CONTAINER]).toHexString(),
            onErrorContainer = Color(scheme[SeedColorRole.ON_ERROR_CONTAINER]).toHexString(),
            background = Color(scheme[SeedColorRole.BACKGROUND]).toHexString(),
            onBackground = Color(scheme[SeedColorRole.ON_BACKGROUND]).toHexString(),
            surface = Color(scheme[SeedColorRole.SURFACE]).toHexString(),
            onSurface = Color(scheme[SeedColorRole.ON_SURFACE]).toHexString(),
            surfaceVariant = Color(scheme[SeedColorRole.SURFACE_VARIANT]).toHexString(),
            onSurfaceVariant = Color(scheme[SeedColorRole.ON_SURFACE_VARIANT]).toHexString(),
            outline = Color(scheme[SeedColorRole.OUTLINE]).toHexString(),
            outlineVariant = Color(scheme[SeedColorRole.OUTLINE_VARIANT]).toHexString(),
            inverseSurface = Color(scheme[SeedColorRole.INVERSE_SURFACE]).toHexString(),
            inverseOnSurface = Color(scheme[SeedColorRole.INVERSE_ON_SURFACE]).toHexString()
        )
    }

    /**
     * Convert Color to HEX string (#RRGGBB format).
     */
    private fun Color.toHexString(): String {
        val red = (red * 255).toInt().coerceIn(0, 255)
        val green = (green * 255).toInt().coerceIn(0, 255)
        val blue = (blue * 255).toInt().coerceIn(0, 255)
        return "#${red.toString(16).padStart(2, '0').uppercase()}${green.toString(16).padStart(2, '0').uppercase()}${blue.toString(16).padStart(2, '0').uppercase()}"
    }

    private fun parseSeedColor(seedColorHex: String): Int {
        return try {
            seedColorHex.toColorInt()
        } catch (e: IllegalArgumentException) {
            "#1976D2".toColorInt()
        }
    }

    /**
     * Serialize a theme to JSON, including all non-null custom colors.
     * Uses kotlinx.serialization for reliable serialization of all 27 color fields.
     */
    private fun serializeTheme(theme: GlobalColorTheme): String {
        return json.encodeToString(theme)
    }

    /**
     * Escape special characters in JSON string values.
     */
    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Read preset theme JSON from assets.
     *
     * @param themeId Theme ID
     * @return JSON string or null if not found
     */
    private fun getPresetThemeJsonFromAssets(themeId: String): String? {
        return try {
            val assetManager: AssetManager = context.assets
            val fileName = "themes/$themeId.json"
            Log.d(TAG, "Opening asset: $fileName")
            val result = assetManager.open(fileName).bufferedReader().use { reader ->
                reader.readText()
            }
            Log.d(TAG, "Successfully read asset: $fileName (${result.length} chars)")
            result
        } catch (e: Exception) {
            // Asset not found or read error - log for debugging
            Log.e(TAG, "Failed to read asset themes/$themeId.json: ${e.message}", e)
            null
        }
    }
}
