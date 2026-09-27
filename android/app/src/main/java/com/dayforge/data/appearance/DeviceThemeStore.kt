package com.dayforge.data.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.LoadedDeviceTheme
import com.dayforge.domain.appearance.ResolvedTheme
import com.dayforge.domain.appearance.SavedThemeSelection
import com.dayforge.domain.model.ContractIntegerSerializer
import com.dayforge.domain.model.ContractLongSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class ThemeSelectionException(val code: String) : IllegalArgumentException(code)

/**
 * Use the app's one existing Preferences DataStore, never a second instance for its file.
 * Version files are immutable and have no delete API. Validate them before committing references;
 * this is not a transaction spanning files and DataStore. Later corruption is an explicit load error.
 * No fallback, migration, UI activation or writes to legacy preferences occur here.
 */
internal class DeviceThemeStore(
    private val preferences: DataStore<Preferences>,
    private val themes: ThemeFileRepository
) {
    fun selections(): Flow<SavedThemeSelection?> = preferences.data
        .map { decode(it[KEY]) }.distinctUntilChanged()

    /** Observe at the owner lifecycle, not on every recomposition/timer tick. Errors propagate. */
    fun resolvedThemes(): Flow<LoadedDeviceTheme?> = selections().map { it?.let { saved -> load(saved) } }

    suspend fun read(): SavedThemeSelection? = selections().first()

    suspend fun load(): LoadedDeviceTheme? = read()?.let { load(it) }

    /** expectedRevision=0 means explicitly uninitialized. Even a no-op must validate both files. */
    suspend fun select(expectedRevision: Long, selection: DeviceThemeSelection): LoadedDeviceTheme {
        require(expectedRevision >= 0)
        currentCoroutineContext().ensureActive()
        val light = themes.read(selection.light.themeId, selection.light.revision)
        val dark = if (selection.light == selection.dark) light
            else themes.read(selection.dark.themeId, selection.dark.revision)
        val committed = preferences.edit { values ->
            currentCoroutineContext().ensureActive()
            val previous = decode(values[KEY])
            if ((previous?.revision ?: 0) != expectedRevision) throw ThemeSelectionException("THEME_SELECTION_CONFLICT")
            if (previous?.selection != selection) {
                if (expectedRevision == Long.MAX_VALUE) throw ThemeSelectionException("THEME_SELECTION_EXHAUSTED")
                values[KEY] = Json.encodeToString(StoredSelection(1, expectedRevision + 1, selection))
            }
        }
        // edit returns this transaction's committed value, not a later writer's current preference.
        val saved = checkNotNull(decode(committed[KEY]))
        return LoadedDeviceTheme(saved, ResolvedTheme.from(light.definition, false), ResolvedTheme.from(dark.definition, true))
    }

    private suspend fun load(saved: SavedThemeSelection): LoadedDeviceTheme {
        val choice = saved.selection
        val light = themes.read(choice.light.themeId, choice.light.revision)
        val dark = if (choice.light == choice.dark) light else themes.read(choice.dark.themeId, choice.dark.revision)
        return LoadedDeviceTheme(saved, ResolvedTheme.from(light.definition, false), ResolvedTheme.from(dark.definition, true))
    }

    private suspend fun decode(value: String?): SavedThemeSelection? = withContext(Dispatchers.Default) {
        if (value == null) return@withContext null
        if (value.length > LIMIT) throw ThemeSelectionException("THEME_SELECTION_LIMIT")
        val context = currentCoroutineContext()
        try {
            val text = strictAppearanceJson(value.toByteArray(Charsets.UTF_8), LIMIT, { context.ensureActive() }) {
                throw ThemeSelectionException("THEME_SELECTION_$it")
            }
            val stored = Json.decodeFromString<StoredSelection>(text)
            if (stored.formatVersion != 1) throw ThemeSelectionException("THEME_SELECTION_VERSION")
            SavedThemeSelection(stored.revision, stored.selection)
        } catch (error: ThemeSelectionException) { throw error }
        catch (_: SerializationException) { throw ThemeSelectionException("THEME_SELECTION_JSON") }
        catch (_: IllegalArgumentException) { throw ThemeSelectionException("THEME_SELECTION_INVALID") }
    }

    @Serializable
    private data class StoredSelection(
        @Serializable(with = ContractIntegerSerializer::class) val formatVersion: Int,
        @Serializable(with = ContractLongSerializer::class) val revision: Long,
        val selection: DeviceThemeSelection
    )

    private companion object {
        val KEY = stringPreferencesKey("appearance_theme_selection_v1")
        const val LIMIT = 4096
    }
}
