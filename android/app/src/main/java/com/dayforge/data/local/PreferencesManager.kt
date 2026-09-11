package com.dayforge.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages sync-related preferences using DataStore for persistent storage.
 * Stores last sync timestamp and other sync metadata.
 */
@Singleton
class PreferencesManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val LAST_SYNC_TIMESTAMP_KEY = longPreferencesKey("last_sync_timestamp")
        private val BATTERY_GUIDANCE_SHOWN = booleanPreferencesKey("battery_guidance_shown")
        private val PENDING_METRIC_HABITS_KEY = stringPreferencesKey("pending_metric_habits")
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val REMOTE_SERVER_URL_KEY = stringPreferencesKey("remote_server_url")
        private val ACTIVE_SERVER_URL_KEY = stringPreferencesKey("active_server_url")
        private val EXPANDED_PARENT_UUIDS_KEY = stringPreferencesKey("expanded_parent_uuids")
        private val LAST_SEEN_DATE_KEY = longPreferencesKey("last_seen_date")

        /**
         * Generate preference key for "never ask again" per habit.
         * Per D-18: User can suppress future metric prompts for specific habits.
         */
        private fun neverAskAgainKey(habitId: Long) = booleanPreferencesKey("never_ask_metric_$habitId")

        private val LANGUAGE_CODE_KEY = stringPreferencesKey("language_code")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val LIGHT_COLOR_THEME_KEY = stringPreferencesKey("light_color_theme")
        private val DARK_COLOR_THEME_KEY = stringPreferencesKey("dark_color_theme")
        private val CARD_COLOR_STYLE_KEY = stringPreferencesKey("card_color_style")
        private val FOCUS_MODE_ENABLED_KEY = booleanPreferencesKey("focus_mode_enabled")
        private val FILTER_MODE_KEY = stringPreferencesKey("filter_mode")

        // Notification settings keys (NOTIFY-04)
        private val GLOBAL_NOTIFICATIONS_ENABLED_KEY = booleanPreferencesKey("global_notifications_enabled")
        private fun habitNotificationKey(habitId: Long) = booleanPreferencesKey("habit_notification_$habitId")
    }

    /**
     * Flow of the last sync timestamp.
     * Returns null if no sync has been performed.
     */
    val lastSyncTimestamp: Flow<Long?> = dataStore.data.map { preferences ->
        preferences[LAST_SYNC_TIMESTAMP_KEY]
    }

    /**
     * Saves the last sync timestamp to DataStore.
     * Called after a successful sync operation.
     */
    suspend fun setLastSyncTimestamp(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[LAST_SYNC_TIMESTAMP_KEY] = timestamp
        }
    }

    /**
     * Clears the last sync timestamp.
     * Called on logout or when sync state needs to be reset.
     */
    suspend fun clearLastSyncTimestamp() {
        dataStore.edit { preferences ->
            preferences.remove(LAST_SYNC_TIMESTAMP_KEY)
        }
    }

    // ========== Language Preference Management ==========

    /**
     * Flow of the user's preferred language code.
     * Returns null if using system default language.
     * Valid values: "zh" for Chinese, "en" for English, null for system default.
     */
    val languageCode: Flow<String?> = dataStore.data.map { preferences ->
        preferences[LANGUAGE_CODE_KEY]
    }

    /**
     * Saves the user's preferred language code to DataStore.
     * Per LOC-03: Language preference persists across app restarts.
     *
     * @param code The language code ("zh", "en", or null for system default)
     */
    suspend fun setLanguageCode(code: String?) {
        dataStore.edit { preferences ->
            if (code == null) {
                preferences.remove(LANGUAGE_CODE_KEY)
            } else {
                preferences[LANGUAGE_CODE_KEY] = code
            }
        }
    }

    // ========== Theme Mode Preference Management ==========

    /**
     * Flow of the user's preferred theme mode.
     * Returns null if using system default theme.
     * Valid values: "light" for Light mode, "dark" for Dark mode, null for System Default.
     */
    val themeMode: Flow<String?> = dataStore.data.map { preferences ->
        preferences[THEME_MODE_KEY]
    }

    /**
     * Saves the user's preferred theme mode to DataStore.
     * Per THEME-02: Theme preference persists across app restarts.
     *
     * @param mode The theme mode ("light", "dark", or null for system default)
     */
    suspend fun setThemeMode(mode: String?) {
        dataStore.edit { preferences ->
            if (mode == null) {
                preferences.remove(THEME_MODE_KEY)
            } else {
                preferences[THEME_MODE_KEY] = mode
            }
        }
    }

    // ========== Light/Dark Color Theme Management ==========

    /**
     * Flow of the user's light mode color theme preference.
     * Per THEME-01: Light mode theme preference persists in DataStore.
     * Returns "ocean" as the default light theme.
     */
    val lightColorThemeId: Flow<String> = dataStore.data.map { preferences ->
        preferences[LIGHT_COLOR_THEME_KEY] ?: "ocean"
    }

    /**
     * Flow of the user's dark mode color theme preference.
     * Per THEME-02: Dark mode theme preference persists in DataStore.
     * Returns "dusk" as the default dark theme.
     */
    val darkColorThemeId: Flow<String> = dataStore.data.map { preferences ->
        preferences[DARK_COLOR_THEME_KEY] ?: "dusk"
    }

    // ========== Card Color Style Management ==========

    /**
     * Flow of the user's preferred card color style.
     * Per CARD-02: Card color style preference persists in DataStore.
     * Returns "follow_theme" as the default (blends with theme colors).
     */
    val cardColorStyle: Flow<String> = dataStore.data.map { preferences ->
        preferences[CARD_COLOR_STYLE_KEY] ?: "follow_theme"
    }

    /**
     * Saves the user's light mode color theme preference to DataStore.
     * Per THEME-01: Preference persists across app restarts.
     *
     * @param themeId The theme ID (e.g., "ocean", "nature", "vibrant")
     */
    suspend fun setLightColorTheme(themeId: String) {
        dataStore.edit { preferences ->
            preferences[LIGHT_COLOR_THEME_KEY] = themeId
        }
    }

    /**
     * Saves the user's dark mode color theme preference to DataStore.
     * Per THEME-02: Preference persists across app restarts.
     *
     * @param themeId The theme ID (e.g., "dusk", "forest", "coral", "oled")
     */
    suspend fun setDarkColorTheme(themeId: String) {
        dataStore.edit { preferences ->
            preferences[DARK_COLOR_THEME_KEY] = themeId
        }
    }

    /**
     * Saves the user's preferred card color style to DataStore.
     * Per CARD-02: Preference persists across app restarts.
     *
     * @param style The card color style ("follow_theme" or "personalized")
     */
    suspend fun setCardColorStyle(style: String) {
        dataStore.edit { preferences ->
            preferences[CARD_COLOR_STYLE_KEY] = style
        }
    }

    /**
     * Get the appropriate theme ID based on current theme mode.
     * Per THEME-03: Current mode determines which theme ID to apply.
     *
     * @param themeMode The current theme mode ("light", "dark", or null for system)
     * @param isSystemInDarkTheme Whether system is currently in dark theme
     * @return The appropriate theme ID (lightColorThemeId or darkColorThemeId)
     */
    suspend fun getColorThemeIdForMode(themeMode: String?, isSystemInDarkTheme: Boolean): String {
        val useDarkTheme = when (themeMode) {
            "light" -> false
            "dark" -> true
            null -> isSystemInDarkTheme  // System default
            else -> isSystemInDarkTheme  // Fallback
        }
        return if (useDarkTheme) darkColorThemeId.first() else lightColorThemeId.first()
    }

    // ========== Filter Mode Management ==========

    /**
     * Flow of the current filter mode for habit list display.
     * Returns "all" as the default (show all active habits).
     * Valid values: "all", "time_window", "checkable", "terminated".
     */
    val filterMode: Flow<String> = dataStore.data.map { preferences ->
        preferences[FILTER_MODE_KEY] ?: "all"
    }

    /**
     * Saves the filter mode preference to DataStore.
     * Preference persists across app restarts.
     *
     * @param mode The filter mode ("all", "time_window", "checkable", "terminated")
     */
    suspend fun setFilterMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[FILTER_MODE_KEY] = mode
        }
    }

    // ========== Notification Settings Management (NOTIFY-04) ==========

    /**
     * Flow of whether global notifications are enabled.
     * Per NOTIFY-04: Global notification preference persists across app sessions.
     * Returns true as the default (notifications enabled).
     */
    val globalNotificationsEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[GLOBAL_NOTIFICATIONS_ENABLED_KEY] ?: true  // Default: enabled
    }

    /**
     * Saves the global notifications preference to DataStore.
     * Per NOTIFY-04: Preference persists across app restarts.
     *
     * @param enabled True to enable notifications globally, false to disable all
     */
    suspend fun setGlobalNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[GLOBAL_NOTIFICATIONS_ENABLED_KEY] = enabled
        }
    }

    /**
     * Flow of whether notifications are enabled for a specific habit.
     * Per NOTIFY-04: Per-habit notification preference persists in DataStore.
     * Returns true as the default (notifications enabled for the habit).
     *
     * @param habitId The ID of the habit
     * @return Flow of Boolean, true if notifications enabled (default)
     */
    fun getHabitNotificationEnabled(habitId: Long): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[habitNotificationKey(habitId)] ?: true  // Default: enabled
    }

    /**
     * Set notification preference for a specific habit.
     * Per NOTIFY-04: User can disable notifications per-habit.
     *
     * @param habitId The ID of the habit
     * @param enabled True to enable notifications for this habit, false to disable
     */
    suspend fun setHabitNotificationEnabled(habitId: Long, enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[habitNotificationKey(habitId)] = enabled
        }
    }

    /**
     * Flow of whether battery optimization guidance has been shown.
     * Returns false if guidance has never been shown.
     */
    val hasShownBatteryGuidance: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[BATTERY_GUIDANCE_SHOWN] ?: false
    }

    /**
     * Marks battery optimization guidance as shown.
     * Called after the first timer start shows the guidance dialog.
     */
    suspend fun setBatteryGuidanceShown() {
        dataStore.edit { preferences ->
            preferences[BATTERY_GUIDANCE_SHOWN] = true
        }
    }

    // ========== Server URL Management ==========

    /**
     * Flow of the custom server URL for API synchronization.
     * Returns null if using the default server URL.
     */
    val serverUrl: Flow<String?> = dataStore.data.map { preferences ->
        preferences[SERVER_URL_KEY]
    }
    val remoteServerUrl: Flow<String?> = dataStore.data.map { it[REMOTE_SERVER_URL_KEY] }
    val activeServerUrl: Flow<String?> = dataStore.data.map { it[ACTIVE_SERVER_URL_KEY] }

    /**
     * Saves a custom server URL to DataStore.
     * Pass null to reset to the default server URL.
     *
     * @param url The custom server URL (e.g., "https://api.example.com/api/v1/") or null for default
     */
    suspend fun setServerUrl(url: String?) {
        dataStore.edit { preferences ->
            if (url.isNullOrBlank()) {
                preferences.remove(SERVER_URL_KEY)
            } else {
                preferences[SERVER_URL_KEY] = url
            }
            preferences.remove(ACTIVE_SERVER_URL_KEY)
        }
    }

    suspend fun setRemoteServerUrl(url: String?) {
        dataStore.edit { preferences ->
            if (url.isNullOrBlank()) preferences.remove(REMOTE_SERVER_URL_KEY)
            else preferences[REMOTE_SERVER_URL_KEY] = url
            preferences.remove(ACTIVE_SERVER_URL_KEY)
        }
    }

    suspend fun setActiveServerUrl(url: String?) {
        dataStore.edit { preferences ->
            if (url.isNullOrBlank()) preferences.remove(ACTIVE_SERVER_URL_KEY)
            else preferences[ACTIVE_SERVER_URL_KEY] = url
        }
    }

    /**
     * Flow of whether "never ask again" is set for a habit's metric prompt.
     * Per D-18: Returns false if not set (user will be prompted).
     *
     * @param habitId The ID of the habit
     * @return Flow of Boolean, false if not set
     */
    fun getNeverAskAgain(habitId: Long): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[neverAskAgainKey(habitId)] ?: false
    }

    /**
     * Set "never ask again" preference for a habit.
     * Per D-18: User can suppress future prompts for this habit.
     *
     * @param habitId The ID of the habit
     * @param value True to suppress future prompts, false to re-enable
     */
    suspend fun setNeverAskAgain(habitId: Long, value: Boolean) {
        dataStore.edit { prefs ->
            prefs[neverAskAgainKey(habitId)] = value
        }
    }

    // ========== Pending Metric Habits Management ==========

    /**
     * Flow of habit IDs that have pending metric prompts.
     * Each habit in this set should show a "record metric" button.
     *
     * Stored as comma-separated string of habit IDs.
     */
    val pendingMetricHabits: Flow<Set<Long>> = dataStore.data.map { prefs ->
        val raw = prefs[PENDING_METRIC_HABITS_KEY]
        if (raw.isNullOrBlank()) {
            emptySet()
        } else {
            raw.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .toSet()
        }
    }

    /**
     * Add a habit to the pending metric prompts set.
     * Called when a timer completes and the habit has linked metrics with promptOnComplete=true.
     *
     * @param habitId The ID of the habit that needs metric recording
     */
    suspend fun addPendingMetricHabit(habitId: Long) {
        dataStore.edit { prefs ->
            val current = prefs[PENDING_METRIC_HABITS_KEY]
            val currentSet = if (current.isNullOrBlank()) {
                emptySet()
            } else {
                current.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
            }
            if (habitId !in currentSet) {
                val newSet = currentSet + habitId
                prefs[PENDING_METRIC_HABITS_KEY] = newSet.joinToString(",")
            }
        }
    }

    /**
     * Remove a habit from the pending metric prompts set.
     * Called when the user records the metric or starts a new timer.
     *
     * @param habitId The ID of the habit to remove
     */
    suspend fun removePendingMetricHabit(habitId: Long) {
        dataStore.edit { prefs ->
            val current = prefs[PENDING_METRIC_HABITS_KEY]
            val currentSet = if (current.isNullOrBlank()) {
                emptySet()
            } else {
                current.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
            }
            val newSet = currentSet - habitId
            prefs[PENDING_METRIC_HABITS_KEY] = if (newSet.isEmpty()) "" else newSet.joinToString(",")
        }
    }

    /**
     * Clear all pending metric prompts.
     * Called on logout or when resetting app state.
     */
    suspend fun clearPendingMetricHabits() {
        dataStore.edit { prefs ->
            prefs.remove(PENDING_METRIC_HABITS_KEY)
        }
    }

    /**
     * Clears values that refer to habits owned by the current account.
     *
     * Local Room IDs can be reused after an account switch, so retaining these keys
     * could apply the previous account's prompt and notification choices to a new user.
     * Device-wide preferences such as language, theme and server URL are preserved.
     */
    suspend fun clearAccountScopedPreferences() {
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { key ->
                    key.name.startsWith("never_ask_metric_") ||
                        key.name.startsWith("habit_notification_")
                }
                .forEach { key -> preferences.remove(key) }
            preferences.remove(PENDING_METRIC_HABITS_KEY)
            preferences.remove(EXPANDED_PARENT_UUIDS_KEY)
        }
    }

    // ========== Expanded Parent UUIDs Management ==========

    /**
     * Flow of expanded parent habit UUIDs.
     * Used to persist expand/collapse state across navigation and app restart.
     *
     * Stored as comma-separated string of UUIDs.
     */
    val expandedParentUuids: Flow<Set<String>> = dataStore.data.map { prefs ->
        val raw = prefs[EXPANDED_PARENT_UUIDS_KEY]
        if (raw.isNullOrBlank()) emptySet()
        else raw.split(",").toSet()
    }

    /**
     * Set the expanded parent habit UUIDs.
     * Called when a parent habit is expanded or collapsed.
     *
     * @param uuids Set of UUIDs for expanded parent habits
     */
    suspend fun setExpandedParentUuids(uuids: Set<String>) {
        dataStore.edit { prefs ->
            prefs[EXPANDED_PARENT_UUIDS_KEY] = uuids.joinToString(",")
        }
    }

    // ========== Date Change Refresh Management ==========

    /**
     * Flow that emits when the date changes.
     * Used to trigger UI refresh when user opens the app on a new day.
     *
     * Emits the current date in epoch days. When this value changes,
     * subscribers should refresh their data.
     */
    val dateChangeTrigger: Flow<Long> = dataStore.data.map { prefs ->
        prefs[LAST_SEEN_DATE_KEY] ?: 0L
    }

    /**
     * Updates the last seen date to today.
     * Returns true if the date changed (indicating a new day).
     *
     * @return true if date changed, false if same day
     */
    suspend fun updateLastSeenDate(todayEpochDays: Long): Boolean {
        var dateChanged = false
        dataStore.edit { prefs ->
            val lastSeen = prefs[LAST_SEEN_DATE_KEY] ?: 0L
            if (lastSeen != todayEpochDays) {
                dateChanged = true
                prefs[LAST_SEEN_DATE_KEY] = todayEpochDays
            }
        }
        return dateChanged
    }
}
