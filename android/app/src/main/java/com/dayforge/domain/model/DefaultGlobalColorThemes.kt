package com.dayforge.domain.model

/**
 * Built-in global color themes for Material3 ColorScheme generation.
 * Each theme has a single seed color that generates the full ColorScheme.
 *
 * Light-suitable themes (THEME-04): Ocean, Nature, Vibrant
 * Dark-suitable themes (THEME-05): Dusk, Forest, Coral, OLED
 */
object DefaultGlobalColorThemes {

    // ========== Light-suitable themes (THEME-04) ==========

    /** Ocean theme - Calm blue palette for light mode */
    val OCEAN = GlobalColorTheme(
        id = "ocean",
        name = "Ocean",
        seedColor = "#1976D2",
        suitableForLight = true,
        suitableForDark = false,
        isDefault = true
    )

    /** Nature theme - Natural green palette for light mode */
    val NATURE = GlobalColorTheme(
        id = "nature",
        name = "Nature",
        seedColor = "#4CAF50",
        suitableForLight = true,
        suitableForDark = false,
        isDefault = true
    )

    /** Vibrant theme - Energetic pink-orange palette for light mode */
    val VIBRANT = GlobalColorTheme(
        id = "vibrant",
        name = "Vibrant",
        seedColor = "#E91E63",
        suitableForLight = true,
        suitableForDark = false,
        isDefault = true
    )

    // ========== Dark-suitable themes (THEME-05) ==========

    /** Dusk theme - Deep blue palette for dark mode */
    val DUSK = GlobalColorTheme(
        id = "dusk",
        name = "Dusk",
        seedColor = "#1A237E",
        suitableForLight = false,
        suitableForDark = true,
        isDefault = true
    )

    /** Forest theme - Deep green palette for dark mode */
    val FOREST = GlobalColorTheme(
        id = "forest",
        name = "Forest",
        seedColor = "#1B5E20",
        suitableForLight = false,
        suitableForDark = true,
        isDefault = true
    )

    /** Coral theme - Deep pink palette for dark mode */
    val CORAL = GlobalColorTheme(
        id = "coral",
        name = "Coral",
        seedColor = "#880E4F",
        suitableForLight = false,
        suitableForDark = true,
        isDefault = true
    )

    /** OLED theme - Pure black background for power saving (THEME-07) */
    val OLED = GlobalColorTheme(
        id = "oled",
        name = "OLED",
        seedColor = "#000000",  // Pure black per D-07
        suitableForLight = false,
        suitableForDark = true,
        isDefault = true
    )

    /** All default themes in display order */
    val ALL: List<GlobalColorTheme> = listOf(OCEAN, NATURE, VIBRANT, DUSK, FOREST, CORAL, OLED)

    /** Light-suitable themes for Phase 71 theme selector (THEME-17) */
    val LIGHT_THEMES: List<GlobalColorTheme> = ALL.filter { it.suitableForLight }

    /** Dark-suitable themes for Phase 71 theme selector (THEME-18) */
    val DARK_THEMES: List<GlobalColorTheme> = ALL.filter { it.suitableForDark }

    /**
     * Get a theme by its ID.
     * Returns OCEAN as fallback if ID is not found.
     */
    fun getById(id: String): GlobalColorTheme {
        return ALL.find { it.id == id } ?: OCEAN
    }

    /**
     * Check if a theme ID is a valid default theme.
     */
    fun isDefaultTheme(id: String): Boolean {
        return ALL.any { it.id == id }
    }
}