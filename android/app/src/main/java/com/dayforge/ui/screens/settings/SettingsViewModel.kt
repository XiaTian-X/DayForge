package com.dayforge.ui.screens.settings

import android.content.Context
import com.dayforge.data.api.NetworkMonitor
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
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
import com.dayforge.data.appearance.DeviceThemeLoadState
import com.dayforge.data.appearance.ValidatedTheme
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
import com.dayforge.domain.service.isSyncTransportFailure
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
    private val accountSessionCoordinator: AccountSessionCoordinator,
    savedStateHandle: SavedStateHandle = SavedStateHandle()
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
    internal val configFileState = configWorkflow.v2?.state ?: MutableStateFlow(
        ConfigV2UiState(profile = ConfigFileProfile.LEGACY)).asStateFlow()

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

    internal val themeState = appearanceWorkflow.themeState
    internal val themeChoices = appearanceWorkflow.choices
    internal val themePreview = appearanceWorkflow.themePreview
    val themeActionError = appearanceWorkflow.themeActionError

    // Global notifications enabled state (NOTIFY-04)
    val globalNotificationsEnabled: StateFlow<Boolean> = preferencesManager.globalNotificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val themeImportProgress = appearanceWorkflow.themeImportProgress
    val themeImportResult = appearanceWorkflow.themeImportResult
    val themeExportProgress = appearanceWorkflow.themeExportProgress
    val themeExportResult = appearanceWorkflow.themeExportResult
    val themeDeleteResult = appearanceWorkflow.themeDeleteResult
    private val themeEditor = appearanceWorkflow.editor(savedStateHandle)
    internal val themeEditorState = themeEditor.state

    private var manualSyncJob: Job? = null

    init {
        viewModelScope.launch { configWorkflow.v2?.observe() }
        viewModelScope.launch { themeEditor.restore() }
        viewModelScope.launch {
            themeState.collect { if (it is DeviceThemeLoadState.Ready) appearanceWorkflow.refreshLibrary(force = false) }
        }
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
            try { syncManager.makeCurrentDevicePrimary() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
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
                // Version/account discovery owns the retry policy; never mutate v4 queues before dispatch.
                val result = if (retryRejected) syncManager.retrySync() else syncManager.sync()
                result.exceptionOrNull()?.also { error ->
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
        _syncErrorMessage.value = if (error.isSyncTransportFailure()) {
            context.getString(R.string.error_network_failed)
        } else {
            error.message ?: context.getString(R.string.sync_rejected_unknown_error)
        }
        _showSyncError.value = true
    }

    fun retryRejectedChange(id: Long) {
        runSyncIssueAction { syncManager.retryRejectedChange(id) }
    }

    fun retryRejectedTimerCommand(id: Long) {
        runSyncIssueAction { syncManager.retryRejectedTimerCommand(id) }
    }

    fun cancelRejectedTimerCommandAndUseServer(id: Long) {
        runSyncIssueAction {
            syncManager.cancelRejectedTimerCommandAndUseServer(id)
            syncManager.sync().getOrThrow()
        }
    }

    fun discardRejectedChange(id: Long) {
        runSyncIssueAction { syncManager.discardRejectedChange(id) }
    }

    fun resolveConflictUseServer(id: Long) {
        runSyncIssueAction { syncManager.resolveConflictUseServer(id) }
    }

    fun resolveConflictUseLocal(id: Long) {
        runSyncIssueAction { syncManager.resolveConflictUseLocal(id) }
    }

    /** A protected/stale action is feedback, not an uncaught coroutine failure; cancellation stays cancellation. */
    private fun runSyncIssueAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showSyncIssue(error)
            }
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
                _syncErrorMessage.value = if (exception.isSyncTransportFailure()) {
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
        if (configWorkflow.v2?.allowLegacy() == false) return null
        val content = configWorkflow.exportConfigToJson()
        return if (configWorkflow.v2?.allowLegacy() == false) null else content
    }

    internal suspend fun beginConfigExport() = configWorkflow.v2?.beginExport(requireSelection = true) == true
    internal fun openConfigExportChoices() { viewModelScope.launch { configWorkflow.v2?.openExportChoices() } }
    internal fun selectConfigItem(id: String, checked: Boolean) { viewModelScope.launch { configWorkflow.v2?.selectItem(id, checked) } }
    internal fun selectConfigTheme(ref: com.dayforge.domain.appearance.ThemeVersionRef, checked: Boolean) {
        viewModelScope.launch { configWorkflow.v2?.selectTheme(ref, checked) }
    }
    internal suspend fun beginConfigImport(recover: Boolean = false) = configWorkflow.v2?.beginImport(recover) == true
    internal fun writeConfigExport(uri: Uri?) { viewModelScope.launch { configWorkflow.v2?.writeExport(uri) } }
    internal fun readConfigImport(uri: Uri?) { viewModelScope.launch { configWorkflow.v2?.readImport(uri) } }
    internal fun confirmConfigImport() { viewModelScope.launch {
        configWorkflow.v2?.confirmImport()
        appearanceWorkflow.refreshLibrary(force = true)
    } }
    internal fun abandonConfigImport() { viewModelScope.launch { configWorkflow.v2?.abandonPrepared() } }
    internal fun dismissConfigPreview() { viewModelScope.launch { configWorkflow.v2?.dismissPreview() } }
    internal fun dismissConfigMessage() { viewModelScope.launch { configWorkflow.v2?.dismissMessage() } }
    internal fun configPickerFailed() { viewModelScope.launch { configWorkflow.v2?.pickerFailed() } }
    internal fun refreshConfigState() { viewModelScope.launch { configWorkflow.v2?.refresh() } }

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
            if (configWorkflow.v2?.allowLegacy() == false) return@launch
            if (showActiveTimerWarningIfNeeded()) return@launch
            configWorkflow.prepareImport(uri)
            if (configWorkflow.v2?.allowLegacy() == false) configWorkflow.cancelImport()
        }
    }

    /**
     * Confirms and executes the import.
     */
    fun confirmImport() {
        viewModelScope.launch {
            if (configWorkflow.v2?.allowLegacy() == false) {
                configWorkflow.cancelImport(); return@launch
            }
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

    internal fun beginThemeEdit(theme: ThemeChoiceSummary) { viewModelScope.launch { themeEditor.begin(theme) } }
    internal fun renameEditedTheme(name: String) = themeEditor.rename(name)
    internal fun changeEditedColor(field: com.dayforge.domain.appearance.ThemeColorField, value: String) = themeEditor.color(field, value)
    internal fun resetEditedColor(field: com.dayforge.domain.appearance.ThemeColorField) = themeEditor.reset(field)
    internal fun previewEditedTheme() { viewModelScope.launch { themeEditor.prepare() } }
    internal fun confirmEditedTheme(preview: ValidatedTheme) { viewModelScope.launch {
        themeEditor.confirm(preview)
        appearanceWorkflow.refreshLibrary()
    } }
    internal fun backToThemeEdit() = themeEditor.backToEdit()
    internal fun cancelThemeEdit() {
        themeEditor.cancel()
        viewModelScope.launch { appearanceWorkflow.refreshLibrary() }
    }
    internal fun dismissThemeEditResult() = themeEditor.dismissResult()

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
    internal fun themeImportPickerFailed(error: Exception) {
        viewModelScope.launch { appearanceWorkflow.themeImportPickerFailed(error) }
    }

    /** Freeze a complete validated theme before launching the destination picker. */
    internal suspend fun prepareThemeExport(theme: ThemeChoiceSummary) = appearanceWorkflow.prepareThemeExport(theme)
    internal suspend fun themeExportPickerFailed(error: Exception) = appearanceWorkflow.themeExportPickerFailed(error)
    fun writeThemeExport(uri: Uri?) { viewModelScope.launch { appearanceWorkflow.writeThemeExport(uri) } }
    internal fun confirmThemeImport(preview: ValidatedTheme) {
        viewModelScope.launch { appearanceWorkflow.confirmThemeImport(preview) }
    }
    fun cancelThemeImport() = appearanceWorkflow.cancelThemeImport()
    fun dismissThemeActionError() = appearanceWorkflow.dismissThemeActionError()

    /** Delete the exact version and catalog revision shown in the confirmation. */
    internal fun deleteCustomTheme(theme: ThemeChoiceSummary) {
        viewModelScope.launch {
            appearanceWorkflow.deleteCustomTheme(theme)
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

}
