package com.dayforge.ui.screens.permission

import android.Manifest
import android.app.AlarmManager
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayforge.R
import com.dayforge.ui.screens.permission.PermissionViewModel.PermissionStatus

/**
 * Permission management screen displaying various app permissions.
 * Uses extensible PermissionItem architecture for adding future permissions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    viewModel: PermissionViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val notificationStatus by viewModel.notificationPermissionStatus.collectAsStateWithLifecycle()
    val exactAlarmStatus by viewModel.exactAlarmPermissionStatus.collectAsStateWithLifecycle()
    val reminderStatus by viewModel.reminderPermissionStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission launcher for Android 13+ (TIRAMISU)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.refreshNotificationPermissionStatus()
    }

    // Lifecycle observer to refresh permission status when returning from settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshNotificationPermissionStatus()
                viewModel.refreshExactAlarmPermissionStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Section title
            item {
                Text(
                    text = stringResource(R.string.permission_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // Notification permission item
            item {
                // Determine action button based on permission status and permanent denial
                val activity = context as? Activity
                val actionButton: Pair<String, () -> Unit>? = if (notificationStatus is PermissionStatus.Denied) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Check if permanently denied (user checked "Don't ask again")
                        val shouldShowRationale = activity != null &&
                            ActivityCompat.shouldShowRequestPermissionRationale(
                                activity, Manifest.permission.POST_NOTIFICATIONS
                            )

                        if (shouldShowRationale) {
                            // First denial - can still request again
                            Pair(stringResource(R.string.permission_request)) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else {
                            // Permanent denial - must open settings
                            Pair(stringResource(R.string.permission_open_settings)) {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = "package:${context.packageName}".toUri()
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // Fallback if settings cannot be opened
                                }
                            }
                        }
                    } else {
                        null // Android 12- does not need runtime permission request
                    }
                } else {
                    null // Granted or NotRequired - no action needed
                }

                PermissionItem(
                    title = stringResource(R.string.permission_notification_title),
                    description = stringResource(R.string.permission_notification_desc),
                    status = notificationStatus,
                    icon = Icons.Rounded.Notifications,
                    actionButton = actionButton
                )
            }

            // Exact alarm permission item (SYS-03)
            item {
                val exactAlarmActionButton: Pair<String, () -> Unit>? = if (exactAlarmStatus is PermissionStatus.Denied) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Pair(stringResource(R.string.permission_open_settings)) {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Fallback to app settings
                            }
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }

                PermissionItem(
                    title = stringResource(R.string.permission_exact_alarm_title),
                    description = stringResource(R.string.permission_exact_alarm_desc),
                    status = exactAlarmStatus,
                    icon = Icons.Rounded.Schedule,
                    actionButton = exactAlarmActionButton
                )
            }

            // Reminder permission item (combined status for NOTIFY-03)
            item {
                // Determine which action to show based on denied permissions
                val reminderActionButton: Pair<String, () -> Unit>? = when {
                    reminderStatus is PermissionStatus.Denied -> {
                        // Determine which permission is denied and open appropriate settings
                        val notificationDenied = notificationStatus is PermissionStatus.Denied
                        val alarmDenied = exactAlarmStatus is PermissionStatus.Denied

                        when {
                            notificationDenied && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                                // Notification permission denied - request it
                                Pair(stringResource(R.string.permission_request)) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            alarmDenied && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                                // Exact alarm permission denied - open settings
                                Pair(stringResource(R.string.permission_open_settings)) {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                            data = "package:${context.packageName}".toUri()
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        // Fallback
                                    }
                                }
                            }
                            else -> null // Older Android version or already handled
                        }
                    }
                    else -> null // Granted or NotRequired
                }

                PermissionItem(
                    title = stringResource(R.string.permission_reminder_title),
                    description = stringResource(R.string.permission_reminder_desc),
                    status = reminderStatus,
                    icon = Icons.Rounded.Notifications,
                    actionButton = reminderActionButton
                )
            }
        }
    }
}

/**
 * Extensible permission item composable.
 * Displays permission status with icon, title, description, and status text.
 *
 * @param title Permission title
 * @param description Permission description
 * @param status Current permission status (Granted, Denied, NotRequired)
 * @param icon Icon representing the permission type
 * @param actionButton Optional action button (buttonText, onClick) - shown only when status is Denied
 */
@Composable
fun PermissionItem(
    title: String,
    description: String,
    status: PermissionStatus,
    icon: ImageVector,
    actionButton: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Icon with status-based tint
            val iconTint = when (status) {
                is PermissionStatus.Granted -> MaterialTheme.colorScheme.primary
                is PermissionStatus.Denied -> MaterialTheme.colorScheme.error
                is PermissionStatus.NotRequired -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Title, description, and status
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Status text
                val statusText = when (status) {
                    is PermissionStatus.Granted -> stringResource(R.string.permission_status_granted)
                    is PermissionStatus.Denied -> stringResource(R.string.permission_status_denied)
                    is PermissionStatus.NotRequired -> stringResource(R.string.permission_status_not_required)
                }

                val statusColor = when (status) {
                    is PermissionStatus.Granted -> MaterialTheme.colorScheme.primary
                    is PermissionStatus.Denied -> MaterialTheme.colorScheme.error
                    is PermissionStatus.NotRequired -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )

                    if (status is PermissionStatus.NotRequired) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.permission_status_not_required_reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }
                }
            }
        }

        // Action button if provided and status is Denied
        if (actionButton != null && status is PermissionStatus.Denied) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = actionButton.second,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(actionButton.first)
            }
        }
        }
    }
}
