package com.dayforge.domain.service

import android.content.Context
import android.util.Log
import com.dayforge.domain.model.GlobalColorTheme
import com.dayforge.domain.repository.CustomThemeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.dayforge.R

private const val TAG = "ThemeImportService"

/**
 * Service for importing custom themes from JSON.
 *
 * Import flow:
 * 1. Parse and validate JSON structure
 * 2. Validate seedColor format (#RRGGBB)
 * 3. Validate all custom color formats if present
 * 4. Generate unique ID if duplicate exists
 * 5. Save to custom theme repository
 */
@Singleton
class ThemeImportService @Inject constructor(
    private val customThemeRepository: CustomThemeRepository,
    private val themeManager: ThemeManager,
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Import a theme from JSON string.
     *
     * @param jsonString JSON containing GlobalColorTheme fields
     * @return Result.success with imported theme, or Result.failure on error
     */
    suspend fun importFromJson(jsonString: String): Result<GlobalColorTheme> {
        Log.d(TAG, "importFromJson called with JSON length: ${jsonString.length}")
        Log.d(TAG, "JSON preview: ${jsonString.take(200)}...")
        return try {
            // 1. Parse JSON
            val theme = json.decodeFromString<GlobalColorTheme>(jsonString)
            Log.d(TAG, "Parsed theme: id=${theme.id}, name=${theme.name}, seedColor=${theme.seedColor}")
            Log.d(TAG, "Parsed custom colors: primary=${theme.primary}, secondary=${theme.secondary}, background=${theme.background}")
            Log.d(TAG, "All custom colors: hasCustomColors=${theme.hasCustomColors()}")

            // 2. Validate seedColor format (#RRGGBB)
            if (!isValidColorFormat(theme.seedColor)) {
                Log.e(TAG, "Invalid seedColor format: '${theme.seedColor}'")
                return Result.failure(
                    IllegalArgumentException(context.getString(R.string.theme_error_invalid_seed_color, theme.seedColor))
                )
            }

            // 3. Validate at least one mode is suitable
            if (!theme.suitableForLight && !theme.suitableForDark) {
                Log.e(TAG, "Theme not suitable for any mode")
                return Result.failure(
                    IllegalArgumentException(context.getString(R.string.theme_error_no_mode))
                )
            }

            // 4. Validate all custom colors format
            val colorValidationErrors = validateCustomColors(theme)
            if (colorValidationErrors.isNotEmpty()) {
                Log.e(TAG, "Invalid color formats: ${colorValidationErrors.joinToString(", ")}")
                return Result.failure(
                    IllegalArgumentException(context.getString(R.string.theme_error_invalid_colors, colorValidationErrors.joinToString(", ")))
                )
            }

            // 5. Handle duplicate ID - generate new UUID if exists
            val finalId = if (themeManager.isValidThemeId(theme.id) || theme.isDefault) {
                val newId = "${theme.id}-${UUID.randomUUID().toString().take(8)}"
                Log.d(TAG, "Theme ID '${theme.id}' exists, generating new ID: $newId")
                newId
            } else {
                Log.d(TAG, "Using original theme ID: ${theme.id}")
                theme.id
            }

            // 6. Create final theme with proper flags
            val importedTheme = theme.copy(
                id = finalId,
                isDefault = false,
                isCustom = true
            )
            Log.d(TAG, "Final imported theme: id=${importedTheme.id}, isCustom=${importedTheme.isCustom}")

            // 7. Save to repository
            val addResult = customThemeRepository.addTheme(importedTheme)
            if (addResult.isFailure) {
                Log.e(TAG, "Failed to save theme: ${addResult.exceptionOrNull()?.message}")
                return Result.failure(addResult.exceptionOrNull() ?: Exception(context.getString(R.string.theme_error_save_failed)))
            }
            Log.d(TAG, "Theme saved successfully with ID: ${importedTheme.id}")

            // 8. Refresh theme manager
            themeManager.refresh()
            Log.d(TAG, "Theme manager refreshed")

            Result.success(importedTheme)
        } catch (e: SerializationException) {
            Log.e(TAG, "JSON parsing failed: ${e.message}", e)
            Result.failure(IllegalArgumentException(context.getString(R.string.theme_error_invalid_json, e.message)))
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            Result.failure(Exception(context.getString(R.string.theme_import_error)))
        }
    }

    /**
     * Validate all custom colors in a theme.
     *
     * @param theme Theme to validate
     * @return List of error messages for invalid colors
     */
    private fun validateCustomColors(theme: GlobalColorTheme): List<String> {
        val errors = mutableListOf<String>()

        // List of all 25 color fields supported by Material3 1.3.1 lightColorScheme/darkColorScheme
        val colorFields = listOf(
            // Primary (5)
            "primary" to theme.primary,
            "onPrimary" to theme.onPrimary,
            "primaryContainer" to theme.primaryContainer,
            "onPrimaryContainer" to theme.onPrimaryContainer,
            "inversePrimary" to theme.inversePrimary,

            // Secondary (4)
            "secondary" to theme.secondary,
            "onSecondary" to theme.onSecondary,
            "secondaryContainer" to theme.secondaryContainer,
            "onSecondaryContainer" to theme.onSecondaryContainer,

            // Tertiary (4)
            "tertiary" to theme.tertiary,
            "onTertiary" to theme.onTertiary,
            "tertiaryContainer" to theme.tertiaryContainer,
            "onTertiaryContainer" to theme.onTertiaryContainer,

            // Error (4)
            "error" to theme.error,
            "onError" to theme.onError,
            "errorContainer" to theme.errorContainer,
            "onErrorContainer" to theme.onErrorContainer,

            // Background (2)
            "background" to theme.background,
            "onBackground" to theme.onBackground,

            // Surface (4)
            "surface" to theme.surface,
            "onSurface" to theme.onSurface,
            "surfaceVariant" to theme.surfaceVariant,
            "onSurfaceVariant" to theme.onSurfaceVariant,

            // Outline (2)
            "outline" to theme.outline,
            "outlineVariant" to theme.outlineVariant,

            // Inverse (2)
            "inverseSurface" to theme.inverseSurface,
            "inverseOnSurface" to theme.inverseOnSurface
        )

        for ((fieldName, colorValue) in colorFields) {
            if (colorValue != null && !isValidColorFormat(colorValue)) {
                errors.add("$fieldName='$colorValue'")
            }
        }

        return errors
    }

    /**
     * Validate color format (#RRGGBB or #AARRGGBB).
     *
     * @param color Color string to validate
     * @return true if valid format
     */
    private fun isValidColorFormat(color: String): Boolean {
        if (!color.startsWith("#")) return false

        val hexPart = color.substring(1)
        return when (hexPart.length) {
            6 -> hexPart.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
            8 -> hexPart.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
            else -> false
        }
    }
}