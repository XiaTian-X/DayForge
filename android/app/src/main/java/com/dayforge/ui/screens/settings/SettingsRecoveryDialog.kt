package com.dayforge.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.data.repository.NextRecoveryBlockReason
import com.dayforge.data.repository.NextRecoveryDependencyReason
import com.dayforge.data.repository.NextRecoveryRequestState

/** Same settings dialog surface on phones/tablets; no new top-level navigation or business writes. */
@Composable
internal fun RecoveryConfirmationDialog(state: RecoveryUiState, onDismiss: () -> Unit,
    onRefresh: () -> Unit, onApprove: (Boolean) -> Unit, onConfirm: () -> Unit) {
    val review = when (state) {
        is RecoveryUiState.Review -> state.data
        is RecoveryUiState.Committing -> state.data
        is RecoveryUiState.RetryConfirmation -> state.data
        else -> null
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.sync_recovery_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("recovery-details"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(when (state) {
                        RecoveryUiState.Loading -> R.string.sync_recovery_loading
                        RecoveryUiState.Stale -> R.string.sync_recovery_stale
                        RecoveryUiState.Unavailable -> R.string.sync_recovery_unavailable
                        is RecoveryUiState.Committing -> R.string.sync_recovery_committing
                        is RecoveryUiState.RetryConfirmation -> R.string.sync_recovery_retry_message
                        is RecoveryUiState.Completed -> R.string.sync_recovery_completed
                        else -> R.string.sync_recovery_explanation
                    }))
                }
                if (state is RecoveryUiState.Completed) item {
                    Text(pluralStringResource(R.plurals.sync_recovery_completed_count, state.count, state.count))
                    Text(state.recoveryId, style = MaterialTheme.typography.bodySmall)
                }
                if (review != null) {
                    item { Text(stringResource(R.string.sync_recovery_root, review.graph.root.requestId)) }
                    item { Text(pluralStringResource(R.plurals.sync_recovery_affected, review.graph.affected.size, review.graph.affected.size)) }
                    items(review.graph.affected, key = { "request:${it.key.kind}:${it.key.requestId}" }) { node ->
                        Column(Modifier.testTag("recovery-request-${node.key.requestId}")) {
                            Text("${node.entityType} · ${node.entityUuid}", style = MaterialTheme.typography.bodySmall)
                            Text(node.key.requestId, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(when (node.state) {
                                NextRecoveryRequestState.PERMANENT_REJECTION -> R.string.sync_recovery_state_rejected
                                NextRecoveryRequestState.NEVER_SENT -> R.string.sync_recovery_state_never_sent
                                NextRecoveryRequestState.UNKNOWN_RESULT -> R.string.sync_recovery_state_unknown
                                NextRecoveryRequestState.ACCEPTED -> R.string.sync_recovery_state_accepted
                                else -> R.string.sync_recovery_state_retired
                            }))
                            for (reason in review.dependencyReasons[node.key].orEmpty()) RecoveryDependencyText(reason)
                        }
                    }
                    items(review.graph.blockers, key = { "blocker:${it.key.kind}:${it.key.requestId}:${it.reason}" }) { blocker ->
                        Text("${blocker.key.requestId}\n" + stringResource(when (blocker.reason) {
                            NextRecoveryBlockReason.ROOT_NOT_PERMANENT_REJECTION -> R.string.sync_recovery_block_root
                            NextRecoveryBlockReason.UNSUPPORTED_CATEGORY -> R.string.sync_recovery_block_category
                            NextRecoveryBlockReason.UNKNOWN_RESULT -> R.string.sync_recovery_block_unknown
                            NextRecoveryBlockReason.ACCEPTED_DATA -> R.string.sync_recovery_block_accepted
                            NextRecoveryBlockReason.PROTECTED_LOCAL_DATA -> R.string.sync_recovery_block_protected
                            NextRecoveryBlockReason.RETIRED_DEPENDENCY -> R.string.sync_recovery_block_retired
                        }))
                    }
                    if (review.preparationFailed) item { Text(stringResource(R.string.sync_recovery_unavailable)) }
                    if (review.checkpoint != null) item {
                        Text(stringResource(R.string.sync_recovery_checkpoint, review.checkpoint, review.contractVersion ?: 1))
                        Text(stringResource(R.string.sync_recovery_baseline_warning), style = MaterialTheme.typography.bodySmall)
                    }
                    items(review.changes, key = { "projection:${it.type}:${it.uuid}" }) { change ->
                        Column(Modifier.testTag("recovery-change-${change.uuid}")) {
                            Text(change.name ?: "${change.type} · ${change.uuid}")
                            if (change.removed) Text(stringResource(R.string.sync_recovery_remove_local))
                            for (field in change.fields) {
                                Text(field.field, style = MaterialTheme.typography.labelMedium)
                                Text(stringResource(R.string.sync_recovery_field_before,
                                    field.before ?: stringResource(R.string.sync_recovery_absent)), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.sync_recovery_field_after,
                                    field.after ?: stringResource(R.string.sync_recovery_absent)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state is RecoveryUiState.Review && review.graph.canConfirm && !review.preparationFailed) item {
                        Row {
                            Checkbox(state.approved, onCheckedChange = onApprove, modifier = Modifier.testTag("recovery-approve"))
                            Text(pluralStringResource(R.plurals.sync_recovery_approve_all, review.graph.affected.size, review.graph.affected.size))
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is RecoveryUiState.Review -> if (state.data.graph.canConfirm && !state.data.preparationFailed) {
                    TextButton(onClick = onConfirm, enabled = state.approved, modifier = Modifier.testTag("recovery-confirm")) {
                        Text(stringResource(R.string.sync_recovery_confirm))
                    }
                }
                is RecoveryUiState.RetryConfirmation -> TextButton(onClick = onConfirm,
                    modifier = Modifier.testTag("recovery-retry-confirm")) { Text(stringResource(R.string.sync_recovery_retry)) }
                RecoveryUiState.Stale, RecoveryUiState.Unavailable -> TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.sync_recovery_refresh))
                }
                else -> Unit
            }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } })
}

@Composable
private fun RecoveryDependencyText(reason: NextRecoveryDependencyReason) {
    Text(stringResource(when (reason) {
        NextRecoveryDependencyReason.STRUCTURAL_PREDECESSOR -> R.string.sync_recovery_dependency_structure
        NextRecoveryDependencyReason.GOAL_CHILD_FRONTIER -> R.string.sync_recovery_dependency_goal
        NextRecoveryDependencyReason.TIMER_SEQUENCE, NextRecoveryDependencyReason.TIMER_POLICY -> R.string.sync_recovery_dependency_timer
        NextRecoveryDependencyReason.COUNT_POLICY, NextRecoveryDependencyReason.FIRST_COUNT_FACT -> R.string.sync_recovery_dependency_count
        NextRecoveryDependencyReason.ENTITY_REFERENCE, NextRecoveryDependencyReason.REVERT_TARGET -> R.string.sync_recovery_dependency_fact
        NextRecoveryDependencyReason.ONE_TIME_CAS -> R.string.sync_recovery_dependency_once
        NextRecoveryDependencyReason.RESTART_FRONTIER -> R.string.sync_recovery_dependency_restart
        NextRecoveryDependencyReason.IMPORT_PHASE -> R.string.sync_recovery_dependency_import
    }), style = MaterialTheme.typography.bodySmall)
}
