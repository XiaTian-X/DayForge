package com.dayforge.domain.service

import android.content.Context
import android.util.Log
import com.dayforge.domain.model.GlobalColorTheme
import com.dayforge.domain.repository.CustomThemeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ThemeManager"

/**
 * Unified theme manager that loads themes from both assets and custom files.
 *
 * Theme sources:
 * - assets/themes/ directory: Preset themes (packaged with APK) as JSON files
 * - files/themes/ directory: Custom user themes as JSON files
 *
 * Preset themes are loaded synchronously in constructor to avoid race conditions.
 * Custom themes are loaded asynchronously via initialize().
 */
@Singleton
class ThemeManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val customThemeRepository: CustomThemeRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Cache of all themes (preset + custom)
    // Initialize with preset themes synchronously to avoid race conditions
    private var _cachedThemes: List<GlobalColorTheme> = loadPresetThemesSync()
    private var _customThemesLoaded = false

    // StateFlow for UI observation
    private val _lightThemes = MutableStateFlow<List<GlobalColorTheme>>(getLightThemesFromCache())
    val lightThemes: StateFlow<List<GlobalColorTheme>> = _lightThemes.asStateFlow()

    private val _darkThemes = MutableStateFlow<List<GlobalColorTheme>>(getDarkThemesFromCache())
    val darkThemes: StateFlow<List<GlobalColorTheme>> = _darkThemes.asStateFlow()

    /**
     * Initialize: load custom themes from files.
     * Preset themes are already loaded synchronously in constructor.
     * Should be called once at app startup to load user custom themes.
     */
    suspend fun initialize() {
        if (_customThemesLoaded) return

        withContext(Dispatchers.IO) {
            val customThemes = customThemeRepository.getAllCustomThemes()
            val presetThemes = _cachedThemes.filter { it.isDefault }

            // Merge: preset first, then custom (sorted by name)
            _cachedThemes = presetThemes + customThemes.sortedBy { it.name }
            _customThemesLoaded = true

            // Update StateFlows
            updateThemeFlows()
        }
    }

    /**
     * Get a theme by ID.
     * Returns default Ocean theme if not found.
     * Compatible with DefaultGlobalColorThemes.getById() API.
     *
     * @param id Theme ID (e.g., "ocean", "dusk")
     * @return GlobalColorTheme (fallback to Ocean if not found)
     */
    fun getById(id: String): GlobalColorTheme {
        return _cachedThemes.find { it.id == id } ?: getDefaultFallback()
    }

    /**
     * Get light-suitable themes (direct from cache, for internal use).
     */
    private fun getLightThemesFromCache(): List<GlobalColorTheme> {
        return _cachedThemes.filter { it.suitableForLight }
    }

    /**
     * Get dark-suitable themes (direct from cache, for internal use).
     */
    private fun getDarkThemesFromCache(): List<GlobalColorTheme> {
        return _cachedThemes.filter { it.suitableForDark }
    }

    /**
     * Update theme StateFlows after cache changes.
     */
    private fun updateThemeFlows() {
        _lightThemes.value = getLightThemesFromCache()
        _darkThemes.value = getDarkThemesFromCache()
    }

    /**
     * Check if a theme ID is valid (exists in preset or custom).
     *
     * @param id Theme ID to check
     * @return true if theme exists
     */
    fun isValidThemeId(id: String): Boolean {
        return _cachedThemes.any { it.id == id }
    }

    /**
     * Check if a theme is a preset (built-in) theme.
     *
     * @param id Theme ID to check
     * @return true if theme is preset (not custom)
     */
    fun isPresetTheme(id: String): Boolean {
        val theme = getById(id)
        return theme.isDefault || !theme.isCustom
    }

    /**
     * Refresh theme list after import/delete.
     */
    suspend fun refresh() {
        _customThemesLoaded = false
        initialize()
        // StateFlows are updated in initialize(), but also update here for safety
        updateThemeFlows()
    }

    /**
     * Load preset themes synchronously from assets.
     * Called in constructor to ensure themes are available immediately.
     */
    private fun loadPresetThemesSync(): List<GlobalColorTheme> {
        Log.d(TAG, "loadPresetThemesSync called")
        return try {
            val assetManager = context.assets
            val themeFiles = assetManager.list("themes") ?: run {
                Log.e(TAG, "assets/themes directory not found or empty")
                return getDefaultFallbackList()
            }
            Log.d(TAG, "Found ${themeFiles.size} files in assets/themes: ${themeFiles.joinToString()}")

            val themes = themeFiles
                .filter { it.endsWith(".json") }
                .mapNotNull { fileName ->
                    try {
                        val assetPath = "themes/$fileName"
                        Log.d(TAG, "Reading asset: $assetPath")
                        val jsonString = assetManager.open(assetPath).bufferedReader().use { it.readText() }
                        Log.d(TAG, "Asset content preview: ${jsonString.take(100)}...")
                        val theme = json.decodeFromString<GlobalColorTheme>(jsonString)
                        Log.d(TAG, "Parsed theme: id=${theme.id}, name=${theme.name}")
                        // Mark as preset theme
                        theme.copy(isDefault = true, isCustom = false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load preset theme from $fileName: ${e.message}", e)
                        null
                    }
                }
                .sortedBy { it.name }
            Log.d(TAG, "Loaded ${themes.size} preset themes: ${themes.map { it.id }.joinToString()}")
            themes
        } catch (e: Exception) {
            // Fallback to hardcoded themes if assets not available
            Log.e(TAG, "Failed to load preset themes from assets: ${e.message}", e)
            getDefaultFallbackList()
        }
    }

    /**
     * Get default fallback Ocean theme.
     * Used when requested theme ID not found.
     */
    private fun getDefaultFallback(): GlobalColorTheme {
        return GlobalColorTheme(
            id = "ocean",
            name = "Ocean",
            seedColor = "#1976D2",
            suitableForLight = true,
            suitableForDark = false,
            isDefault = true,
            isCustom = false
        )
    }

    /**
     * Get default fallback themes list.
     * Used when assets loading fails.
     */
    private fun getDefaultFallbackList(): List<GlobalColorTheme> {
        return listOf(
            getDefaultFallback(),
            GlobalColorTheme(
                id = "nature",
                name = "Nature",
                seedColor = "#4CAF50",
                suitableForLight = true,
                suitableForDark = false,
                isDefault = true,
                isCustom = false
            ),
            GlobalColorTheme(
                id = "vibrant",
                name = "Vibrant",
                seedColor = "#E91E63",
                suitableForLight = true,
                suitableForDark = false,
                isDefault = true,
                isCustom = false
            ),
            GlobalColorTheme(
                id = "dusk",
                name = "Dusk",
                seedColor = "#1A237E",
                suitableForLight = false,
                suitableForDark = true,
                isDefault = true,
                isCustom = false
            ),
            GlobalColorTheme(
                id = "forest",
                name = "Forest",
                seedColor = "#1B5E20",
                suitableForLight = false,
                suitableForDark = true,
                isDefault = true,
                isCustom = false
            ),
            GlobalColorTheme(
                id = "coral",
                name = "Coral",
                seedColor = "#880E4F",
                suitableForLight = false,
                suitableForDark = true,
                isDefault = true,
                isCustom = false
            ),
            GlobalColorTheme(
                id = "oled",
                name = "OLED",
                seedColor = "#000000",
                suitableForLight = false,
                suitableForDark = true,
                isDefault = true,
                isCustom = false
            )
        )
    }
}
