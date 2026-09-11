package com.dayforge.domain.model

/**
 * Represents the card color style preference for habit cards and metric cards.
 *
 * Per CARD-02: Card color style preference persists in DataStore.
 * - FOLLOW_THEME: Card background blends with theme colors (15% blend with user's hue)
 * - PERSONALIZED: Card background uses user's custom colorHex directly
 */
enum class CardColorStyle {
    /**
     * Card background blends with theme colors.
     * Value name: "follow_theme" (used for DataStore persistence)
     */
    FOLLOW_THEME,

    /**
     * Card background uses user's custom colorHex directly.
     * Value name: "personalized" (used for DataStore persistence)
     */
    PERSONALIZED;

    companion object {
        /**
         * Parses a string value to CardColorStyle enum.
         * Returns null for invalid values.
         *
         * @param value The string representation ("follow_theme" or "personalized")
         * @return The corresponding CardColorStyle, or null if invalid
         */
        fun fromString(value: String): CardColorStyle? {
            return when (value.lowercase()) {
                "follow_theme" -> FOLLOW_THEME
                "personalized" -> PERSONALIZED
                else -> null
            }
        }

        /**
         * Parses a string value to CardColorStyle enum with default fallback.
         * Returns FOLLOW_THEME as default for invalid or null values.
         *
         * @param value The string representation (may be null or invalid)
         * @return The corresponding CardColorStyle, or DEFAULT for invalid values
         */
        fun fromStringOrDefault(value: String?): CardColorStyle {
            return when (value?.lowercase()) {
                "follow_theme" -> FOLLOW_THEME
                "personalized" -> PERSONALIZED
                else -> DEFAULT
            }
        }

        /**
         * Default card color style.
         * Per ROADMAP: Default value is "follow_theme" (blends with theme colors).
         */
        val DEFAULT: CardColorStyle = FOLLOW_THEME
    }
}