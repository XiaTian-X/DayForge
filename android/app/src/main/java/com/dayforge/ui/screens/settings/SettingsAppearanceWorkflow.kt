package com.dayforge.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.dayforge.data.appearance.ThemeCatalogContent
import com.dayforge.data.appearance.ThemeCatalogException
import com.dayforge.data.appearance.ThemeCatalogItem
import com.dayforge.data.appearance.ThemeDocuments
import com.dayforge.data.appearance.ValidatedTheme
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.widget.WidgetRefreshScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun ThemeVersionRef.choiceKey() = "$themeId:$revision"

/** Catalog identity and revision are retained in confirmations; display names never grant privileges. */
internal data class ThemeChoiceSummary(val item: ThemeCatalogItem, val catalogRevision: Long) {
    val ref get() = item.slot.ref
    val id get() = ref.choiceKey()
    val builtInSlug get() = item.builtIn?.slug
    val isCustom get() = item.builtIn == null
    val available get() = item.content is ThemeCatalogContent.Available
    val definition get() = (item.content as? ThemeCatalogContent.Available)?.definition
    val name get() = definition?.name ?: "${ref.themeId} · v${ref.revision}"
    val suitableForLight get() = item.builtIn?.suitableForLight ?: true
    val suitableForDark get() = item.builtIn?.suitableForDark ?: true
    fun primary(dark: Boolean): String? = definition?.let { (if (dark) it.dark else it.light).material.getValue("primary") }
}

/** Settings owns drafts/results; the controller and repository own active state. */
class SettingsAppearanceWorkflow @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val themes: DeviceThemeController
) {
    internal val themeState = themes.state
    private val documents = ThemeDocuments(context.contentResolver)
    private val mutations = Mutex()
    private val libraryReads = Mutex()
    private var listedRevision: Long? = null
    private val _choices = MutableStateFlow<List<ThemeChoiceSummary>>(emptyList())
    internal val choices = _choices.asStateFlow()
    private val _themeActionError = MutableStateFlow<Exception?>(null)
    val themeActionError = _themeActionError.asStateFlow()
    private val _themeImportProgress = MutableStateFlow(false)
    val themeImportProgress = _themeImportProgress.asStateFlow()
    private val _themeImportResult = MutableStateFlow<Result<String>?>(null)
    val themeImportResult = _themeImportResult.asStateFlow()
    private val _themePreview = MutableStateFlow<ValidatedTheme?>(null)
    internal val themePreview = _themePreview.asStateFlow()
    private val _themeExportProgress = MutableStateFlow(false)
    val themeExportProgress = _themeExportProgress.asStateFlow()
    private val _themeExportResult = MutableStateFlow<Result<String>?>(null)
    val themeExportResult = _themeExportResult.asStateFlow()
    private var pendingExport: ValidatedTheme? = null
    private var exportDestinationPending = false
    private val _themeDeleteResult = MutableStateFlow<Result<Unit>?>(null)
    val themeDeleteResult = _themeDeleteResult.asStateFlow()

    internal suspend fun refreshLibrary(force: Boolean = true) {
        try {
            libraryReads.withLock {
                if (force || themes.catalog()?.revision != listedRevision) {
                    val library = themes.library()
                    _choices.value = library.items.map { ThemeChoiceSummary(it, library.state.revision) }
                    listedRevision = library.state.revision
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _themeActionError.value = error
        }
    }

    suspend fun changeLanguage(code: String?) {
        preferencesManager.setLanguageCode(code)
        val locales = if (code == null) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.create(java.util.Locale.forLanguageTag(code))
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private suspend fun change(transform: (DeviceThemeSelection) -> DeviceThemeSelection) = mutations.withLock {
        _themeActionError.value = null
        try {
            val saved = themes.current().saved
            themes.select(saved.revision, transform(saved.selection))
            WidgetRefreshScheduler.request(context)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _themeActionError.value = error
        }
    }
    suspend fun changeTheme(mode: String?) = change { it.copy(mode = when (mode) {
        null -> DeviceThemeMode.SYSTEM
        "light" -> DeviceThemeMode.LIGHT
        "dark" -> DeviceThemeMode.DARK
        else -> throw IllegalArgumentException("THEME_MODE_UNSUPPORTED")
    }) }
    suspend fun changeLightColorTheme(id: String) = change { it.copy(light = choice(id).ref) }
    suspend fun changeDarkColorTheme(id: String) = change { it.copy(dark = choice(id).ref) }
    suspend fun changeCardColorStyle(style: String) = change { it.copy(cardStyle = when (style) {
        "follow_theme" -> DeviceCardStyle.FOLLOW_THEME
        "personalized" -> DeviceCardStyle.PERSONALIZED
        else -> throw IllegalArgumentException("THEME_CARD_STYLE_UNSUPPORTED")
    }) }
    private fun choice(id: String) = _choices.value.singleOrNull { it.id == id && it.available }
        ?: throw ThemeCatalogException("THEME_NOT_AVAILABLE")
    suspend fun setGlobalNotificationsEnabled(enabled: Boolean) = preferencesManager.setGlobalNotificationsEnabled(enabled)

    suspend fun importTheme(uri: Uri) = mutations.withLock {
        _themeImportProgress.value = true
        _themePreview.value = null
        _themeImportResult.value = null
        try {
            _themePreview.value = documents.preview(uri)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _themeImportResult.value = Result.failure(error)
        } finally { _themeImportProgress.value = false }
    }
    internal suspend fun themeImportPickerFailed(error: Exception) = mutations.withLock {
        if (error is CancellationException) throw error
        _themePreview.value = null
        _themeImportResult.value = Result.failure(error)
    }
    internal suspend fun confirmThemeImport(preview: ValidatedTheme) = mutations.withLock {
        if (_themePreview.value !== preview) return@withLock
        _themeImportProgress.value = true
        try {
            themes.install(preview)
            _themePreview.value = null
            _themeImportResult.value = Result.success(preview.definition.name)
            refreshLibrary()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _themeImportResult.value = Result.failure(error)
            // A confirmed installation can leave an owned journal after publication/disk failure.
            // Keep that entry visible for retry/deletion even if no ViewModel observer is active.
            refreshLibrary()
        } finally { _themeImportProgress.value = false }
    }
    fun cancelThemeImport() { if (!_themeImportProgress.value) _themePreview.value = null }

    /** Freeze the exact validated export before opening the external destination picker. */
    internal suspend fun prepareThemeExport(theme: ThemeChoiceSummary): String? = mutations.withLock {
        _themeExportProgress.value = true
        _themeExportResult.value = null
        try {
            if (exportDestinationPending) throw IllegalStateException("THEME_EXPORT_ALREADY_PENDING")
            val existing = pendingExport
            if (existing?.definition?.themeId == theme.ref.themeId && existing.definition.revision == theme.ref.revision) {
                exportDestinationPending = true
                return@withLock "theme-${theme.ref.themeId}-v${theme.ref.revision}.json"
            }
            pendingExport = null
            if (themes.catalog()?.revision != theme.catalogRevision) throw ThemeCatalogException("THEME_CATALOG_CONFLICT")
            pendingExport = themes.export(theme.ref)
            exportDestinationPending = true
            "theme-${theme.ref.themeId}-v${theme.ref.revision}.json"
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _themeExportResult.value = Result.failure(error)
            null
        } finally { _themeExportProgress.value = false }
    }
    suspend fun writeThemeExport(uri: Uri?) = mutations.withLock {
        exportDestinationPending = false
        if (uri == null) { pendingExport = null; return@withLock }
        _themeExportProgress.value = true
        _themeExportResult.value = null
        try {
            val snapshot = pendingExport ?: throw IOException("THEME_EXPORT_PREVIEW_MISSING")
            documents.write(uri, snapshot)
            pendingExport = null
            _themeExportResult.value = Result.success("dayforge.theme")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _themeExportResult.value = Result.failure(error)
        } finally { _themeExportProgress.value = false }
    }
    /** No picker callback will arrive when launching failed; retain frozen bytes for explicit retry. */
    internal suspend fun themeExportPickerFailed(error: Exception) = mutations.withLock {
        if (error is CancellationException) throw error
        exportDestinationPending = false
        _themeExportResult.value = Result.failure(error)
    }
    internal suspend fun deleteCustomTheme(theme: ThemeChoiceSummary) = mutations.withLock {
        try {
            themes.delete(theme.ref, theme.catalogRevision)
            _themeDeleteResult.value = Result.success(Unit)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _themeDeleteResult.value = Result.failure(error)
        } finally { refreshLibrary() }
    }
    fun dismissThemeActionError() { _themeActionError.value = null }
    fun dismissThemeImportResult() { _themeImportResult.value = null }
    fun dismissThemeExportResult() { _themeExportResult.value = null }
    fun dismissThemeDeleteResult() { _themeDeleteResult.value = null }
}
