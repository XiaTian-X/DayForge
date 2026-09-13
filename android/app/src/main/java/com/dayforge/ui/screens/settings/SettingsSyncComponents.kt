package com.dayforge.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.model.SyncProgress
import java.util.Locale

/**
 * Card displaying sync status with last sync time and sync button.
 */
@Composable
internal fun SyncStatusCard(
    isOnline: Boolean,
    isLoggedIn: Boolean,
    lastSyncTime: String,
    activeServerUrl: String?,
    syncProgress: SyncProgress,
    canEditStructure: Boolean,
    isPrimaryEditor: Boolean,
    rejectedCount: Int,
    onRejectedClick: () -> Unit,
    onSyncClick: () -> Unit,
    onMakePrimaryClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Sync status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status icon
                val (icon, iconColor) = when {
                    !isOnline -> Icons.Rounded.CloudOff to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    syncProgress is SyncProgress.Success -> Icons.Rounded.CloudDone to MaterialTheme.colorScheme.primary
                    syncProgress.isRunning() ->
                        Icons.Rounded.CloudSync to MaterialTheme.colorScheme.tertiary
                    else -> Icons.Rounded.CloudDone to MaterialTheme.colorScheme.primary
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Last sync time
                Text(
                    text = stringResource(R.string.sync_last_time, lastSyncTime),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (!activeServerUrl.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.sync_current_endpoint, activeServerUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isLoggedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (canEditStructure) Icons.Rounded.Edit else Icons.Rounded.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            when {
                                isPrimaryEditor -> R.string.sync_device_role_primary
                                canEditStructure -> R.string.sync_device_role_editor
                                else -> R.string.sync_device_role_facts_only
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (!canEditStructure) {
                        TextButton(onClick = onMakePrimaryClick) {
                            Text(stringResource(R.string.sync_device_make_primary))
                        }
                    }
                }
            }

            // Sync button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSyncClick,
                    enabled = isLoggedIn && !syncProgress.isRunning(),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_now_button))
                }

                when {
                    !isLoggedIn -> {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.sync_need_login),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    !isOnline -> {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.sync_need_network),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (rejectedCount > 0) {
                TextButton(onClick = onRejectedClick) {
                    Icon(Icons.Rounded.Warning, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_rejected_count, rejectedCount))
                }
            }
        }
    }
}

internal fun SyncProgress.isRunning(): Boolean = when (this) {
    is SyncProgress.UploadingChanges,
    SyncProgress.Downloading,
    SyncProgress.Recovering -> true
    else -> false
}

@Composable
internal fun RejectedChangesDialog(
    changes: List<SyncOutboxEntity>,
    conflicts: List<SyncConflictEntity>,
    timerCommands: List<TimerCommandEntity>,
    onDismiss: () -> Unit,
    onRetry: (Long) -> Unit,
    onRetryTimerCommand: (Long) -> Unit,
    onCancelTimerAndUseServer: (TimerCommandEntity) -> Unit,
    onUseServer: (Long) -> Unit,
    onUseLocal: (Long) -> Unit,
    onDiscard: (SyncOutboxEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_rejected_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(conflicts, key = { "conflict-${it.id}" }) { conflict ->
                    Column {
                        Text(
                            stringResource(
                                R.string.sync_conflict_item,
                                conflict.recordType,
                                conflict.localEntityUuid.take(8)
                            )
                        )
                        Text(
                            conflict.message ?: stringResource(R.string.sync_conflict_default_message),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (conflict.conflictingFieldsJson != "[]") {
                            Text(
                                stringResource(
                                    R.string.sync_conflict_fields,
                                    conflict.conflictingFieldsJson
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row {
                            TextButton(onClick = { onUseServer(conflict.id) }) {
                                Text(stringResource(R.string.sync_conflict_use_server))
                            }
                            if (conflict.conflictKind != "deleted_conflict") {
                                TextButton(onClick = { onUseLocal(conflict.id) }) {
                                    Text(stringResource(R.string.sync_conflict_use_local))
                                }
                            }
                        }
                    }
                }
                items(changes, key = { it.id }) { change ->
                    Column {
                        Text("${change.recordType} · ${change.entityUuid.take(8)}")
                        Text(
                            change.lastError ?: stringResource(R.string.sync_rejected_unknown_error),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            TextButton(onClick = { onRetry(change.id) }) {
                                Text(stringResource(R.string.sync_rejected_retry))
                            }
                            TextButton(onClick = { onDiscard(change) }) {
                                Text(stringResource(R.string.sync_rejected_discard))
                            }
                        }
                    }
                }
                items(timerCommands, key = { "timer-${it.id}" }) { command ->
                    Column {
                        Text(
                            stringResource(
                                R.string.sync_rejected_timer_command,
                                command.commandType,
                                command.sessionUuid.take(8)
                            )
                        )
                        Text(
                            command.lastError
                                ?: stringResource(R.string.sync_rejected_unknown_error),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            TextButton(onClick = { onRetryTimerCommand(command.id) }) {
                                Text(stringResource(R.string.sync_rejected_retry))
                            }
                            TextButton(onClick = { onCancelTimerAndUseServer(command) }) {
                                Text(stringResource(R.string.sync_rejected_timer_use_server))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

/**
 * Account section showing login status and logout/login button.
 * Supports logged-in and logged-out states.
 */
@Composable
internal fun AccountSection(
    isLoggedIn: Boolean,
    isAdmin: Boolean,
    userEmail: String?,
    onLogoutClick: () -> Unit,
    onLoginClick: () -> Unit,
    onAdminClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Login status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isLoggedIn) Icons.Rounded.Person else Icons.Rounded.PersonOutline,
                    contentDescription = null,
                    tint = if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isLoggedIn) userEmail ?: stringResource(R.string.account_logged_in) else stringResource(R.string.account_not_logged),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // The backend is authoritative for the admin role.
            if (isLoggedIn && isAdmin) {
                OutlinedButton(
                    onClick = onAdminClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AdminPanelSettings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_admin_dashboard))
                }
            }

            // Login/Logout button
            if (isLoggedIn) {
                OutlinedButton(
                    onClick = onLogoutClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_logout))
                }
            } else {
                Button(
                    onClick = onLoginClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_login))
                }
            }
        }
    }
}

/**
 * Blocking progress dialog shown during sync operation.
 */
@Composable
internal fun SyncProgressDialog(progress: SyncProgress) {
    AlertDialog(
        onDismissRequest = { /* Not dismissible */ },
        title = { Text(stringResource(R.string.sync_in_progress_status)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )

                val statusText = when (progress) {
                    is SyncProgress.UploadingChanges -> stringResource(R.string.sync_uploading_changes, progress.current, progress.total)
                    is SyncProgress.Downloading -> stringResource(R.string.sync_downloading)
                    is SyncProgress.Recovering -> stringResource(R.string.sync_recovering)
                    else -> stringResource(R.string.sync_in_progress_status)
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { /* No buttons - blocking */ }
    )
}

/**
 * Error dialog shown when sync fails.
 */
@Composable
internal fun SyncErrorDialog(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.sync_error)) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Confirmation dialog shown when user tries to logout.
 * Offers sync-before-logout option for logged-in users.
 */
@Composable
internal fun LogoutSyncConfirmationDialog(
    isLoggedIn: Boolean,
    isOnline: Boolean,
    isLoggingOut: Boolean,
    onSyncAndLogout: () -> Unit,
    onDirectLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.logout_confirm_title)) },
        text = {
            if (isLoggingOut) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_in_progress_status))
                }
            } else if (isLoggedIn && isOnline) {
                Text(stringResource(R.string.logout_confirm_sync_message))
            } else {
                Text(stringResource(R.string.logout_confirm_direct_message))
            }
        },
        confirmButton = {
            if (isLoggingOut) {
                // No buttons during logout
            } else if (isLoggedIn && isOnline) {
                // Primary action: Sync then logout
                Button(onClick = onSyncAndLogout) {
                    Text(stringResource(R.string.logout_sync_then_logout))
                }
            } else {
                // Not logged in or offline: just logout
                TextButton(
                    onClick = onDirectLogout,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        },
        dismissButton = {
            if (isLoggingOut) {
                // No dismiss during logout
            } else if (isLoggedIn && isOnline) {
                // Two options: Direct logout and Cancel
                Row {
                    TextButton(
                        onClick = onDirectLogout,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.logout_direct))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

/**
 * Blocks account-destructive actions and configuration import while a timer is active.
 * Ordinary manual and automatic synchronization remains available during timing.
 */
@Composable
internal fun ActiveTimerDialog(
    habitName: String,
    durationSeconds: Int,
    onDismiss: () -> Unit
) {
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    val durationText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.timer_active_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.timer_active_dialog_habit_running, habitName))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.timer_active_dialog_elapsed, durationText),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.timer_active_dialog_complete_first),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}
