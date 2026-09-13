package com.dayforge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.dayforge.data.local.DataStoreProvider
import com.dayforge.data.local.PreferencesManager
import com.dayforge.reminder.HabitReminderReceiver
import com.dayforge.reminder.HabitReminderScheduler
import com.dayforge.sync.AutoSyncCoordinator
import com.dayforge.widget.WidgetRefreshScheduler
import com.dayforge.widget.WidgetUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class DayForgeApplication : Application() {

    @Inject
    lateinit var autoSyncCoordinator: AutoSyncCoordinator

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Restore saved language preference before any Activity starts
        // This sets the Application locale so all Activities inherit it
        restoreLanguagePreference()

        // Schedule midnight widget updates
        WidgetUpdateWorker.schedule(this)

        // Observe durable changes and all network paths (including local-only Wi-Fi)
        // and let WorkManager provide retries across process restarts.
        autoSyncCoordinator.start()

        // Recover presentation state after a process restart; work reads current Room data.
        WidgetRefreshScheduler.request(this)

        // Create notification channel for habit reminders
        createReminderNotificationChannel()

        // Reschedule all habit reminders on app start (alarms are lost after device reboot)
        applicationScope.launch {
            try {
                HabitReminderScheduler.rescheduleAllReminders(this@DayForgeApplication)
            } catch (e: Exception) {
                // Log error but continue - reminders will be rescheduled next app start
            }
        }
    }

    /**
     * Creates the notification channel for habit reminders on Android 8+.
     * Per NOTIFY-02: Channel for reminder notifications with default importance.
     */
    private fun createReminderNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                HabitReminderReceiver.CHANNEL_ID,  // "habit_reminders"
                getString(R.string.notification_channel_habit_reminders),
                NotificationManager.IMPORTANCE_DEFAULT  // Makes sound, shows in status bar
            ).apply {
                description = getString(R.string.notification_channel_habit_reminders_desc)
                setShowBadge(true)  // Show badge on launcher icon
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Restores saved language preference from DataStore.
     * Called in Application.onCreate() before any Activity exists.
     *
     * IMPORTANT: We use DataStoreProvider directly (not Hilt injection) because
     * Hilt injection completes after super.onCreate(), but we need locale set before
     * the first Activity starts.
     *
     * On Android 13+: setApplicationLocales() is stored by system, but we restore
     * from DataStore for consistency across all Android versions.
     */
    private fun restoreLanguagePreference() {
        runBlocking {
            try {
                // Use DataStoreProvider directly - no Hilt dependency
                val dataStore = DataStoreProvider.get(this@DayForgeApplication)
                val preferencesManager = PreferencesManager(dataStore)
                val savedLanguageCode = preferencesManager.languageCode.first()

                // Apply saved locale to AppCompatDelegate
                // No Activity exists yet, so this won't trigger recreate
                // But it sets the Application locale for all future Activities
                val localeList = if (savedLanguageCode != null) {
                    LocaleListCompat.create(Locale.forLanguageTag(savedLanguageCode))
                } else {
                    LocaleListCompat.getEmptyLocaleList()
                }
                AppCompatDelegate.setApplicationLocales(localeList)
            } catch (e: Exception) {
                // Ignore errors during language restoration
            }
        }
    }

}
