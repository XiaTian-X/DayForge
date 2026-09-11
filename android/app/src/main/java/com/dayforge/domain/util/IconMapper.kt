package com.dayforge.domain.util

/**
 * Utility object for bidirectional icon mapping between local iconResId (1-91)
 * and server icon names. This provides a single source of truth for icon conversion,
 * enabling proper sync behavior for both upload and download paths.
 *
 * Icons 1-8 are legacy icons preserved for backward compatibility.
 * Icons 9-52 are category-based icons added in Phase 67.
 * Icon 53 is the TaskAlt icon added in Phase 88 for temporary tasks.
 * Icons 54-91 are extended icons: Personal Hygiene, Social, Finance, Creative,
 * Transportation, Entertainment, Household, Outdoor, Technology.
 */
object IconMapper {
    // Mapping from local iconResId to server icon name
    // Must match IconPicker.kt category icon lists exactly
    private val RES_ID_TO_NAME = mapOf(
        // Legacy icons 1-8 (backward compatible)
        1 to "water",
        2 to "exercise",
        3 to "sleep",
        4 to "food",
        5 to "book",
        6 to "meditation",
        7 to "work",
        8 to "health",
        // Fitness icons (9-17)
        9 to "fitness_center",
        10 to "directions_bike",
        11 to "sports_gymnastics",
        12 to "sports",
        13 to "pool",
        14 to "hiking",
        15 to "directions_walk",
        16 to "sports_soccer",
        17 to "sports_basketball",
        // Health icons (18-25)
        18 to "local_hospital",
        19 to "medical_services",
        20 to "healing",
        21 to "bloodtype",
        22 to "sanitizer",
        23 to "restaurant",
        24 to "local_pharmacy",
        25 to "vaccines",
        // Learning icons (26-34)
        26 to "school",
        27 to "menu_book",
        28 to "lightbulb",
        29 to "calculate",
        30 to "translate",
        31 to "science",
        32 to "edit_note",
        33 to "psychology",
        34 to "code",
        // Mental icons (35-43)
        35 to "spa",
        36 to "sentiment_satisfied",
        37 to "mood",
        38 to "psychology_alt",
        39 to "sentiment_very_satisfied",
        40 to "nature",
        41 to "forest",
        42 to "grain",
        43 to "self_improvement",
        // Lifestyle icons (44-52)
        44 to "home",
        45 to "shopping_bag",
        46 to "shopping_cart",
        47 to "cleaning_services",
        48 to "local_laundry_service",
        49 to "pets",
        50 to "family_restroom",
        51 to "celebration",
        52 to "nightlife",
        // TODO icons (53)
        53 to "task_alt",
        // Personal Hygiene icons (54-61)
        54 to "shower",
        55 to "bathtub",
        56 to "wash",
        57 to "brush_teeth",
        58 to "face_wash",
        59 to "hair_brush",
        60 to "shave",
        61 to "nail_care",
        // Social icons (62-66)
        62 to "people",
        63 to "group",
        64 to "chat",
        65 to "forum",
        66 to "handshake",
        // Finance icons (67-71)
        67 to "account_balance",
        68 to "savings",
        69 to "payment",
        70 to "receipt",
        71 to "attach_money",
        // Creative icons (72-76)
        72 to "brush",
        73 to "music_note",
        74 to "camera",
        75 to "edit",
        76 to "design_services",
        // Transportation icons (77-79)
        77 to "directions_car",
        78 to "train",
        79 to "flight",
        // Entertainment icons (80-82)
        80 to "movie",
        81 to "tv",
        82 to "sports_esports",
        // Household icons (83-85)
        83 to "kitchen",
        84 to "build",
        85 to "iron",
        // Outdoor icons (86-88)
        86 to "park",
        87 to "landscape",
        88 to "terrain",
        // Technology icons (89-91)
        89 to "phone",
        90 to "computer",
        91 to "devices"
    )

    // Reverse mapping: icon name to resId (case-insensitive lookup)
    private val NAME_TO_RES_ID = RES_ID_TO_NAME.entries.associate { (k, v) -> v to k }

    /**
     * Convert local iconResId to server icon name.
     * Returns "health" (default) for unknown resIds.
     */
    fun toIconName(iconResId: Int): String {
        return RES_ID_TO_NAME[iconResId] ?: "health"
    }

    /**
     * Convert server icon name to local iconResId.
     * Returns 8 (health default) for unknown names.
     * Icon names are case-insensitive.
     */
    fun toIconResId(iconName: String): Int {
        return NAME_TO_RES_ID[iconName.lowercase()] ?: 8
    }

    /**
     * Check if a resId is a valid icon identifier (1-91).
     */
    fun isValidIconResId(resId: Int): Boolean = resId in 1..91

    /**
     * Check if an icon name is valid.
     * Case-insensitive check.
     */
    fun isValidIconName(name: String): Boolean = NAME_TO_RES_ID.containsKey(name.lowercase())
}