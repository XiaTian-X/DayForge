package com.dayforge.ui.screens.settings

/** Display only. Full UUID + revision remain the key for selection and every operation.
 * Grow colliding suffixes rather than presenting an ambiguous fixed eight-character identifier.
 */
internal fun themeIdentitySuffixes(themes: List<ThemeChoiceSummary>): Map<String, String> {
    val ids = themes.filter { it.isCustom }.map { it.ref.themeId }.distinct()
    val compact = ids.associateWith { it.replace("-", "") }
    return ids.associateWith { id ->
        val value = compact.getValue(id)
        val length = (8..32 step 4).first { count ->
            val suffix = value.takeLast(count)
            compact.none { (other, candidate) -> other != id && candidate.endsWith(suffix) }
        }
        value.takeLast(length)
    }
}
