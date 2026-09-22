package com.dayforge.domain.repository

import android.content.Context
import android.util.Log
import com.dayforge.domain.model.GlobalColorTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CustomThemeRepository"

/**
 * Repository for managing user custom themes stored in files/themes/ directory as JSON files.
 *
 * Custom themes are stored in the app's private files directory.
 * Provides CRUD operations for custom themes with JSON serialization.
 */
@Singleton
class CustomThemeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val themesDir: File = File(context.filesDir, "themes")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Get all custom themes from files/themes/ directory as JSON files.
     *
     * @return List of GlobalColorTheme from custom theme files
     */
    suspend fun getAllCustomThemes(): List<GlobalColorTheme> {
        ensureThemesDirExists()

        val themeFiles = themesDir.listFiles { file ->
            file.extension == "json"
        } ?: return emptyList()

        return themeFiles.mapNotNull { file ->
            try {
                val jsonString = file.readText()
                Log.d(TAG, "Loading theme from ${file.name}: ${jsonString.take(200)}...")
                val theme = json.decodeFromString<GlobalColorTheme>(jsonString)
                Log.d(TAG, "Parsed theme: id=${theme.id}, primary=${theme.primary}, background=${theme.background}")
                // Mark as custom theme (override isCustom if not set)
                theme.copy(isCustom = true, isDefault = false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load theme from ${file.name}: ${e.message}")
                // Skip invalid files
                null
            }
        }
    }

    /**
     * Add a new custom theme.
     *
     * @param theme The theme to add (id will be used as filename)
     * @return Result.success with theme id, or Result.failure on error
     */
    suspend fun addTheme(theme: GlobalColorTheme): Result<String> {
        return try {
            ensureThemesDirExists()

            // Validate theme ID doesn't conflict with preset themes
            if (theme.isDefault) {
                return Result.failure(IllegalArgumentException("Cannot add preset theme as custom"))
            }

            // Write to file: files/themes/{id}.json
            val file = File(themesDir, "${theme.id}.json")

            // Check for duplicate ID
            if (file.exists()) {
                return Result.failure(IllegalArgumentException("Theme with ID '${theme.id}' already exists"))
            }

            val themeToSave = theme.copy(isCustom = true, isDefault = false)
            val jsonString = json.encodeToString(themeToSave)
            Log.d(TAG, "Saving theme to ${file.name}: primary=${theme.primary}, background=${theme.background}")
            Log.d(TAG, "JSON content: $jsonString")
            file.writeText(jsonString)

            Result.success(theme.id)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(IOException("Failed to add theme: ${e.message}", e))
        }
    }

    /**
     * Delete a custom theme by ID.
     *
     * @param themeId The theme ID to delete
     * @return Result.success on deletion, Result.failure if not found or is preset
     */
    suspend fun deleteTheme(themeId: String): Result<Unit> {
        return try {
            ensureThemesDirExists()

            val file = File(themesDir, "$themeId.json")

            if (!file.exists()) {
                return Result.failure(IllegalArgumentException("Theme '$themeId' not found"))
            }

            // Verify it's not a preset theme (shouldn't happen, but safety check)
            val jsonString = file.readText()
            val theme = json.decodeFromString<GlobalColorTheme>(jsonString)
            if (theme.isDefault) {
                return Result.failure(IllegalArgumentException("Cannot delete preset theme"))
            }

            file.delete()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IOException("Failed to delete theme: ${e.message}", e))
        }
    }

    /**
     * Get a custom theme by ID.
     *
     * @param themeId The theme ID
     * @return GlobalColorTheme or null if not found
     */
    suspend fun getThemeById(themeId: String): GlobalColorTheme? {
        ensureThemesDirExists()

        val file = File(themesDir, "$themeId.json")
        if (!file.exists()) return null

        return try {
            val jsonString = file.readText()
            val theme = json.decodeFromString<GlobalColorTheme>(jsonString)
            theme.copy(isCustom = true, isDefault = false)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the raw JSON string for a theme by ID.
     *
     * @param themeId The theme ID
     * @return JSON string or null if not found
     */
    suspend fun getThemeJson(themeId: String): String? {
        ensureThemesDirExists()

        val file = File(themesDir, "$themeId.json")
        if (!file.exists()) return null

        return try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if a theme ID exists as a custom theme.
     */
    suspend fun exists(themeId: String): Boolean {
        ensureThemesDirExists()
        return File(themesDir, "$themeId.json").exists()
    }

    /**
     * Ensure themes directory exists, create if not.
     */
    private fun ensureThemesDirExists() {
        if (!themesDir.exists()) {
            themesDir.mkdirs()
        }
    }
}
