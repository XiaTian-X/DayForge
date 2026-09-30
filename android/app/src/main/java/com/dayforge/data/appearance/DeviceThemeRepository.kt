package com.dayforge.data.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.LoadedDeviceTheme
import com.dayforge.domain.appearance.ThemeVersionRef
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Loading is not an empty/default selection; a failed saved selection remains available for repair. */
internal sealed interface DeviceThemeLoadState {
    data object Loading : DeviceThemeLoadState
    data class Ready(val theme: LoadedDeviceTheme) : DeviceThemeLoadState
    data class Failed(val error: Exception) : DeviceThemeLoadState
}

/**
 * Lifecycle-neutral entry for the Activity, settings and cold widgets. It owns no background scope,
 * creates no DataStore, and never reads legacy seeds. UI owners collect once and retry explicitly.
 * The shared process controller owns collection for production app and widget consumers.
 */
internal class DeviceThemeRepository(
    preferences: DataStore<Preferences>,
    files: ThemeFileRepository,
    builtIns: BuiltInThemes
) {
    private val catalog = ThemeCatalogRepository(preferences, files, builtIns)
    private val selection = DeviceThemeStore(preferences, files)

    /** Initialize only genuinely absent metadata; recovery touches confirmed journal operations only. */
    suspend fun initialize(): LoadedDeviceTheme = initialization.withLock {
        catalog.initialize()
        catalog.recover()
        selection.load() ?: selection.select(0, defaults())
    }

    /** A new collection retries; cancellation stays cancellation, never an import/load error. */
    fun observe(): Flow<DeviceThemeLoadState> = flow<DeviceThemeLoadState> {
        emit(DeviceThemeLoadState.Loading)
        initialize()
        emitAll(selection.resolvedThemes().map { loaded ->
            DeviceThemeLoadState.Ready(loaded ?: throw ThemeSelectionException("THEME_SELECTION_UNINITIALIZED"))
        })
    }.catch { error ->
        if (error is CancellationException || error !is Exception) throw error
        emit(DeviceThemeLoadState.Failed(error))
    }

    /** No automatic defaults, recovery, or install during subsequent explicit reloads. */
    suspend fun load(): LoadedDeviceTheme = selection.load()
        ?: throw ThemeSelectionException("THEME_SELECTION_UNINITIALIZED")

    /** Exact revision from the visible snapshot; a concurrent selection must not be overwritten. */
    suspend fun select(expectedRevision: Long, choice: DeviceThemeSelection): LoadedDeviceTheme {
        if (catalog.state() == null) throw ThemeCatalogException("THEME_CATALOG_UNINITIALIZED")
        return selection.select(expectedRevision, choice)
    }

    /** Read once, bounded and frozen. Preparing/cancelling a preview cannot install or change selection. */
    suspend fun preview(openSource: () -> InputStream): ValidatedTheme = ValidatedTheme.read(openSource)

    suspend fun install(preview: ValidatedTheme): ThemeCatalogState = catalog.install(preview)
    suspend fun export(ref: ThemeVersionRef): ValidatedTheme = catalog.export(ref)
    suspend fun delete(ref: ThemeVersionRef, expectedCatalogRevision: Long): ThemeCatalogState =
        catalog.delete(ref, expectedCatalogRevision)
    suspend fun recover(): ThemeCatalogState = catalog.recover()
    suspend fun catalog(): ThemeCatalogState? = catalog.state()
    suspend fun library(): ThemeCatalogListing = catalog.listing()
    suspend fun savedSelection() = selection.read()

    private companion object {
        // App and widget can cold-start concurrently, including through separate facade instances.
        val initialization = Mutex()
        fun defaults() = DeviceThemeSelection(
            ThemeVersionRef(BuiltInTheme.OCEAN.themeId, BuiltInTheme.OCEAN.revision),
            ThemeVersionRef(BuiltInTheme.DUSK.themeId, BuiltInTheme.DUSK.revision),
            DeviceThemeMode.SYSTEM,
            DeviceCardStyle.FOLLOW_THEME
        )
    }
}
