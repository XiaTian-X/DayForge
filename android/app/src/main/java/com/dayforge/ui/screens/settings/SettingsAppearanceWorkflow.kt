package com.dayforge.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.model.GlobalColorTheme
import com.dayforge.domain.repository.CustomThemeRepository
import com.dayforge.domain.service.ThemeExportService
import com.dayforge.domain.service.ThemeImportService
import com.dayforge.domain.service.ThemeManager
import com.dayforge.widget.WidgetRefreshScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Owns settings appearance mutations and theme file workflow state. */
class SettingsAppearanceWorkflow @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val themeManager: ThemeManager,
    private val themeImportService: ThemeImportService,
    private val themeExportService: ThemeExportService,
    private val customThemeRepository: CustomThemeRepository
) {
    private val _themeImportProgress = MutableStateFlow(false)
    val themeImportProgress: StateFlow<Boolean> = _themeImportProgress.asStateFlow()

    private val _themeImportResult = MutableStateFlow<Result<GlobalColorTheme>?>(null)
    val themeImportResult: StateFlow<Result<GlobalColorTheme>?> = _themeImportResult.asStateFlow()

    private val _themeExportProgress = MutableStateFlow(false)
    val themeExportProgress: StateFlow<Boolean> = _themeExportProgress.asStateFlow()

    private val _themeExportResult = MutableStateFlow<Result<String>?>(null)
    val themeExportResult: StateFlow<Result<String>?> = _themeExportResult.asStateFlow()

    private val _themeDeleteResult = MutableStateFlow<Result<Unit>?>(null)
    val themeDeleteResult: StateFlow<Result<Unit>?> = _themeDeleteResult.asStateFlow()

    val allLightThemes: StateFlow<List<GlobalColorTheme>> = themeManager.lightThemes
    val allDarkThemes: StateFlow<List<GlobalColorTheme>> = themeManager.darkThemes

    suspend fun changeLanguage(code: String?) {
        preferencesManager.setLanguageCode(code)
        val locales = if (code == null) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.create(java.util.Locale.forLanguageTag(code))
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    suspend fun changeTheme(mode: String?) {
        preferencesManager.setThemeMode(mode)
        notifyWidgetsToRefresh()
    }

    suspend fun changeLightColorTheme(themeId: String) {
        preferencesManager.setLightColorTheme(themeId)
        notifyWidgetsToRefresh()
    }

    suspend fun changeDarkColorTheme(themeId: String) {
        preferencesManager.setDarkColorTheme(themeId)
        notifyWidgetsToRefresh()
    }

    suspend fun changeCardColorStyle(style: String) {
        preferencesManager.setCardColorStyle(style)
        notifyWidgetsToRefresh()
    }

    suspend fun setGlobalNotificationsEnabled(enabled: Boolean) {
        preferencesManager.setGlobalNotificationsEnabled(enabled)
    }

    suspend fun importTheme(uri: Uri) {
        _themeImportProgress.value = true
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                _themeImportProgress.value = false
                _themeImportResult.value = Result.failure(
                    Exception(context.getString(R.string.error_cannot_open_file))
                )
                return
            }

            val jsonString = inputStream.bufferedReader().use { it.readText() }
            _themeImportProgress.value = false
            _themeImportResult.value = themeImportService.importFromJson(jsonString)
        } catch (error: Exception) {
            _themeImportProgress.value = false
            _themeImportResult.value = Result.failure(
                Exception(context.getString(R.string.error_import_failed, error.message))
            )
        }
    }

    suspend fun exportTheme(themeId: String, includeGeneratedColors: Boolean = false): String? {
        _themeExportProgress.value = true
        val result = themeExportService.exportToJson(themeId, includeGeneratedColors)
        _themeExportProgress.value = false
        _themeExportResult.value = result
        return result.getOrNull()
    }

    suspend fun deleteCustomTheme(themeId: String) {
        if (themeManager.isPresetTheme(themeId)) {
            _themeDeleteResult.value = Result.failure(
                Exception(context.getString(R.string.theme_error_cannot_delete_preset))
            )
            return
        }

        val result = customThemeRepository.deleteTheme(themeId)
        if (result.isSuccess) themeManager.refresh()
        _themeDeleteResult.value = result
    }

    fun dismissThemeImportResult() {
        _themeImportResult.value = null
    }

    fun dismissThemeExportResult() {
        _themeExportResult.value = null
    }

    fun dismissThemeDeleteResult() {
        _themeDeleteResult.value = null
    }

    fun isPresetTheme(themeId: String): Boolean = themeManager.isPresetTheme(themeId)

    private fun notifyWidgetsToRefresh() {
        WidgetRefreshScheduler.request(context)
    }
}
