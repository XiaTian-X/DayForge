package com.dayforge.ui.screens.settings

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.model.SyncProgress
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.ConfigExportService
import com.dayforge.domain.service.ConfigImportService
import com.dayforge.domain.service.SyncManager
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.AccountLocalStateCleaner
import com.dayforge.domain.service.ThemeManager
import com.dayforge.domain.service.ThemeImportService
import com.dayforge.domain.service.ThemeExportService
import com.dayforge.domain.service.TimerElapsedCalculator
import com.dayforge.domain.model.GlobalColorTheme
import com.dayforge.domain.repository.CustomThemeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.dayforge.R
import com.dayforge.reminder.HabitReminderScheduler
import com.dayforge.widget.WidgetUpdateReceiver

/**
 * ViewModel for SettingsScreen.
 * Handles sync operations, network state, and authentication status.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncManager: SyncManager,
    private val tokenManager: TokenManager,
    private val preferencesManager: PreferencesManager,
    private val connectivityManager: ConnectivityManager,
    private val habitRepository: HabitRepository,
    private val habitDao: HabitDao,
    private val metricDao: MetricDao,
    private val timeLogDao: TimeLogDao,
    private val completionDao: CompletionDao,
    private val metricLogDao: MetricLogDao,
    private val linkDao: HabitMetricLinkDao,
    private val configExportService: ConfigExportService,
    private val configImportService: ConfigImportService,
    private val themeManager: ThemeManager,
    private val themeImportService: ThemeImportService,
    private val themeExportService: ThemeExportService,
    private val customThemeRepository: CustomThemeRepository,
    private val accountSessionCoordinator: AccountSessionCoordinator
) : ViewModel() {

    // UI State
    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private val _isOnline = MutableStateFlow(checkInitialNetworkState())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _showSyncSuccess = MutableStateFlow(false)
    val showSyncSuccess: StateFlow<Boolean> = _showSyncSuccess.asStateFlow()

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

    // Export state
    private val _exportProgress = MutableStateFlow(false)
    val exportProgress: StateFlow<Boolean> = _exportProgress.asStateFlow()

    private val _exportResult = MutableStateFlow<Result<String>?>(null)
    val exportResult: StateFlow<Result<String>?> = _exportResult.asStateFlow()

    // Import state
    private val _importProgress = MutableStateFlow(false)
    val importProgress: StateFlow<Boolean> = _importProgress.asStateFlow()

    data class ImportConfirmData(
        val uri: Uri,
        val importHabitCount: Int,
        val importMetricCount: Int,
        val importLinkCount: Int,
        val deleteHabitCount: Int,
        val deleteMetricCount: Int,
        val deleteCompletionCount: Int,
        val deleteTimeLogCount: Int,
        val deleteMetricLogCount: Int
    )

    private val _importConfirmData = MutableStateFlow<ImportConfirmData?>(null)
    val importConfirmData: StateFlow<ImportConfirmData?> = _importConfirmData.asStateFlow()

    private val _importResult = MutableStateFlow<Result<Unit>?>(null)
    val importResult: StateFlow<Result<Unit>?> = _importResult.asStateFlow()

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

    // Network callback for real-time network state
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isOnline.value = true
        }

        override fun onLost(network: Network) {
            _isOnline.value = connectivityManager.allNetworks.any { it != network }
        }
    }

    init {
        // Register network callback
        // No INTERNET capability is requested: a NAS may be reachable on a
        // Wi-Fi LAN without internet access. This API works from minSdk 26.
        val networkRequest = NetworkRequest.Builder().build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Observe sync progress from SyncManager
        viewModelScope.launch {
            syncManager.syncProgress.collect { progress ->
                _syncProgress.value = progress
                when (progress) {
                    is SyncProgress.Success -> {
                        _showSyncSuccess.value = true
                        // Reschedule reminders after successful sync (new habits may have bestTime)
                        try {
                            HabitReminderScheduler.rescheduleAllReminders(context)
                        } catch (e: Exception) {
                            // Log error but don't block sync success
                        }
                        // Auto-reset success after delay
                        kotlinx.coroutines.delay(1000)
                        _showSyncSuccess.value = false
                        syncManager.resetProgress()
                    }
                    is SyncProgress.Error -> {
                        _syncErrorMessage.value = if (progress.isNetworkFailure) {
                            context.getString(R.string.error_network_failed)
                        } else {
                            progress.message
                        }
                        _showSyncError.value = true
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectivityManager.unregisterNetworkCallback(networkCallback)
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
        viewModelScope.launch {
            val result = syncManager.sync()
            // Result is handled via syncProgress flow observation
        }
    }

    /**
     * Dismisses the sync error dialog.
     */
    fun dismissSyncError() {
        _showSyncError.value = false
        syncManager.resetProgress()
    }

    /**
     * Retries sync after error.
     */
    fun retrySync() {
        _showSyncError.value = false
        viewModelScope.launch {
            // A retry explicitly requested by the user also reactivates
            // quarantined operations; ordinary background sync leaves them
            // isolated so one bad row cannot block downloads.
            syncManager.retryRejectedChanges()
            syncManager.sync()
        }
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
        if (!_isOnline.value) {
            // Not online - show error, don't logout
            _syncErrorMessage.value = context.getString(R.string.error_network_failed)
            _showSyncError.value = true
            return
        }

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

    /**
     * Checks initial network state on ViewModel creation.
     */
    private fun checkInitialNetworkState(): Boolean {
        return connectivityManager.allNetworks.isNotEmpty()
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
        _exportProgress.value = true
        val result = configExportService.exportConfigToJson()
        _exportProgress.value = false
        // Only show error dialog on failure; success dialog not needed (user chose location)
        if (result.isFailure) {
            @Suppress("UNCHECKED_CAST")
            _exportResult.value = result as Result<String>
        }
        return result.getOrNull()
    }

    /**
     * Dismisses the export result dialog.
     */
    fun dismissExportResult() {
        _exportResult.value = null
    }

    /**
     * Prepares import by reading the file and showing confirmation dialog.
     * Also checks for active timer and validates the import file.
     */
    fun prepareImport(uri: Uri) {
        viewModelScope.launch {
            // 1. Check for active timer first
            if (showActiveTimerWarningIfNeeded()) return@launch

            try {
                // 2. Read file content
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    _importResult.value = Result.failure(Exception(context.getString(R.string.error_cannot_open_file)))
                    return@launch
                }

                val jsonString = inputStream.bufferedReader().use { it.readText() }

                // 3. Parse JSON to get counts
                val json = Json { ignoreUnknownKeys = true }
                val configDto = json.decodeFromString<com.dayforge.data.export.dto.ConfigExportDto>(jsonString)

                // 4. Validate for duplicate habit names
                val habitNames = configDto.habits.map { it.name }
                val duplicateNames = habitNames.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                if (duplicateNames.isNotEmpty()) {
                    _importResult.value = Result.failure(
                        Exception(context.getString(R.string.error_duplicate_habit_names, duplicateNames.joinToString(", ")))
                    )
                    return@launch
                }

                // 5. Get current database counts for deletion preview
                val deleteHabitCount = habitDao.getAllHabitsOnce().size
                val deleteMetricCount = metricDao.getAllMetricsOnce().size
                val deleteCompletionCount = completionDao.countAll()
                val deleteTimeLogCount = timeLogDao.countAll()
                val deleteMetricLogCount = metricLogDao.countAll()

                // 6. Build confirmation data
                _importConfirmData.value = ImportConfirmData(
                    uri = uri,
                    importHabitCount = configDto.habits.size,
                    importMetricCount = configDto.metrics.size,
                    importLinkCount = configDto.links.size,
                    deleteHabitCount = deleteHabitCount,
                    deleteMetricCount = deleteMetricCount,
                    deleteCompletionCount = deleteCompletionCount,
                    deleteTimeLogCount = deleteTimeLogCount,
                    deleteMetricLogCount = deleteMetricLogCount
                )
            } catch (e: Exception) {
                _importResult.value = Result.failure(Exception(context.getString(R.string.error_read_file_failed, e.message)))
            }
        }
    }

    /**
     * Confirms and executes the import.
     */
    fun confirmImport() {
        val confirmData = _importConfirmData.value ?: return

        viewModelScope.launch {
            _importProgress.value = true

            try {
                // Read file content again
                val inputStream = context.contentResolver.openInputStream(confirmData.uri)
                if (inputStream == null) {
                    _importProgress.value = false
                    _importResult.value = Result.failure(Exception(context.getString(R.string.error_cannot_open_file)))
                    _importConfirmData.value = null
                    return@launch
                }

                val jsonString = inputStream.bufferedReader().use { it.readText() }
                val result = configImportService.importConfig(jsonString)

                // Schedule reminders for imported habits with bestTime set
                if (result.isSuccess) {
                    try {
                        HabitReminderScheduler.rescheduleAllReminders(context)
                    } catch (e: Exception) {
                        // Log error but don't fail the import
                    }
                }

                _importProgress.value = false
                _importResult.value = result
                _importConfirmData.value = null
            } catch (e: Exception) {
                _importProgress.value = false
                _importResult.value = Result.failure(Exception(context.getString(R.string.error_import_failed, e.message)))
                _importConfirmData.value = null
            }
        }
    }

    /**
     * Cancels the import confirmation.
     */
    fun cancelImport() {
        _importConfirmData.value = null
    }

    /**
     * Dismisses the import result dialog.
     */
    fun dismissImportResult() {
        _importResult.value = null
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
            // 1. Persist to DataStore
            preferencesManager.setLanguageCode(code)

            // 2. Apply to AppCompatDelegate - this will trigger Activity recreation
            if (code == null) {
                // System default - reset to system locale by passing empty locale list
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                val localeList = LocaleListCompat.create(java.util.Locale.forLanguageTag(code))
                AppCompatDelegate.setApplicationLocales(localeList)
            }
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
            preferencesManager.setThemeMode(mode)
            // Notify widgets to refresh colors (theme mode affects dark/light color scheme selection)
            notifyWidgetsToRefresh()
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
            preferencesManager.setLightColorTheme(themeId)
            // Notify widgets to refresh colors
            notifyWidgetsToRefresh()
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
            preferencesManager.setDarkColorTheme(themeId)
            // Notify widgets to refresh colors
            notifyWidgetsToRefresh()
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
            preferencesManager.setCardColorStyle(style)
            // Notify widgets to refresh colors
            notifyWidgetsToRefresh()
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
            preferencesManager.setGlobalNotificationsEnabled(enabled)
        }
    }

    /**
     * Sends broadcast to notify widgets to refresh their colors.
     * Per WIDGET-COLOR-05: Widgets refresh within ~100ms when settings change.
     */
    private fun notifyWidgetsToRefresh() {
        val intent = Intent(WidgetUpdateReceiver.ACTION_DATA_CHANGED)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    // ========== Theme Import/Export ==========

    // Theme import/export state
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

    // Available themes lists (preset + custom) - observed from ThemeManager StateFlow
    val allLightThemes: StateFlow<List<GlobalColorTheme>> = themeManager.lightThemes
    val allDarkThemes: StateFlow<List<GlobalColorTheme>> = themeManager.darkThemes

    /**
     * Imports a theme from a JSON file URI.
     *
     * @param uri The content URI of the JSON file
     */
    fun importTheme(uri: Uri) {
        viewModelScope.launch {
            _themeImportProgress.value = true
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    _themeImportProgress.value = false
                    _themeImportResult.value = Result.failure(
                        Exception(context.getString(R.string.error_cannot_open_file))
                    )
                    return@launch
                }

                val jsonString = inputStream.bufferedReader().use { it.readText() }
                val result = themeImportService.importFromJson(jsonString)

                _themeImportProgress.value = false
                _themeImportResult.value = result
            } catch (e: Exception) {
                _themeImportProgress.value = false
                _themeImportResult.value = Result.failure(
                    Exception(context.getString(R.string.error_import_failed, e.message))
                )
            }
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
        _themeExportProgress.value = true
        val result = themeExportService.exportToJson(themeId, includeGeneratedColors)
        _themeExportProgress.value = false
        _themeExportResult.value = result
        return result.getOrNull()
    }

    /**
     * Deletes a custom theme by ID.
     * Only custom themes can be deleted.
     *
     * @param themeId The theme ID to delete
     */
    fun deleteCustomTheme(themeId: String) {
        viewModelScope.launch {
            // Check if it's a preset theme
            if (themeManager.isPresetTheme(themeId)) {
                _themeDeleteResult.value = Result.failure(
                    Exception(context.getString(R.string.theme_error_cannot_delete_preset))
                )
                return@launch
            }

            val result = customThemeRepository.deleteTheme(themeId)
            if (result.isSuccess) {
                themeManager.refresh()
            }
            _themeDeleteResult.value = result
        }
    }

    /**
     * Dismisses the theme import result dialog.
     */
    fun dismissThemeImportResult() {
        _themeImportResult.value = null
    }

    /**
     * Dismisses the theme export result dialog.
     */
    fun dismissThemeExportResult() {
        _themeExportResult.value = null
    }

    /**
     * Dismisses the theme delete result dialog.
     */
    fun dismissThemeDeleteResult() {
        _themeDeleteResult.value = null
    }

    /**
     * Checks if a theme is a preset (built-in) theme.
     */
    fun isPresetTheme(themeId: String): Boolean {
        return themeManager.isPresetTheme(themeId)
    }
}
