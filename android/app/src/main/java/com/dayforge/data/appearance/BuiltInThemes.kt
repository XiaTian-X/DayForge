package com.dayforge.data.appearance

import android.content.res.AssetManager
import java.io.InputStream
import java.util.Collections

/** Stable identities, not user-controlled names. New definitions require a new revision. */
internal enum class BuiltInTheme(
    val slug: String,
    val displayName: String,
    val themeId: String,
    val suitableForLight: Boolean,
    val suitableForDark: Boolean
) {
    OCEAN("ocean", "Ocean", "df000000-0000-4000-8000-000000000001", true, false),
    NATURE("nature", "Nature", "df000000-0000-4000-8000-000000000002", true, false),
    VIBRANT("vibrant", "Vibrant", "df000000-0000-4000-8000-000000000003", true, false),
    DUSK("dusk", "Dusk", "df000000-0000-4000-8000-000000000004", false, true),
    FOREST("forest", "Forest", "df000000-0000-4000-8000-000000000005", false, true),
    CORAL("coral", "Coral", "df000000-0000-4000-8000-000000000006", false, true),
    OLED("oled", "OLED", "df000000-0000-4000-8000-000000000007", false, true);

    val revision: Int get() = 1
    val assetPath: String get() = "appearance/themes/$slug-v$revision.json"
}

/**
 * Reads complete APK palettes on IO without generating colors or publishing a partial catalog.
 * Validates the entire input set before installation. A later file failure can leave already installed
 * immutable versions; retry validates them. No deletion, preference changes or UI activation here.
 */
internal class BuiltInThemes(private val openAsset: (String) -> InputStream) {
    constructor(assets: AssetManager) : this({ path -> assets.open(path) })

    suspend fun readAll(): List<ValidatedTheme> = Collections.unmodifiableList(BuiltInTheme.entries.map { entry ->
        ValidatedTheme.read { openAsset(entry.assetPath) }.also {
            val theme = it.definition
            if (theme.themeId != entry.themeId || theme.revision != entry.revision || theme.name != entry.displayName) {
                throw ThemeInputException("THEME_BUILTIN_IDENTITY")
            }
        }
    })

    suspend fun installAll(repository: ThemeFileRepository): List<ValidatedTheme> {
        val validated = readAll()
        return Collections.unmodifiableList(validated.map { repository.install(it) })
    }
}
