package com.dayforge.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.data.repository.ConfigImportPhase
import com.dayforge.data.repository.ConfigReplacementBlocker

@Composable
internal fun ConfigV2Status(state: ConfigV2UiState, recover: () -> Unit, refresh: () -> Unit) {
    if (state.profile == ConfigFileProfile.LEGACY) return
    Column(Modifier.fillMaxWidth().testTag("config-file-status")) {
        if (state.profile == ConfigFileProfile.LOADING) Text(stringResource(R.string.config_file_loading))
        else if (state.profile == ConfigFileProfile.UNAVAILABLE) Text(stringResource(R.string.config_file_unavailable))
        else {
            Text(stringResource(R.string.config_file_v2_hint), style = MaterialTheme.typography.bodySmall)
            state.progress?.let { progress ->
                Text(stringResource(when (progress.phase) {
                    ConfigImportPhase.PREPARED -> R.string.config_file_prepared
                    ConfigImportPhase.LOCAL_COMMITTED -> R.string.config_file_waiting
                    ConfigImportPhase.OPERATIONS_ACCEPTED -> R.string.config_file_accepted
                }), Modifier.testTag("config-file-phase"))
                if (progress.phase != ConfigImportPhase.PREPARED) Text(stringResource(R.string.config_file_receipts,
                    progress.accepted, progress.total))
                if (progress.phase == ConfigImportPhase.PREPARED) TextButton(onClick = recover,
                    enabled = !state.busy && !state.pickerPending && state.preview == null,
                    modifier = Modifier.testTag("config-file-recover")) { Text(stringResource(R.string.config_file_recover)) }
            }
        }
        TextButton(onClick = refresh, enabled = !state.busy) { Text(stringResource(R.string.config_file_refresh)) }
    }
}

@Composable
internal fun ConfigV2Dialogs(state: ConfigV2UiState, confirm: () -> Unit, dismiss: () -> Unit,
    abandon: () -> Unit, dismissMessage: () -> Unit) {
    state.preview?.let { preview ->
        AlertDialog(onDismissRequest = { if (!state.busy) dismiss() },
            modifier = Modifier.testTag("config-file-preview"),
            title = { Text(stringResource(if (preview.recovery) R.string.config_file_recovery_title else R.string.import_confirm_title)) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.config_file_incoming, preview.nodes, preview.metrics, preview.links, preview.assets))
                Text(stringResource(R.string.config_file_no_history))
                if (preview.unresolvedRoles > 0) Text(stringResource(R.string.config_file_unresolved, preview.unresolvedRoles))
                if (preview.themes > 0) Text(stringResource(R.string.config_file_themes_not_installed, preview.themes))
                preview.removed?.let { old ->
                    Text(stringResource(R.string.config_file_removed, old.goals, old.habits, old.items, old.metrics, old.links),
                        color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.config_file_removed_history, old.checkAndCountRecords, old.timerSessions,
                        old.metricRecords), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.config_file_destructive))
                }
                for (blocker in preview.blockers.sortedBy { it.ordinal }) Text(stringResource(when (blocker) {
                    ConfigReplacementBlocker.ACTIVE_TIMER -> R.string.config_file_block_timer
                    ConfigReplacementBlocker.PENDING_WORK -> R.string.config_file_block_pending
                    ConfigReplacementBlocker.CONFLICT_OR_RECOVERY -> R.string.config_file_block_conflict
                    ConfigReplacementBlocker.COMPLETION_PROMPT -> R.string.config_file_block_prompt
                    ConfigReplacementBlocker.UNCONFIRMED_REPLICA -> R.string.config_file_block_replica
                    ConfigReplacementBlocker.UNCONFIRMED_STRUCTURE -> R.string.config_file_block_structure
                    ConfigReplacementBlocker.QUEUE_CAPACITY -> R.string.config_file_block_capacity
                }), color = MaterialTheme.colorScheme.error)
                if (preview.canAbandon) TextButton(onClick = abandon, enabled = !state.busy,
                    modifier = Modifier.testTag("config-file-abandon")) { Text(stringResource(R.string.config_file_abandon)) }
            } },
            confirmButton = { TextButton(onClick = confirm, enabled = !state.busy && preview.blockers.isEmpty(),
                modifier = Modifier.testTag("config-file-confirm")) { Text(stringResource(R.string.action_confirm)) } },
            dismissButton = { TextButton(onClick = dismiss, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) } })
    }
    if (state.busy && state.preview == null) ImportProgressDialog()
    state.message?.let { message ->
        AlertDialog(onDismissRequest = dismissMessage, modifier = Modifier.testTag("config-file-result"),
            title = { Text(stringResource(if (message == ConfigFileMessage.FAILED) R.string.config_file_error else R.string.settings_config_section)) },
            text = { Text(stringResource(when (message) {
                ConfigFileMessage.EXPORTED -> R.string.config_file_exported
                ConfigFileMessage.LOCAL_COMMITTED -> R.string.config_file_local_committed
                ConfigFileMessage.ABANDONED -> R.string.config_file_abandoned
                ConfigFileMessage.FAILED -> R.string.config_file_failed
            })) }, confirmButton = { TextButton(onClick = dismissMessage) { Text(stringResource(R.string.action_confirm)) } })
    }
}
