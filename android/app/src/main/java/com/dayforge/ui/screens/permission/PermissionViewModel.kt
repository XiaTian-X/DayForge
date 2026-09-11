package com.dayforge.ui.screens.permission

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * ViewModel for PermissionScreen.
 * Manages permission status state for various app permissions.
 */
@HiltViewModel
class PermissionViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * Sealed class representing permission status states.
     */
    sealed class PermissionStatus {
        data object Granted : PermissionStatus()
        data object Denied : PermissionStatus()
        data class NotRequired(val reason: String) : PermissionStatus()
    }

    private val _notificationPermissionStatus = MutableStateFlow<PermissionStatus>(getNotificationPermissionStatus())
    val notificationPermissionStatus: StateFlow<PermissionStatus> = _notificationPermissionStatus.asStateFlow()

    // Exact alarm permission status (SYS-03)
    private val _exactAlarmPermissionStatus = MutableStateFlow<PermissionStatus>(getExactAlarmPermissionStatus())
    val exactAlarmPermissionStatus: StateFlow<PermissionStatus> = _exactAlarmPermissionStatus.asStateFlow()

    // Combined reminder permission status (NOTIFY-03)
    // Reminder notifications require BOTH POST_NOTIFICATIONS and SCHEDULE_EXACT_ALARM
    private val _reminderPermissionStatus = MutableStateFlow<PermissionStatus>(
        getCombinedReminderPermissionStatus()
    )
    val reminderPermissionStatus: StateFlow<PermissionStatus> = _reminderPermissionStatus.asStateFlow()

    /**
     * Gets the notification permission status.
     * For Android 13+ (TIRAMISU), checks POST_NOTIFICATIONS runtime permission.
     * For Android 12 and below, checks if notifications are enabled via NotificationManagerCompat.
     */
    private fun getNotificationPermissionStatus(): PermissionStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires runtime permission
            val permissionResult = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            return when (permissionResult) {
                android.content.pm.PackageManager.PERMISSION_GRANTED -> PermissionStatus.Granted
                else -> PermissionStatus.Denied
            }
        } else {
            // Android 12 and below - notifications are enabled by default
            // but user can disable them in app settings
            val areEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            return if (areEnabled) {
                PermissionStatus.NotRequired("Android 12 and below")
            } else {
                PermissionStatus.Denied
            }
        }
    }

    /**
     * Refreshes the notification permission status.
     * Call this when returning from permission request or settings.
     */
    fun refreshNotificationPermissionStatus() {
        _notificationPermissionStatus.value = getNotificationPermissionStatus()
        _reminderPermissionStatus.value = getCombinedReminderPermissionStatus()
    }

    /**
     * Gets the exact alarm permission status (SYS-03).
     * For Android 12+ (S), checks SCHEDULE_EXACT_ALARM permission.
     * For Android 11 and below, exact alarms are allowed by default.
     */
    private fun getExactAlarmPermissionStatus(): PermissionStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires SCHEDULE_EXACT_ALARM permission
            val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
            val canScheduleExact = alarmManager.canScheduleExactAlarms()
            return if (canScheduleExact) {
                PermissionStatus.Granted
            } else {
                PermissionStatus.Denied
            }
        } else {
            // Android 11 and below - exact alarms allowed by default
            return PermissionStatus.NotRequired("Android 11 and below")
        }
    }

    /**
     * Gets the combined reminder permission status (NOTIFY-03).
     * Reminder notifications require BOTH POST_NOTIFICATIONS and SCHEDULE_EXACT_ALARM.
     * Combined status: Granted if both granted, Denied if either denied, NotRequired if both not required.
     */
    private fun getCombinedReminderPermissionStatus(): PermissionStatus {
        val notificationStatus = getNotificationPermissionStatus()
        val alarmStatus = getExactAlarmPermissionStatus()

        // Both must be granted for full functionality
        return when {
            notificationStatus is PermissionStatus.Denied -> PermissionStatus.Denied
            alarmStatus is PermissionStatus.Denied -> PermissionStatus.Denied
            notificationStatus is PermissionStatus.Granted && alarmStatus is PermissionStatus.Granted -> PermissionStatus.Granted
            notificationStatus is PermissionStatus.NotRequired && alarmStatus is PermissionStatus.NotRequired -> PermissionStatus.NotRequired("Not required on this device")
            else -> PermissionStatus.Denied  // Partial grant = limited functionality
        }
    }

    /**
     * Refreshes the exact alarm permission status.
     * Call this when returning from settings.
     */
    fun refreshExactAlarmPermissionStatus() {
        _exactAlarmPermissionStatus.value = getExactAlarmPermissionStatus()
        _reminderPermissionStatus.value = getCombinedReminderPermissionStatus()
    }

    /**
     * Refreshes the combined reminder permission status.
     * Call this when returning from permission request or settings.
     */
    fun refreshReminderPermissionStatus() {
        _notificationPermissionStatus.value = getNotificationPermissionStatus()
        _exactAlarmPermissionStatus.value = getExactAlarmPermissionStatus()
        _reminderPermissionStatus.value = getCombinedReminderPermissionStatus()
    }
}