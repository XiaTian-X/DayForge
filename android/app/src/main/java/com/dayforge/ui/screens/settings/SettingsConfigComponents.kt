package com.dayforge.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dayforge.R

/**
 * Card with export and import buttons.
 */
@Composable
internal fun ConfigManagementCard(
    exportProgress: Boolean,
    importProgress: Boolean,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit
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
            // Export button
            Button(
                onClick = onExportClick,
                enabled = !exportProgress && !importProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (exportProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.export_progress_message))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_export_config))
                }
            }

            // Import button
            OutlinedButton(
                onClick = onImportClick,
                enabled = !exportProgress && !importProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (importProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.import_progress_message))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_import_config))
                }
            }

            // Hint text
            Text(
                text = stringResource(R.string.config_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Progress dialog shown during export.
 */
@Composable
internal fun ExportProgressDialog() {
    AlertDialog(
        onDismissRequest = { /* Not dismissible */ },
        title = { Text(stringResource(R.string.export_progress_title)) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.export_progress_message))
            }
        },
        confirmButton = { /* No buttons - blocking */ }
    )
}

/**
 * Success dialog shown after export completes.
 */
@Composable
internal fun ExportSuccessDialog(
    filePath: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.export_success_title)) },
        text = {
            Column {
                Text(stringResource(R.string.export_success_saved_to))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

/**
 * Error dialog shown when export fails.
 */
@Composable
internal fun ExportErrorDialog(
    message: String,
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
        title = { Text(stringResource(R.string.export_error)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}

/**
 * Progress dialog shown during import.
 */
@Composable
internal fun ImportProgressDialog() {
    AlertDialog(
        onDismissRequest = { /* Not dismissible */ },
        title = { Text(stringResource(R.string.import_progress_title)) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.import_progress_message))
            }
        },
        confirmButton = { /* No buttons - blocking */ }
    )
}

/**
 * Confirmation dialog before import.
 * Shows counts of data that will be replaced.
 */
@Composable
internal fun ImportConfirmDialog(
    data: SettingsImportConfirmData,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.import_confirm_title)) },
        text = {
            Column {
                // Deletion section
                Text(
                    text = stringResource(R.string.import_confirm_delete_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("• ${stringResource(R.string.import_item_habits, data.deleteHabitCount)}")
                Text("• ${stringResource(R.string.import_item_metrics, data.deleteMetricCount)}")
                if (data.deleteCompletionCount > 0) {
                    Text("• ${stringResource(R.string.import_item_completions, data.deleteCompletionCount)}")
                }
                if (data.deleteTimeLogCount > 0) {
                    Text("• ${stringResource(R.string.import_item_timelogs, data.deleteTimeLogCount)}")
                }
                if (data.deleteMetricLogCount > 0) {
                    Text("• ${stringResource(R.string.import_item_metriclogs, data.deleteMetricLogCount)}")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Import section
                Text(
                    text = stringResource(R.string.import_confirm_import_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("• ${stringResource(R.string.import_item_habits, data.importHabitCount)}")
                Text("• ${stringResource(R.string.import_item_metrics, data.importMetricCount)}")
                Text("• ${stringResource(R.string.import_item_links, data.importLinkCount)}")

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.import_confirm_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.import_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Success dialog shown after import completes.
 */
@Composable
internal fun ImportSuccessDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.import_success_title)) },
        text = {
            Column {
                Text(stringResource(R.string.import_success_message))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.import_success_sync_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

/**
 * Error dialog shown when import fails.
 */
@Composable
internal fun ImportErrorDialog(
    message: String,
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
        title = { Text(stringResource(R.string.import_error)) },
        text = {
            Column {
                Text(message)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.import_error_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
