package com.dayforge.domain.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.DeviceThemeLoadState
import com.dayforge.data.appearance.DeviceThemeRepository
import com.dayforge.data.appearance.ThemeFileRepository
import com.dayforge.data.appearance.ValidatedTheme
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.LoadedDeviceTheme
import com.dayforge.domain.appearance.ThemeVersionRef
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** Process-scoped resolved palettes shared by app and widgets; no per-tick palette generation. */
@Singleton
class DeviceThemeController internal constructor(
    private val repository: DeviceThemeRepository,
    private val scope: CoroutineScope
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        preferences: DataStore<Preferences>
    ) : this(DeviceThemeRepository(preferences, ThemeFileRepository(context.filesDir), BuiltInThemes(context.assets)),
        CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val reload = MutableStateFlow(Any())
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val state = reload.flatMapLatest { repository.observe() }
        .stateIn(scope, SharingStarted.Eagerly, DeviceThemeLoadState.Loading)

    internal fun retry() { reload.value = Any() }

    /** Cached colors, but check the authoritative metadata so a just-committed change is not missed. */
    internal suspend fun current(): LoadedDeviceTheme {
        val loaded = when (val status = state.first { it !is DeviceThemeLoadState.Loading }) {
            is DeviceThemeLoadState.Ready -> status.theme
            is DeviceThemeLoadState.Failed -> throw status.error
            DeviceThemeLoadState.Loading -> error("Unreachable loading state")
        }
        return if (repository.savedSelection() == loaded.saved) loaded else repository.load()
    }

    internal suspend fun select(expectedRevision: Long, choice: DeviceThemeSelection) = repository.select(expectedRevision, choice)
    internal suspend fun library() = repository.library()
    internal suspend fun catalog() = repository.catalog()
    internal suspend fun preview(openSource: () -> InputStream) = repository.preview(openSource)
    internal suspend fun install(preview: ValidatedTheme, expectedRevision: Long? = null) =
        repository.install(preview, expectedRevision)
    internal suspend fun export(ref: ThemeVersionRef) = repository.export(ref)
    internal suspend fun delete(ref: ThemeVersionRef, expectedRevision: Long) = repository.delete(ref, expectedRevision)
    internal suspend fun recover() = repository.recover()
    /** Tests/explicit owner shutdown must join before closing their DataStore or deleting files. */
    internal suspend fun close() { scope.coroutineContext[Job]?.cancelAndJoin() }
}
