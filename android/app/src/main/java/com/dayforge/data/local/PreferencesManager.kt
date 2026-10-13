package com.dayforge.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import com.dayforge.domain.model.StableHabitBinding
import com.dayforge.domain.service.DeviceCalendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages sync-related preferences using DataStore for persistent storage.
 * Stores last sync timestamp and other sync metadata.
 */
@Singleton
class PreferencesManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    val calendar: DeviceCalendar = DeviceCalendar(),
    private val tokens: TokenManager? = null
) {
    init { require(tokens == null || tokens.usesPreferences(dataStore)) }
    internal fun usesPreferences(value: DataStore<Preferences>) = dataStore === value
    private suspend fun editOrdinary(block: suspend (MutablePreferences) -> Unit): Preferences = dataStore.edit {
        ReplicaSwitchGuard.requireOpen(it)
        block(it)
    }
    companion object {
        private val LAST_SYNC_TIMESTAMP_KEY = longPreferencesKey("last_sync_timestamp")
        private val BATTERY_GUIDANCE_SHOWN = booleanPreferencesKey("battery_guidance_shown")
        private val PENDING_METRIC_HABITS_KEY = stringPreferencesKey("pending_metric_habits")
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val REMOTE_SERVER_URL_KEY = stringPreferencesKey("remote_server_url")
        private val ACTIVE_SERVER_URL_KEY = stringPreferencesKey("active_server_url")
        private val EXPANDED_PARENT_UUIDS_KEY = stringPreferencesKey("expanded_parent_uuids")
        private val LAST_SEEN_DATE_KEY = longPreferencesKey("last_seen_date")

        private val LANGUAGE_CODE_KEY = stringPreferencesKey("language_code")
        private val FOCUS_MODE_ENABLED_KEY = booleanPreferencesKey("focus_mode_enabled")
        private val FILTER_MODE_KEY = stringPreferencesKey("filter_mode")

        // Notification settings keys (NOTIFY-04)
        private val GLOBAL_NOTIFICATIONS_ENABLED_KEY = booleanPreferencesKey("global_notifications_enabled")
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
        editOrdinary { preferences ->
            preferences[LAST_SYNC_TIMESTAMP_KEY] = timestamp
        }
    }

    /**
     * Clears the last sync timestamp.
     * Called on logout or when sync state needs to be reset.
     */
    suspend fun clearLastSyncTimestamp() {
        editOrdinary { preferences ->
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
        editOrdinary { preferences ->
            if (code == null) {
                preferences.remove(LANGUAGE_CODE_KEY)
            } else {
                preferences[LANGUAGE_CODE_KEY] = code
            }
        }
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
        editOrdinary { preferences ->
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
        editOrdinary { preferences ->
            preferences[GLOBAL_NOTIFICATIONS_ENABLED_KEY] = enabled
        }
    }

    /**
     * Flow of whether notifications are enabled for a specific habit.
     * Per NOTIFY-04: Per-habit notification preference persists in DataStore.
     * Returns true as the default (notifications enabled for the habit).
     *
     * @param habitUuid The original Room-verified UUID, never a reusable numeric ID
     * @return Flow of Boolean, true if notifications enabled (default)
     */
    internal fun getHabitNotificationEnabled(session: LocalDataSession, habitUuid: String): Flow<Boolean> =
        habitPreference(session, habitUuid).map { it.notifications }

    /**
     * Set notification preference for a specific habit.
     * Per NOTIFY-04: User can disable notifications per-habit.
     *
     * @param habitUuid The original Room-verified UUID
     * @param enabled True to enable notifications for this habit, false to disable
     */
    internal suspend fun setHabitNotificationEnabled(session: LocalDataSession, habitUuid: String, enabled: Boolean) =
        editHabitPreference(session, habitUuid) { it.copy(notifications = enabled) }

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
        editOrdinary { preferences ->
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
        editOrdinary { preferences ->
            if (url.isNullOrBlank()) {
                preferences.remove(SERVER_URL_KEY)
            } else {
                preferences[SERVER_URL_KEY] = url
            }
            preferences.remove(ACTIVE_SERVER_URL_KEY)
        }
    }

    suspend fun setRemoteServerUrl(url: String?) {
        editOrdinary { preferences ->
            if (url.isNullOrBlank()) preferences.remove(REMOTE_SERVER_URL_KEY)
            else preferences[REMOTE_SERVER_URL_KEY] = url
            preferences.remove(ACTIVE_SERVER_URL_KEY)
        }
    }

    suspend fun setActiveServerUrl(url: String?) {
        editOrdinary { preferences ->
            if (url.isNullOrBlank()) preferences.remove(ACTIVE_SERVER_URL_KEY)
            else preferences[ACTIVE_SERVER_URL_KEY] = url
        }
    }

    /**
     * Flow of whether "never ask again" is set for a habit's metric prompt.
     * Per D-18: Returns false if not set (user will be prompted).
     *
     * @param habitUuid The original Room-verified UUID
     * @return Flow of Boolean, false if not set
     */
    internal fun getNeverAskAgain(session: LocalDataSession, habitUuid: String): Flow<Boolean> =
        habitPreference(session, habitUuid).map { it.neverAsk }

    /**
     * Set "never ask again" preference for a habit.
     * Per D-18: User can suppress future prompts for this habit.
     *
     * @param habitUuid The original Room-verified UUID
     * @param value True to suppress future prompts, false to re-enable
     */
    internal suspend fun setNeverAskAgain(session: LocalDataSession, habitUuid: String, value: Boolean) =
        editHabitPreference(session, habitUuid) { it.copy(neverAsk = value) }

    // ========== Pending Metric Habits Management ==========

    /**
     * Current-replica completion hints for actual Room UUIDs. The stable record is scoped to
     * account/server/habit identity; its pending hint additionally belongs to the original epoch.
     */
    internal fun pendingMetricHabitUuids(session: LocalDataSession, habitUuids: Set<String>): Flow<Set<String>> = flow {
        // The caller has verified these actual Room UUIDs under its existing account/publication proof.
        prepareHabitPreferences(session, habitUuids)
        emitAll(dataStore.data.map { prefs ->
            if (!hasHabitPreferenceSession(prefs, session)) emptySet()
            else habitUuids.filterTo(mutableSetOf()) { uuid ->
                val record = habitPreference(prefs, session, uuid)
                record.pending && record.pendingEpoch == session.syncEpoch
            }
        }.distinctUntilChanged())
    }

    /**
     * Add a habit to the pending metric prompts set.
     * Called when a timer completes and the habit has linked metrics with promptOnComplete=true.
     *
     * @param habitUuid The original Room-verified UUID that needs metric recording
     */
    internal suspend fun addPendingMetricHabit(session: LocalDataSession, habitUuid: String) =
        editHabitPreference(session, habitUuid) { it.copy(pending = true, pendingEpoch = session.syncEpoch) }

    /**
     * Remove a habit from the pending metric prompts set.
     * Called when the user records the metric or starts a new timer.
     *
     * @param habitUuid The original Room-verified UUID to remove
     */
    internal suspend fun removePendingMetricHabit(session: LocalDataSession, habitUuid: String) =
        editHabitPreference(session, habitUuid) { it.copy(pending = false, pendingEpoch = null) }

    internal val habitPreferenceChanges: Flow<Unit> = dataStore.data.map { Unit }

    private fun hasHabitPreferenceSession(values: Preferences, session: LocalDataSession): Boolean = try {
        requireNotNull(tokens) { "HABIT_PREFERENCE_TOKENS_REQUIRED" }.requireHabitPreferenceSession(values, session); true
    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
    catch (_: IllegalStateException) { false }
    catch (_: IllegalArgumentException) { false }

    private fun habitPreference(values: Preferences, session: LocalDataSession, uuid: String): HabitDevicePreferences {
        val binding = StableHabitBinding.capture(session, uuid)
        return HabitDevicePreferences.decode(values[HabitDevicePreferences.key(binding)])
            ?.takeIf { it.binding == binding } ?: HabitDevicePreferences(binding)
    }

    /** Never acquires AccountSessionCoordinator: callers may already hold its non-reentrant lock. */
    private suspend fun prepareHabitPreferences(session: LocalDataSession, uuids: Set<String>) {
        editOrdinary { values ->
            requireNotNull(tokens) { "HABIT_PREFERENCE_TOKENS_REQUIRED" }.requireHabitPreferenceSession(values, session)
            uuids.forEach { uuid -> promoteHabitPreference(values, session, uuid) }
        }
    }

    private fun promoteHabitPreference(values: MutablePreferences, session: LocalDataSession, uuid: String) {
        if (session.serverInstanceId == null) return
        val binding = StableHabitBinding.capture(session, uuid)
        val key = HabitDevicePreferences.key(binding)
        if (values[key] != null) return
        val provisional = StableHabitBinding.capture(session.copy(serverInstanceId = null, syncEpoch = null), uuid)
        val oldKey = HabitDevicePreferences.key(provisional)
        val oldRaw = values[oldKey] ?: return
        val old = HabitDevicePreferences.decode(oldRaw) ?: return
        if (old.binding != provisional || old.binding.resolve(session, uuid) != binding) return
        // One actor snapshot is the original-value CAS. No value from a previous read is adopted.
        check(values[oldKey] == oldRaw && values[key] == null)
        values[key] = old.copy(binding = binding, pendingEpoch = session.syncEpoch.takeIf { old.pending }).encode()
        values.remove(oldKey)
    }

    private fun habitPreference(session: LocalDataSession, uuid: String): Flow<HabitDevicePreferences> = flow {
        prepareHabitPreferences(session, setOf(uuid))
        emitAll(dataStore.data.map { values ->
            if (hasHabitPreferenceSession(values, session)) habitPreference(values, session, uuid)
            else HabitDevicePreferences(StableHabitBinding.capture(session, uuid))
        }.distinctUntilChanged())
    }

    private suspend fun editHabitPreference(session: LocalDataSession, uuid: String,
        change: (HabitDevicePreferences) -> HabitDevicePreferences) {
        editOrdinary { values ->
            requireNotNull(tokens) { "HABIT_PREFERENCE_TOKENS_REQUIRED" }.requireHabitPreferenceSession(values, session)
            promoteHabitPreference(values, session, uuid)
            val current = habitPreference(values, session, uuid)
            values[HabitDevicePreferences.key(current.binding)] = change(current).encode()
        }
    }

    /**
     * Clear all pending metric prompts.
     * Called on logout or when resetting app state.
     */
    suspend fun clearPendingMetricHabits() {
        editOrdinary { prefs ->
            prefs.remove(PENDING_METRIC_HABITS_KEY)
            prefs.asMap().keys.filter { it.name.startsWith(HabitDevicePreferences.PREFIX) }.forEach { rawKey ->
                val key = stringPreferencesKey(rawKey.name)
                HabitDevicePreferences.decode(prefs[key])?.let { value ->
                    prefs[key] = value.copy(pending = false, pendingEpoch = null).encode()
                }
            }
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
        editOrdinary { preferences ->
            preferences.asMap().keys
                .filter { key ->
                    key.name.startsWith("never_ask_metric_") ||
                        key.name.startsWith("habit_notification_") ||
                        key.name.startsWith(HabitDevicePreferences.PREFIX)
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
        editOrdinary { prefs ->
            prefs[EXPANDED_PARENT_UUIDS_KEY] = uuids.joinToString(",")
        }
    }

    // ========== Date Change Refresh Management ==========

    /**
     * Refreshes after persisted date changes or foreground calendar/clock/zone invalidation.
     *
     * Keeps the legacy epoch-day value; equal emissions can signal a zone/clock change.
     * Consumers must recalculate on every emission, not distinctUntilChanged this trigger.
     */
    val dateChangeTrigger: Flow<Long> = dataStore.data.map { prefs ->
        prefs[LAST_SEEN_DATE_KEY] ?: 0L
    }.combine(calendar.changes) { date, _ -> date }

    /**
     * Updates the last seen date to today.
     * Returns true if the date changed (indicating a new day).
     *
     * @return true if date changed, false if same day
     */
    suspend fun updateLastSeenDate(todayEpochDays: Long): Boolean {
        var dateChanged = false
        editOrdinary { prefs ->
            val lastSeen = prefs[LAST_SEEN_DATE_KEY] ?: 0L
            if (lastSeen != todayEpochDays) {
                dateChanged = true
                prefs[LAST_SEEN_DATE_KEY] = todayEpochDays
            }
        }
        return dateChanged
    }
}
