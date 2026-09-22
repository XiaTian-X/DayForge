package com.dayforge.ui.screens.settings

import android.content.Context
import com.dayforge.data.api.NetworkMonitor
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.model.SyncProgress
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.SyncManager
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.AccountLocalStateCleaner
import com.dayforge.domain.service.TimerElapsedCalculator
import com.dayforge.domain.model.GlobalColorTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dagger.hilt.android.qualifiers.ApplicationContext
import com.dayforge.R
import com.dayforge.reminder.HabitReminderScheduler
import javax.inject.Inject

/**
 * ViewModel for SettingsScreen.
 * Handles sync operations, network state, and authentication status.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val syncManager: SyncManager,
    private val tokenManager: TokenManager,
    private val preferencesManager: PreferencesManager,
    private val networkMonitor: NetworkMonitor,
    private val habitRepository: HabitRepository,
    private val habitDao: HabitDao,
    private val timeLogDao: TimeLogDao,
    private val configWorkflow: SettingsConfigWorkflow,
    private val appearanceWorkflow: SettingsAppearanceWorkflow,
    private val accountSessionCoordinator: AccountSessionCoordinator
) : ViewModel() {

    // UI State
    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    val isOnline: StateFlow<Boolean> = networkMonitor.state
        .map { it.mayBeConnected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, networkMonitor.state.value.mayBeConnected)

    private val _showSyncError = MutableStateFlow(false)
    val showSyncError: StateFlow<Boolean> = _showSyncError.asStateFlow()

    private val _syncErrorMessage = MutableStateFlow("")
    val syncErrorMessage: StateFlow<String> = _syncErrorMessage.asStateFlow()

    // Logout-in-progress state
    private val _isLoggingOut = MutableStateFlow(false)
    val isLoggingOut: StateFlow<Boolean> = _isLoggingOut.asStateFlow()

    // Active timer dialog state
    private val _showActiveTimerDialog = MutableStateFlow(false)
    val showActiveTimerDialog: StateFlow<Boolean> = _showActiveTimerDialog.asStateFlow()

    private val _activeTimerHabitName = MutableStateFlow("")
    val activeTimerHabitName: StateFlow<String> = _activeTimerHabitName.asStateFlow()

    private val _activeTimerDuration = MutableStateFlow(0)
    val activeTimerDuration: StateFlow<Int> = _activeTimerDuration.asStateFlow()

    val exportProgress = configWorkflow.exportProgress
    val exportResult = configWorkflow.exportResult
    val importProgress = configWorkflow.importProgress
    val importConfirmData = configWorkflow.importConfirmData
    val importResult = configWorkflow.importResult

    // Last sync time from SyncManager
    val lastSyncTime: StateFlow<Long?> = syncManager.getLastSyncTime()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeServerUrl: StateFlow<String?> = preferencesManager.activeServerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val rejectedChanges: StateFlow<List<SyncOutboxEntity>> = syncManager.observeRejectedChanges()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncConflicts: StateFlow<List<SyncConflictEntity>> = syncManager.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rejectedTimerCommands: StateFlow<List<TimerCommandEntity>> =
        syncManager.observeRejectedTimerCommands()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Login state - derived from access token presence
    val isLoggedIn: StateFlow<Boolean> = tokenManager.accessToken
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isAdmin: StateFlow<Boolean> = tokenManager.isAdmin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val canEditStructure: StateFlow<Boolean> = syncManager.canEditStructure()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isPrimaryEditor: StateFlow<Boolean> = syncManager.isPrimaryEditor()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // User email from TokenManager
    val userEmail: StateFlow<String?> = tokenManager.userEmail
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Language preference state
    val languageCode: StateFlow<String?> = preferencesManager.languageCode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Theme preference state
    val themeMode: StateFlow<String?> = preferencesManager.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Global color theme state (THEME-01, THEME-02)
    val lightColorThemeId: StateFlow<String> = preferencesManager.lightColorThemeId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ocean")

    val darkColorThemeId: StateFlow<String> = preferencesManager.darkColorThemeId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "dusk")

    // Card color style preference state (CARD-01, CARD-09)
    val cardColorStyle: StateFlow<String> = preferencesManager.cardColorStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "follow_theme")

    // Global notifications enabled state (NOTIFY-04)
    val globalNotificationsEnabled: StateFlow<Boolean> = preferencesManager.globalNotificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val themeImportProgress = appearanceWorkflow.themeImportProgress
    val themeImportResult = appearanceWorkflow.themeImportResult
    val themeExportProgress = appearanceWorkflow.themeExportProgress
    val themeExportResult = appearanceWorkflow.themeExportResult
    val themeDeleteResult = appearanceWorkflow.themeDeleteResult
    val allLightThemes: StateFlow<List<GlobalColorTheme>> = appearanceWorkflow.allLightThemes
    val allDarkThemes: StateFlow<List<GlobalColorTheme>> = appearanceWorkflow.allDarkThemes

    private var manualSyncJob: Job? = null

    init {
        // Global progress is durable process state used for non-blocking status only.
        // Modal feedback is owned by the settings action that initiated a sync.
        viewModelScope.launch {
            syncManager.syncProgress.collect { progress ->
                _syncProgress.value = progress
                if (progress is SyncProgress.Success) {
                    try {
                        HabitReminderScheduler.rescheduleAllReminders(context)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Reminder refresh is best-effort and does not change sync success.
                    }
                }
            }
        }
    }

    /**
     * Triggers manual sync operation.
     * Always attempts the configured endpoint. A Wi-Fi LAN without internet may
     * still reach a local NAS, so Android's INTERNET capability is not a gate.
     * Active timers no longer block ordinary plan/fact synchronization.
     */
    fun sync() {
        performSync()
    }

    fun makeCurrentDevicePrimary() {
        viewModelScope.launch {
            runCatching { syncManager.makeCurrentDevicePrimary() }
                .onFailure { error ->
                    _syncErrorMessage.value = error.message ?: context.getString(R.string.sync_device_role_failed)
                    _showSyncError.value = true
                }
        }
    }

    /**
     * Dismisses the active timer dialog without syncing.
     */
    fun dismissActiveTimerDialog() {
        _showActiveTimerDialog.value = false
    }

    /**
     * Performs the actual sync operation.
     */
    private fun performSync() {
        startManualSync(retryRejected = false)
    }

    /**
     * Dismisses the sync error dialog.
     */
    fun dismissSyncError() {
        _showSyncError.value = false
    }

    /**
     * Retries sync after error.
     */
    fun retrySync() {
        _showSyncError.value = false
        startManualSync(retryRejected = true)
    }

    private fun startManualSync(retryRejected: Boolean) {
        if (manualSyncJob?.isActive == true) return
        manualSyncJob = viewModelScope.launch {
            val failure = try {
                if (retryRejected) {
                    // A retry explicitly requested by the user also reactivates
                    // quarantined operations; ordinary background sync leaves them
                    // isolated so one bad row cannot block downloads.
                    syncManager.retryRejectedChanges()
                }
                syncManager.sync().exceptionOrNull()?.also { error ->
                    if (error is CancellationException) throw error
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            }
            failure?.let(::showManualSyncIssue)
        }
    }

    private fun showManualSyncIssue(error: Throwable) {
        _syncErrorMessage.value = if (error.hasIOExceptionCause()) {
            context.getString(R.string.error_network_failed)
        } else {
            error.message ?: context.getString(R.string.sync_rejected_unknown_error)
        }
        _showSyncError.value = true
    }

    fun retryRejectedChange(id: Long) {
        viewModelScope.launch { syncManager.retryRejectedChange(id) }
    }

    fun retryRejectedTimerCommand(id: Long) {
        viewModelScope.launch { syncManager.retryRejectedTimerCommand(id) }
    }

    fun cancelRejectedTimerCommandAndUseServer(id: Long) {
        viewModelScope.launch {
            runCatching {
                syncManager.cancelRejectedTimerCommandAndUseServer(id)
                syncManager.sync().getOrThrow()
            }
                .onFailure(::showSyncIssue)
        }
    }

    fun discardRejectedChange(id: Long) {
        viewModelScope.launch { syncManager.discardRejectedChange(id) }
    }

    fun resolveConflictUseServer(id: Long) {
        viewModelScope.launch {
            runCatching { syncManager.resolveConflictUseServer(id) }
                .onFailure(::showSyncIssue)
        }
    }

    fun resolveConflictUseLocal(id: Long) {
        viewModelScope.launch {
            runCatching { syncManager.resolveConflictUseLocal(id) }
                .onFailure(::showSyncIssue)
        }
    }

    private fun showSyncIssue(error: Throwable) {
        _syncErrorMessage.value = error.message ?: context.getString(R.string.sync_rejected_unknown_error)
        _showSyncError.value = true
    }

    /**
     * Logs out the user by clearing tokens and local data.
     */
    fun logout() {
        viewModelScope.launch {
            if (showActiveTimerWarningIfNeeded()) return@launch
            accountSessionCoordinator.exclusive {
                clearAccountData()
            }
        }
    }

    /**
     * Syncs data then logs out the user.
     * Called when user chooses "Sync then logout" option.
     */
    fun syncAndLogout(onComplete: () -> Unit) {
        viewModelScope.launch {
            if (showActiveTimerWarningIfNeeded()) return@launch
            _isLoggingOut.value = true
            val result = syncManager.syncAndThen { clearAccountData() }
            _isLoggingOut.value = false

            if (result.isSuccess) {
                onComplete()
            } else {
                // Sync failed - show error, don't logout
                val exception = result.exceptionOrNull()
                _syncErrorMessage.value = if (exception.hasIOExceptionCause()) {
                    context.getString(R.string.error_network_failed)
                } else {
                    context.getString(
                        R.string.error_sync_failed,
                        exception?.message ?: context.getString(R.string.time_never)
                    )
                }
                _showSyncError.value = true
            }
        }
    }

    private fun Throwable?.hasIOExceptionCause(): Boolean {
        var current = this
        while (current != null) {
            if (current is IOException) return true
            current = current.cause
        }
        return false
    }

    /**
     * Logs out directly without syncing.
     * Called when user chooses "Direct logout" option.
     */
    fun directLogout(onComplete: () -> Unit) {
        viewModelScope.launch {
            if (showActiveTimerWarningIfNeeded()) return@launch
            accountSessionCoordinator.exclusive { clearAccountData() }
            onComplete()
        }
    }

    private suspend fun clearAccountData() {
        AccountLocalStateCleaner.clear(context) {
            habitRepository.clearAllData(context)
            preferencesManager.clearAccountScopedPreferences()
        }
        preferencesManager.clearLastSyncTimestamp()
        tokenManager.clearTokens()
        syncManager.resetProgress()
    }

    private suspend fun showActiveTimerWarningIfNeeded(): Boolean {
        val activeTimer = timeLogDao.getActiveTimeLog() ?: return false
        val habit = habitDao.getHabitById(activeTimer.habitId)
        _activeTimerHabitName.value = habit?.name ?: context.getString(R.string.error_unknown_habit)
        _activeTimerDuration.value = TimerElapsedCalculator.elapsedSeconds(activeTimer, context)
        _showActiveTimerDialog.value = true
        return true
    }

    /**
     * Formats timestamp for display.
     */
    fun formatLastSyncTime(timestamp: Long?): String {
        if (timestamp == null) return context.getString(R.string.time_never)

        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> context.getString(R.string.time_just_now)
            diff < 3600_000 -> context.getString(R.string.time_minutes_ago, diff / 60_000)
            diff < 86400_000 -> context.getString(R.string.time_hours_ago, diff / 3600_000)
            diff < 604800_000 -> context.getString(R.string.time_days_ago, diff / 86400_000)
            else -> {
                val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                format.format(Date(timestamp))
            }
        }
    }

    // ==================== Export/Import Config ====================

    /**
     * Exports habit configuration to a JSON string.
     * Used with CreateDocument to let user choose save location.
     * Only shows error dialog on failure; success is implicit (user chose location).
     *
     * @return JSON string or null on failure
     */
    suspend fun exportConfigToJson(): String? {
        return configWorkflow.exportConfigToJson()
    }

    /**
     * Dismisses the export result dialog.
     */
    fun dismissExportResult() {
        configWorkflow.dismissExportResult()
    }

    /**
     * Prepares import by reading the file and showing confirmation dialog.
     * Also checks for active timer and validates the import file.
     */
    fun prepareImport(uri: Uri) {
        viewModelScope.launch {
            if (showActiveTimerWarningIfNeeded()) return@launch
            configWorkflow.prepareImport(uri)
        }
    }

    /**
     * Confirms and executes the import.
     */
    fun confirmImport() {
        viewModelScope.launch {
            configWorkflow.confirmImport()
        }
    }

    /**
     * Cancels the import confirmation.
     */
    fun cancelImport() {
        configWorkflow.cancelImport()
    }

    /**
     * Dismisses the import result dialog.
     */
    fun dismissImportResult() {
        configWorkflow.dismissImportResult()
    }

    // ==================== Language Management ====================

    /**
     * Changes the app language and persists the preference.
     * Per LOC-03: Uses AppCompatDelegate.setApplicationLocales() for immediate effect.
     * No activity recreate needed - Compose UI auto-refreshes with new string resources.
     *
     * @param code The language code: "zh" for Chinese, "en" for English, null for system default
     */
    fun changeLanguage(code: String?) {
        viewModelScope.launch {
            appearanceWorkflow.changeLanguage(code)
        }
    }

    // ==================== Theme Management ====================

    /**
     * Changes the app theme and persists the preference.
     * Per THEME-05: Theme applied immediately via Flow recomposition - no Activity recreate needed.
     * Per WIDGET-COLOR-05: Notify widgets to refresh colors when theme mode changes.
     *
     * @param mode The theme mode: "light", "dark", or null for system default
     */
    fun changeTheme(mode: String?) {
        viewModelScope.launch {
            appearanceWorkflow.changeTheme(mode)
        }
    }

    // ========== Global Color Theme Management ==========

    /**
     * Changes the light mode color theme preference.
     * Per THEME-01: Preference persists immediately via Flow recomposition.
     * Per WIDGET-COLOR-05: Notify widgets to refresh colors when light theme changes.
     *
     * @param themeId The theme ID ("ocean", "nature", "vibrant")
     */
    fun changeLightColorTheme(themeId: String) {
        viewModelScope.launch {
            appearanceWorkflow.changeLightColorTheme(themeId)
        }
    }

    /**
     * Changes the dark mode color theme preference.
     * Per THEME-02: Preference persists immediately via Flow recomposition.
     * Per WIDGET-COLOR-05: Notify widgets to refresh colors when dark theme changes.
     *
     * @param themeId The theme ID ("dusk", "forest", "coral", "oled")
     */
    fun changeDarkColorTheme(themeId: String) {
        viewModelScope.launch {
            appearanceWorkflow.changeDarkColorTheme(themeId)
        }
    }

    // ========== Card Color Style Management ==========

    /**
     * Changes the card color style preference.
     * Per CARD-09: Flow-based reactive update triggers instant UI refresh.
     * Per WIDGET-COLOR-05: Notify widgets to refresh colors when card color style changes.
     *
     * @param style The card color style ("follow_theme" or "personalized")
     */
    fun changeCardColorStyle(style: String) {
        viewModelScope.launch {
            appearanceWorkflow.changeCardColorStyle(style)
        }
    }

    // ========== Global Notifications Management (NOTIFY-04) ==========

    /**
     * Sets the global notifications enabled preference.
     * Per NOTIFY-04: When disabled, all habit reminders are suppressed.
     *
     * @param enabled True to enable notifications globally, false to disable all
     */
    fun setGlobalNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appearanceWorkflow.setGlobalNotificationsEnabled(enabled)
        }
    }

    // ========== Theme Import/Export ==========

    /**
     * Imports a theme from a JSON file URI.
     *
     * @param uri The content URI of the JSON file
     */
    fun importTheme(uri: Uri) {
        viewModelScope.launch {
            appearanceWorkflow.importTheme(uri)
        }
    }

    /**
     * Exports a theme to JSON string.
     *
     * @param themeId The theme ID to export
     * @param includeGeneratedColors If true, generate all 25 colors from seedColor (for template export)
     * @return JSON string or null on failure
     */
    suspend fun exportTheme(themeId: String, includeGeneratedColors: Boolean = false): String? {
        return appearanceWorkflow.exportTheme(themeId, includeGeneratedColors)
    }

    /**
     * Deletes a custom theme by ID.
     * Only custom themes can be deleted.
     *
     * @param themeId The theme ID to delete
     */
    fun deleteCustomTheme(themeId: String) {
        viewModelScope.launch {
            appearanceWorkflow.deleteCustomTheme(themeId)
        }
    }

    /**
     * Dismisses the theme import result dialog.
     */
    fun dismissThemeImportResult() {
        appearanceWorkflow.dismissThemeImportResult()
    }

    /**
     * Dismisses the theme export result dialog.
     */
    fun dismissThemeExportResult() {
        appearanceWorkflow.dismissThemeExportResult()
    }

    /**
     * Dismisses the theme delete result dialog.
     */
    fun dismissThemeDeleteResult() {
        appearanceWorkflow.dismissThemeDeleteResult()
    }

    /**
     * Checks if a theme is a preset (built-in) theme.
     */
    fun isPresetTheme(themeId: String): Boolean {
        return appearanceWorkflow.isPresetTheme(themeId)
    }
}
